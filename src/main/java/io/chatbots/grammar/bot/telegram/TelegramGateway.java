package io.chatbots.grammar.bot.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.ActionType;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendAnimation;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.LinkPreviewOptions;
import org.telegram.telegrambots.meta.api.objects.ReplyParameters;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.io.Serializable;
import java.util.concurrent.atomic.AtomicBoolean;

/** Telegram API primitives with uniform error handling. Failures are logged, never thrown at handlers. */
@Component
public class TelegramGateway {

    private static final Logger log = LoggerFactory.getLogger(TelegramGateway.class);

    static final long TYPING_REFRESH_MS = 4500;
    private static final LinkPreviewOptions NO_PREVIEW = LinkPreviewOptions.builder().isDisabled(true).build();

    private final TelegramClient client;

    public TelegramGateway(TelegramClient client) {
        this.client = client;
    }

    public Integer send(long chatId, String html, InlineKeyboardMarkup keyboard) {
        return send(chatId, html, keyboard, null);
    }

    /** Sends an HTML message, optionally as a reply, and returns its id (null on failure). */
    public Integer send(long chatId, String html, InlineKeyboardMarkup keyboard, Integer replyTo) {
        var builder = SendMessage.builder().chatId(chatId).text(html).parseMode("HTML")
            .linkPreviewOptions(NO_PREVIEW).replyMarkup(keyboard);
        if (replyTo != null) {
            builder.replyParameters(ReplyParameters.builder().messageId(replyTo).allowSendingWithoutReply(true).build());
        }
        var message = execute(builder.build());
        return message != null ? message.getMessageId() : null;
    }

    public void editText(long chatId, int messageId, String html, InlineKeyboardMarkup keyboard) {
        execute(EditMessageText.builder().chatId(chatId).messageId(messageId).text(html).parseMode("HTML")
            .linkPreviewOptions(NO_PREVIEW).replyMarkup(keyboard).build());
    }

    public void editKeyboard(long chatId, int messageId, InlineKeyboardMarkup keyboard) {
        execute(EditMessageReplyMarkup.builder().chatId(chatId).messageId(messageId).replyMarkup(keyboard).build());
    }

    public void delete(long chatId, int messageId) {
        execute(DeleteMessage.builder().chatId(chatId).messageId(messageId).build());
    }

    public void answer(String callbackQueryId, String toast) {
        execute(AnswerCallbackQuery.builder().callbackQueryId(callbackQueryId).text(toast).build());
    }

    public Message sendAnimation(SendAnimation animation) throws TelegramApiException {
        return client.execute(animation);
    }

    /** Shows "typing…" until the returned handle is closed; Telegram clears the status after ~5 s. */
    public AutoCloseable typing(long chatId) {
        var active = new AtomicBoolean(true);
        Thread.ofVirtual().name("typing-" + chatId).start(() -> {
            while (active.get()) {
                execute(SendChatAction.builder().chatId(chatId).action(ActionType.TYPING.toString()).build());
                try {
                    Thread.sleep(TYPING_REFRESH_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
        return () -> active.set(false);
    }

    public <T extends Serializable> T execute(BotApiMethod<T> method) {
        try {
            return client.execute(method);
        } catch (TelegramApiException e) {
            if (isHarmless(e)) {
                log.debug("Ignored Telegram response for {}: {}", method.getMethod(), e.getMessage());
            } else {
                log.warn("Telegram {} failed: {}", method.getMethod(), e.getMessage());
            }
            return null;
        }
    }

    /** Double taps and stale callbacks produce these; they need no handling. */
    static boolean isHarmless(TelegramApiException e) {
        var message = e.getMessage();
        return message != null && (message.contains("message is not modified")
            || message.contains("query is too old")
            || message.contains("query ID is invalid"));
    }
}
