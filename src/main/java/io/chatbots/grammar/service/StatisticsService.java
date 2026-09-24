package io.chatbots.grammar.service;

import io.chatbots.grammar.domain.AiInteractionRepository;
import io.chatbots.grammar.domain.ChatUserRepository;
import io.chatbots.grammar.domain.TextEntryRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/** Admin-only /stats report. */
@Service
@Transactional(readOnly = true)
public class StatisticsService {

    private final ChatUserRepository users;
    private final TextEntryRepository texts;
    private final AiInteractionRepository interactions;
    private final BuildProperties buildProperties;
    private final Clock clock;

    public StatisticsService(ChatUserRepository users, TextEntryRepository texts,
                             AiInteractionRepository interactions,
                             ObjectProvider<BuildProperties> buildProperties, Clock clock) {
        this.users = users;
        this.texts = texts;
        this.interactions = interactions;
        this.buildProperties = buildProperties.getIfAvailable();
        this.clock = clock;
    }

    public String buildReport() {
        var now = LocalDateTime.now(clock);
        var dayAgo = now.minusDays(1);
        var weekAgo = now.minusDays(7);

        var sb = new StringBuilder("📊 <b>Bot statistics</b>\n\n");
        sb.append("🏷 Version: ").append(describeBuild()).append('\n');
        sb.append("⏱ Uptime: ").append(describeUptime(
            Duration.ofMillis(ManagementFactory.getRuntimeMXBean().getUptime()))).append("\n\n");

        sb.append("👥 Users: ").append(users.count())
            .append(" (onboarded ").append(users.countByOnboardedTrue()).append(")\n");
        sb.append("🟢 Active 24h / 7d: ").append(users.countByLastActiveAtAfter(dayAgo))
            .append(" / ").append(users.countByLastActiveAtAfter(weekAgo)).append('\n');
        sb.append("📝 Texts 24h / 7d: ").append(texts.countByCreatedAtAfter(dayAgo))
            .append(" / ").append(texts.countByCreatedAtAfter(weekAgo)).append('\n');

        var ok = interactions.countByCreatedAtAfterAndOutcome(dayAgo, "OK");
        var errors = interactions.countByCreatedAtAfterAndOutcome(dayAgo, "ERROR");
        var latency = interactions.averageLatencySince(dayAgo);
        sb.append("🤖 AI calls 24h: ").append(ok).append(" ok, ").append(errors).append(" failed");
        if (latency != null) sb.append(", avg ").append(Math.round(latency)).append(" ms");
        sb.append('\n');

        appendBreakdown(sb, "🎯 Actions 7d", texts.countByActionSince(weekAgo));
        appendBreakdown(sb, "⚙️ Modes", users.countByMode());
        appendBreakdown(sb, "🌐 Result languages", users.countByTargetLanguage());
        appendBreakdown(sb, "📣 Sources", users.countBySource());
        return sb.toString().trim();
    }

    static void appendBreakdown(StringBuilder sb, String title, List<Object[]> rows) {
        if (rows.isEmpty()) return;
        sb.append('\n').append(title).append(":\n");
        for (var row : rows) {
            sb.append("  • ").append(row[0]).append(": ").append(row[1]).append('\n');
        }
    }

    String describeBuild() {
        if (buildProperties == null) return "unknown";
        var tag = buildProperties.get("tag");
        var commit = buildProperties.get("commit");
        var sb = new StringBuilder(tag != null ? tag : buildProperties.getVersion());
        if (commit != null && !"local".equals(commit)) {
            sb.append(" (").append(commit, 0, Math.min(7, commit.length())).append(')');
        }
        return sb.toString();
    }

    static String describeUptime(Duration uptime) {
        var days = uptime.toDays();
        var hours = uptime.toHoursPart();
        var minutes = uptime.toMinutesPart();
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + minutes + "m";
        return minutes + "m";
    }
}
