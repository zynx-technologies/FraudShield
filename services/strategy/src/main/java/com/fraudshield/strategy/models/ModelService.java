package com.fraudshield.strategy.models;

import static com.fraudshield.strategy.Db.instant;
import static com.fraudshield.strategy.Db.ts;

import com.fraudshield.common.web.ApiErrors;
import com.fraudshield.strategy.Approvals;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Model monitoring (screen 5) and release approval (screen 6). Metrics and gate evidence are pushed here by the
 * Databricks monitoring and validation jobs; approvals are recorded here and must also be written to the Unity
 * Catalog tag approval.approved_by, which the release pipeline checks.
 */
@Service
public class ModelService {

    public static final double MIN_RECALL_PCT = 75.0;
    public static final String CANARY_GATE = "Canary at 5% of traffic";
    public static final String APPROVAL_GATE = "Risk owner approval";

    public record Version(int version, String stage, LocalDate trainingFrom, LocalDate trainingTo, BigDecimal prAuc, BigDecimal livePrAuc,
                          BigDecimal recallPct, Integer latencyP99Ms, String owner, String trainedBy, Instant deployedAt) {}

    public record WeekPoint(LocalDate week, BigDecimal champion, BigDecimal challenger) {}

    public record Drift(String feature, BigDecimal psi, String status) {}

    public record Monitoring(String model, Version champion, Version challenger, BigDecimal prAucBaseline,
                             double minimumRecallPct, long driftAlerts, List<String> driftAlertFeatures,
                             List<WeekPoint> weekly, List<Drift> drift, List<Version> versions, Integer pendingRelease) {}

    public record Gate(String gate, String result, String status) {}

    public record Release(long id, String model, int version, String requestedBy, Instant requestedAt, String status,
                          Integer rollbackTo, String modelCardUrl, String lineageUrl, String trainedBy, String decidedBy,
                          Instant decidedAt, String decisionReason, List<Gate> gates, boolean readyForDecision) {}

    public record ReleaseRequest(int version, Integer rollbackTo, String modelCardUrl, String lineageUrl, List<Gate> gates) {}

    public record MonitoringUpdate(List<WeekIn> weekly, List<DriftIn> drift) {
        public record WeekIn(int version, LocalDate weekStart, BigDecimal recallPct) {}
        public record DriftIn(String feature, BigDecimal psi) {}
    }

    private final JdbcClient jdbc;

    public ModelService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Version> versions(String model) {
        return jdbc.sql("""
                SELECT version, stage, training_from, training_to, pr_auc, live_pr_auc, recall_pct, latency_p99_ms, owner, trained_by, deployed_at
                FROM model_version WHERE model = ? ORDER BY version DESC""").param(model)
                .query((rs, n) -> new Version(rs.getInt("version"), rs.getString("stage"),
                        rs.getObject("training_from", LocalDate.class), rs.getObject("training_to", LocalDate.class),
                        rs.getBigDecimal("pr_auc"), rs.getBigDecimal("live_pr_auc"), rs.getBigDecimal("recall_pct"), (Integer) rs.getObject("latency_p99_ms"),
                        rs.getString("owner"), rs.getString("trained_by"), instant(rs, "deployed_at"))).list();
    }

