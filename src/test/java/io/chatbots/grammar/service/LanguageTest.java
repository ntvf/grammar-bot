package io.chatbots.grammar.service;

import io.chatbots.grammar.domain.Language;
import io.chatbots.grammar.domain.Mode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class LanguageTest {

    @Test
    void fromCode_acceptsTagsAndIsCaseInsensitive() {
        assertThat(Language.fromCode("pt-BR")).contains(Language.PT);
        assertThat(Language.fromCode(" UK ")).contains(Language.UK);
        assertThat(Language.fromCode("zh_Hans")).contains(Language.ZH);
        assertThat(Language.fromCode("xx")).isEmpty();
        assertThat(Language.fromCode(null)).isEmpty();
        assertThat(Language.fromCode(" ")).isEmpty();
    }

    @Test
    void ordered_putsPreferredFirstWithoutDuplicates() {
        var ordered = Language.ordered(Language.DE, null, Language.EN, Language.DE);
        assertThat(ordered).startsWith(Language.DE, Language.EN)
            .hasSize(Language.TARGETS.size()).doesNotHaveDuplicates();
        assertThat(Language.ordered(Language.RU)).isEqualTo(Language.TARGETS);
    }

    @Test
    void codesAreUniqueAndLabelsHaveFlags() {
        assertThat(Arrays.stream(Language.values()).map(Language::code)).doesNotHaveDuplicates();
        assertThat(Language.UK.label()).isEqualTo("🇺🇦 Українська");
    }

    @Test
    void onlyFixModeIgnoresTargetLanguage() {
        assertThat(Mode.FIX.usesTargetLanguage()).isFalse();
        assertThat(Mode.SMART.usesTargetLanguage()).isTrue();
        assertThat(Mode.TRANSLATE.usesTargetLanguage()).isTrue();
    }
}
