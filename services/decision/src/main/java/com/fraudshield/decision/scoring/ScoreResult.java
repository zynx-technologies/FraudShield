package com.fraudshield.decision.scoring;

import java.util.List;

/** score is 0 to 99. contributions are ordered strongest first. */
public record ScoreResult(int score, List<Contribution> contributions, String modelVersion) {}