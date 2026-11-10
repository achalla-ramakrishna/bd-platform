package com.codewalnut.bd.engagement;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
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
 * Leader interviews (backlog item 8): "I'm reaching out to interview you and publish it where AI
 * engineering leaders are featured", not "can I supply you engineers". Everyone reads; pipeline roles
 * add and move interviews.
 */
@RestController
@RequestMapping("/api/v1/interviews")
class InterviewController {

    static final Set<String> FORMATS = Set.of("BLOG", "PODCAST", "VIDEO", "LINKEDIN_ARTICLE");
    static final List<String> FLOW = List.of("IDEA", "ASKED", "AGREED", "BOOKED", "RECORDED", "PUBLISHED");

    record Interview(long id, String leaderName, String leaderTitle, String company, Long accountId, String accountName,
                     String topic, String format, String status, long ownerId, String ownerName, LocalDate askedOn,
                     LocalDate scheduledOn, LocalDate recordedOn, LocalDate publishedOn, String publishedUrl,
                     String notes, Instant createdAt, int version) {}

    /** Cards: in progress (asked to recorded), booked in the next 30 days, recorded but not published, published this quarter. */
    record Summary(long inProgress, long bookedNext30Days, long awaitingPublication, long publishedThisQuarter) {}

    record Page(List<Interview> items, Summary summary) {}

    record Input(String leaderName, String leaderTitle, String company, Long accountId, String topic, String format,
                 String notes, Long ownerId, Integer version) {}

    /** Move to a status; BOOKED needs the date, PUBLISHED the link. {@code on} defaults to today. */
    record Move(String status, LocalDate on, String publishedUrl, Integer version) {}

    private static final String SELECT = """
        SELECT i.id, i.leader_name, i.leader_title, i.company, i.account_id, a.name, i.topic, i.format, i.status,
            i.owner_user_id, u.full_name, i.asked_on, i.scheduled_on, i.recorded_on, i.published_on, i.published_url,
            i.notes, i.created_at, i.version
        FROM interview i
        JOIN app_user u ON u.id = i.owner_user_id
        LEFT JOIN account a ON a.id = i.account_id
        """;

    private final JdbcClient jdbc;
    private final CurrentUserProvider currentUser;
    private final AuditLog audit;

