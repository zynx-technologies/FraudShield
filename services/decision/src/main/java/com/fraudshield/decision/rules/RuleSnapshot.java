package com.fraudshield.decision.rules;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything the decision path needs from the strategy service, versioned together so every decision can be
 * replayed with the exact rules and thresholds it used (design section 2, audit).
 */
public record RuleSnapshot(
        String version,
        List<Rule> rules,
        Set<String> sanctionedPayees,
        Set<String> blockedDevices,
        Thresholds thresholds) {

    /** LIVE rules change outcomes. SHADOW rules are evaluated and counted, with no effect. */
    public record Rule(String id, String name, String type, String action, String status, Map<String, String> params) {

        public boolean live() { return "LIVE".equals(status); }

        public BigDecimal decimal(String key, String fallback) {
            return new BigDecimal(params == null ? fallback : params.getOrDefault(key, fallback));
        }

        public int integer(String key, int fallback) {
            return params == null || !params.containsKey(key) ? fallback : Integer.parseInt(params.get(key));
        }

        public String text(String key) {
            return params == null ? null : params.get(key);
        }
    }

    /** Score thresholds (design section 4). Approved by a second person in the strategy service. */
    public record Thresholds(int stepUp, int hold, int block, String version) {}

    /** Used until the strategy service answers, and whenever it cannot be reached at start-up. */
    public static RuleSnapshot builtIn() {
        return new RuleSnapshot("rs-builtin", List.of(), Set.of(), Set.of(), new Thresholds(60, 80, 99, "th-builtin"));
    }
}