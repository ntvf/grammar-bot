package io.chatbots.grammar.service;

import io.chatbots.grammar.domain.ChatUser;
import io.chatbots.grammar.domain.ChatUserRepository;
import io.chatbots.grammar.domain.Language;
import io.chatbots.grammar.domain.Mode;
import io.chatbots.grammar.domain.Tone;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.function.Consumer;

@Service
public class UserService {

    static final int MAX_SOURCE_LENGTH = 64;

    private final ChatUserRepository repository;
    private final Clock clock;

    public UserService(ChatUserRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** Telegram profile data as seen in the latest update. */
    public record Profile(long chatId, String username, String firstName, String languageCode) {
    }

    @Transactional
    public ChatUser touch(Profile profile) {
        return touch(profile, null);
    }

    /** Returns the user, creating them with sensible defaults on first contact, and records activity. */
    @Transactional
    public ChatUser touch(Profile profile, String source) {
        var now = LocalDateTime.now(clock);
        var user = repository.findByChatId(profile.chatId()).orElseGet(() -> create(profile, source, now));
        user.setUsername(truncate(profile.username(), 64));
        user.setFirstName(truncate(profile.firstName(), 128));
        user.setLastActiveAt(now);
        return user;
    }

    @Transactional(readOnly = true)
    public Optional<ChatUser> find(long chatId) {
        return repository.findByChatId(chatId);
    }

    @Transactional
    public ChatUser setMode(long chatId, Mode mode) {
        return update(chatId, u -> u.setMode(mode));
    }

    @Transactional
    public ChatUser setTargetLanguage(long chatId, Language language) {
        return update(chatId, u -> u.setTargetLanguage(language));
    }

    @Transactional
    public ChatUser setTone(long chatId, Tone tone) {
        return update(chatId, u -> u.setTone(tone));
    }

    @Transactional
    public ChatUser setUiLanguage(long chatId, String uiLanguage) {
        return update(chatId, u -> u.setUiLanguage(I18n.resolve(uiLanguage)));
    }

    @Transactional
    public ChatUser toggleAutoExplain(long chatId) {
        return update(chatId, u -> u.setAutoExplain(!u.isAutoExplain()));
    }

    @Transactional
    public ChatUser completeOnboarding(long chatId) {
        return update(chatId, u -> u.setOnboarded(true));
    }

    @Transactional
    public void delete(long chatId) {
        repository.findByChatId(chatId).ifPresent(repository::delete);
    }

    private ChatUser update(long chatId, Consumer<ChatUser> change) {
        var user = repository.findByChatId(chatId)
            .orElseThrow(() -> new IllegalStateException("Unknown chat " + chatId));
        change.accept(user);
        return user;
    }

    private ChatUser create(Profile profile, String source, LocalDateTime now) {
        var user = new ChatUser();
        user.setChatId(profile.chatId());
        user.setUiLanguage(I18n.resolve(profile.languageCode()));
        // English is by far the most common target; for English speakers that simply means correction.
        user.setTargetLanguage(Language.EN);
        user.setMode(Mode.SMART);
        user.setTone(Tone.NATURAL);
        user.setSource(sanitizeSource(source));
        user.setCreatedAt(now);
        user.setLastActiveAt(now);
        return repository.save(user);
    }

    static String sanitizeSource(String source) {
        if (source == null) return null;
        var sanitized = source.replaceAll("[^A-Za-z0-9_-]", "");
        if (sanitized.isEmpty()) return null;
        return truncate(sanitized, MAX_SOURCE_LENGTH);
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() > max ? value.substring(0, max) : value;
    }
}
