package io.chatbots.grammar.service;

import io.chatbots.grammar.ai.AiProcessingException;
import io.chatbots.grammar.ai.AiRequest;
import io.chatbots.grammar.ai.AiResult;
import io.chatbots.grammar.ai.TextAiService;
import io.chatbots.grammar.config.AppProperties;
import io.chatbots.grammar.domain.ChatUser;
import io.chatbots.grammar.domain.Language;
import io.chatbots.grammar.domain.Mode;
import io.chatbots.grammar.domain.TextAction;
import io.chatbots.grammar.domain.TextEntry;
import io.chatbots.grammar.domain.TextEntryRepository;
import io.chatbots.grammar.domain.TextStatus;
import io.chatbots.grammar.domain.Tone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.text.BreakIterator;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Orchestrates text processing. Model calls deliberately run outside database transactions — they take
 * seconds, and holding a connection that long would starve the pool under load.
 */
@Service
public class TextService {

    private static final Logger log = LoggerFactory.getLogger(TextService.class);

    /** Below this, a single sentence has nothing left to cut. */
    static final int MIN_WORDS_TO_SHORTEN = 3;
    static final int MAX_OWN_LANGUAGES = 3;
    static final int QUICK_LANGUAGES = 2;

    /** Result of an operation on an existing entry; {@code unchanged} means the visible text is the same. */
    public record Outcome(TextEntry entry, boolean unchanged) {
    }

    private final TextEntryRepository repository;
    private final TextAiService ai;
    private final RateLimitService rateLimit;
    private final AppProperties properties;
    private final Clock clock;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public TextService(TextEntryRepository repository, TextAiService ai, RateLimitService rateLimit,
                       AppProperties properties, Clock clock) {
        this.repository = repository;
        this.ai = ai;
        this.rateLimit = rateLimit;
        this.properties = properties;
        this.clock = clock;
    }

    /** Processes a new text with the user's settings; failures surface as {@link ProcessingFailedException}. */
    public TextEntry process(ChatUser user, String text, Integer sourceMessageId) {
        var source = validate(text);
        rateLimit.consume(user.getChatId());
        var now = LocalDateTime.now(clock);
        var entry = new TextEntry();
        entry.setChatUser(user);
        entry.setSourceText(source);
        entry.setSourceMessageId(sourceMessageId);
        entry.setMode(user.getMode());
        entry.setTargetLanguage(user.getTargetLanguage());
        entry.setTone(user.getTone());
        entry.setCreatedAt(now);
        entry.setUpdatedAt(now);
        return run(save(entry), null, "MESSAGE", ownLanguages(user));
    }

    /** Another wording of the same result; retries the call if the previous attempt failed. */
    public Outcome regenerate(TextEntry entry) {
        rateLimit.consume(entry.getChatUser().getChatId());
        var previous = entry.getStatus() == TextStatus.DONE ? entry.getResultText() : null;
        var updated = run(entry, previous, "REGENERATE", List.of());
        return new Outcome(updated, Objects.equals(previous, updated.getResultText()));
    }

    /** Applies a one-off style. Tapping the style that is already active returns to Natural. */
    public Outcome restyle(TextEntry entry, Tone tone) {
        var newTone = entry.getTone() == tone ? Tone.NATURAL : tone;
        rateLimit.consume(entry.getChatUser().getChatId());
        var previous = entry.getResultText();
        entry.setTone(newTone);
        var updated = run(entry, null, "RESTYLE", List.of());
        return new Outcome(updated, Objects.equals(previous, updated.getResultText()));
    }

    /** Re-renders the text in another language. Picking a language explicitly always means "translate". */
    public Outcome retarget(TextEntry entry, Language language) {
        rateLimit.consume(entry.getChatUser().getChatId());
        var previous = entry.getResultText();
        entry.setTargetLanguage(language);
        if (entry.getMode() == Mode.FIX) entry.setMode(Mode.SMART);
        var updated = run(entry, null, "RETARGET", List.of());
        return new Outcome(updated, Objects.equals(previous, updated.getResultText()));
    }

