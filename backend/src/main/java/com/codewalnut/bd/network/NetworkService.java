package com.codewalnut.bd.network;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.codewalnut.bd.reporting.NetworkQueries;
import com.codewalnut.bd.shared.AuditLog;
import com.codewalnut.bd.shared.ConflictException;
import com.codewalnut.bd.shared.CurrentUser;
import com.codewalnut.bd.shared.NotFoundException;
import com.codewalnut.bd.shared.RuleViolationException;

/**
 * GTN, Go To Network (ADR-0017): the rules that move a person through Initiate → Responded → Intro made → Call
 * scheduled → Warm commitment → Monthly beep, with Unresponsive and its Why? diagnosis. Every move is recorded.
 * Nothing is sent from here: people send on LinkedIn, WhatsApp or email and confirm (ADR-0005, ADR-0018).
 */
@Service
public class NetworkService {

    static final Set<String> STAGES = Set.copyOf(NetworkQueries.STAGES);
    static final Set<String> REASONS = Set.of("NOT_SEEN", "INERTIA", "NOT_RIGHT_TIME");
    static final Set<String> CHANNELS = Set.of("LINKEDIN", "EMAIL", "WHATSAPP", "CALL", "MEETING", "OTHER");
    static final Set<String> COMMITMENTS = Set.of("REFERRAL", "INTRO_ONWARD", "PILOT_TALK", "FOLLOW_UP", "KEEP_POSTED");

    /** Start working someone: their first message has been sent (Initiate). */
    public record Start(Long connectionId, String channel, String summary, Long templateId, LocalDate sentOn) {}

    /**
     * Something happened on a track. {@code action}: SENT, REPLIED, DIAGNOSED, INTRO_DONE, CALL_BOOKED, CALL_HELD,
     * NO_SHOW, COMMITTED or MOVED. The other fields are used by the actions that need them.
     */
    public record Action(String action, String channel, String summary, Long templateId, String outcome, String reason,
                         LocalDate revisitOn, String revisitReason, LocalDate callOn, String commitmentKind,
                         String commitment, String stage, String note, Integer version) {}

    /** Change the next action, the beep interval or the owner without moving the stage. */
    public record Change(String nextAction, LocalDate nextActionOn, Integer beepIntervalDays, Long ownerId, Integer version) {}

    private record State(long id, String stage, String whyReason, int nudgesSent, int beepIntervalDays, LocalDate callOn,
                         String lastChannel, int version, boolean replyCheck) {}

    private final JdbcClient jdbc;
    private final GtnProperties gtn;
    private final AuditLog audit;

    private final ApplicationEventPublisher events;

    NetworkService(JdbcClient jdbc, GtnProperties gtn, AuditLog audit, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.gtn = gtn;
        this.audit = audit;
        this.events = events;
    }

    public LocalDate today() {
        return LocalDate.now(ZoneId.of(gtn.zone()));
    }

