package com.codewalnut.bd.reporting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * What Walnut, the BD agent, knows about one person's BD work on a day (ADR-0015): their follow-ups, deals,
 * the best accounts nobody is talking to yet, events, interviews and how the team did last week. Read-only.
 */
@Component
public class BdSnapshot {

    public record Person(long id, String name, String email) {}

    public record FollowUp(long conversationId, String person, String account, long accountId, String topic,
                           String status, String nextStep, LocalDate due, LocalDate lastTouchOn) {}

    public record Deal(long opportunityId, String name, String account, String stage, BigDecimal valueInr,
                       LocalDate expectedClose, List<String> missing) {}

    public record Target(long accountId, String name, String city, int fitScore, List<String> reasons,
                         String whoToContact, String openingMessage, long warmPaths) {}

    public record EventItem(long eventId, String name, LocalDate startsOn, String city, String ourRole, boolean going) {}

    public record InterviewItem(long interviewId, String leader, String topic, String status, LocalDate scheduledOn) {}

    public record TeamWeek(String name, long touches, long started, long meetings, long connections) {}

    /**
     * Where one conversation is in the BD life cycle (ADR-0015): REQUEST_SENT (LinkedIn invitation not yet
     * accepted), CONNECTED (accepted, no message yet), MESSAGED (waiting for a reply), REPLIED (book a meeting),
     * MEETING (turn it into an opportunity). {@code since} is the date of the last touch.
     */
    public record Step(long conversationId, String person, String account, long accountId, String stage,
                       LocalDate since, String openingMessage) {}

    public record Snapshot(LocalDate today, Person me, List<Person> team, List<FollowUp> overdue,
                           List<FollowUp> dueSoon, List<FollowUp> quiet, List<Deal> deals, List<Target> targets,
                           List<EventItem> events, List<InterviewItem> interviews, List<TeamWeek> teamWeek,
                           String strategyExcerpt, List<Step> lifecycle,
                           List<FulfilmentQueries.RequirementStatus> requirements,
                           List<FulfilmentQueries.Candidate> candidates) {}

    static final int QUIET_DAYS = 14;
    static final int TARGETS = 5;

    private final JdbcClient jdbc;
    private final FitScorer fit;
    private final FulfilmentQueries fulfilment;

    BdSnapshot(JdbcClient jdbc, FitScorer fit, FulfilmentQueries fulfilment) {
        this.jdbc = jdbc;
        this.fit = fit;
        this.fulfilment = fulfilment;
    }

