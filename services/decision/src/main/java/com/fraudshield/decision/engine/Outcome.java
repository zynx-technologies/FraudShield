package com.fraudshield.decision.engine;

/** Ordered by severity, so the stricter of the score and the rules wins. */
public enum Outcome {
    ALLOW, STEP_UP, HOLD, BLOCK;

    public Outcome max(Outcome other) {
        return other != null && other.ordinal() > ordinal() ? other : this;
    }

    /** Label used in the fraudshield_decisions_total metric for canary analysis. */
    public String group() {
        return switch (this) {
            case ALLOW -> "allow";
            case STEP_UP -> "step_up";
            case HOLD, BLOCK -> "alert";
        };
    }
}