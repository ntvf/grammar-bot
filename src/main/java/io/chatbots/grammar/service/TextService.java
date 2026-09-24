package io.chatbots.grammar.service;

import io.chatbots.grammar.ai.AiProcessingException;
import io.chatbots.grammar.ai.AiRequest;
import io.chatbots.grammar.ai.AiResult;
import io.chatbots.grammar.ai.TextAiService;
import io.chatbots.grammar.config.AppProperties;
import io.chatbots.grammar.domain.ChatUser;
import io.chatbots.grammar.domain.Language;
import io.chatbots.grammar.domain.Mode;
import io.chatbots.grammar.domain.TextEntry;
import io.chatbots.grammar.domain.TextEntryRepository;
import io.chatbots.grammar.domain.TextStatus;
import io.chatbots.grammar.domain.Tone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Orchestrates text processing. Model calls deliberately run outside database transactions — they take
 * seconds, and holding a connection that long would starve the pool under load.
 */
@Service
public class TextService {

    private static final Logger log = LoggerFactory.getLogger(TextService.class);

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
        return run(save(entry), null, "MESSAGE");
    }

    /** Another wording of the same result; retries the call if the previous attempt failed. */
    public Outcome regenerate(TextEntry entry) {
        rateLimit.consume(entry.getChatUser().getChatId());
        var previous = entry.getStatus() == TextStatus.DONE ? entry.getResultText() : null;
        var updated = run(entry, previous, "REGENERATE");
        return new Outcome(updated, Objects.equals(previous, updated.getResultText()));
    }

    /** Applies a one-off style. Tapping the style that is already active returns to the user's default. */
    public Outcome restyle(TextEntry entry, Tone tone) {
        var newTone = entry.getTone() == tone ? entry.getChatUser().getTone() : tone;
        if (newTone == entry.getTone()) newTone = Tone.NATURAL;
        rateLimit.consume(entry.getChatUser().getChatId());
        var previous = entry.getResultText();
        entry.setTone(newTone);
        var updated = run(entry, null, "RESTYLE");
        return new Outcome(updated, Objects.equals(previous, updated.getResultText()));
    }

    /** Re-renders the text in another language. Picking a language explicitly always means "translate". */
    public Outcome retarget(TextEntry entry, Language language) {
        rateLimit.consume(entry.getChatUser().getChatId());
        var previous = entry.getResultText();
        entry.setTargetLanguage(language);
        if (entry.getMode() == Mode.FIX) entry.setMode(Mode.SMART);
        var updated = run(entry, null, "RETARGET");
        return new Outcome(updated, Objects.equals(previous, updated.getResultText()));
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
        return Optional.of(run(entry, null, "EDIT"));
    }

    /** Inline mode: nothing is stored besides the usage counter and the AI log. */
    public AiResult processInline(ChatUser user, String text) {
        var source = validate(text);
        rateLimit.consume(user.getChatId());
        var request = new AiRequest(source, user.getMode(), user.getTargetLanguage(), user.getTone(),
            I18n.asLanguage(user.getUiLanguage()), null);
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

    private TextEntry run(TextEntry entry, String previousResult, String kind) {
        var user = entry.getChatUser();
        var request = new AiRequest(entry.getSourceText(), entry.getMode(), entry.getTargetLanguage(),
            entry.getTone(), I18n.asLanguage(user.getUiLanguage()), previousResult);
        try {
            var result = ai.process(user.getChatId(), kind, request);
            entry.setResultText(result.text());
            entry.setDetectedLanguage(result.detectedLanguage());
            entry.setAction(result.action());
            entry.setChangesJson(result.changes().isEmpty() ? null : jsonMapper.writeValueAsString(result.changes()));
            entry.setExplanationShown(!result.changes().isEmpty()
                && (entry.isExplanationShown() || user.isAutoExplain()));
            entry.setStatus(TextStatus.DONE);
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
