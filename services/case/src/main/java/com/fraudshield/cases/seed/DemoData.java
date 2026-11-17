package com.fraudshield.cases.seed;

import static com.fraudshield.cases.Db.ts;

import com.fraudshield.cases.alerts.AlertIntake;
import com.fraudshield.cases.alerts.AlertService;
import com.fraudshield.cases.overview.DecisionCounts;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Synthetic data for local and demo environments only (fraudshield.seed.enabled). It mirrors the design's
 * mock-ups: the analyst queue, case CS-20417, about 1.24 million decisions a day, and a false-positive ratio of
 * about 6:1. All names, accounts, and figures are illustrative.
 */
@Component
@ConditionalOnProperty(name = "fraudshield.seed.enabled", havingValue = "true")
class DemoData {

    static final String ANALYST = "j.analyst@example.test";
    private static final String[] CHANNELS = {"INSTANT", "CARD", "TRANSFER"};
    private static final String[][] REASONS = {
            {"NEW_PAYEE_HIGH_AMOUNT", "New payee and high amount"}, {"REMOTE_ACCESS", "Remote-access tool active"},
            {"DEVICE_LINKED_FRAUD", "Device linked to confirmed fraud"}, {"UNUSUAL_VELOCITY", "Unusual velocity"},
            {"PAYEE_MULE_SIGNALS", "Payee has mule signals"}};
    private static final double[] REASON_WEIGHTS = {0.31, 0.22, 0.18, 0.14, 0.09};

    private final JdbcClient jdbc;
    private final AlertService alerts;
    private final DecisionCounts counts;
    private final ZoneId zone;
    private final Random random = new Random(42);

    DemoData(JdbcClient jdbc, AlertService alerts, DecisionCounts counts,
             @Value("${fraudshield.time-zone:Europe/London}") String zone) {
        this.jdbc = jdbc;
        this.alerts = alerts;
        this.counts = counts;
        this.zone = ZoneId.of(zone);
    }

    @EventListener(ApplicationReadyEvent.class)
    void seed() {
        if (jdbc.sql("SELECT COUNT(*) FROM alert").query(Long.class).single() > 0) return;
        Instant now = Instant.now();
        seedDecisionVolumes(now);
        seedHistory(now);
        seedQueue(now);
        seedDesignCase(now);
        seedTeamQueue(now);
    }

    /** About 1.24 million decisions a day, shaped like a working day. */
    private void seedDecisionVolumes(Instant now) {
        LocalDate today = now.atZone(zone).toLocalDate();
        for (LocalDate day : List.of(today, today.minusDays(7))) {
            for (int h = 0; h < 24; h++) {
                Instant hour = day.atTime(h, 0).atZone(zone).toInstant();
                if (!hour.isBefore(now)) break;
                double fraction = hour.plus(Duration.ofHours(1)).isAfter(now)
                        ? Duration.between(hour, now).toMinutes() / 60.0 : 1.0;
                double scale = day.equals(today) ? 1.031 : 1.0;
                long total = Math.round(1_240_000 * shape(h) * fraction * scale);
                for (int c = 0; c < CHANNELS.length; c++) {
                    long channelTotal = Math.round(total * new double[] {0.42, 0.44, 0.14}[c]);
                    add(hour, CHANNELS[c], "BLOCK", Math.round(channelTotal * 0.0003));
                    add(hour, CHANNELS[c], "HOLD", Math.round(channelTotal * 0.0012));
                    add(hour, CHANNELS[c], "STEP_UP", Math.round(channelTotal * 0.012));
                    add(hour, CHANNELS[c], "ALLOW", Math.round(channelTotal * 0.9865));
                }
            }
        }
    }

    private void add(Instant hour, String channel, String outcome, long n) {
        if (n > 0) counts.add(new DecisionCounts.HourlyCount(hour, channel, outcome, n, n * 62, 190));
    }

    /** Share of a day's volume in each hour. Sums to 1. */
    private static double shape(int hour) {
        double[] weights = {0.8, 0.5, 0.4, 0.4, 0.6, 1.2, 2.5, 4.0, 5.5, 6.2, 6.6, 6.8, 7.0, 6.9, 6.6, 6.4, 6.3, 6.2, 5.8, 5.0, 4.0, 3.0, 2.2, 1.3};
        double sum = 0;
        for (double w : weights) sum += w;
        return weights[hour] / sum;
    }

