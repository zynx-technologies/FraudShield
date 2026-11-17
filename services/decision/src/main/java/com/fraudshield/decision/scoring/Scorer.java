package com.fraudshield.decision.scoring;

import com.fraudshield.decision.api.DecisionRequest;
import com.fraudshield.decision.features.Features;

/** Step 4 of the decision flow. Runs in-process, with no network hop (design section 17). */
public interface Scorer {

    ScoreResult score(DecisionRequest request, Features features);

    /** False in rules-only mode. The engine then uses fallback rules with stricter channel limits. */
    boolean available();

    String version();

    String mode();
}