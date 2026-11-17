package com.fraudshield.decision.features;

import java.math.BigDecimal;

/** Feature values for one decision (design section 6). Recorded with the decision for replay and evidence. */
public record Features(
        // Velocity: in-line, per transaction
        int txnCount1h,
        BigDecimal txnValue24h,
        int txnCount7d,
        int cardAttempts2m,
        // Customer behaviour
        BigDecimal medianAmount90d,
        double amountToMedian,
        // Payee
        boolean newPayee,
        int payeeAgeDays,
        int payeeIncomingSenders7d,
        int payeeFraudReports,
        // Device and session
        boolean newDevice,
        int deviceAgeDays,
        boolean deviceLinkedToFraud,
        boolean remoteAccess,
        boolean newLocation,
        // Graph (hourly batch)
        double muleRingScore,
        /** True when a feature lookup timed out and safe defaults were used. */
        boolean reducedConfidence) {}