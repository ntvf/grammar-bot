package io.chatbots.grammar.service;

import io.chatbots.grammar.domain.Language;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Interface strings. Uses plain {0}-style substitution instead of MessageFormat, so translators never have
 * to think about escaping apostrophes (l’exemple, п'ятниця…).
 */
@Component
public class I18n {

    public static final String DEFAULT_LANGUAGE = "en";
    public static final List<String> SUPPORTED = List.of("en", "uk", "ru", "de", "es", "fr", "it", "pl", "pt", "tr");

    private final Map<String, Properties> bundles = new LinkedHashMap<>();

    public I18n() {
        for (var lang : SUPPORTED) {
            bundles.put(lang, load(lang));
        }
    }

    public String t(String lang, String key, Object... args) {
        var value = bundles.get(resolve(lang)).getProperty(key);
        if (value == null) value = bundles.get(DEFAULT_LANGUAGE).getProperty(key);
        if (value == null) return key;
        for (int i = 0; i < args.length; i++) {
            value = value.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return value;
    }

    /** Maps a Telegram language_code ("pt-br", "uk", null…) to a supported interface language. */
    public static String resolve(String languageCode) {
        if (languageCode == null || languageCode.isBlank()) return DEFAULT_LANGUAGE;
        var primary = languageCode.trim().toLowerCase(Locale.ROOT).split("[-_]")[0];
        return SUPPORTED.contains(primary) ? primary : DEFAULT_LANGUAGE;
    }

    /** Every interface language is also a result language, so this never falls back in practice. */
    public static Language asLanguage(String uiLanguage) {
        return Language.fromCode(uiLanguage).orElse(Language.EN);
    }

    Map<String, Properties> bundles() {
        return bundles;
    }

    private static Properties load(String lang) {
        var path = "/i18n/messages_" + lang + ".properties";
        var props = new Properties();
        try (var in = I18n.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Missing message bundle " + path);
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return props;
    }
}
