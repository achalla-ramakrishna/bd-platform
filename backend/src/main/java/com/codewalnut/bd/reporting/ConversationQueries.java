package com.codewalnut.bd.reporting;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.codewalnut.bd.shared.NotFoundException;

/** Read side of conversations (backlog item 3): lists, the due list and one conversation's updates. */
@Component
public class ConversationQueries {

    public record Conversation(long id, long accountId, String accountName, String city, String websiteDomain,
                               Long contactId, String personName, String personTitle, String topic, long ownerId,
                               String ownerName, String status, String nextStep, LocalDate nextStepDue,
                               Long opportunityId, String opportunityName, LocalDate lastTouchOn, String lastSummary,
                               Instant createdAt, int version) {}

    public record Detail(Conversation conversation, List<ActivityQueries.Activity> updates) {}

    /** Counts for the cards at the top of the page. "Open" leaves out closed and gone-quiet ones. */
    public record Summary(long open, long overdue, long dueThisWeek, long meetingsBooked, long opportunities,
                          long goneQuiet, long startedLast30Days) {}

    public record Page(List<Conversation> items, Summary summary) {}

    private static final String SELECT = """
        SELECT v.id, v.account_id, a.name, a.city, a.website_domain, v.contact_id,
            COALESCE(c.full_name, v.person_name) AS person, c.title, v.topic, v.owner_user_id, u.full_name,
            v.status, v.next_step, v.next_step_due, v.opportunity_id, o.name AS opportunity, v.last_touch_on,
            (SELECT x.summary FROM activity x WHERE x.conversation_id = v.id ORDER BY x.occurred_on DESC, x.id DESC LIMIT 1),
            v.created_at, v.version
        FROM conversation v
        JOIN account a ON a.id = v.account_id
        JOIN app_user u ON u.id = v.owner_user_id
        LEFT JOIN contact c ON c.id = v.contact_id
        LEFT JOIN opportunity o ON o.id = v.opportunity_id
        """;

    private static final String OPEN = "v.status NOT IN ('CLOSED', 'GONE_QUIET')";

    private final JdbcClient jdbc;
    private final ActivityQueries activities;

    ConversationQueries(JdbcClient jdbc, ActivityQueries activities) {
        this.jdbc = jdbc;
        this.activities = activities;
    }

    public Conversation get(long id) {
        return jdbc.sql(SELECT + " WHERE v.id = :id").param("id", id).query(this::map).optional()
            .orElseThrow(() -> new NotFoundException("Conversation", id));
    }

    public Detail detail(long id) {
        return new Detail(get(id), activities.forConversation(id));
    }

    /**
     * {@code due}: "overdue" (open, next step before today), "week" (open, next step within 7 days,
     * overdue included) or null. Open conversations with the earliest next step come first.
     */
    public Page list(String status, Long ownerId, Long accountId, String due, String q) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        Map<String, Object> params = new LinkedHashMap<>();
        if (status != null && !status.isBlank()) {
            if ("OPEN".equalsIgnoreCase(status)) {
                where.append(" AND ").append(OPEN);
            } else {
                where.append(" AND v.status = :status");
                params.put("status", status.toUpperCase(java.util.Locale.ROOT));
            }
        }
        if (ownerId != null) {
            where.append(" AND v.owner_user_id = :owner");
            params.put("owner", ownerId);
        }
        if (accountId != null) {
            where.append(" AND v.account_id = :account");
            params.put("account", accountId);
        }
        if ("overdue".equalsIgnoreCase(due)) {
            where.append(" AND ").append(OPEN).append(" AND v.next_step_due < CURRENT_DATE");
        } else if ("week".equalsIgnoreCase(due)) {
            where.append(" AND ").append(OPEN).append(" AND v.next_step_due <= CURRENT_DATE + INTERVAL 7 DAY");
        }
        if (q != null && !q.isBlank()) {
            where.append(" AND (a.name LIKE :q OR v.topic LIKE :q OR v.person_name LIKE :q OR c.full_name LIKE :q)");
            params.put("q", "%" + q.trim() + "%");
        }
        List<Conversation> items = jdbc.sql(SELECT + where + """
                 ORDER BY (v.status IN ('CLOSED', 'GONE_QUIET')), v.next_step_due IS NULL, v.next_step_due,
                    v.last_touch_on DESC, v.id DESC LIMIT 1000
                """).params(params).query(this::map).list();
        return new Page(items, summary(ownerId));
    }

    private Summary summary(Long ownerId) {
        String mine = ownerId == null ? "" : " AND v.owner_user_id = :owner";
        return jdbc.sql("SELECT "
                + "SUM(" + OPEN + "), "
                + "SUM(" + OPEN + " AND v.next_step_due < CURRENT_DATE), "
                + "SUM(" + OPEN + " AND v.next_step_due <= CURRENT_DATE + INTERVAL 7 DAY), "
                + "SUM(v.status = 'MEETING_BOOKED'), SUM(v.status = 'OPPORTUNITY'), SUM(v.status = 'GONE_QUIET'), "
                + "SUM(v.created_at >= NOW() - INTERVAL 30 DAY) "
                + "FROM conversation v WHERE 1 = 1" + mine)
            .params(ownerId == null ? Map.of() : Map.of("owner", ownerId))
            .query((rs, n) -> new Summary(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5),
                rs.getLong(6), rs.getLong(7)))
            .single();
    }

    private Conversation map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Conversation(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
            rs.getObject(6, Long.class), rs.getString(7), rs.getString(8), rs.getString(9), rs.getLong(10),
            rs.getString(11), rs.getString(12), rs.getString(13), rs.getObject(14, LocalDate.class),
            rs.getObject(15, Long.class), rs.getString(16), rs.getObject(17, LocalDate.class), rs.getString(18),
            rs.getTimestamp(19).toInstant(), rs.getInt(20));
    }
}
