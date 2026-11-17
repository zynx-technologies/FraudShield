package com.fraudshield.cases.overview;

import static com.fraudshield.cases.Db.instant;
import static com.fraudshield.cases.Db.ts;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Screen 1: volume, queue pressure, service levels, and platform health at a glance. */
@Service
public class OverviewService {

    public record Kpis(long transactionsToday, double changeVsLastWeekPct, long heldOrBlocked, double heldOrBlockedPct,
                       long alertsWaiting, long closeToSla, Double falsePositiveRatio, Object scoringP99Ms) {}

    public record HourPoint(int hour, long today, long lastWeek) {}

    public record ReasonShare(String reason, double percent) {}

    public record ChannelQueue(String channel, long waiting, long oldestMinutes, String status) {}

    public record Overview(Kpis kpis, List<HourPoint> alertsPerHour, List<ReasonShare> topReasons,
                           List<ChannelQueue> queueByChannel, Map<String, Object> platformHealth, Instant asOf) {}

    private final JdbcClient jdbc;
    private final DecisionStatusClient decisionStatus;
    private final ZoneId zone;

    public OverviewService(JdbcClient jdbc, DecisionStatusClient decisionStatus,
                           @Value("${fraudshield.time-zone:Europe/London}") String zone) {
        this.jdbc = jdbc;
        this.decisionStatus = decisionStatus;
        this.zone = ZoneId.of(zone);
    }

    public Overview overview(Instant now) {
        ZonedDateTime local = now.atZone(zone);
        Instant todayStart = local.toLocalDate().atStartOfDay(zone).toInstant();
        Instant weekAgo = now.minus(Duration.ofDays(7));
        Instant weekAgoStart = local.toLocalDate().minusDays(7).atStartOfDay(zone).toInstant();

        long today = decisions(todayStart, now, null);
        long lastWeekSoFar = decisions(weekAgoStart, weekAgo, null);
        long held = decisions(todayStart, now, List.of("HOLD", "BLOCK"));
        long waiting = count("SELECT COUNT(*) FROM alert WHERE status = 'OPEN'");
        long closeToSla = jdbc.sql("SELECT COUNT(*) FROM alert WHERE status = 'OPEN' AND sla_due_at <= ?")
                .param(ts(now.plus(Duration.ofMinutes(10)))).query(Long.class).single();

        Instant thirtyDays = now.minus(Duration.ofDays(30));
        long genuine = jdbc.sql("SELECT COUNT(*) FROM outcome WHERE label = 'GENUINE' AND decided_at >= ?").param(ts(thirtyDays)).query(Long.class).single();
        long fraud = jdbc.sql("SELECT COUNT(*) FROM outcome WHERE label = 'CONFIRMED_FRAUD' AND decided_at >= ?").param(ts(thirtyDays)).query(Long.class).single();

        Map<String, Object> health = new LinkedHashMap<>(decisionStatus.status());
        var kpis = new Kpis(today, lastWeekSoFar == 0 ? 0 : round1(100.0 * (today - lastWeekSoFar) / lastWeekSoFar),
                held, today == 0 ? 0 : round2(100.0 * held / today), waiting, closeToSla,
                fraud == 0 ? null : round1((double) genuine / fraud), health.get("scoringP99Ms"));

        return new Overview(kpis, alertsPerHour(local.toLocalDate(), local.getHour()), topReasons(now),
                queueByChannel(now), health, now);
    }

    private long decisions(Instant from, Instant to, List<String> outcomes) {
        String sql = "SELECT COALESCE(SUM(decisions), 0) FROM decision_hourly WHERE hour_start >= ? AND hour_start < ?";
        List<Object> params = new ArrayList<>(List.of(ts(from), ts(to)));
        if (outcomes != null) {
            sql += " AND outcome IN (" + String.join(",", outcomes.stream().map(o -> "?").toList()) + ")";
            params.addAll(outcomes);
        }
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    private List<HourPoint> alertsPerHour(LocalDate day, int currentHour) {
        List<HourPoint> points = new ArrayList<>();
        for (int h = 0; h <= currentHour; h++) {
            Instant start = day.atTime(h, 0).atZone(zone).toInstant();
            Instant end = start.plus(Duration.ofHours(1));
            points.add(new HourPoint(h, alertsBetween(start, end),
                    alertsBetween(start.minus(Duration.ofDays(7)), end.minus(Duration.ofDays(7)))));
        }
        return points;
    }

    private long alertsBetween(Instant from, Instant to) {
        return jdbc.sql("SELECT COUNT(*) FROM alert WHERE created_at >= ? AND created_at < ?")
                .params(ts(from), ts(to)).query(Long.class).single();
    }

    private List<ReasonShare> topReasons(Instant now) {
        Instant since = now.minus(Duration.ofDays(7));
        long total = jdbc.sql("SELECT COUNT(*) FROM alert WHERE created_at >= ?").param(ts(since)).query(Long.class).single();
        if (total == 0) return List.of();
        return jdbc.sql("""
                SELECT main_reason, COUNT(*) AS n FROM alert WHERE created_at >= ?
                GROUP BY main_reason ORDER BY n DESC""").param(ts(since))
                .query((rs, n) -> new ReasonShare(rs.getString(1), round1(100.0 * rs.getLong(2) / total)))
                .list().stream().limit(5).toList();
    }

    private List<ChannelQueue> queueByChannel(Instant now) {
        return jdbc.sql("""
                SELECT channel, COUNT(*) AS waiting, MIN(created_at) AS oldest FROM alert WHERE status = 'OPEN'
                GROUP BY channel ORDER BY waiting DESC""")
                .query((rs, n) -> {
                    long oldest = Duration.between(instant(rs, "oldest"), now).toMinutes();
                    String status = oldest > 30 ? "Breach" : oldest > 10 ? "Watch" : "On target";
                    return new ChannelQueue(rs.getString("channel"), rs.getLong("waiting"), oldest, status);
                }).list();
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private static double round1(double v) { return Math.round(v * 10) / 10.0; }

    private static double round2(double v) { return Math.round(v * 100) / 100.0; }
}