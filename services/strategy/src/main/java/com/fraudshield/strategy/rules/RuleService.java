package com.fraudshield.strategy.rules;

import static com.fraudshield.strategy.Db.instant;
import static com.fraudshield.strategy.Db.ts;

import com.fraudshield.common.web.ApiErrors;
import com.fraudshield.strategy.Approvals;
import com.fraudshield.strategy.thresholds.ThresholdService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Rule authoring with two-person approval (FR-8), and the live snapshot the decision service loads. */
@Service
public class RuleService {

    public record Pending(int version, String action, String mode, Map<String, String> params, String author, Instant createdAt) {}

    public record RuleRow(String id, String name, String type, String action, String status, Map<String, String> params,
                          Integer version, long hits7d, BigDecimal precisionPct, Pending pending) {}

    public record Change(String ruleId, String ruleName, int version, String action, String mode, Map<String, String> params,
                         String author, Instant createdAt, String status, String decidedBy, Instant decidedAt, String decisionReason) {}

    public record NewRule(String name, String type, String action, String mode, Map<String, String> params) {}

    public record RuleChange(String action, String mode, Map<String, String> params) {}

    public record SnapshotRule(String id, String name, String type, String action, String status, Map<String, String> params) {}

    public record Snapshot(String version, List<SnapshotRule> rules, java.util.Set<String> sanctionedPayees,
                           java.util.Set<String> blockedDevices, ThresholdService.Thresholds thresholds) {}

    private static final TypeReference<Map<String, String>> PARAMS = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final RulesetVersions versions;
    private final ThresholdService thresholds;

    public RuleService(JdbcClient jdbc, ObjectMapper json, RulesetVersions versions, ThresholdService thresholds) {
        this.jdbc = jdbc;
        this.json = json;
        this.versions = versions;
        this.thresholds = thresholds;
    }

    public List<RuleRow> list(Instant now) {
        LocalDate weekAgo = LocalDate.ofInstant(now, ZoneOffset.UTC).minusDays(6);
        return jdbc.sql("""
                SELECT r.id, r.name, r.rule_type, r.action, r.status, r.params, r.current_version,
                  COALESCE(p.hits_7d_offline, 0) + COALESCE((SELECT SUM(h.hits) FROM rule_hit_daily h WHERE h.rule_id = r.id AND h.hit_day >= ?), 0) AS hits,
                  p.precision_pct
                FROM rule r LEFT JOIN rule_performance p ON p.rule_id = r.id
                WHERE r.status <> 'RETIRED'
                ORDER BY CASE r.action WHEN 'BLOCK' THEN 0 WHEN 'HOLD' THEN 1 WHEN 'STEP_UP' THEN 2 ELSE 3 END, r.id""")
                .param(weekAgo)
                .query((rs, n) -> new RuleRow(rs.getString("id"), rs.getString("name"), rs.getString("rule_type"),
                        rs.getString("action"), rs.getString("status"), json.readValue(rs.getString("params"), PARAMS),
                        (Integer) rs.getObject("current_version"), rs.getLong("hits"), rs.getBigDecimal("precision_pct"), null))
                .list().stream()
                .map(r -> new RuleRow(r.id(), r.name(), r.type(), r.action(), r.status(), r.params(), r.version(), r.hits7d(),
                        r.precisionPct(), pending(r.id()).orElse(null)))
                .toList();
    }

    private Optional<Pending> pending(String ruleId) {
        return jdbc.sql("SELECT version, action, mode, params, author, created_at FROM rule_version WHERE rule_id = ? AND status = 'PENDING'")
                .param(ruleId).query((rs, n) -> new Pending(rs.getInt("version"), rs.getString("action"), rs.getString("mode"),
                        json.readValue(rs.getString("params"), PARAMS), rs.getString("author"), instant(rs, "created_at")))
                .optional();
    }

    @Transactional
    public RuleRow create(NewRule in, String author, Instant now) {
        if (in.name() == null || in.name().isBlank() || in.name().length() > 120) {
            throw new ApiErrors.BadRequest("A rule name of up to 120 characters is required");
        }
        String action = requireIn(in.action(), RuleTypes.ACTIONS, "action");
        String mode = requireIn(in.mode() == null ? "SHADOW" : in.mode(), java.util.Set.of("LIVE", "SHADOW"), "mode");
        Map<String, String> params = RuleTypes.validate(in.type(), in.params());
        String id = "R-" + jdbc.sql("SELECT NEXT VALUE FOR rule_seq").query(Long.class).single();
        jdbc.sql("INSERT INTO rule (id, name, rule_type, action, status, params, created_by, created_at) VALUES (?, ?, ?, ?, 'REVIEW', ?, ?, ?)")
                .params(id, in.name().strip(), in.type(), action, json.writeValueAsString(params), author, ts(now)).update();
        insertVersion(id, 1, in.name().strip(), action, mode, params, author, now);
        return list(now).stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    }

