package com.codewalnut.bd.prospecting;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.codewalnut.bd.assist.ReplyReader;
import com.codewalnut.bd.engagement.Conversations;
import com.codewalnut.bd.network.EmailReplyFound;
import com.codewalnut.bd.network.NetworkService;
import com.codewalnut.bd.reporting.ProspectContextQueries;
import com.codewalnut.bd.shared.AuditLog;
import com.codewalnut.bd.shared.ConflictException;
import com.codewalnut.bd.shared.CurrentUser;
import com.codewalnut.bd.shared.NotFoundException;
import com.codewalnut.bd.shared.Role;
import com.codewalnut.bd.shared.RuleViolationException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Replies (ADR-0024): every Gmail reply on a GTN track, and any reply pasted on a conversation or a GTN contact, is
 * read by the reply agent, which sorts it and drafts the answer. An unsubscribe opts the person out at once; the owner
 * confirms everything else, which is then applied to the conversation or the GTN track.
 */
@Service
public class ReplyService {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ReplyService.class);
    static final Set<String> CHANNELS = Set.of("LINKEDIN", "EMAIL", "WHATSAPP", "OTHER");
    static final int TEXT_MAX = 4000;
    static final int ANSWER_MAX = 2000;

    public record Reply(long id, String personType, long personId, String personName, String personTitle, String company,
                        String linkedinUrl, String email, Long conversationId, Long trackId, String ownerName, String source,
                        String channel, String replyText, LocalDate receivedOn, String status, String kind, String confidence,
                        String summary, LocalDate revisitOn, String referralName, String referralRole, String nextStep,
                        String draftAnswer, String finalKind, String sentAnswer, boolean optedOut, String error, int version) {}

    public record Inbox(List<Reply> waiting, List<Reply> recent, Stats last30Days, boolean enabled) {}

    public record Stats(int read, int confirmed, int changed, int optedOut, Map<String, Integer> kinds) {}

    /** Paste a reply on a conversation or a GTN track (one of them). */
    public record Paste(Long conversationId, Long trackId, String text, String channel, LocalDate receivedOn) {}

    /** The owner's decision: the kind (the agent's, or changed), the answer they sent if any, and dates. */
    public record Done(String kind, String answer, Boolean answerSent, LocalDate revisitOn, String nextStep, LocalDate nextStepDue,
                       Integer version) {}

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final ReplyReader reader;
    private final ProspectContextQueries people;
    private final Conversations conversations;
    private final NetworkService network;
    private final TaskExecutor executor;
    private final AuditLog audit;
    private final int perDay;

    ReplyService(JdbcClient jdbc, JsonMapper json, ReplyReader reader, ProspectContextQueries people, Conversations conversations,
                 NetworkService network, @Qualifier("applicationTaskExecutor") TaskExecutor executor, AuditLog audit,
                 @Value("${app.agent.replies-per-day:200}") int perDay) {
        this.jdbc = jdbc;
        this.json = json;
        this.reader = reader;
        this.people = people;
        this.conversations = conversations;
        this.network = network;
        this.executor = executor;
        this.audit = audit;
        this.perDay = Math.max(1, perDay);
    }

    // ---- Arriving ---------------------------------------------------------------------------------------------

    /** A Gmail reply GTN logged: read it, once it is committed. Nothing happens when the agent is off. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void onEmailReply(EmailReplyFound e) {
        if (!reader.enabled()) {
            return;
        }
        String text = e.preview() == null || e.preview().isBlank() ? "(no preview)" : e.preview().strip();
        var key = new GeneratedKeyHolder();
        int added = jdbc.sql("""
                INSERT IGNORE INTO prospect_reply (owner_user_id, person_type, person_id, track_id, source, channel, reply_text,
                    received_on, gmail_message_id)
                VALUES (:o, 'CONNECTION', :p, :t, 'GMAIL', 'EMAIL', :text, :on, :m)
                """).param("o", e.ownerId()).param("p", e.connectionId()).param("t", e.trackId())
            .param("text", cut(text, TEXT_MAX)).param("on", e.on()).param("m", e.gmailMessageId()).update(key);
        if (added > 0) {
            long id = key.getKey().longValue();
            afterCommit(() -> read(id)); // its own transaction: GTN's has committed already
        }
    }

    /** A reply found in Gmail on a sequence thread (ADR-0025): read like any other, on the contact's conversation. */
    @Transactional
    public void fromSequence(long ownerId, long contactId, Long conversationId, String gmailMessageId, String preview, LocalDate on) {
        if (!reader.enabled()) {
            return;
        }
        String text = preview == null || preview.isBlank() ? "(no preview)" : preview.strip();
        var key = new GeneratedKeyHolder();
        int added = jdbc.sql("""
                INSERT IGNORE INTO prospect_reply (owner_user_id, person_type, person_id, conversation_id, source, channel, reply_text,
                    received_on, gmail_message_id)
                VALUES (:o, 'CONTACT', :p, :c, 'GMAIL', 'EMAIL', :text, :on, :m)
                """).param("o", ownerId).param("p", contactId).param("c", conversationId).param("text", cut(text, TEXT_MAX))
            .param("on", on).param("m", gmailMessageId).update(key);
        if (added > 0) {
            long id = key.getKey().longValue();
            afterCommit(() -> read(id));
        }
    }

    /** A reply someone copied from LinkedIn, WhatsApp or a longer email. */
    @Transactional
    public Reply paste(Paste in, CurrentUser user) {
        user.require(Role.PIPELINE_EDITORS);
        if (!reader.enabled()) {
            throw new RuleViolationException(List.of("The reply agent is not switched on. Ask an Admin to set ANTHROPIC_API_KEY."));
        }
        List<String> problems = new ArrayList<>();
        String text = in == null || in.text() == null ? "" : in.text().strip();
        if (text.isEmpty()) {
            problems.add("Paste what they wrote.");
        } else if (text.length() > TEXT_MAX) {
            problems.add("Paste at most " + TEXT_MAX + " characters: the reply itself, not the whole thread.");
        }
        String channel = in == null || in.channel() == null ? "LINKEDIN" : in.channel().trim().toUpperCase(Locale.ROOT);
        if (!CHANNELS.contains(channel)) {
            problems.add("Choose where they replied: LinkedIn, email, WhatsApp or other.");
        }
        LocalDate on = in == null || in.receivedOn() == null ? network.today() : in.receivedOn();
        if (on.isAfter(network.today())) {
            problems.add("The reply can't be from the future.");
        }
        if (in == null || (in.conversationId() == null) == (in.trackId() == null)) {
            problems.add("Paste the reply on a conversation or a GTN contact.");
        } else if (in.conversationId() != null && !Set.of("LINKEDIN", "EMAIL").contains(channel)) {
            problems.add("A conversation takes LinkedIn or email replies.");
        }
        if (!problems.isEmpty()) {
            throw new RuleViolationException(problems);
        }
        String type;
        long person;
        long owner;
        if (in.conversationId() != null) {
            var c = jdbc.sql("SELECT contact_id, owner_user_id FROM conversation WHERE id = :id").param("id", in.conversationId())
                .query((rs, n) -> new long[] {rs.getObject(1) == null ? -1 : rs.getLong(1), rs.getLong(2)}).optional()
                .orElseThrow(() -> new NotFoundException("Conversation", in.conversationId()));
            if (c[0] < 0) {
                throw new RuleViolationException(List.of("This conversation has no contact. Add them as a contact first."));
            }
            type = "CONTACT";
            person = c[0];
            owner = c[1];
        } else {
            var t = jdbc.sql("SELECT connection_id, owner_user_id FROM network_track WHERE id = :id").param("id", in.trackId())
                .query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)}).optional()
                .orElseThrow(() -> new NotFoundException("Network track", in.trackId()));
            type = "CONNECTION";
            person = t[0];
            owner = t[1];
        }
        var key = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO prospect_reply (owner_user_id, person_type, person_id, conversation_id, track_id, source, channel,
                    reply_text, received_on, created_by)
                VALUES (:o, :t, :p, :c, :tr, 'PASTED', :ch, :text, :on, :u)
                """).param("o", owner).param("t", type).param("p", person).param("c", in.conversationId()).param("tr", in.trackId())
            .param("ch", channel).param("text", text).param("on", on).param("u", user.id()).update(key);
        long id = key.getKey().longValue();
        audit.record("prospect_reply", id, AuditLog.Action.CREATE, Map.of("source", "PASTED"), user.id());
        afterCommit(() -> read(id));
        return get(id);
    }

    /** Runs a task in the background once the current transaction commits (or now, without one). */
    void afterCommitRun(Runnable task) {
        afterCommit(task);
    }

    private void afterCommit(Runnable task) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        executor.execute(task);
                    }
                });
        } else {
            executor.execute(task);
        }
    }

    // ---- Reading ----------------------------------------------------------------------------------------------

    void read(long id) {
        try {
            Reply r = get(id);
            int today = jdbc.sql("SELECT COUNT(*) FROM prospect_reply WHERE created_at >= CURRENT_DATE AND model IS NOT NULL")
                .query(Integer.class).single();
            if (today >= perDay) {
                fail(id, "Today's limit for the reply agent (" + perDay + ") is used up. Handle this one by hand.");
                return;
            }
            ProspectContextQueries.Context ctx = people.build(r.personType(), r.personId());
            String owner = jdbc.sql("SELECT full_name FROM app_user WHERE id = (SELECT owner_user_id FROM prospect_reply WHERE id = :id)")
                .param("id", id).query(String.class).single();
            List<String> history = ctx.history().stream()
                .filter(h -> !h.contains(r.replyText().length() > 60 ? r.replyText().substring(0, 60) : r.replyText())).toList();
            ReplyReader.Reading reading = reader.read(new ReplyReader.Request(r.personName(), ctx.person().title(), ctx.person().company(),
                owner, r.channel(), history, brief(r.personType(), r.personId()), r.replyText(), network.today()));
            if ("UNSUBSCRIBE".equals(reading.kind())) {
                optOut(id, true); // at once, before anyone sees it (ADR-0024)
            }
            jdbc.sql("""
                    UPDATE prospect_reply SET status = 'READY', category = :k, confidence = :c, summary = :s, revisit_on = :rv,
                        referral_name = :rn, referral_role = :rr, next_step = :ns, draft_answer = :a, model = :m
                    WHERE id = :id AND status = 'READING'
                    """).param("k", reading.kind()).param("c", cut(reading.confidence(), 10)).param("s", cut(reading.summary(), 500))
                .param("rv", reading.revisitOn()).param("rn", cut(reading.referralName(), 200)).param("rr", cut(reading.referralRole(), 200))
                .param("ns", cut(reading.nextStep(), 300)).param("a", cut(reading.answer(), ANSWER_MAX)).param("m", reading.model())
                .param("id", id).update();
        } catch (com.codewalnut.bd.assist.ResearchException e) {
            fail(id, e.getMessage());
        } catch (RuntimeException e) {
            LOG.error("Reading reply {} failed", id, e);
            fail(id, "The reply could not be read. Handle it by hand.");
        }
    }

    /** The headline and angle of the person's newest brief, if any. */
    private String brief(String type, long id) {
        return jdbc.sql("""
                SELECT brief FROM prospect_brief WHERE person_type = :t AND person_id = :id AND status = 'DONE' ORDER BY id DESC LIMIT 1
                """).param("t", type).param("id", id).query(String.class).optional().map(s -> {
                JsonNode b = json.readTree(s);
                String angle = b.path("angle").isObject() ? " Angle: " + b.path("angle").path("offer").asString("") + "." : "";
                return b.path("headline").asString("") + "." + angle;
            }).orElse(null);
    }

    private void fail(long id, String message) {
        jdbc.sql("UPDATE prospect_reply SET status = 'FAILED', error = :e WHERE id = :id AND status = 'READING'")
            .param("e", cut(message, 500)).param("id", id).update();
    }

    // ---- Opting out ---------------------------------------------------------------------------------------------

    /** Stops all contact with the person (or undoes it): no queue, no research; a GTN track's next action says so. */
    @Transactional
    public void optOut(long replyId, boolean out) {
        Reply r = get(replyId);
        String table = "CONTACT".equals(r.personType()) ? "contact" : "connection";
        jdbc.sql("UPDATE " + table + " SET opted_out = :o, opted_out_at = IF(:o, CURRENT_TIMESTAMP(3), NULL) WHERE id = :id")
            .param("o", out).param("id", r.personId()).update();
        if ("CONNECTION".equals(r.personType())) {
            jdbc.sql("""
                    UPDATE contact SET opted_out = :o, opted_out_at = IF(:o, CURRENT_TIMESTAMP(3), NULL)
                    WHERE id = (SELECT contact_id FROM connection WHERE id = :id)
                    """).param("o", out).param("id", r.personId()).update();
        }
        if (out) {
            jdbc.sql("UPDATE prospect_queue_item SET status = 'EXPIRED' WHERE person_type = :t AND person_id = :p AND status = 'QUEUED'")
                .param("t", r.personType()).param("p", r.personId()).update();
            if (r.trackId() != null) {
                jdbc.sql("""
                        UPDATE network_track SET next_action = 'They asked not to be contacted: confirm in Replies', next_action_on = :d
                        WHERE id = :id
                        """).param("d", network.today()).param("id", r.trackId()).update();
            }
        }
        jdbc.sql("UPDATE prospect_reply SET opted_out = :o WHERE id = :id").param("o", out).param("id", replyId).update();
    }

    /** Undo an opt-out the agent made by mistake. */
    public Reply undoOptOut(long id, CurrentUser user) {
        Reply r = owned(id, user);
        if (!r.optedOut()) {
            throw new ConflictException("They are not opted out.");
        }
        optOut(id, false);
        audit.record("prospect_reply", id, AuditLog.Action.UPDATE, Map.of("optedOut", false), user.id());
        return get(id);
    }

    // ---- Acting -----------------------------------------------------------------------------------------------

    /** The owner confirms the kind (and the answer they sent); it is applied to the conversation or the GTN track. */
    @Transactional
    public Reply done(long id, Done in, CurrentUser user) {
        Reply r = owned(id, user);
        if (!Set.of("READY", "FAILED").contains(r.status())) {
            throw new ConflictException("This reply was already handled.");
        }
        if (in != null && in.version() != null && in.version() != r.version()) {
            throw new ConflictException("This reply changed. Reload and try again.");
        }
        String kind = in == null || in.kind() == null ? r.kind() : in.kind().trim().toUpperCase(Locale.ROOT);
        List<String> problems = new ArrayList<>();
        if (kind == null || !ReplyReader.KINDS.contains(kind)) {
            problems.add("Say what kind of reply it is.");
        }
        String answer = in == null || in.answer() == null || in.answer().isBlank() ? null : in.answer().strip();
        boolean answered = in != null && Boolean.TRUE.equals(in.answerSent()) && answer != null;
        if (answer != null && answer.length() > ANSWER_MAX) {
            problems.add("The answer can be at most " + ANSWER_MAX + " characters.");
        }
        LocalDate today = network.today();
        LocalDate revisit = in != null && in.revisitOn() != null ? in.revisitOn() : r.revisitOn();
        if ("NOT_NOW".equals(kind) && (revisit == null || !revisit.isAfter(today))) {
            revisit = today.plusMonths(3);
        }
        if ("NOT_INTERESTED".equals(kind)) {
            revisit = today.plusMonths(6);
        }
        String nextStep = in != null && in.nextStep() != null && !in.nextStep().isBlank() ? in.nextStep().strip() : r.nextStep();
        LocalDate due = in != null && in.nextStepDue() != null ? in.nextStepDue() : defaultDue(kind, revisit, today);
        if (!problems.isEmpty()) {
            throw new RuleViolationException(problems);
        }
        String summary = "Replied: " + (r.summary() == null ? cut(r.replyText(), 300) : r.summary());
        if (r.conversationId() != null) {
            applyToConversation(r, kind, summary, answered ? answer : null, nextStep, due, today, user);
        } else if (r.trackId() != null) {
            applyToTrack(r, kind, summary, answered ? answer : null, revisit, user);
        }
        if ("UNSUBSCRIBE".equals(kind) && !r.optedOut()) {
            optOut(id, true);
        }
        Map<String, Object> set = new HashMap<>();
        set.put("k", kind);
        set.put("a", answered ? answer : null);
        set.put("id", id);
        jdbc.sql("""
                UPDATE prospect_reply SET status = 'DONE', final_category = :k, sent_answer = :a, acted_at = CURRENT_TIMESTAMP(3),
                    version = version + 1
                WHERE id = :id
                """).params(set).update();
        audit.record("prospect_reply", id, AuditLog.Action.UPDATE, Map.of("status", "DONE", "kind", kind,
            "agreed", kind.equals(r.kind())), user.id());
        return get(id);
    }

    private static LocalDate defaultDue(String kind, LocalDate revisit, LocalDate today) {
        return switch (kind) {
            case "NOT_NOW" -> revisit;
            case "NOT_INTERESTED", "UNSUBSCRIBE", "OUT_OF_OFFICE" -> null;
            default -> today.plusDays(2);
        };
    }

    private void applyToConversation(Reply r, String kind, String summary, String answer, String nextStep, LocalDate due,
                                     LocalDate today, CurrentUser user) {
        if ("OUT_OF_OFFICE".equals(kind)) {
            return;
        }
        // Conversations take LinkedIn or email replies (checked when pasted).
        String channel = "EMAIL".equals(r.channel()) ? "EMAIL" : "LINKEDIN_MESSAGE";
        boolean closes = Set.of("NOT_INTERESTED", "UNSUBSCRIBE").contains(kind);
        String step = closes ? null : nextStep == null ? "Answer them" : nextStep;
        LocalDate stepDue = closes || step == null ? null : due == null ? today.plusDays(2) : due;
        conversations.logTouch(r.conversationId(), channel, r.receivedOn(), cut(summary, 2000), closes ? "NOT_INTERESTED" : "REPLIED",
            step, stepDue, user);
        if (answer != null && !closes) {
            conversations.logTouch(r.conversationId(), channel, today, cut("We answered: " + answer, 2000), "NO_REPLY", step, stepDue, user);
        }
    }

    private void applyToTrack(Reply r, String kind, String summary, String answer, LocalDate revisit, CurrentUser user) {
        if ("UNSUBSCRIBE".equals(kind)) {
            network.stop(r.trackId(), user);
            return;
        }
        if ("OUT_OF_OFFICE".equals(kind)) {
            return;
        }
        boolean later = Set.of("NOT_NOW", "NOT_INTERESTED").contains(kind);
        String outcome = later ? "NOT_RIGHT_TIME" : "POSITIVE";
        String reason = "NOT_INTERESTED".equals(kind) ? "Not interested for now" : later ? cut(r.summary(), 300) : null;
        String note = answer == null ? null : cut("we answered: " + answer, 1000);
        boolean waiting = jdbc.sql("SELECT reply_check FROM network_track WHERE id = :id").param("id", r.trackId())
            .query(Boolean.class).optional().orElse(false);
        if ("GMAIL".equals(r.source())) {
            if (!waiting) {
                return; // already confirmed on the GTN page
            }
            network.act(r.trackId(), new NetworkService.Action("CONFIRM_REPLY", null, null, null, outcome, null,
                later ? revisit : null, reason, null, null, null, null, note, null), user);
        } else {
            String channel = "OTHER".equals(r.channel()) ? "OTHER" : r.channel();
            network.act(r.trackId(), new NetworkService.Action("REPLIED", channel, cut(summary, 1000), null, outcome, null,
                later ? revisit : null, reason, null, null, null, null, note, null), user);
        }
    }

    /** Leave everything as it is (an out-of-office, a duplicate). */
    public Reply dismiss(long id, CurrentUser user) {
        owned(id, user);
        if (jdbc.sql("""
                UPDATE prospect_reply SET status = 'DISMISSED', acted_at = CURRENT_TIMESTAMP(3), version = version + 1
                WHERE id = :id AND status IN ('READY', 'FAILED')
                """).param("id", id).update() == 0) {
            throw new ConflictException("This reply was already handled.");
        }
        return get(id);
    }

    /** Read it again (after a failure or a limit). */
    public Reply again(long id, CurrentUser user) {
        Reply r = owned(id, user);
        if (!"FAILED".equals(r.status())) {
            throw new ConflictException("Only a reply that could not be read is read again.");
        }
        jdbc.sql("UPDATE prospect_reply SET status = 'READING', error = NULL WHERE id = :id").param("id", id).update();
        executor.execute(() -> read(id));
        return get(id);
    }

    private Reply owned(long id, CurrentUser user) {
        user.require(Role.PIPELINE_EDITORS);
        Reply r = get(id);
        long owner = jdbc.sql("SELECT owner_user_id FROM prospect_reply WHERE id = :id").param("id", id).query(Long.class).single();
        if (owner != user.id() && !user.hasAnyRole(Role.NETWORK_MANAGERS)) {
            throw new AccessDeniedException("This reply is for someone else.");
        }
        return r;
    }

    // ---- Listing ----------------------------------------------------------------------------------------------

    /** My replies: waiting (being read, ready, could not be read) and handled in the last 7 days. */
    public Inbox inbox(CurrentUser user) {
        user.require(Role.PIPELINE_EDITORS);
        List<Reply> waiting = list("r.owner_user_id = :u AND r.status IN ('READING', 'READY', 'FAILED')", Map.of("u", user.id()));
        List<Reply> recent = list("r.owner_user_id = :u AND r.status IN ('DONE', 'DISMISSED') AND r.acted_at > NOW() - INTERVAL 7 DAY",
            Map.of("u", user.id()));
        return new Inbox(waiting, recent, stats(user.id()), reader.enabled());
    }

    /** Replies on one conversation or track, newest first (for those pages). */
    public List<Reply> forConversation(long conversationId) {
        return list("r.conversation_id = :c", Map.of("c", conversationId));
    }

    public List<Reply> forTrack(long trackId) {
        return list("r.track_id = :t", Map.of("t", trackId));
    }

    private Stats stats(long userId) {
        var rows = jdbc.sql("""
                SELECT category, final_category, opted_out, COUNT(*) FROM prospect_reply
                WHERE owner_user_id = :u AND created_at > NOW() - INTERVAL 30 DAY AND category IS NOT NULL
                GROUP BY category, final_category, opted_out
                """).param("u", userId)
            .query((rs, n) -> new Object[] {rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getInt(4)}).list();
        int read = 0, confirmed = 0, changed = 0, out = 0;
        Map<String, Integer> kinds = new java.util.TreeMap<>();
        for (Object[] row : rows) {
            int n = (Integer) row[3];
            read += n;
            kinds.merge((String) row[0], n, Integer::sum);
            if (row[1] != null) {
                confirmed += n;
                if (!row[1].equals(row[0])) changed += n;
            }
            if ((Boolean) row[2]) out += n;
        }
        return new Stats(read, confirmed, changed, out, kinds);
    }

    Reply get(long id) {
        return list("r.id = :id", Map.of("id", id)).stream().findFirst().orElseThrow(() -> new NotFoundException("Reply", id));
    }

    private List<Reply> list(String where, Map<String, Object> params) {
        return jdbc.sql("""
                SELECT r.id, r.person_type, r.person_id, COALESCE(c.full_name, n.full_name), COALESCE(c.title, n.current_role),
                    COALESCE(a.name, n.current_company), COALESCE(c.linkedin_url, n.linkedin_url), COALESCE(c.email, n.email),
                    r.conversation_id, r.track_id, u.full_name, r.source, r.channel, r.reply_text, r.received_on, r.status, r.category,
                    r.confidence, r.summary, r.revisit_on, r.referral_name, r.referral_role, r.next_step, r.draft_answer,
                    r.final_category, r.sent_answer, r.opted_out, r.error, r.version
                FROM prospect_reply r
                JOIN app_user u ON u.id = r.owner_user_id
                LEFT JOIN contact c ON r.person_type = 'CONTACT' AND c.id = r.person_id
                LEFT JOIN account a ON a.id = c.account_id
                LEFT JOIN connection n ON r.person_type = 'CONNECTION' AND n.id = r.person_id
                WHERE\s""" + where + " ORDER BY r.received_on DESC, r.id DESC LIMIT 200")
            .params(params)
            .query((rs, n) -> new Reply(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getString(7), rs.getString(8), rs.getObject(9, Long.class), rs.getObject(10, Long.class), rs.getString(11),
                rs.getString(12), rs.getString(13), rs.getString(14), rs.getObject(15, LocalDate.class), rs.getString(16),
                rs.getString(17), rs.getString(18), rs.getString(19), rs.getObject(20, LocalDate.class), rs.getString(21),
                rs.getString(22), rs.getString(23), rs.getString(24), rs.getString(25), rs.getString(26), rs.getBoolean(27),
                rs.getString(28), rs.getInt(29)))
            .list();
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }
}
