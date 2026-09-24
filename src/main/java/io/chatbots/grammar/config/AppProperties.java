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
    long inlineDebounceMs
) {
    public AppProperties {
        adminChatIds = adminChatIds == null ? List.of() : List.copyOf(adminChatIds);
    }

    public boolean isAdmin(long chatId) {
        return adminChatIds.contains(chatId);
    }
}
