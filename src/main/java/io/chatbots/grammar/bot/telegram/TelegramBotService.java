package io.chatbots.grammar.bot.telegram;

import io.chatbots.grammar.bot.ChatTaskDispatcher;
import io.chatbots.grammar.service.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.util.Set;

/** Entry point for updates: routes each one to its handler on the chat's ordered queue. */
@Service
public class TelegramBotService implements SpringLongPollingBot {

    private static final Set<String> GONE_STATUSES = Set.of("kicked", "left");

    private final String botToken;
    private final ChatTaskDispatcher dispatcher;
    private final MessageHandler messages;
    private final CallbackHandler callbacks;
    private final InlineQueryHandler inlineQueries;
    private final UserService users;

    public TelegramBotService(@Value("${telegram.bot-token}") String botToken, ChatTaskDispatcher dispatcher,
                              MessageHandler messages, CallbackHandler callbacks,
                              InlineQueryHandler inlineQueries, UserService users) {
        this.botToken = botToken;
        this.dispatcher = dispatcher;
        this.messages = messages;
        this.callbacks = callbacks;
        this.inlineQueries = inlineQueries;
        this.users = users;
    }

    @Override
    public String getBotToken() {
        return botToken;
    }

    @Override
    public LongPollingUpdateConsumer getUpdatesConsumer() {
        return updates -> updates.forEach(this::route);
    }

    void route(Update update) {
        if (update.hasMessage()) {
            var message = update.getMessage();
            dispatcher.submit(message.getChatId(), () -> messages.handle(message));
        } else if (update.hasEditedMessage()) {
            var message = update.getEditedMessage();
            dispatcher.submit(message.getChatId(), () -> messages.handleEdit(message));
        } else if (update.hasCallbackQuery()) {
            var query = update.getCallbackQuery();
            dispatcher.submit(query.getFrom().getId(), () -> callbacks.handle(query));
        } else if (update.hasInlineQuery()) {
            var query = update.getInlineQuery();
            dispatcher.submitUnordered(query.getFrom().getId(), () -> inlineQueries.handle(query));
        } else if (update.hasMyChatMember()) {
            var member = update.getMyChatMember();
            if (GONE_STATUSES.contains(member.getNewChatMember().getStatus())) {
                // The user blocked the bot: forget them and their texts.
                var chatId = member.getChat().getId();
                dispatcher.submit(chatId, () -> users.delete(chatId));
            }
        }
    }
}