    @Transactional
    public long start(Start in, CurrentUser user) {
        List<String> problems = new ArrayList<>();
        if (in.connectionId() == null || jdbc.sql("SELECT COUNT(*) FROM connection WHERE id = :id")
                .param("id", in.connectionId()).query(Integer.class).single() == 0) {
            throw new RuleViolationException(List.of("Choose someone from Connections."));
        }
        String owner = jdbc.sql("""
                SELECT u.full_name FROM network_track t JOIN app_user u ON u.id = t.owner_user_id WHERE t.connection_id = :c
                """).param("c", in.connectionId()).query(String.class).optional().orElse(null);
        if (owner != null) {
            throw new ConflictException("This person is already on GTN with " + owner + ". Open their track, or ask "
                + Suggestions.firstName(owner) + " or a manager to hand them over.");
        }
        String channel = channel(in.channel(), problems);
        String summary = summary(in.summary(), "First message sent", problems);
        LocalDate today = today();
        LocalDate sentOn = in.sentOn() == null ? today : in.sentOn();
        if (sentOn.isAfter(today)) {
            problems.add("The first message can't be sent in the future.");
        }
        template(in.templateId(), problems);
        if (!problems.isEmpty()) {
            throw new RuleViolationException(problems);
        }
        var key = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO network_track (connection_id, owner_user_id, stage, next_action, next_action_on, last_out_on,
                    last_channel, beep_interval_days)
                VALUES (:c, :owner, 'INITIATE', 'Check for a reply', :next, :sent, :channel, :beep)
                """).param("c", in.connectionId()).param("owner", user.id())
            .param("next", sentOn.plusDays(gtn.unresponsiveDays())).param("sent", sentOn).param("channel", channel)
            .param("beep", gtn.beepIntervalDays()).update(key);
        long id = key.getKey().longValue();
        change(id, null, "INITIATE", user.id(), "First message sent");
        touch(id, user.id(), channel, "OUT", "OUTREACH", in.templateId(), summary, sentOn);
        audit.record("network_track", id, AuditLog.Action.CREATE, Map.of("stage", "INITIATE"), user.id());
        return id;
    }

    @Transactional
    public void act(long id, Action in, CurrentUser user) {
        State s = lock(id, in.version());
        requireWorker(id, user);
        String action = upper(in.action());
        LocalDate today = today();
        List<String> problems = new ArrayList<>();
        if (action == null) {
            throw new RuleViolationException(List.of("Say what happened."));
        }
        switch (action) {
            case "SENT" -> sent(s, in, today, user, problems);
            case "REPLIED" -> replied(s, in, today, user, problems);
            case "DIAGNOSED" -> diagnosed(s, in, today, user, problems);
            case "INTRO_DONE" -> {
                allowed(s, Set.of("RESPONDED"), "The intro comes after they reply.", problems);
                done(problems);
                if (in.summary() != null && !in.summary().isBlank()) {
                    touch(id, user.id(), channel(in.channel(), problems), "OUT", "INTRO", in.templateId(),
                        summary(in.summary(), "Intro", problems), today);
                }
                move(s, "INTRO_MADE", "Book a call", today.plusDays(3), user.id(), note(in, "Intro made"));
            }
            case "CALL_BOOKED" -> {
                allowed(s, Set.of("RESPONDED", "INTRO_MADE", "MONTHLY_BEEP", "CALL_SCHEDULED"),
                    "A call is booked after they reply, after the intro, or when a beep re-opens the conversation.", problems);
                if (in.callOn() == null) {
                    problems.add("Give the date of the call.");
                } else if (in.callOn().isBefore(today.minusDays(1))) {
                    problems.add("The call date is in the past. Use \"Call held\" if it already happened.");
                }
                done(problems);
                jdbc.sql("UPDATE network_track SET call_on = :on, call_at = NULL WHERE id = :id").param("on", in.callOn()).param("id", id).update();
                if ("CALL_SCHEDULED".equals(s.stage())) {
                    next(id, "Hold the call", in.callOn());
                    change(id, s.stage(), s.stage(), user.id(), note(in, "Call rescheduled to " + in.callOn()));
                } else {
                    move(s, "CALL_SCHEDULED", "Hold the call", in.callOn(), user.id(), note(in, "Call booked for " + in.callOn()));
                }
                callBooked(id, in.callOn());
            }
            case "CALL_HELD" -> {
                allowed(s, Set.of("CALL_SCHEDULED"), "Book the call first.", problems);
                String kind = commitmentKind(in.commitmentKind(), problems);
                String what = commitmentText(in.commitment(), problems);
                done(problems);
                touch(id, user.id(), "CALL", "OUT", "CALL", null, "Call held. " + what, today);
                commit(id, kind, what, user.id());
                move(s, "WARM_COMMITMENT", "First monthly beep", today.plusDays(s.beepIntervalDays()), user.id(),
                    note(in, "Call held: " + what));
            }
            case "NO_SHOW" -> {
                allowed(s, Set.of("CALL_SCHEDULED"), "Only a booked call can be a no-show.", problems);
                done(problems);
                touch(id, user.id(), "CALL", "OUT", "CALL", null, summary(in.summary(), "No-show: the call didn't happen", problems), today);
                next(id, "Reschedule the call", today.plusDays(1));
                change(id, s.stage(), s.stage(), user.id(), note(in, "No-show"));
            }
            case "COMMITTED" -> {
                allowed(s, Set.of("INTRO_MADE", "CALL_SCHEDULED"), "A commitment follows the intro or the call.", problems);
                String kind = commitmentKind(in.commitmentKind(), problems);
                String what = commitmentText(in.commitment(), problems);
                done(problems);
                commit(id, kind, what, user.id());
                move(s, "WARM_COMMITMENT", "First monthly beep", today.plusDays(s.beepIntervalDays()), user.id(),
                    note(in, "Committed: " + what));
            }
            case "CONFIRM_REPLY" -> {
                if (!s.replyCheck()) {
                    problems.add("There is no reply waiting to be confirmed.");
                }
                String outcome = upper(in.outcome()) == null ? "POSITIVE" : upper(in.outcome());
                if (!Set.of("POSITIVE", "NOT_RIGHT_TIME").contains(outcome)) {
                    problems.add("Say whether the reply was positive or \"not the right time\".");
                }
                if ("NOT_RIGHT_TIME".equals(outcome)) {
                    revisit(in, today, problems);
                }
                done(problems);
                jdbc.sql("UPDATE network_track SET reply_check = FALSE WHERE id = :id").param("id", id).update();
                if ("NOT_RIGHT_TIME".equals(outcome)) {
                    park(s, in, today, user.id(), "Replied by email: not the right time");
                } else {
                    change(id, s.stage(), s.stage(), user.id(), note(in, "Reply confirmed as positive"));
                }
            }
            case "MOVED" -> {
                String stage = upper(in.stage());
                if (stage == null || !STAGES.contains(stage)) {
                    problems.add("Choose a stage.");
                }
                done(problems);
                Next n = defaults(stage, s, today);
                move(s, stage, n.what(), n.on(), user.id(), note(in, "Moved by hand"));
            }
            default -> throw new RuleViolationException(List.of("Unknown action: " + in.action()));
        }
        jdbc.sql("UPDATE network_track SET version = version + 1 WHERE id = :id").param("id", id).update();
        audit.record("network_track", id, AuditLog.Action.UPDATE, Map.of("action", action), user.id());
    }

    private void sent(State s, Action in, LocalDate today, CurrentUser user, List<String> problems) {
        String channel = channel(in.channel(), problems);
        String kind = switch (s.stage()) {
            case "INITIATE" -> "OUTREACH";
            case "UNRESPONSIVE" -> "NUDGE";
            case "WARM_COMMITMENT", "MONTHLY_BEEP" -> "BEEP";
            default -> "OUTREACH";
        };
        String summary = summary(in.summary(), switch (kind) {
            case "NUDGE" -> "Nudge sent";
            case "BEEP" -> "Monthly beep sent";
            default -> "Message sent";
        }, problems);
        template(in.templateId(), problems);
        if ("UNRESPONSIVE".equals(s.stage()) && s.whyReason() == null) {
            problems.add("Say why they haven't replied first, so the right nudge goes out.");
        }
        done(problems);
        touch(s.id(), user.id(), channel, "OUT", kind, in.templateId(), summary, today);
        if ("NUDGE".equals(kind) || "BEEP".equals(kind)) {
            // When it was due, so "sent on time" can be measured (ADR-0021).
            jdbc.sql("""
                    UPDATE network_touch SET due_on = (SELECT next_action_on FROM network_track WHERE id = :t)
                    WHERE id = (SELECT id FROM (SELECT MAX(id) AS id FROM network_touch WHERE track_id = :t) x)
                    """).param("t", s.id()).update();
        }
        jdbc.sql("UPDATE network_track SET last_out_on = :today, last_channel = :ch, ask_now = FALSE WHERE id = :id")
            .param("today", today).param("ch", channel).param("id", s.id()).update();
        switch (s.stage()) {
            case "INITIATE" -> next(s.id(), "Check for a reply", today.plusDays(gtn.unresponsiveDays()));
            case "UNRESPONSIVE" -> {
                int sentSoFar = s.nudgesSent() + 1;
                jdbc.sql("UPDATE network_track SET nudges_sent = :n WHERE id = :id").param("n", sentSoFar).param("id", s.id()).update();
                if (sentSoFar >= gtn.nudgeCap()) {
                    jdbc.sql("UPDATE network_track SET revisit_reason = COALESCE(revisit_reason, :r) WHERE id = :id")
                        .param("r", "No reply after " + sentSoFar + " nudges").param("id", s.id()).update();
                    move(s, "MONTHLY_BEEP", "Monthly beep", today.plusDays(s.beepIntervalDays()), user.id(),
                        "Parked after " + sentSoFar + " nudges without a reply");
                } else {
                    next(s.id(), "Send nudge " + (sentSoFar + 1) + " of " + gtn.nudgeCap(), today.plusDays(gtn.nudgeSpacingDays()));
                }
            }
            case "WARM_COMMITMENT" -> move(s, "MONTHLY_BEEP", "Monthly beep", today.plusDays(s.beepIntervalDays()), user.id(),
                "First monthly beep sent");
            case "MONTHLY_BEEP" -> next(s.id(), "Monthly beep", today.plusDays(s.beepIntervalDays()));
            default -> {
                // Responded, Intro made, Call scheduled: the touch is logged; the next action stays.
            }
        }
    }

    private void replied(State s, Action in, LocalDate today, CurrentUser user, List<String> problems) {
        String outcome = upper(in.outcome()) == null ? "POSITIVE" : upper(in.outcome());
        if (!Set.of("POSITIVE", "NOT_RIGHT_TIME").contains(outcome)) {
            problems.add("Say whether the reply was positive or \"not the right time\".");
        }
        String channel = channel(in.channel() == null ? s.lastChannel() : in.channel(), problems);
        String summary = summary(in.summary(), "NOT_RIGHT_TIME".equals(outcome) ? "Replied: not the right time" : "Replied", problems);
        if ("NOT_RIGHT_TIME".equals(outcome)) {
            allowed(s, Set.of("INITIATE", "UNRESPONSIVE", "RESPONDED", "MONTHLY_BEEP"),
                "Use \"Moved\" to park someone at this stage.", problems);
            revisit(in, today, problems);
        }
        done(problems);
        touch(s.id(), user.id(), channel, "IN", "REPLY", null, summary, today);
        jdbc.sql("UPDATE network_track SET last_in_on = :today, nudges_sent = 0, ask_now = FALSE, reply_check = FALSE WHERE id = :id")
            .param("today", today).param("id", s.id()).update();
        if ("NOT_RIGHT_TIME".equals(outcome)) {
            park(s, in, today, user.id(), "Replied: not the right time");
            return;
        }
        switch (s.stage()) {
            case "INITIATE", "UNRESPONSIVE" -> move(s, "RESPONDED", "Make the intro", today.plusDays(2), user.id(), note(in, "Replied"));
            case "MONTHLY_BEEP" -> move(s, "CALL_SCHEDULED", "Book the call", today.plusDays(2), user.id(),
                note(in, "A beep re-opened the conversation"));
            default -> {
                // Already talking: logged on the timeline.
            }
        }
    }

    private void diagnosed(State s, Action in, LocalDate today, CurrentUser user, List<String> problems) {
        allowed(s, Set.of("UNRESPONSIVE"), "Only someone who hasn't replied gets a Why? reason.", problems);
        String reason = upper(in.reason());
        if (reason == null || !REASONS.contains(reason)) {
            problems.add("Choose why they haven't replied: not seen on LinkedIn, inertia, or not the right time.");
        }
        if ("NOT_RIGHT_TIME".equals(reason)) {
            revisit(in, today, problems);
        }
        done(problems);
        jdbc.sql("UPDATE network_track SET why_reason = :r WHERE id = :id").param("r", reason).param("id", s.id()).update();
        if ("NOT_RIGHT_TIME".equals(reason)) {
            park(s, in, today, user.id(), "Diagnosed: not the right time");
        } else {
            next(s.id(), "Send nudge " + (s.nudgesSent() + 1) + " of " + gtn.nudgeCap(), today);
            change(s.id(), s.stage(), s.stage(), user.id(), note(in, "Why? " + label(reason)));
        }
    }

    /** Park in Monthly beep with a revisit date (default next month) and the reason, kept for the beep. */
    private void park(State s, Action in, LocalDate today, Long userId, String why) {
        LocalDate on = in.revisitOn() == null ? today.plusMonths(1) : in.revisitOn();
        String reason = in.revisitReason() == null || in.revisitReason().isBlank() ? null : in.revisitReason().trim();
        jdbc.sql("UPDATE network_track SET revisit_reason = :r, why_reason = COALESCE(why_reason, :w) WHERE id = :id")
            .param("r", reason).param("w", "UNRESPONSIVE".equals(s.stage()) ? "NOT_RIGHT_TIME" : null).param("id", s.id()).update();
        move(s, "MONTHLY_BEEP", "Monthly beep (revisit)", on, userId, why + (reason == null ? "" : ": " + reason));
    }

    @Transactional
    public void change(long id, Change in, CurrentUser user) {
        State s = lock(id, in.version());
        requireWorker(id, user);
        if (in.ownerId() != null) {
            handOver(id, in.ownerId(), null, null, user);
        }
        List<String> problems = new ArrayList<>();
        String what = in.nextAction() == null || in.nextAction().isBlank() ? null : in.nextAction().trim();
        if (what != null && what.length() > 200) {
            problems.add("The next action can be at most 200 characters.");
        }
        if (in.nextActionOn() != null && in.nextActionOn().isBefore(today().minusYears(1))) {
            problems.add("The next action date looks wrong: it is more than a year ago.");
        }
        if (in.beepIntervalDays() != null && (in.beepIntervalDays() < 7 || in.beepIntervalDays() > 180)) {
            problems.add("Beeps can be every 7 to 180 days.");
        }
        if (in.ownerId() != null && jdbc.sql("SELECT COUNT(*) FROM app_user WHERE id = :id AND active = TRUE")
                .param("id", in.ownerId()).query(Integer.class).single() == 0) {
            problems.add("Choose an active user as owner.");
        }
        done(problems);
        jdbc.sql("""
                UPDATE network_track SET next_action = COALESCE(:what, next_action), next_action_on = COALESCE(:on, next_action_on),
                    beep_interval_days = COALESCE(:beep, beep_interval_days), version = version + 1
                WHERE id = :id
                """).param("what", what).param("on", in.nextActionOn()).param("beep", in.beepIntervalDays())
            .param("id", s.id()).update();
        audit.record("network_track", id, AuditLog.Action.UPDATE, Map.of("changed", true), user.id());
    }

    /** Stop working someone: the track and its timeline go; the person stays in Connections. */
    @Transactional
    public void stop(long id, CurrentUser user) {
        lock(id, null);
        requireWorker(id, user);
        jdbc.sql("DELETE FROM network_track WHERE id = :id").param("id", id).update();
        audit.record("network_track", id, AuditLog.Action.DELETE, Map.of(), user.id());
    }

    /**
     * The daily automatic moves (ADR-0017): no reply within the threshold → Unresponsive with a diagnosis due;
     * a warm commitment whose first beep is due → Monthly beep. Idempotent; returns how many tracks moved.
     */
    @Transactional
    public int sweep(LocalDate today) {
        int moved = 0;
        List<State> quiet = states("""
                stage = 'INITIATE' AND last_out_on IS NOT NULL AND last_out_on <= :cutoff
                """, Map.of("cutoff", today.minusDays(gtn.unresponsiveDays())));
        for (State s : quiet) {
            jdbc.sql("UPDATE network_track SET why_reason = NULL, nudges_sent = 0, version = version + 1 WHERE id = :id")
                .param("id", s.id()).update();
            move(s, "UNRESPONSIVE", "Diagnose: why no reply?", today, null, "No reply in " + gtn.unresponsiveDays() + " days");
            moved++;
        }
        List<State> warm = states("stage = 'WARM_COMMITMENT' AND next_action_on <= :today", Map.of("today", today));
        for (State s : warm) {
            jdbc.sql("UPDATE network_track SET version = version + 1 WHERE id = :id").param("id", s.id()).update();
            move(s, "MONTHLY_BEEP", "Monthly beep", today, null, "First monthly beep is due");
            moved++;
        }
        return moved;
    }

    // ---- team use (ADR-0021) ----

    /** The owner works the track; managers may work any. Everyone else reads. */
    void requireWorker(long trackId, CurrentUser user) {
        long owner = jdbc.sql("SELECT owner_user_id FROM network_track WHERE id = :id").param("id", trackId).query(Long.class)
            .optional().orElseThrow(() -> new NotFoundException("Network track", trackId));
        if (owner != user.id() && !user.hasAnyRole(com.codewalnut.bd.shared.Role.NETWORK_MANAGERS)) {
            throw new org.springframework.security.access.AccessDeniedException(
                "This is someone else's contact. Ask them, or a manager, to hand it over to you.");
        }
    }

    public record HandedOver(long trackId, long fromUserId, long toUserId, String toName) {}

    /**
     * Gives a contact to another active team member with a pipeline role (ADR-0021). The stage and history stay;
     * the move is on the timeline and in the audit log.
     */
    @Transactional
    public HandedOver handOver(long trackId, long toUserId, String note, Integer version, CurrentUser user) {
        State s = lock(trackId, version);
        requireWorker(trackId, user);
        long from = jdbc.sql("SELECT owner_user_id FROM network_track WHERE id = :id").param("id", trackId).query(Long.class).single();
        String toName = jdbc.sql("""
                SELECT u.full_name FROM app_user u WHERE u.id = :id AND u.active
                  AND EXISTS (SELECT 1 FROM user_role r WHERE r.user_id = u.id AND r.role IN (:roles))
                """).param("id", toUserId).param("roles", com.codewalnut.bd.shared.Role.PIPELINE_EDITORS.stream().map(Enum::name).toList())
            .query(String.class).optional()
            .orElseThrow(() -> new RuleViolationException(List.of("Hand it to an active team member who works the pipeline.")));
        if (from == toUserId) {
            throw new RuleViolationException(List.of("They already own this contact."));
        }
        String fromName = jdbc.sql("SELECT full_name FROM app_user WHERE id = :id").param("id", from).query(String.class).single();
        String text = note == null || note.isBlank() ? null : note.trim();
        if (text != null && text.length() > 300) {
            throw new RuleViolationException(List.of("Keep the hand-over note under 300 characters."));
        }
        jdbc.sql("UPDATE network_track SET owner_user_id = :to, version = version + 1 WHERE id = :id")
            .param("to", toUserId).param("id", trackId).update();
        change(trackId, s.stage(), s.stage(), user.id(), "Handed over from " + fromName + " to " + toName + (text == null ? "" : ": " + text));
        audit.record("network_track", trackId, AuditLog.Action.UPDATE, Map.of("owner", toUserId, "from", from), user.id());
        return new HandedOver(trackId, from, toUserId, toName);
    }

    // ---- Gmail and calendar (ADR-0020) ----

    /** The message just logged went out through Gmail: remember its thread so replies can be found. */
    @Transactional
    public void sentThroughGmail(long trackId, String threadId, String messageId, String rfcMessageId) {
        jdbc.sql("""
                UPDATE network_touch SET gmail_thread_id = :t, gmail_message_id = :m, rfc_message_id = :r
                WHERE id = (SELECT id FROM (SELECT MAX(id) AS id FROM network_touch WHERE track_id = :track AND direction = 'OUT') x)
                """).param("t", threadId).param("m", messageId).param("r", rfcMessageId).param("track", trackId).update();
    }

    /**
     * A reply found in Gmail: logged with its preview, flagged for the owner to confirm what it meant, and for someone
     * at Initiate or Unresponsive moved to Responded (any reply does, PRD rule 1). False when already logged.
     */
    @Transactional
    public boolean emailReply(long trackId, String gmailMessageId, String threadId, String preview, LocalDate on) {
        if (jdbc.sql("SELECT COUNT(*) FROM network_touch WHERE gmail_message_id = :m").param("m", gmailMessageId)
                .query(Integer.class).single() > 0) {
            return false;
        }
        State s = lock(trackId, null);
        long owner = jdbc.sql("SELECT owner_user_id FROM network_track WHERE id = :id").param("id", trackId).query(Long.class).single();
        String text = preview == null || preview.isBlank() ? "(no preview)" : preview.strip();
        jdbc.sql("""
                INSERT INTO network_touch (track_id, user_id, channel, direction, kind, summary, occurred_on, gmail_thread_id,
                    gmail_message_id)
                VALUES (:t, :u, 'EMAIL', 'IN', 'REPLY', :s, :on, :thread, :m)
                """).param("t", trackId).param("u", owner)
            .param("s", "Replied by email: " + (text.length() > 400 ? text.substring(0, 400) + "…" : text))
            .param("on", on).param("thread", threadId).param("m", gmailMessageId).update();
        jdbc.sql("""
                UPDATE network_track SET last_in_on = GREATEST(COALESCE(last_in_on, :on), :on), nudges_sent = 0, ask_now = FALSE,
                    reply_check = TRUE, version = version + 1
                WHERE id = :id
                """).param("on", on).param("id", trackId).update();
        if ("INITIATE".equals(s.stage()) || "UNRESPONSIVE".equals(s.stage())) {
            move(s, "RESPONDED", "Make the intro", today().plusDays(2), null, "Reply found in Gmail");
        }
        // The owner confirms what the reply meant today, whatever the stage.
        jdbc.sql("UPDATE network_track SET next_action_on = LEAST(next_action_on, :today) WHERE id = :id")
            .param("today", today()).param("id", trackId).update();
        long connection = jdbc.sql("SELECT connection_id FROM network_track WHERE id = :id").param("id", trackId).query(Long.class).single();
        events.publishEvent(new EmailReplyFound(trackId, owner, connection, gmailMessageId, preview, on));
        return true;
    }

    /** A call booked on the profile page (ADR-0020): Call scheduled at that time, from any stage. */
    @Transactional
    public void booked(long trackId, java.time.Instant at, String eventId, String touchSummary) {
        State s = lock(trackId, null);
        long owner = jdbc.sql("SELECT owner_user_id FROM network_track WHERE id = :id").param("id", trackId).query(Long.class).single();
        LocalDate day = at.atZone(ZoneId.of(gtn.zone())).toLocalDate();
        jdbc.sql("""
                UPDATE network_track SET call_on = :day, call_at = :at, calendar_event_id = :e, ask_now = FALSE, version = version + 1
                WHERE id = :id
                """).param("day", day).param("at", java.sql.Timestamp.from(at)).param("e", eventId).param("id", trackId).update();
        jdbc.sql("""
                INSERT INTO network_touch (track_id, user_id, channel, direction, kind, summary, occurred_on)
                VALUES (:t, :u, 'OTHER', 'IN', 'VISIT', :s, :on)
                """).param("t", trackId).param("u", owner).param("s", touchSummary).param("on", today()).update();
        if ("CALL_SCHEDULED".equals(s.stage())) {
            next(trackId, "Hold the call", day);
            change(trackId, s.stage(), s.stage(), null, "Call booked on the profile page");
        } else {
            move(s, "CALL_SCHEDULED", "Hold the call", day, null, "Call booked on the profile page");
        }
        callBooked(trackId, day);
    }

    private void callBooked(long trackId, LocalDate on) {
        var t = jdbc.sql("SELECT connection_id, owner_user_id FROM network_track WHERE id = :id").param("id", trackId)
            .query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2)}).single();
        events.publishEvent(new CallBooked(trackId, t[0], t[1], on));
    }

    // ---- helpers ----

    private record Next(String what, LocalDate on) {}

    /** The next action a stage starts with when someone moves a track by hand. */
    private Next defaults(String stage, State s, LocalDate today) {
        return switch (stage) {
            case "INITIATE" -> new Next("Check for a reply", today.plusDays(gtn.unresponsiveDays()));
            case "RESPONDED" -> new Next("Make the intro", today.plusDays(2));
            case "UNRESPONSIVE" -> new Next(s.whyReason() == null ? "Diagnose: why no reply?" : "Send a nudge", today);
            case "INTRO_MADE" -> new Next("Book a call", today.plusDays(3));
            case "CALL_SCHEDULED" -> s.callOn() != null && !s.callOn().isBefore(today)
                ? new Next("Hold the call", s.callOn()) : new Next("Book the call", today.plusDays(2));
            case "WARM_COMMITMENT" -> new Next("First monthly beep", today.plusDays(s.beepIntervalDays()));
            default -> new Next("Monthly beep", today.plusDays(s.beepIntervalDays()));
        };
    }

    private void move(State s, String to, String nextAction, LocalDate nextOn, Long userId, String note) {
        jdbc.sql("""
                UPDATE network_track SET stage = :to, stage_since = CURRENT_TIMESTAMP(3), next_action = :what,
                    next_action_on = :on, ask_now = FALSE
                WHERE id = :id
                """).param("to", to).param("what", nextAction).param("on", nextOn).param("id", s.id()).update();
        if ("UNRESPONSIVE".equals(to) && !"UNRESPONSIVE".equals(s.stage())) {
            jdbc.sql("UPDATE network_track SET why_reason = NULL, nudges_sent = 0 WHERE id = :id").param("id", s.id()).update();
        }
        change(s.id(), s.stage(), to, userId, note);
    }

    private void next(long id, String what, LocalDate on) {
        jdbc.sql("UPDATE network_track SET next_action = :what, next_action_on = :on WHERE id = :id")
            .param("what", what).param("on", on).param("id", id).update();
    }

    private void change(long id, String from, String to, Long userId, String note) {
        jdbc.sql("""
                INSERT INTO network_stage_change (track_id, from_stage, to_stage, changed_by, note)
                VALUES (:id, :from, :to, :user, :note)
                """).param("id", id).param("from", from).param("to", to).param("user", userId)
            .param("note", note == null ? null : note.length() > 500 ? note.substring(0, 500) : note).update();
    }

    private void touch(long trackId, long userId, String channel, String direction, String kind, Long templateId,
                       String summary, LocalDate on) {
        jdbc.sql("""
                INSERT INTO network_touch (track_id, user_id, channel, direction, kind, template_id, summary, occurred_on)
                VALUES (:t, :u, :ch, :dir, :kind, :tpl, :summary, :on)
                """).param("t", trackId).param("u", userId).param("ch", channel).param("dir", direction).param("kind", kind)
            .param("tpl", templateId).param("summary", summary).param("on", on).update();
    }

    private void commit(long trackId, String kind, String what, long userId) {
        jdbc.sql("""
                INSERT INTO network_commitment (track_id, kind, description, recorded_by) VALUES (:t, :k, :d, :u)
                """).param("t", trackId).param("k", kind).param("d", what).param("u", userId).update();
    }

    private State lock(long id, Integer version) {
        State s = jdbc.sql("""
                SELECT id, stage, why_reason, nudges_sent, beep_interval_days, call_on, last_channel, version, reply_check
                FROM network_track WHERE id = :id FOR UPDATE
                """).param("id", id).query((rs, n) -> state(rs)).optional()
            .orElseThrow(() -> new NotFoundException("Network track", id));
        if (version != null && version != s.version()) {
            throw new ConflictException("Someone else updated this person's track. Reload and try again.");
        }
        return s;
    }

    private List<State> states(String where, Map<String, ?> params) {
        return jdbc.sql("""
                SELECT id, stage, why_reason, nudges_sent, beep_interval_days, call_on, last_channel, version, reply_check
                FROM network_track WHERE\s""" + where + " FOR UPDATE").params(params).query((rs, n) -> state(rs)).list();
    }

    private static State state(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new State(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5),
            rs.getObject(6, LocalDate.class), rs.getString(7), rs.getInt(8), rs.getBoolean(9));
    }

    private void template(Long templateId, List<String> problems) {
        if (templateId != null && jdbc.sql("SELECT COUNT(*) FROM message_template WHERE id = :id").param("id", templateId)
                .query(Integer.class).single() == 0) {
            problems.add("The message template no longer exists.");
        }
    }

    private static void allowed(State s, Set<String> from, String message, List<String> problems) {
        if (!from.contains(s.stage())) {
            problems.add(message);
        }
    }

    private static void revisit(Action in, LocalDate today, List<String> problems) {
        if (in.revisitOn() != null && !in.revisitOn().isAfter(today)) {
            problems.add("The revisit date should be in the future.");
        }
        if (in.revisitReason() != null && in.revisitReason().trim().length() > 300) {
            problems.add("The reason can be at most 300 characters.");
        }
    }

    private static String channel(String raw, List<String> problems) {
        String c = upper(raw);
        if (c == null || !CHANNELS.contains(c)) {
            problems.add("Choose the channel: LinkedIn, email, WhatsApp, call, meeting or other.");
            return "OTHER";
        }
        return c;
    }

    private static String summary(String raw, String otherwise, List<String> problems) {
        String s = raw == null || raw.isBlank() ? otherwise : raw.trim();
        if (s.length() > 2000) {
            problems.add("The message or note can be at most 2,000 characters.");
            return s.substring(0, 2000);
        }
        return s;
    }

    private static String commitmentKind(String raw, List<String> problems) {
        String k = upper(raw);
        if (k == null || !COMMITMENTS.contains(k)) {
            problems.add("Say what they committed to: a referral, an intro onward, a pilot talk, a follow-up, or keep them posted.");
        }
        return k;
    }

    private static String commitmentText(String raw, List<String> problems) {
        if (raw == null || raw.isBlank()) {
            problems.add("Describe the commitment in a few words.");
            return "";
        }
        if (raw.trim().length() > 500) {
            problems.add("The commitment can be at most 500 characters.");
            return raw.trim().substring(0, 500);
        }
        return raw.trim();
    }

    private static String note(Action in, String otherwise) {
        return in.note() == null || in.note().isBlank() ? otherwise : otherwise + ": " + in.note().trim();
    }

    private static void done(List<String> problems) {
        if (!problems.isEmpty()) {
            throw new RuleViolationException(problems);
        }
    }

    static String label(String reason) {
        return switch (reason) {
            case "NOT_SEEN" -> "not seen on LinkedIn";
            case "INERTIA" -> "inertia to respond";
            default -> "not the right time";
        };
    }

    private static String upper(String s) {
        return s == null || s.isBlank() ? null : s.trim().toUpperCase(Locale.ROOT);
    }
}
