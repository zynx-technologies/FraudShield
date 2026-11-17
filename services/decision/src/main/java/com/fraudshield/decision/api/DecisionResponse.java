package com.fraudshield.decision.api;

import java.util.List;

/** The design's response shape (section 5). score is null when the model was not used (fallback rules). */
public record DecisionResponse(
        String decisionId,
        String outcome,
        Integer score,
        List<String> reasonCodes,
        String modelVersion,
        String rulesetVersion,
        long latencyMs) {}