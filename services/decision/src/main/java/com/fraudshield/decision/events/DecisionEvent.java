package com.fraudshield.decision.events;

import com.fraudshield.decision.features.Features;
import com.fraudshield.decision.rules.RuleHit;
import com.fraudshield.decision.scoring.Contribution;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Everything needed to replay a decision: inputs, features, model, ruleset, and threshold versions
 * (design section 2, audit).
 */
public record DecisionEvent(
        String decisionId,
        String transactionId,
        String channel,
        BigDecimal amount,
        String currency,
        String accountToken,
        String payeeToken,
        String deviceId,
        String merchantCategory,
        int score,
        String outcome,
        List<Contribution> reasons,
        List<RuleHit> ruleHits,
        String modelVersion,
        String rulesetVersion,
        String thresholdsVersion,
        long latencyMs,
        boolean fallback,
        Features features,
        Instant decidedAt) {}