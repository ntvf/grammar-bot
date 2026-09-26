package io.chatbots.grammar.domain;

public enum Tone {
    NATURAL("🪶"),
    FORMAL("🎩"),
    CASUAL("😎");

    private final String emoji;

    Tone(String emoji) {
        this.emoji = emoji;
    }

    public String emoji() {
        return emoji;
    }
}
