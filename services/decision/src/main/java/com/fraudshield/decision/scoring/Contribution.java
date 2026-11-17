package com.fraudshield.decision.scoring;

/** One driver of the score: a reason code, its plain-language label, and its weight in the score. */
public record Contribution(String code, String label, double value) {}