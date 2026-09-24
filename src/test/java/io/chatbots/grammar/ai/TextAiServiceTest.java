package io.chatbots.grammar.ai;

import io.chatbots.grammar.config.AppProperties;
import io.chatbots.grammar.domain.Language;
import io.chatbots.grammar.domain.Mode;
import io.chatbots.grammar.domain.TextAction;
import io.chatbots.grammar.domain.Tone;
import io.chatbots.grammar.service.AiInteractionLogService;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TextAiServiceTest {

    private final OpenAiGateway gateway = mock(OpenAiGateway.class);
    private final AiInteractionLogService interactionLog = mock(AiInteractionLogService.class);
    private final TextAiService service = new TextAiService(gateway, interactionLog);

    private static final AiRequest REQUEST =
        new AiRequest("i has went", Mode.SMART, Language.EN, Tone.NATURAL, Language.EN, null);

    private static AiResult.Change change(String from, String to) {
        return new AiResult.Change(from, to, "grammar");
    }

    @Test
    void process_usesLowTemperatureAndLogsSuccess() {
        var raw = new AiResult(" I went ", "EN", TextAction.CORRECTED, List.of(change("has went", "went")));
        when(gateway.complete(anyString(), anyString(), anyDouble())).thenReturn(raw);

        var result = service.process(42L, "MESSAGE", REQUEST);

        assertThat(result.text()).isEqualTo("I went");
        assertThat(result.detectedLanguage()).isEqualTo("en");
        verify(gateway).complete(anyString(), anyString(), eq(TextAiService.DEFAULT_TEMPERATURE));
        verify(interactionLog).record(eq(42L), eq("MESSAGE"), eq("i has went"), any(), eq("OK"), isNull(), anyLong());
    }

    @Test
    void regeneration_usesHighTemperature() {
        when(gateway.complete(anyString(), anyString(), anyDouble()))
            .thenReturn(new AiResult("I've gone", "en", TextAction.CORRECTED, List.of()));
        var regen = new AiRequest("i has went", Mode.SMART, Language.EN, Tone.NATURAL, Language.EN, "I went");

        service.process(1L, "REGENERATE", regen);

        verify(gateway).complete(anyString(), anyString(), eq(TextAiService.REGENERATE_TEMPERATURE));
    }

    @Test
    void gatewayFailure_isWrappedAndLogged() {
        when(gateway.complete(anyString(), anyString(), anyDouble())).thenThrow(new RuntimeException("timeout"));

        assertThatThrownBy(() -> service.process(7L, "MESSAGE", REQUEST))
            .isInstanceOf(AiProcessingException.class)
            .hasRootCauseMessage("timeout");
        verify(interactionLog).record(eq(7L), eq("MESSAGE"), eq("i has went"), isNull(), eq("ERROR"),
            eq("timeout"), anyLong());
    }

    @Test
    void emptyResponse_isAnError() {
        when(gateway.complete(anyString(), anyString(), anyDouble()))
            .thenReturn(new AiResult("  ", "en", TextAction.CORRECTED, List.of()));
        assertThatThrownBy(() -> service.process(7L, "MESSAGE", REQUEST))
            .isInstanceOf(AiProcessingException.class).hasMessage("Empty AI response");
    }

    @Test
    void normalize_nullResponse_throws() {
        assertThatThrownBy(() -> TextAiService.normalize(REQUEST, null)).isInstanceOf(AiProcessingException.class);
    }

    @Test
    void normalize_unchangedClaimWithDifferentText_becomesCorrected() {
        var result = TextAiService.normalize(REQUEST, new AiResult("I went", "en", TextAction.UNCHANGED, null));
        assertThat(result.action()).isEqualTo(TextAction.CORRECTED);
        assertThat(result.changes()).isEmpty();
    }

    @Test
    void normalize_correctedClaimWithSameText_becomesUnchangedAndDropsChanges() {
        var result = TextAiService.normalize(REQUEST,
            new AiResult("i has went", "en", TextAction.CORRECTED, List.of(change("a", "b"))));
        assertThat(result.action()).isEqualTo(TextAction.UNCHANGED);
        assertThat(result.changes()).isEmpty();
    }

    @Test
    void normalize_missingActionDefaultsToCorrected() {
        var result = TextAiService.normalize(REQUEST, new AiResult("I went", null, null, List.of()));
        assertThat(result.action()).isEqualTo(TextAction.CORRECTED);
        assertThat(result.detectedLanguage()).isNull();
    }

    @Test
    void normalize_filtersNoOpAndNullChangesAndCapsCount() {
        var changes = Arrays.asList(
            null, change("same", "same"), new AiResult.Change(null, "x", "r"),
            change("a", "1"), change("b", "2"), change("c", "3"), change("d", "4"), change("e", "5"), change("f", "6"));
        var result = TextAiService.normalize(REQUEST, new AiResult("I went", "en", TextAction.CORRECTED, changes));
        assertThat(result.changes()).hasSize(PromptBuilder.MAX_CHANGES)
            .extracting(AiResult.Change::original).containsExactly("a", "b", "c", "d", "e");
    }

    @Test
    void reasoningModel_getsNoTemperatureButAReasoningEffort() {
        var options = OpenAiGateway.options(0.9, new AppProperties.Ai(false, "low")).build();
        assertThat(options.getTemperature()).isNull();
        assertThat(options.getReasoningEffort()).isEqualTo("low");
        assertThat(options.getResponseFormat()).isNotNull();
    }

    @Test
    void classicModel_getsTemperatureAndNoReasoningEffort() {
        var options = OpenAiGateway.options(0.2, new AppProperties.Ai(true, " ")).build();
        assertThat(options.getTemperature()).isEqualTo(0.2);
        assertThat(options.getReasoningEffort()).isNull();
    }

    @Test
    void responseSchema_requiresEveryField() {
        assertThat(OpenAiGateway.RESPONSE_SCHEMA)
            .contains("\"required\": [\"text\", \"detectedLanguage\", \"action\", \"changes\"]");
    }
}
