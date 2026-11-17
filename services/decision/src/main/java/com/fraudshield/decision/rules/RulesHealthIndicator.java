package com.fraudshield.decision.rules;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * The pod is not ready until it has loaded a ruleset from the strategy service. Without one, sanctions and
 * blocklists would not be enforced, so Kubernetes must not send it payments. Part of the readiness group.
 */
@Component("rules")
public class RulesHealthIndicator implements HealthIndicator {

    private final RuleSnapshotProvider rules;
    private final boolean required;

    public RulesHealthIndicator(RuleSnapshotProvider rules,
                                @Value("${fraudshield.rules.required-for-readiness:true}") boolean required) {
        this.rules = rules;
        this.required = required;
    }

    @Override
    public Health health() {
        String version = rules.current().version();
        if (required && RuleSnapshot.builtIn().version().equals(version)) {
            return Health.outOfService().withDetail("ruleset", "not loaded yet").build();
        }
        return Health.up().withDetail("ruleset", version).build();
    }
}