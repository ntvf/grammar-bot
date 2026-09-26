package io.chatbots.grammar.integration;

import io.chatbots.grammar.ai.AiResult;
import io.chatbots.grammar.ai.OpenAiGateway;
import io.chatbots.grammar.domain.AiInteractionRepository;
import io.chatbots.grammar.domain.ChatUserRepository;
import io.chatbots.grammar.domain.DailyUsageRepository;
import io.chatbots.grammar.domain.TextAction;
import io.chatbots.grammar.domain.TextEntryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.telegram.telegrambots.meta.api.methods.send.SendAnimation;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.games.Animation;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

/**
 * Real Spring context and a real PostgreSQL (Testcontainers) with the Liquibase schema; only the two
 * network edges — Telegram and the model — are mocked.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class IntegrationTest {

    protected static final String FIXED = "I went to the shop yesterday.";

    @MockitoBean protected TelegramClient telegramClient;
    @MockitoBean protected OpenAiGateway aiGateway;

    @Autowired protected ChatUserRepository chatUsers;
    @Autowired protected TextEntryRepository textEntries;
    @Autowired protected DailyUsageRepository dailyUsage;
    @Autowired protected AiInteractionRepository aiInteractions;

    private final AtomicInteger messageIds = new AtomicInteger(1000);

    @BeforeEach
    void stubEdges() throws Exception {
        when(telegramClient.execute(any(SendMessage.class))).thenAnswer(inv -> message(messageIds.incrementAndGet()));
        when(telegramClient.execute(any(SendAnimation.class))).thenAnswer(inv -> {
            var message = message(messageIds.incrementAndGet());
            message.setAnimation(new Animation("file-id-1", "unique-1", 720, 900, 12));
            return message;
        });
        aiReturns(FIXED, TextAction.CORRECTED, new AiResult.Change("has went", "went", "past simple"));
    }

    @AfterEach
    void cleanDatabase() {
        textEntries.deleteAll();
        chatUsers.deleteAll();
        dailyUsage.deleteAll();
        aiInteractions.deleteAll();
    }

    protected void aiReturns(String text, TextAction action, AiResult.Change... changes) {
        aiReturns(text, "en", action, changes);
    }

    protected void aiReturns(String text, String detectedLanguage, TextAction action, AiResult.Change... changes) {
        doReturn(new AiResult(text, detectedLanguage, action, List.of(changes)))
            .when(aiGateway).complete(anyString(), anyString(), anyDouble());
    }

    protected void aiFails() {
        doThrow(new RuntimeException("model down")).when(aiGateway).complete(anyString(), anyString(), anyDouble());
    }

    /** Every Telegram API call argument of the given type, in call order. */
    protected <T> List<T> sent(Class<T> type) {
        return mockingDetails(telegramClient).getInvocations().stream()
            .flatMap(invocation -> Arrays.stream(invocation.getArguments()))
            .filter(type::isInstance)
            .map(type::cast)
            .toList();
    }

    protected <T> T last(Class<T> type) {
        var all = sent(type);
        if (all.isEmpty()) throw new AssertionError("No " + type.getSimpleName() + " was sent");
        return all.getLast();
    }

    private static Message message(int id) {
        var message = new Message();
        message.setMessageId(id);
        return message;
    }
}
