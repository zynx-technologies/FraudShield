package com.fraudshield.decision.features;

import com.fraudshield.decision.api.Channel;
import com.fraudshield.decision.api.DecisionRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Step 3 of the decision flow. Reads velocity and profiles in parallel, each with a timeout (25 ms by default,
 * design section 4). A slow store never blocks the payment: its features take safe defaults and the decision is
 * flagged as reduced confidence.
 */
@Component
public class FeatureService {

    private final VelocityStore velocity;
    private final ProfileStore profiles;
    private final Duration timeout;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public FeatureService(VelocityStore velocity, ProfileStore profiles,
                          @Value("${fraudshield.features.timeout:25ms}") Duration timeout) {
        this.velocity = velocity;
        this.profiles = profiles;
        this.timeout = timeout;
    }

    private record Profiles(ProfileStore.Account account, ProfileStore.Payee payee, ProfileStore.Device device) {}

    public Features compute(DecisionRequest r, Instant now) {
        velocity.record(r.accountToken(), now, r.amount(), r.channel() == Channel.CARD);
        var velocityFuture = CompletableFuture.supplyAsync(() -> velocity.snapshot(r.accountToken(), now), executor);
        var profileFuture = CompletableFuture.supplyAsync(() -> new Profiles(
                profiles.account(r.accountToken()), profiles.payee(r.payeeToken()), profiles.device(r.deviceId())), executor);

        boolean reduced = false;
        VelocityStore.Snapshot v;
        try {
            v = velocityFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            v = new VelocityStore.Snapshot(1, r.amount(), 1, r.channel() == Channel.CARD ? 1 : 0);
            reduced = true;
        }
        Profiles p;
        try {
            p = profileFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            p = new Profiles(null, null, null);
            reduced = true;
        }
        return build(r, now, v, p, reduced);
    }

    private static Features build(DecisionRequest r, Instant now, VelocityStore.Snapshot v, Profiles p, boolean reduced) {
        BigDecimal median = p.account() == null ? null : ProfileStore.median(p.account().recentAmounts());
        double ratio = median == null || median.signum() == 0 ? 1.0
                : r.amount().divide(median, 2, RoundingMode.HALF_UP).doubleValue();

        boolean newPayee = r.payeeToken() != null
                && (p.account() == null || !p.account().knownPayees().contains(r.payeeToken()));
        int payeeAge = p.payee() == null ? 0 : (int) Duration.between(p.payee().firstSeen(), now).toDays();
        int senders = 0;
        if (p.payee() != null) {
            Instant weekAgo = now.minus(Duration.ofDays(7));
            senders = (int) p.payee().senders().entrySet().stream()
                    .filter(e -> !e.getKey().equals(r.accountToken()) && e.getValue().isAfter(weekAgo)).count();
        }
        boolean newDevice = r.deviceId() != null
                && (p.account() == null || !p.account().knownDevices().contains(r.deviceId()));
        int deviceAge = p.device() == null ? 0 : (int) Duration.between(p.device().firstSeen(), now).toDays();

        return new Features(
                v.count1h(), v.value24h(), v.count7d(), v.cardAttempts2m(),
                median, ratio,
                newPayee, payeeAge, senders, p.payee() == null ? 0 : p.payee().fraudReports(),
                newDevice, deviceAge, p.device() != null && p.device().linkedToFraud(),
                r.remoteAccess(), r.newLocation(),
                p.payee() == null ? 0 : p.payee().muleRingScore(),
                reduced);
    }
}