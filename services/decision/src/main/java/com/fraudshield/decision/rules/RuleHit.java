package com.fraudshield.decision.rules;

import com.fraudshield.decision.engine.Outcome;

public record RuleHit(String ruleId, String name, Outcome action, boolean live, boolean absolute) {}