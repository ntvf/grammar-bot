package io.chatbots.grammar.service;

import io.chatbots.grammar.domain.TextEntry;

/** The model call failed. Carries the stored entry so the user can retry it with one tap. */
public class ProcessingFailedException extends RuntimeException {

    private final transient TextEntry entry;

    public ProcessingFailedException(TextEntry entry, Throwable cause) {
        super("Processing failed for entry " + entry.getId(), cause);
        this.entry = entry;
    }

    public TextEntry getEntry() {
        return entry;
    }
}
