package com.fraudshield.strategy.rules;

import static com.fraudshield.strategy.Db.ts;

import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The ruleset version the decision service reports with each decision, for example rs-204. */
@Component
public class RulesetVersions {

    private final JdbcClient jdbc;

    public RulesetVersions(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public String current() {
        Integer id = jdbc.sql("SELECT MAX(id) FROM ruleset_version").query(Integer.class).optional().orElse(null);
        return id == null ? "rs-0" : "rs-" + id;
    }

    public void bump(String reason, Instant at) {
        jdbc.sql("INSERT INTO ruleset_version (created_at, reason) VALUES (?, ?)").params(ts(at), reason).update();
    }
}