package com.fraudshield.cases.cases;

import static com.fraudshield.cases.Db.instant;
import static com.fraudshield.cases.Db.ts;

import com.fraudshield.cases.alerts.AlertService;
import com.fraudshield.common.web.ApiErrors;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Case investigation and outcomes (FR-5, FR-6). */
@Service
public class CaseService {

    public enum Action { CONFIRM_FRAUD, MARK_GENUINE, CALL_CUSTOMER, ESCALATE_AML }

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final CaseEvents events;
    private final AlertService alerts;

    public CaseService(JdbcClient jdbc, ObjectMapper json, CaseEvents events, AlertService alerts) {
        this.jdbc = jdbc;
        this.json = json;
        this.events = events;
        this.alerts = alerts;
    }

    public List<CaseView.ListItem> list(String status) {
        return jdbc.sql("""
                SELECT c.id, c.status, c.assigned_to, c.created_at, a.score, a.amount, a.currency, a.channel, a.main_reason,
                  (SELECT TOP 1 o.label FROM outcome o WHERE o.case_id = c.id ORDER BY o.id DESC) AS outcome_label
                FROM fraud_case c JOIN alert a ON a.id = (SELECT TOP 1 a2.id FROM alert a2 WHERE a2.case_id = c.id ORDER BY a2.score DESC)
                WHERE (? = 'ALL' OR c.status = ? OR (? = 'ACTIVE' AND c.status <> 'CLOSED'))
                ORDER BY c.created_at DESC""")
                .params(status, status, status)
                .query((rs, n) -> new CaseView.ListItem(rs.getString("id"), rs.getString("status"), rs.getString("assigned_to"),
                        rs.getInt("score"), rs.getBigDecimal("amount"), rs.getString("currency"), rs.getString("channel"),
                        rs.getString("main_reason"), instant(rs, "created_at"), rs.getString("outcome_label")))
                .list().stream().limit(500).toList();
    }

    public CaseView get(String caseId) {
        var header = jdbc.sql("SELECT id, status, assigned_to, account_token FROM fraud_case WHERE id = ?").param(caseId)
                .query((rs, n) -> new String[] {rs.getString("id"), rs.getString("status"), rs.getString("assigned_to"),
                        rs.getString("account_token")})
                .optional().orElseThrow(() -> new ApiErrors.NotFound("Case not found"));

        List<Map<String, Object>> rows = jdbc.sql("SELECT * FROM alert WHERE case_id = ? ORDER BY score DESC, created_at")
                .param(caseId).query((rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (String c : List.of("id", "decision_id", "transaction_id", "channel", "currency", "account_token",
                            "payee_token", "device_id", "outcome", "main_reason", "model_version", "ruleset_version",
                            "reasons", "rule_hits", "features", "status")) {
                        row.put(c, rs.getString(c));
                    }
                    row.put("amount", rs.getBigDecimal("amount"));
                    row.put("score", rs.getInt("score"));
                    row.put("latency_ms", rs.getObject("latency_ms") == null ? null : rs.getInt("latency_ms"));
                    row.put("created_at", instant(rs, "created_at"));
                    return row;
                }).list();
        if (rows.isEmpty()) throw new ApiErrors.NotFound("Case has no alerts");
        Map<String, Object> top = rows.getFirst();

        Map<String, Object> features = top.get("features") == null ? Map.of()
                : json.readValue((String) top.get("features"), new TypeReference<Map<String, Object>>() {});
        List<CaseView.Reason> reasons = json.readValue((String) top.get("reasons"), new TypeReference<List<CaseView.Reason>>() {});
        List<Map<String, Object>> ruleHits = json.readValue((String) top.get("rule_hits"), new TypeReference<List<Map<String, Object>>>() {});

        BigDecimal median = features.get("medianAmount90d") == null ? null : new BigDecimal(features.get("medianAmount90d").toString());
        Double ratio = features.get("amountToMedian") == null ? null : ((Number) features.get("amountToMedian")).doubleValue();
        var transaction = new CaseView.Transaction(
                (String) top.get("id"), (String) top.get("decision_id"), (String) top.get("transaction_id"),
                (String) top.get("channel"), (BigDecimal) top.get("amount"), (String) top.get("currency"),
                mask((String) top.get("account_token")), (String) top.get("payee_token"),
                Boolean.TRUE.equals(features.get("newPayee")), (String) top.get("device_id"),
                Boolean.TRUE.equals(features.get("newDevice")) && Integer.valueOf(0).equals(features.get("deviceAgeDays")),
                AlertService.decisionLabel((String) top.get("outcome")), (Integer) top.get("latency_ms"),
                median, ratio, (String) top.get("model_version"), (String) top.get("ruleset_version"),
                (Instant) top.get("created_at"), features);

        List<CaseView.AlertSummary> alertSummaries = rows.stream().map(r -> new CaseView.AlertSummary(
                (String) r.get("id"), (Integer) r.get("score"), (BigDecimal) r.get("amount"), (String) r.get("currency"),
                (String) r.get("channel"), (String) r.get("main_reason"), AlertService.decisionLabel((String) r.get("outcome")),
                (String) r.get("status"))).toList();

        List<CaseView.Event> history = jdbc.sql("SELECT at, actor, event_type, detail FROM case_event WHERE case_id = ? ORDER BY at, id")
                .param(caseId).query((rs, n) -> new CaseView.Event(instant(rs, "at"), rs.getString("actor"),
                        rs.getString("event_type"), rs.getString("detail"))).list();

        Optional<CaseView.Outcome> outcome = jdbc.sql(
                        "SELECT label, reason, decided_by, decided_at FROM outcome WHERE case_id = ? ORDER BY id DESC")
                .param(caseId).query((rs, n) -> new CaseView.Outcome(rs.getString("label"), rs.getString("reason"),
                        rs.getString("decided_by"), instant(rs, "decided_at"))).list().stream().findFirst();

        return new CaseView(header[0], header[1], header[2], (Integer) top.get("score"), transaction, reasons, ruleHits,
                alertSummaries, history, graph(header[3], (String) top.get("payee_token"), (String) top.get("device_id")),
                outcome.orElse(null));
    }

