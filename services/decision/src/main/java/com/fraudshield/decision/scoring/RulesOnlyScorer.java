package com.fraudshield.decision.scoring;

import com.fraudshield.decision.api.DecisionRequest;
import com.fraudshield.decision.features.Features;

/** Scoring switched off: every decision uses fallback rules. */
public class RulesOnlyScorer implements Scorer {

    @Override
    public ScoreResult score(DecisionRequest request, Features features) {
        throw new IllegalStateException("Scoring is switched off");
    }

    @Override public boolean available() { return false; }

    @Override public String version() { return "none"; }

    @Override public String mode() { return "rules-only"; }
}