    public Monitoring monitoring(String model) {
        List<Version> versions = versions(model);
        Version champion = versions.stream().filter(v -> v.stage().equals("CHAMPION")).findFirst().orElse(null);
        Version challenger = versions.stream().filter(v -> v.stage().equals("SHADOW")).findFirst().orElse(null);

        List<Drift> drift = jdbc.sql("SELECT feature, psi FROM feature_drift WHERE model = ? ORDER BY psi DESC").param(model)
                .query((rs, n) -> new Drift(rs.getString(1), rs.getBigDecimal(2), driftStatus(rs.getBigDecimal(2)))).list();
        List<String> alerts = drift.stream().filter(d -> d.status().equals("Alert")).map(Drift::feature).toList();

        Map<LocalDate, WeekPoint> weeks = new LinkedHashMap<>();
        jdbc.sql("SELECT version, week_start, recall_pct FROM model_metric_weekly WHERE model = ? ORDER BY week_start").param(model)
                .query((rs, n) -> new Object[] {rs.getInt(1), rs.getObject(2, LocalDate.class), rs.getBigDecimal(3)}).list()
                .forEach(r -> {
                    LocalDate week = (LocalDate) r[1];
                    WeekPoint p = weeks.getOrDefault(week, new WeekPoint(week, null, null));
                    int version = (Integer) r[0];
                    if (champion != null && version == champion.version()) p = new WeekPoint(week, (BigDecimal) r[2], p.challenger());
                    if (challenger != null && version == challenger.version()) p = new WeekPoint(week, p.champion(), (BigDecimal) r[2]);
                    weeks.put(week, p);
                });

        Integer pending = jdbc.sql("SELECT version FROM release_request WHERE model = ? AND status = 'PENDING' ORDER BY id DESC")
                .param(model).query(Integer.class).list().stream().findFirst().orElse(null);
        return new Monitoring(model, champion, challenger, champion == null ? null : champion.prAuc(),
                MIN_RECALL_PCT, alerts.size(), alerts, new ArrayList<>(weeks.values()), drift, versions, pending);
    }

    static String driftStatus(BigDecimal psi) {
        double v = psi.doubleValue();
        return v >= 0.2 ? "Alert" : v >= 0.1 ? "Watch" : "OK";
    }

    public List<Release> releases(String model) {
        return jdbc.sql("SELECT id FROM release_request WHERE model = ? ORDER BY id DESC").param(model)
                .query(Long.class).list().stream().map(this::release).toList();
    }

    public Release release(String model, int version) {
        Long id = jdbc.sql("SELECT id FROM release_request WHERE model = ? AND version = ? ORDER BY id DESC")
                .params(model, version).query(Long.class).list().stream().findFirst()
                .orElseThrow(() -> new ApiErrors.NotFound("No release request for this model version"));
        return release(id);
    }

    private Release release(long id) {
        List<Gate> gates = jdbc.sql("SELECT gate, result, status FROM release_gate WHERE release_id = ? ORDER BY seq").param(id)
                .query((rs, n) -> new Gate(rs.getString(1), rs.getString(2), rs.getString(3))).list();
        return jdbc.sql("""
                SELECT r.*, (SELECT m.trained_by FROM model_version m WHERE m.model = r.model AND m.version = r.version) AS trained_by
                FROM release_request r WHERE r.id = ?""").param(id)
                .query((rs, n) -> new Release(rs.getLong("id"), rs.getString("model"), rs.getInt("version"),
                        rs.getString("requested_by"), instant(rs, "requested_at"), rs.getString("status"),
                        (Integer) rs.getObject("rollback_to"), rs.getString("model_card_url"), rs.getString("lineage_url"),
                        rs.getString("trained_by"), rs.getString("decided_by"), instant(rs, "decided_at"),
                        rs.getString("decision_reason"), gates, evidenceComplete(gates)))
                .single();
    }

    /** Every gate before approval must have passed. The canary and the approval itself come after. */
    static boolean evidenceComplete(List<Gate> gates) {
        return !gates.isEmpty() && gates.stream()
                .filter(g -> !Set.of(CANARY_GATE, APPROVAL_GATE).contains(g.gate()))
                .allMatch(g -> g.status().equals("PASSED"));
    }

