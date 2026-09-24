package io.chatbots.grammar.bot.telegram;

import io.chatbots.grammar.bot.CallbackData;
import io.chatbots.grammar.bot.Keyboards;
import io.chatbots.grammar.domain.ChatUser;
import io.chatbots.grammar.domain.Language;
import io.chatbots.grammar.domain.Mode;
import io.chatbots.grammar.domain.TextEntry;
import io.chatbots.grammar.domain.Tone;
import io.chatbots.grammar.service.I18n;
import io.chatbots.grammar.service.ProcessingFailedException;
import io.chatbots.grammar.service.RateLimitExceededException;
import io.chatbots.grammar.service.TextService;
import io.chatbots.grammar.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.util.function.Supplier;

/** Inline button taps. Every screen edits its own message in place, so the chat never fills up with menus. */
@Component
public class CallbackHandler {

    private static final Logger log = LoggerFactory.getLogger(CallbackHandler.class);

    private final UserService users;
    private final TextService texts;
    private final BotViews views;
    private final Keyboards keyboards;
    private final TelegramGateway gateway;
    private final MessageHandler messages;
    private final I18n i18n;

    public CallbackHandler(UserService users, TextService texts, BotViews views, Keyboards keyboards,
                           TelegramGateway gateway, MessageHandler messages, I18n i18n) {
        this.users = users;
        this.texts = texts;
        this.views = views;
        this.keyboards = keyboards;
        this.gateway = gateway;
        this.messages = messages;
        this.i18n = i18n;
    }

    /** Context of one tap. */
    record Tap(String queryId, long chatId, int messageId, ChatUser user) {
        String lang() {
            return user.getUiLanguage();
        }
    }

    public void handle(CallbackQuery query) {
        var message = query.getMessage();
        var data = CallbackData.parse(query.getData());
        if (message == null || data.isEmpty() || !message.isUserMessage()) {
            gateway.answer(query.getId(), null);
            return;
        }
        var from = query.getFrom();
        var user = users.touch(new UserService.Profile(message.getChatId(), from.getUserName(), from.getFirstName(),
            from.getLanguageCode()));
        var tap = new Tap(query.getId(), message.getChatId(), message.getMessageId(), user);
        var callback = data.get();
        try {
            switch (callback.scope()) {
                case CallbackData.RESULT -> onResult(tap, callback, query.getData());
                case CallbackData.ONBOARDING -> onOnboarding(tap, callback);
                case CallbackData.SETTINGS -> onSettings(tap, callback);
                case CallbackData.PICKER -> onPicker(tap, callback);
                default -> gateway.answer(tap.queryId(), null);
            }
        } catch (IllegalArgumentException e) {
            // Unknown enum constant or language code: an outdated or forged button.
            log.info("Ignoring malformed callback '{}' from chat {}", query.getData(), tap.chatId());
            gateway.answer(tap.queryId(), null);
        }
    }

    // ---- buttons under results ----

    private void onResult(Tap tap, CallbackData callback, String raw) {
        var found = texts.find(callback.id(), tap.chatId());
        if (found.isEmpty()) {
            gateway.answer(tap.queryId(), i18n.t(tap.lang(), "toast.expired"));
            return;
        }
        var entry = found.get();
        entry.setChatUser(tap.user());
        switch (callback.action()) {
            case "g" -> rerun(tap, entry, raw, () -> texts.regenerate(entry));
            case "t" -> {
                var tone = Tone.valueOf(callback.arg());
                rerun(tap, entry, raw, () -> texts.restyle(entry, tone));
            }
            case "L" -> {
                var target = language(callback.arg());
                rerun(tap, entry, raw, () -> texts.retarget(entry, target));
            }
            case "l" -> {
                gateway.editKeyboard(tap.chatId(), tap.messageId(), keyboards.resultLanguages(entry, tap.lang()));
                gateway.answer(tap.queryId(), null);
            }
            case "b" -> {
                gateway.editKeyboard(tap.chatId(), tap.messageId(), views.resultKeyboard(entry));
                gateway.answer(tap.queryId(), null);
            }
            case "e" -> {
                if (texts.changes(entry).isEmpty()) {
                    gateway.answer(tap.queryId(), i18n.t(tap.lang(), "toast.nothing_to_explain"));
                    return;
                }
                views.showResult(texts.toggleExplanation(entry), tap.messageId());
                gateway.answer(tap.queryId(), null);
            }
            default -> gateway.answer(tap.queryId(), null);
        }
    }

    /**
     * Shows a "working" state on the message right away (a toast would vanish too quickly for multi-second
     * model calls), then puts the new result in place.
     */
    private void rerun(Tap tap, TextEntry entry, String tappedData, Supplier<TextService.Outcome> operation) {
        var wasFailed = entry.getResultText() == null;
        var original = wasFailed ? keyboards.retry(entry, tap.lang()) : views.resultKeyboard(entry);
        gateway.editKeyboard(tap.chatId(), tap.messageId(), keyboards.working(original, tappedData, tap.lang()));
        try (var ignored = gateway.typing(tap.chatId())) {
            var outcome = operation.get();
            views.showResult(outcome.entry(), tap.messageId());
            if (wasFailed) texts.attachResultMessage(outcome.entry(), tap.messageId());
            gateway.answer(tap.queryId(), outcome.unchanged() ? i18n.t(tap.lang(), "toast.same") : null);
        } catch (RateLimitExceededException e) {
            restore(tap, original);
            gateway.answer(tap.queryId(), i18n.t(tap.lang(), "error.rate_limit", e.getLimit()));
        } catch (ProcessingFailedException e) {
            restore(tap, original);
            gateway.answer(tap.queryId(), i18n.t(tap.lang(), "error.ai"));
        } catch (Exception e) {
            log.error("Result action failed in chat {}: {}", tap.chatId(), e.getMessage(), e);
            restore(tap, original);
            gateway.answer(tap.queryId(), i18n.t(tap.lang(), "error.generic"));
        }
    }

