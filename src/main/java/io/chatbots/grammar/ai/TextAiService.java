package io.chatbots.grammar.ai;

import io.chatbots.grammar.domain.TextAction;
import io.chatbots.grammar.service.AiInteractionLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
public class TextAiService {

    private static final Logger log = LoggerFactory.getLogger(TextAiService.class);

    static final double DEFAULT_TEMPERATURE = 0.2;
    static final double REGENERATE_TEMPERATURE = 0.9;

    private final OpenAiGateway gateway;
    private final AiInteractionLogService interactionLog;

    public TextAiService(OpenAiGateway gateway, AiInteractionLogService interactionLog) {
        this.gateway = gateway;
        this.interactionLog = interactionLog;
    }

    public AiResult process(long chatId, String kind, AiRequest request) {
        var system = PromptBuilder.systemPrompt(request);
        var user = PromptBuilder.userPrompt(request);
        var temperature = request.isRegeneration() ? REGENERATE_TEMPERATURE : DEFAULT_TEMPERATURE;
        var started = System.nanoTime();
        try {
            var result = normalize(request, gateway.complete(system, user, temperature));
            interactionLog.record(chatId, kind, request.text(), result, "OK", null, elapsedMs(started));
            return result;
        } catch (Exception e) {
            log.warn("AI processing failed for chat {}: {}", chatId, e.getMessage());
            interactionLog.record(chatId, kind, request.text(), null, "ERROR", e.getMessage(), elapsedMs(started));
            if (e instanceof AiProcessingException ape) throw ape;
            throw new AiProcessingException("AI call failed", e);
        }
    }

    /** Guards against sloppy model output so the UI can rely on the invariants. */
    static AiResult normalize(AiRequest request, AiResult raw) {
        if (raw == null || raw.text() == null || raw.text().isBlank()) {
            throw new AiProcessingException("Empty AI response");
        }
        var text = raw.text().strip();
        var action = raw.action() != null ? raw.action() : TextAction.CORRECTED;
        var unchangedText = text.equals(request.text().strip());
        if (action == TextAction.UNCHANGED && !unchangedText) {
            action = TextAction.CORRECTED;
        } else if (action == TextAction.CORRECTED && unchangedText) {
            action = TextAction.UNCHANGED;
        }
        var changes = raw.changes() == null ? List.<AiResult.Change>of() : raw.changes().stream()
            .filter(Objects::nonNull)
            .filter(c -> c.replacement() != null && c.original() != null && !c.original().equals(c.replacement()))
            .limit(PromptBuilder.MAX_CHANGES)
            .toList();
        if (action == TextAction.UNCHANGED) changes = List.of();
        var detected = raw.detectedLanguage() == null ? null : raw.detectedLanguage().strip().toLowerCase();
        return new AiResult(text, detected, action, changes);
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
