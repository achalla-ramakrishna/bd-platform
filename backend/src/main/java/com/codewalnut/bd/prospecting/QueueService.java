package com.codewalnut.bd.prospecting;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.codewalnut.bd.admin.UserDirectory;
import com.codewalnut.bd.assist.ProspectService;
import com.codewalnut.bd.engagement.Conversations;
import com.codewalnut.bd.network.NetworkService;
import com.codewalnut.bd.reporting.FitScorer;
import com.codewalnut.bd.shared.AuditLog;
import com.codewalnut.bd.shared.ConflictException;
import com.codewalnut.bd.shared.CurrentUser;
import com.codewalnut.bd.shared.NotFoundException;
import com.codewalnut.bd.shared.Role;
import com.codewalnut.bd.shared.RuleViolationException;

import org.springframework.security.access.AccessDeniedException;

/**
 * The daily prospecting queue (ADR-0023): each morning, the best-scored people a BD person can approach, with the
 * brief's note as the draft. The person sends on LinkedIn themselves and marks it sent, which starts a conversation
 * (a contact) or a GTN track (a connection); or skips with a reason. Drafts and sent notes are both kept.
 */
@Service
public class QueueService {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(QueueService.class);
    static final int BRIEF_FRESH_DAYS = 30;
    static final int SKIP_DAYS = 90;
    static final Set<String> SKIP_REASONS = Set.of("WRONG_PERSON", "NOT_NOW", "IN_TOUCH", "OTHER");
    static final int NOTE_MAX = 1000;

    /** One person in a queue, with what is needed to act on them. */
    public record Item(long id, String personType, long personId, String name, String title, String company,
                       String linkedinUrl, Long accountId, String accountName, int score, String headline, String whyNow,
                       String draftNote, String sentNote, String status, String skipReason, String skipNote,
                       Long conversationId, Long trackId, LocalDate queuedOn) {}

    /** Today's queue and the last 30 days of results. */
    public record Queue(LocalDate day, int size, List<Item> items, Stats last30Days, boolean researchEnabled, int researchLeftToday) {}

    public record Stats(int sent, int skipped, int expired, int edited, Map<String, Integer> skipReasons) {}

    public record Sent(String note) {}

    public record Skip(String reason, String note) {}

    public record Research(int started, String message) {}

    private final JdbcClient jdbc;
    private final ProspectService prospects;
    private final Conversations conversations;
    private final NetworkService network;
    private final FitScorer fit;
    private final UserDirectory users;
    private final AuditLog audit;
    private final int size;
    private final int minScore;
    private final int researchPerPerson;
    private final ZoneId zone;

    QueueService(JdbcClient jdbc, ProspectService prospects, Conversations conversations, NetworkService network, FitScorer fit,
                 UserDirectory users, AuditLog audit,
                 @Value("${app.agent.queue-size:20}") int size,
                 @Value("${app.agent.queue-min-score:50}") int minScore,
                 @Value("${app.agent.queue-research-per-person:5}") int researchPerPerson,
                 @Value("${app.gtn.zone:Asia/Kolkata}") String zone) {
        this.jdbc = jdbc;
        this.prospects = prospects;
        this.conversations = conversations;
        this.network = network;
        this.fit = fit;
        this.users = users;
        this.audit = audit;
        this.size = Math.max(1, size);
        this.minScore = minScore;
        this.researchPerPerson = Math.max(0, researchPerPerson);
        this.zone = ZoneId.of(zone);
    }

    public LocalDate today() {
        return LocalDate.now(zone);
    }

    // ---- The morning --------------------------------------------------------------------------------------------

