package com.fraudshield.cases.overview;

import java.time.Instant;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OverviewController {

    private final OverviewService overview;

    public OverviewController(OverviewService overview) {
        this.overview = overview;
    }

    @GetMapping("/api/v1/overview")
    @PreAuthorize("hasAnyRole('TEAM_LEAD', 'ANALYST')")
    public OverviewService.Overview get() {
        return overview.overview(Instant.now());
    }
}