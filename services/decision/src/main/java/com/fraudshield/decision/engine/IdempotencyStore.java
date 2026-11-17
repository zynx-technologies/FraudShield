package com.fraudshield.decision.engine;

import com.fraudshield.common.web.ApiErrors;
import com.fraudshield.decision.api.DecisionResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Returns the same decision when a channel retries with the same Idempotency-Key, and refuses a key reused
 * for a different payment.
 *
 * This release keeps keys in memory for 24 hours, per pod. API Management routes by key affinity, but a
 * shared store (the Cosmos DB decisions outbox) is needed before production. See the README.
 */
@Component
public class IdempotencyStore {

    private static final Duration TTL = Duration.ofHours(24);
    private static final int MAX_ENTRIES = 500_000;

    private record Entry(int requestHash, DecisionResponse response, Instant storedAt) {}

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public DecisionResponse getOrCompute(String key, int requestHash, Supplier<DecisionResponse> compute) {
        Entry existing = entries.get(key);
        if (existing != null && existing.storedAt().plus(TTL).isAfter(Instant.now())) {
            if (existing.requestHash() != requestHash) {
                throw new ApiErrors.Conflict("This Idempotency-Key was already used for a different payment");
            }
            return existing.response();
        }
        DecisionResponse response = compute.get();
        if (entries.size() >= MAX_ENTRIES) {
            evictExpired();
        }
        entries.put(key, new Entry(requestHash, response, Instant.now()));
        return response;
    }

    private void evictExpired() {
        Instant cutoff = Instant.now().minus(TTL);
        for (Iterator<Entry> it = entries.values().iterator(); it.hasNext(); ) {
            if (it.next().storedAt().isBefore(cutoff)) it.remove();
        }
        if (entries.size() >= MAX_ENTRIES) {
            entries.clear(); // Safety valve against memory exhaustion. Retries after this get a fresh decision.
        }
    }
}