    /** For everyone who can edit the pipeline: start research to feed the queue, then fill today's queue. */
    public void morning(LocalDate today) {
        List<String> emails = jdbc.sql("""
                SELECT DISTINCT u.email FROM app_user u JOIN user_role r ON r.user_id = u.id
                WHERE u.active AND r.role IN (:roles) ORDER BY u.email
                """).param("roles", Role.PIPELINE_EDITORS.stream().map(Enum::name).toList()).query(String.class).list();
        for (String email : emails) {
            users.findByEmail(email).ifPresent(user -> {
                try {
                    fill(user.id(), today);
                    research(user, researchPerPerson);
                } catch (RuntimeException e) {
                    LOG.warn("Prospecting queue for user {} failed: {}", user.id(), e.getMessage());
                }
            });
        }
    }

    /** Expires earlier days' untouched items and adds the best eligible people, up to the day's size. */
    @Transactional
    public int fill(long userId, LocalDate today) {
        jdbc.sql("UPDATE prospect_queue_item SET status = 'EXPIRED' WHERE status = 'QUEUED' AND queued_on < :d")
            .param("d", today).update();
        int already = jdbc.sql("SELECT COUNT(*) FROM prospect_queue_item WHERE user_id = :u AND queued_on = :d")
            .param("u", userId).param("d", today).query(Integer.class).single();
        int room = size - already;
        if (room <= 0) {
            return 0;
        }
        List<Map<String, Object>> picks = jdbc.sql("""
                SELECT b.id, b.person_type, b.person_id, b.account_id, b.score,
                    COALESCE(JSON_UNQUOTE(JSON_EXTRACT(b.brief, '$.connectionNote')), '') AS note
                FROM prospect_brief b
                WHERE b.status = 'DONE' AND b.score >= :min AND b.requested_at > NOW() - INTERVAL %d DAY
                  AND b.id = (SELECT MAX(x.id) FROM prospect_brief x WHERE x.person_type = b.person_type
                              AND x.person_id = b.person_id AND x.status = 'DONE')
                  AND (%s)
                  AND NOT EXISTS (%s)
                ORDER BY b.score DESC, b.id DESC
                LIMIT :n
                """.formatted(BRIEF_FRESH_DAYS, mine("b.person_type", "b.person_id"), done("b.person_type", "b.person_id")))
            .param("min", minScore).param("u", userId).param("n", room)
            .query().listOfRows();
        int added = 0;
        for (Map<String, Object> p : picks) {
            String note = (String) p.get("note");
            added += jdbc.sql("""
                    INSERT IGNORE INTO prospect_queue_item (user_id, queued_on, person_type, person_id, account_id, brief_id, score, draft_note)
                    VALUES (:u, :d, :t, :p, :a, :b, :s, :note)
                    """).param("u", userId).param("d", today).param("t", p.get("person_type")).param("p", p.get("person_id"))
                .param("a", p.get("account_id")).param("b", p.get("id")).param("s", p.get("score"))
                .param("note", note.length() > NOTE_MAX ? note.substring(0, NOTE_MAX) : note).update();
        }
        return added;
    }

    /**
     * People this user can approach: contacts at their accounts, connections they know or added. Not yet engaged.
     * The outer query must not use the aliases c, a, v, n, t or k for the person, or they are shadowed here.
     */
    private static String mine(String type, String id) {
        return """
            ((%1$s = 'CONTACT' AND EXISTS (SELECT 1 FROM contact c JOIN account a ON a.id = c.account_id
                 WHERE c.id = %2$s AND a.owner_user_id = :u AND NOT c.opted_out)
               AND NOT EXISTS (SELECT 1 FROM conversation v WHERE v.contact_id = %2$s)
               AND NOT EXISTS (SELECT 1 FROM connection n JOIN network_track t ON t.connection_id = n.id WHERE n.contact_id = %2$s))
            OR (%1$s = 'CONNECTION' AND EXISTS (SELECT 1 FROM connection n WHERE n.id = %2$s AND NOT n.opted_out AND (n.created_by = :u
                 OR EXISTS (SELECT 1 FROM connection_knower k WHERE k.connection_id = n.id AND k.user_id = :u))
                 AND (n.contact_id IS NULL OR NOT EXISTS (SELECT 1 FROM contact c WHERE c.id = n.contact_id AND c.opted_out))
                 AND (n.contact_id IS NULL OR NOT EXISTS (SELECT 1 FROM conversation v WHERE v.contact_id = n.contact_id)))
               AND NOT EXISTS (SELECT 1 FROM network_track t WHERE t.connection_id = %2$s)))
            """.formatted(type, id);
    }

