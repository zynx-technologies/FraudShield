package com.fraudshield.decision.features;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Customer, payee, and device profiles (design section 6). Profiles are updated in-line as payments arrive;
 * graph scores and fraud reports come from the hourly and streaming jobs.
 *
 * This release keeps profiles in memory per pod. Production reads them from Cosmos DB, kept fresh by the
 * Databricks online-feature sync; see the README.
 */
@Component
public class ProfileStore {

    public record Account(List<BigDecimal> recentAmounts, Set<String> knownPayees, Set<String> knownDevices) {}

    public record Payee(Instant firstSeen, Map<String, Instant> senders, int fraudReports, double muleRingScore) {}

    public record Device(Instant firstSeen, boolean linkedToFraud) {}

    private final Map<String, Account> accounts = new ConcurrentHashMap<>();
    private final Map<String, Payee> payees = new ConcurrentHashMap<>();
    private final Map<String, Device> devices = new ConcurrentHashMap<>();
    private volatile Instant lastUpdate = Instant.now();

    public Account account(String token) { return accounts.get(token); }

    public Payee payee(String token) { return token == null ? null : payees.get(token); }

    public Device device(String id) { return id == null ? null : devices.get(id); }

    /** Seconds since the profiles last changed. Shown as "Feature freshness" on the overview. */
    public Duration freshness() { return Duration.between(lastUpdate, Instant.now()); }

    public static BigDecimal median(List<BigDecimal> amounts) {
        if (amounts == null || amounts.isEmpty()) return null;
        List<BigDecimal> sorted = new ArrayList<>(amounts);
        Collections.sort(sorted);
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(mid)
                : sorted.get(mid - 1).add(sorted.get(mid)).divide(BigDecimal.TWO, 2, RoundingMode.HALF_UP);
    }

    /** After the decision: the payment becomes part of the customer's history. */
    public void record(String accountToken, String payeeToken, String deviceId, BigDecimal amount, Instant at) {
        Account account = accounts.computeIfAbsent(accountToken,
                k -> new Account(Collections.synchronizedList(new ArrayList<>()), ConcurrentHashMap.newKeySet(), ConcurrentHashMap.newKeySet()));
        synchronized (account.recentAmounts()) {
            account.recentAmounts().add(amount);
            if (account.recentAmounts().size() > 200) account.recentAmounts().removeFirst();
        }
        if (payeeToken != null) {
            account.knownPayees().add(payeeToken);
            payees.compute(payeeToken, (k, p) -> {
                Payee payee = p != null ? p : new Payee(at, new ConcurrentHashMap<>(), 0, 0);
                payee.senders().put(accountToken, at);
                return payee;
            });
        }
        if (deviceId != null) {
            account.knownDevices().add(deviceId);
            devices.putIfAbsent(deviceId, new Device(at, false));
        }
        lastUpdate = Instant.now();
    }

    // ---------- Loading from the feature sync (and synthetic data in local and demo environments) ----------

    public void putAccount(String token, List<BigDecimal> amounts, Set<String> payees, Set<String> devices) {
        accounts.put(token, new Account(Collections.synchronizedList(new ArrayList<>(amounts)),
                ConcurrentHashMap.newKeySet(), ConcurrentHashMap.newKeySet()));
        accounts.get(token).knownPayees().addAll(payees);
        accounts.get(token).knownDevices().addAll(devices);
        lastUpdate = Instant.now();
    }

    public void putPayee(String token, Instant firstSeen, Map<String, Instant> senders, int fraudReports, double muleRingScore) {
        payees.put(token, new Payee(firstSeen, new ConcurrentHashMap<>(senders), fraudReports, muleRingScore));
        lastUpdate = Instant.now();
    }

    public void putDevice(String id, Instant firstSeen, boolean linkedToFraud) {
        devices.put(id, new Device(firstSeen, linkedToFraud));
        lastUpdate = Instant.now();
    }
}