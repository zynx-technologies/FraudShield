package com.fraudshield.cases.alerts;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** A suspicious decision from the decision service. Unknown fields are ignored, so the event can grow. */
public record AlertIntake(
        @NotBlank String decisionId,
        @NotBlank String transactionId,
        @NotBlank String channel,
        @NotNull BigDecimal amount,
        @NotBlank String currency,
        @NotBlank String accountToken,
        String payeeToken,
        String deviceId,
        @Min(0) @Max(99) int score,
        @NotBlank String outcome,
        List<Reason> reasons,
        List<RuleHit> ruleHits,
        String modelVersion,
        String rulesetVersion,
        Long latencyMs,
        Boolean fallback,
        Map<String, Object> features,
        @NotNull Instant decidedAt) {

    public record Reason(String code, String label, double value) {}

    public record RuleHit(String ruleId, String name, String action, boolean live, boolean absolute) {}

    /** The headline shown in the queue: the strongest model reason, or the live rule that fired. */
    public String mainReason() {
        if (ruleHits != null) {
            for (RuleHit h : ruleHits) if (h.absolute()) return h.name();
        }
        if (reasons != null && !reasons.isEmpty()) return reasons.getFirst().label();
        if (ruleHits != null) {
            for (RuleHit h : ruleHits) if (h.live()) return h.name();
        }
        return Boolean.TRUE.equals(fallback) ? "Fallback rules (scoring unavailable)" : "Policy threshold";
    }
}