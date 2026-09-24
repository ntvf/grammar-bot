package io.chatbots.grammar.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
    int dailyLimit,
    int maxInputLength,
    int retentionDays,
    boolean logAiContent,
    List<Long> adminChatIds,
    int inlineMinLength,
    long inlineDebounceMs,
    Ai ai
) {
    public AppProperties {
        adminChatIds = adminChatIds == null ? List.of() : List.copyOf(adminChatIds);
        ai = ai == null ? new Ai(true, null) : ai;
    }

    /**
     * Model capabilities differ: reasoning models such as gpt-6-luna reject any temperature but their default,
     * and instead take a reasoning effort.
     */
    public record Ai(boolean temperatureSupported, String reasoningEffort) {
    }

    public boolean isAdmin(long chatId) {
        return adminChatIds.contains(chatId);
    }
}
