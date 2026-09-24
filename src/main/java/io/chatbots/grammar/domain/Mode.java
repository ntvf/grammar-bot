package io.chatbots.grammar.domain;

public enum Mode {
    /** Correct if the text is already in the target language, otherwise translate it. */
    SMART("✨"),
    /** Correct grammar and spelling, keep the original language. */
    FIX("✍️"),
    /** Always translate into the target language. */
    TRANSLATE("🌐");

    private final String emoji;

    Mode(String emoji) {
        this.emoji = emoji;
    }

    public String emoji() {
        return emoji;
    }

    public boolean usesTargetLanguage() {
        return this != FIX;
    }
}
