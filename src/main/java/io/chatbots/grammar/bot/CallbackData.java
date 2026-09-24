package io.chatbots.grammar.bot;

import java.util.Optional;

/**
 * Compact inline-button payload: {@code scope:action:id:arg}. Telegram limits callback data to 64 bytes,
 * so scopes and actions are single letters.
 */
public record CallbackData(String scope, String action, long id, String arg) {

    /** Buttons under a result message; id is the text entry. */
    public static final String RESULT = "r";
    /** First-run setup. */
    public static final String ONBOARDING = "o";
    /** /settings panel. */
    public static final String SETTINGS = "s";
    /** Standalone /language picker. */
    public static final String PICKER = "p";
    /** Inert button, e.g. "Working on it…". */
    public static final String NOOP = "n";

    static final int MAX_BYTES = 64;

    public static String of(String scope, String action) {
        return of(scope, action, 0, "");
    }

    public static String of(String scope, String action, String arg) {
        return of(scope, action, 0, arg);
    }

    public static String of(String scope, String action, long id, String arg) {
        var data = scope + ":" + action + ":" + id + ":" + (arg == null ? "" : arg);
        if (data.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("Callback data too long: " + data);
        }
        return data;
    }

    public static Optional<CallbackData> parse(String data) {
        if (data == null) return Optional.empty();
        var parts = data.split(":", -1);
        if (parts.length != 4 || parts[0].isEmpty()) return Optional.empty();
        try {
            return Optional.of(new CallbackData(parts[0], parts[1], Long.parseLong(parts[2]), parts[3]));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
