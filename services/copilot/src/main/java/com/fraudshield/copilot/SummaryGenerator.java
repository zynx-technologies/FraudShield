package com.fraudshield.copilot;

import java.util.List;

public interface SummaryGenerator {
    List<Statement> generate(Evidence evidence);

    String name();
}