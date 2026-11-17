package com.fraudshield.strategy.thresholds;

import com.fraudshield.common.security.CurrentUser;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/thresholds")
@PreAuthorize("hasAnyRole('STRATEGIST', 'RISK_APPROVER', 'TEAM_LEAD')")
public class ThresholdController {

    public record Values(int stepUp, int hold, int block) {}

    public record Decision(String reason) {}

    private final ThresholdService thresholds;
    private final CurrentUser currentUser;

    public ThresholdController(ThresholdService thresholds, CurrentUser currentUser) {
        this.thresholds = thresholds;
        this.currentUser = currentUser;
    }

    @GetMapping
    public Map<String, Object> get() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("active", thresholds.active());
        result.put("pending", thresholds.pending().orElse(null));
        result.put("history", thresholds.policies());
        return result;
    }

    @PostMapping("/simulate")
    public ThresholdService.Simulation simulate(@RequestBody Values v) {
        return thresholds.simulate(v.stepUp(), v.hold(), v.block());
    }

    @PostMapping("/proposals")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('STRATEGIST')")
    public ThresholdService.Policy propose(@RequestBody ThresholdService.Proposal proposal) {
        return thresholds.propose(proposal, currentUser.id(), Instant.now());
    }

    @PostMapping("/proposals/{id}/approve")
    @PreAuthorize("hasRole('RISK_APPROVER')")
    public ThresholdService.Policy approve(@PathVariable long id, @RequestBody Decision d) {
        return thresholds.decide(id, true, d.reason(), currentUser.id(), Instant.now());
    }

    @PostMapping("/proposals/{id}/reject")
    @PreAuthorize("hasRole('RISK_APPROVER')")
    public ThresholdService.Policy reject(@PathVariable long id, @RequestBody Decision d) {
        return thresholds.decide(id, false, d.reason(), currentUser.id(), Instant.now());
    }
}