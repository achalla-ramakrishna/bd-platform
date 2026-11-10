package com.codewalnut.bd.reporting;

import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Finds leader interviews by the leader's name, for the BD assistant (ADR-0013). */
@Component
public class InterviewQueries {

    public record Match(long id, String leaderName, String leaderTitle, String company, String topic, String format,
                        String status, LocalDate scheduledOn, int version) {}

    private final JdbcClient jdbc;

    InterviewQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Not yet published or declined first, then the newest. */
    public List<Match> findByLeader(String name, int limit) {
        if (name == null || name.isBlank()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT id, leader_name, leader_title, company, topic, format, status, scheduled_on, version
                FROM interview WHERE leader_name LIKE :like
                ORDER BY status IN ('PUBLISHED', 'DECLINED'), created_at DESC
                LIMIT :limit
                """).param("like", "%" + name.trim() + "%").param("limit", limit)
            .query((rs, n) -> new Match(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getObject(8, LocalDate.class), rs.getInt(9)))
            .list();
    }
}
