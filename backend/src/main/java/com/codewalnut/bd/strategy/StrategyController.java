package com.codewalnut.bd.strategy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.codewalnut.bd.admin.CurrentUserProvider;
import com.codewalnut.bd.shared.AuditLog;
import com.codewalnut.bd.shared.ConflictException;
import com.codewalnut.bd.shared.CurrentUser;
import com.codewalnut.bd.shared.NotFoundException;
import com.codewalnut.bd.shared.Role;
import com.codewalnut.bd.shared.RuleViolationException;

/**
 * The BD strategy, written in Markdown, so nobody has to look outside the app for what we are trying
 * to do (backlog item 2). Everyone reads it; leadership and admins edit. Each save is a new version.
 */
@RestController
@RequestMapping("/api/v1/strategy")
class StrategyController {

    static final int MAX_LENGTH = 50_000;

    record Version(long id, String body, String note, String editedBy, Instant editedAt) {}

    record VersionSummary(long id, String note, String editedBy, Instant editedAt) {}

    record Strategy(Version current, boolean canEdit) {}

    /** {@code baseVersionId} is the version the editor started from; a newer one means someone else saved. */
    record Edit(String body, String note, Long baseVersionId) {}

    private static final String SELECT = """
        SELECT s.id, s.body, s.note, COALESCE(u.full_name, 'Set up') AS edited_by, s.edited_at
        FROM strategy_version s LEFT JOIN app_user u ON u.id = s.edited_by
        """;

    private final JdbcClient jdbc;
    private final CurrentUserProvider currentUser;
    private final AuditLog audit;

    StrategyController(JdbcClient jdbc, CurrentUserProvider currentUser, AuditLog audit) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    @GetMapping
    Strategy current() {
        CurrentUser user = currentUser.get();
        return new Strategy(latest(), user.hasAnyRole(Role.STRATEGY_EDITORS));
    }

    @GetMapping("/versions")
    List<VersionSummary> versions() {
        currentUser.get();
        return jdbc.sql("""
                SELECT s.id, s.note, COALESCE(u.full_name, 'Set up'), s.edited_at
                FROM strategy_version s LEFT JOIN app_user u ON u.id = s.edited_by
                ORDER BY s.id DESC LIMIT 200
                """)
            .query((rs, n) -> new VersionSummary(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getTimestamp(4).toInstant()))
            .list();
    }

    @GetMapping("/versions/{id}")
    Version version(@PathVariable long id) {
        currentUser.get();
        return jdbc.sql(SELECT + " WHERE s.id = :id").param("id", id).query((rs, n) -> version(rs)).optional()
            .orElseThrow(() -> new NotFoundException("Strategy version", id));
    }

    @PutMapping
    @Transactional
    Version save(@RequestBody Edit edit) {
        CurrentUser user = currentUser.get();
        user.require(Role.STRATEGY_EDITORS);
        List<String> problems = new ArrayList<>();
        String body = edit == null || edit.body() == null ? "" : edit.body().strip();
        if (body.isEmpty()) {
            problems.add("The strategy cannot be empty.");
        }
        if (body.length() > MAX_LENGTH) {
            problems.add("The strategy can be at most " + MAX_LENGTH + " characters.");
        }
        String note = edit == null || edit.note() == null || edit.note().isBlank() ? null : edit.note().strip();
        if (note != null && note.length() > 300) {
            problems.add("The change note can be at most 300 characters.");
        }
        if (!problems.isEmpty()) {
            throw new RuleViolationException(problems);
        }
        // Serialise saves so two editors cannot both pass the check below.
        jdbc.sql("SELECT id FROM strategy_version ORDER BY id DESC LIMIT 1 FOR UPDATE").query(Long.class).optional();
        Version latest = latest();
        if (latest != null && edit.baseVersionId() != null && latest.id() != edit.baseVersionId()) {
            throw new ConflictException(latest.editedBy() + " saved a newer version. Copy your changes, reload and try again.");
        }
        if (latest != null && latest.body().equals(body)) {
            return latest;
        }
        var key = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO strategy_version (body, note, edited_by) VALUES (:body, :note, :user)")
            .param("body", body).param("note", note).param("user", user.id()).update(key);
        long id = key.getKey().longValue();
        audit.record("strategy_version", id, AuditLog.Action.CREATE, Map.of("characters", body.length()), user.id());
        return version(id);
    }

    private Version latest() {
        return jdbc.sql(SELECT + " ORDER BY s.id DESC LIMIT 1").query((rs, n) -> version(rs)).optional().orElse(null);
    }

    private static Version version(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Version(rs.getLong("id"), rs.getString("body"), rs.getString("note"), rs.getString("edited_by"),
            rs.getTimestamp("edited_at").toInstant());
    }
}