    /** Closed alerts for the charts and the false-positive ratio, today and the same day last week. */
    private void seedHistory(Instant now) {
        LocalDate today = now.atZone(zone).toLocalDate();
        int seq = 80000;
        for (int dayOffset = 0; dayOffset <= 7; dayOffset++) {
            LocalDate day = today.minusDays(dayOffset);
            for (int h = 0; h < 24; h++) {
                Instant hour = day.atTime(h, 0).atZone(zone).toInstant();
                if (!hour.isBefore(now.minus(Duration.ofMinutes(60)))) break;
                int n = (int) Math.round(shape(h) * 24 * (dayOffset == 0 ? 9.5 : 9) * (0.9 + random.nextDouble() * 0.2));
                if (dayOffset != 0 && dayOffset != 7) n = n / 3; // Keep the in-between days light.
                for (int i = 0; i < n; i++) {
                    Instant at = hour.plusSeconds(random.nextInt(3600));
                    String alertId = "AL-" + (seq++);
                    String account = "tok_h" + Integer.toHexString(seq);
                    int r = pickReason();
                    alerts.intake(intake("DE-H" + seq, account, null, null, CHANNELS[random.nextInt(3)],
                            money(50 + random.nextInt(9000)), 70 + random.nextInt(30), "HOLD", r, Map.of()),
                            alertId, "CS-" + (seq + 100000), at);
                    boolean fraud = random.nextDouble() < 1 / 7.2;
                    close("CS-" + (seq + 100000), alertId, fraud, at.plus(Duration.ofMinutes(5 + random.nextInt(40))));
                }
            }
        }
    }

    private int pickReason() {
        double x = random.nextDouble(), acc = 0;
        for (int i = 0; i < REASON_WEIGHTS.length; i++) {
            acc += REASON_WEIGHTS[i];
            if (x <= acc) return i;
        }
        return 0;
    }

    private void close(String caseId, String alertId, boolean fraud, Instant at) {
        jdbc.sql("INSERT INTO outcome (case_id, alert_id, transaction_id, label, reason, decided_by, decided_at) VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params(caseId, alertId, "TX-" + alertId, fraud ? "CONFIRMED_FRAUD" : "GENUINE",
                        fraud ? "Customer confirmed they did not make this payment" : "Customer confirmed the payment",
                        "k.reviewer@example.test", ts(at)).update();
        jdbc.sql("UPDATE alert SET status = 'CLOSED' WHERE id = ?").param(alertId).update();
        jdbc.sql("UPDATE fraud_case SET status = 'CLOSED', closed_at = ? WHERE id = ?").params(ts(at), caseId).update();
    }

    /** The analyst's own queue from screen 2. Minutes left = SLA minus age. */
    private void seedQueue(Instant now) {
        Object[][] rows = {
                {"AL-90407", 96, "4200.00", "INSTANT", "Payee has mule signals", "HOLD", 13 - 4},
                {"AL-90398", 93, "1760.40", "CARD", "Device linked to confirmed fraud", "HOLD", 15 - 9},
                {"AL-90391", 89, "12300.00", "TRANSFER", "Amount far above usual", "HOLD", 30 - 14},
                {"AL-90385", 86, "689.99", "CARD", "Rapid card testing", "HOLD", 30 - 18},
                {"AL-90377", 84, "3075.00", "INSTANT", "New device and payee", "HOLD", 30 - 21},
                {"AL-90370", 81, "2410.00", "TRANSFER", "Unusual login location", "HOLD", 30 - 26},
                {"AL-90362", 78, "455.20", "CARD", "Unusual merchant type", "STEP_UP", 60 - 33},
                {"AL-90355", 76, "1120.00", "INSTANT", "Unusual velocity", "STEP_UP", 60 - 41},
                {"AL-90349", 74, "5000.00", "TRANSFER", "New payee", "STEP_UP", 60 - 47},
        };
        int caseNo = 20400;
        for (Object[] row : rows) {
            String id = (String) row[0];
            Instant at = now.minus(Duration.ofMinutes((Integer) row[6]));
            AlertIntake in = new AlertIntake("DE-" + id, "TX-" + id.substring(3), (String) row[3], new BigDecimal((String) row[2]),
                    "GBP", "tok_q" + id.substring(3), "tok_pq" + id.substring(3), "D-q" + id.substring(3),
                    (Integer) row[1], (String) row[5], List.of(new AlertIntake.Reason("SEED", (String) row[4], 0.3)),
                    List.of(), "fs-baseline-1", "rs-203", 120L, false, Map.of(), at);
            String caseId = "CS-" + (caseNo++);
            alerts.intake(in, id, caseId, at);
            alerts.assignCase(caseId, ANALYST, at.plus(Duration.ofMinutes(1)));
        }
    }

