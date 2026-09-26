package io.chatbots.grammar.bot;

import io.chatbots.grammar.domain.ChatUser;
import io.chatbots.grammar.domain.Language;
import io.chatbots.grammar.domain.TextEntry;
import io.chatbots.grammar.domain.Tone;
import io.chatbots.grammar.service.I18n;
import io.chatbots.grammar.service.TextService;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.CopyTextButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.util.ArrayList;
import java.util.List;

import static io.chatbots.grammar.bot.CallbackData.NOOP;
import static io.chatbots.grammar.bot.CallbackData.PICKER;
import static io.chatbots.grammar.bot.CallbackData.RESULT;

@Component
public class Keyboards {

    /** Telegram rejects copy_text buttons with more than 256 characters. */
    static final int COPY_TEXT_LIMIT = 256;
    static final int LANGUAGE_COLUMNS = 3;
    static final String CHECK = "✓ ";

    /** Styles offered under a result; Natural is what tapping the active one returns to. */
    static final List<Tone> RESULT_TONES = List.of(Tone.FORMAL, Tone.CASUAL);

    private final I18n i18n;

    public Keyboards(I18n i18n) {
        this.i18n = i18n;
    }

    // ---- result message ----

    /**
     * Copy and style on top, then one-tap translations into the user's usual languages next to the full picker,
     * then "What changed" when there is something to explain.
     */
    public InlineKeyboardMarkup result(TextEntry entry, boolean explainable, String lang, List<Language> quick) {
        long id = entry.getId();
        var rows = new ArrayList<InlineKeyboardRow>();
        rows.add(new InlineKeyboardRow(copy(entry, lang),
            button(i18n.t(lang, "btn.style") + " · " + entry.getTone().emoji(), CallbackData.of(RESULT, "s", id, ""))));
        var languages = new InlineKeyboardRow();
        for (var language : quick) {
            languages.add(button(language.label(), CallbackData.of(RESULT, "L", id, language.code())));
        }
        languages.add(button(i18n.t(lang, "btn.language"), CallbackData.of(RESULT, "l", id, "")));
        rows.add(languages);
        if (explainable) {
            rows.add(new InlineKeyboardRow(button(
                i18n.t(lang, entry.isExplanationShown() ? "btn.hide_explain" : "btn.explain"),
                CallbackData.of(RESULT, "e", id, ""))));
        }
        return markup(rows);
    }

    public InlineKeyboardMarkup resultLanguages(TextEntry entry, String lang, List<Language> order) {
        var rows = languageGrid(order, entry.getTargetLanguage(),
            l -> CallbackData.of(RESULT, "L", entry.getId(), l.code()));
        rows.add(backToResult(entry, lang));
        return markup(rows);
    }

    /** One-off styles plus "Shorter", which stays until there's nothing left to cut. */
    public InlineKeyboardMarkup resultTones(TextEntry entry, String lang) {
        var tones = new InlineKeyboardRow();
        for (var tone : RESULT_TONES) {
            var label = tone.emoji() + " " + i18n.t(lang, "tone." + tone.name());
            tones.add(button(entry.getTone() == tone ? CHECK + label : label,
                CallbackData.of(RESULT, "t", entry.getId(), tone.name())));
        }
        var rows = new ArrayList<InlineKeyboardRow>(List.of(tones));
        if (TextService.canShorten(entry)) {
            rows.add(new InlineKeyboardRow(button(i18n.t(lang, "btn.shorter"), CallbackData.of(RESULT, "k", entry.getId(), ""))));
        }
        rows.add(backToResult(entry, lang));
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

    // ---- /language ----

    public InlineKeyboardMarkup targetPicker(ChatUser user, List<Language> order) {
        return markup(languageGrid(order, user.getTargetLanguage(), l -> CallbackData.of(PICKER, "l", l.code())));
    }

    // ---- helpers ----

    /** Copy is always offered; texts over Telegram's copy_text limit are resent as a copyable code block. */
    private InlineKeyboardButton copy(TextEntry entry, String lang) {
        var text = entry.getResultText();
        var label = i18n.t(lang, "btn.copy");
        if (text != null && !text.isEmpty() && text.length() <= COPY_TEXT_LIMIT) {
            return InlineKeyboardButton.builder().text(label).copyText(new CopyTextButton(text)).build();
        }
        return button(label, CallbackData.of(RESULT, "c", entry.getId(), ""));
    }

    private InlineKeyboardRow backToResult(TextEntry entry, String lang) {
        return new InlineKeyboardRow(button(i18n.t(lang, "btn.back"), CallbackData.of(RESULT, "b", entry.getId(), "")));
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
