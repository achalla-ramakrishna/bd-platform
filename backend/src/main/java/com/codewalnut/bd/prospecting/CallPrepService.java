package com.codewalnut.bd.prospecting;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.codewalnut.bd.assist.CallPrepWriter;
import com.codewalnut.bd.assist.ResearchException;
import com.codewalnut.bd.engagement.Conversations;
import com.codewalnut.bd.engagement.MeetingBooked;
import com.codewalnut.bd.network.CallBooked;
import com.codewalnut.bd.network.NetworkService;
import com.codewalnut.bd.opportunities.OpportunityService;
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
 * The ops agent (ADR-0026): when a call is booked on a conversation or a GTN track (or someone asks), a call brief and
 * a draft opportunity. The owner creates the opportunity with one click.
 */
@Service
public class CallPrepService {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(CallPrepService.class);

    public record View(long id, String personType, long personId, String personName, Long accountId, String accountName,
                       Long conversationId, Long trackId, String ownerName, LocalDate callOn, String status, CallPrepWriter.Prep prep,
                       CallPrepWriter.Draft draft, Long opportunityId, String opportunityName, String error, Instant createdAt) {}

    public record Prepare(Long conversationId, Long trackId, Boolean again) {}

    public record NewOpportunity(String name, String type, String need) {}

    private record Target(String type, long personId, Long accountId, Long conversationId, Long trackId, long owner, LocalDate callOn) {}

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final CallPrepWriter writer;
    private final ProspectContextQueries people;
    private final Conversations conversations;
    private final OpportunityService opportunities;
    private final NetworkService network;
    private final ReplyService replies;
    private final TaskExecutor executor;
    private final AuditLog audit;
    private final int perDay;

    CallPrepService(JdbcClient jdbc, JsonMapper json, CallPrepWriter writer, ProspectContextQueries people, Conversations conversations,
                    OpportunityService opportunities, NetworkService network, ReplyService replies,
                    @Qualifier("applicationTaskExecutor") TaskExecutor executor, AuditLog audit,
                    @Value("${app.agent.call-prep-per-day:50}") int perDay) {
        this.jdbc = jdbc;
        this.json = json;
        this.writer = writer;
        this.people = people;
        this.conversations = conversations;
        this.opportunities = opportunities;
        this.network = network;
        this.replies = replies;
        this.executor = executor;
        this.audit = audit;
        this.perDay = Math.max(1, perDay);
    }

    // ---- Triggers ---------------------------------------------------------------------------------------------

    /** A conversation's meeting was booked: prepare, once it has committed. Needs a contact. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onMeetingBooked(MeetingBooked e) {
        if (e.contactId() == null || !writer.enabled()) {
            return;
        }
        begin(new Target("CONTACT", e.contactId(), e.accountId(), e.conversationId(), null, e.ownerId(), e.on()), false, null, true);
    }

    /** A GTN call was booked: prepare, once it has committed. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCallBooked(CallBooked e) {
        if (!writer.enabled()) {
            return;
        }
        Long account = jdbc.sql("SELECT account_id FROM connection WHERE id = :id").param("id", e.connectionId()).query(Long.class)
            .optional().orElse(null);
        begin(new Target("CONNECTION", e.connectionId(), account, null, e.trackId(), e.ownerId(), e.on()), false, null, true);
    }

    /** "Prepare for the call" on a conversation or a GTN contact. */
    @Transactional
    public View prepare(Prepare in, CurrentUser user) {
        user.require(Role.PIPELINE_EDITORS);
        if (!writer.enabled()) {
            throw new RuleViolationException(List.of("The call-prep agent is not switched on. Ask an Admin to set ANTHROPIC_API_KEY."));
        }
        if (in == null || (in.conversationId() == null) == (in.trackId() == null)) {
            throw new RuleViolationException(List.of("Prepare a call on a conversation or a GTN contact."));
        }
        Target t;
        if (in.conversationId() != null) {
            t = jdbc.sql("SELECT contact_id, account_id, owner_user_id, next_step_due FROM conversation WHERE id = :id")
                .param("id", in.conversationId())
                .query((rs, n) -> rs.getObject(1) == null ? null : new Target("CONTACT", rs.getLong(1), rs.getLong(2), in.conversationId(), null,
                    rs.getLong(3), rs.getObject(4, LocalDate.class)))
                .optional().orElseThrow(() -> new NotFoundException("Conversation", in.conversationId()));
            if (t == null) {
                throw new RuleViolationException(List.of("This conversation has no contact. Add them as a contact first."));
            }
        } else {
            t = jdbc.sql("""
                    SELECT t.connection_id, n.account_id, t.owner_user_id, t.call_on FROM network_track t JOIN connection n ON n.id = t.connection_id
                    WHERE t.id = :id
                    """).param("id", in.trackId())
                .query((rs, n) -> new Target("CONNECTION", rs.getLong(1), rs.getObject(2, Long.class), null, in.trackId(), rs.getLong(3),
                    rs.getObject(4, LocalDate.class)))
                .optional().orElseThrow(() -> new NotFoundException("Network track", in.trackId()));
        }
        return get(begin(t, Boolean.TRUE.equals(in.again()), user.id(), false));
    }

