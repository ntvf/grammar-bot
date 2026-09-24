package io.chatbots.grammar.bot.telegram;

import io.chatbots.grammar.bot.Keyboards;
import io.chatbots.grammar.bot.ResultFormatter;
import io.chatbots.grammar.domain.ChatUser;
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

    /** Mode, language and style in a few human-readable lines. */
    public String summary(ChatUser user, boolean full) {
        var lang = user.getUiLanguage();
        var sb = new StringBuilder();
        sb.append(i18n.t(lang, "summary.mode",
            user.getMode().emoji() + " <b>" + i18n.t(lang, "mode." + user.getMode().name()) + "</b>"));
        if (user.getMode().usesTargetLanguage()) {
            sb.append('\n').append(i18n.t(lang, "summary.target", "<b>" + user.getTargetLanguage().label() + "</b>"));
        }
        sb.append('\n').append(i18n.t(lang, "summary.tone",
            user.getTone().emoji() + " <b>" + i18n.t(lang, "tone." + user.getTone().name()) + "</b>"));
        if (full) {
            sb.append('\n').append(i18n.t(lang, "summary.explain",
                "<b>" + i18n.t(lang, user.isAutoExplain() ? "common.on" : "common.off") + "</b>"));
            sb.append('\n').append(i18n.t(lang, "summary.ui", "<b>" + i18n.t(lang, "language.name") + "</b>"));
        }
        return sb.toString();
    }

    public void sendWelcome(ChatUser user) {
        var lang = user.getUiLanguage();
        var caption = i18n.t(lang, "welcome.caption", displayName(user));
        if (!demo.send(user.getChatId(), caption, null)) {
            gateway.send(user.getChatId(), caption, null);
        }
        gateway.send(user.getChatId(), modeQuestion(lang, true), keyboards.onboardingModes(lang));
    }

    public String modeQuestion(String lang, boolean onboarding) {
        var prefix = onboarding ? i18n.t(lang, "onboarding.step", 1, 2) + " · " : "";
        return prefix + i18n.t(lang, "question.mode") + "\n\n" + i18n.t(lang, "mode.descriptions");
    }

    public String languageQuestion(String lang, boolean onboarding) {
        var prefix = onboarding ? i18n.t(lang, "onboarding.step", 2, 2) + " · " : "";
        return prefix + i18n.t(lang, "question.target");
    }

    public String onboardingDone(ChatUser user) {
        var lang = user.getUiLanguage();
        return i18n.t(lang, "onboarding.done", summary(user, false)) + "\n\n" + i18n.t(lang, "tip.inline", botUsername);
    }

    public void sendWelcomeBack(ChatUser user) {
        var lang = user.getUiLanguage();
        gateway.send(user.getChatId(), i18n.t(lang, "welcome.back", displayName(user), summary(user, false)),
            keyboards.welcomeBack(lang));
    }

    public void sendHelp(ChatUser user) {
        var text = i18n.t(user.getUiLanguage(), "help.text", botUsername);
        if (!demo.send(user.getChatId(), text, null)) {
            gateway.send(user.getChatId(), text, null);
        }
    }

    public String settingsText(ChatUser user) {
        return i18n.t(user.getUiLanguage(), "settings.title", summary(user, true));
    }

    public void sendSettings(ChatUser user) {
        gateway.send(user.getChatId(), settingsText(user), keyboards.settingsHome(user));
    }

    public void sendTargetPicker(ChatUser user) {
        gateway.send(user.getChatId(), languageQuestion(user.getUiLanguage(), false), keyboards.targetPicker(user));
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
        return keyboards.result(entry, !textService.changes(entry).isEmpty(), entry.getChatUser().getUiLanguage());
    }

    public String botUsername() {
        return botUsername;
    }

    private static String displayName(ChatUser user) {
        var name = user.getFirstName() != null && !user.getFirstName().isBlank() ? user.getFirstName() : "friend";
        return ResultFormatter.escape(name);
    }
}
