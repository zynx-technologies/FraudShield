package com.fraudshield.decision.features;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Velocity counters: transaction count and value per account over 1 hour, 24 hours, and 7 days, and card
 * attempts over 2 minutes.
 *
 * This release keeps counters in memory per pod. Production uses Azure Managed Redis (atomic counters shared by
 * every pod and replicated between regions); see the README.
 */
@Component
public class VelocityStore {

    public record Snapshot(int count1h, BigDecimal value24h, int count7d, int cardAttempts2m) {}

    private record Event(Instant at, BigDecimal amount, boolean card) {}

    private final Map<String, Deque<Event>> byAccount = new ConcurrentHashMap<>();

    public Snapshot snapshot(String account, Instant now) {
        Deque<Event> events = byAccount.get(account);
        if (events == null) return new Snapshot(0, BigDecimal.ZERO, 0, 0);
        int c1h = 0, c7d = 0, card2m = 0;
        BigDecimal v24h = BigDecimal.ZERO;
        synchronized (events) {
            for (Event e : events) {
                Duration age = Duration.between(e.at(), now);
                if (age.compareTo(Duration.ofDays(7)) <= 0) c7d++;
                if (age.compareTo(Duration.ofHours(24)) <= 0) v24h = v24h.add(e.amount());
                if (age.compareTo(Duration.ofHours(1)) <= 0) c1h++;
                if (e.card() && age.compareTo(Duration.ofMinutes(2)) <= 0) card2m++;
            }
        }
        return new Snapshot(c1h, v24h, c7d, card2m);
    }

    /** Called for every decision, including the current one before it is scored, so counts include it. */
    public void record(String account, Instant at, BigDecimal amount, boolean card) {
        Deque<Event> events = byAccount.computeIfAbsent(account, k -> new ArrayDeque<>());
        synchronized (events) {
            events.addLast(new Event(at, amount, card));
            Instant cutoff = at.minus(Duration.ofDays(7));
            while (!events.isEmpty() && events.peekFirst().at().isBefore(cutoff)) events.removeFirst();
        }
    }
}