package io.chatbots.grammar.service;

public class RateLimitExceededException extends RuntimeException {

    private final int limit;

    public RateLimitExceededException(int limit) {
        super("Daily limit of " + limit + " requests reached");
        this.limit = limit;
    }

    public int getLimit() {
        return limit;
    }
}