    private void restore(Tap tap, InlineKeyboardMarkup original) {
        gateway.editKeyboard(tap.chatId(), tap.messageId(), original);
    }

    // ---- first-run setup ----

    private void onOnboarding(Tap tap, CallbackData callback) {
        var chatId = tap.chatId();
        switch (callback.action()) {
            case "m" -> {
                var user = users.setMode(chatId, Mode.valueOf(callback.arg()));
                if (user.getMode().usesTargetLanguage()) {
                    gateway.editText(chatId, tap.messageId(), views.languageQuestion(user.getUiLanguage(), true),
                        keyboards.onboardingLanguages(user.getUiLanguage()));
                } else {
                    finishOnboarding(tap, user);
                }
                gateway.answer(tap.queryId(), null);
            }
            case "l" -> {
                finishOnboarding(tap, users.setTargetLanguage(chatId, language(callback.arg())));
                gateway.answer(tap.queryId(), null);
            }
            case "x" -> {
                gateway.answer(tap.queryId(), null);
                var example = i18n.t(tap.lang(), "example.text");
                var introId = gateway.send(chatId, i18n.t(tap.lang(), "example.intro")
                    + "\n\n<blockquote>" + example + "</blockquote>", null);
                messages.processAndReply(tap.user(), example, introId);
            }
            default -> gateway.answer(tap.queryId(), null);
        }
    }

    private void finishOnboarding(Tap tap, ChatUser user) {
        var done = users.completeOnboarding(user.getChatId());
        gateway.editText(tap.chatId(), tap.messageId(), views.onboardingDone(done),
            keyboards.onboardingDone(done.getUiLanguage()));
    }

    // ---- settings ----

    private void onSettings(Tap tap, CallbackData callback) {
        var chatId = tap.chatId();
        var arg = callback.arg();
        var user = tap.user();
        String toast = null;
        switch (callback.action()) {
            case "h" -> showSettingsHome(tap, user);
            case "help" -> views.sendHelp(user);
            case "c" -> gateway.delete(chatId, tap.messageId());
            case "e" -> {
                user = users.toggleAutoExplain(chatId);
                showSettingsHome(tap, user);
                toast = i18n.t(user.getUiLanguage(), "saved");
            }
            case "m" -> {
                if (arg.isEmpty()) {
                    gateway.editText(chatId, tap.messageId(), views.modeQuestion(user.getUiLanguage(), false),
                        keyboards.settingsModes(user));
                } else {
                    user = users.setMode(chatId, Mode.valueOf(arg));
                    showSettingsHome(tap, user);
                    toast = i18n.t(user.getUiLanguage(), "saved");
                }
            }
            case "l" -> {
                if (arg.isEmpty()) {
                    gateway.editText(chatId, tap.messageId(), views.languageQuestion(user.getUiLanguage(), false),
                        keyboards.settingsLanguages(user));
                } else {
                    user = users.setTargetLanguage(chatId, language(arg));
                    if (user.getMode() == Mode.FIX) user = users.setMode(chatId, Mode.SMART);
                    showSettingsHome(tap, user);
                    toast = i18n.t(user.getUiLanguage(), "saved");
                }
            }
            case "t" -> {
                if (arg.isEmpty()) {
                    gateway.editText(chatId, tap.messageId(), i18n.t(user.getUiLanguage(), "question.tone"),
                        keyboards.settingsTones(user));
                } else {
                    user = users.setTone(chatId, Tone.valueOf(arg));
                    showSettingsHome(tap, user);
                    toast = i18n.t(user.getUiLanguage(), "saved");
                }
            }
            case "u" -> {
                if (arg.isEmpty()) {
                    gateway.editText(chatId, tap.messageId(), i18n.t(user.getUiLanguage(), "question.ui"),
                        keyboards.settingsUiLanguages(user));
                } else {
                    if (!I18n.SUPPORTED.contains(arg)) throw new IllegalArgumentException("Unsupported UI " + arg);
                    user = users.setUiLanguage(chatId, arg);
                    showSettingsHome(tap, user);
                    toast = i18n.t(user.getUiLanguage(), "saved");
                }
            }
            default -> {
                // unknown action: just acknowledge
            }
        }
        gateway.answer(tap.queryId(), toast);
    }

    private void showSettingsHome(Tap tap, ChatUser user) {
        gateway.editText(tap.chatId(), tap.messageId(), views.settingsText(user), keyboards.settingsHome(user));
    }

    // ---- /language picker ----

    private void onPicker(Tap tap, CallbackData callback) {
        var user = users.setTargetLanguage(tap.chatId(), language(callback.arg()));
        if (user.getMode() == Mode.FIX) user = users.setMode(tap.chatId(), Mode.SMART);
        gateway.editText(tap.chatId(), tap.messageId(),
            i18n.t(user.getUiLanguage(), "saved.target", user.getTargetLanguage().label()), null);
        gateway.answer(tap.queryId(), null);
    }

    private static Language language(String code) {
        return Language.fromCode(code).orElseThrow(() -> new IllegalArgumentException("Unknown language " + code));
    }
}
