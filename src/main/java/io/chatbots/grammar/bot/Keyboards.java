package io.chatbots.grammar.bot;

import io.chatbots.grammar.domain.ChatUser;
import io.chatbots.grammar.domain.Language;
import io.chatbots.grammar.domain.Mode;
import io.chatbots.grammar.domain.TextEntry;
import io.chatbots.grammar.domain.Tone;
import io.chatbots.grammar.service.I18n;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.CopyTextButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.util.ArrayList;
import java.util.List;

import static io.chatbots.grammar.bot.CallbackData.NOOP;
import static io.chatbots.grammar.bot.CallbackData.ONBOARDING;
import static io.chatbots.grammar.bot.CallbackData.PICKER;
import static io.chatbots.grammar.bot.CallbackData.RESULT;
import static io.chatbots.grammar.bot.CallbackData.SETTINGS;

@Component
public class Keyboards {

    /** Telegram rejects copy_text buttons with more than 256 characters. */
    static final int COPY_TEXT_LIMIT = 256;
    static final int LANGUAGE_COLUMNS = 3;
    static final String CHECK = "✓ ";

    /** Styles shown right under a result; the rest live in /settings. */
    static final List<Tone> QUICK_TONES = List.of(Tone.FORMAL, Tone.CASUAL, Tone.SHORTER);

    private final I18n i18n;

    public Keyboards(I18n i18n) {
        this.i18n = i18n;
    }

    // ---- result message ----

    public InlineKeyboardMarkup result(TextEntry entry, boolean hasChanges, String lang) {
        long id = entry.getId();
        var top = new InlineKeyboardRow();
        top.add(button(i18n.t(lang, "btn.regenerate"), CallbackData.of(RESULT, "g", id, "")));
        var text = entry.getResultText();
        if (text != null && text.length() <= COPY_TEXT_LIMIT) {
            top.add(InlineKeyboardButton.builder().text(i18n.t(lang, "btn.copy"))
                .copyText(new CopyTextButton(text)).build());
        }
        var tones = new InlineKeyboardRow();
        for (var tone : QUICK_TONES) {
            var label = tone.emoji() + " " + i18n.t(lang, "tone." + tone.name());
            tones.add(button(entry.getTone() == tone ? CHECK + label : label,
                CallbackData.of(RESULT, "t", id, tone.name())));
        }
        var bottom = new InlineKeyboardRow();
        bottom.add(button(i18n.t(lang, "btn.language") + " · " + entry.getTargetLanguage().flag(),
            CallbackData.of(RESULT, "l", id, "")));
        if (hasChanges) {
            bottom.add(button(i18n.t(lang, entry.isExplanationShown() ? "btn.hide_explain" : "btn.explain"),
                CallbackData.of(RESULT, "e", id, "")));
        }
        return markup(List.of(top, tones, bottom));
    }

    public InlineKeyboardMarkup resultLanguages(TextEntry entry, String lang) {
        var current = entry.getTargetLanguage();
        var rows = languageGrid(Language.ordered(current, I18n.asLanguage(lang), Language.EN), current,
            l -> CallbackData.of(RESULT, "L", entry.getId(), l.code()));
        rows.add(new InlineKeyboardRow(button(i18n.t(lang, "btn.back"), CallbackData.of(RESULT, "b", entry.getId(), ""))));
        return markup(rows);
    }

    /**
     * The same keyboard with only the tapped button turned into "Working on it…", so nothing jumps around
     * while the model thinks. Falls back to a single working button if the tapped one isn't found.
     */
    public InlineKeyboardMarkup working(InlineKeyboardMarkup current, String tappedData, String lang) {
        var working = button(i18n.t(lang, "btn.working"), CallbackData.of(NOOP, ""));
        var rows = new ArrayList<InlineKeyboardRow>();
        var replaced = false;
        for (var row : current.getKeyboard()) {
            var copy = new InlineKeyboardRow();
            for (var b : row) {
                if (!replaced && tappedData.equals(b.getCallbackData())) {
                    copy.add(working);
                    replaced = true;
                } else {
                    copy.add(b);
                }
            }
            rows.add(copy);
        }
        return replaced ? markup(rows) : markup(List.of(new InlineKeyboardRow(working)));
    }

    public InlineKeyboardMarkup retry(TextEntry entry, String lang) {
        var retry = InlineKeyboardButton.builder().text(i18n.t(lang, "btn.retry"))
            .callbackData(CallbackData.of(RESULT, "g", entry.getId(), "")).style("primary").build();
        return markup(List.of(new InlineKeyboardRow(retry)));
    }

    // ---- onboarding ----

    public InlineKeyboardMarkup onboardingModes(String lang) {
        var rows = new ArrayList<InlineKeyboardRow>();
        for (var mode : Mode.values()) {
            rows.add(new InlineKeyboardRow(button(mode.emoji() + " " + i18n.t(lang, "mode." + mode.name()),
                CallbackData.of(ONBOARDING, "m", mode.name()))));
        }
        return markup(rows);
    }

    public InlineKeyboardMarkup onboardingLanguages(String lang) {
        return markup(languageGrid(Language.ordered(Language.EN, I18n.asLanguage(lang)), null,
            l -> CallbackData.of(ONBOARDING, "l", l.code())));
    }

    public InlineKeyboardMarkup onboardingDone(String lang) {
        var tryIt = InlineKeyboardButton.builder().text(i18n.t(lang, "btn.try_example"))
            .callbackData(CallbackData.of(ONBOARDING, "x")).style("success").build();
        return markup(List.of(
            new InlineKeyboardRow(tryIt),
            new InlineKeyboardRow(button(i18n.t(lang, "btn.settings"), CallbackData.of(SETTINGS, "h")))));
    }

