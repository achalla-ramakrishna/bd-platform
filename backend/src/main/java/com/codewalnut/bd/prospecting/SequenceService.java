package com.codewalnut.bd.prospecting;

import java.security.SecureRandom;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
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

import com.codewalnut.bd.admin.UserDirectory;
import com.codewalnut.bd.assist.ResearchException;
import com.codewalnut.bd.assist.SequenceWriter;
import com.codewalnut.bd.engagement.Conversations;
import com.codewalnut.bd.google.GoogleAccounts;
import com.codewalnut.bd.google.GoogleApi;
import com.codewalnut.bd.google.Mime;
import com.codewalnut.bd.reporting.ProspectContextQueries;
import com.codewalnut.bd.shared.AuditLog;
import com.codewalnut.bd.shared.ConflictException;
import com.codewalnut.bd.shared.CurrentUser;
import com.codewalnut.bd.shared.NotFoundException;
import com.codewalnut.bd.shared.Role;
import com.codewalnut.bd.shared.RuleViolationException;
import com.codewalnut.bd.walnut.WalnutProperties;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Email sequences (ADR-0025): the copy agent writes a contact's emails from a template, the owner edits and approves
 * them once, and the runner sends each from the owner's Gmail when it is due, after checking every guardrail again.
 * A reply, an opt-out or an unsubscribe ends it.
 */
