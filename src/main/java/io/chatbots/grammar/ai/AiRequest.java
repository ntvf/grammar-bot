package io.chatbots.grammar.ai;

import io.chatbots.grammar.domain.Language;
import io.chatbots.grammar.domain.Mode;
import io.chatbots.grammar.domain.Tone;

/**
 * @param explanationLanguage language used for the short "why" notes, usually the user's interface language
 * @param previousResult      when regenerating, the version the user wants an alternative to; otherwise null
 */
public record AiRequest(
    String text,
    Mode mode,
    Language targetLanguage,
    Tone tone,
    Language explanationLanguage,
    String previousResult
) {
    public boolean isRegeneration() {
        return previousResult != null && !previousResult.isBlank();
    }
}