    /** Proposes a change to an existing rule: action, mode (including RETIRED), or parameters. */
    @Transactional
    public void propose(String ruleId, RuleChange change, String author, Instant now) {
        var rule = jdbc.sql("SELECT name, rule_type, action, params FROM rule WHERE id = ?").param(ruleId)
                .query((rs, n) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)})
                .optional().orElseThrow(() -> new ApiErrors.NotFound("Rule not found"));
        if (pending(ruleId).isPresent()) throw new ApiErrors.Conflict("This rule already has a change waiting for approval");
        String action = requireIn(change.action() == null ? rule[2] : change.action(), RuleTypes.ACTIONS, "action");
        String mode = requireIn(change.mode(), RuleTypes.MODES, "mode");
        Map<String, String> params = change.params() == null ? json.readValue(rule[3], PARAMS) : RuleTypes.validate(rule[1], change.params());
        int next = jdbc.sql("SELECT COALESCE(MAX(version), 0) + 1 FROM rule_version WHERE rule_id = ?").param(ruleId).query(Integer.class).single();
        insertVersion(ruleId, next, rule[0], action, mode, params, author, now);
    }

    private void insertVersion(String ruleId, int version, String name, String action, String mode, Map<String, String> params,
                               String author, Instant now) {
        jdbc.sql("""
                INSERT INTO rule_version (rule_id, version, name, action, mode, params, author, created_at, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')""")
                .params(ruleId, version, name, action, mode, json.writeValueAsString(params), author, ts(now)).update();
    }

    @Transactional
    public void decide(String ruleId, int version, boolean approve, String reason, String approver, Instant now) {
        String why = Approvals.requireReason(reason);
        var v = jdbc.sql("SELECT action, mode, params, author, status FROM rule_version WHERE rule_id = ? AND version = ?")
                .params(ruleId, version)
                .query((rs, n) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)})
                .optional().orElseThrow(() -> new ApiErrors.NotFound("Rule version not found"));
        if (!"PENDING".equals(v[4])) throw new ApiErrors.Conflict("This change has already been decided");
        Approvals.requireDifferentPerson(approver, v[3], "proposed this change");
        jdbc.sql("UPDATE rule_version SET status = ?, decided_by = ?, decided_at = ?, decision_reason = ? WHERE rule_id = ? AND version = ?")
                .params(approve ? "APPROVED" : "REJECTED", approver, ts(now), why, ruleId, version).update();
        if (approve) {
            jdbc.sql("UPDATE rule SET action = ?, status = ?, params = ?, current_version = ? WHERE id = ?")
                    .params(v[0], v[1], v[2], version, ruleId).update();
            versions.bump("Rule " + ruleId + " v" + version + " approved by " + approver, now);
        } else {
            jdbc.sql("UPDATE rule SET status = 'RETIRED' WHERE id = ? AND current_version IS NULL").param(ruleId).update();
        }
    }

    public List<Change> history(String ruleId) {
        String sql = """
                SELECT v.rule_id, v.name, v.version, v.action, v.mode, v.params, v.author, v.created_at, v.status,
                  v.decided_by, v.decided_at, v.decision_reason
                FROM rule_version v""" + (ruleId == null ? "" : " WHERE v.rule_id = ?") + " ORDER BY v.created_at DESC, v.id DESC";
        return jdbc.sql(sql)
                .params(ruleId == null ? List.of() : List.of(ruleId))
                .query((rs, n) -> new Change(rs.getString("rule_id"), rs.getString("name"), rs.getInt("version"),
                        rs.getString("action"), rs.getString("mode"), json.readValue(rs.getString("params"), PARAMS),
                        rs.getString("author"), instant(rs, "created_at"), rs.getString("status"), rs.getString("decided_by"),
                        instant(rs, "decided_at"), rs.getString("decision_reason")))
                .list().stream().limit(200).toList();
    }

    /** What the decision service loads: LIVE and SHADOW rules, blocklists, and the active thresholds. */
    public Snapshot snapshot() {
        List<SnapshotRule> rules = jdbc.sql("SELECT id, name, rule_type, action, status, params FROM rule WHERE status IN ('LIVE', 'SHADOW')")
                .query((rs, n) -> new SnapshotRule(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), json.readValue(rs.getString(6), PARAMS))).list();
        var payees = new LinkedHashSet<>(jdbc.sql("SELECT entry_value FROM blocklist_entry WHERE kind = 'PAYEE'").query(String.class).list());
        var devices = new LinkedHashSet<>(jdbc.sql("SELECT entry_value FROM blocklist_entry WHERE kind = 'DEVICE'").query(String.class).list());
        return new Snapshot(versions.current(), rules, payees, devices, thresholds.active());
    }

    /** Hit counts from the decision service, for the "Hits, 7 days" column. */
    @Transactional
    public void addHits(Map<String, Long> hits, Instant now) {
        LocalDate day = LocalDate.ofInstant(now, ZoneOffset.UTC);
        hits.forEach((ruleId, n) -> {
            if (n == null || n <= 0) return;
            int updated = jdbc.sql("UPDATE rule_hit_daily SET hits = hits + ? WHERE rule_id = ? AND hit_day = ?").params(n, ruleId, day).update();
            if (updated == 0) {
                jdbc.sql("INSERT INTO rule_hit_daily (rule_id, hit_day, hits) VALUES (?, ?, ?)").params(ruleId, day, n).update();
            }
        });
        jdbc.sql("DELETE FROM rule_hit_daily WHERE hit_day < ?").param(day.minusDays(Duration.ofDays(35).toDays())).update();
    }

    private static String requireIn(String value, java.util.Set<String> allowed, String what) {
        if (value == null || !allowed.contains(value)) throw new ApiErrors.BadRequest("Invalid " + what + ": " + value);
        return value;
    }
}