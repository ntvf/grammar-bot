package io.chatbots.grammar.bot;

import io.chatbots.grammar.ai.AiResult;
import io.chatbots.grammar.domain.TextAction;
import io.chatbots.grammar.domain.TextEntry;
import io.chatbots.grammar.service.I18n;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResultFormatterTest {

    private final I18n i18n = new I18n();

    private static TextEntry entry(String text, TextAction action, boolean explanationShown) {
        var entry = new TextEntry();
        entry.setResultText(text);
        entry.setAction(action);
        entry.setExplanationShown(explanationShown);
        return entry;
    }

    @Test
    void correctedText_isRenderedAloneAndEscaped() {
        var html = ResultFormatter.render(entry("1 < 2 & <b>x</b>", TextAction.CORRECTED, false), List.of(), i18n, "en");
        assertThat(html).isEqualTo("1 &lt; 2 &amp; &lt;b&gt;x&lt;/b&gt;");
    }

    @Test
    void unchanged_addsFooter() {
        var html = ResultFormatter.render(entry("All good.", TextAction.UNCHANGED, false), List.of(), i18n, "en");
        assertThat(html).startsWith("All good.\n\n<i>✅ Looks good");
    }

    @Test
    void explanation_isShownOnlyWhenToggledOn() {
        var changes = List.of(new AiResult.Change("has went", "went", "past simple"),
            new AiResult.Change("", "the", ""));
        var hidden = ResultFormatter.render(entry("I went.", TextAction.CORRECTED, false), changes, i18n, "en");
        assertThat(hidden).isEqualTo("I went.");

        var shown = ResultFormatter.render(entry("I went.", TextAction.CORRECTED, true), changes, i18n, "uk");
        assertThat(shown)
            .startsWith("I went.\n\n<blockquote>💡 <b>Що змінено:</b>")
            .contains("• <s>has went</s> → <b>went</b> — <i>past simple</i>")
            .contains("• <b>the</b>")
            .endsWith("</blockquote>");
    }

    @Test
    void tooLong_dropsExplanationThenTruncates() {
        var changes = List.of(new AiResult.Change("a", "b", "c".repeat(200)));
        var nearLimit = "x".repeat(ResultFormatter.MAX_MESSAGE_LENGTH - 50);
        assertThat(ResultFormatter.render(entry(nearLimit, TextAction.CORRECTED, true), changes, i18n, "en"))
            .isEqualTo(nearLimit);

        var huge = "y".repeat(ResultFormatter.MAX_MESSAGE_LENGTH + 100);
        var rendered = ResultFormatter.render(entry(huge, TextAction.CORRECTED, false), List.of(), i18n, "en");
        assertThat(rendered).hasSize(ResultFormatter.MAX_MESSAGE_LENGTH).endsWith("…");
    }

    @Test
    void escape_null() {
        assertThat(ResultFormatter.escape(null)).isEmpty();
    }
}
