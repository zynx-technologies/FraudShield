package com.fraudshield.strategy.rules;

import com.fraudshield.common.web.ApiErrors;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The rule types the decision service can evaluate in memory, and their parameters. Keep this list in step with
 * services/decision/.../rules/RuleEvaluator.java.
 */
public final class RuleTypes {

    public record Param(String name, String label, String kind) {}

    public record Type(String type, String description, List<Param> params) {}

    public static final List<Type> TYPES = List.of(
            new Type("SANCTIONS_LIST", "Payee on the sanctions list. Absolute: checked before any model, from the blocklist", List.of()),
            new Type("CARD_TESTING", "Card attempts on one account within 2 minutes",
                    List.of(new Param("minAttempts", "Minimum attempts", "integer"))),
            new Type("FIRST_PAYEE_REMOTE", "First payment to a payee, over an amount, during a remote-access session",
                    List.of(new Param("minAmount", "Minimum amount (GBP)", "amount"))),
            new Type("NEW_DEVICE_NEW_PAYEE", "New device and new payee together", List.of()),
            new Type("MERCHANT_CATEGORY_AMOUNT", "Card payment to a merchant category over an amount",
                    List.of(new Param("category", "Merchant category", "text"), new Param("minAmount", "Minimum amount (GBP)", "amount"))),
            new Type("AMOUNT_OVER", "Any payment over an amount, optionally on one channel",
                    List.of(new Param("minAmount", "Minimum amount (GBP)", "amount"), new Param("channel", "Channel (CARD, INSTANT, TRANSFER, or blank)", "channel"))),
            new Type("VELOCITY_1H", "Payments from one account in the last hour",
                    List.of(new Param("minCount", "Minimum payments", "integer"))),
            new Type("PAYEE_FRAUD_REPORTS", "Payee with confirmed fraud reports",
                    List.of(new Param("minReports", "Minimum reports", "integer"))));

    public static final Set<String> ACTIONS = Set.of("BLOCK", "HOLD", "STEP_UP", "MONITOR");
    public static final Set<String> MODES = Set.of("LIVE", "SHADOW", "RETIRED");

    private RuleTypes() {}

    /** Rejects unknown types and parameters, and values of the wrong kind. Returns the cleaned parameters. */
    public static Map<String, String> validate(String type, Map<String, String> params) {
        Type t = TYPES.stream().filter(x -> x.type().equals(type)).findFirst()
                .orElseThrow(() -> new ApiErrors.BadRequest("Unknown rule type " + type));
        Map<String, String> clean = new LinkedHashMap<>();
        Map<String, String> given = params == null ? Map.of() : params;
        for (String key : given.keySet()) {
            if (t.params().stream().noneMatch(p -> p.name().equals(key))) {
                throw new ApiErrors.BadRequest("Rule type " + type + " has no parameter " + key);
            }
        }
        for (Param p : t.params()) {
            String v = given.get(p.name());
            if (v == null || v.isBlank()) {
                if (p.kind().equals("channel")) continue;
                throw new ApiErrors.BadRequest(p.label() + " is required");
            }
            v = v.strip();
            try {
                switch (p.kind()) {
                    case "integer" -> { if (Integer.parseInt(v) < 1) throw new NumberFormatException(); }
                    case "amount" -> { if (new BigDecimal(v).signum() < 0) throw new NumberFormatException(); }
                    case "channel" -> { if (!Set.of("CARD", "INSTANT", "TRANSFER").contains(v)) throw new NumberFormatException(); }
                    default -> { if (v.length() > 40) throw new NumberFormatException(); }
                }
            } catch (NumberFormatException e) {
                throw new ApiErrors.BadRequest(p.label() + " has an invalid value");
            }
            clean.put(p.name(), v);
        }
        return clean;
    }
}