package com.fraudshield.cases.overview;

import static com.fraudshield.cases.Db.ts;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Hourly decision counts from the decision service, for volume and latency on the overview. */
@RestController
public class DecisionCounts {

    public record HourlyCount(@NotNull Instant hour, @NotBlank String channel, @NotBlank String outcome,
                              @PositiveOrZero long decisions, @PositiveOrZero long latencyMsTotal, @PositiveOrZero long latencyMsMax) {}

    private final JdbcClient jdbc;

    public DecisionCounts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @PostMapping("/api/v1/decision-events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasRole('DECISION_ENGINE')")
    @Transactional
    public void add(@Valid @RequestBody List<@Valid HourlyCount> counts) {
        counts.forEach(this::add);
    }

    public void add(HourlyCount c) {
        Instant hour = c.hour().truncatedTo(ChronoUnit.HOURS);
        for (int attempt = 0; attempt < 3; attempt++) {
            int updated = jdbc.sql("""
                    UPDATE decision_hourly SET decisions = decisions + ?, latency_ms_total = latency_ms_total + ?,
                      latency_ms_max = CASE WHEN latency_ms_max < ? THEN ? ELSE latency_ms_max END
                    WHERE hour_start = ? AND channel = ? AND outcome = ?""")
                    .params(c.decisions(), c.latencyMsTotal(), c.latencyMsMax(), c.latencyMsMax(), ts(hour), c.channel(), c.outcome())
                    .update();
            if (updated > 0) return;
            try {
                jdbc.sql("INSERT INTO decision_hourly (hour_start, channel, outcome, decisions, latency_ms_total, latency_ms_max) VALUES (?, ?, ?, ?, ?, ?)")
                        .params(ts(hour), c.channel(), c.outcome(), c.decisions(), c.latencyMsTotal(), c.latencyMsMax()).update();
                return;
            } catch (DuplicateKeyException e) {
                // Another pod inserted the row first. Update it on the next attempt.
            }
        }
    }
}