    /** Already in a queue, sent from one, or skipped recently. */
    private static String done(String type, String id) {
        return """
            SELECT 1 FROM prospect_queue_item q WHERE q.person_type = %s AND q.person_id = %s
              AND (q.status IN ('QUEUED', 'SENT') OR (q.status = 'SKIPPED' AND q.acted_at > NOW() - INTERVAL %d DAY))
            """.formatted(type, id, SKIP_DAYS);
    }

    // ---- Research to feed the queue -----------------------------------------------------------------------------

    /** Starts research on the most promising people this user can approach who have no recent brief. */
    public Research research(CurrentUser user, int limit) {
        if (!prospects.enabled()) {
            return new Research(0, "The research agent is not switched on.");
        }
        int k = Math.min(limit, prospects.remainingToday());
        if (k <= 0) {
            return new Research(0, "Today's research limit is used up.");
        }
        String noBrief = """
            NOT EXISTS (SELECT 1 FROM prospect_brief b WHERE b.person_type = '%s' AND b.person_id = %s
              AND (b.status IN ('RUNNING', 'DONE') AND b.requested_at > NOW() - INTERVAL %d DAY
                   OR b.status = 'FAILED' AND b.requested_at > NOW() - INTERVAL 1 DAY))
            """;
        record Candidate(String type, long id, double priority) {}
        List<Candidate> candidates = new ArrayList<>();
        List<Map<String, Object>> contacts = jdbc.sql("""
                SELECT pc.id, pc.account_id FROM contact pc WHERE %s AND %s AND NOT EXISTS (%s) LIMIT 500
                """.formatted(mine("'CONTACT'", "pc.id"), noBrief.formatted("CONTACT", "pc.id", BRIEF_FRESH_DAYS),
                done("'CONTACT'", "pc.id")))
            .param("u", user.id()).query().listOfRows();
        Map<Long, FitScorer.Fit> fits = fit.score(contacts.stream().map(r -> ((Number) r.get("account_id")).longValue()).distinct().toList());
        for (Map<String, Object> r : contacts) {
            FitScorer.Fit f = fits.get(((Number) r.get("account_id")).longValue());
            candidates.add(new Candidate("CONTACT", ((Number) r.get("id")).longValue(), f == null ? 0 : f.score()));
        }
        // Connections: known closely first (above any contact), then well, then the rest.
        jdbc.sql("""
                SELECT pn.id, MIN(CASE pk.strength WHEN 'CLOSE' THEN 1 WHEN 'KNOW_WELL' THEN 2 ELSE 3 END) AS s
                FROM connection pn LEFT JOIN connection_knower pk ON pk.connection_id = pn.id
                WHERE %s AND %s AND NOT EXISTS (%s)
                GROUP BY pn.id LIMIT 500
                """.formatted(mine("'CONNECTION'", "pn.id"), noBrief.formatted("CONNECTION", "pn.id", BRIEF_FRESH_DAYS),
                done("'CONNECTION'", "pn.id")))
            .param("u", user.id())
            .query((rs, n) -> new Candidate("CONNECTION", rs.getLong(1), switch (rs.getInt(2)) {
                case 1 -> 1000;
                case 2 -> 60;
                default -> 30;
            })).list().forEach(candidates::add);
        candidates.sort(Comparator.comparingDouble(Candidate::priority).reversed().thenComparingLong(Candidate::id));
        int started = 0;
        for (Candidate c : candidates) {
            if (started >= k) {
                break;
            }
            try {
                prospects.start(c.type(), c.id(), false, user);
                started++;
            } catch (RuleViolationException e) {
                break; // the daily cap, or the agent switched off
            }
        }
        if (started == 0) {
            return new Research(0, candidates.isEmpty() ? "Everyone you can approach has a recent brief." : "Nothing started.");
        }
        return new Research(started, "Researching " + started + (started == 1 ? " person" : " people")
            + ". They join your queue in a few minutes if they score " + minScore + " or more.");
    }

