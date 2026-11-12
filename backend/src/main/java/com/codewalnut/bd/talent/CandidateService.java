package com.codewalnut.bd.talent;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.codewalnut.bd.shared.AuditLog;
import com.codewalnut.bd.shared.ConflictException;
import com.codewalnut.bd.shared.CurrentUser;
import com.codewalnut.bd.shared.NotFoundException;
import com.codewalnut.bd.shared.RuleViolationException;

/**
 * Candidates proposed against a requirement, from the bench or from outside, and their path to starting at
 * the client (ADR-0016): Proposed, Profile shared, Client interview, Selected, Onboarding, Started; or
 * Rejected or Withdrawn. Profiles are shared only with the engineer's consent (BR-BD-10).
 */
@Service
class CandidateService {

    record Propose(Long engineerId, String candidateName, String candidateSource, String notes) {}

    /** {@code on}: the interview date for INTERVIEW, the start date for ONBOARDING and STARTED, else the day it happened. */
    record Move(String status, LocalDate on, String notes, Integer version) {}

    static final List<String> FLOW = List.of("PROPOSED", "SHARED", "INTERVIEW", "SELECTED", "ONBOARDING", "STARTED");
    static final List<String> CLOSED = List.of("REJECTED", "WITHDRAWN");

    private final JdbcClient jdbc;
    private final AuditLog audit;

