package io.chatbots.grammar.service;

import io.chatbots.grammar.domain.Language;
import io.chatbots.grammar.domain.Tone;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Properties;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Quality gate for translations: a missing key or placeholder in any language fails the build. */
class I18nTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d}");
    private static final Pattern HTML_TAG = Pattern.compile("</?(b|i|code|s|blockquote)>");

    private final I18n i18n = new I18n();

    @Test
    void everyLanguageHasExactlyTheEnglishKeys() {
        var english = new TreeSet<>(i18n.bundles().get("en").stringPropertyNames());
        i18n.bundles().forEach((lang, props) ->
            assertThat(new TreeSet<>(props.stringPropertyNames())).as("keys of %s", lang).isEqualTo(english));
    }

    @Test
    void placeholdersAndHtmlTagsMatchEnglish() {
        var english = i18n.bundles().get("en");
        i18n.bundles().forEach((lang, props) -> {
            for (var key : english.stringPropertyNames()) {
                assertThat(tokens(PLACEHOLDER, props, key)).as("%s placeholders in %s", key, lang)
                    .isEqualTo(tokens(PLACEHOLDER, english, key));
                assertThat(tokens(HTML_TAG, props, key)).as("%s tags in %s", key, lang)
                    .isEqualTo(tokens(HTML_TAG, english, key));
            }
        });
    }

    @Test
    void everyEnumHasALabel() {
        for (var lang : I18n.SUPPORTED) {
            for (var tone : Tone.values()) assertThat(i18n.t(lang, "tone." + tone.name())).doesNotStartWith("tone.");
        }
    }

    @Test
    void telegramLimitsAreRespected() {
        for (var lang : I18n.SUPPORTED) {
            assertThat(i18n.t(lang, "bot.short_description")).as(lang).hasSizeLessThanOrEqualTo(120);
            assertThat(i18n.t(lang, "bot.description", "a_32_characters_long_bot_username")).as(lang)
                .hasSizeLessThanOrEqualTo(512);
            assertThat(i18n.t(lang, "help.text", "a_32_characters_long_bot_username")).as(lang)
                .hasSizeLessThanOrEqualTo(1024);
            for (var cmd : new String[]{"cmd.language", "cmd.help"}) {
                assertThat(i18n.t(lang, cmd)).as(lang).hasSizeBetween(3, 256);
            }
        }
    }

    @Test
    void everyInterfaceLanguageIsAlsoAResultLanguage() {
        for (var lang : I18n.SUPPORTED) {
            assertThat(Language.fromCode(lang)).as(lang).isPresent();
        }
    }

    @Test
    void substitutesArgumentsLiterally() {
        assertThat(i18n.t("en", "error.too_long", 2500, 2000)).contains("2500").contains("2000");
        assertThat(i18n.t("fr", "welcome.caption", "Zoé")).contains("Zoé").contains("n’importe");
    }

    @Test
    void unknownKeyReturnsKey_unknownLanguageFallsBackToEnglish() {
        assertThat(i18n.t("en", "no.such.key")).isEqualTo("no.such.key");
        assertThat(i18n.t("xx", "btn.back")).isEqualTo("⬅️ Back");
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {"uk,uk", "pt-BR,pt", "de_AT,de", "UK,uk", "ja,en", "'',en", "null,en"})
    void resolve(String telegramCode, String expected) {
        assertThat(I18n.resolve(telegramCode)).isEqualTo(expected);
    }

    @Test
    void asLanguage() {
        assertThat(I18n.asLanguage("uk")).isEqualTo(Language.UK);
        assertThat(I18n.asLanguage("zz")).isEqualTo(Language.EN);
    }

    private static java.util.List<String> tokens(Pattern pattern, Properties props, String key) {
        var matcher = pattern.matcher(props.getProperty(key, ""));
        var found = new java.util.ArrayList<String>();
        while (matcher.find()) found.add(matcher.group());
        java.util.Collections.sort(found);
        return found;
    }
}
