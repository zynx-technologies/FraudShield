package com.fraudshield.cases.alerts;

import com.fraudshield.common.security.CurrentUser;
import com.fraudshield.common.web.ApiErrors;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {

    private final AlertService alerts;
    private final CurrentUser currentUser;

    public AlertController(AlertService alerts, CurrentUser currentUser) {
        this.alerts = alerts;
        this.currentUser = currentUser;
    }

    /** Called by the decision service for HOLD, BLOCK, and high-scoring STEP_UP decisions. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('DECISION_ENGINE')")
    public Map<String, String> create(@Valid @RequestBody AlertIntake intake) {
        return Map.of("alertId", alerts.intake(intake));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ANALYST', 'TEAM_LEAD')")
    public AlertService.Queue queue(@RequestParam(defaultValue = "mine") String scope,
                                    @RequestParam(required = false) String channel,
                                    @RequestParam(required = false) Integer minScore,
                                    @RequestParam(required = false) BigDecimal minAmount) {
        if (!scope.equals("mine") && !scope.equals("team")) {
            throw new ApiErrors.BadRequest("scope must be mine or team");
        }
        return alerts.queue(currentUser.id(), scope, channel, minScore, minAmount);
    }

    @PostMapping("/assign-next")
    @PreAuthorize("hasAnyRole('ANALYST', 'TEAM_LEAD')")
    public AlertService.QueueItem assignNext() {
        return alerts.assignNext(currentUser.id(), Instant.now())
                .orElseThrow(() -> new ApiErrors.NotFound("No unassigned alerts are waiting"));
    }
}