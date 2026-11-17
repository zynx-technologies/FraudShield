package com.fraudshield.decision.rules;

import com.fraudshield.common.client.ServiceCalls;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Live reload of rules and thresholds (design section 5). Polls the strategy service and swaps the snapshot
 * atomically, so a decision always uses one consistent version. If the strategy service is unreachable, the
 * last good snapshot stays in force.
 */
@Component
public class RuleSnapshotProvider {

    private static final Logger log = LoggerFactory.getLogger(RuleSnapshotProvider.class);

    private final AtomicReference<RuleSnapshot> current = new AtomicReference<>(RuleSnapshot.builtIn());
    private final ServiceCalls calls;
    private final RestClient strategy;
    private final boolean enabled;

    public RuleSnapshotProvider(ServiceCalls calls,
                                @Value("${fraudshield.services.strategy-url}") String strategyUrl,
                                @Value("${fraudshield.rules.reload-enabled:true}") boolean enabled) {
        this.calls = calls;
        this.strategy = calls.client(strategyUrl, Duration.ofSeconds(5));
        this.enabled = enabled;
    }

    public RuleSnapshot current() {
        return current.get();
    }

    /** Tests and local tools can install a snapshot directly. */
    public void install(RuleSnapshot snapshot) {
        current.set(snapshot);
    }

    /** Until the first ruleset arrives, retry every 2 seconds. The pod stays not-ready meanwhile. */
    @Scheduled(initialDelayString = "${fraudshield.rules.initial-delay:1s}", fixedDelay = 2000)
    public void loadFirst() {
        if (current.get().version().equals(RuleSnapshot.builtIn().version())) reload();
    }

    @Scheduled(initialDelayString = "${fraudshield.rules.reload-interval:30s}", fixedDelayString = "${fraudshield.rules.reload-interval:30s}")
    public void reload() {
        if (!enabled) return;
        try {
            RuleSnapshot snapshot = strategy.get().uri("/api/v1/rules/live").headers(calls.asService())
                    .retrieve().body(RuleSnapshot.class);
            if (snapshot != null && !snapshot.version().equals(current.get().version())) {
                current.set(snapshot);
                log.info("Loaded ruleset {} with thresholds {}", snapshot.version(), snapshot.thresholds().version());
            }
        } catch (RuntimeException e) {
            log.warn("Could not reload rules; keeping {}: {}", current.get().version(), e.getMessage());
        }
    }
}