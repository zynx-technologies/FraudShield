package com.fraudshield.decision.features;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Component;

/**
 * Synthetic profiles for local and demo environments, matching the design's example case (CS-20417): a customer
 * whose usual payment is about GBP 210 sends GBP 9,850 to a new payee from a new device, with a remote-access
 * tool running. Never enabled in test, perf, or prod (fraudshield.seed.enabled).
 */
@Component
@ConditionalOnProperty(name = "fraudshield.seed.enabled", havingValue = "true")
class DemoProfiles {

    private final ProfileStore profiles;

    DemoProfiles(ProfileStore profiles) {
        this.profiles = profiles;
    }

    @EventListener(ApplicationReadyEvent.class)
    void seed() {
        Instant now = Instant.now();
        profiles.putAccount("tok_8fa1",
                List.of(bd(180), bd(205), bd(210), bd(212), bd(199), bd(240), bd(215), bd(230), bd(190), bd(210)),
                Set.of("tok_utility", "tok_landlord"), Set.of("D-12"));
        profiles.putPayee("tok_21bd", now.minus(Duration.ofDays(3)),
                Map.of("tok_7741", now.minus(Duration.ofDays(2)), "tok_9020", now.minus(Duration.ofDays(1))), 0, 0.4);
        profiles.putPayee("tok_landlord", now.minus(Duration.ofDays(900)), Map.of(), 0, 0);
        profiles.putDevice("D-12", now.minus(Duration.ofDays(400)), false);
        profiles.putDevice("D-99", now.minus(Duration.ofDays(20)), true);
        profiles.putAccount("tok_31c0", List.of(bd(45), bd(60), bd(52), bd(38), bd(70)), Set.of("tok_grocer"), Set.of("D-31"));
        profiles.putDevice("D-31", now.minus(Duration.ofDays(200)), false);
    }

    private static BigDecimal bd(int v) { return BigDecimal.valueOf(v); }
}