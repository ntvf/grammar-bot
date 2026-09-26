package io.chatbots.grammar.bot.telegram;

import io.chatbots.grammar.bot.Keyboards;
import io.chatbots.grammar.bot.ResultFormatter;
import io.chatbots.grammar.domain.ChatUser;
import io.chatbots.grammar.domain.TextAction;
import io.chatbots.grammar.domain.TextEntry;
import io.chatbots.grammar.service.I18n;
import io.chatbots.grammar.service.TextService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

/** Screens shared by several handlers. */
@Component
public class BotViews {

    private final I18n i18n;
    private final Keyboards keyboards;
    private final TelegramGateway gateway;
    private final TextService textService;
    private final DemoAnimation demo;
    private final String botUsername;

    public BotViews(I18n i18n, Keyboards keyboards, TelegramGateway gateway, TextService textService,
                    DemoAnimation demo, @Value("${telegram.bot-username}") String botUsername) {
        this.i18n = i18n;
        this.keyboards = keyboards;
        this.gateway = gateway;
        this.textService = textService;
        this.demo = demo;
        this.botUsername = botUsername;
    }

    /** The demo clip with a short pitch; there is nothing to set up, the user can start writing right away. */
    public void sendWelcome(ChatUser user) {
        var lang = user.getUiLanguage();
        var caption = i18n.t(lang, "welcome.caption", displayName(user), user.getTargetLanguage().label());
        if (!demo.send(user.getChatId(), lang, caption, null)) {
            gateway.send(user.getChatId(), caption, null);
        }
    }

    public void sendWelcomeBack(ChatUser user) {
        gateway.send(user.getChatId(), i18n.t(user.getUiLanguage(), "welcome.back", displayName(user),
            user.getTargetLanguage().label()), null);
    }

    public void sendHelp(ChatUser user) {
        var text = i18n.t(user.getUiLanguage(), "help.text", botUsername, user.getTargetLanguage().label());
        if (!demo.send(user.getChatId(), user.getUiLanguage(), text, null)) {
            gateway.send(user.getChatId(), text, null);
        }
    }

    public void sendTargetPicker(ChatUser user) {
        gateway.send(user.getChatId(), i18n.t(user.getUiLanguage(), "question.target"), keyboards.targetPicker(user,
            textService.languageOrder(user, user.getTargetLanguage())));
    }

    public String renderResult(TextEntry entry) {
        return ResultFormatter.render(entry, textService.changes(entry), i18n, entry.getChatUser().getUiLanguage());
    }

    public void sendResult(TextEntry entry, Integer replyTo) {
        var user = entry.getChatUser();
        var messageId = gateway.send(user.getChatId(), renderResult(entry), resultKeyboard(entry), replyTo);
        if (messageId != null) textService.attachResultMessage(entry, messageId);
    }

    public void showResult(TextEntry entry, int messageId) {
        gateway.editText(entry.getChatUser().getChatId(), messageId, renderResult(entry), resultKeyboard(entry));
    }

    public InlineKeyboardMarkup resultKeyboard(TextEntry entry) {
        var user = entry.getChatUser();
        return keyboards.result(entry, explainable(entry), user.getUiLanguage(),
            textService.quickLanguages(user, entry.getTargetLanguage()));
    }

    /** Translations don't get "What changed": listing differences between two languages isn't useful. */
    public boolean explainable(TextEntry entry) {
        return entry.getAction() != TextAction.TRANSLATED && !textService.changes(entry).isEmpty();
    }

    public String botUsername() {
        return botUsername;
    }

    private static String displayName(ChatUser user) {
        var name = user.getFirstName() != null && !user.getFirstName().isBlank() ? user.getFirstName() : "friend";
        return ResultFormatter.escape(name);
    }
}
