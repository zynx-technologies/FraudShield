package com.fraudshield.strategy.rules;

import com.fraudshield.common.security.CurrentUser;
import com.fraudshield.common.web.ApiErrors;
import java.time.Instant;
import java.util.List;
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
@RequestMapping("/api/v1/rules")
public class RuleController {

    public record Decision(String reason) {}

    public record Hits(Map<String, Long> hits) {}

    private static final String READERS = "hasAnyRole('STRATEGIST', 'RISK_APPROVER', 'TEAM_LEAD', 'ANALYST')";

    private final RuleService rules;
    private final CurrentUser currentUser;

    public RuleController(RuleService rules, CurrentUser currentUser) {
        this.rules = rules;
        this.currentUser = currentUser;
    }

    @GetMapping
    @PreAuthorize(READERS)
    public List<RuleService.RuleRow> list() {
        return rules.list(Instant.now());
    }

    @GetMapping("/types")
    @PreAuthorize(READERS)
    public List<RuleTypes.Type> types() {
        return RuleTypes.TYPES;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('STRATEGIST')")
    public RuleService.RuleRow create(@RequestBody RuleService.NewRule rule) {
        return rules.create(rule, currentUser.id(), Instant.now());
    }

    @PostMapping("/{id}/changes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('STRATEGIST')")
    public void propose(@PathVariable String id, @RequestBody RuleService.RuleChange change) {
        rules.propose(id, change, currentUser.id(), Instant.now());
    }

    @PostMapping("/{id}/versions/{version}/approve")
    @PreAuthorize("hasRole('RISK_APPROVER')")
    public void approve(@PathVariable String id, @PathVariable int version, @RequestBody Decision decision) {
        rules.decide(id, version, true, decision.reason(), currentUser.id(), Instant.now());
    }

    @PostMapping("/{id}/versions/{version}/reject")
    @PreAuthorize("hasRole('RISK_APPROVER')")
    public void reject(@PathVariable String id, @PathVariable int version, @RequestBody Decision decision) {
        rules.decide(id, version, false, decision.reason(), currentUser.id(), Instant.now());
    }

    @GetMapping("/history")
    @PreAuthorize(READERS)
    public List<RuleService.Change> history() {
        return rules.history(null);
    }

    @GetMapping("/{id}/history")
    @PreAuthorize(READERS)
    public List<RuleService.Change> history(@PathVariable String id) {
        return rules.history(id);
    }

    /** Backtests run on Databricks SQL against the transaction history (design section 5). */
    @PostMapping("/{id}/backtest")
    @PreAuthorize("hasRole('STRATEGIST')")
    public void backtest(@PathVariable String id) {
        throw new ApiErrors.NotImplemented("Backtests need the Databricks SQL connection, which is not set up in this environment yet");
    }

    @GetMapping("/live")
    @PreAuthorize("hasRole('DECISION_ENGINE')")
    public RuleService.Snapshot live() {
        return rules.snapshot();
    }

    @PostMapping("/hits")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasRole('DECISION_ENGINE')")
    public void hits(@RequestBody Hits hits) {
        rules.addHits(hits.hits() == null ? Map.of() : hits.hits(), Instant.now());
    }
}