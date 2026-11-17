package com.fraudshield.copilot;

import tools.jackson.databind.JsonNode;

/** Where Copilot reads a case from. */
public interface CaseSource {
    JsonNode get(String caseId);
}
