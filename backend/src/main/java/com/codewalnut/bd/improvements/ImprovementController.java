package com.codewalnut.bd.improvements;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.codewalnut.bd.admin.CurrentUserProvider;
import com.codewalnut.bd.shared.AuditLog;
import com.codewalnut.bd.shared.ConflictException;
import com.codewalnut.bd.shared.CurrentUser;
import com.codewalnut.bd.shared.NotFoundException;
import com.codewalnut.bd.shared.Role;
import com.codewalnut.bd.shared.RuleViolationException;

/**
 * Improvement areas (backlog item 5): the tool is the source of improvement knowledge. Anyone signed in
 * suggests; leadership and admins approve or decline, rank the approved ones and move them on. The
 * approved list, in rank order, is the next work to build.
 */
@RestController
@RequestMapping("/api/v1/improvements")
class ImprovementController {

    static final Set<String> STATUSES = Set.of("SUGGESTED", "APPROVED", "IN_PROGRESS", "DONE", "DECLINED");

    /** Which moves are allowed: anything can be declined or sent back to suggested, otherwise one step on. */
    static final Map<String, Set<String>> MOVES = Map.of(
        "SUGGESTED", Set.of("APPROVED", "DECLINED"),
        "APPROVED", Set.of("IN_PROGRESS", "DONE", "SUGGESTED", "DECLINED"),
        "IN_PROGRESS", Set.of("DONE", "APPROVED", "DECLINED"),
        "DONE", Set.of("IN_PROGRESS"),
        "DECLINED", Set.of("SUGGESTED"));

    record Improvement(long id, String title, String details, String status, Integer rank, String suggestedBy,
                       long suggestedById, Instant createdAt, String decidedBy, Instant decidedAt, String decisionNote,
                       String deliveredRef, Instant doneAt, int version, boolean canEdit) {}

    record Board(List<Improvement> items, boolean canApprove) {}

    record Input(String title, String details, Integer version) {}

    /** Change status (with an optional note and, for DONE, a link to what delivered it), or rank. */
    record Decision(String status, String note, String deliveredRef, Integer version) {}

    record Move(String direction, Integer version) {}

    private static final String SELECT = """
        SELECT i.id, i.title, i.details, i.status, i.rank_order, s.full_name, i.suggested_by, i.created_at,
            d.full_name, i.decided_at, i.decision_note, i.delivered_ref, i.done_at, i.version
        FROM improvement i
        JOIN app_user s ON s.id = i.suggested_by
        LEFT JOIN app_user d ON d.id = i.decided_by
        """;

    private final JdbcClient jdbc;
    private final CurrentUserProvider currentUser;
    private final AuditLog audit;

    ImprovementController(JdbcClient jdbc, CurrentUserProvider currentUser, AuditLog audit) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    /** All items: approved first in rank order (the build queue), then in progress, suggested, done, declined. */
    @GetMapping
    Board list(@RequestParam(required = false) String status) {
        CurrentUser user = currentUser.get();
        String where = status == null || status.isBlank() ? "" : " WHERE i.status = :status";
        var spec = jdbc.sql(SELECT + where + """
                 ORDER BY FIELD(i.status, 'IN_PROGRESS', 'APPROVED', 'SUGGESTED', 'DONE', 'DECLINED'),
                    i.rank_order IS NULL, i.rank_order, COALESCE(i.done_at, i.created_at) DESC, i.id DESC
                LIMIT 1000
                """);
        if (!where.isEmpty()) {
            spec = spec.param("status", status.trim().toUpperCase(Locale.ROOT));
        }
        return new Board(spec.query((rs, n) -> map(rs, user)).list(), user.hasAnyRole(Role.IMPROVEMENT_APPROVERS));
    }

