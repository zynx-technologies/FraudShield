package com.fraudshield.decision.scoring;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * FRAUDSHIELD_SCORING_MODE, set by the Helm chart:
 *   baseline    hand-weighted baseline score (default until a trained model is released)
 *   rules-only  no score; fallback rules decide
 *   model       the released ONNX model. Not built in this release, so the service refuses to start rather than
 *               silently scoring with something else.
 */
@Configuration
public class ScoringConfig {

    @Bean
    Scorer scorer(@Value("${fraudshield.scoring.mode:baseline}") String mode,
                  @Value("${fraudshield.scoring.model-version:}") String modelVersion) {
        return switch (mode) {
            case "baseline" -> new BaselineScorer();
            case "rules-only" -> new RulesOnlyScorer();
            case "model" -> throw new IllegalStateException("Model " + modelVersion + " was released, but ONNX scoring is "
                    + "not built in this release. Set the model back to the previous version, or build the ONNX scorer first.");
            default -> throw new IllegalStateException("Unknown scoring mode: " + mode);
        };
    }
}