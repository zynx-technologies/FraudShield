package com.fraudshield.decision.api;

import com.fraudshield.common.web.ApiErrors;
import com.fraudshield.decision.engine.DecisionEngine;
import com.fraudshield.decision.engine.DecisionStats;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/decisions")
public class DecisionController {

    private final DecisionEngine engine;
    private final DecisionStats stats;

    public DecisionController(DecisionEngine engine, DecisionStats stats) {
        this.engine = engine;
        this.stats = stats;
    }

    /** FR-1. Payment channels call this through API Management. Idempotency-Key makes retries safe. */
    @PostMapping
    @PreAuthorize("hasRole('CHANNEL')")
    public DecisionResponse decide(@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                                   @Valid @RequestBody DecisionRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
            throw new ApiErrors.BadRequest("An Idempotency-Key header of up to 100 characters is required");
        }
        return engine.decide(idempotencyKey, request);
    }

    /** Platform health for the operations overview. */
    @GetMapping("/status")
    @PreAuthorize("hasAnyRole('ANALYST', 'TEAM_LEAD', 'ML_ENGINEER', 'DECISION_ENGINE')")
    public Map<String, Object> status() {
        return stats.status();
    }
}