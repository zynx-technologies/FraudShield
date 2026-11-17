package com.fraudshield.copilot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Turns the case service's view into cited sources: transaction, payee history, device log, and model reasons. */
@Component
public class EvidenceBuilder {

    public Evidence build(JsonNode c) {
        JsonNode t = c.path("transaction");
        JsonNode f = t.path("features");
        List<Evidence.Source> sources = new ArrayList<>();

        String txId = Evidence.untrusted(t.path("transactionId").asString());
        Map<String, String> tx = new LinkedHashMap<>();
        tx.put("amountText", money(t.path("amount").decimalValue(), t.path("currency").asString()));
        tx.put("channel", Evidence.untrusted(t.path("channel").asString()));
        if (!t.path("usualMedian").isMissingNode() && !t.path("usualMedian").isNull()) {
            tx.put("median", money(t.path("usualMedian").decimalValue().setScale(0, RoundingMode.HALF_UP), t.path("currency").asString()));
        }
        if (!t.path("amountToMedian").isMissingNode() && !t.path("amountToMedian").isNull() && t.path("amountToMedian").asDouble() >= 2) {
            tx.put("ratio", BigDecimal.valueOf(t.path("amountToMedian").asDouble()).setScale(0, RoundingMode.HALF_UP).toPlainString());
        }
        sources.add(new Evidence.Source(txId, "transaction " + txId, tx));

        String payee = t.path("payee").asString(null);
        if (payee != null) {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("firstPayment", String.valueOf(t.path("payeeFirstPayment").asBoolean()));
            p.put("otherSenders", String.valueOf(f.path("payeeIncomingSenders7d").asInt(0)));
            String id = "PH-" + Evidence.untrusted(payee);
            sources.add(new Evidence.Source(id, "payee history " + Evidence.untrusted(payee), p));
        }

        String device = t.path("deviceId").asString(null);
        if (device != null) {
            Map<String, String> d = new LinkedHashMap<>();
            d.put("remoteAccess", String.valueOf(f.path("remoteAccess").asBoolean()));
            d.put("firstSeenToday", String.valueOf(t.path("deviceFirstSeenToday").asBoolean()));
            String id = "DL-" + Evidence.untrusted(device);
            sources.add(new Evidence.Source(id, "device log " + Evidence.untrusted(device), d));
        }

        List<String> labels = new ArrayList<>();
        c.path("reasons").forEach(r -> labels.add(Evidence.untrusted(r.path("label").asString()).toLowerCase(Locale.UK)));
        if (!labels.isEmpty()) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("score", String.valueOf(c.path("score").asInt()));
            m.put("decision", Evidence.untrusted(t.path("decision").asString("Held")));
            m.put("topReasons", String.join(", ", labels.subList(0, Math.min(3, labels.size()))));
            String model = Evidence.untrusted(t.path("modelVersion").asString("model"));
            m.put("modelVersion", model);
            sources.add(new Evidence.Source("MR-" + c.path("id").asString(), "model reasons, " + model, m));
        }
        return new Evidence(c.path("id").asString(), sources);
    }

    static String money(BigDecimal amount, String currency) {
        NumberFormat format = NumberFormat.getCurrencyInstance(Locale.UK);
        try {
            format.setCurrency(java.util.Currency.getInstance(currency));
        } catch (RuntimeException ignored) {
            // Keep pounds if the currency code is unknown.
        }
        format.setMaximumFractionDigits(amount.scale() > 0 ? 2 : 0);
        format.setMinimumFractionDigits(amount.scale() > 0 ? 2 : 0);
        return format.format(amount);
    }
}