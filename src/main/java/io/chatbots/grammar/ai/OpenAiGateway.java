package io.chatbots.grammar.ai;

import io.chatbots.grammar.config.AppProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

/** Thin wrapper around the model call so the rest of the app can be tested without a network. */
@Component
public class OpenAiGateway {

    static final String RESPONSE_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "text":             {"type": "string"},
            "detectedLanguage": {"type": "string"},
            "action":           {"type": "string", "enum": ["CORRECTED", "TRANSLATED", "UNCHANGED"]},
            "changes": {
              "type": "array",
              "items": {
                "type": "object",
                "properties": {
                  "original":    {"type": "string"},
                  "replacement": {"type": "string"},
                  "reason":      {"type": "string"}
                },
                "required": ["original", "replacement", "reason"],
                "additionalProperties": false
              }
            }
          },
          "required": ["text", "detectedLanguage", "action", "changes"],
          "additionalProperties": false
        }
        """;

    private static final OpenAiChatModel.ResponseFormat STRUCTURED_FORMAT =
        OpenAiChatModel.ResponseFormat.builder()
            .type(OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA)
            .jsonSchema(RESPONSE_SCHEMA)
            .build();

    private final ChatClient chatClient;
    private final AppProperties.Ai ai;

    public OpenAiGateway(ChatClient.Builder chatClientBuilder, AppProperties properties) {
        this.chatClient = chatClientBuilder.build();
        this.ai = properties.ai();
    }

    public AiResult complete(String systemPrompt, String userPrompt, double temperature) {
        return chatClient.prompt()
            .system(systemPrompt)
            .user(userPrompt)
            .options(options(temperature, ai))
            .call()
            .entity(AiResult.class);
    }

    static OpenAiChatOptions.Builder options(double temperature, AppProperties.Ai ai) {
        var builder = OpenAiChatOptions.builder().responseFormat(STRUCTURED_FORMAT);
        if (ai.temperatureSupported()) builder.temperature(temperature);
        if (ai.reasoningEffort() != null && !ai.reasoningEffort().isBlank()) builder.reasoningEffort(ai.reasoningEffort());
        return builder;
    }
}
