package com.fraudshield.cases.cases;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Everything an analyst needs on one screen (screen 3): transaction, model reasons, links, history, outcome. */
public record CaseView(
        String id,
        String status,
        String assignedTo,
        int score,
        Transaction transaction,
        List<Reason> reasons,
        List<Map<String, Object>> ruleHits,
        List<AlertSummary> alerts,
        List<Event> history,
        Graph links,
        Outcome outcome) {

    public record Transaction(String alertId, String decisionId, String transactionId, String channel, BigDecimal amount,
                              String currency, String accountMasked, String payee, boolean payeeFirstPayment,
                              String deviceId, boolean deviceFirstSeenToday, String decision, Integer latencyMs,
                              BigDecimal usualMedian, Double amountToMedian, String modelVersion, String rulesetVersion,
                              Instant decidedAt, Map<String, Object> features) {}

    public record Reason(String code, String label, double value) {}

    public record AlertSummary(String id, int score, BigDecimal amount, String currency, String channel,
                               String mainReason, String decision, String status) {}

    public record Event(Instant at, String actor, String type, String detail) {}

    public record Graph(List<Node> nodes, List<Edge> edges) {}

    public record Node(String id, String label, String type, boolean flagged, boolean focus) {}

    public record Edge(String from, String to, String relation) {}

    public record Outcome(String label, String reason, String decidedBy, Instant decidedAt) {}

    public record ListItem(String id, String status, String assignedTo, int score, BigDecimal amount, String currency,
                           String channel, String mainReason, Instant createdAt, String outcome) {}
}