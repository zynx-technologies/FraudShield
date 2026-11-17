package com.fraudshield.cases.overview;

import com.fraudshield.common.client.ServiceCalls;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.core.type.TypeReference;

/** Reads platform health from the decision service, with the caller's own credentials. */
@Component
public class DecisionStatusClient {

    private final ServiceCalls calls;
    private final RestClient decision;

    public DecisionStatusClient(ServiceCalls calls, @Value("${fraudshield.services.decision-url}") String decisionUrl) {
        this.calls = calls;
        this.decision = calls.client(decisionUrl, Duration.ofSeconds(2));
    }

    public Map<String, Object> status() {
        try {
            Map<String, Object> status = decision.get().uri("/api/v1/decisions/status").headers(calls.asCaller())
                    .retrieve().body(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});
            return status == null ? unavailable() : status;
        } catch (RuntimeException e) {
            return unavailable();
        }
    }

    private static Map<String, Object> unavailable() {
        return Map.of("decisionApi", "Unavailable");
    }
}