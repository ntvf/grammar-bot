package io.chatbots.grammar.service;

public class TextTooLongException extends RuntimeException {

    private final int length;
    private final int maxLength;

    public TextTooLongException(int length, int maxLength) {
        super("Text length " + length + " exceeds " + maxLength);
        this.length = length;
        this.maxLength = maxLength;
    }

    public int getLength() {
        return length;
    }

    public int getMaxLength() {
        return maxLength;
    }
}
