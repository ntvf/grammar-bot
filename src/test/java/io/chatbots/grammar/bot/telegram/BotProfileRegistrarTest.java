package io.chatbots.grammar.bot.telegram;

import io.chatbots.grammar.service.I18n;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.description.SetMyDescription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class BotProfileRegistrarTest {

    private final TelegramGateway gateway = mock(TelegramGateway.class);

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void registersCommandsAndDescriptionsForDefaultAndEveryLanguage() {
        new BotProfileRegistrar(gateway, new I18n(), "my_bot", true).registerAll();

        ArgumentCaptor<BotApiMethod> captor = ArgumentCaptor.forClass(BotApiMethod.class);
        var calls = (I18n.SUPPORTED.size() + 1) * 3;
        verify(gateway, times(calls)).execute(captor.capture());
        var ukCommands = captor.getAllValues().stream()
            .filter(SetMyCommands.class::isInstance).map(SetMyCommands.class::cast)
            .filter(c -> "uk".equals(c.getLanguageCode())).findFirst().orElseThrow();
        assertThat(ukCommands.getCommands()).extracting(c -> c.getCommand()).containsExactly("settings", "language", "help");
        assertThat(captor.getAllValues().stream().filter(SetMyDescription.class::isInstance)
            .map(d -> ((SetMyDescription) d).getDescription())).allMatch(d -> d.contains("@my_bot"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void disabled_doesNothing() {
        new BotProfileRegistrar(gateway, new I18n(), "my_bot", false).onReady();
        verify(gateway, never()).execute(any(BotApiMethod.class));
    }
}
