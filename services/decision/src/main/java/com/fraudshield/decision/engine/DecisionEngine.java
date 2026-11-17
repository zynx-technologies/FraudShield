package com.fraudshield.decision.engine;

import com.fraudshield.decision.api.DecisionRequest;
import com.fraudshield.decision.api.DecisionResponse;
import com.fraudshield.decision.events.DecisionEvent;
import com.fraudshield.decision.events.DecisionPublisher;
import com.fraudshield.decision.features.FeatureService;
import com.fraudshield.decision.features.Features;
import com.fraudshield.decision.features.ProfileStore;
import com.fraudshield.decision.rules.RuleEvaluator;
import com.fraudshield.decision.rules.RuleHit;
import com.fraudshield.decision.rules.RuleSnapshot;
import com.fraudshield.decision.rules.RuleSnapshotProvider;
import com.fraudshield.decision.scoring.Contribution;
import com.fraudshield.decision.scoring.ScoreResult;
import com.fraudshield.decision.scoring.Scorer;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The decision flow from design section 4:
 * 1 receive and validate, 2 absolute rules, 3 features, 4 score, 5 policy (rules plus thresholds), 6 respond.
 * The event is published after the response is built, so delivery never adds to payment latency.
 */
@Service
public class DecisionEngine {

    private static final Logger log = LoggerFactory.getLogger(DecisionEngine.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ID_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final IdempotencyStore idempotency;
    private final RuleSnapshotProvider rules;
    private final RuleEvaluator evaluator;
    private final FeatureService features;
    private final ProfileStore profiles;
    private final Scorer scorer;
    private final DecisionStats stats;
    private final DecisionPublisher publisher;
    private final int stepUpAlertScore;
    private final BigDecimal fallbackInstantHold;
    private final BigDecimal fallbackTransferHold;
    private final BigDecimal fallbackCardStepUp;

    public DecisionEngine(IdempotencyStore idempotency, RuleSnapshotProvider rules, RuleEvaluator evaluator,
                          FeatureService features, ProfileStore profiles, Scorer scorer, DecisionStats stats,
                          DecisionPublisher publisher,
                          @Value("${fraudshield.alerts.step-up-min-score:70}") int stepUpAlertScore,
                          @Value("${fraudshield.fallback.instant-hold-over:1000}") BigDecimal fallbackInstantHold,
                          @Value("${fraudshield.fallback.transfer-hold-over:5000}") BigDecimal fallbackTransferHold,
                          @Value("${fraudshield.fallback.card-step-up-over:500}") BigDecimal fallbackCardStepUp) {
        this.idempotency = idempotency;
        this.rules = rules;
        this.evaluator = evaluator;
        this.features = features;
        this.profiles = profiles;
        this.scorer = scorer;
        this.stats = stats;
        this.publisher = publisher;
        this.stepUpAlertScore = stepUpAlertScore;
        this.fallbackInstantHold = fallbackInstantHold;
        this.fallbackTransferHold = fallbackTransferHold;
        this.fallbackCardStepUp = fallbackCardStepUp;
    }

    public DecisionResponse decide(String idempotencyKey, DecisionRequest request) {
        long start = System.nanoTime();
        return idempotency.getOrCompute(idempotencyKey, request.hashCode(), () -> run(request, start));
    }

    private DecisionResponse run(DecisionRequest request, long start) {
        Instant now = Instant.now();
        RuleSnapshot snapshot = rules.current();
        String decisionId = "DE-" + randomId(10);

        // 2. Absolute rules, before any model (FR-2).
        List<RuleHit> absolute = evaluator.absolute(snapshot, request);
        if (!absolute.isEmpty()) {
            List<String> codes = absolute.stream().map(h -> "RULE_" + h.ruleId()).toList();
            return finish(decisionId, request, now, start, snapshot, Outcome.BLOCK, null, List.of(), absolute,
                    codes, "none", false, null);
        }

        // 3. Features, with timeouts and safe defaults.
        Features f = features.compute(request, now);

        // 4. Score. If scoring fails, fall back to rules with stricter channel limits.
        ScoreResult score = null;
        boolean fallback = !scorer.available();
        if (!fallback) {
            try {
                score = scorer.score(request, f);
            } catch (RuntimeException e) {
                log.error("Scoring failed; using fallback rules for {}", decisionId, e);
                fallback = true;
            }
        }

        // 5. Policy: rules plus thresholds. The stricter outcome wins.
        List<RuleHit> hits = evaluator.evaluate(snapshot, request, f);
        Outcome outcome = score != null ? fromScore(score.score(), snapshot.thresholds()) : fallbackOutcome(request);
        for (RuleHit hit : hits) {
            if (hit.live()) outcome = outcome.max(hit.action());
        }

        List<String> codes = new ArrayList<>();
        if (score != null) score.contributions().forEach(c -> codes.add(c.code()));
        hits.stream().filter(RuleHit::live).forEach(h -> codes.add("RULE_" + h.ruleId()));
        if (fallback) codes.add("FALLBACK_RULES");

        return finish(decisionId, request, now, start, snapshot, outcome, score,
                score == null ? List.of() : score.contributions(), hits,
                codes.stream().limit(5).toList(), score == null ? "none (fallback rules)" : score.modelVersion(), fallback, f);
    }

    private DecisionResponse finish(String decisionId, DecisionRequest request, Instant now, long start,
                                    RuleSnapshot snapshot, Outcome outcome, ScoreResult score,
                                    List<Contribution> reasons, List<RuleHit> hits, List<String> codes,
                                    String modelVersion, boolean fallback, Features f) {
        long latencyMs = (System.nanoTime() - start) / 1_000_000;
        DecisionResponse response = new DecisionResponse(decisionId, outcome.name(),
                score == null ? null : score.score(), codes, modelVersion, snapshot.version(), latencyMs);

        // 6. Respond, then record and publish off the payment path.
        stats.record(outcome, latencyMs, fallback);
        int alertScore = score != null ? score.score() : (outcome == Outcome.BLOCK ? 99 : 0);
        boolean alert = outcome == Outcome.HOLD || outcome == Outcome.BLOCK
                || (outcome == Outcome.STEP_UP && alertScore >= stepUpAlertScore);
        publisher.publish(new DecisionEvent(decisionId, request.transactionId(), request.channel().name(),
                request.amount(), request.currency(), request.accountToken(), request.payeeToken(), request.deviceId(),
                request.merchantCategory(), alertScore, outcome.name(), reasons, hits, modelVersion, snapshot.version(),
                snapshot.thresholds().version(), latencyMs, fallback, f, now), alert);
        if (outcome == Outcome.ALLOW || outcome == Outcome.STEP_UP) {
            profiles.record(request.accountToken(), request.payeeToken(), request.deviceId(), request.amount(), now);
        }
        return response;
    }

    static Outcome fromScore(int score, RuleSnapshot.Thresholds t) {
        if (score >= t.block()) return Outcome.BLOCK;
        if (score >= t.hold()) return Outcome.HOLD;
        if (score >= t.stepUp()) return Outcome.STEP_UP;
        return Outcome.ALLOW;
    }

    /** Design section 4: when scoring fails, apply stricter limits by channel. Confirm the limits with the fraud lead. */
    Outcome fallbackOutcome(DecisionRequest r) {
        return switch (r.channel()) {
            case INSTANT -> r.amount().compareTo(fallbackInstantHold) > 0 ? Outcome.HOLD : Outcome.STEP_UP;
            case TRANSFER -> r.amount().compareTo(fallbackTransferHold) > 0 ? Outcome.HOLD : Outcome.STEP_UP;
            case CARD -> r.amount().compareTo(fallbackCardStepUp) > 0 ? Outcome.STEP_UP : Outcome.ALLOW;
        };
    }

    private static String randomId(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) sb.append(ID_CHARS.charAt(RANDOM.nextInt(ID_CHARS.length())));
        return sb.toString();
    }
}