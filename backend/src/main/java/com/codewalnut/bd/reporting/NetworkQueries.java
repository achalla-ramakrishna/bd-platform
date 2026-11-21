package com.codewalnut.bd.reporting;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.codewalnut.bd.shared.NotFoundException;

/** Reads GTN tracks, their timelines and the counts by stage (ADR-0017). */
@Service
public class NetworkQueries {

    public static final List<String> STAGES = List.of("INITIATE", "RESPONDED", "UNRESPONSIVE", "INTRO_MADE",
        "CALL_SCHEDULED", "WARM_COMMITMENT", "MONTHLY_BEEP");

    /** One person on GTN, with what the board, the list and Today need. {@code howWeKnow}: as the owner put it. */
    public record Track(long id, long connectionId, String fullName, String company, String role, String linkedinUrl,
                        String email, String whatsapp, String sourceTag, Long photoVersion, long ownerId, String ownerName,
                        String stage, Instant stageSince, long daysInStage, String whyReason, String nextAction,
                        LocalDate nextActionOn, boolean overdue, LocalDate lastOutOn, LocalDate lastInOn,
                        String lastChannel, int nudgesSent, String revisitReason, int beepIntervalDays, LocalDate callOn,
                        String howWeKnow, String lastTopic, int version, boolean askNow, Instant visitedAt,
                        int bestVideoPct, Instant interestedAt) {}

    public record Touch(long id, String channel, String direction, String kind, String summary, LocalDate occurredOn,
                        String userName, Long templateId, Instant createdAt) {}

    public record StageChange(long id, String fromStage, String toStage, String changedBy, Instant changedAt, String note) {}

    public record Commitment(long id, String kind, String description, String recordedBy, Instant recordedAt) {}

    public record Detail(Track track, List<Touch> touches, List<StageChange> changes, List<Commitment> commitments) {}

    /** {@code byStage}: tracks per stage; {@code due}: next action due today or earlier; {@code overdue}: before today. */
    public record Counts(Map<String, Long> byStage, long total, long due, long overdue) {}

    private static final String SELECT = """
        SELECT t.id, t.connection_id, n.full_name, n.current_company, n.current_role, n.linkedin_url, n.email, n.whatsapp,
            n.source_tag, ph.updated_at, t.owner_user_id, u.full_name, t.stage, t.stage_since, t.why_reason, t.next_action,
            t.next_action_on, t.last_out_on, t.last_in_on, t.last_channel, t.nudges_sent, t.revisit_reason,
            t.beep_interval_days, t.call_on, k.how_known,
            (SELECT x.summary FROM network_touch x WHERE x.track_id = t.id ORDER BY x.occurred_on DESC, x.id DESC LIMIT 1),
            t.version, t.ask_now, t.visited_at, t.best_video_pct, t.interested_at
        FROM network_track t
        JOIN connection n ON n.id = t.connection_id
        JOIN app_user u ON u.id = t.owner_user_id
        LEFT JOIN connection_photo ph ON ph.connection_id = n.id
        LEFT JOIN connection_knower k ON k.connection_id = n.id AND k.user_id = t.owner_user_id
        """;

    private final JdbcClient jdbc;

    NetworkQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Track get(long id, LocalDate today) {
        return jdbc.sql(SELECT + " WHERE t.id = :id").param("id", id).query((rs, n) -> map(rs, today)).optional()
            .orElseThrow(() -> new NotFoundException("Network track", id));
    }

    public Optional<Long> forConnection(long connectionId) {
        return jdbc.sql("SELECT id FROM network_track WHERE connection_id = :c").param("c", connectionId)
            .query(Long.class).optional();
    }

