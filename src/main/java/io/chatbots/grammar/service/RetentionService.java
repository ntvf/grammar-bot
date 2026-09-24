package io.chatbots.grammar.service;

import io.chatbots.grammar.config.AppProperties;
import io.chatbots.grammar.domain.AiInteractionRepository;
import io.chatbots.grammar.domain.DailyUsageRepository;
import io.chatbots.grammar.domain.TextEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Users' texts are only kept as long as the buttons under results need them. */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    private final TextEntryRepository texts;
    private final AiInteractionRepository interactions;
    private final DailyUsageRepository usage;
    private final AppProperties properties;
    private final Clock clock;

    public RetentionService(TextEntryRepository texts, AiInteractionRepository interactions,
                            DailyUsageRepository usage, AppProperties properties, Clock clock) {
        this.texts = texts;
        this.interactions = interactions;
        this.usage = usage;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "UTC")
    @Transactional
    public void purge() {
        var cutoff = LocalDateTime.now(clock).minusDays(properties.retentionDays());
        var deletedTexts = texts.deleteOlderThan(cutoff);
        var deletedLogs = interactions.deleteOlderThan(cutoff);
        var deletedUsage = usage.deleteOlderThan(LocalDate.now(clock).minusDays(7));
        log.info("Retention: removed {} texts, {} AI logs, {} usage rows", deletedTexts, deletedLogs, deletedUsage);
    }
}
