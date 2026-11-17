package com.fraudshield.decision.events;

import com.fraudshield.common.client.ServiceCalls;
import com.fraudshield.decision.rules.RuleHit;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Delivers decisions off the payment path:
 * <ul>
 *   <li>alerts (HOLD, BLOCK, and high-scoring STEP_UP) to the case service, with retries;</li>
 *   <li>hourly decision counts to the case service, for the operations overview;</li>
 *   <li>rule hit counts to the strategy service, for the rules screen.</li>
 * </ul>
 * This release queues in memory. The design's Cosmos DB outbox and Event Hubs stream (which also feed the
 * lakehouse) replace this queue before production; see the README.
 */
@Component
public class DecisionPublisher {

    private static final Logger log = LoggerFactory.getLogger(DecisionPublisher.class);
    private static final int MAX_ATTEMPTS = 8;

    private record Pending(DecisionEvent event, int attempts) {}

    private final ServiceCalls calls;
    private final RestClient caseService;
    private final RestClient strategyService;
    private final boolean enabled;
    private final BlockingQueue<Pending> alerts = new LinkedBlockingQueue<>(100_000);
    private final Map<String, long[]> hourly = new ConcurrentHashMap<>(); // count, latency total, latency max
    private final Map<String, LongAdder> ruleHits = new ConcurrentHashMap<>();
    private final List<DecisionEvent> recent = Collections.synchronizedList(new LinkedList<>());
    private final Thread worker;
    private volatile boolean running = true;

    public DecisionPublisher(ServiceCalls calls,
                             @Value("${fraudshield.services.case-url}") String caseUrl,
                             @Value("${fraudshield.services.strategy-url}") String strategyUrl,
                             @Value("${fraudshield.events.enabled:true}") boolean enabled) {
        this.calls = calls;
        this.caseService = calls.client(caseUrl, Duration.ofSeconds(5));
        this.strategyService = calls.client(strategyUrl, Duration.ofSeconds(5));
        this.enabled = enabled;
        this.worker = Thread.ofVirtual().name("alert-delivery").start(this::deliverAlerts);
    }

    public void publish(DecisionEvent event, boolean alert) {
        recent.add(event);
        if (recent.size() > 200) recent.removeFirst();
        String key = event.decidedAt().truncatedTo(ChronoUnit.HOURS) + "|" + event.channel() + "|" + event.outcome();
        hourly.compute(key, (k, v) -> {
            long[] a = v == null ? new long[3] : v;
            a[0]++;
            a[1] += event.latencyMs();
            a[2] = Math.max(a[2], event.latencyMs());
            return a;
        });
        for (RuleHit hit : event.ruleHits()) {
            if (!hit.absolute()) ruleHits.computeIfAbsent(hit.ruleId(), k -> new LongAdder()).increment();
        }
        if (alert && !alerts.offer(new Pending(event, 0))) {
            log.error("Alert queue full; alert for {} not delivered", event.decisionId());
        }
    }

    /** The most recent decisions on this pod. Used by tests and local troubleshooting. */
    public List<DecisionEvent> recent() {
        synchronized (recent) {
            return List.copyOf(recent);
        }
    }

    private void deliverAlerts() {
        while (running) {
            try {
                Pending p = alerts.poll(1, TimeUnit.SECONDS);
                if (p == null || !enabled) continue;
                try {
                    caseService.post().uri("/api/v1/alerts").headers(calls.asService()).body(p.event())
                            .retrieve().toBodilessEntity();
                } catch (RuntimeException e) {
                    if (p.attempts() + 1 >= MAX_ATTEMPTS) {
                        log.error("Giving up on alert for {} after {} attempts: {}", p.event().decisionId(), MAX_ATTEMPTS, e.getMessage());
                    } else {
                        Thread.sleep(Math.min(30_000, 500L << p.attempts()));
                        alerts.offer(new Pending(p.event(), p.attempts() + 1));
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public record HourlyCount(Instant hour, String channel, String outcome, long decisions, long latencyMsTotal, long latencyMsMax) {}

    @Scheduled(fixedDelayString = "${fraudshield.events.stats-interval:10s}")
    public void flushCounts() {
        if (!enabled || hourly.isEmpty()) return;
        List<HourlyCount> batch = new ArrayList<>();
        for (String key : List.copyOf(hourly.keySet())) {
            long[] a = hourly.remove(key);
            if (a == null) continue;
            String[] parts = key.split("\\|");
            batch.add(new HourlyCount(Instant.parse(parts[0]), parts[1], parts[2], a[0], a[1], a[2]));
        }
        try {
            caseService.post().uri("/api/v1/decision-events").headers(calls.asService()).body(batch)
                    .retrieve().toBodilessEntity();
        } catch (RuntimeException e) {
            log.warn("Could not send decision counts; will retry: {}", e.getMessage());
            for (HourlyCount c : batch) {
                hourly.merge(c.hour() + "|" + c.channel() + "|" + c.outcome(),
                        new long[] {c.decisions(), c.latencyMsTotal(), c.latencyMsMax()},
                        (x, y) -> new long[] {x[0] + y[0], x[1] + y[1], Math.max(x[2], y[2])});
            }
        }
    }

    @Scheduled(fixedDelayString = "${fraudshield.events.rule-hits-interval:30s}")
    public void flushRuleHits() {
        if (!enabled || ruleHits.isEmpty()) return;
        Map<String, Long> batch = new ConcurrentHashMap<>();
        ruleHits.forEach((id, adder) -> {
            long n = adder.sumThenReset();
            if (n > 0) batch.put(id, n);
        });
        if (batch.isEmpty()) return;
        try {
            strategyService.post().uri("/api/v1/rules/hits").headers(calls.asService()).body(Map.of("hits", batch))
                    .retrieve().toBodilessEntity();
        } catch (RuntimeException e) {
            log.warn("Could not send rule hits; will retry: {}", e.getMessage());
            batch.forEach((id, n) -> ruleHits.computeIfAbsent(id, k -> new LongAdder()).add(n));
        }
    }

    @PreDestroy
    void stop() {
        running = false;
        worker.interrupt();
        flushCounts();
        flushRuleHits();
    }
}