    // ---- Reading ----------------------------------------------------------------------------------------------

    /** Today's queue (topped up first) and the last 30 days. */
    public Queue queue(CurrentUser user) {
        LocalDate today = today();
        fill(user.id(), today);
        List<Item> items = items("q.user_id = :u AND q.queued_on = :d", Map.of("u", user.id(), "d", today));
        return new Queue(today, size, items, stats(user.id()), prospects.enabled(), prospects.remainingToday());
    }

    private Stats stats(long userId) {
        var rows = jdbc.sql("""
                SELECT status, skip_reason, (sent_note IS NOT NULL AND sent_note <> draft_note) AS edited, COUNT(*)
                FROM prospect_queue_item WHERE user_id = :u AND queued_on > :since
                GROUP BY status, skip_reason, edited
                """).param("u", userId).param("since", today().minusDays(30))
            .query((rs, n) -> new Object[] {rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getInt(4)}).list();
        int sent = 0, skipped = 0, expired = 0, edited = 0;
        Map<String, Integer> reasons = new java.util.TreeMap<>();
        for (Object[] r : rows) {
            int n = (Integer) r[3];
            switch ((String) r[0]) {
                case "SENT" -> {
                    sent += n;
                    if ((Boolean) r[2]) edited += n;
                }
                case "SKIPPED" -> {
                    skipped += n;
                    reasons.merge((String) r[1], n, Integer::sum);
                }
                case "EXPIRED" -> expired += n;
                default -> { }
            }
        }
        return new Stats(sent, skipped, expired, edited, reasons);
    }

    private List<Item> items(String where, Map<String, Object> params) {
        return jdbc.sql("""
                SELECT q.id, q.person_type, q.person_id,
                    COALESCE(c.full_name, n.full_name), COALESCE(c.title, n.current_role),
                    COALESCE(ca.name, n.current_company), COALESCE(c.linkedin_url, n.linkedin_url),
                    q.account_id, a.name, q.score,
                    JSON_UNQUOTE(JSON_EXTRACT(b.brief, '$.headline')), JSON_UNQUOTE(JSON_EXTRACT(b.brief, '$.whyNow[0].statement')),
                    q.draft_note, q.sent_note, q.status, q.skip_reason, q.skip_note, q.conversation_id, q.track_id, q.queued_on
                FROM prospect_queue_item q
                JOIN prospect_brief b ON b.id = q.brief_id
                LEFT JOIN contact c ON q.person_type = 'CONTACT' AND c.id = q.person_id
                LEFT JOIN account ca ON ca.id = c.account_id
                LEFT JOIN connection n ON q.person_type = 'CONNECTION' AND n.id = q.person_id
                LEFT JOIN account a ON a.id = q.account_id
                WHERE\s""" + where + " ORDER BY q.status = 'QUEUED' DESC, q.score DESC, q.id")
            .params(params)
            .query((rs, n) -> new Item(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getObject(8, Long.class), rs.getString(9), rs.getInt(10), rs.getString(11),
                rs.getString(12), rs.getString(13), rs.getString(14), rs.getString(15), rs.getString(16), rs.getString(17),
                rs.getObject(18, Long.class), rs.getObject(19, Long.class), rs.getObject(20, LocalDate.class)))
            .list();
    }

    private Item item(long id) {
        return items("q.id = :id", Map.of("id", id)).stream().findFirst()
            .orElseThrow(() -> new NotFoundException("Queue item", id));
    }

    // ---- Acting -----------------------------------------------------------------------------------------------

