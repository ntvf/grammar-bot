package io.chatbots.grammar.bot.telegram;

import io.chatbots.grammar.bot.Keyboards;
import io.chatbots.grammar.config.AppProperties;
import io.chatbots.grammar.domain.ChatUser;
import io.chatbots.grammar.service.I18n;
import io.chatbots.grammar.service.ProcessingFailedException;
import io.chatbots.grammar.service.RateLimitExceededException;
import io.chatbots.grammar.service.StatisticsService;
import io.chatbots.grammar.service.TextService;
import io.chatbots.grammar.service.TextTooLongException;
import io.chatbots.grammar.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.util.Locale;

/** Private-chat messages: commands, texts to process, and edits of earlier texts. */
@Component
public class MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(MessageHandler.class);

    private final UserService users;
    private final TextService texts;
    private final StatisticsService statistics;
    private final BotViews views;
    private final Keyboards keyboards;
    private final TelegramGateway gateway;
    private final I18n i18n;
    private final AppProperties properties;

    public MessageHandler(UserService users, TextService texts, StatisticsService statistics, BotViews views,
                          Keyboards keyboards, TelegramGateway gateway, I18n i18n, AppProperties properties) {
        this.users = users;
        this.texts = texts;
        this.statistics = statistics;
        this.views = views;
        this.keyboards = keyboards;
        this.gateway = gateway;
        this.i18n = i18n;
        this.properties = properties;
    }

    public void handle(Message message) {
        if (!message.isUserMessage() || message.getFrom() == null) return;
        var text = message.hasText() ? message.getText() : message.getCaption();

        if (text != null && text.startsWith("/")) {
            handleCommand(message, text.strip());
            return;
        }
        var user = users.touch(profile(message));
        if (text == null || text.isBlank()) {
            gateway.send(user.getChatId(), i18n.t(user.getUiLanguage(), "error.not_text"), null);
            return;
        }
        processAndReply(user, text, message.getMessageId());
    }

    public void handleEdit(Message message) {
        if (!message.isUserMessage() || message.getFrom() == null) return;
        var text = message.hasText() ? message.getText() : message.getCaption();
        if (text == null || text.isBlank() || text.startsWith("/")) return;
        var user = users.touch(profile(message));
        try {
            texts.reprocessEdited(user, message.getMessageId(), text)
                .filter(entry -> entry.getResultMessageId() != null)
                .ifPresent(entry -> views.showResult(entry, entry.getResultMessageId()));
        } catch (RuntimeException e) {
            // Edits are a convenience: the previous answer simply stays as it was.
            log.info("Skipped re-processing edited message in chat {}: {}", user.getChatId(), e.getMessage());
        }
    }

    /** Runs the text through the model and replies with the result, or a friendly explanation why not. */
    public void processAndReply(ChatUser user, String text, Integer replyTo) {
        var chatId = user.getChatId();
        var lang = user.getUiLanguage();
        try (var ignored = gateway.typing(chatId)) {
            var entry = texts.process(user, text, replyTo);
            views.sendResult(entry, replyTo);
        } catch (TextTooLongException e) {
            gateway.send(chatId, i18n.t(lang, "error.too_long", e.getLength(), e.getMaxLength()), null, replyTo);
        } catch (RateLimitExceededException e) {
            gateway.send(chatId, i18n.t(lang, "error.rate_limit", e.getLimit()), null, replyTo);
        } catch (ProcessingFailedException e) {
            gateway.send(chatId, i18n.t(lang, "error.ai"), keyboards.retry(e.getEntry(), lang), replyTo);
        } catch (Exception e) {
            log.error("Failed to process text in chat {}: {}", chatId, e.getMessage(), e);
            gateway.send(chatId, i18n.t(lang, "error.generic"), null, replyTo);
        }
    }

    private void handleCommand(Message message, String text) {
        var parts = text.split("\\s+", 2);
        var command = parts[0].toLowerCase(Locale.ROOT).replaceFirst("@.*$", "");
        var argument = parts.length > 1 ? parts[1].strip() : null;

        if ("/start".equals(command)) {
            var user = users.touch(profile(message), argument);
            if (user.isOnboarded()) {
                views.sendWelcomeBack(user);
            } else {
                views.sendWelcome(user);
            }
            return;
        }

        var user = users.touch(profile(message));
        switch (command) {
            case "/help" -> views.sendHelp(user);
            case "/settings" -> views.sendSettings(user);
            case "/language" -> views.sendTargetPicker(user);
            case "/stats" -> {
                if (properties.isAdmin(user.getChatId())) {
                    gateway.send(user.getChatId(), statistics.buildReport(), null);
                } else {
                    gateway.send(user.getChatId(), i18n.t(user.getUiLanguage(), "error.unknown_command"), null);
                }
            }
            default -> gateway.send(user.getChatId(), i18n.t(user.getUiLanguage(), "error.unknown_command"), null);
        }
    }

    static UserService.Profile profile(Message message) {
        var from = message.getFrom();
        return new UserService.Profile(message.getChatId(), from.getUserName(), from.getFirstName(),
            from.getLanguageCode());
    }
}