    public InlineKeyboardMarkup welcomeBack(String lang) {
        return markup(List.of(new InlineKeyboardRow(
            button(i18n.t(lang, "btn.settings"), CallbackData.of(SETTINGS, "h")),
            button(i18n.t(lang, "btn.help"), CallbackData.of(SETTINGS, "help")))));
    }

    // ---- settings ----

    public InlineKeyboardMarkup settingsHome(ChatUser user) {
        var lang = user.getUiLanguage();
        var onOff = i18n.t(lang, user.isAutoExplain() ? "common.on" : "common.off");
        return markup(List.of(
            new InlineKeyboardRow(
                button(user.getMode().emoji() + " " + i18n.t(lang, "settings.btn.mode"), CallbackData.of(SETTINGS, "m")),
                button(user.getTargetLanguage().flag() + " " + i18n.t(lang, "settings.btn.target"),
                    CallbackData.of(SETTINGS, "l"))),
            new InlineKeyboardRow(
                button(user.getTone().emoji() + " " + i18n.t(lang, "settings.btn.tone"), CallbackData.of(SETTINGS, "t")),
                button("💡 " + i18n.t(lang, "settings.btn.explain", onOff), CallbackData.of(SETTINGS, "e"))),
            new InlineKeyboardRow(
                button("🗣 " + i18n.t(lang, "settings.btn.ui"), CallbackData.of(SETTINGS, "u"))),
            new InlineKeyboardRow(
                button(i18n.t(lang, "btn.close"), CallbackData.of(SETTINGS, "c")))));
    }

    public InlineKeyboardMarkup settingsModes(ChatUser user) {
        var lang = user.getUiLanguage();
        var rows = new ArrayList<InlineKeyboardRow>();
        for (var mode : Mode.values()) {
            var label = mode.emoji() + " " + i18n.t(lang, "mode." + mode.name());
            rows.add(new InlineKeyboardRow(button(user.getMode() == mode ? CHECK + label : label,
                CallbackData.of(SETTINGS, "m", mode.name()))));
        }
        rows.add(backToSettings(lang));
        return markup(rows);
    }

    public InlineKeyboardMarkup settingsTones(ChatUser user) {
        var lang = user.getUiLanguage();
        var rows = new ArrayList<InlineKeyboardRow>();
        var row = new InlineKeyboardRow();
        for (var tone : Tone.values()) {
            var label = tone.emoji() + " " + i18n.t(lang, "tone." + tone.name());
            row.add(button(user.getTone() == tone ? CHECK + label : label, CallbackData.of(SETTINGS, "t", tone.name())));
            if (row.size() == 2) {
                rows.add(row);
                row = new InlineKeyboardRow();
            }
        }
        if (!row.isEmpty()) rows.add(row);
        rows.add(backToSettings(lang));
        return markup(rows);
    }

    public InlineKeyboardMarkup settingsLanguages(ChatUser user) {
        var current = user.getTargetLanguage();
        var rows = languageGrid(Language.ordered(current, I18n.asLanguage(user.getUiLanguage()), Language.EN), current,
            l -> CallbackData.of(SETTINGS, "l", l.code()));
        rows.add(backToSettings(user.getUiLanguage()));
        return markup(rows);
    }

    public InlineKeyboardMarkup settingsUiLanguages(ChatUser user) {
        var rows = new ArrayList<InlineKeyboardRow>();
        var row = new InlineKeyboardRow();
        for (var code : I18n.SUPPORTED) {
            var label = i18n.t(code, "language.name");
            row.add(button(code.equals(user.getUiLanguage()) ? CHECK + label : label, CallbackData.of(SETTINGS, "u", code)));
            if (row.size() == 2) {
                rows.add(row);
                row = new InlineKeyboardRow();
            }
        }
        if (!row.isEmpty()) rows.add(row);
        rows.add(backToSettings(user.getUiLanguage()));
        return markup(rows);
    }

    public InlineKeyboardMarkup targetPicker(ChatUser user) {
        var current = user.getTargetLanguage();
        return markup(languageGrid(Language.ordered(current, I18n.asLanguage(user.getUiLanguage()), Language.EN),
            current, l -> CallbackData.of(PICKER, "l", l.code())));
    }

    // ---- helpers ----

    private InlineKeyboardRow backToSettings(String lang) {
        return new InlineKeyboardRow(button(i18n.t(lang, "btn.back"), CallbackData.of(SETTINGS, "h")));
    }

    private static List<InlineKeyboardRow> languageGrid(List<Language> languages, Language current,
                                                        java.util.function.Function<Language, String> callback) {
        var rows = new ArrayList<InlineKeyboardRow>();
        var row = new InlineKeyboardRow();
        for (var language : languages) {
            var label = language == current ? CHECK + language.label() : language.label();
            row.add(button(label, callback.apply(language)));
            if (row.size() == LANGUAGE_COLUMNS) {
                rows.add(row);
                row = new InlineKeyboardRow();
            }
        }
        if (!row.isEmpty()) rows.add(row);
        return rows;
    }

    private static InlineKeyboardButton button(String text, String callbackData) {
        return InlineKeyboardButton.builder().text(text).callbackData(callbackData).build();
    }

    private static InlineKeyboardMarkup markup(List<InlineKeyboardRow> rows) {
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }
}