@Service
public class SequenceService {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SequenceService.class);
    static final Set<String> OPEN = Set.of("DRAFTING", "REVIEW", "FAILED", "ACTIVE", "PAUSED");
    static final int MAX_STEPS = 4;
    static final int MIN_GAP_DAYS = 3;
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Step(Integer position, Integer waitDays, String subject, String body) {}

    public record Template(Long id, String name, String description, boolean active, List<Step> steps, Integer version) {}

    public record Email(int position, int waitDays, String subject, String body, boolean edited, String status, Instant sentAt) {}

    public record Enrolment(long id, long sequenceId, String sequenceName, long contactId, String contactName, String contactTitle,
                            long accountId, String accountName, String ownerName, Long conversationId, String toEmail, String status,
                            int nextPosition, LocalDate nextSendOn, String waitingReason, String endedReason, String error, int sent,
                            int steps, Instant createdAt, int version, List<Email> emails) {}

    public record Overview(boolean sending, boolean writerEnabled, String window, int perSenderPerDay, List<Enrolment> mine,
                           List<Template> templates) {}

    public record Start(Long contactId, Long sequenceId) {}

    public record EmailEdit(String subject, String body) {}

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final SequenceWriter writer;
    private final ProspectContextQueries people;
    private final Conversations conversations;
    private final ReplyService replies;
    private final GoogleAccounts accounts;
    private final GoogleApi google;
    private final UserDirectory users;
    private final WalnutProperties walnut;
    private final TaskExecutor executor;
    private final AuditLog audit;
    private final boolean sending;
    private final int perSender;
    private final int windowStart;
    private final int windowEnd;
    private final Set<DayOfWeek> days;
    private final Set<String> consentBases;
    private final ZoneId zone;

    SequenceService(JdbcClient jdbc, JsonMapper json, SequenceWriter writer, ProspectContextQueries people, Conversations conversations,
                    ReplyService replies, GoogleAccounts accounts, GoogleApi google, UserDirectory users, WalnutProperties walnut,
                    @Qualifier("applicationTaskExecutor") TaskExecutor executor, AuditLog audit,
                    @Value("${app.agent.email.sending:false}") boolean sending,
                    @Value("${app.agent.email.per-sender-per-day:25}") int perSender,
                    @Value("${app.agent.email.window-start:9}") int windowStart,
                    @Value("${app.agent.email.window-end:18}") int windowEnd,
                    @Value("${app.agent.email.days:MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY}") List<DayOfWeek> days,
                    @Value("${app.agent.email.consent-bases:EXISTING_RELATIONSHIP,CONSENTED,BUSINESS_CONTACT}") List<String> consentBases,
                    @Value("${app.gtn.zone:Asia/Kolkata}") String zone) {
        this.jdbc = jdbc;
        this.json = json;
        this.writer = writer;
        this.people = people;
        this.conversations = conversations;
        this.replies = replies;
        this.accounts = accounts;
        this.google = google;
        this.users = users;
        this.walnut = walnut;
        this.executor = executor;
        this.audit = audit;
        this.sending = sending;
        this.perSender = Math.max(1, perSender);
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.days = Set.copyOf(days);
        this.consentBases = Set.copyOf(consentBases.stream().map(s -> s.trim().toUpperCase(Locale.ROOT)).toList());
        this.zone = ZoneId.of(zone);
    }

    LocalDate today() {
        return LocalDate.now(zone);
    }

    // ---- Templates ------------------------------------------------------------------------------------------

    public List<Template> templates(boolean activeOnly) {
        List<Template> list = jdbc.sql("SELECT id, name, description, active, version FROM email_sequence "
                + (activeOnly ? "WHERE active " : "") + "ORDER BY name")
            .query((rs, n) -> new Template(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4), List.of(), rs.getInt(5))).list();
        return list.stream().map(t -> new Template(t.id(), t.name(), t.description(), t.active(), steps(t.id()), t.version())).toList();
    }

    private List<Step> steps(long sequenceId) {
        return jdbc.sql("SELECT position, wait_days, subject, body FROM email_sequence_step WHERE sequence_id = :s ORDER BY position")
            .param("s", sequenceId).query((rs, n) -> new Step(rs.getInt(1), rs.getInt(2), rs.getString(3), rs.getString(4))).list();
    }

    /** Creates or replaces a template (BD leads, Leadership, Admins). */
    @Transactional
    public Template saveTemplate(Long id, Template in, CurrentUser user) {
        user.require(Role.NETWORK_MANAGERS);
        List<String> problems = new ArrayList<>();
        String name = in == null || in.name() == null ? "" : in.name().strip();
        if (name.isEmpty() || name.length() > 120) {
            problems.add("Give the sequence a name of up to 120 characters.");
        }
        List<Step> steps = in == null || in.steps() == null ? List.of() : in.steps();
        if (steps.isEmpty() || steps.size() > MAX_STEPS) {
            problems.add("A sequence has 1 to " + MAX_STEPS + " emails.");
        }
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            String label = "Email " + (i + 1) + ": ";
            if (i > 0 && (s.waitDays() == null || s.waitDays() < MIN_GAP_DAYS || s.waitDays() > 60)) {
                problems.add(label + "wait " + MIN_GAP_DAYS + " to 60 days after the one before.");
            }
            if (s.subject() == null || s.subject().isBlank() || s.subject().length() > 200) {
                problems.add(label + "write a subject of up to 200 characters.");
            }
            if (s.body() == null || s.body().isBlank() || s.body().length() > 2000) {
                problems.add(label + "say what the email should do, in up to 2,000 characters.");
            }
        }
        if (in != null && in.description() != null && in.description().length() > 500) {
            problems.add("Keep the description under 500 characters.");
        }
        if (!problems.isEmpty()) {
            throw new RuleViolationException(problems);
        }
        if (jdbc.sql("SELECT COUNT(*) FROM email_sequence WHERE name = :n AND (:id IS NULL OR id <> :id)").param("n", name)
                .param("id", id).query(Integer.class).single() > 0) {
            throw new ConflictException("Another sequence is called " + name + ".");
        }
        long sequenceId;
        if (id == null) {
            var key = new GeneratedKeyHolder();
            jdbc.sql("INSERT INTO email_sequence (name, description, active, created_by) VALUES (:n, :d, :a, :u)")
                .param("n", name).param("d", blankToNull(in.description())).param("a", in.active()).param("u", user.id()).update(key);
            sequenceId = key.getKey().longValue();
        } else {
            int updated = jdbc.sql("""
                    UPDATE email_sequence SET name = :n, description = :d, active = :a, version = version + 1
                    WHERE id = :id AND (:v IS NULL OR version = :v)
                    """).param("n", name).param("d", blankToNull(in.description())).param("a", in.active()).param("id", id)
                .param("v", in.version()).update();
            if (updated == 0) {
                if (jdbc.sql("SELECT COUNT(*) FROM email_sequence WHERE id = :id").param("id", id).query(Integer.class).single() == 0) {
                    throw new NotFoundException("Sequence", id);
                }
                throw new ConflictException("Someone else changed this sequence. Reload and try again.");
            }
            sequenceId = id;
            jdbc.sql("DELETE FROM email_sequence_step WHERE sequence_id = :s").param("s", id).update();
        }
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            jdbc.sql("INSERT INTO email_sequence_step (sequence_id, position, wait_days, subject, body) VALUES (:s, :p, :w, :sub, :b)")
                .param("s", sequenceId).param("p", i + 1).param("w", i == 0 ? 0 : s.waitDays()).param("sub", s.subject().strip())
                .param("b", s.body().strip()).update();
        }
        audit.record("email_sequence", sequenceId, id == null ? AuditLog.Action.CREATE : AuditLog.Action.UPDATE, Map.of("name", name), user.id());
        return templates(false).stream().filter(t -> t.id() == sequenceId).findFirst().orElseThrow();
    }

    // ---- Starting -------------------------------------------------------------------------------------------

    /** Starts a sequence for a contact: checks the guardrails that can be checked now, then the agent writes it. */
    @Transactional
    public Enrolment start(Start in, CurrentUser user) {
        user.require(Role.PIPELINE_EDITORS);
        if (in == null || in.contactId() == null || in.sequenceId() == null) {
            throw new RuleViolationException(List.of("Choose a contact and a sequence."));
        }
        record Contact(String email, String consent, boolean optedOut, String name) {}
        Contact c = jdbc.sql("SELECT email, consent_basis, opted_out, full_name FROM contact WHERE id = :id").param("id", in.contactId())
            .query((rs, n) -> new Contact(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getString(4))).optional()
            .orElseThrow(() -> new NotFoundException("Contact", in.contactId()));
        List<Step> steps = steps(in.sequenceId());
        Boolean active = jdbc.sql("SELECT active FROM email_sequence WHERE id = :id").param("id", in.sequenceId()).query(Boolean.class)
            .optional().orElseThrow(() -> new NotFoundException("Sequence", in.sequenceId()));
        List<String> problems = new ArrayList<>();
        if (!active || steps.isEmpty()) {
            problems.add("This sequence is switched off.");
        }
        if (c.email() == null || c.email().isBlank()) {
            problems.add(c.name() + " has no email address. Add it on the account's contacts first.");
        }
        String block = c.email() == null ? null : blocked(c.optedOut(), c.email(), c.consent());
        if (block != null) {
            problems.add(block);
        }
        if (jdbc.sql("""
                SELECT COUNT(*) FROM conversation WHERE contact_id = :c AND status IN ('REPLIED', 'MEETING_BOOKED', 'OPPORTUNITY')
                """).param("c", in.contactId()).query(Integer.class).single() > 0) {
            problems.add(c.name() + " is already talking with us: answer them in the conversation instead.");
        }
        if (jdbc.sql("SELECT COUNT(*) FROM sequence_enrolment WHERE contact_id = :c AND status IN (:open)").param("c", in.contactId())
                .param("open", OPEN).query(Integer.class).single() > 0) {
            problems.add(c.name() + " is already in a sequence. Stop it first.");
        }
        if (!problems.isEmpty()) {
            throw new RuleViolationException(problems);
        }
        Long conversation = jdbc.sql("""
                SELECT id FROM conversation WHERE contact_id = :c AND status NOT IN ('CLOSED') ORDER BY id DESC LIMIT 1
                """).param("c", in.contactId()).query(Long.class).optional().orElse(null);
        var key = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO sequence_enrolment (sequence_id, contact_id, owner_user_id, conversation_id, to_email, consent_basis,
                    unsubscribe_token)
                VALUES (:s, :c, :o, :conv, :e, :cb, :t)
                """).param("s", in.sequenceId()).param("c", in.contactId()).param("o", user.id()).param("conv", conversation)
            .param("e", c.email().strip().toLowerCase(Locale.ROOT)).param("cb", c.consent()).param("t", token()).update(key);
        long id = key.getKey().longValue();
        audit.record("sequence_enrolment", id, AuditLog.Action.CREATE, Map.of("contact", in.contactId(), "sequence", in.sequenceId()), user.id());
        replies.afterCommitRun(() -> draft(id));
        return get(id);
    }

    /** Why this person can't be emailed now, or null: opted out, suppressed, or a consent basis not allowed. */
    private String blocked(boolean optedOut, String email, String consent) {
        if (optedOut) {
            return "They asked not to be contacted.";
        }
        if (jdbc.sql("SELECT COUNT(*) FROM email_suppression WHERE email = :e").param("e", email.strip().toLowerCase(Locale.ROOT))
                .query(Integer.class).single() > 0) {
            return "Their address is on the do-not-email list (they unsubscribed).";
        }
        if (consent == null || !consentBases.contains(consent)) {
            return "Their consent basis (" + (consent == null ? "none" : consent.toLowerCase(Locale.ROOT).replace('_', ' '))
                + ") is not one we email on.";
        }
        return null;
    }

    /** The copy agent writes the emails; without it, the templates are copied for the owner to write by hand. */
    void draft(long id) {
        Enrolment e = get(id);
        List<Step> steps = steps(e.sequenceId());
        List<SequenceWriter.Email> emails;
        String error = null;
        try {
            if (!writer.enabled()) {
                emails = steps.stream().map(s -> new SequenceWriter.Email(s.position(), fill(s.subject(), e), s.body())).toList();
                error = "The agent is off: each email shows what it should say. Write them before approving.";
            } else {
                ProspectContextQueries.Context ctx = people.build("CONTACT", e.contactId());
                String owner = jdbc.sql("SELECT full_name FROM app_user WHERE id = (SELECT owner_user_id FROM sequence_enrolment WHERE id = :id)")
                    .param("id", id).query(String.class).single();
                emails = writer.write(new SequenceWriter.Request(e.contactName(), e.contactTitle(), e.accountName(), owner, ctx.history(),
                    brief(e.contactId()), steps.stream().map(s -> new SequenceWriter.Step(s.position(), s.waitDays(), s.subject(), s.body()))
                        .toList())).emails();
            }
        } catch (ResearchException ex) {
            emails = steps.stream().map(s -> new SequenceWriter.Email(s.position(), fill(s.subject(), e), s.body())).toList();
            error = ex.getMessage() + " Each email shows what it should say: write them before approving.";
        } catch (RuntimeException ex) {
            LOG.error("Writing sequence {} failed", id, ex);
            emails = steps.stream().map(s -> new SequenceWriter.Email(s.position(), fill(s.subject(), e), s.body())).toList();
            error = "The emails could not be written. Each email shows what it should say: write them before approving.";
        }
        for (SequenceWriter.Email m : emails) {
            int wait = steps.stream().filter(s -> s.position() == m.position()).findFirst().map(Step::waitDays).orElse(MIN_GAP_DAYS);
            jdbc.sql("""
                    INSERT INTO sequence_email (enrolment_id, position, wait_days, agent_subject, agent_body, subject, body)
                    VALUES (:e, :p, :w, :s, :b, :s, :b)
                    """).param("e", id).param("p", m.position()).param("w", wait).param("s", cut(m.subject(), 200))
                .param("b", cut(m.body(), 2000)).update();
        }
        jdbc.sql("UPDATE sequence_enrolment SET status = 'REVIEW', error = :err WHERE id = :id AND status = 'DRAFTING'")
            .param("err", error).param("id", id).update();
    }

    private static String fill(String pattern, Enrolment e) {
        String first = e.contactName() == null ? "" : e.contactName().trim().split("\\s+")[0];
        return pattern.replace("{company}", e.accountName() == null ? "your team" : e.accountName()).replace("{firstName}", first);
    }

    private String brief(long contactId) {
        return jdbc.sql("""
                SELECT brief FROM prospect_brief WHERE person_type = 'CONTACT' AND person_id = :id AND status = 'DONE' ORDER BY id DESC LIMIT 1
                """).param("id", contactId).query(String.class).optional().map(s -> {
                JsonNode b = json.readTree(s);
                StringBuilder t = new StringBuilder(b.path("headline").asString("")).append(". ").append(b.path("summary").asString(""));
                b.path("whyNow").values().forEach(w -> t.append("\nWhy now: ").append(w.path("statement").asString("")));
                if (b.path("angle").isObject()) {
                    t.append("\nAngle: ").append(b.path("angle").path("offer").asString("")).append(": ").append(b.path("angle").path("why").asString(""));
                }
                return t.toString();
            }).orElse(null);
    }

    // ---- Reviewing ------------------------------------------------------------------------------------------

    /** Edit an email that has not been sent: before approval, or while paused. */
    public Enrolment editEmail(long id, int position, EmailEdit in, CurrentUser user) {
        Enrolment e = owned(id, user);
        if (!Set.of("REVIEW", "FAILED", "PAUSED").contains(e.status())) {
            throw new ConflictException("Pause the sequence to change its emails.");
        }
        List<String> problems = new ArrayList<>();
        if (in == null || in.subject() == null || in.subject().isBlank() || in.subject().length() > 200) {
            problems.add("Write a subject of up to 200 characters.");
        }
        if (in == null || in.body() == null || in.body().isBlank() || in.body().length() > 2000) {
            problems.add("Write the email, up to 2,000 characters.");
        }
        if (!problems.isEmpty()) {
            throw new RuleViolationException(problems);
        }
        if (jdbc.sql("""
                UPDATE sequence_email SET subject = :s, body = :b WHERE enrolment_id = :e AND position = :p AND status = 'PLANNED'
                """).param("s", in.subject().strip()).param("b", in.body().strip()).param("e", id).param("p", position).update() == 0) {
            throw new ConflictException("That email was sent already, or does not exist.");
        }
        return get(id);
    }

    /** The owner read every email: schedule them. Step 1 goes at the next sending slot. */
    @Transactional
    public Enrolment approve(long id, Integer version, CurrentUser user) {
        Enrolment e = owned(id, user);
        if (!Set.of("REVIEW", "FAILED").contains(e.status())) {
            throw new ConflictException("Only a sequence waiting for review is approved.");
        }
        if (version != null && version != e.version()) {
            throw new ConflictException("This sequence changed. Reload and try again.");
        }
        List<String> problems = new ArrayList<>();
        for (Email m : e.emails()) {
            if (m.body().length() < 40 || m.body().equals(steps(e.sequenceId()).stream().filter(s -> s.position() == m.position())
                    .findFirst().map(Step::body).orElse(""))) {
                problems.add("Email " + m.position() + " still shows the template's instructions: write the email first.");
            }
        }
        if (!problems.isEmpty()) {
            throw new RuleViolationException(problems);
        }
        jdbc.sql("""
                UPDATE sequence_enrolment SET status = 'ACTIVE', approved_at = CURRENT_TIMESTAMP(3), next_send_on = :d, error = NULL,
                    version = version + 1
                WHERE id = :id
                """).param("d", today()).param("id", id).update();
        audit.record("sequence_enrolment", id, AuditLog.Action.UPDATE, Map.of("status", "ACTIVE",
            "edited", e.emails().stream().filter(Email::edited).count()), user.id());
        return get(id);
    }

    public Enrolment pause(long id, CurrentUser user) {
        return move(id, Set.of("ACTIVE"), "PAUSED", null, user);
    }

    public Enrolment resume(long id, CurrentUser user) {
        Enrolment e = owned(id, user);
        if (!"PAUSED".equals(e.status())) {
            throw new ConflictException("Only a paused sequence is resumed.");
        }
        jdbc.sql("""
                UPDATE sequence_enrolment SET status = 'ACTIVE', next_send_on = GREATEST(COALESCE(next_send_on, :d), :d), version = version + 1
                WHERE id = :id
                """).param("d", today()).param("id", id).update();
        return get(id);
    }

    public Enrolment stop(long id, CurrentUser user) {
        return move(id, OPEN, "STOPPED", "Stopped by " + user.name(), user);
    }

    private Enrolment move(long id, Set<String> from, String to, String reason, CurrentUser user) {
        Enrolment e = owned(id, user);
        if (!from.contains(e.status())) {
            throw new ConflictException("This sequence is " + e.status().toLowerCase(Locale.ROOT) + ".");
        }
        end(id, to, reason);
        audit.record("sequence_enrolment", id, AuditLog.Action.UPDATE, Map.of("status", to), user.id());
        return get(id);
    }

    private void end(long id, String status, String reason) {
        boolean finished = !"PAUSED".equals(status);
        jdbc.sql("""
                UPDATE sequence_enrolment SET status = :s, ended_reason = COALESCE(:r, ended_reason),
                    ended_at = IF(:f, CURRENT_TIMESTAMP(3), ended_at), waiting_reason = NULL, version = version + 1
                WHERE id = :id
                """).param("s", status).param("r", reason).param("f", finished).param("id", id).update();
        if (finished) {
            jdbc.sql("UPDATE sequence_email SET status = 'SKIPPED' WHERE enrolment_id = :id AND status = 'PLANNED'").param("id", id).update();
        }
    }

    // ---- Sending --------------------------------------------------------------------------------------------

    /** Sends every due email that passes the guardrails (ADR-0025). Returns how many were sent. */
    public int sendDue(ZonedDateTime now) {
        if (!sending) {
            return 0;
        }
        LocalDate today = now.withZoneSameInstant(zone).toLocalDate();
        int hour = now.withZoneSameInstant(zone).getHour();
        if (!days.contains(today.getDayOfWeek()) || hour < windowStart || hour >= windowEnd) {
            return 0;
        }
        List<Long> due = jdbc.sql("SELECT id FROM sequence_enrolment WHERE status = 'ACTIVE' AND next_send_on <= :d ORDER BY next_send_on, id")
            .param("d", today).query(Long.class).list();
        int sent = 0;
        for (long id : due) {
            try {
                if (sendNext(id, today)) {
                    sent++;
                }
            } catch (RuntimeException e) {
                LOG.warn("Sequence {} could not send: {}", id, e.getMessage());
                jdbc.sql("UPDATE sequence_enrolment SET waiting_reason = :w WHERE id = :id")
                    .param("w", cut("Could not send: " + e.getMessage(), 300)).param("id", id).update();
            }
        }
        return sent;
    }

    private boolean sendNext(long id, LocalDate today) {
        record Row(long contactId, long accountId, long owner, Long conversation, String to, String consent, int position, String thread,
                   Instant approvedAt, String token, boolean optedOut, String contactConsent, String contactName) {}
        Row r = jdbc.sql("""
                SELECT e.contact_id, c.account_id, e.owner_user_id, e.conversation_id, e.to_email, e.consent_basis, e.next_position,
                    e.gmail_thread_id, e.approved_at, e.unsubscribe_token, c.opted_out, c.consent_basis, c.full_name
                FROM sequence_enrolment e JOIN contact c ON c.id = e.contact_id WHERE e.id = :id AND e.status = 'ACTIVE'
                """).param("id", id).query((rs, n) -> new Row(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getObject(4, Long.class),
                rs.getString(5), rs.getString(6), rs.getInt(7), rs.getString(8), rs.getTimestamp(9).toInstant(), rs.getString(10),
                rs.getBoolean(11), rs.getString(12), rs.getString(13))).optional().orElse(null);
        if (r == null) {
            return false;
        }
        // Checks that end the sequence.
        String block = blocked(r.optedOut(), r.to(), r.contactConsent());
        if (block != null) {
            end(id, "STOPPED", block);
            return false;
        }
        if (repliedSince(r.contactId(), r.approvedAt())) {
            end(id, "REPLIED", "They replied");
            return false;
        }
        // Checks that wait.
        String wait = null;
        int sentToday = jdbc.sql("""
                SELECT COUNT(*) FROM sequence_email m JOIN sequence_enrolment e ON e.id = m.enrolment_id
                WHERE e.owner_user_id = :o AND m.sent_at >= :start
                """).param("o", r.owner()).param("start", java.sql.Timestamp.from(today.atStartOfDay(zone).toInstant()))
            .query(Integer.class).single();
        Instant lastToThem = jdbc.sql("""
                SELECT MAX(m.sent_at) FROM sequence_email m JOIN sequence_enrolment e ON e.id = m.enrolment_id WHERE e.contact_id = :c
                """).param("c", r.contactId()).query(java.sql.Timestamp.class).optional().map(java.sql.Timestamp::toInstant).orElse(null);
        GoogleAccounts.Status g = accounts.status(r.owner());
        if (sentToday >= perSender) {
            wait = "Today's limit of " + perSender + " sequence emails from this sender is reached.";
        } else if (lastToThem != null && lastToThem.isAfter(Instant.now().minusSeconds(MIN_GAP_DAYS * 86_400L - 3600))) {
            wait = "Less than " + MIN_GAP_DAYS + " days since the last email to them.";
        } else if (!g.connected() || !g.canSend()) {
            wait = "The sender's Gmail is not connected: GTN → My profile page → Connect Google.";
        } else if (walnut.baseUrl().isBlank()) {
            wait = "Set APP_BASE_URL so the unsubscribe link works.";
        }
        if (wait != null) {
            jdbc.sql("UPDATE sequence_enrolment SET waiting_reason = :w WHERE id = :id").param("w", wait).param("id", id).update();
            return false;
        }
        record Planned(String subject, String body) {}
        Planned m = jdbc.sql("SELECT subject, body FROM sequence_email WHERE enrolment_id = :e AND position = :p AND status = 'PLANNED'")
            .param("e", id).param("p", r.position()).query((rs, n) -> new Planned(rs.getString(1), rs.getString(2))).optional().orElse(null);
        if (m == null) {
            end(id, "FINISHED", null);
            return false;
        }
        String token = accounts.accessToken(r.owner()).orElse(null);
        if (token == null) {
            jdbc.sql("UPDATE sequence_enrolment SET waiting_reason = 'The Google connection has expired: connect again.' WHERE id = :id")
                .param("id", id).update();
            return false;
        }
        String link = walnut.baseUrl() + "/u/" + r.token();
        String body = m.body() + "\n\n--\nIf you'd rather not hear from me, unsubscribe here: " + link;
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("List-Unsubscribe", "<" + link + ">");
        headers.put("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
        String inReplyTo = jdbc.sql("""
                SELECT rfc_message_id FROM sequence_email WHERE enrolment_id = :e AND status = 'SENT' ORDER BY position DESC LIMIT 1
                """).param("e", id).query(String.class).optional().orElse(null);
        String senderName = jdbc.sql("SELECT full_name FROM app_user WHERE id = :u").param("u", r.owner()).query(String.class).single();
        String raw = Mime.plainText(senderName, accounts.emailOf(r.owner()), r.contactName(), r.to(), m.subject(), body, inReplyTo, headers);
        GoogleApi.Sent sent;
        try {
            sent = google.send(token, raw, r.thread());
        } catch (GoogleApi.GoogleException ex) {
            jdbc.sql("UPDATE sequence_enrolment SET waiting_reason = :w WHERE id = :id")
                .param("w", cut((ex.unauthorized() ? "Gmail refused: connect Google again. " : "Gmail didn't send it: ") + ex.getMessage(), 300))
                .param("id", id).update();
            return false;
        }
        String rfc;
        try {
            rfc = google.message(token, sent.id()).rfcMessageId();
        } catch (GoogleApi.GoogleException ex) {
            rfc = null;
        }
        jdbc.sql("""
                UPDATE sequence_email SET status = 'SENT', sent_at = CURRENT_TIMESTAMP(3), gmail_message_id = :g, rfc_message_id = :r
                WHERE enrolment_id = :e AND position = :p
                """).param("g", sent.id()).param("r", rfc).param("e", id).param("p", r.position()).update();
        Integer nextWait = jdbc.sql("SELECT wait_days FROM sequence_email WHERE enrolment_id = :e AND position = :p")
            .param("e", id).param("p", r.position() + 1).query(Integer.class).optional().orElse(null);
        LocalDate next = nextWait == null ? null : today.plusDays(Math.max(MIN_GAP_DAYS, nextWait));
        jdbc.sql("""
                UPDATE sequence_enrolment SET gmail_thread_id = :t, next_position = next_position + 1, next_send_on = :n, waiting_reason = NULL,
                    status = IF(:n IS NULL, 'FINISHED', status), ended_at = IF(:n IS NULL, CURRENT_TIMESTAMP(3), NULL),
                    ended_reason = IF(:n IS NULL, 'All emails sent', NULL), version = version + 1
                WHERE id = :id
                """).param("t", sent.threadId()).param("n", next).param("id", id).update();
        log(id, r.accountId(), r.contactId(), r.conversation(), r.owner(), r.position(), m.subject(), today, next);
        return true;
    }

    /** Logs the email on the contact's conversation, opening one with the first email. */
    private void log(long id, long accountId, long contactId, Long conversation, long owner, int position, String subject, LocalDate today,
                     LocalDate next) {
        CurrentUser user = users.findByEmail(jdbc.sql("SELECT email FROM app_user WHERE id = :u").param("u", owner).query(String.class).single())
            .orElse(null);
        if (user == null) {
            return;
        }
        String summary = "Sequence email " + position + " sent: " + subject;
        LocalDate due = next == null ? today.plusDays(7) : next;
        try {
            if (conversation == null) {
                long c = conversations.startFromSequenceEmail(accountId, contactId, subject, summary, today, due, user);
                jdbc.sql("UPDATE sequence_enrolment SET conversation_id = :c WHERE id = :id").param("c", c).param("id", id).update();
            } else {
                conversations.logTouch(conversation, "EMAIL", today, summary, "NO_REPLY", "Check for a reply", due, user);
            }
        } catch (RuntimeException e) {
            LOG.warn("Sequence {} sent, but the conversation was not updated: {}", id, e.getMessage());
        }
    }

    /** A reply since the sequence started: found in Gmail, logged by a person, or the conversation moved on. */
    private boolean repliedSince(long contactId, Instant since) {
        java.sql.Timestamp t = java.sql.Timestamp.from(since);
        return jdbc.sql("SELECT COUNT(*) FROM prospect_reply WHERE person_type = 'CONTACT' AND person_id = :c AND created_at >= :t")
                .param("c", contactId).param("t", t).query(Integer.class).single() > 0
            || jdbc.sql("""
                SELECT COUNT(*) FROM activity WHERE contact_id = :c AND created_at >= :t
                  AND outcome IN ('REPLIED', 'MEETING_BOOKED', 'NOT_INTERESTED', 'REFERRED_ON')
                """).param("c", contactId).param("t", t).query(Integer.class).single() > 0
            || jdbc.sql("""
                SELECT COUNT(*) FROM conversation WHERE contact_id = :c AND updated_at >= :t
                  AND status IN ('REPLIED', 'MEETING_BOOKED', 'OPPORTUNITY', 'CLOSED')
                """).param("c", contactId).param("t", t).query(Integer.class).single() > 0;
    }

    // ---- Replies in Gmail -----------------------------------------------------------------------------------

    /** Looks for replies in the Gmail threads of running sequences; a reply ends the sequence and goes to Replies. */
    public int checkReplies() {
        record Thread(long id, long owner, long contactId, Long conversation, String thread) {}
        List<Thread> threads = jdbc.sql("""
                SELECT id, owner_user_id, contact_id, conversation_id, gmail_thread_id FROM sequence_enrolment
                WHERE status IN ('ACTIVE', 'PAUSED') AND gmail_thread_id IS NOT NULL
                """).query((rs, n) -> new Thread(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getObject(4, Long.class), rs.getString(5))).list();
        int found = 0;
        for (Thread t : threads) {
            try {
                String token = accounts.status(t.owner()).canRead() ? accounts.accessToken(t.owner()).orElse(null) : null;
                String me = accounts.emailOf(t.owner());
                if (token == null || me == null) {
                    continue;
                }
                for (GoogleApi.Message m : google.thread(token, t.thread())) {
                    if (m.autoReply() || m.from() == null || m.from().toLowerCase(Locale.ROOT).contains(me.toLowerCase(Locale.ROOT))) {
                        continue;
                    }
                    end(t.id(), "REPLIED", "They replied");
                    LocalDate on = Instant.ofEpochMilli(m.internalDateMillis()).atZone(zone).toLocalDate();
                    replies.fromSequence(t.owner(), t.contactId(), currentConversation(t.id(), t.conversation()), m.id(),
                        unescape(m.snippet()), on);
                    found++;
                    break;
                }
            } catch (RuntimeException e) {
                LOG.warn("Checking sequence {} for replies failed: {}", t.id(), e.getMessage());
            }
        }
        return found;
    }

    private Long currentConversation(long id, Long fallback) {
        return jdbc.sql("SELECT conversation_id FROM sequence_enrolment WHERE id = :id").param("id", id).query(Long.class).optional()
            .orElse(fallback);
    }

    private static String unescape(String s) {
        return s == null ? null : s.replace("&#39;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&amp;", "&");
    }

    // ---- Unsubscribe ----------------------------------------------------------------------------------------

    /** Who the link is for (first name only), or empty for an unknown link. */
    public java.util.Optional<String> unsubscribeName(String token) {
        return jdbc.sql("SELECT c.full_name FROM sequence_enrolment e JOIN contact c ON c.id = e.contact_id WHERE e.unsubscribe_token = :t")
            .param("t", token).query(String.class).optional().map(n -> n.trim().split("\\s+")[0]);
    }

    /** One click: opt the contact out, suppress the address, stop every sequence to it. False for an unknown link. */
    @Transactional
    public boolean unsubscribe(String token) {
        record Row(long id, long contactId, String email) {}
        Row r = jdbc.sql("SELECT id, contact_id, to_email FROM sequence_enrolment WHERE unsubscribe_token = :t").param("t", token)
            .query((rs, n) -> new Row(rs.getLong(1), rs.getLong(2), rs.getString(3))).optional().orElse(null);
        if (r == null) {
            return false;
        }
        jdbc.sql("UPDATE contact SET opted_out = TRUE, opted_out_at = COALESCE(opted_out_at, CURRENT_TIMESTAMP(3)) WHERE id = :c")
            .param("c", r.contactId()).update();
        jdbc.sql("INSERT IGNORE INTO email_suppression (email, reason) VALUES (:e, 'UNSUBSCRIBED')").param("e", r.email()).update();
        List<Long> open = jdbc.sql("SELECT id FROM sequence_enrolment WHERE (contact_id = :c OR to_email = :e) AND status IN (:open)")
            .param("c", r.contactId()).param("e", r.email()).param("open", OPEN).query(Long.class).list();
        open.forEach(id -> end(id, "STOPPED", "They unsubscribed"));
        jdbc.sql("UPDATE prospect_queue_item SET status = 'EXPIRED' WHERE person_type = 'CONTACT' AND person_id = :c AND status = 'QUEUED'")
            .param("c", r.contactId()).update();
        audit.record("sequence_enrolment", r.id(), AuditLog.Action.UPDATE, Map.of("unsubscribed", true), null);
        return true;
    }

    // ---- Reading --------------------------------------------------------------------------------------------

    public Overview overview(CurrentUser user) {
        user.require(Role.PIPELINE_EDITORS);
        List<Enrolment> mine = list("e.owner_user_id = :u AND (e.status IN (:open) OR e.ended_at > NOW() - INTERVAL 30 DAY)",
            Map.of("u", user.id(), "open", OPEN));
        return new Overview(sending, writer.enabled(), String.format("%02d:00–%02d:00 IST", windowStart, windowEnd)
            + (days.size() == 5 && !days.contains(DayOfWeek.SATURDAY) && !days.contains(DayOfWeek.SUNDAY) ? ", Monday to Friday" : ""), perSender,
            mine, templates(false));
    }

    public List<Enrolment> forContact(long contactId) {
        return list("e.contact_id = :c", Map.of("c", contactId));
    }

    public Enrolment get(long id) {
        return list("e.id = :id", Map.of("id", id)).stream().findFirst().orElseThrow(() -> new NotFoundException("Sequence", id));
    }

    private Enrolment owned(long id, CurrentUser user) {
        user.require(Role.PIPELINE_EDITORS);
        Enrolment e = get(id);
        long owner = jdbc.sql("SELECT owner_user_id FROM sequence_enrolment WHERE id = :id").param("id", id).query(Long.class).single();
        if (owner != user.id() && !user.hasAnyRole(Role.NETWORK_MANAGERS)) {
            throw new AccessDeniedException("This sequence is someone else's.");
        }
        return e;
    }

    private List<Enrolment> list(String where, Map<String, Object> params) {
        List<Enrolment> list = jdbc.sql("""
                SELECT e.id, e.sequence_id, s.name, e.contact_id, c.full_name, c.title, a.id, a.name, u.full_name, e.conversation_id,
                    e.to_email, e.status, e.next_position, e.next_send_on, e.waiting_reason, e.ended_reason, e.error,
                    (SELECT COUNT(*) FROM sequence_email m WHERE m.enrolment_id = e.id AND m.status = 'SENT'),
                    (SELECT COUNT(*) FROM sequence_email m WHERE m.enrolment_id = e.id), e.created_at, e.version
                FROM sequence_enrolment e
                JOIN email_sequence s ON s.id = e.sequence_id
                JOIN contact c ON c.id = e.contact_id
                JOIN account a ON a.id = c.account_id
                JOIN app_user u ON u.id = e.owner_user_id
                WHERE\s""" + where + " ORDER BY e.id DESC LIMIT 200")
            .params(params)
            .query((rs, n) -> new Enrolment(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getString(5), rs.getString(6),
                rs.getLong(7), rs.getString(8), rs.getString(9), rs.getObject(10, Long.class), rs.getString(11), rs.getString(12),
                rs.getInt(13), rs.getObject(14, LocalDate.class), rs.getString(15), rs.getString(16), rs.getString(17), rs.getInt(18),
                rs.getInt(19), rs.getTimestamp(20).toInstant(), rs.getInt(21), List.of()))
            .list();
        return list.stream().map(e -> new Enrolment(e.id(), e.sequenceId(), e.sequenceName(), e.contactId(), e.contactName(), e.contactTitle(),
            e.accountId(), e.accountName(), e.ownerName(), e.conversationId(), e.toEmail(), e.status(), e.nextPosition(), e.nextSendOn(),
            e.waitingReason(), e.endedReason(), e.error(), e.sent(), e.steps(), e.createdAt(), e.version(), emails(e.id()))).toList();
    }

    private List<Email> emails(long id) {
        return jdbc.sql("""
                SELECT position, wait_days, subject, body, subject <> agent_subject OR body <> agent_body, status, sent_at
                FROM sequence_email WHERE enrolment_id = :e ORDER BY position
                """).param("e", id).query((rs, n) -> new Email(rs.getInt(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getBoolean(5),
                rs.getString(6), rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant())).list();
    }

    private static String token() {
        byte[] b = new byte[24];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }
}