    /** Links between the account, its device and payee, other accounts that share them, and flagged entities. */
    CaseView.Graph graph(String account, String payee, String device) {
        Map<String, CaseView.Node> nodes = new LinkedHashMap<>();
        List<CaseView.Edge> edges = new ArrayList<>();
        nodes.put(account, new CaseView.Node(account, mask(account), "ACCOUNT", false, true));
        if (payee != null) {
            nodes.put(payee, new CaseView.Node(payee, "Payee " + payee, "PAYEE", false, false));
            edges.add(new CaseView.Edge(account, payee, "PAID"));
        }
        if (device != null) {
            nodes.put(device, new CaseView.Node(device, "Device " + device, "DEVICE", false, false));
            edges.add(new CaseView.Edge(account, device, "USED"));
        }
        // Other accounts that paid the same payee or used the same device, from alerts.
        jdbc.sql("""
                SELECT DISTINCT account_token, payee_token, device_id FROM alert
                WHERE account_token <> ? AND ((payee_token IS NOT NULL AND payee_token = ?) OR (device_id IS NOT NULL AND device_id = ?))""")
                .params(account, payee, device)
                .query((rs, n) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)}).list()
                .stream().limit(20).forEach(r -> {
                    nodes.putIfAbsent(r[0], new CaseView.Node(r[0], mask(r[0]), "ACCOUNT", false, false));
                    if (payee != null && payee.equals(r[1])) edges.add(new CaseView.Edge(r[0], payee, "PAID"));
                    if (device != null && device.equals(r[2])) edges.add(new CaseView.Edge(r[0], device, "USED"));
                });
        // Links from the graph job.
        List<String> ids = new ArrayList<>(nodes.keySet());
        for (String id : ids) {
            jdbc.sql("SELECT from_type, from_id, to_type, to_id, relation, flagged FROM entity_link WHERE from_id = ? OR to_id = ?")
                    .params(id, id).query((rs, n) -> new Object[] {rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getBoolean(6)}).list()
                    .stream().limit(20).forEach(l -> {
                        String fromId = (String) l[1], toId = (String) l[3];
                        boolean flagged = (Boolean) l[5];
                        nodes.putIfAbsent(fromId, new CaseView.Node(fromId, label((String) l[0], fromId), (String) l[0], false, false));
                        CaseView.Node to = nodes.get(toId);
                        nodes.put(toId, new CaseView.Node(toId, label((String) l[2], toId), (String) l[2],
                                flagged || (to != null && to.flagged()), to != null && to.focus()));
                        edges.add(new CaseView.Edge(fromId, toId, (String) l[4]));
                    });
        }
        List<CaseView.Edge> unique = edges.stream().distinct().toList();
        return new CaseView.Graph(List.copyOf(nodes.values()), unique);
    }

    @Transactional
    public CaseView act(String caseId, Action action, String reason, String user, boolean teamLead) {
        if (reason == null || reason.isBlank() || reason.strip().length() < 5) {
            throw new ApiErrors.BadRequest("A reason of at least 5 characters is required for every action");
        }
        reason = reason.strip();
        if (reason.length() > 500) throw new ApiErrors.BadRequest("The reason must be 500 characters or fewer");
        String status = jdbc.sql("SELECT status FROM fraud_case WHERE id = ?").param(caseId).query(String.class)
                .optional().orElseThrow(() -> new ApiErrors.NotFound("Case not found"));
        Instant now = Instant.now();
        boolean closing = action == Action.CONFIRM_FRAUD || action == Action.MARK_GENUINE;
        if ("CLOSED".equals(status)) {
            // Corrections add a new outcome row; they never change the old one. Team leads only.
            if (!closing || !teamLead) throw new ApiErrors.Conflict("This case is closed. A team lead can record a correction.");
        }
        alerts.assignCase(caseId, user, now);
        switch (action) {
            case CONFIRM_FRAUD, MARK_GENUINE -> {
                String label = action == Action.CONFIRM_FRAUD ? "CONFIRMED_FRAUD" : "GENUINE";
                List<String[]> caseAlerts = jdbc.sql("SELECT id, transaction_id FROM alert WHERE case_id = ?").param(caseId)
                        .query((rs, n) -> new String[] {rs.getString(1), rs.getString(2)}).list();
                for (String[] a : caseAlerts) {
                    jdbc.sql("INSERT INTO outcome (case_id, alert_id, transaction_id, label, reason, decided_by, decided_at) VALUES (?, ?, ?, ?, ?, ?, ?)")
                            .params(caseId, a[0], a[1], label, reason, user, ts(now)).update();
                }
                jdbc.sql("UPDATE alert SET status = 'CLOSED' WHERE case_id = ?").param(caseId).update();
                jdbc.sql("UPDATE fraud_case SET status = 'CLOSED', closed_at = ? WHERE id = ?").params(ts(now), caseId).update();
                String what = "CLOSED".equals(status) ? "Correction: " : "";
                events.record(caseId, now, user, action.name(), what + (action == Action.CONFIRM_FRAUD
                        ? "Confirmed fraud; payment blocked. " : "Marked genuine; payment released. ") + "Reason: " + reason);
            }
            case CALL_CUSTOMER -> events.record(caseId, now, user, action.name(), "Customer call. " + reason);
            case ESCALATE_AML -> {
                jdbc.sql("UPDATE fraud_case SET status = 'ESCALATED' WHERE id = ?").param(caseId).update();
                events.record(caseId, now, user, action.name(), "Escalated to the AML team. " + reason);
            }
        }
        return get(caseId);
    }

    /** Account tokens are shown masked by default (design section 10). */
    public static String mask(String token) {
        if (token == null) return null;
        return token.length() <= 4 ? "****" : "****" + token.substring(token.length() - 4);
    }

    private static String label(String type, String id) {
        return switch (type) {
            case "ACCOUNT" -> mask(id);
            case "PAYEE" -> "Payee " + id;
            case "DEVICE" -> "Device " + id;
            case "IP" -> "IP " + id;
            default -> id;
        };
    }
}