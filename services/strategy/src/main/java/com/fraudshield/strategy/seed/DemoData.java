package com.fraudshield.strategy.seed;

import static com.fraudshield.strategy.Db.ts;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Synthetic rules, thresholds, scored history, and model monitoring for local and demo environments only,
 * mirroring the design's screens 4, 5, and 6. All figures are illustrative.
 */
@Component
@ConditionalOnProperty(name = "fraudshield.seed.enabled", havingValue = "true")
class DemoData {

    static final String STRATEGIST = "s.strategist@example.test";
    static final String APPROVER = "r.approver@example.test";
    static final String ML_ENGINEER = "m.engineer@example.test";

    private final JdbcClient jdbc;

    DemoData(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    void seed() {
        if (jdbc.sql("SELECT COUNT(*) FROM rule").query(Long.class).single() > 0) return;
        Instant now = Instant.now();
        Instant past = now.minus(Duration.ofDays(40));
        for (int i = 0; i < 4; i++) {
            jdbc.sql("INSERT INTO ruleset_version (created_at, reason) VALUES (?, ?)").params(ts(past), "Initial ruleset").update();
        }
        jdbc.sql("""
                INSERT INTO threshold_policy (step_up_from, hold_from, block_from, status, reason, author, created_at, decided_by, decided_at, decision_reason)
                VALUES (60, 80, 99, 'ACTIVE', 'Initial thresholds from the design', ?, ?, ?, ?, 'Agreed at go-live planning')""")
                .params(STRATEGIST, ts(past), APPROVER, ts(past)).update();

        rule("R-101", "Payment to sanctioned payee", "SANCTIONS_LIST", "BLOCK", "LIVE", "{}", 12, "100.0", true);
        rule("R-102", "Card testing: 5+ attempts in 2 min", "CARD_TESTING", "BLOCK", "LIVE", "{\"minAttempts\":\"5\"}", 842, "91.0", true);
        rule("R-103", "First payee over £5,000 with remote session", "FIRST_PAYEE_REMOTE", "HOLD", "LIVE", "{\"minAmount\":\"5000\"}", 316, "64.0", true);
        rule("R-104", "New device and new payee", "NEW_DEVICE_NEW_PAYEE", "STEP_UP", "LIVE", "{}", 1204, "23.0", true);
        rule("R-105", "Crypto exchange over £2,000", "MERCHANT_CATEGORY_AMOUNT", "STEP_UP", "REVIEW",
                "{\"category\":\"CRYPTO\",\"minAmount\":\"2000\"}", 967, "11.0", false);
        rule("R-106", "Gambling merchant over £1,000", "MERCHANT_CATEGORY_AMOUNT", "MONITOR", "SHADOW",
                "{\"category\":\"GAMBLING\",\"minAmount\":\"1000\"}", 2330, "4.0", true);
        jdbc.sql("ALTER SEQUENCE rule_seq RESTART WITH 107").update();

        jdbc.sql("INSERT INTO blocklist_entry (kind, entry_value, source, added_by, added_at) VALUES ('PAYEE', 'tok_sanctioned1', 'UK sanctions list (OFSI)', ?, ?)")
                .params(APPROVER, ts(past)).update();
        jdbc.sql("INSERT INTO blocklist_entry (kind, entry_value, source, added_by, added_at) VALUES ('DEVICE', 'D-BAD1', 'Confirmed fraud device', ?, ?)")
                .params(APPROVER, ts(past)).update();

        seedScoreDistribution(now);
        seedModels(now);
    }

    private void rule(String id, String name, String type, String action, String status, String params,
                      long hits, String precision, boolean approved) {
        Instant created = Instant.now().minus(Duration.ofDays(approved ? 60 : 1));
        jdbc.sql("INSERT INTO rule (id, name, rule_type, action, status, params, current_version, created_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(id, name, type, action, status, params, approved ? 1 : null, STRATEGIST, ts(created)).update();
        jdbc.sql("""
                INSERT INTO rule_version (rule_id, version, name, action, mode, params, author, created_at, status, decided_by, decided_at, decision_reason)
                VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(id, name, action, "REVIEW".equals(status) ? "LIVE" : status, params, STRATEGIST, ts(created),
                        approved ? "APPROVED" : "PENDING", approved ? APPROVER : null, approved ? ts(created.plus(Duration.ofHours(3))) : null,
                        approved ? "Backtest reviewed" : null).update();
        jdbc.sql("INSERT INTO rule_performance (rule_id, hits_7d_offline, precision_pct, measured_at) VALUES (?, ?, ?, ?)")
                .params(id, hits, new BigDecimal(precision), ts(Instant.now())).update();
    }

    /**
     * Thirty days of scored decisions (about 1.24 million a day), shaped so that the current thresholds give
     * roughly 78% recall, 2,240 alerts a day, and a 6:1 false-positive ratio, as on screen 4.
     */
    private void seedScoreDistribution(Instant now) {
        double fraudHigh = 0, fraudLow = 0, genuineHigh = 0;
        double[] fraud = new double[100], genuine = new double[100];
        for (int s = 0; s < 100; s++) {
            fraud[s] = s >= 80 ? Math.exp((s - 80) / 6.0) : (s >= 30 ? Math.exp((s - 79) / 12.0) : 0.002);
            genuine[s] = s >= 80 ? Math.exp(-(s - 80) / 7.0) : 0;
            if (s >= 80) { fraudHigh += fraud[s]; genuineHigh += genuine[s]; } else { fraudLow += fraud[s]; }
        }
        long totalVolume = 1_240_000L * 30;
        long genuineHighTarget = 58_330, fraudHighTarget = 9_408, fraudLowTarget = 2_592;
        long[] g = new long[100], f = new long[100];
        for (int s = 80; s < 100; s++) g[s] = Math.round(genuine[s] / genuineHigh * genuineHighTarget);
        // Genuine payments thin out smoothly towards high scores, so lowering a threshold adds alerts gradually.
        long tail = 0;
        for (int s = 40; s < 80; s++) {
            g[s] = Math.round(g[80] * Math.exp((80 - s) / 9.0));
            tail += g[s];
        }
        long genuineLow = totalVolume - genuineHighTarget - fraudHighTarget - fraudLowTarget - tail;
        double[] lowShape = new double[80];
        double lowSum = 0;
        for (int s = 0; s < 80; s++) { lowShape[s] = Math.exp(-s / 6.0); lowSum += lowShape[s]; }
        for (int s = 0; s < 100; s++) {
            f[s] = Math.round(s >= 80 ? fraud[s] / fraudHigh * fraudHighTarget : fraud[s] / fraudLow * fraudLowTarget);
            if (s < 80) g[s] += Math.round(lowShape[s] / lowSum * genuineLow);
            BigDecimal value = BigDecimal.valueOf(f[s]).multiply(BigDecimal.valueOf(s >= 80 ? 458 : 210)).setScale(2, RoundingMode.HALF_UP);
            jdbc.sql("INSERT INTO score_distribution (score, genuine_count, fraud_count, fraud_value, window_days, refreshed_at) VALUES (?, ?, ?, ?, 30, ?)")
                    .params(s, g[s], f[s], value, ts(now.minus(Duration.ofHours(6)))).update();
        }
    }
    private void seedModels(Instant now) {
        model(13, "ARCHIVED", "2024-04-01", "2026-03-31", "0.700", null, null, 10, null);
        model(14, "CHAMPION", "2024-07-01", "2026-06-30", "0.720", "0.710", "78.4", 10, now.minus(Duration.ofDays(33)));
        model(15, "SHADOW", "2024-10-01", "2026-09-30", "0.750", null, "81.0", 11, null);

        LocalDate monday = LocalDate.ofInstant(now, ZoneOffset.UTC).with(java.time.DayOfWeek.MONDAY).minusWeeks(10);
        double[] champion = {74.6, 75.4, 75.5, 76.2, 76.4, 77.1, 76.6, 77.4, 77.5, 78.3, 78.4};
        double[] challenger = {0, 0, 0, 0, 0, 77.8, 78.6, 79.4, 80.1, 80.2, 81.0};
        for (int w = 0; w < 11; w++) {
            weekly(14, monday.plusWeeks(w), champion[w]);
            if (challenger[w] > 0) weekly(15, monday.plusWeeks(w), challenger[w]);
        }
        String[][] drift = {{"device_age_days", "0.27"}, {"payee_age_days", "0.14"}, {"amount_to_median", "0.06"},
                {"txn_count_1h", "0.05"}, {"session_remote_flag", "0.03"}};
        for (String[] d : drift) {
            jdbc.sql("INSERT INTO feature_drift (model, feature, psi, measured_at) VALUES ('fs-txn', ?, ?, ?)")
                    .params(d[0], new BigDecimal(d[1]), ts(now)).update();
        }

        jdbc.sql("""
                INSERT INTO release_request (model, version, requested_by, requested_at, status, rollback_to, model_card_url, lineage_url)
                VALUES ('fs-txn', 15, 'ml.engineering@example.test', ?, 'PENDING', 14, 'https://example.test/model-cards/fs-txn-v15',
                  'https://example.test/mlflow/runs/8f21c')""").param(ts(now.minus(Duration.ofHours(20)))).update();
        long id = jdbc.sql("SELECT MAX(id) FROM release_request").query(Long.class).single();
        String[][] gates = {
                {"Unit, data, and schema tests", "412 of 412 passed", "PASSED"},
                {"Beats champion on PR-AUC", "0.75 vs. 0.72", "PASSED"},
                {"Recall at 0.2% alert rate", "81% vs. 78%", "PASSED"},
                {"Scoring latency", "p99 11 ms, limit 15 ms", "PASSED"},
                {"Outcome differences across review groups", "Within agreed limits", "PASSED"},
                {"Shadow run, 14 days", "Stable", "PASSED"},
                {"Canary at 5% of traffic", "Scheduled after approval", "PENDING"},
                {"Risk owner approval", "Awaiting decision", "PENDING"}};
        for (int i = 0; i < gates.length; i++) {
            jdbc.sql("INSERT INTO release_gate (release_id, seq, gate, result, status) VALUES (?, ?, ?, ?, ?)")
                    .params(id, i, gates[i][0], gates[i][1], gates[i][2]).update();
        }
    }

    private void model(int version, String stage, String from, String to, String prAuc, String livePrAuc, String recall,
                       int latency, Instant deployed) {
        jdbc.sql("""
                INSERT INTO model_version (model, version, stage, training_from, training_to, pr_auc, live_pr_auc, recall_pct, latency_p99_ms, owner, trained_by, deployed_at)
                VALUES ('fs-txn', ?, ?, ?, ?, ?, ?, ?, ?, 'ML team', ?, ?)""")
                .params(version, stage, LocalDate.parse(from), LocalDate.parse(to), new BigDecimal(prAuc),
                        livePrAuc == null ? null : new BigDecimal(livePrAuc), recall == null ? null : new BigDecimal(recall),
                        latency, ML_ENGINEER, ts(deployed)).update();
    }

    private void weekly(int version, LocalDate week, double recall) {
        jdbc.sql("INSERT INTO model_metric_weekly (model, version, week_start, recall_pct) VALUES ('fs-txn', ?, ?, ?)")
                .params(version, week, BigDecimal.valueOf(recall)).update();
    }
}