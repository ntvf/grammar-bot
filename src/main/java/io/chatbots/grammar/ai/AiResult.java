package io.chatbots.grammar.ai;

import io.chatbots.grammar.domain.TextAction;

import java.util.List;

public record AiResult(
    String text,
    String detectedLanguage,
    TextAction action,
    List<Change> changes
) {
    public record Change(String original, String replacement, String reason) {
    }
}
