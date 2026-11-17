package com.fraudshield.cases.alerts;

import static com.fraudshield.cases.Db.instant;
import static com.fraudshield.cases.Db.ts;

import com.fraudshield.cases.cases.CaseEvents;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Creates alerts and serves the analyst queue (FR-4). */
@Service
public class AlertService {

    public record QueueItem(String id, String caseId, int score, BigDecimal amount, String currency, String channel,
                            String mainReason, String decision, Instant createdAt, Instant slaDueAt, String assignedTo) {}

    public record Queue(List<QueueItem> items, long mine, long team) {}

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final CaseEvents events;
    private final Duration slaHigh;
    private final Duration slaMedium;
    private final Duration slaLow;

    public AlertService(JdbcClient jdbc, ObjectMapper json, CaseEvents events,
                        @Value("${fraudshield.sla.score-90-plus:15m}") Duration slaHigh,
                        @Value("${fraudshield.sla.score-80-plus:30m}") Duration slaMedium,
                        @Value("${fraudshield.sla.other:60m}") Duration slaLow) {
        this.jdbc = jdbc;
        this.json = json;
        this.events = events;
        this.slaHigh = slaHigh;
        this.slaMedium = slaMedium;
        this.slaLow = slaLow;
    }

    /** Idempotent on decision ID, because the decision service retries delivery. */
    @Transactional
    public String intake(AlertIntake in) {
        return intake(in, null, null, Instant.now());
    }

    @Transactional
    public String intake(AlertIntake in, String alertId, String caseId, Instant receivedAt) {
        Optional<String> existing = jdbc.sql("SELECT id FROM alert WHERE decision_id = ?").param(in.decisionId())
                .query(String.class).optional();
        if (existing.isPresent()) return existing.get();

        // A new alert joins the customer's open case, so related alerts are investigated together.
        String targetCase = caseId != null ? caseId : jdbc.sql(
                        "SELECT id FROM fraud_case WHERE account_token = ? AND status <> 'CLOSED' ORDER BY created_at DESC")
                .param(in.accountToken()).query(String.class).list().stream().findFirst().orElse(null);
        boolean newCase = targetCase == null || caseId != null;
        if (targetCase == null) {
            targetCase = "CS-" + jdbc.sql("SELECT NEXT VALUE FOR case_seq").query(Long.class).single();
        }
        if (newCase) {
            jdbc.sql("INSERT INTO fraud_case (id, account_token, status, created_at) VALUES (?, ?, 'OPEN', ?)")
                    .params(targetCase, in.accountToken(), ts(receivedAt)).update();
        }
        String id = alertId != null ? alertId
                : "AL-" + jdbc.sql("SELECT NEXT VALUE FOR alert_seq").query(Long.class).single();
        jdbc.sql("""
                INSERT INTO alert (id, case_id, decision_id, transaction_id, channel, amount, currency, account_token,
                  payee_token, device_id, score, outcome, main_reason, model_version, ruleset_version, latency_ms,
                  reasons, rule_hits, features, status, created_at, sla_due_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?)""")
                .params(id, targetCase, in.decisionId(), in.transactionId(), in.channel(), in.amount(), in.currency(),
                        in.accountToken(), in.payeeToken(), in.deviceId(), in.score(), in.outcome(), truncate(in.mainReason(), 200),
                        in.modelVersion(), in.rulesetVersion(), in.latencyMs() == null ? null : in.latencyMs().intValue(),
                        json.writeValueAsString(in.reasons() == null ? List.of() : in.reasons()),
                        json.writeValueAsString(in.ruleHits() == null ? List.of() : in.ruleHits()),
                        in.features() == null ? null : json.writeValueAsString(in.features()),
                        ts(receivedAt), ts(receivedAt.plus(sla(in.score()))))
                .update();
        events.record(targetCase, receivedAt, "system", "ALERT_CREATED", "Alert " + id + " created, score " + in.score());
        events.record(targetCase, receivedAt, "system", "PAYMENT_" + in.outcome(), switch (in.outcome()) {
            case "BLOCK" -> "Payment blocked";
            case "HOLD" -> "Payment held";
            default -> "Step-up authentication sent";
        });
        return id;
    }