    /** Active people holding any of the roles (Walnut's audience), in a stable order. */
    public List<Person> team(Collection<String> roles) {
        if (roles.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT DISTINCT u.id, u.full_name, u.email FROM app_user u JOIN user_role r ON r.user_id = u.id
                WHERE u.active AND r.role IN (:roles) ORDER BY u.id
                """).param("roles", roles)
            .query((rs, n) -> new Person(rs.getLong(1), rs.getString(2), rs.getString(3))).list();
    }

    public Snapshot of(Person me, List<Person> team, LocalDate today) {
        return new Snapshot(today, me, team,
            followUps(me.id(), "v.next_step_due < :today", "v.next_step_due", today, 8),
            followUps(me.id(), "v.next_step_due BETWEEN :today AND :soon", "v.next_step_due", today, 8),
            followUps(me.id(), "v.next_step_due IS NULL AND v.last_touch_on < :quiet", "v.last_touch_on", today, 5),
            deals(me.id(), today), targets(me, team), events(me.id(), today), interviews(me.id()),
            teamWeek(team, today), strategy(), lifecycle(me.id()),
            fulfilment.requirements(me.id(), null, false), fulfilment.activeFor(me.id()));
    }

    static final java.util.Set<String> MESSAGE_CHANNELS = java.util.Set.of("LINKEDIN_MESSAGE", "INMAIL", "EMAIL", "CALL", "MEETING");

    /** The stage of each open conversation, from its latest touch and whether the person is now a connection. */
    private List<Step> lifecycle(long owner) {
        return jdbc.sql("""
                SELECT v.id, COALESCE(c.full_name, v.person_name), a.name, a.id, v.status, v.opportunity_id,
                    la.channel, la.outcome, COALESCE(la.occurred_on, v.last_touch_on),
                    EXISTS (SELECT 1 FROM connection n WHERE n.account_id = v.account_id
                        AND LOWER(n.full_name) = LOWER(COALESCE(c.full_name, v.person_name))),
                    EXISTS (SELECT 1 FROM activity m WHERE m.conversation_id = v.id
                        AND m.channel IN ('LINKEDIN_MESSAGE', 'INMAIL', 'EMAIL', 'CALL', 'MEETING')),
                    JSON_UNQUOTE(JSON_EXTRACT(r.summary, '$.openingMessage'))
                FROM conversation v JOIN account a ON a.id = v.account_id LEFT JOIN contact c ON c.id = v.contact_id
                LEFT JOIN activity la ON la.id = (SELECT MAX(x.id) FROM activity x WHERE x.conversation_id = v.id)
                LEFT JOIN account_research r ON r.id = (SELECT MAX(y.id) FROM account_research y
                    WHERE y.account_id = a.id AND y.status = 'DONE')
                WHERE v.owner_user_id = :owner AND v.status NOT IN ('CLOSED', 'GONE_QUIET')
                ORDER BY COALESCE(la.occurred_on, v.last_touch_on), v.id LIMIT 40
                """).param("owner", owner)
            .query((rs, n) -> {
                String status = rs.getString(5);
                boolean hasOpportunity = rs.getObject(6) != null;
                String channel = rs.getString(7);
                String outcome = rs.getString(8);
                boolean connected = rs.getBoolean(10);
                boolean messaged = rs.getBoolean(11);
                String stage;
                if (hasOpportunity || "OPPORTUNITY".equals(status)) {
                    stage = null;
                } else if ("MEETING_BOOKED".equals(status)) {
                    stage = "MEETING";
                } else if ("REPLIED".equals(status) && messaged) {
                    stage = "REPLIED";
                } else if (messaged) {
                    stage = "MESSAGED";
                } else if (connected || "LINKEDIN_CONNECTION".equals(channel) && !"NO_REPLY".equals(outcome)) {
                    stage = "CONNECTED";
                } else if ("LINKEDIN_CONNECTION".equals(channel)) {
                    stage = "REQUEST_SENT";
                } else {
                    stage = "MESSAGED";
                }
                return stage == null ? null : new Step(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                    stage, rs.getObject(9, LocalDate.class), rs.getString(12));
            })
            .list().stream().filter(java.util.Objects::nonNull).toList();
    }

    private List<FollowUp> followUps(long owner, String condition, String order, LocalDate today, int limit) {
        return jdbc.sql("""
                SELECT v.id, COALESCE(c.full_name, v.person_name), a.name, a.id, v.topic, v.status, v.next_step,
                    v.next_step_due, v.last_touch_on
                FROM conversation v JOIN account a ON a.id = v.account_id LEFT JOIN contact c ON c.id = v.contact_id
                WHERE v.owner_user_id = :owner AND v.status NOT IN ('CLOSED', 'GONE_QUIET') AND\s""" + condition
                + " ORDER BY " + order + ", v.id LIMIT " + limit)
            .param("owner", owner).param("today", today).param("soon", today.plusDays(2))
            .param("quiet", today.minusDays(QUIET_DAYS))
            .query((rs, n) -> new FollowUp(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getObject(8, LocalDate.class),
                rs.getObject(9, LocalDate.class)))
            .list();
    }

    private List<Deal> deals(long owner, LocalDate today) {
        return jdbc.sql("""
                SELECT o.id, o.name, a.name, s.label, o.value_inr, o.expected_close_date,
                    o.decision_maker_contact_id IS NULL, COALESCE(TRIM(o.need_summary), '') = '', o.target_start_date IS NULL
                FROM opportunity o JOIN account a ON a.id = o.account_id JOIN opportunity_stage s ON s.code = o.stage_code
                WHERE o.owner_user_id = :owner AND s.outcome = 'OPEN'
                ORDER BY o.expected_close_date IS NULL, o.expected_close_date, o.id LIMIT 8
                """).param("owner", owner)
            .query((rs, n) -> {
                List<String> missing = new ArrayList<>();
                if (rs.getBoolean(7)) missing.add("decision maker");
                if (rs.getBoolean(8)) missing.add("the client's need");
                if (rs.getBoolean(9)) missing.add("target start date");
                LocalDate close = rs.getObject(6, LocalDate.class);
                if (close != null && close.isBefore(today)) missing.add("a new close date (the old one has passed)");
                return new Deal(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBigDecimal(5),
                    close, missing);
            })
            .list();
    }

    /**
     * The best-fitting prospects nobody has a conversation with yet. Ranked by fit score and dealt out in turn
     * to the team, so two people are never sent to the same account on the same day.
     */
    private List<Target> targets(Person me, List<Person> team) {
        record Row(long id, String name, String city, String who, String opening, long warm) {}
        List<Row> rows = jdbc.sql("""
                SELECT a.id, a.name, a.city,
                    JSON_UNQUOTE(JSON_EXTRACT(r.summary, '$.whoToContact[0].persona')),
                    JSON_UNQUOTE(JSON_EXTRACT(r.summary, '$.openingMessage')),
                    (SELECT COUNT(*) FROM connection n WHERE n.account_id = a.id)
                FROM account a
                LEFT JOIN account_research r ON r.id = (SELECT MAX(x.id) FROM account_research x
                    WHERE x.account_id = a.id AND x.status = 'DONE')
                WHERE a.relationship = 'PROSPECT'
                  AND NOT EXISTS (SELECT 1 FROM conversation v WHERE v.account_id = a.id)
                """)
            .query((rs, n) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getLong(6)))
            .list();
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, FitScorer.Fit> fits = fit.score(rows.stream().map(Row::id).toList());
        List<Row> ranked = rows.stream()
            .sorted(Comparator.comparingInt((Row r) -> fits.containsKey(r.id()) ? -fits.get(r.id()).score() : 0)
                .thenComparing(Row::name))
            .toList();
        int members = Math.max(team.size(), 1);
        int mine = Math.max(team.stream().map(Person::id).toList().indexOf(me.id()), 0);
        List<Target> out = new ArrayList<>();
        for (int i = mine; i < ranked.size() && out.size() < TARGETS; i += members) {
            Row r = ranked.get(i);
            FitScorer.Fit f = fits.get(r.id());
            out.add(new Target(r.id(), r.name(), r.city(), f == null ? 0 : f.score(),
                f == null ? List.of() : f.reasons().stream().limit(3).toList(), r.who(), r.opening(), r.warm()));
        }
        return out;
    }

    private List<EventItem> events(long me, LocalDate today) {
        return jdbc.sql("""
                SELECT e.id, e.name, e.starts_on, e.city, e.our_role,
                    EXISTS (SELECT 1 FROM bd_event_attendee x WHERE x.event_id = e.id AND x.user_id = :me)
                FROM bd_event e WHERE COALESCE(e.ends_on, e.starts_on) >= :today AND e.starts_on <= :until
                ORDER BY e.starts_on LIMIT 6
                """).param("me", me).param("today", today).param("until", today.plusDays(21))
            .query((rs, n) -> new EventItem(rs.getLong(1), rs.getString(2), rs.getObject(3, LocalDate.class),
                rs.getString(4), rs.getString(5), rs.getBoolean(6)))
            .list();
    }

    private List<InterviewItem> interviews(long me) {
        return jdbc.sql("""
                SELECT id, leader_name, topic, status, scheduled_on FROM interview
                WHERE owner_user_id = :me AND status NOT IN ('PUBLISHED', 'DECLINED') ORDER BY scheduled_on IS NULL, scheduled_on, id LIMIT 5
                """).param("me", me)
            .query((rs, n) -> new InterviewItem(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getObject(5, LocalDate.class)))
            .list();
    }

    /** The last seven days, per person: touches logged, conversations started, meetings booked, connections made. */
    private List<TeamWeek> teamWeek(List<Person> team, LocalDate today) {
        LocalDate from = today.minusDays(7);
        List<TeamWeek> out = new ArrayList<>();
        for (Person p : team) {
            long[] c = jdbc.sql("""
                    SELECT (SELECT COUNT(*) FROM activity WHERE user_id = :u AND occurred_on > :from),
                        (SELECT COUNT(*) FROM conversation WHERE owner_user_id = :u AND created_at > :from),
                        (SELECT COUNT(*) FROM activity WHERE user_id = :u AND occurred_on > :from AND outcome = 'MEETING_BOOKED'),
                        (SELECT COUNT(*) FROM connection_knower WHERE user_id = :u AND connected_on > :from)
                    """).param("u", p.id()).param("from", from)
                .query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)}).single();
            out.add(new TeamWeek(p.name(), c[0], c[1], c[2], c[3]));
        }
        return out;
    }

    private String strategy() {
        String body = jdbc.sql("SELECT body FROM strategy_version ORDER BY id DESC LIMIT 1").query(String.class)
            .optional().orElse("");
        return body.length() > 1500 ? body.substring(0, 1500) + "…" : body;
    }
}