    InterviewController(JdbcClient jdbc, CurrentUserProvider currentUser, AuditLog audit) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    @GetMapping
    Page list(@RequestParam(required = false) String status, @RequestParam(required = false) Boolean mine,
              @RequestParam(required = false) Long accountId) {
        CurrentUser user = currentUser.get();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        Map<String, Object> params = new LinkedHashMap<>();
        if ("OPEN".equalsIgnoreCase(status)) {
            where.append(" AND i.status NOT IN ('PUBLISHED', 'DECLINED')");
        } else if (status != null && !status.isBlank()) {
            where.append(" AND i.status = :status");
            params.put("status", status.trim().toUpperCase(Locale.ROOT));
        }
        if (Boolean.TRUE.equals(mine)) {
            where.append(" AND i.owner_user_id = :me");
            params.put("me", user.id());
        }
        if (accountId != null) {
            where.append(" AND i.account_id = :account");
            params.put("account", accountId);
        }
        List<Interview> items = jdbc.sql(SELECT + where + """
                 ORDER BY FIELD(i.status, 'BOOKED', 'AGREED', 'ASKED', 'RECORDED', 'IDEA', 'PUBLISHED', 'DECLINED'),
                    i.scheduled_on IS NULL, i.scheduled_on, i.published_on DESC, i.created_at DESC
                LIMIT 1000
                """).params(params).query((rs, n) -> map(rs)).list();
        Summary summary = jdbc.sql("""
                SELECT SUM(status IN ('ASKED', 'AGREED', 'BOOKED', 'RECORDED')),
                    SUM(status = 'BOOKED' AND scheduled_on BETWEEN CURRENT_DATE AND CURRENT_DATE + INTERVAL 30 DAY),
                    SUM(status = 'RECORDED'),
                    SUM(status = 'PUBLISHED' AND YEAR(published_on) = YEAR(CURRENT_DATE) AND QUARTER(published_on) = QUARTER(CURRENT_DATE))
                FROM interview
                """).query((rs, n) -> new Summary(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4))).single();
        return new Page(items, summary);
    }

    @GetMapping("/{id}")
    Interview get(@PathVariable long id) {
        currentUser.get();
        return load(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Interview add(@RequestBody Input in) {
        CurrentUser user = currentUser.get();
        user.require(Role.PIPELINE_EDITORS);
        check(in);
        var key = new GeneratedKeyHolder();
        bind(jdbc.sql("""
                INSERT INTO interview (leader_name, leader_title, company, account_id, topic, format, status, owner_user_id, notes)
                VALUES (:name, :title, :company, :account, :topic, :format, 'IDEA', :owner, :notes)
                """), in, user).update(key);
        long id = key.getKey().longValue();
        audit.record("interview", id, AuditLog.Action.CREATE, Map.of("format", in.format()), user.id());
        return load(id);
    }

    @PutMapping("/{id}")
    @Transactional
    Interview update(@PathVariable long id, @RequestBody Input in) {
        CurrentUser user = currentUser.get();
        user.require(Role.PIPELINE_EDITORS);
        lock(id, in.version());
        check(in);
        bind(jdbc.sql("""
                UPDATE interview SET leader_name = :name, leader_title = :title, company = :company, account_id = :account,
                    topic = :topic, format = :format, owner_user_id = :owner, notes = :notes, version = version + 1
                WHERE id = :id
                """), in, user).param("id", id).update();
        audit.record("interview", id, AuditLog.Action.UPDATE, Map.of(), user.id());
        return load(id);
    }

    @PostMapping("/{id}/move")
    @Transactional
    Interview move(@PathVariable long id, @RequestBody Move in) {
        CurrentUser user = currentUser.get();
        user.require(Role.PIPELINE_EDITORS);
        Interview current = lock(id, in.version());
        String to = in.status() == null ? null : in.status().trim().toUpperCase(Locale.ROOT);
        List<String> problems = new ArrayList<>();
        int from = FLOW.indexOf(current.status());
        boolean allowed = to != null && (
            ("DECLINED".equals(to) && from >= 0 && from < FLOW.indexOf("RECORDED"))
            || ("IDEA".equals(to) && "DECLINED".equals(current.status()))
            || (FLOW.contains(to) && from >= 0 && Math.abs(FLOW.indexOf(to) - from) == 1));
        if (!allowed) {
            problems.add("An interview that is " + label(current.status()) + " cannot move to " + (to == null ? "that" : label(to)) + ".");
        }
        LocalDate on = in.on() == null ? LocalDate.now() : in.on();
        if ("BOOKED".equals(to) && in.on() == null) {
            problems.add("Give the date it is booked for.");
        }
        String url = in.publishedUrl() == null ? null : in.publishedUrl().trim();
        if ("PUBLISHED".equals(to) && (url == null || !url.matches("(?i)^https?://\\S+$") || url.length() > 500)) {
            problems.add("Give the link where it is published (http:// or https://).");
        }
        if (("RECORDED".equals(to) || "PUBLISHED".equals(to) || "ASKED".equals(to)) && on.isAfter(LocalDate.now())) {
            problems.add("That date cannot be in the future.");
        }
        if (!problems.isEmpty()) {
            throw new RuleViolationException(problems);
        }
        String column = switch (to) {
            case "ASKED" -> "asked_on";
            case "BOOKED" -> "scheduled_on";
            case "RECORDED" -> "recorded_on";
            case "PUBLISHED" -> "published_on";
            default -> null;
        };
        jdbc.sql("UPDATE interview SET status = :to" + (column == null ? "" : ", " + column + " = :on")
                + ("PUBLISHED".equals(to) ? ", published_url = :url" : "") + ", version = version + 1 WHERE id = :id")
            .param("to", to).param("on", on).param("url", url).param("id", id).update();
        audit.record("interview", id, AuditLog.Action.UPDATE, Map.of("from", current.status(), "to", to), user.id());
        return load(id);
    }

    private Interview lock(long id, Integer version) {
        Integer stored = jdbc.sql("SELECT version FROM interview WHERE id = :id FOR UPDATE").param("id", id)
            .query(Integer.class).optional().orElseThrow(() -> new NotFoundException("Interview", id));
        if (version != null && !version.equals(stored)) {
            throw new ConflictException("Someone else changed this interview. Reload and try again.");
        }
        return load(id);
    }

    private Interview load(long id) {
        return jdbc.sql(SELECT + " WHERE i.id = :id").param("id", id).query((rs, n) -> map(rs)).optional()
            .orElseThrow(() -> new NotFoundException("Interview", id));
    }

    private void check(Input in) {
        List<String> problems = new ArrayList<>();
        if (in == null || blank(in.leaderName())) {
            problems.add("Give the leader's name.");
        }
        if (in == null || blank(in.topic())) {
            problems.add("Give the topic, for example \"How we scaled AI-native delivery in our GCC\".");
        }
        if (in == null) {
            throw new RuleViolationException(problems);
        }
        if (!FORMATS.contains(String.valueOf(in.format()).toUpperCase(Locale.ROOT))) {
            problems.add("Choose the format.");
        }
        if (len(in.leaderName()) > 200 || len(in.leaderTitle()) > 200 || len(in.company()) > 200 || len(in.topic()) > 300) {
            problems.add("Names, titles and companies can be at most 200 characters, and the topic 300.");
        }
        if (in.accountId() != null && jdbc.sql("SELECT COUNT(*) FROM account WHERE id = :id").param("id", in.accountId())
                .query(Integer.class).single() == 0) {
            problems.add("Choose an existing account.");
        }
        if (in.ownerId() != null && jdbc.sql("SELECT COUNT(*) FROM app_user WHERE id = :id").param("id", in.ownerId())
                .query(Integer.class).single() == 0) {
            problems.add("Choose an existing user as owner.");
        }
        if (!problems.isEmpty()) {
            throw new RuleViolationException(problems);
        }
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, Input in, CurrentUser user) {
        return spec.param("name", in.leaderName().trim()).param("title", trim(in.leaderTitle()))
            .param("company", trim(in.company())).param("account", in.accountId()).param("topic", in.topic().trim())
            .param("format", in.format().trim().toUpperCase(Locale.ROOT))
            .param("owner", in.ownerId() == null ? user.id() : in.ownerId()).param("notes", trim(in.notes()));
    }

    private static Interview map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Interview(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getObject(5, Long.class),
            rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getLong(10), rs.getString(11),
            rs.getObject(12, LocalDate.class), rs.getObject(13, LocalDate.class), rs.getObject(14, LocalDate.class),
            rs.getObject(15, LocalDate.class), rs.getString(16), rs.getString(17), rs.getTimestamp(18).toInstant(), rs.getInt(19));
    }

    private static String label(String status) {
        return status.toLowerCase(Locale.ROOT);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String trim(String s) {
        return blank(s) ? null : s.trim();
    }

    private static int len(String s) {
        return s == null ? 0 : s.trim().length();
    }
}
