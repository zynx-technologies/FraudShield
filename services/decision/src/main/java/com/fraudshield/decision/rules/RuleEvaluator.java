package com.fraudshield.decision.rules;

import com.fraudshield.decision.api.Channel;
import com.fraudshield.decision.api.DecisionRequest;
import com.fraudshield.decision.engine.Outcome;
import com.fraudshield.decision.features.Features;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Evaluates parameterised rule types in memory. Strategists create rules from these types in the portal, and a
 * second person approves them before they go live (FR-8).
 *
 * The design names Drools. Parameterised types cover every rule in the design and need no rule compiler on the
 * payment path; Drools can replace this class behind the same snapshot contract when free-form rules are needed.
 */
@Component
public class RuleEvaluator {

    /** Step 2 of the decision flow: absolute rules, before features or the model (FR-2). */
    public List<RuleHit> absolute(RuleSnapshot snapshot, DecisionRequest request) {
        List<RuleHit> hits = new ArrayList<>();
        if (request.payeeToken() != null && snapshot.sanctionedPayees().contains(request.payeeToken())) {
            hits.add(new RuleHit("SANCTIONS", "Payment to sanctioned payee", Outcome.BLOCK, true, true));
        }
        if (request.deviceId() != null && snapshot.blockedDevices().contains(request.deviceId())) {
            hits.add(new RuleHit("DEVICE_BLOCKLIST", "Device on the blocklist", Outcome.BLOCK, true, true));
        }
        return hits;
    }

    /** Step 5: rules that use features. SHADOW rules are returned too, flagged as not live. */
    public List<RuleHit> evaluate(RuleSnapshot snapshot, DecisionRequest request, Features f) {
        List<RuleHit> hits = new ArrayList<>();
        for (RuleSnapshot.Rule rule : snapshot.rules()) {
            if (matches(rule, request, f)) {
                hits.add(new RuleHit(rule.id(), rule.name(), action(rule.action()), rule.live(), false));
            }
        }
        return hits;
    }

    boolean matches(RuleSnapshot.Rule rule, DecisionRequest r, Features f) {
        return switch (rule.type()) {
            case "CARD_TESTING" -> r.channel() == Channel.CARD && f.cardAttempts2m() >= rule.integer("minAttempts", 5);
            case "FIRST_PAYEE_REMOTE" -> f.newPayee() && r.remoteAccess()
                    && r.amount().compareTo(rule.decimal("minAmount", "5000")) > 0;
            case "NEW_DEVICE_NEW_PAYEE" -> f.newDevice() && f.newPayee();
            case "MERCHANT_CATEGORY_AMOUNT" -> rule.text("category") != null
                    && rule.text("category").equalsIgnoreCase(r.merchantCategory())
                    && r.amount().compareTo(rule.decimal("minAmount", "0")) > 0;
            case "AMOUNT_OVER" -> (rule.text("channel") == null || rule.text("channel").equals(r.channel().name()))
                    && r.amount().compareTo(rule.decimal("minAmount", "0")) > 0;
            case "VELOCITY_1H" -> f.txnCount1h() >= rule.integer("minCount", 10);
            case "PAYEE_FRAUD_REPORTS" -> f.payeeFraudReports() >= rule.integer("minReports", 1);
            default -> false; // Unknown types never fire. The strategy service validates types on save.
        };
    }

    private static Outcome action(String action) {
        return switch (action) {
            case "BLOCK" -> Outcome.BLOCK;
            case "HOLD" -> Outcome.HOLD;
            case "STEP_UP" -> Outcome.STEP_UP;
            default -> Outcome.ALLOW; // MONITOR: counted only.
        };
    }
}