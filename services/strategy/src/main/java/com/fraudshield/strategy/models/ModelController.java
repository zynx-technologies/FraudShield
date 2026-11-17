package com.fraudshield.strategy.models;

import com.fraudshield.common.security.CurrentUser;
import java.time.Instant;
import java.util.List;
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
@RequestMapping("/api/v1/models/{model}")
public class ModelController {

    public record Decision(String reason) {}

    private static final String READERS = "hasAnyRole('ML_ENGINEER', 'MODEL_RISK_OWNER', 'TEAM_LEAD', 'STRATEGIST')";

    private final ModelService models;
    private final CurrentUser currentUser;

    public ModelController(ModelService models, CurrentUser currentUser) {
        this.models = models;
        this.currentUser = currentUser;
    }

    @GetMapping
    @PreAuthorize(READERS)
    public ModelService.Monitoring monitoring(@PathVariable String model) {
        return models.monitoring(model);
    }

    @GetMapping("/releases")
    @PreAuthorize(READERS)
    public List<ModelService.Release> releases(@PathVariable String model) {
        return models.releases(model);
    }

    @GetMapping("/releases/{version}")
    @PreAuthorize(READERS)
    public ModelService.Release release(@PathVariable String model, @PathVariable int version) {
        return models.release(model, version);
    }

    /** Raised by ML engineering (or the validation job) with the gate evidence from MLflow. */
    @PostMapping("/releases")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ML_ENGINEER')")
    public ModelService.Release request(@PathVariable String model, @RequestBody ModelService.ReleaseRequest request) {
        return models.request(model, request, currentUser.id(), Instant.now());
    }

    @PostMapping("/releases/{version}/approve")
    @PreAuthorize("hasRole('MODEL_RISK_OWNER')")
    public ModelService.Release approve(@PathVariable String model, @PathVariable int version, @RequestBody Decision d) {
        return models.decide(model, version, true, d.reason(), currentUser.id(), Instant.now());
    }

    @PostMapping("/releases/{version}/reject")
    @PreAuthorize("hasRole('MODEL_RISK_OWNER')")
    public ModelService.Release reject(@PathVariable String model, @PathVariable int version, @RequestBody Decision d) {
        return models.decide(model, version, false, d.reason(), currentUser.id(), Instant.now());
    }

    /** Pushed by the Lakehouse Monitoring job. */
    @PostMapping("/monitoring")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasRole('ML_ENGINEER')")
    public void monitoring(@PathVariable String model, @RequestBody ModelService.MonitoringUpdate update) {
        models.updateMonitoring(model, update, Instant.now());
    }
}