    @GetMapping("/{id}")
    Improvement get(@PathVariable long id) {
        return get(id, currentUser.get());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Improvement suggest(@RequestBody Input in) {
        CurrentUser user = currentUser.get();
        check(in);
        var key = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO improvement (title, details, status, suggested_by) VALUES (:t, :d, 'SUGGESTED', :u)")
            .param("t", in.title().trim()).param("d", blankToNull(in.details())).param("u", user.id()).update(key);
        long id = key.getKey().longValue();
        audit.record("improvement", id, AuditLog.Action.CREATE, Map.of(), user.id());
        return get(id, user);
    }

    /** The person who suggested it may edit it while it is still a suggestion; approvers may edit any time. */
    @PutMapping("/{id}")
    @Transactional
    Improvement edit(@PathVariable long id, @RequestBody Input in) {
        CurrentUser user = currentUser.get();
        Improvement current = lock(id, in.version(), user);
        if (!current.canEdit()) {
            throw new AccessDeniedException("Only the person who suggested it, while it is a suggestion, or an approver may edit it.");
        }
        check(in);
        jdbc.sql("UPDATE improvement SET title = :t, details = :d, version = version + 1 WHERE id = :id")
            .param("t", in.title().trim()).param("d", blankToNull(in.details())).param("id", id).update();
        audit.record("improvement", id, AuditLog.Action.UPDATE, Map.of(), user.id());
        return get(id, user);
    }

    @PostMapping("/{id}/decision")
    @Transactional
    Improvement decide(@PathVariable long id, @RequestBody Decision in) {
        CurrentUser user = currentUser.get();
        user.require(Role.IMPROVEMENT_APPROVERS);
        Improvement current = lock(id, in.version(), user);
        String to = in.status() == null ? null : in.status().trim().toUpperCase(Locale.ROOT);
        List<String> problems = new ArrayList<>();
        if (to == null || !STATUSES.contains(to)) {
            problems.add("Choose a status.");
        } else if (!MOVES.get(current.status()).contains(to)) {
            problems.add("An item that is " + label(current.status()) + " cannot move to " + label(to) + ".");
        }
        if ("DECLINED".equals(to) && blank(in.note())) {
            problems.add("Say why it is declined, so the person who suggested it knows.");
        }
        if (in.note() != null && in.note().length() > 1000) {
            problems.add("The note can be at most 1,000 characters.");
        }
        if (in.deliveredRef() != null && in.deliveredRef().length() > 500) {
            problems.add("The delivered link can be at most 500 characters.");
        }
        if (!problems.isEmpty()) {
            throw new RuleViolationException(problems);
        }
        // Approved items join the end of the build queue; other statuses leave it.
        Integer rank = null;
        if ("APPROVED".equals(to) || "IN_PROGRESS".equals(to)) {
            rank = current.rank() != null ? current.rank()
                : jdbc.sql("SELECT COALESCE(MAX(rank_order), 0) + 1 FROM improvement").query(Integer.class).single();
        }
        jdbc.sql("""
                UPDATE improvement SET status = :to, rank_order = :rank, decided_by = :u, decided_at = CURRENT_TIMESTAMP(3),
                    decision_note = COALESCE(:note, decision_note),
                    delivered_ref = CASE WHEN :to = 'DONE' THEN COALESCE(:ref, delivered_ref) ELSE delivered_ref END,
                    done_at = CASE WHEN :to = 'DONE' THEN CURRENT_TIMESTAMP(3) ELSE NULL END,
                    version = version + 1
                WHERE id = :id
                """)
            .param("to", to).param("rank", rank).param("u", user.id()).param("note", blankToNull(in.note()))
            .param("ref", blankToNull(in.deliveredRef())).param("id", id).update();
        audit.record("improvement", id, AuditLog.Action.UPDATE, Map.of("from", current.status(), "to", to), user.id());
        return get(id, user);
    }

    /** Moves an approved or in-progress item one place up or down the build queue. */
    @PostMapping("/{id}/move")
    @Transactional
    Board move(@PathVariable long id, @RequestBody Move in) {
        CurrentUser user = currentUser.get();
        user.require(Role.IMPROVEMENT_APPROVERS);
        Improvement current = lock(id, in.version(), user);
        if (current.rank() == null) {
            throw new RuleViolationException(List.of("Only approved items have a place in the queue."));
        }
        boolean up = "UP".equalsIgnoreCase(in.direction());
        var neighbour = jdbc.sql("SELECT id, rank_order FROM improvement WHERE rank_order IS NOT NULL AND rank_order "
                + (up ? "< :r ORDER BY rank_order DESC" : "> :r ORDER BY rank_order") + " LIMIT 1 FOR UPDATE")
            .param("r", current.rank())
            .query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)}).optional();
        if (neighbour.isPresent()) {
            jdbc.sql("UPDATE improvement SET rank_order = :r, version = version + 1 WHERE id = :id")
                .param("r", neighbour.get()[1]).param("id", id).update();
            jdbc.sql("UPDATE improvement SET rank_order = :r, version = version + 1 WHERE id = :id")
                .param("r", current.rank()).param("id", neighbour.get()[0]).update();
        }
        return list(null);
    }

    private Improvement lock(long id, Integer version, CurrentUser user) {
        Integer stored = jdbc.sql("SELECT version FROM improvement WHERE id = :id FOR UPDATE").param("id", id)
            .query(Integer.class).optional().orElseThrow(() -> new NotFoundException("Improvement", id));
        if (version != null && !version.equals(stored)) {
            throw new ConflictException("Someone else changed this item. Reload and try again.");
        }
        return get(id, user);
    }

    private Improvement get(long id, CurrentUser user) {
        return jdbc.sql(SELECT + " WHERE i.id = :id").param("id", id).query((rs, n) -> map(rs, user)).optional()
            .orElseThrow(() -> new NotFoundException("Improvement", id));
    }

    private static void check(Input in) {
        List<String> problems = new ArrayList<>();
        if (in == null || blank(in.title())) {
            problems.add("Give the improvement a short title.");
        } else if (in.title().trim().length() > 200) {
            problems.add("The title can be at most 200 characters.");
        }
        if (in != null && in.details() != null && in.details().length() > 10_000) {
            problems.add("The details can be at most 10,000 characters.");
        }
        if (!problems.isEmpty()) {
            throw new RuleViolationException(problems);
        }
    }

    private static Improvement map(java.sql.ResultSet rs, CurrentUser user) throws java.sql.SQLException {
        String status = rs.getString(4);
        boolean approver = user.hasAnyRole(Role.IMPROVEMENT_APPROVERS);
        boolean canEdit = approver || (rs.getLong(7) == user.id() && "SUGGESTED".equals(status));
        return new Improvement(rs.getLong(1), rs.getString(2), rs.getString(3), status, rs.getObject(5, Integer.class),
            rs.getString(6), rs.getLong(7), rs.getTimestamp(8).toInstant(), rs.getString(9),
            rs.getTimestamp(10) == null ? null : rs.getTimestamp(10).toInstant(), rs.getString(11), rs.getString(12),
            rs.getTimestamp(13) == null ? null : rs.getTimestamp(13).toInstant(), rs.getInt(14), canEdit);
    }

    private static String label(String status) {
        return status.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return blank(s) ? null : s.trim();
    }
}
