package io.chatbots.grammar.bot.telegram;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TelegramGatewayTest {

    private final TelegramClient client = mock(TelegramClient.class);
    private final TelegramGateway gateway = new TelegramGateway(client);

    @Test
    void failuresAreSwallowed() throws Exception {
        when(client.execute(any(SendMessage.class))).thenThrow(new TelegramApiException("Forbidden: bot was blocked"));
        assertThat(gateway.send(1L, "hi", null, 5)).isNull();
    }

    @Test
    void harmlessErrorsAreRecognised() throws Exception {
        assertThat(TelegramGateway.isHarmless(new TelegramApiException("Bad Request: message is not modified"))).isTrue();
        assertThat(TelegramGateway.isHarmless(new TelegramApiException("Bad Request: query is too old"))).isTrue();
        assertThat(TelegramGateway.isHarmless(new TelegramApiException("Forbidden"))).isFalse();
        assertThat(TelegramGateway.isHarmless(new TelegramApiException((String) null))).isFalse();

        when(client.execute(any(AnswerCallbackQuery.class)))
            .thenThrow(new TelegramApiException("query ID is invalid"));
        assertThat(gateway.execute(AnswerCallbackQuery.builder().callbackQueryId("x").build())).isNull();
    }

    @Test
    void typingStopsWhenClosed() throws Exception {
        var handle = gateway.typing(1L);
        handle.close();
    }
}