    /**
     * Cuts the current result down, one step per tap: a sentence at a time, then words of the last sentence.
     * Works on the result rather than the source, so taps add up; the text language, fixes and explanations stay.
     */
    public Outcome shorten(TextEntry entry) {
        if (!canShorten(entry)) return new Outcome(entry, true);
        rateLimit.consume(entry.getChatUser().getChatId());
        var previous = entry.getResultText();
        var shortening = sentenceCount(previous, entry.getTargetLanguage()) > 1
            ? AiRequest.Shortening.SENTENCE : AiRequest.Shortening.WORDS;
        var request = new AiRequest(previous, Mode.FIX, entry.getTargetLanguage(), entry.getTone(),
            I18n.asLanguage(entry.getChatUser().getUiLanguage()), null, shortening);
        try {
            var result = ai.process(entry.getChatUser().getChatId(), "SHORTEN", request);
            entry.setResultText(result.text());
        } catch (AiProcessingException e) {
            throw new ProcessingFailedException(entry, e);
        }
        entry.setShortest(shortening == AiRequest.Shortening.WORDS);
        entry.setVersion(entry.getVersion() + 1);
        entry.setUpdatedAt(LocalDateTime.now(clock));
        var updated = save(entry);
        return new Outcome(updated, Objects.equals(previous, updated.getResultText()));
    }

    /** "Shorter" is offered until the text is down to part of a single sentence. */
    public static boolean canShorten(TextEntry entry) {
        var text = entry.getResultText();
        return entry.getStatus() == TextStatus.DONE && text != null && !entry.isShortest()
            && text.strip().split("\\s+").length >= MIN_WORDS_TO_SHORTEN;
    }

    static int sentenceCount(String text, Language language) {
        var sentences = BreakIterator.getSentenceInstance(Locale.forLanguageTag(language.code()));
        sentences.setText(text.strip());
        var count = 0;
        while (sentences.next() != BreakIterator.DONE) count++;
        return count;
    }

    /**
     * Languages the user writes in themselves besides the default: the ones they translate into (they know them),
     * except the interface language, which is their native one and so gets translated.
     */
    public List<Language> ownLanguages(ChatUser user) {
        var nativeLanguage = I18n.asLanguage(user.getUiLanguage());
        return repository.targetLanguagesByUse(user).stream()
            .filter(l -> l.isTarget() && l != user.getTargetLanguage() && l != nativeLanguage)
            .limit(MAX_OWN_LANGUAGES)
            .toList();
    }

    /** One-tap translations under a result: the user's most used languages, then the default and native ones. */
    public List<Language> quickLanguages(ChatUser user, Language current) {
        var candidates = new ArrayList<>(repository.targetLanguagesByUse(user));
        candidates.add(user.getTargetLanguage());
        candidates.add(I18n.asLanguage(user.getUiLanguage()));
        return candidates.stream().filter(l -> l.isTarget() && l != current).distinct().limit(QUICK_LANGUAGES).toList();
    }

    /** Result languages for a picker: current, the interface language, then this user's most used, then the rest. */
    public List<Language> languageOrder(ChatUser user, Language current) {
        var preferred = new ArrayList<Language>();
        preferred.add(current);
        preferred.add(I18n.asLanguage(user.getUiLanguage()));
        preferred.addAll(repository.targetLanguagesByUse(user));
        preferred.add(Language.EN);
        return Language.ordered(preferred.toArray(Language[]::new));
    }

    /** The user edited their original message: redo it with the same per-message settings. */
    public Optional<TextEntry> reprocessEdited(ChatUser user, Integer sourceMessageId, String newText) {
        var existing = repository.findFirstByChatUserAndSourceMessageIdOrderByIdDesc(user, sourceMessageId);
        if (existing.isEmpty()) return Optional.empty();
        var entry = existing.get();
        var source = validate(newText);
        if (source.equals(entry.getSourceText())) return Optional.empty();
        rateLimit.consume(user.getChatId());
        entry.setChatUser(user);
        entry.setSourceText(source);
        return Optional.of(run(entry, null, "EDIT", ownLanguages(user)));
    }