    /** Case CS-20417 from screen 3: GBP 9,850 to a new payee, about 47 times the customer's usual payment. */
    private void seedDesignCase(Instant now) {
        Instant at = now.minus(Duration.ofMinutes(13)).truncatedTo(ChronoUnit.SECONDS);
        Map<String, Object> features = new LinkedHashMap<>();
        features.put("medianAmount90d", 210);
        features.put("amountToMedian", 46.9);
        features.put("newPayee", true);
        features.put("payeeAgeDays", 3);
        features.put("payeeIncomingSenders7d", 2);
        features.put("newDevice", true);
        features.put("deviceAgeDays", 0);
        features.put("remoteAccess", true);
        features.put("txnCount1h", 1);
        AlertIntake in = new AlertIntake("DE-55291", "TX-77120", "INSTANT", new BigDecimal("9850.00"), "GBP",
                "tok_8fa1", "tok_21bd", "D-55", 98, "HOLD",
                List.of(new AlertIntake.Reason("NEW_PAYEE_HIGH_AMOUNT", "New payee and high amount", 0.31),
                        new AlertIntake.Reason("REMOTE_ACCESS", "Remote-access tool active", 0.24),
                        new AlertIntake.Reason("PAYEE_MULE_SIGNALS", "Payee has mule signals", 0.18),
                        new AlertIntake.Reason("AMOUNT_VS_PATTERN", "Amount vs. 90-day pattern", 0.12)),
                List.of(), "fs-baseline-1", "rs-203", 148L, false, features, at);
        alerts.intake(in, "AL-90412", "CS-20417", at);
        alerts.assignCase("CS-20417", ANALYST, at.plus(Duration.ofMinutes(2)));

        String[][] links = {
                {"ACCOUNT", "tok_7741", "PAYEE", "tok_21bd", "PAID", "0"},
                {"ACCOUNT", "tok_9020", "PAYEE", "tok_21bd", "PAID", "0"},
                {"ACCOUNT", "tok_7741", "DEVICE", "D-55", "USED", "0"},
                {"ACCOUNT", "tok_9020", "DEVICE", "D-55", "USED", "0"},
                {"ACCOUNT", "tok_8fa1", "IP", "203.0.113.7", "SIGNED_IN_FROM", "1"},
        };
        for (String[] l : links) {
            jdbc.sql("INSERT INTO entity_link (from_type, from_id, to_type, to_id, relation, flagged) VALUES (?, ?, ?, ?, ?, ?)")
                    .params(l[0], l[1], l[2], l[3], l[4], "1".equals(l[5])).update();
        }
    }

    /** The rest of the team's open queue. */
    private void seedTeamQueue(Instant now) {
        for (int i = 0; i < 36; i++) {
            int r = pickReason();
            Instant at = now.minus(Duration.ofMinutes(1 + random.nextInt(25)));
            alerts.intake(intake("DE-T" + i, "tok_t" + i, "tok_pt" + i, "D-t" + i, CHANNELS[i % 3],
                    money(80 + random.nextInt(6000)), 70 + random.nextInt(29), random.nextBoolean() ? "HOLD" : "STEP_UP", r, Map.of()),
                    "AL-" + (90420 + i), "CS-" + (20420 + i), at);
        }
    }

    private static AlertIntake intake(String decisionId, String account, String payee, String device, String channel,
                                      BigDecimal amount, int score, String outcome, int reason, Map<String, Object> features) {
        return new AlertIntake(decisionId, "TX-" + decisionId.substring(3), channel, amount, "GBP", account, payee, device,
                score, outcome, List.of(new AlertIntake.Reason(REASONS[reason][0], REASONS[reason][1], 0.3)), List.of(),
                "fs-baseline-1", "rs-203", 110L, false, features, Instant.now());
    }

    private BigDecimal money(int pounds) {
        return BigDecimal.valueOf(pounds).add(BigDecimal.valueOf(random.nextInt(100), 2)).setScale(2, RoundingMode.HALF_UP);
    }
}