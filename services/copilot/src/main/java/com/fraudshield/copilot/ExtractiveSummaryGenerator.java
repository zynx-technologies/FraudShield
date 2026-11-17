package com.fraudshield.copilot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Builds the summary from the evidence with fixed sentence templates and no language model. It is the default,
 * and the fallback whenever the model is switched off.
 */
@Component
@ConditionalOnProperty(name = "fraudshield.copilot.provider", havingValue = "extractive", matchIfMissing = true)
public class ExtractiveSummaryGenerator implements SummaryGenerator {

    @Override
    public List<Statement> generate(Evidence e) {
        List<Statement> out = new ArrayList<>();
        Evidence.Source tx = first(e, "TX");
        Evidence.Source payee = first(e, "PH");
        Evidence.Source device = first(e, "DL");
        Evidence.Source reasons = first(e, "MR");

        if (tx != null) {
            Map<String, String> f = tx.facts();
            StringBuilder s = new StringBuilder("The customer made ");
            boolean firstPayment = payee != null && "true".equals(payee.facts().get("firstPayment"));
            s.append(firstPayment ? "a first payment of " : "a payment of ").append(f.get("amountText"));
            if (payee != null) s.append(firstPayment ? " to a new payee" : " to an existing payee");
            if (f.get("ratio") != null && f.get("median") != null) {
                s.append(", about ").append(f.get("ratio")).append(" times their usual median payment of ").append(f.get("median"));
            }
            s.append('.');
            out.add(new Statement(s.toString(), payee != null ? List.of(tx.id(), payee.id()) : List.of(tx.id())));
        }
        if (device != null) {
            boolean remote = "true".equals(device.facts().get("remoteAccess"));
            boolean newToday = "true".equals(device.facts().get("firstSeenToday"));
            if (remote && newToday) out.add(new Statement("A remote-access tool was active, and the device was first seen today.", List.of(device.id())));
            else if (remote) out.add(new Statement("A remote-access tool was active during the session.", List.of(device.id())));
            else if (newToday) out.add(new Statement("The device was first seen today.", List.of(device.id())));
        }
        if (payee != null && payee.facts().get("otherSenders") != null && !"0".equals(payee.facts().get("otherSenders"))) {
            String n = payee.facts().get("otherSenders");
            out.add(new Statement("The payee received funds from " + words(n) + " other account" + ("1".equals(n) ? "" : "s") + " this week.",
                    List.of(payee.id())));
        }
        if (reasons != null) {
            out.add(new Statement("The payment was " + reasons.facts().get("decision").toLowerCase() + " with a score of "
                    + reasons.facts().get("score") + ". The strongest model reasons were " + reasons.facts().get("topReasons") + ".",
                    List.of(reasons.id())));
        }
        return out;
    }

    private static String words(String n) {
        return switch (n) {
            case "1" -> "one";
            case "2" -> "two";
            case "3" -> "three";
            default -> n;
        };
    }

    private static Evidence.Source first(Evidence e, String prefix) {
        return e.sources().stream().filter(s -> s.id().startsWith(prefix)).findFirst().orElse(null);
    }

    static String round(BigDecimal v) {
        return v.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    @Override
    public String name() { return "extractive"; }
}