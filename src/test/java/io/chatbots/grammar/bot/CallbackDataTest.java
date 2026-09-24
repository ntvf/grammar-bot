package io.chatbots.grammar.bot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CallbackDataTest {

    @Test
    void roundTrip() {
        var data = CallbackData.of(CallbackData.RESULT, "t", 123456789L, "FORMAL");
        assertThat(data).isEqualTo("r:t:123456789:FORMAL");
        assertThat(CallbackData.parse(data)).contains(new CallbackData("r", "t", 123456789L, "FORMAL"));
    }

    @Test
    void shortForms() {
        assertThat(CallbackData.of("s", "h")).isEqualTo("s:h:0:");
        assertThat(CallbackData.of("o", "l", "de")).isEqualTo("o:l:0:de");
        assertThat(CallbackData.of("o", "l", 0, null)).isEqualTo("o:l:0:");
    }

    @Test
    void worstCaseFitsTelegramLimit() {
        var data = CallbackData.of(CallbackData.RESULT, "t", Long.MAX_VALUE, "FRIENDLY");
        assertThat(data.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(64);
    }

    @Test
    void tooLong_isRejected() {
        assertThatThrownBy(() -> CallbackData.of("s", "x", "a".repeat(60)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "r:g", "r:g:1", "r:g:x:", ":g:1:", "r:g:1::extra", "tz:regions"})
    void malformed_isEmpty(String data) {
        assertThat(CallbackData.parse(data)).isEmpty();
    }
}
