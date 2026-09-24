package io.chatbots.grammar.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.LocalDate;

@Entity
@Table(name = "daily_usage")
@IdClass(DailyUsage.Key.class)
public class DailyUsage {

    @Id
    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Id
    @Column(name = "usage_date", nullable = false)
    private LocalDate usageDate;

    @Column(name = "request_count", nullable = false)
    private int requestCount;

    public Long getChatId() {
        return chatId;
    }

    public LocalDate getUsageDate() {
        return usageDate;
    }

    public int getRequestCount() {
        return requestCount;
    }

    public record Key(Long chatId, LocalDate usageDate) implements Serializable {
    }
}