    /** Starts a prep unless one exists for the same call (or is running); returns its id. */
    private long begin(Target t, boolean again, Long requestedBy, boolean quiet) {
        String where = t.conversationId() != null ? "conversation_id = :ref" : "track_id = :ref";
        long ref = t.conversationId() != null ? t.conversationId() : t.trackId();
        record Existing(long id, String status, LocalDate callOn) {}
        Existing last = jdbc.sql("SELECT id, status, call_on FROM call_prep WHERE " + where + " ORDER BY id DESC LIMIT 1").param("ref", ref)
            .query((rs, n) -> new Existing(rs.getLong(1), rs.getString(2), rs.getObject(3, LocalDate.class))).optional().orElse(null);
        if (last != null && ("RUNNING".equals(last.status()) || !again && "DONE".equals(last.status())
                && java.util.Objects.equals(last.callOn(), t.callOn()))) {
            return last.id();
        }
        int today = jdbc.sql("SELECT COUNT(*) FROM call_prep WHERE created_at >= CURRENT_DATE").query(Integer.class).single();
        if (today >= perDay) {
            if (quiet) {
                LOG.info("Call prep skipped: today's limit of {} is used.", perDay);
                return last == null ? -1 : last.id();
            }
            throw new RuleViolationException(List.of("Today's call-prep limit (" + perDay + ") is used up. Try tomorrow, or ask an Admin to "
                + "raise AGENT_CALL_PREP_PER_DAY."));
        }
        var key = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO call_prep (owner_user_id, person_type, person_id, account_id, conversation_id, track_id, call_on, requested_by)
                VALUES (:o, :t, :p, :a, :c, :tr, :on, :u)
                """).param("o", t.owner()).param("t", t.type()).param("p", t.personId()).param("a", t.accountId())
            .param("c", t.conversationId()).param("tr", t.trackId()).param("on", t.callOn()).param("u", requestedBy).update(key);
        long id = key.getKey().longValue();
        replies.afterCommitRun(() -> run(id));
        return id;
    }

    void run(long id) {
        try {
            View v = get(id);
            ProspectContextQueries.Context ctx = people.build(v.personType(), v.personId());
            String owner = jdbc.sql("SELECT u.full_name FROM app_user u JOIN call_prep c ON c.owner_user_id = u.id WHERE c.id = :id")
                .param("id", id).query(String.class).single();
            List<String> extra = new ArrayList<>();
            jdbc.sql("""
                    SELECT received_on, COALESCE(summary, reply_text) FROM prospect_reply WHERE person_type = :t AND person_id = :p
                    ORDER BY id DESC LIMIT 5
                    """).param("t", v.personType()).param("p", v.personId())
                .query((rs, n) -> extra.add("Their reply on " + rs.getDate(1) + ": " + rs.getString(2))).list();
            if (v.accountId() != null) {
                jdbc.sql("""
                        SELECT o.name, s.label FROM opportunity o JOIN opportunity_stage s ON s.code = o.stage_code
                        WHERE o.account_id = :a AND s.outcome = 'OPEN' ORDER BY o.id DESC LIMIT 5
                        """).param("a", v.accountId())
                    .query((rs, n) -> extra.add("Open opportunity at their account: " + rs.getString(1) + " (" + rs.getString(2) + ")")).list();
            }
            CallPrepWriter.Result r = writer.write(new CallPrepWriter.Request(ctx, owner, v.callOn(), brief(v.personType(), v.personId()), extra,
                network.today()));
            jdbc.sql("""
                    UPDATE call_prep SET status = 'DONE', brief = :b, draft = :d, model = :m, finished_at = CURRENT_TIMESTAMP(3)
                    WHERE id = :id AND status = 'RUNNING'
                    """).param("b", json.writeValueAsString(r.prep())).param("d", json.writeValueAsString(r.draft())).param("m", r.model())
                .param("id", id).update();
        } catch (ResearchException e) {
            fail(id, e.getMessage());
        } catch (RuntimeException e) {
            LOG.error("Call prep {} failed", id, e);
            fail(id, "The call prep could not be written. Try again.");
        }
    }

    private void fail(long id, String message) {
        jdbc.sql("UPDATE call_prep SET status = 'FAILED', error = :e, finished_at = CURRENT_TIMESTAMP(3) WHERE id = :id AND status = 'RUNNING'")
            .param("e", message.length() > 500 ? message.substring(0, 500) : message).param("id", id).update();
    }

    private String brief(String type, long id) {
        return jdbc.sql("""
                SELECT brief FROM prospect_brief WHERE person_type = :t AND person_id = :id AND status = 'DONE' ORDER BY id DESC LIMIT 1
                """).param("t", type).param("id", id).query(String.class).optional().map(s -> {
                JsonNode b = json.readTree(s);
                StringBuilder t = new StringBuilder(b.path("headline").asString("")).append(". ").append(b.path("role").asString(""))
                    .append(". ").append(b.path("summary").asString(""));
                b.path("whyNow").values().forEach(w -> t.append("\nWhy now: ").append(w.path("statement").asString("")));
                if (b.path("angle").isObject()) {
                    t.append("\nAngle: ").append(b.path("angle").path("offer").asString("")).append(": ").append(b.path("angle").path("why").asString(""));
                }
                b.path("talkingPoints").values().forEach(x -> t.append("\nTalking point: ").append(x.asString("")));
                return t.toString();
            }).orElse(null);
    }

    // ---- The opportunity --------------------------------------------------------------------------------------

    /** Creates the opportunity from the (edited) draft at the person's account, and links it to the conversation. */
    @Transactional
    public View createOpportunity(long id, NewOpportunity in, CurrentUser user) {
        user.require(Role.PIPELINE_EDITORS);
        View v = get(id);
        long owner = jdbc.sql("SELECT owner_user_id FROM call_prep WHERE id = :id").param("id", id).query(Long.class).single();
        if (owner != user.id() && !user.hasAnyRole(Role.NETWORK_MANAGERS)) {
            throw new AccessDeniedException("This call is someone else's.");
        }
        if (v.opportunityId() != null) {
            throw new ConflictException("The opportunity was created already.");
        }
        if (v.accountId() == null) {
            throw new RuleViolationException(List.of("Link " + v.personName() + " to an account first (on their connection page)."));
        }
        String name = in == null || in.name() == null || in.name().isBlank() ? (v.draft() == null ? null : v.draft().name()) : in.name().strip();
        String type = in == null || in.type() == null || in.type().isBlank() ? (v.draft() == null ? null : v.draft().type())
            : in.type().strip().toUpperCase(Locale.ROOT);
        String need = in == null || in.need() == null || in.need().isBlank() ? (v.draft() == null ? null : v.draft().need()) : in.need().strip();
        Long decisionMaker = "CONTACT".equals(v.personType()) ? v.personId() : jdbc.sql("""
                SELECT c.id FROM connection n JOIN contact c ON c.id = n.contact_id AND c.account_id = n.account_id WHERE n.id = :id
                """).param("id", v.personId()).query(Long.class).optional().orElse(null);
        long opportunity = opportunities.create(new OpportunityService.Input(v.accountId(), name, type, user.id(), null, null, null,
            decisionMaker, need, null, List.of(), null, null, null, null, null, null), user);
        jdbc.sql("UPDATE call_prep SET opportunity_id = :o WHERE id = :id").param("o", opportunity).param("id", id).update();
        if (v.conversationId() != null) {
            conversations.linkOpportunity(v.conversationId(), opportunity, user);
        }
        audit.record("call_prep", id, AuditLog.Action.UPDATE, Map.of("opportunity", opportunity), user.id());
        return get(id);
    }

    // ---- Reading ----------------------------------------------------------------------------------------------

    public View get(long id) {
        return list("p.id = :id", Map.of("id", id)).stream().findFirst().orElseThrow(() -> new NotFoundException("Call prep", id));
    }

    public java.util.Optional<View> forConversation(long conversationId) {
        return list("p.id = (SELECT MAX(x.id) FROM call_prep x WHERE x.conversation_id = :c)", Map.of("c", conversationId)).stream().findFirst();
    }

    public java.util.Optional<View> forTrack(long trackId) {
        return list("p.id = (SELECT MAX(x.id) FROM call_prep x WHERE x.track_id = :t)", Map.of("t", trackId)).stream().findFirst();
    }

    /** My calls: the newest prep per conversation or track, for calls from today on (or with no date, from the last 14 days). */
    public List<View> upcoming(CurrentUser user) {
        user.require(Role.PIPELINE_EDITORS);
        Map<String, Object> params = new HashMap<>();
        params.put("u", user.id());
        params.put("d", network.today());
        return list("""
                p.owner_user_id = :u AND (p.call_on >= :d OR (p.call_on IS NULL AND p.created_at > NOW() - INTERVAL 14 DAY))
                  AND p.id = (SELECT MAX(x.id) FROM call_prep x WHERE (x.conversation_id = p.conversation_id OR x.track_id = p.track_id))
                """, params);
    }

    private List<View> list(String where, Map<String, Object> params) {
        return jdbc.sql("""
                SELECT p.id, p.person_type, p.person_id, COALESCE(c.full_name, n.full_name), p.account_id, a.name, p.conversation_id, p.track_id,
                    u.full_name, p.call_on, p.status, p.brief, p.draft, p.opportunity_id, o.name, p.error, p.created_at
                FROM call_prep p
                JOIN app_user u ON u.id = p.owner_user_id
                LEFT JOIN contact c ON p.person_type = 'CONTACT' AND c.id = p.person_id
                LEFT JOIN connection n ON p.person_type = 'CONNECTION' AND n.id = p.person_id
                LEFT JOIN account a ON a.id = p.account_id
                LEFT JOIN opportunity o ON o.id = p.opportunity_id
                WHERE\s""" + where + " ORDER BY p.call_on IS NULL, p.call_on, p.id DESC LIMIT 100")
            .params(params)
            .query((rs, n) -> new View(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getObject(5, Long.class), rs.getString(6),
                rs.getObject(7, Long.class), rs.getObject(8, Long.class), rs.getString(9), rs.getObject(10, LocalDate.class), rs.getString(11),
                rs.getString(12) == null ? null : json.readValue(rs.getString(12), CallPrepWriter.Prep.class),
                rs.getString(13) == null ? null : json.readValue(rs.getString(13), CallPrepWriter.Draft.class),
                rs.getObject(14, Long.class), rs.getString(15), rs.getString(16), rs.getTimestamp(17).toInstant()))
            .list();
    }
}