    CandidateService(JdbcClient jdbc, AuditLog audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional
    long propose(long requirementId, Propose in, CurrentUser user) {
        Integer exists = jdbc.sql("SELECT COUNT(*) FROM opportunity_requirement WHERE id = :id").param("id", requirementId)
            .query(Integer.class).single();
        if (exists == 0) {
            throw new NotFoundException("Requirement", requirementId);
        }
        List<String> problems = new ArrayList<>();
        String name = in == null || in.candidateName() == null || in.candidateName().isBlank() ? null : in.candidateName().strip();
        Long engineer = in == null ? null : in.engineerId();
        if (engineer == null && name == null) {
            problems.add("Choose an engineer from the bench, or type the external candidate's name.");
        }
        if (engineer != null && name != null) {
            problems.add("Choose a bench engineer or an external candidate, not both.");
        }
        if (engineer != null && jdbc.sql("SELECT COUNT(*) FROM engineer WHERE id = :id").param("id", engineer)
                .query(Integer.class).single() == 0) {
            problems.add("Choose an engineer from the list.");
        }
        if (name != null && name.length() > 200) {
            problems.add("The name can be at most 200 characters.");
        }
        if (name != null && jdbc.sql("""
                SELECT COUNT(*) FROM submission WHERE requirement_id = :r AND LOWER(candidate_name) = LOWER(:n)
                """).param("r", requirementId).param("n", name).query(Integer.class).single() > 0) {
            problems.add(name + " is already proposed for this requirement.");
        }
        if (!problems.isEmpty()) {
            throw new RuleViolationException(problems);
        }
        var key = new GeneratedKeyHolder();
        try {
            jdbc.sql("""
                    INSERT INTO submission (requirement_id, engineer_id, candidate_name, candidate_source, status, status_on,
                        notes, proposed_by)
                    VALUES (:r, :e, :n, :src, 'PROPOSED', CURRENT_DATE, :notes, :user)
                    """).param("r", requirementId).param("e", engineer).param("n", name)
                .param("src", engineer == null ? trim(in.candidateSource(), 200) : null).param("notes", trim(in.notes(), 2000))
                .param("user", user.id()).update(key);
        } catch (DuplicateKeyException e) {
            throw new RuleViolationException(List.of("That engineer is already proposed for this requirement."));
        }
        long id = key.getKey().longValue();
        audit.record("submission", id, AuditLog.Action.CREATE, Map.of("requirement", requirementId), user.id());
        return id;
    }

    @Transactional
    void move(long id, Move in, CurrentUser user) {
        record Current(long requirementId, Long engineerId, String status, int version, Boolean consent) {}
        Current c = jdbc.sql("""
                SELECT s.requirement_id, s.engineer_id, s.status, s.version, e.consent_to_share_profile
                FROM submission s LEFT JOIN engineer e ON e.id = s.engineer_id WHERE s.id = :id FOR UPDATE
                """).param("id", id)
            .query((rs, n) -> new Current(rs.getLong(1), rs.getObject(2, Long.class), rs.getString(3), rs.getInt(4),
                rs.getObject(5, Boolean.class)))
            .optional().orElseThrow(() -> new NotFoundException("Candidate", id));
        if (in == null || in.version() == null || in.version() != c.version()) {
            throw new ConflictException("Someone else moved this candidate. Reload and try again.");
        }
        String to = in.status() == null ? "" : in.status().strip().toUpperCase(Locale.ROOT);
        List<String> problems = new ArrayList<>();
        if (!allowed(c.status(), to)) {
            problems.add("A candidate who is " + label(c.status()) + " cannot move to " + (to.isEmpty() ? "that" : label(to)) + ".");
        }
        int at = FLOW.indexOf(to);
        if (c.engineerId() != null && at >= FLOW.indexOf("SHARED") && !Boolean.TRUE.equals(c.consent())) {
            problems.add("This engineer has not agreed to share their profile, so it cannot go to the client (BR-BD-10).");
        }
        LocalDate on = in.on() == null ? LocalDate.now() : in.on();
        if (("INTERVIEW".equals(to) || "ONBOARDING".equals(to)) && in.on() == null) {
            problems.add("INTERVIEW".equals(to) ? "Give the interview date." : "Give the start date.");
        }
        if ("STARTED".equals(to) && on.isAfter(LocalDate.now())) {
            problems.add("A start date for Started cannot be in the future. Use Onboarding until then.");
        }
        if (!problems.isEmpty()) {
            throw new RuleViolationException(problems);
        }
        jdbc.sql("""
                UPDATE submission SET status = :to, status_on = :today,
                    interview_on = CASE WHEN :to = 'INTERVIEW' THEN :on ELSE interview_on END,
                    start_on = CASE WHEN :to IN ('ONBOARDING', 'STARTED') THEN :on ELSE start_on END,
                    notes = COALESCE(:notes, notes), version = version + 1
                WHERE id = :id
                """).param("to", to).param("today", "INTERVIEW".equals(to) || "ONBOARDING".equals(to) ? LocalDate.now() : on)
            .param("on", on).param("notes", trim(in.notes(), 2000)).param("id", id).update();
        // The requirement is filled once enough candidates have started: the request to Delivery is done.
        jdbc.sql("""
                UPDATE deployment_request d JOIN opportunity_requirement r ON r.id = d.requirement_id
                SET d.status = CASE WHEN (SELECT COUNT(*) FROM submission s WHERE s.requirement_id = r.id AND s.status = 'STARTED')
                    >= r.headcount THEN 'FILLED' ELSE 'OPEN' END
                WHERE d.requirement_id = :r AND d.status <> 'CANCELLED'
                """).param("r", c.requirementId()).update();
        audit.record("submission", id, AuditLog.Action.UPDATE, Map.of("from", c.status(), "to", to), user.id());
    }

    /** Forward to any later stage (skipping is fine), one step back to correct, closed from any open stage, and reopened. */
    static boolean allowed(String from, String to) {
        if (from.equals(to)) {
            return false;
        }
        if (CLOSED.contains(from)) {
            return "PROPOSED".equals(to);
        }
        if ("STARTED".equals(from)) {
            return "ONBOARDING".equals(to);
        }
        if (CLOSED.contains(to)) {
            return true;
        }
        int a = FLOW.indexOf(from);
        int b = FLOW.indexOf(to);
        return b >= 0 && (b > a || b == a - 1);
    }

    static String label(String status) {
        return switch (status) {
            case "SHARED" -> "profile shared";
            case "INTERVIEW" -> "in client interview";
            default -> status.toLowerCase(Locale.ROOT);
        };
    }

    private static String trim(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.strip();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