    Duration sla(int score) {
        return score >= 90 ? slaHigh : score >= 80 ? slaMedium : slaLow;
    }

    /** Ordered by risk, then time remaining, so the most urgent work comes first (screen 2). */
    public Queue queue(String user, String scope, String channel, Integer minScore, BigDecimal minAmount) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, case_id, score, amount, currency, channel, main_reason, outcome, created_at, sla_due_at, assigned_to
                FROM alert WHERE status = 'OPEN'""");
        List<Object> params = new ArrayList<>();
        if ("mine".equals(scope)) {
            sql.append(" AND assigned_to = ?");
            params.add(user);
        }
        if (channel != null && !channel.isBlank()) {
            sql.append(" AND channel = ?");
            params.add(channel);
        }
        if (minScore != null) {
            sql.append(" AND score >= ?");
            params.add(minScore);
        }
        if (minAmount != null) {
            sql.append(" AND amount >= ?");
            params.add(minAmount);
        }
        sql.append(" ORDER BY score DESC, sla_due_at ASC, id ASC");
        List<QueueItem> items = jdbc.sql(sql.toString()).params(params).query((rs, n) -> new QueueItem(
                rs.getString("id"), rs.getString("case_id"), rs.getInt("score"), rs.getBigDecimal("amount"),
                rs.getString("currency"), rs.getString("channel"), rs.getString("main_reason"),
                decisionLabel(rs.getString("outcome")), instant(rs, "created_at"), instant(rs, "sla_due_at"),
                rs.getString("assigned_to"))).list().stream().limit(500).toList();
        long mine = jdbc.sql("SELECT COUNT(*) FROM alert WHERE status = 'OPEN' AND assigned_to = ?").param(user)
                .query(Long.class).single();
        long team = jdbc.sql("SELECT COUNT(*) FROM alert WHERE status = 'OPEN'").query(Long.class).single();
        return new Queue(items, mine, team);
    }

    /** Assigns the most urgent unassigned alert to the caller. Safe when two analysts click at once. */
    @Transactional
    public Optional<QueueItem> assignNext(String user, Instant now) {
        for (int attempt = 0; attempt < 5; attempt++) {
            Optional<String[]> next = jdbc.sql("""
                    SELECT id, case_id FROM alert WHERE status = 'OPEN' AND assigned_to IS NULL
                    ORDER BY score DESC, sla_due_at ASC, id ASC""")
                    .query((rs, n) -> new String[] {rs.getString("id"), rs.getString("case_id")}).list().stream().findFirst();
            if (next.isEmpty()) return Optional.empty();
            int updated = jdbc.sql("UPDATE alert SET assigned_to = ? WHERE id = ? AND assigned_to IS NULL")
                    .params(user, next.get()[0]).update();
            if (updated == 1) {
                assignCase(next.get()[1], user, now);
                return queue(user, "mine", null, null, null).items().stream()
                        .filter(i -> i.id().equals(next.get()[0])).findFirst();
            }
        }
        return Optional.empty();
    }

    public void assignCase(String caseId, String user, Instant now) {
        int changed = jdbc.sql("UPDATE fraud_case SET assigned_to = ? WHERE id = ? AND (assigned_to IS NULL OR assigned_to <> ?)")
                .params(user, caseId, user).update();
        jdbc.sql("UPDATE alert SET assigned_to = ? WHERE case_id = ? AND status = 'OPEN'").params(user, caseId).update();
        if (changed > 0) events.record(caseId, now, user, "ASSIGNED", "Assigned to " + user);
    }

    public static String decisionLabel(String outcome) {
        return switch (outcome) {
            case "BLOCK" -> "Blocked";
            case "HOLD" -> "Held";
            default -> "Step-up sent";
        };
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}