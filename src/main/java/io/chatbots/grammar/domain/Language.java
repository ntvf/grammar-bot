package io.chatbots.grammar.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Languages the bot can write results in. */
public enum Language {
    EN("en", "🇬🇧", "English", "English"),
    UK("uk", "🇺🇦", "Ukrainian", "Українська"),
    DE("de", "🇩🇪", "German", "Deutsch"),
    ES("es", "🇪🇸", "Spanish", "Español"),
    FR("fr", "🇫🇷", "French", "Français"),
    IT("it", "🇮🇹", "Italian", "Italiano"),
    PT("pt", "🇵🇹", "Portuguese", "Português"),
    PL("pl", "🇵🇱", "Polish", "Polski"),
    NL("nl", "🇳🇱", "Dutch", "Nederlands"),
    CS("cs", "🇨🇿", "Czech", "Čeština"),
    SV("sv", "🇸🇪", "Swedish", "Svenska"),
    RO("ro", "🇷🇴", "Romanian", "Română"),
    TR("tr", "🇹🇷", "Turkish", "Türkçe"),
    RU("ru", "🇷🇺", "Russian", "Русский"),
    AR("ar", "🇸🇦", "Arabic", "العربية"),
    HI("hi", "🇮🇳", "Hindi", "हिन्दी"),
    ZH("zh", "🇨🇳", "Chinese", "中文"),
    JA("ja", "🇯🇵", "Japanese", "日本語"),
    KO("ko", "🇰🇷", "Korean", "한국어"),
    HE("he", "🇮🇱", "Hebrew", "עברית"),
    KA("ka", "🇬🇪", "Georgian", "ქართული");

    private final String code;
    private final String flag;
    private final String englishName;
    private final String nativeName;

    Language(String code, String flag, String englishName, String nativeName) {
        this.code = code;
        this.flag = flag;
        this.englishName = englishName;
        this.nativeName = nativeName;
    }

    public String code() {
        return code;
    }

    public String flag() {
        return flag;
    }

    public String englishName() {
        return englishName;
    }

    public String nativeName() {
        return nativeName;
    }

    public String label() {
        return flag + " " + nativeName;
    }

    /** Resolves ISO 639-1 codes and IETF tags like "pt-BR"; unknown codes yield empty. */
    public static Optional<Language> fromCode(String code) {
        if (code == null || code.isBlank()) return Optional.empty();
        var primary = code.trim().toLowerCase(Locale.ROOT).split("[-_]")[0];
        return Arrays.stream(values()).filter(l -> l.code.equals(primary)).findFirst();
    }

    /** All languages with the preferred ones moved to the front, in the given order. */
    public static List<Language> ordered(Language... preferred) {
        var result = new java.util.ArrayList<Language>();
        for (var l : preferred) {
            if (l != null && !result.contains(l)) result.add(l);
        }
        for (var l : values()) {
            if (!result.contains(l)) result.add(l);
        }
        return List.copyOf(result);
    }
}
