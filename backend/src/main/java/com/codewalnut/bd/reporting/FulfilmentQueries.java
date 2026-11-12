package com.codewalnut.bd.reporting;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Realising opportunities (ADR-0016): each staff augmentation requirement on an open or won opportunity, with
 * how many candidates are at each stage, and the candidates themselves. Read-only.
 */
@Component
public class FulfilmentQueries {

    public record Candidate(long id, long requirementId, Long engineerId, String name, String source, String seniority,
                            String availability, Boolean consent, String status, LocalDate statusOn,
                            LocalDate interviewOn, LocalDate startOn, String notes, String proposedBy,
                            Instant createdAt, int version) {}

    public record RequirementStatus(long requirementId, long opportunityId, String opportunityName, long accountId,
                                    String accountName, String stage, String stageOutcome, long ownerId, String ownerName,
                                    String role, String seniority, List<String> skills, int headcount,
                                    LocalDate startDate, LocalDate receivedOn, Map<String, Long> byStatus,
                                    long active, long selected, long started, String deploymentRequest) {}

    static final List<String> ACTIVE = List.of("PROPOSED", "SHARED", "INTERVIEW", "SELECTED", "ONBOARDING");

    private final JdbcClient jdbc;
    private final tools.jackson.databind.json.JsonMapper json;

    FulfilmentQueries(JdbcClient jdbc, tools.jackson.databind.json.JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    private static final String CANDIDATES = """
        SELECT s.id, s.requirement_id, s.engineer_id, COALESCE(e.full_name, s.candidate_name),
            CASE WHEN s.engineer_id IS NULL THEN COALESCE(s.candidate_source, 'External') ELSE 'Bench' END,
            e.seniority, e.availability, e.consent_to_share_profile, s.status, s.status_on, s.interview_on, s.start_on,
            s.notes, u.full_name, s.created_at, s.version
        FROM submission s LEFT JOIN engineer e ON e.id = s.engineer_id LEFT JOIN app_user u ON u.id = s.proposed_by
        """;

    public List<Candidate> forOpportunity(long opportunityId) {
        return candidates(CANDIDATES + """
             JOIN opportunity_requirement r ON r.id = s.requirement_id WHERE r.opportunity_id = :id
            ORDER BY s.requirement_id, FIELD(s.status, 'STARTED', 'ONBOARDING', 'SELECTED', 'INTERVIEW', 'SHARED', 'PROPOSED', 'REJECTED', 'WITHDRAWN'), s.id
            """, Map.of("id", opportunityId));
    }

    public Candidate candidate(long id) {
        return candidates(CANDIDATES + " WHERE s.id = :id", Map.of("id", id)).stream().findFirst()
            .orElseThrow(() -> new com.codewalnut.bd.shared.NotFoundException("Candidate", id));
    }

    /** Candidates still moving (not started, rejected or withdrawn), for Walnut's plan. Owner of the opportunity. */
    public List<Candidate> activeFor(long ownerId) {
        return candidates(CANDIDATES + """
             JOIN opportunity_requirement r ON r.id = s.requirement_id JOIN opportunity o ON o.id = r.opportunity_id
            WHERE o.owner_user_id = :owner AND s.status IN ('PROPOSED', 'SHARED', 'INTERVIEW', 'SELECTED', 'ONBOARDING')
            ORDER BY s.status_on, s.id
            """, Map.of("owner", ownerId));
    }

    private List<Candidate> candidates(String sql, Map<String, ?> params) {
        return jdbc.sql(sql).params(params)
            .query((rs, n) -> new Candidate(rs.getLong(1), rs.getLong(2), rs.getObject(3, Long.class), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getObject(8, Boolean.class), rs.getString(9),
                rs.getObject(10, LocalDate.class), rs.getObject(11, LocalDate.class), rs.getObject(12, LocalDate.class),
                rs.getString(13), rs.getString(14), rs.getTimestamp(15).toInstant(), rs.getInt(16)))
            .list();
    }

    /**
     * Requirements being realised: staff augmentation lines on open or won opportunities that are not yet
     * fully started. {@code all} includes filled ones and lost opportunities too.
     */
    public List<RequirementStatus> requirements(Long ownerId, Long accountId, boolean all) {
        StringBuilder where = new StringBuilder(" WHERE o.opportunity_type = 'STAFF_AUGMENTATION'");
        Map<String, Object> params = new java.util.HashMap<>();
        if (!all) {
            where.append(" AND st.outcome IN ('OPEN', 'WON')");
        }
        if (ownerId != null) {
            where.append(" AND o.owner_user_id = :owner");
            params.put("owner", ownerId);
        }
        if (accountId != null) {
            where.append(" AND o.account_id = :account");
            params.put("account", accountId);
        }
        record Row(long id, long opp, String oppName, long acc, String accName, String stage, String outcome, long owner,
                   String ownerName, String role, String seniority, String skills, int headcount, LocalDate start,
                   LocalDate received, String request) {}
        List<Row> rows = jdbc.sql("""
                SELECT r.id, o.id, o.name, a.id, a.name, st.label, st.outcome, o.owner_user_id, u.full_name, r.role,
                    r.seniority, r.skills, r.headcount, r.start_date, COALESCE(r.received_on, DATE(r.created_at)), d.status
                FROM opportunity_requirement r JOIN opportunity o ON o.id = r.opportunity_id
                JOIN account a ON a.id = o.account_id JOIN opportunity_stage st ON st.code = o.stage_code
                JOIN app_user u ON u.id = o.owner_user_id LEFT JOIN deployment_request d ON d.requirement_id = r.id
                """ + where + " ORDER BY r.start_date, r.id").params(params)
            .query((rs, n) -> new Row(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getLong(8), rs.getString(9), rs.getString(10), rs.getString(11),
                rs.getString(12), rs.getInt(13), rs.getObject(14, LocalDate.class), rs.getObject(15, LocalDate.class),
                rs.getString(16)))
            .list();
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, Map<String, Long>> counts = new java.util.HashMap<>();
        jdbc.sql("SELECT requirement_id, status, COUNT(*) FROM submission WHERE requirement_id IN (:ids) GROUP BY requirement_id, status")
            .param("ids", rows.stream().map(Row::id).toList())
            .query((rs, n) -> counts.computeIfAbsent(rs.getLong(1), k -> new java.util.LinkedHashMap<>()).put(rs.getString(2), rs.getLong(3)))
            .list();
        return rows.stream().map(r -> {
            Map<String, Long> c = counts.getOrDefault(r.id(), Map.of());
            long active = ACTIVE.stream().mapToLong(s -> c.getOrDefault(s, 0L)).sum();
            long selected = c.getOrDefault("SELECTED", 0L) + c.getOrDefault("ONBOARDING", 0L);
            long started = c.getOrDefault("STARTED", 0L);
            return new RequirementStatus(r.id(), r.opp(), r.oppName(), r.acc(), r.accName(), r.stage(), r.outcome(),
                r.owner(), r.ownerName(), r.role(), r.seniority(), List.of(json.readValue(r.skills(), String[].class)),
                r.headcount(), r.start(), r.received(), c, active, selected, started, r.request());
        }).filter(r -> all || r.started() < r.headcount()).toList();
    }
}
