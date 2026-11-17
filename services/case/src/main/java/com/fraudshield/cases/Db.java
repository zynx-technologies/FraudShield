package com.fraudshield.cases;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/** Times are stored as UTC instants. Every time used in SQL is passed in from Java, never taken from the database clock. */
public final class Db {
    private Db() {}

    public static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    public static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}