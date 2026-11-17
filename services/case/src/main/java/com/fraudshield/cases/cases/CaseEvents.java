package com.fraudshield.cases.cases;

import static com.fraudshield.cases.Db.ts;

import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Writes the append-only case history. Every action is audited (screen 3). */
@Component
public class CaseEvents {

    private final JdbcClient jdbc;

    public CaseEvents(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String caseId, Instant at, String actor, String type, String detail) {
        jdbc.sql("INSERT INTO case_event (case_id, at, actor, event_type, detail) VALUES (?, ?, ?, ?, ?)")
                .params(caseId, ts(at), actor, type, detail.length() > 1000 ? detail.substring(0, 1000) : detail)
                .update();
    }
}