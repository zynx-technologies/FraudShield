package com.fraudshield.strategy.thresholds;

import static com.fraudshield.strategy.Db.instant;
import static com.fraudshield.strategy.Db.ts;

import com.fraudshield.common.web.ApiErrors;
import com.fraudshield.strategy.Approvals;
import com.fraudshield.strategy.rules.RulesetVersions;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Score thresholds, the 30-day simulator, and two-person approval (screen 4). */
@Service
public class ThresholdService {

    /** Matches the decision service's RuleSnapshot.Thresholds. */
    public record Thresholds(int stepUp, int hold, int block, String version) {}

    public record Policy(long id, int stepUp, int hold, int block, String status, String reason, String author,
                         Instant createdAt, String decidedBy, Instant decidedAt) {}

    public record Proposal(int stepUp, int hold, int block, String reason) {}

    /**
     * Over the simulator window: fraud recall and value stopped count fraud scored at or above the hold
     * threshold; alerts are payments held or blocked.
     */
    public record Metrics(BigDecimal fraudValueStopped, double fraudRecallPct, long alertsPerDay, Double falsePositiveRatio,
                          long stepUpsPerDay, long blocksPerDay) {}

    public record Simulation(int windowDays, Instant refreshedAt, Metrics current, Metrics proposed) {}

    private static final Thresholds DEFAULT = new Thresholds(60, 80, 99, "th-default");

    private final JdbcClient jdbc;
    private final RulesetVersions versions;

    public ThresholdService(JdbcClient jdbc, RulesetVersions versions) {
        this.jdbc = jdbc;
        this.versions = versions;
    }

    public Thresholds active() {
        return jdbc.sql("SELECT id, step_up_from, hold_from, block_from FROM threshold_policy WHERE status = 'ACTIVE'")
                .query((rs, n) -> new Thresholds(rs.getInt(2), rs.getInt(3), rs.getInt(4), "th-" + rs.getLong(1)))
                .optional().orElse(DEFAULT);
    }

    public List<Policy> policies() {
        return jdbc.sql("""
                SELECT id, step_up_from, hold_from, block_from, status, reason, author, created_at, decided_by, decided_at
                FROM threshold_policy ORDER BY id DESC""")
                .query((rs, n) -> new Policy(rs.getLong("id"), rs.getInt("step_up_from"), rs.getInt("hold_from"),
                        rs.getInt("block_from"), rs.getString("status"), rs.getString("reason"), rs.getString("author"),
                        instant(rs, "created_at"), rs.getString("decided_by"), instant(rs, "decided_at")))
                .list().stream().limit(50).toList();
    }

    public Optional<Policy> pending() {
        return policies().stream().filter(p -> p.status().equals("PENDING")).findFirst();
    }

    public Simulation simulate(int stepUp, int hold, int block) {
        validate(stepUp, hold, block);
        record Row(int score, long genuine, long fraud, BigDecimal value, int window, Instant refreshed) {}
        List<Row> rows = jdbc.sql("SELECT score, genuine_count, fraud_count, fraud_value, window_days, refreshed_at FROM score_distribution")
                .query((rs, n) -> new Row(rs.getInt(1), rs.getLong(2), rs.getLong(3), rs.getBigDecimal(4), rs.getInt(5),
                        instant(rs, "refreshed_at"))).list();
        if (rows.isEmpty()) {
            throw new ApiErrors.Unavailable("No scored history yet. The lakehouse job fills score_distribution daily.");
        }
        int window = rows.getFirst().window();
        Thresholds now = active();
        java.util.function.Function<int[], Metrics> metrics = t -> {
            long fraudTotal = 0, fraudAlerted = 0, genuineAlerted = 0, stepUps = 0, blocks = 0;
            BigDecimal value = BigDecimal.ZERO;
            for (Row r : rows) {
                fraudTotal += r.fraud();
                if (r.score() >= t[1]) {
                    fraudAlerted += r.fraud();
                    genuineAlerted += r.genuine();
                    value = value.add(r.value());
                } else if (r.score() >= t[0]) {
                    stepUps += r.fraud() + r.genuine();
                }
                if (r.score() >= t[2]) blocks += r.fraud() + r.genuine();
            }
            return new Metrics(value, fraudTotal == 0 ? 0 : Math.round(1000.0 * fraudAlerted / fraudTotal) / 10.0,
                    Math.round((double) (fraudAlerted + genuineAlerted) / window), fraudAlerted == 0 ? null
                    : Math.round(10.0 * genuineAlerted / fraudAlerted) / 10.0,
                    Math.round((double) stepUps / window), Math.round((double) blocks / window));
        };
        return new Simulation(window, rows.getFirst().refreshed(),
                metrics.apply(new int[] {now.stepUp(), now.hold(), now.block()}),
                metrics.apply(new int[] {stepUp, hold, block}));
    }

    @Transactional
    public Policy propose(Proposal p, String author, Instant now) {
        validate(p.stepUp(), p.hold(), p.block());
        String reason = Approvals.requireReason(p.reason());
        if (pending().isPresent()) throw new ApiErrors.Conflict("A threshold change is already waiting for approval");
        Thresholds current = active();
        if (current.stepUp() == p.stepUp() && current.hold() == p.hold() && current.block() == p.block()) {
            throw new ApiErrors.BadRequest("These are the thresholds already in force");
        }
        jdbc.sql("INSERT INTO threshold_policy (step_up_from, hold_from, block_from, status, reason, author, created_at) VALUES (?, ?, ?, 'PENDING', ?, ?, ?)")
                .params(p.stepUp(), p.hold(), p.block(), reason, author, ts(now)).update();
        return pending().orElseThrow();
    }

    @Transactional
    public Policy decide(long id, boolean approve, String reason, String approver, Instant now) {
        String why = Approvals.requireReason(reason);
        Policy p = policies().stream().filter(x -> x.id() == id).findFirst()
                .orElseThrow(() -> new ApiErrors.NotFound("Threshold change not found"));
        if (!p.status().equals("PENDING")) throw new ApiErrors.Conflict("This change has already been decided");
        Approvals.requireDifferentPerson(approver, p.author(), "proposed this change");
        if (approve) {
            jdbc.sql("UPDATE threshold_policy SET status = 'SUPERSEDED' WHERE status = 'ACTIVE'").update();
        }
        jdbc.sql("UPDATE threshold_policy SET status = ?, decided_by = ?, decided_at = ?, decision_reason = ? WHERE id = ?")
                .params(approve ? "ACTIVE" : "REJECTED", approver, ts(now), why, id).update();
        if (approve) versions.bump("Thresholds th-" + id + " approved by " + approver, now);
        return policies().stream().filter(x -> x.id() == id).findFirst().orElseThrow();
    }

    static void validate(int stepUp, int hold, int block) {
        if (!(stepUp >= 1 && stepUp < hold && hold < block && block <= 99)) {
            throw new ApiErrors.BadRequest("Thresholds must satisfy 1 <= step-up < hold < block <= 99");
        }
    }
}