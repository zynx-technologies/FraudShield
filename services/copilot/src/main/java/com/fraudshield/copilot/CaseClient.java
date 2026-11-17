package com.fraudshield.copilot;

import com.fraudshield.common.client.ServiceCalls;
import com.fraudshield.common.web.ApiErrors;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * Reads one case from the case service with the analyst's own token, so Copilot can never see more than the
 * analyst can. Copilot has no client that can change a case.
 */
@Component
public class CaseClient implements CaseSource {

    private final ServiceCalls calls;
    private final RestClient cases;

    public CaseClient(ServiceCalls calls, @Value("${fraudshield.services.case-url}") String caseUrl) {
        this.calls = calls;
        this.cases = calls.client(caseUrl, Duration.ofSeconds(5));
    }

    @Override
    public JsonNode get(String caseId) {
        try {
            return cases.get().uri("/api/v1/cases/{id}", caseId).headers(calls.asCaller()).retrieve().body(JsonNode.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new ApiErrors.NotFound("Case not found");
        } catch (HttpClientErrorException.Forbidden e) {
            throw new ApiErrors.Forbidden("You cannot read this case");
        } catch (RestClientException e) {
            throw new ApiErrors.Unavailable("The case service is unavailable");
        }
    }
}