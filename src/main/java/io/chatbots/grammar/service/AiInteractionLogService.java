package io.chatbots.grammar.service;

import io.chatbots.grammar.config.AppProperties;
import io.chatbots.grammar.domain.AiInteraction;
import io.chatbots.grammar.domain.AiInteractionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDateTime;

/** Persists every model call for quality analysis. Never lets a logging failure break the user flow. */
@Service
public class AiInteractionLogService {

    private static final Logger log = LoggerFactory.getLogger(AiInteractionLogService.class);

    static final int MAX_TEXT_LENGTH = 8000;

    private final AiInteractionRepository repository;
    private final AppProperties properties;
    private final Clock clock;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public AiInteractionLogService(AiInteractionRepository repository, AppProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(long chatId, String kind, String requestText, Object response, String outcome,
                       String errorText, long latencyMs) {
        try {
            var interaction = new AiInteraction();
            interaction.setChatId(chatId);
            interaction.setKind(kind);
            if (properties.logAiContent()) {
                interaction.setRequestText(truncate(requestText));
                interaction.setResponseJson(truncate(toJson(response)));
            }
            interaction.setOutcome(outcome);
            interaction.setErrorText(truncate(errorText));
            interaction.setLatencyMs(latencyMs);
            interaction.setCreatedAt(LocalDateTime.now(clock));
            repository.save(interaction);
        } catch (Exception e) {
            log.warn("Failed to log AI interaction for chat {}: {}", chatId, e.getMessage());
        }
    }

    private String toJson(Object response) {
        if (response == null) return null;
        try {
            return jsonMapper.writeValueAsString(response);
        } catch (Exception e) {
            return String.valueOf(response);
        }
    }

    static String truncate(String text) {
        if (text == null) return null;
        return text.length() > MAX_TEXT_LENGTH ? text.substring(0, MAX_TEXT_LENGTH) : text;
    }
}