    /** Inline mode: nothing is stored besides the usage counter and the AI log. */
    public AiResult processInline(ChatUser user, String text) {
        var source = validate(text);
        rateLimit.consume(user.getChatId());
        var request = new AiRequest(source, user.getMode(), user.getTargetLanguage(), user.getTone(),
            I18n.asLanguage(user.getUiLanguage()), null, null, ownLanguages(user));
        return ai.process(user.getChatId(), "INLINE", request);
    }

    /** Loads an entry only if it belongs to the given chat, so forged callback data can't touch others' texts. */
    public Optional<TextEntry> find(long entryId, long chatId) {
        return repository.findWithUser(entryId).filter(e -> e.getChatUser().getChatId() == chatId);
    }

    public TextEntry toggleExplanation(TextEntry entry) {
        entry.setExplanationShown(!entry.isExplanationShown());
        return save(entry);
    }

    public void attachResultMessage(TextEntry entry, Integer resultMessageId) {
        entry.setResultMessageId(resultMessageId);
        save(entry);
    }

    public List<AiResult.Change> changes(TextEntry entry) {
        if (entry.getChangesJson() == null || entry.getChangesJson().isBlank()) return List.of();
        try {
            return Arrays.asList(jsonMapper.readValue(entry.getChangesJson(), AiResult.Change[].class));
        } catch (Exception e) {
            log.warn("Unreadable changes for entry {}: {}", entry.getId(), e.getMessage());
            return List.of();
        }
    }

    String validate(String text) {
        var source = text == null ? "" : text.strip();
        if (source.isEmpty()) throw new IllegalArgumentException("Empty text");
        var length = source.codePointCount(0, source.length());
        if (length > properties.maxInputLength()) {
            throw new TextTooLongException(length, properties.maxInputLength());
        }
        return source;
    }

    private TextEntry run(TextEntry entry, String previousResult, String kind, List<Language> ownLanguages) {
        var user = entry.getChatUser();
        var request = new AiRequest(entry.getSourceText(), entry.getMode(), entry.getTargetLanguage(),
            entry.getTone(), I18n.asLanguage(user.getUiLanguage()), previousResult, null, ownLanguages);
        try {
            var result = ai.process(user.getChatId(), kind, request);
            // Corrected in one of the user's own languages: that is the result language now.
            var detected = Language.fromCode(result.detectedLanguage());
            if (result.action() != TextAction.TRANSLATED && detected.filter(ownLanguages::contains).isPresent()) {
                entry.setTargetLanguage(detected.get());
            }
            entry.setResultText(result.text());
            entry.setDetectedLanguage(result.detectedLanguage());
            entry.setAction(result.action());
            entry.setChangesJson(result.changes().isEmpty() ? null : jsonMapper.writeValueAsString(result.changes()));
            entry.setExplanationShown(!result.changes().isEmpty() && result.action() != TextAction.TRANSLATED
                && (entry.isExplanationShown() || user.isAutoExplain()));
            entry.setStatus(TextStatus.DONE);
            entry.setShortest(false);
            if (previousResult != null) entry.setVersion(entry.getVersion() + 1);
        } catch (AiProcessingException e) {
            // A previously good result stays untouched (the in-memory changes are simply dropped);
            // a first attempt is marked FAILED so it can be retried.
            if (entry.getStatus() != TextStatus.DONE) {
                entry.setStatus(TextStatus.FAILED);
                entry.setUpdatedAt(LocalDateTime.now(clock));
                entry = save(entry);
            }
            throw new ProcessingFailedException(entry, e);
        }
        entry.setUpdatedAt(LocalDateTime.now(clock));
        return save(entry);
    }

    /**
     * Merging a detached entry returns a copy whose user is an unloaded proxy, unusable outside the
     * transaction. Entries always travel with their user, so re-attach the instance we already have.
     */
    private TextEntry save(TextEntry entry) {
        var user = entry.getChatUser();
        var saved = repository.save(entry);
        saved.setChatUser(user);
        return saved;
    }
}
