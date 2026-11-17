package com.fraudshield.decision.engine;

import com.fraudshield.decision.features.ProfileStore;
import com.fraudshield.decision.rules.RuleSnapshotProvider;
import com.fraudshield.decision.scoring.Scorer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** Latency, fallback, and outcome metrics for this pod, for Prometheus and for the overview screen. */
@Component
public class DecisionStats {

    private static final int RESERVOIR = 10_000;

    private final MeterRegistry registry;
    private final Scorer scorer;
    private final RuleSnapshotProvider rules;
    private final ProfileStore profiles;
    private final long[] latencies = new long[RESERVOIR];
    private int next;
    private int filled;
    private volatile Instant lastFallback = Instant.EPOCH;
    private final Timer latency;
    private final Counter fallback;

    public DecisionStats(MeterRegistry registry, Scorer scorer, RuleSnapshotProvider rules, ProfileStore profiles) {
        this.registry = registry;
        this.scorer = scorer;
        this.rules = rules;
        this.profiles = profiles;
        this.latency = Timer.builder("fraudshield.decision.latency")
                .publishPercentiles(0.5, 0.99).publishPercentileHistogram().register(registry);
        // Raises the rules-only fallback alert (infra/terraform/modules/observability).
        this.fallback = Counter.builder("fraudshield.decision.fallback").register(registry);
    }

    public void record(Outcome outcome, long latencyMs, boolean usedFallback) {
        // fraudshield_decisions_total{outcome_group} drives the canary's alert-rate and step-up-rate checks.
        Counter.builder("fraudshield.decisions")
                .tag("outcome_group", outcome.group()).tag("outcome", outcome.name())
                .register(registry).increment();
        latency.record(latencyMs, TimeUnit.MILLISECONDS);
        if (usedFallback) {
            fallback.increment();
            lastFallback = Instant.now();
        }
        synchronized (latencies) {
            latencies[next] = latencyMs;
            next = (next + 1) % RESERVOIR;
            filled = Math.min(filled + 1, RESERVOIR);
        }
    }

    public long p99Millis() {
        long[] copy;
        synchronized (latencies) {
            if (filled == 0) return 0;
            copy = Arrays.copyOf(latencies, filled);
        }
        Arrays.sort(copy);
        return copy[Math.min(copy.length - 1, (int) Math.ceil(copy.length * 0.99) - 1)];
    }

    public boolean fallbackActive() {
        return lastFallback.isAfter(Instant.now().minus(Duration.ofMinutes(5)));
    }

    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        boolean fallbackActive = fallbackActive() || !scorer.available();
        status.put("decisionApi", fallbackActive ? "Degraded" : "Healthy");
        status.put("scoringP99Ms", p99Millis());
        status.put("featureFreshnessSeconds", profiles.freshness().toSeconds());
        status.put("liveModel", scorer.version());
        status.put("scoringMode", scorer.mode());
        status.put("rulesetVersion", rules.current().version());
        status.put("fallbackActive", fallbackActive);
        return status;
    }
}