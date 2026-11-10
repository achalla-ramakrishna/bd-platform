package com.codewalnut.bd.reporting;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.codewalnut.bd.shared.NotFoundException;

/** Read side of connections (backlog item 4): the list, who knows whom, warm paths and growth. */
@Component
public class ConnectionQueries {

    public record Knower(long userId, String userName, String strength, String howKnown, LocalDate connectedOn) {}

    public record Connection(long id, String fullName, String currentCompany, String currentRole, String city,
                             String linkedinUrl, String email, Long accountId, String accountName, String accountLink,
                             Long contactId, String notes, List<Knower> knownBy, LocalDate firstConnectedOn,
                             Instant createdAt, int version, Long metAtEventId, String metAtEventName,
                             Long photoVersion) {}

    /** Connections first made in one week (Monday start), for the growth line. */
    public record Week(LocalDate weekStart, long added) {}

    public record Summary(long total, long knownByMe, long linkedToAccounts, long addedLast30Days, List<Week> weekly) {}

    public record Page(List<Connection> items, Summary summary) {}

    private static final String SELECT = """
        SELECT n.id, n.full_name, n.current_company, n.current_role, n.city, n.linkedin_url, n.email, n.account_id,
            a.name, n.account_link, n.contact_id, n.notes, n.created_at, n.version, n.met_at_event_id, ev.name,
            ph.updated_at
        FROM connection n
        LEFT JOIN connection_photo ph ON ph.connection_id = n.id
        LEFT JOIN account a ON a.id = n.account_id
        LEFT JOIN bd_event ev ON ev.id = n.met_at_event_id
        """;

    private final JdbcClient jdbc;

    ConnectionQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Connection get(long id) {
        List<Connection> one = load(SELECT + " WHERE n.id = :id", Map.of("id", id));
        if (one.isEmpty()) {
            throw new NotFoundException("Connection", id);
        }
        return one.get(0);
    }

    /** Warm paths into one account: people who work there or can introduce us. */
    public List<Connection> forAccount(long accountId) {
        return load(SELECT + " WHERE n.account_id = :a ORDER BY n.account_link DESC, n.full_name", Map.of("a", accountId));
    }

    /**
     * {@code knownBy}: a user id, or null for everyone. {@code linked}: true for connections linked to a
     * target account, false for the rest. {@code strength}: only connections someone knows this well.
     */
    public Page list(String q, Long knownBy, Long accountId, Boolean linked, String strength, long me) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        Map<String, Object> params = new LinkedHashMap<>();
        if (q != null && !q.isBlank()) {
            where.append(" AND (n.full_name LIKE :q OR n.current_company LIKE :q OR n.current_role LIKE :q OR a.name LIKE :q)");
            params.put("q", "%" + q.trim() + "%");
        }
        if (knownBy != null) {
            where.append(" AND EXISTS (SELECT 1 FROM connection_knower k WHERE k.connection_id = n.id AND k.user_id = :knower)");
            params.put("knower", knownBy);
        }
        if (accountId != null) {
            where.append(" AND n.account_id = :account");
            params.put("account", accountId);
        }
        if (linked != null) {
            where.append(linked ? " AND n.account_id IS NOT NULL" : " AND n.account_id IS NULL");
        }
        if (strength != null && !strength.isBlank()) {
            where.append(" AND EXISTS (SELECT 1 FROM connection_knower k WHERE k.connection_id = n.id AND k.strength = :strength)");
            params.put("strength", strength.trim().toUpperCase(java.util.Locale.ROOT));
        }
        return new Page(load(SELECT + where + " ORDER BY n.full_name LIMIT 2000", params), summary(me));
    }

    private Summary summary(long me) {
        long[] counts = jdbc.sql("""
                SELECT COUNT(*),
                    SUM(EXISTS (SELECT 1 FROM connection_knower k WHERE k.connection_id = n.id AND k.user_id = :me)),
                    SUM(n.account_id IS NOT NULL)
                FROM connection n
                """).param("me", me)
            .query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3)}).single();
        // A connection counts in the week its first knower connected with them.
        Map<LocalDate, Long> byWeek = new HashMap<>();
        LocalDate thisMonday = LocalDate.now().with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        LocalDate from = thisMonday.minusWeeks(11);
        long last30 = 0;
        for (LocalDate first : jdbc.sql("SELECT MIN(connected_on) FROM connection_knower GROUP BY connection_id")
                .query(LocalDate.class).list()) {
            if (!first.isBefore(LocalDate.now().minusDays(30))) {
                last30++;
            }
            if (!first.isBefore(from)) {
                byWeek.merge(first.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)), 1L, Long::sum);
            }
        }
        List<Week> weekly = new ArrayList<>();
        for (LocalDate w = from; !w.isAfter(thisMonday); w = w.plus(1, ChronoUnit.WEEKS)) {
            weekly.add(new Week(w, byWeek.getOrDefault(w, 0L)));
        }
        return new Summary(counts[0], counts[1], counts[2], last30, weekly);
    }

    private List<Connection> load(String sql, Map<String, ?> params) {
        record Base(long id, String name, String company, String role, String city, String linkedin, String email,
                    Long account, String accountName, String link, Long contact, String notes, Instant created, int version,
                    Long event, String eventName, Long photo) {}
        List<Base> rows = jdbc.sql(sql).params(params)
            .query((rs, n) -> new Base(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getObject(8, Long.class), rs.getString(9), rs.getString(10),
                rs.getObject(11, Long.class), rs.getString(12), rs.getTimestamp(13).toInstant(), rs.getInt(14),
                rs.getObject(15, Long.class), rs.getString(16),
                rs.getTimestamp(17) == null ? null : rs.getTimestamp(17).getTime()))
            .list();
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, List<Knower>> knowers = new HashMap<>();
        jdbc.sql("""
                SELECT k.connection_id, k.user_id, u.full_name, k.strength, k.how_known, k.connected_on
                FROM connection_knower k JOIN app_user u ON u.id = k.user_id
                WHERE k.connection_id IN (:ids)
                ORDER BY FIELD(k.strength, 'CLOSE', 'KNOW_WELL', 'ACQUAINTANCE'), u.full_name
                """).param("ids", rows.stream().map(Base::id).toList())
            .query((rs, n) -> knowers.computeIfAbsent(rs.getLong(1), x -> new ArrayList<>()).add(new Knower(rs.getLong(2),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getObject(6, LocalDate.class))))
            .list();
        return rows.stream().map(b -> {
            List<Knower> k = knowers.getOrDefault(b.id(), List.of());
            LocalDate first = k.stream().map(Knower::connectedOn).min(LocalDate::compareTo).orElse(null);
            return new Connection(b.id(), b.name(), b.company(), b.role(), b.city(), b.linkedin(), b.email(), b.account(),
                b.accountName(), b.link(), b.contact(), b.notes(), k, first, b.created(), b.version(), b.event(), b.eventName(),
                b.photo());
        }).toList();
    }
}
