package io.chatbots.grammar.bot.telegram;

import io.chatbots.grammar.bot.CallbackData;
import io.chatbots.grammar.bot.Keyboards;
import io.chatbots.grammar.bot.ResultFormatter;
import io.chatbots.grammar.domain.ChatUser;
import io.chatbots.grammar.domain.Language;
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

import java.util.List;
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
    private final I18n i18n;

    public CallbackHandler(UserService users, TextService texts, BotViews views, Keyboards keyboards,
                           TelegramGateway gateway, I18n i18n) {
        this.users = users;
        this.texts = texts;
        this.views = views;
        this.keyboards = keyboards;
        this.gateway = gateway;
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
            case "g" -> rerun(tap, entry, raw, shownKeyboard(tap, entry), () -> texts.regenerate(entry));
            case "t" -> {
                var tone = Tone.valueOf(callback.arg());
                rerun(tap, entry, raw, keyboards.resultTones(entry, tap.lang()), () -> texts.restyle(entry, tone));
            }
            case "k" -> rerun(tap, entry, raw, keyboards.resultTones(entry, tap.lang()), () -> texts.shorten(entry));
            case "L" -> {
                var target = language(callback.arg());
                // Quick buttons sit on the result itself, the rest in the full picker.
                var result = views.resultKeyboard(entry);
                var shown = hasButton(result, raw) ? result : resultLanguages(tap, entry);
                rerun(tap, entry, raw, shown, () -> texts.retarget(entry, target));
            }
            case "l" -> {
                gateway.editKeyboard(tap.chatId(), tap.messageId(), resultLanguages(tap, entry));
                gateway.answer(tap.queryId(), null);
            }
            case "s" -> {
                gateway.editKeyboard(tap.chatId(), tap.messageId(), keyboards.resultTones(entry, tap.lang()));
                gateway.answer(tap.queryId(), null);
            }
            case "b" -> {
                gateway.editKeyboard(tap.chatId(), tap.messageId(), views.resultKeyboard(entry));
                gateway.answer(tap.queryId(), null);
            }
            case "c" -> {
                // Too long for a copy_text button: a code block gets its own copy button in Telegram clients.
                if (entry.getResultText() != null) {
                    gateway.send(tap.chatId(), "<pre>" + ResultFormatter.escape(entry.getResultText()) + "</pre>",
                        null, tap.messageId());
                }
                gateway.answer(tap.queryId(), null);
            }
            case "e" -> {
                if (!views.explainable(entry)) {
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
    private void rerun(Tap tap, TextEntry entry, String tappedData, InlineKeyboardMarkup original,
                       Supplier<TextService.Outcome> operation) {
        var wasFailed = entry.getResultText() == null;
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

    private static boolean hasButton(InlineKeyboardMarkup keyboard, String data) {
        return keyboard.getKeyboard().stream().flatMap(List::stream).anyMatch(b -> data.equals(b.getCallbackData()));
    }

    private InlineKeyboardMarkup resultLanguages(Tap tap, TextEntry entry) {
        return keyboards.resultLanguages(entry, tap.lang(), texts.languageOrder(tap.user(), entry.getTargetLanguage()));
    }

    /** The keyboard the tapped button sits on: a retry button after a failure, the result buttons otherwise. */
    private InlineKeyboardMarkup shownKeyboard(Tap tap, TextEntry entry) {
        return entry.getResultText() == null ? keyboards.retry(entry, tap.lang()) : views.resultKeyboard(entry);
    }

    private void restore(Tap tap, InlineKeyboardMarkup original) {
        gateway.editKeyboard(tap.chatId(), tap.messageId(), original);
    }

    // ---- /language picker ----

    private void onPicker(Tap tap, CallbackData callback) {
        var user = users.setTargetLanguage(tap.chatId(), language(callback.arg()));
        gateway.editText(tap.chatId(), tap.messageId(),
            i18n.t(user.getUiLanguage(), "saved.target", user.getTargetLanguage().label()), null);
        gateway.answer(tap.queryId(), null);
    }

    private static Language language(String code) {
        return Language.fromCode(code).filter(Language::isTarget)
            .orElseThrow(() -> new IllegalArgumentException("Unknown language " + code));
    }
}