    /** The person sent the note on LinkedIn: keep it, and start the follow-up where it belongs. */
    @Transactional
    public Item sent(long id, Sent in, CurrentUser user) {
        Item item = actable(id, user);
        String note = in == null || in.note() == null || in.note().isBlank() ? item.draftNote() : in.note().trim();
        if (note.isBlank()) {
            throw new RuleViolationException(List.of("Write the note you sent."));
        }
        if (note.length() > NOTE_MAX) {
            throw new RuleViolationException(List.of("The note can be at most " + NOTE_MAX + " characters."));
        }
        LocalDate today = today();
        Long conversationId = null;
        Long trackId = null;
        if ("CONTACT".equals(item.personType())) {
            Long accountId = jdbc.sql("SELECT account_id FROM contact WHERE id = :id").param("id", item.personId())
                .query(Long.class).single();
            conversationId = conversations.startFromLinkedInNote(accountId, item.personId(), note, today, user);
        } else {
            trackId = network.start(new NetworkService.Start(item.personId(), "LINKEDIN", "Connection request sent: " + note, null, today), user);
        }
        Map<String, Object> set = new java.util.HashMap<>();
        set.put("note", note);
        set.put("conv", conversationId);
        set.put("track", trackId);
        claim(id, "status = 'SENT', sent_note = :note, conversation_id = :conv, track_id = :track", set);
        audit.record("prospect_queue_item", id, AuditLog.Action.UPDATE, Map.of("status", "SENT", "edited", !note.equals(item.draftNote())), user.id());
        return item(id);
    }

    /** Not this one: why, so the agent can learn. They are not queued again for 90 days. */
    @Transactional
    public Item skip(long id, Skip in, CurrentUser user) {
        actable(id, user);
        String reason = in == null || in.reason() == null ? null : in.reason().trim().toUpperCase(java.util.Locale.ROOT);
        if (reason == null || !SKIP_REASONS.contains(reason)) {
            throw new RuleViolationException(List.of("Choose why: not the right person, not now, already in touch, or other."));
        }
        String note = in.note() == null || in.note().isBlank() ? null : in.note().trim();
        if (note != null && note.length() > 500) {
            throw new RuleViolationException(List.of("Keep the reason under 500 characters."));
        }
        Map<String, Object> set = new java.util.HashMap<>();
        set.put("reason", reason);
        set.put("note", note);
        claim(id, "status = 'SKIPPED', skip_reason = :reason, skip_note = :note", set);
        audit.record("prospect_queue_item", id, AuditLog.Action.UPDATE, Map.of("status", "SKIPPED", "reason", reason), user.id());
        return item(id);
    }

    private Item actable(long id, CurrentUser user) {
        user.require(Role.PIPELINE_EDITORS);
        Long owner = jdbc.sql("SELECT user_id FROM prospect_queue_item WHERE id = :id").param("id", id).query(Long.class)
            .optional().orElseThrow(() -> new NotFoundException("Queue item", id));
        if (owner != user.id() && !user.hasAnyRole(Role.NETWORK_MANAGERS)) {
            throw new AccessDeniedException("This is someone else's queue.");
        }
        Item item = item(id);
        if (!"QUEUED".equals(item.status())) {
            throw new ConflictException("This person was already " + item.status().toLowerCase(java.util.Locale.ROOT) + ".");
        }
        return item;
    }

    /** Moves a QUEUED item on; a second click (or another tab) gets a conflict instead of a second conversation. */
    private void claim(long id, String set, Map<String, Object> params) {
        var sql = jdbc.sql("UPDATE prospect_queue_item SET " + set + ", acted_at = CURRENT_TIMESTAMP(3), version = version + 1 "
            + "WHERE id = :id AND status = 'QUEUED'").param("id", id);
        for (var e : params.entrySet()) {
            sql = sql.param(e.getKey(), e.getValue());
        }
        if (sql.update() == 0) {
            throw new ConflictException("Someone acted on this person already. Reload the queue.");
        }
    }
}
