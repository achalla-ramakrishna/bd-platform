package com.codewalnut.bd.reporting;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.codewalnut.bd.admin.CurrentUserProvider;

/**
 * The BD activity heatmap (backlog item 7): one cell per day, darker for more touches, new connections
 * and events. For the team, or one person. Everything comes from what people already record:
 * activities (including conversation updates), connections and events.
 */
@RestController
class HeatmapController {

    static final int MAX_ITEMS_PER_DAY = 8;

    /** What happened on a day. {@code link} is an app path. */
    record Item(String kind, String text, String link) {}

    /**
     * {@code total} = touches + new connections + events + interviews recorded or published; meetings and
     * conversations started are part of touches.
     */
    record Day(LocalDate date, int total, int touches, int meetings, int conversationsStarted, int connections,
               int events, int interviews, List<Item> items, int moreItems) {}

    record Week(LocalDate weekStart, int total) {}

    record Person(long id, String name) {}

    record Summary(int total, int thisWeek, int lastWeek, int activeDaysLast30, Week bestWeek) {}

    record Heatmap(LocalDate from, LocalDate to, List<Day> days, List<Week> weeks, Summary summary,
                   List<Person> people) {}

    private final JdbcClient jdbc;
    private final CurrentUserProvider currentUser;

    HeatmapController(JdbcClient jdbc, CurrentUserProvider currentUser) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
    }

    @GetMapping("/api/v1/heatmap")
    Heatmap heatmap(@RequestParam(defaultValue = "26") int weeks, @RequestParam(required = false) Long userId) {
        currentUser.get();
        int span = Math.clamp(weeks, 4, 53);
        LocalDate today = LocalDate.now();
        LocalDate thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate from = thisMonday.minusWeeks(span - 1L);

        Map<LocalDate, Acc> days = new TreeMap<>();
        for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) {
            days.put(d, new Acc());
        }

        // Touches: every logged activity, including conversation updates.
        jdbc.sql("""
                SELECT x.occurred_on, x.channel, x.outcome, COALESCE(c.full_name, v.person_name), a.name, x.account_id,
                    x.conversation_id
                FROM activity x
                JOIN account a ON a.id = x.account_id
                LEFT JOIN contact c ON c.id = x.contact_id
                LEFT JOIN conversation v ON v.id = x.conversation_id
                WHERE x.occurred_on BETWEEN :from AND :to AND (:user IS NULL OR x.user_id = :user)
                ORDER BY x.occurred_on, x.id
                """).param("from", from).param("to", today).param("user", userId)
            .query((rs, n) -> {
                Acc acc = days.get(rs.getObject(1, LocalDate.class));
                if (acc != null) {
                    acc.touches++;
                    String channel = rs.getString(2);
                    if ("MEETING".equals(channel) || "MEETING_BOOKED".equals(rs.getString(3))) {
                        acc.meetings++;
                    }
                    String who = rs.getString(4) == null ? rs.getString(5) : rs.getString(4) + " at " + rs.getString(5);
                    Long conversation = rs.getObject(7, Long.class);
                    acc.add(new Item(channel, label(channel) + ": " + who,
                        conversation != null ? "/conversations/" + conversation : "/accounts/" + rs.getLong(6)));
                }
                return null;
            }).list();

        // Conversations started (already counted as touches through their first activity).
        jdbc.sql("""
                SELECT DATE(created_at) FROM conversation
                WHERE created_at >= :from AND DATE(created_at) <= :to AND (:user IS NULL OR owner_user_id = :user)
                """).param("from", from).param("to", today).param("user", userId)
            .query((rs, n) -> {
                Acc acc = days.get(rs.getObject(1, LocalDate.class));
                if (acc != null) {
                    acc.conversationsStarted++;
                }
                return null;
            }).list();

        // New connections: for one person, the day they connected; for the team, the first day anyone did.
        String connections = userId == null
            ? """
              SELECT MIN(k.connected_on), n.id, n.full_name FROM connection_knower k JOIN connection n ON n.id = k.connection_id
              GROUP BY n.id, n.full_name HAVING MIN(k.connected_on) BETWEEN :from AND :to
              """
            : """
              SELECT k.connected_on, n.id, n.full_name FROM connection_knower k JOIN connection n ON n.id = k.connection_id
              WHERE k.user_id = :user AND k.connected_on BETWEEN :from AND :to
              """;
        var connectionQuery = jdbc.sql(connections).param("from", from).param("to", today);
        if (userId != null) {
            connectionQuery = connectionQuery.param("user", userId);
        }
        connectionQuery.query((rs, n) -> {
            Acc acc = days.get(rs.getObject(1, LocalDate.class));
            if (acc != null) {
                acc.connections++;
                acc.add(new Item("CONNECTION", "New connection: " + rs.getString(3), "/connections/" + rs.getLong(2)));
            }
            return null;
        }).list();

        // Events someone from the team went to, on the day they started.
        jdbc.sql("""
                SELECT e.starts_on, e.id, e.name FROM bd_event e
                WHERE e.starts_on BETWEEN :from AND :to
                  AND EXISTS (SELECT 1 FROM bd_event_attendee x WHERE x.event_id = e.id AND (:user IS NULL OR x.user_id = :user))
                """).param("from", from).param("to", today).param("user", userId)
            .query((rs, n) -> {
                Acc acc = days.get(rs.getObject(1, LocalDate.class));
                if (acc != null) {
                    acc.events++;
                    acc.add(new Item("EVENT", "Event: " + rs.getString(3), "/events/" + rs.getLong(2)));
                }
                return null;
            }).list();

        // Leader interviews recorded or published (backlog item 8), by their owner.
        jdbc.sql("""
                SELECT recorded_on, id, leader_name, 'recorded' FROM interview
                WHERE recorded_on BETWEEN :from AND :to AND (:user IS NULL OR owner_user_id = :user)
                UNION ALL
                SELECT published_on, id, leader_name, 'published' FROM interview
                WHERE published_on BETWEEN :from AND :to AND (:user IS NULL OR owner_user_id = :user)
                """).param("from", from).param("to", today).param("user", userId)
            .query((rs, n) -> {
                Acc acc = days.get(rs.getObject(1, LocalDate.class));
                if (acc != null) {
                    acc.interviews++;
                    acc.add(new Item("INTERVIEW", "Interview " + rs.getString(4) + ": " + rs.getString(3),
                        "/interviews/" + rs.getLong(2)));
                }
                return null;
            }).list();

        List<Day> dayList = new ArrayList<>();
        Map<LocalDate, Integer> byWeek = new TreeMap<>();
        int activeLast30 = 0;
        int total = 0;
        for (var entry : days.entrySet()) {
            Acc a = entry.getValue();
            int dayTotal = a.touches + a.connections + a.events + a.interviews;
            dayList.add(new Day(entry.getKey(), dayTotal, a.touches, a.meetings, a.conversationsStarted, a.connections,
                a.events, a.interviews, a.items, Math.max(0, a.itemCount - a.items.size())));
            byWeek.merge(entry.getKey().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), dayTotal, Integer::sum);
            if (dayTotal > 0 && !entry.getKey().isBefore(today.minusDays(29))) {
                activeLast30++;
            }
            total += dayTotal;
        }
        List<Week> weekList = byWeek.entrySet().stream().map(e -> new Week(e.getKey(), e.getValue())).toList();
        Week best = weekList.stream().filter(w -> w.total() > 0).max(Comparator.comparingInt(Week::total)).orElse(null);
        Summary summary = new Summary(total, byWeek.getOrDefault(thisMonday, 0), byWeek.getOrDefault(thisMonday.minusWeeks(1), 0),
            activeLast30, best);
        List<Person> people = jdbc.sql("""
                SELECT u.id, u.full_name FROM app_user u
                WHERE EXISTS (SELECT 1 FROM activity x WHERE x.user_id = u.id)
                   OR EXISTS (SELECT 1 FROM connection_knower k WHERE k.user_id = u.id)
                   OR EXISTS (SELECT 1 FROM bd_event_attendee e WHERE e.user_id = u.id)
                   OR EXISTS (SELECT 1 FROM interview i WHERE i.owner_user_id = u.id)
                ORDER BY u.full_name
                """).query((rs, n) -> new Person(rs.getLong(1), rs.getString(2))).list();
        return new Heatmap(from, today, dayList, weekList, summary, people);
    }

    private static String label(String channel) {
        return switch (channel) {
            case "LINKEDIN_CONNECTION" -> "LinkedIn connection request";
            case "LINKEDIN_MESSAGE" -> "LinkedIn message";
            case "INMAIL" -> "InMail";
            case "EMAIL" -> "Email";
            case "CALL" -> "Call";
            case "MEETING" -> "Meeting";
            case "EVENT" -> "At an event";
            case "REFERRAL" -> "Referral";
            default -> channel;
        };
    }

    /** Running counts for one day. */
    private static final class Acc {
        int touches;
        int meetings;
        int conversationsStarted;
        int connections;
        int events;
        int interviews;
        int itemCount;
        final List<Item> items = new ArrayList<>();

        void add(Item item) {
            itemCount++;
            if (items.size() < MAX_ITEMS_PER_DAY) {
                items.add(item);
            }
        }
    }
}
