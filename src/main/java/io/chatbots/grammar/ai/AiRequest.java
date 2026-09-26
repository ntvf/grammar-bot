package io.chatbots.grammar.ai;

import io.chatbots.grammar.domain.Language;
import io.chatbots.grammar.domain.Mode;
import io.chatbots.grammar.domain.Tone;

import java.util.List;

/**
 * @param explanationLanguage language used for the short "why" notes, usually the user's interface language
 * @param previousResult      when regenerating, the version the user wants an alternative to; otherwise null
 * @param shortening          when set, {@code text} is an existing result to shorten rather than a new message
 * @param ownLanguages        Smart mode: languages besides the target that the user writes in themselves, so a text
 *                            in one of them is corrected in place instead of translated
 */
public record AiRequest(
    String text,
    Mode mode,
    Language targetLanguage,
    Tone tone,
    Language explanationLanguage,
    String previousResult,
    Shortening shortening,
    List<Language> ownLanguages
) {
    /** How "Shorter" cuts a result down. */
    public enum Shortening {
        /** Several sentences: one fewer. */
        SENTENCE,
        /** A single sentence: fewer words. */
        WORDS
    }

    public AiRequest {
        ownLanguages = ownLanguages == null ? List.of() : List.copyOf(ownLanguages);
    }

    public AiRequest(String text, Mode mode, Language targetLanguage, Tone tone, Language explanationLanguage,
                     String previousResult) {
        this(text, mode, targetLanguage, tone, explanationLanguage, previousResult, null, List.of());
    }

    public AiRequest(String text, Mode mode, Language targetLanguage, Tone tone, Language explanationLanguage,
                     String previousResult, Shortening shortening) {
        this(text, mode, targetLanguage, tone, explanationLanguage, previousResult, shortening, List.of());
    }

    public boolean isRegeneration() {
        return previousResult != null && !previousResult.isBlank();
    }
}
