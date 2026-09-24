package io.chatbots.grammar.bot;

import io.chatbots.grammar.ai.AiResult;
import io.chatbots.grammar.domain.TextAction;
import io.chatbots.grammar.domain.TextEntry;
import io.chatbots.grammar.service.I18n;

import java.util.List;

/**
 * Renders a result message. The corrected text always comes first and alone on its lines, so a long-press
 * "copy" or a forward gives the user exactly the text they want to send.
 */
public final class ResultFormatter {

    /** Telegram's limit is 4096; leave room for entities. */
    static final int MAX_MESSAGE_LENGTH = 4000;

    private ResultFormatter() {
    }

    public static String render(TextEntry entry, List<AiResult.Change> changes, I18n i18n, String lang) {
        var body = escape(entry.getResultText());
        var footer = new StringBuilder();
        if (entry.getAction() == TextAction.UNCHANGED) {
            footer.append("\n\n<i>").append(i18n.t(lang, "result.unchanged")).append("</i>");
        }
        if (entry.isExplanationShown() && !changes.isEmpty()) {
            footer.append("\n\n<blockquote>💡 <b>").append(i18n.t(lang, "result.changes")).append("</b>");
            for (var change : changes) {
                footer.append("\n• ");
                if (!change.original().isBlank()) {
                    footer.append("<s>").append(escape(change.original())).append("</s> → ");
                }
                footer.append("<b>").append(escape(change.replacement())).append("</b>");
                if (change.reason() != null && !change.reason().isBlank()) {
                    footer.append(" — <i>").append(escape(change.reason())).append("</i>");
                }
            }
            footer.append("</blockquote>");
        }
        if (body.length() + footer.length() > MAX_MESSAGE_LENGTH) {
            // The text itself matters more than the explanation.
            return body.length() > MAX_MESSAGE_LENGTH ? body.substring(0, MAX_MESSAGE_LENGTH - 1) + "…" : body;
        }
        return body + footer;
    }

    public static String escape(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