    public Detail detail(long id, LocalDate today) {
        Track track = get(id, today);
        List<Touch> touches = jdbc.sql("""
                SELECT x.id, x.channel, x.direction, x.kind, x.summary, x.occurred_on, u.full_name, x.template_id, x.created_at
                FROM network_touch x JOIN app_user u ON u.id = x.user_id
                WHERE x.track_id = :id ORDER BY x.occurred_on DESC, x.id DESC
                """).param("id", id)
            .query((rs, n) -> new Touch(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getObject(6, LocalDate.class), rs.getString(7), rs.getObject(8, Long.class), rs.getTimestamp(9).toInstant()))
            .list();
        List<StageChange> changes = jdbc.sql("""
                SELECT c.id, c.from_stage, c.to_stage, u.full_name, c.changed_at, c.note
                FROM network_stage_change c LEFT JOIN app_user u ON u.id = c.changed_by
                WHERE c.track_id = :id ORDER BY c.changed_at DESC, c.id DESC
                """).param("id", id)
            .query((rs, n) -> new StageChange(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4) == null ? "Automatic" : rs.getString(4), rs.getTimestamp(5).toInstant(), rs.getString(6)))
            .list();
        List<Commitment> commitments = jdbc.sql("""
                SELECT c.id, c.kind, c.description, u.full_name, c.recorded_at
                FROM network_commitment c JOIN app_user u ON u.id = c.recorded_by
                WHERE c.track_id = :id ORDER BY c.recorded_at DESC
                """).param("id", id)
            .query((rs, n) -> new Commitment(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getTimestamp(5).toInstant()))
            .list();
        return new Detail(track, touches, changes, commitments);
    }

    /**
     * The board and list (GTN FR-2). Filters are optional: stage, owner, source tag, Why? reason, a search on
     * name, company or role, and only overdue.
     */
    public List<Track> list(String stage, Long ownerId, String source, String reason, String q, boolean overdueOnly,
                            LocalDate today) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        Map<String, Object> params = new LinkedHashMap<>();
        if (stage != null && !stage.isBlank()) {
            where.append(" AND t.stage = :stage");
            params.put("stage", stage.trim().toUpperCase(Locale.ROOT));
        }
        if (ownerId != null) {
            where.append(" AND t.owner_user_id = :owner");
            params.put("owner", ownerId);
        }
        if (source != null && !source.isBlank()) {
            where.append(" AND n.source_tag = :source");
            params.put("source", source.trim());
        }
        if (reason != null && !reason.isBlank()) {
            where.append(" AND t.why_reason = :reason");
            params.put("reason", reason.trim().toUpperCase(Locale.ROOT));
        }
        if (q != null && !q.isBlank()) {
            where.append(" AND (n.full_name LIKE :q OR n.current_company LIKE :q OR n.current_role LIKE :q)");
            params.put("q", "%" + q.trim() + "%");
        }
        if (overdueOnly) {
            where.append(" AND t.next_action_on < :today");
            params.put("today", today);
        }
        return jdbc.sql(SELECT + where + " ORDER BY t.next_action_on, n.full_name LIMIT 2000").params(params)
            .query((rs, n) -> map(rs, today)).list();
    }

    /** Tracks whose next action is due today or earlier, soonest first; everyone's when {@code ownerId} is null. */
    public List<Track> due(Long ownerId, LocalDate today) {
        return jdbc.sql(SELECT + " WHERE t.next_action_on <= :today AND (:owner IS NULL OR t.owner_user_id = :owner)"
                + " ORDER BY t.next_action_on, n.full_name")
            .param("today", today).param("owner", ownerId).query((rs, n) -> map(rs, today)).list();
    }

    public Counts counts(Long ownerId, LocalDate today) {
        Map<String, Long> byStage = new LinkedHashMap<>();
        STAGES.forEach(s -> byStage.put(s, 0L));
        jdbc.sql("SELECT stage, COUNT(*) FROM network_track WHERE (:owner IS NULL OR owner_user_id = :owner) GROUP BY stage")
            .param("owner", ownerId).query((rs, n) -> byStage.put(rs.getString(1), rs.getLong(2))).list();
        long[] due = jdbc.sql("""
                SELECT COALESCE(SUM(next_action_on <= :today), 0), COALESCE(SUM(next_action_on < :today), 0)
                FROM network_track WHERE (:owner IS NULL OR owner_user_id = :owner)
                """).param("today", today).param("owner", ownerId)
            .query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)}).single();
        return new Counts(byStage, byStage.values().stream().mapToLong(Long::longValue).sum(), due[0], due[1]);
    }

    // ---- dashboard (GTN M10, ADR-0019) ----

    /** Of the people started on GTN in the period: how far they got. */
    public record Cohort(long started, long responded, long intro, long call, long warm) {}

    /** People given a Why? reason: replied after going quiet, parked in monthly beep, or still quiet. */
    public record ReasonRow(String reason, long total, long replied, long parked, long stillQuiet) {}

    public record SourceRow(String source, long started, long responded, long warm) {}

    public record ProfileStats(long links, long visitors, long watched75, long ctaClicks, long interested) {}

    public record Dashboard(LocalDate from, LocalDate to, Map<String, Long> byStage, long total, Cohort cohort,
                            long commitments, List<ReasonRow> reasons, long nudges, long nudgedThenReplied, long beepsSent,
                            long overdue, long missedBeeps, ProfileStats profile, List<SourceRow> sources) {}

    private static final String SCOPE = " FROM network_track t JOIN connection n ON n.id = t.connection_id"
        + " WHERE (:owner IS NULL OR t.owner_user_id = :owner) AND (:source IS NULL OR n.source_tag = :source)";

    private static String reached(String stages) {
        return "EXISTS (SELECT 1 FROM network_stage_change c WHERE c.track_id = t.id AND c.to_stage IN (" + stages + "))";
    }

    public Dashboard dashboard(LocalDate from, LocalDate to, Long ownerId, String source, LocalDate today) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("owner", ownerId);
        p.put("source", source == null || source.isBlank() ? null : source.trim());
        p.put("from", from.atStartOfDay());
        p.put("to", to.plusDays(1).atStartOfDay());
        p.put("fromDay", from);
        p.put("toDay", to);
        p.put("today", today);

        Map<String, Long> byStage = new LinkedHashMap<>();
        STAGES.forEach(st -> byStage.put(st, 0L));
        jdbc.sql("SELECT t.stage, COUNT(*)" + SCOPE + " GROUP BY t.stage").params(p)
            .query((rs, n) -> byStage.put(rs.getString(1), rs.getLong(2))).list();

        String replied = "(EXISTS (SELECT 1 FROM network_touch x WHERE x.track_id = t.id AND x.kind = 'REPLY') OR "
            + reached("'RESPONDED'") + ")";
        String warm = "(" + reached("'WARM_COMMITMENT'") + " OR EXISTS (SELECT 1 FROM network_commitment m WHERE m.track_id = t.id))";
        Cohort cohort = jdbc.sql("SELECT COUNT(*), COALESCE(SUM(" + replied + "), 0), COALESCE(SUM(" + reached("'INTRO_MADE'")
                + "), 0), COALESCE(SUM(" + reached("'CALL_SCHEDULED'") + "), 0), COALESCE(SUM(" + warm + "), 0)" + SCOPE
                + " AND t.created_at >= :from AND t.created_at < :to").params(p)
            .query((rs, n) -> new Cohort(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5))).single();

        long commitments = jdbc.sql("SELECT COUNT(*) FROM network_commitment m JOIN network_track t ON t.id = m.track_id"
                + " JOIN connection n ON n.id = t.connection_id WHERE (:owner IS NULL OR t.owner_user_id = :owner)"
                + " AND (:source IS NULL OR n.source_tag = :source) AND m.recorded_at >= :from AND m.recorded_at < :to")
            .params(p).query(Long.class).single();

        String repliedAfterQuiet = "EXISTS (SELECT 1 FROM network_touch x WHERE x.track_id = t.id AND x.kind = 'REPLY' AND x.occurred_on >="
            + " (SELECT MIN(DATE(c.changed_at)) FROM network_stage_change c WHERE c.track_id = t.id AND c.to_stage = 'UNRESPONSIVE'))";
        List<ReasonRow> reasons = jdbc.sql("SELECT t.why_reason, COUNT(*), COALESCE(SUM(" + repliedAfterQuiet + "), 0),"
                + " COALESCE(SUM(t.stage = 'MONTHLY_BEEP' AND NOT " + repliedAfterQuiet + "), 0), COALESCE(SUM(t.stage = 'UNRESPONSIVE'), 0)"
                + SCOPE + " AND t.why_reason IS NOT NULL GROUP BY t.why_reason"
                + " ORDER BY FIELD(t.why_reason, 'NOT_SEEN', 'INERTIA', 'NOT_RIGHT_TIME')")
            .params(p).query((rs, n) -> new ReasonRow(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)))
            .list();

        long[] touches = jdbc.sql("SELECT COALESCE(SUM(x.kind = 'NUDGE'), 0), COALESCE(SUM(x.kind = 'BEEP'), 0),"
                + " COUNT(DISTINCT CASE WHEN x.kind = 'NUDGE' AND EXISTS (SELECT 1 FROM network_touch r WHERE r.track_id = x.track_id"
                + " AND r.kind = 'REPLY' AND r.occurred_on >= x.occurred_on) THEN x.track_id END)"
                + " FROM network_touch x JOIN network_track t ON t.id = x.track_id JOIN connection n ON n.id = t.connection_id"
                + " WHERE (:owner IS NULL OR t.owner_user_id = :owner) AND (:source IS NULL OR n.source_tag = :source)"
                + " AND x.occurred_on BETWEEN :fromDay AND :toDay")
            .params(p).query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3)}).single();

        long[] late = jdbc.sql("SELECT COALESCE(SUM(t.next_action_on < :today), 0),"
                + " COALESCE(SUM(t.next_action_on < :today AND t.stage IN ('MONTHLY_BEEP', 'WARM_COMMITMENT')), 0)" + SCOPE)
            .params(p).query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)}).single();

        ProfileStats profile = jdbc.sql("SELECT COUNT(DISTINCT CASE WHEN l.created_at >= :from AND l.created_at < :to THEN l.id END),"
                + " COUNT(DISTINCT CASE WHEN e.kind = 'VISIT' THEN l.id END),"
                + " COUNT(DISTINCT CASE WHEN e.kind = 'VIDEO_PROGRESS' AND e.value IN ('75', '100') THEN l.id END),"
                + " COALESCE(SUM(e.kind = 'CTA_CLICK'), 0), COUNT(DISTINCT CASE WHEN e.kind = 'INTERESTED' THEN l.id END)"
                + " FROM profile_link l JOIN connection n ON n.id = l.connection_id"
                + " LEFT JOIN network_track t ON t.connection_id = l.connection_id"
                + " LEFT JOIN profile_event e ON e.link_id = l.id AND e.occurred_at >= :from AND e.occurred_at < :to"
                + " WHERE (:owner IS NULL OR COALESCE(t.owner_user_id, l.owner_user_id) = :owner)"
                + " AND (:source IS NULL OR n.source_tag = :source)")
            .params(p).query((rs, n) -> new ProfileStats(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)))
            .single();

        List<SourceRow> sources = jdbc.sql("SELECT COALESCE(n.source_tag, ''), COUNT(*), COALESCE(SUM(" + replied + "), 0),"
                + " COALESCE(SUM(" + warm + "), 0)" + SCOPE + " AND t.created_at >= :from AND t.created_at < :to"
                + " GROUP BY COALESCE(n.source_tag, '') ORDER BY COUNT(*) DESC LIMIT 20")
            .params(p).query((rs, n) -> new SourceRow(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4))).list();

        return new Dashboard(from, to, byStage, byStage.values().stream().mapToLong(Long::longValue).sum(), cohort, commitments,
            reasons, touches[0], touches[2], touches[1], late[0], late[1], profile, sources);
    }

    private static Track map(java.sql.ResultSet rs, LocalDate today) throws java.sql.SQLException {
        Instant since = rs.getTimestamp(14).toInstant();
        LocalDate next = rs.getObject(17, LocalDate.class);
        return new Track(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
            rs.getString(7), rs.getString(8), rs.getString(9), rs.getTimestamp(10) == null ? null : rs.getTimestamp(10).getTime(),
            rs.getLong(11), rs.getString(12), rs.getString(13), since,
            Math.max(0, ChronoUnit.DAYS.between(since.atZone(java.time.ZoneId.systemDefault()).toLocalDate(), today)),
            rs.getString(15), rs.getString(16), next, next.isBefore(today), rs.getObject(18, LocalDate.class),
            rs.getObject(19, LocalDate.class), rs.getString(20), rs.getInt(21), rs.getString(22), rs.getInt(23),
            rs.getObject(24, LocalDate.class), rs.getString(25), rs.getString(26), rs.getInt(27), rs.getBoolean(28),
            rs.getTimestamp(29) == null ? null : rs.getTimestamp(29).toInstant(), rs.getInt(30),
            rs.getTimestamp(31) == null ? null : rs.getTimestamp(31).toInstant());
    }
}