    @Transactional
    public Release request(String model, ReleaseRequest in, String requestedBy, Instant now) {
        if (jdbc.sql("SELECT COUNT(*) FROM model_version WHERE model = ? AND version = ?").params(model, in.version())
                .query(Long.class).single() == 0) {
            throw new ApiErrors.BadRequest("Model version is not registered");
        }
        if (jdbc.sql("SELECT COUNT(*) FROM release_request WHERE model = ? AND status = 'PENDING'").param(model)
                .query(Long.class).single() > 0) {
            throw new ApiErrors.Conflict("A release for this model is already waiting for a decision");
        }
        jdbc.sql("""
                INSERT INTO release_request (model, version, requested_by, requested_at, status, rollback_to, model_card_url, lineage_url)
                VALUES (?, ?, ?, ?, 'PENDING', ?, ?, ?)""")
                .params(model, in.version(), requestedBy, ts(now), in.rollbackTo(), in.modelCardUrl(), in.lineageUrl()).update();
        long id = jdbc.sql("SELECT MAX(id) FROM release_request WHERE model = ?").param(model).query(Long.class).single();
        List<Gate> gates = new ArrayList<>(in.gates() == null ? List.of() : in.gates());
        gates.removeIf(g -> Set.of(CANARY_GATE, APPROVAL_GATE).contains(g.gate()));
        gates.add(new Gate(CANARY_GATE, "Scheduled after approval", "PENDING"));
        gates.add(new Gate(APPROVAL_GATE, "Awaiting decision", "PENDING"));
        for (int i = 0; i < gates.size(); i++) {
            Gate g = gates.get(i);
            if (!Set.of("PASSED", "PENDING", "FAILED").contains(g.status())) throw new ApiErrors.BadRequest("Invalid gate status " + g.status());
            jdbc.sql("INSERT INTO release_gate (release_id, seq, gate, result, status) VALUES (?, ?, ?, ?, ?)")
                    .params(id, i, g.gate(), g.result(), g.status()).update();
        }
        return release(id);
    }

    /** Design section 8: the model risk owner signs off, and cannot be the model's author. */
    @Transactional
    public Release decide(String model, int version, boolean approve, String reason, String approver, Instant now) {
        String why = Approvals.requireReason(reason);
        Release r = release(model, version);
        if (!r.status().equals("PENDING")) throw new ApiErrors.Conflict("This release has already been decided");
        if (r.trainedBy() != null) Approvals.requireDifferentPerson(approver, r.trainedBy(), "trained this model");
        Approvals.requireDifferentPerson(approver, r.requestedBy(), "requested this release");
        if (approve && !r.readyForDecision()) {
            throw new ApiErrors.Conflict("Every promotion gate must pass before the release can be approved");
        }
        jdbc.sql("UPDATE release_request SET status = ?, decided_by = ?, decided_at = ?, decision_reason = ? WHERE id = ?")
                .params(approve ? "APPROVED" : "REJECTED", approver, ts(now), why, r.id()).update();
        jdbc.sql("UPDATE release_gate SET result = ?, status = ? WHERE release_id = ? AND gate = ?")
                .params((approve ? "Approved by " : "Rejected by ") + approver, approve ? "PASSED" : "FAILED", r.id(), APPROVAL_GATE).update();
        if (approve) {
            jdbc.sql("UPDATE release_gate SET result = ? WHERE release_id = ? AND gate = ?")
                    .params("Ready: run the model release pipeline with action release", r.id(), CANARY_GATE).update();
        }
        return release(r.id());
    }

    @Transactional
    public void updateMonitoring(String model, MonitoringUpdate update, Instant now) {
        if (update.weekly() != null) {
            for (var w : update.weekly()) {
                int n = jdbc.sql("UPDATE model_metric_weekly SET recall_pct = ? WHERE model = ? AND version = ? AND week_start = ?")
                        .params(w.recallPct(), model, w.version(), w.weekStart()).update();
                if (n == 0) {
                    jdbc.sql("INSERT INTO model_metric_weekly (model, version, week_start, recall_pct) VALUES (?, ?, ?, ?)")
                            .params(model, w.version(), w.weekStart(), w.recallPct()).update();
                }
            }
        }
        if (update.drift() != null) {
            for (var d : update.drift()) {
                int n = jdbc.sql("UPDATE feature_drift SET psi = ?, measured_at = ? WHERE model = ? AND feature = ?")
                        .params(d.psi(), ts(now), model, d.feature()).update();
                if (n == 0) {
                    jdbc.sql("INSERT INTO feature_drift (model, feature, psi, measured_at) VALUES (?, ?, ?, ?)")
                            .params(model, d.feature(), d.psi(), ts(now)).update();
                }
            }
        }
    }

    public Optional<Integer> latestPending(String model) {
        return jdbc.sql("SELECT version FROM release_request WHERE model = ? AND status = 'PENDING'").param(model)
                .query(Integer.class).list().stream().findFirst();
    }
}