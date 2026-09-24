package io.chatbots.grammar.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StatisticsServiceTest {

    @Test
    void describeUptime() {
        assertThat(StatisticsService.describeUptime(Duration.ofMinutes(5))).isEqualTo("5m");
        assertThat(StatisticsService.describeUptime(Duration.ofMinutes(125))).isEqualTo("2h 5m");
        assertThat(StatisticsService.describeUptime(Duration.ofHours(50))).isEqualTo("2d 2h");
    }

    @Test
    void breakdown_skipsEmpty() {
        var sb = new StringBuilder();
        StatisticsService.appendBreakdown(sb, "Modes", new ArrayList<>());
        assertThat(sb).isEmpty();
        StatisticsService.appendBreakdown(sb, "Modes", List.<Object[]>of(new Object[]{"SMART", 3L}));
        assertThat(sb.toString()).isEqualTo("\nModes:\n  • SMART: 3\n");
    }
}
