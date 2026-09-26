package io.chatbots.grammar.integration;

import io.chatbots.grammar.domain.ChatUser;
import io.chatbots.grammar.domain.Language;
import io.chatbots.grammar.domain.Mode;
import io.chatbots.grammar.domain.TextAction;
import io.chatbots.grammar.domain.TextStatus;
import io.chatbots.grammar.domain.Tone;
import io.chatbots.grammar.service.ProcessingFailedException;
import io.chatbots.grammar.service.RetentionService;
import io.chatbots.grammar.service.StatisticsService;
import io.chatbots.grammar.service.TextService;
import io.chatbots.grammar.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TextServiceIntegrationTest extends IntegrationTest {

    @Autowired TextService texts;
    @Autowired UserService users;
    @Autowired RetentionService retention;
    @Autowired StatisticsService statistics;

    private ChatUser user(long chatId) {
        return users.touch(new UserService.Profile(chatId, "ann", "Ann", "uk"), "ref");
    }

    @Test
    void newUser_getsDefaults_andProfileUpdatesFollowTelegram() {
        var created = user(1L);
        assertThat(created.getUiLanguage()).isEqualTo("uk");
        assertThat(created.getTargetLanguage()).isEqualTo(Language.EN);
        assertThat(created.getMode()).isEqualTo(Mode.SMART);
        assertThat(created.getTone()).isEqualTo(Tone.NATURAL);

        var again = users.touch(new UserService.Profile(1L, "ann2", "Anna", "de"), "other");
        assertThat(again.getUsername()).isEqualTo("ann2");
        assertThat(again.getUiLanguage()).isEqualTo("de");
        assertThat(again.getSource()).isEqualTo("ref");

        var noLanguage = users.touch(new UserService.Profile(1L, "ann2", "Anna", null));
        assertThat(noLanguage.getUiLanguage()).isEqualTo("de");
        assertThat(chatUsers.count()).isEqualTo(1);
    }

    @Test
    void blankText_isRejected() {
        assertThatThrownBy(() -> texts.process(user(1L), "   ", 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failedEntry_isStoredForRetry_andGoodResultSurvivesALaterFailure() {
        var user = user(1L);
        aiFails();
        assertThatThrownBy(() -> texts.process(user, "hello", 1))
            .isInstanceOfSatisfying(ProcessingFailedException.class,
                e -> assertThat(e.getEntry().getStatus()).isEqualTo(TextStatus.FAILED));

        aiReturns("Hello.", TextAction.CORRECTED);
        var entry = texts.process(user, "hello", 2);
        aiFails();
        assertThatThrownBy(() -> texts.restyle(entry, Tone.FORMAL)).isInstanceOf(ProcessingFailedException.class);
        var stored = textEntries.findById(entry.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(TextStatus.DONE);
        assertThat(stored.getResultText()).isEqualTo("Hello.");
        assertThat(stored.getTone()).isEqualTo(Tone.NATURAL);
    }

    @Test
    void reprocessEdited_skipsIdenticalText() {
        var user = user(1L);
        texts.process(user, "hello", 5);
        assertThat(texts.reprocessEdited(user, 5, " hello ")).isEmpty();
        assertThat(texts.reprocessEdited(user, 5, "hello there")).isPresent();
    }

    @Test
    void changes_toleratesCorruptJson() {
        var entry = texts.process(user(1L), "hello", 1);
        entry.setChangesJson("{not json");
        assertThat(texts.changes(entry)).isEmpty();
    }

    @Test
    void aiLog_storesContent() {
        texts.process(user(1L), "hello", 1);
        var log = aiInteractions.findAll().getFirst();
        assertThat(log.getRequestText()).isEqualTo("hello");
        assertThat(log.getResponseJson()).contains(FIXED);
        assertThat(log.getOutcome()).isEqualTo("OK");
        assertThat(log.getKind()).isEqualTo("MESSAGE");
    }

    @Test
    void retention_removesOnlyOldData() {
        var user = user(1L);
        var old = texts.process(user, "old", 1);
        old.setCreatedAt(LocalDateTime.now().minusDays(40));
        textEntries.save(old);
        texts.process(user, "fresh", 2);

        retention.purge();

        assertThat(textEntries.findAll()).extracting(e -> e.getSourceText()).containsExactly("fresh");
    }

    @Test
    void statistics_reportCountsAndBreakdowns() {
        texts.process(user(1L), "hello", 1);
        users.touch(new UserService.Profile(2L, null, null, "de"));

        var report = statistics.buildReport();

        assertThat(report).contains("Users: 2").contains("Texts 24h / 7d: 1 / 1")
            .contains("AI calls 24h: 1 ok, 0 failed").contains("SMART: 2").contains("ref: 1");
    }
}
