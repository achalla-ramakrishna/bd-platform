package com.codewalnut.bd.reporting;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.codewalnut.bd.shared.NotFoundException;

/** Read side of conferences and events (backlog item 6). */
@Component
public class EventQueries {

    public record Attendee(long userId, String name) {}

    public record Event(long id, String name, String kind, LocalDate startsOn, LocalDate endsOn, String city,
                        boolean online, String url, String organiser, String ourRole, String notes, String outcome,
                        List<Attendee> attendees, long conversations, long connections, int version) {}

    public record Detail(Event event, List<ConversationQueries.Conversation> conversations,
                         List<ConnectionQueries.Connection> connections) {}

    /** Counts for the cards: next 30 days, where we present or organise, this quarter, people met in 90 days. */
    public record Summary(long next30Days, long presentingOrOrganising, long thisQuarter, long peopleMetLast90Days) {}

    public record Page(List<Event> items, Summary summary) {}

    private static final String SELECT = """
        SELECT e.id, e.name, e.kind, e.starts_on, e.ends_on, e.city, e.online, e.url, e.organiser, e.our_role, e.notes,
            e.outcome, e.version,
            (SELECT COUNT(*) FROM conversation v WHERE v.event_id = e.id),
            (SELECT COUNT(*) FROM connection n WHERE n.met_at_event_id = e.id)
        FROM bd_event e
        """;

    private final JdbcClient jdbc;
    private final ConversationQueries conversations;
    private final ConnectionQueries connections;

    EventQueries(JdbcClient jdbc, ConversationQueries conversations, ConnectionQueries connections) {
        this.jdbc = jdbc;
        this.conversations = conversations;
        this.connections = connections;
    }

    /** {@code when}: "upcoming" (today or later, soonest first) or "past" (most recent first); null for all. */
    public Page list(String when, String role, String kind, Boolean mine, long me) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        Map<String, Object> params = new LinkedHashMap<>();
        boolean past = "past".equalsIgnoreCase(when);
        if ("upcoming".equalsIgnoreCase(when)) {
            where.append(" AND COALESCE(e.ends_on, e.starts_on) >= CURRENT_DATE");
        } else if (past) {
            where.append(" AND COALESCE(e.ends_on, e.starts_on) < CURRENT_DATE");
        }
        if (role != null && !role.isBlank()) {
            where.append(" AND e.our_role = :role");
            params.put("role", role.trim().toUpperCase(java.util.Locale.ROOT));
        }
        if (kind != null && !kind.isBlank()) {
            where.append(" AND e.kind = :kind");
            params.put("kind", kind.trim().toUpperCase(java.util.Locale.ROOT));
        }
        if (Boolean.TRUE.equals(mine)) {
            where.append(" AND EXISTS (SELECT 1 FROM bd_event_attendee x WHERE x.event_id = e.id AND x.user_id = :me)");
            params.put("me", me);
        }
        String order = past ? " ORDER BY e.starts_on DESC, e.id DESC" : " ORDER BY e.starts_on, e.id";
        return new Page(load(SELECT + where + order + " LIMIT 1000", params), summary());
    }

    /** Events whose name contains the text, or is contained in it (ADR-0013 matching), soonest upcoming first. */
    public List<Event> findByName(String text, int limit) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return load(SELECT + """
             WHERE e.name LIKE :like OR :text LIKE CONCAT('%', e.name, '%')
            ORDER BY COALESCE(e.ends_on, e.starts_on) < CURRENT_DATE, e.starts_on
            LIMIT :limit
            """, Map.of("like", "%" + text.trim() + "%", "text", text.trim(), "limit", limit));
    }

    public Event get(long id) {
        List<Event> one = load(SELECT + " WHERE e.id = :id", Map.of("id", id));
        if (one.isEmpty()) {
            throw new NotFoundException("Event", id);
        }
        return one.get(0);
    }

    public Detail detail(long id) {
        Event e = get(id);
        List<Long> conversationIds = jdbc.sql("SELECT id FROM conversation WHERE event_id = :id ORDER BY id")
            .param("id", id).query(Long.class).list();
        List<Long> connectionIds = jdbc.sql("SELECT id FROM connection WHERE met_at_event_id = :id ORDER BY full_name")
            .param("id", id).query(Long.class).list();
        return new Detail(e, conversationIds.stream().map(conversations::get).toList(),
            connectionIds.stream().map(connections::get).toList());
    }

    private Summary summary() {
        return jdbc.sql("""
                SELECT
                    (SELECT COUNT(*) FROM bd_event WHERE starts_on BETWEEN CURRENT_DATE AND CURRENT_DATE + INTERVAL 30 DAY),
                    (SELECT COUNT(*) FROM bd_event WHERE our_role IN ('PRESENTING', 'ORGANISING', 'STALL')
                        AND COALESCE(ends_on, starts_on) >= CURRENT_DATE),
                    (SELECT COUNT(*) FROM bd_event WHERE YEAR(starts_on) = YEAR(CURRENT_DATE)
                        AND QUARTER(starts_on) = QUARTER(CURRENT_DATE)),
                    (SELECT COUNT(*) FROM conversation v JOIN bd_event e ON e.id = v.event_id
                        WHERE e.starts_on >= CURRENT_DATE - INTERVAL 90 DAY)
                    + (SELECT COUNT(*) FROM connection n JOIN bd_event e ON e.id = n.met_at_event_id
                        WHERE e.starts_on >= CURRENT_DATE - INTERVAL 90 DAY)
                """)
            .query((rs, n) -> new Summary(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4))).single();
    }

    private List<Event> load(String sql, Map<String, ?> params) {
        record Base(long id, String name, String kind, LocalDate starts, LocalDate ends, String city, boolean online,
                    String url, String organiser, String role, String notes, String outcome, int version, long conv, long conn) {}
        List<Base> rows = jdbc.sql(sql).params(params).query((rs, n) -> new Base(rs.getLong(1), rs.getString(2),
            rs.getString(3), rs.getObject(4, LocalDate.class), rs.getObject(5, LocalDate.class), rs.getString(6),
            rs.getBoolean(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12),
            rs.getInt(13), rs.getLong(14), rs.getLong(15))).list();
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, List<Attendee>> attendees = new HashMap<>();
        jdbc.sql("""
                SELECT x.event_id, u.id, u.full_name FROM bd_event_attendee x JOIN app_user u ON u.id = x.user_id
                WHERE x.event_id IN (:ids) ORDER BY u.full_name
                """).param("ids", rows.stream().map(Base::id).toList())
            .query((rs, n) -> attendees.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
                .add(new Attendee(rs.getLong(2), rs.getString(3))))
            .list();
        return rows.stream().map(b -> new Event(b.id(), b.name(), b.kind(), b.starts(), b.ends(), b.city(), b.online(),
            b.url(), b.organiser(), b.role(), b.notes(), b.outcome(), attendees.getOrDefault(b.id(), List.of()),
            b.conv(), b.conn(), b.version())).toList();
    }
}
