package io.chatbots.grammar.service;

import io.chatbots.grammar.config.AppProperties;
import io.chatbots.grammar.domain.DailyUsageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;

@Service
public class RateLimitService {

    private final DailyUsageRepository repository;
    private final AppProperties properties;
    private final Clock clock;

    public RateLimitService(DailyUsageRepository repository, AppProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /** Counts one AI request against today's quota; admins are never limited. */
    @Transactional
    public void consume(long chatId) {
        if (properties.isAdmin(chatId)) return;
        var count = repository.incrementAndGet(chatId, LocalDate.now(clock));
        if (count > properties.dailyLimit()) {
            throw new RateLimitExceededException(properties.dailyLimit());
        }
    }
}
