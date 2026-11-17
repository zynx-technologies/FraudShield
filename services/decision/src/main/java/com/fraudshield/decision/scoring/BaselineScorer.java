package com.fraudshield.decision.scoring;

import com.fraudshield.decision.api.DecisionRequest;
import com.fraudshield.decision.features.Features;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A transparent, hand-weighted logistic score over the design's feature groups. It stands in until fs-txn, the
 * trained LightGBM model exported to ONNX, is released through the model pipeline. It is labelled
 * "fs-baseline-1" everywhere, so nobody mistakes it for a trained model.
 *
 * Weights are in log-odds. Reported contributions are the log-odds divided by ten, which keeps them on the same
 * scale as the reason weights analysts see for the trained model.
 */
public class BaselineScorer implements Scorer {

    static final String VERSION = "fs-baseline-1";
    private static final double INTERCEPT = -6.0;

    @Override
    public ScoreResult score(DecisionRequest r, Features f) {
        List<Contribution> parts = new ArrayList<>();
        double ratio = Math.max(f.amountToMedian(), 1.0);
        add(parts, "AMOUNT_VS_PATTERN", "Amount vs. 90-day pattern", 0.55 * Math.min(log2(ratio), 6));
        if (f.newPayee()) {
            boolean high = r.amount().compareTo(BigDecimal.valueOf(1000)) > 0;
            add(parts, "NEW_PAYEE_HIGH_AMOUNT", high ? "New payee and high amount" : "New payee", high ? 2.0 : 0.6);
        }
        if (f.remoteAccess()) add(parts, "REMOTE_ACCESS", "Remote-access tool active", 2.2);
        double mule = (f.payeeIncomingSenders7d() >= 2 ? 0.6 * Math.min(f.payeeIncomingSenders7d(), 5) : 0)
                + 2.0 * f.muleRingScore();
        add(parts, "PAYEE_MULE_SIGNALS", "Payee has mule signals", mule);
        if (f.payeeFraudReports() > 0) add(parts, "PAYEE_FRAUD_REPORTS", "Payee has fraud reports", 1.5 * Math.min(f.payeeFraudReports(), 2));
        if (f.deviceLinkedToFraud()) add(parts, "DEVICE_LINKED_FRAUD", "Device linked to confirmed fraud", 3.0);
        else if (f.newDevice() && f.deviceAgeDays() == 0) add(parts, "NEW_DEVICE", "Device first seen today", 1.0);
        if (f.newLocation()) add(parts, "NEW_LOCATION", "Unusual login location", 0.9);
        if (f.txnCount1h() >= 5) add(parts, "UNUSUAL_VELOCITY", "Unusual velocity", Math.min(0.4 * (f.txnCount1h() - 4), 2.5));
        if (f.cardAttempts2m() >= 3) add(parts, "CARD_TESTING", "Rapid card testing", Math.min(0.7 * (f.cardAttempts2m() - 2), 3.0));

        double logit = INTERCEPT + parts.stream().mapToDouble(Contribution::value).sum();
        double probability = 1 / (1 + Math.exp(-logit));
        int score = (int) Math.min(99, Math.round(99 * probability));

        List<Contribution> reported = parts.stream()
                .sorted(Comparator.comparingDouble(Contribution::value).reversed())
                .map(c -> new Contribution(c.code(), c.label(), Math.round(c.value() * 10) / 100.0))
                .filter(c -> c.value() >= 0.05)
                .limit(5)
                .toList();
        return new ScoreResult(score, reported, VERSION);
    }

    private static void add(List<Contribution> parts, String code, String label, double value) {
        if (value > 0) parts.add(new Contribution(code, label, value));
    }

    private static double log2(double x) { return Math.log(x) / Math.log(2); }

    @Override public boolean available() { return true; }

    @Override public String version() { return VERSION; }

    @Override public String mode() { return "baseline"; }
}