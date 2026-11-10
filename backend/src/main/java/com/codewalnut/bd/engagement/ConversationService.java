package com.codewalnut.bd.engagement;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.codewalnut.bd.reporting.ConversationQueries;
import com.codewalnut.bd.reporting.OpportunityQueries;
import com.codewalnut.bd.shared.AuditLog;
import com.codewalnut.bd.shared.ConflictException;
import com.codewalnut.bd.shared.CurrentUser;
import com.codewalnut.bd.shared.NotFoundException;
import com.codewalnut.bd.shared.RuleViolationException;

/**
 * Conversations (backlog item 3): contacts first, then conversations with them; conversations are the
 * early sign of future business. Each update is logged as an activity in the conversation, and moves
 * its status and next step. People talk on LinkedIn, email or phone themselves and record it here;
 * nothing is sent from the app (ADR-0005).
 */
@Service
class ConversationService {

    static final Set<String> STATUSES = Set.of("STARTED", "REPLIED", "MEETING_BOOKED", "OPPORTUNITY", "GONE_QUIET", "CLOSED");

    /** Opens a conversation with its first touch. Give a contact from the account, or a person's name. */
    record Start(Long accountId, Long contactId, String personName, String topic, String channel,
                 LocalDate occurredOn, String summary, String outcome, String nextStep, LocalDate nextStepDue,
                 Long eventId) {}

    /** A new touch in the conversation. Status is optional: without it, the outcome decides. */
    record Update(String channel, LocalDate occurredOn, String summary, String outcome, String status,
                  String nextStep, LocalDate nextStepDue, Integer version) {}

    /** Change status, next step, owner or linked opportunity without logging a touch. */
    record Change(String status, String nextStep, LocalDate nextStepDue, Long ownerId, Long opportunityId,
                  Integer version) {}

    private final JdbcClient jdbc;
    private final ActivityService activities;
    private final ConversationQueries queries;
    private final OpportunityQueries opportunities;
    private final AuditLog audit;

    ConversationService(JdbcClient jdbc, ActivityService activities, ConversationQueries queries,
                        OpportunityQueries opportunities, AuditLog audit) {
        this.jdbc = jdbc;
        this.activities = activities;
        this.queries = queries;
        this.opportunities = opportunities;
        this.audit = audit;
    }

    @Transactional
    long start(Start in, CurrentUser user) {
        List<String> problems = new ArrayList<>();
        if (in.accountId() == null || jdbc.sql("SELECT COUNT(*) FROM account WHERE id = :id").param("id", in.accountId())
                .query(Integer.class).single() == 0) {
            // Checked here as well as by the activity rules, so a missing account is a clear message, not an error.
            throw new RuleViolationException(List.of("Choose an existing account."));
        }
        String person = blank(in.personName()) ? null : in.personName().trim();
        if (in.contactId() == null && person == null) {
            problems.add("Choose a contact or type the person's name.");
        }
        if (person != null && person.length() > 200) {
            problems.add("The person's name can be at most 200 characters.");
        }
        String topic = blank(in.topic()) ? null : in.topic().trim();
        if (topic == null) {
            problems.add("Give the conversation a short topic, for example \"React team for the payments rebuild\".");
        } else if (topic.length() > 200) {
            problems.add("The topic can be at most 200 characters.");
        }
        nextStepProblems(in.nextStep(), in.nextStepDue(), problems);
        if (in.eventId() != null && jdbc.sql("SELECT COUNT(*) FROM bd_event WHERE id = :id").param("id", in.eventId())
                .query(Integer.class).single() == 0) {
            problems.add("Choose an existing event.");
        }
        if (!problems.isEmpty()) {
            throw new RuleViolationException(problems);
        }
        String status = statusFor(in.outcome(), "STARTED");
        var key = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO conversation (account_id, contact_id, person_name, topic, owner_user_id, status, next_step,
                    next_step_due, last_touch_on, event_id)
                VALUES (:account, :contact, :person, :topic, :owner, :status, :next, :due, :on, :event)
                """)
            .param("account", in.accountId()).param("contact", in.contactId()).param("person", in.contactId() == null ? person : null)
            .param("topic", topic).param("owner", user.id()).param("status", status)
            .param("next", trimOrNull(in.nextStep())).param("due", in.nextStepDue())
            .param("on", in.occurredOn() == null ? LocalDate.now() : in.occurredOn()).param("event", in.eventId())
            .update(key);
        long id = key.getKey().longValue();
        // The first touch is checked by the activity rules (account exists, contact at the account, channel...).
        activities.log(new ActivityService.Input(in.accountId(), in.contactId(), in.channel(), in.occurredOn(),
            in.summary(), in.outcome()), id, user);
        audit.record("conversation", id, AuditLog.Action.CREATE, Map.of("status", status), user.id());
        return id;
    }

    @Transactional
    void update(long id, Update in, CurrentUser user) {
        var current = lockAndCheck(id, in.version());
        List<String> problems = new ArrayList<>();
        String status = in.status() == null || in.status().isBlank() ? statusFor(in.outcome(), current.status())
            : in.status().trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(status)) {
            problems.add("Choose a status.");
        }
        nextStepProblems(in.nextStep(), in.nextStepDue(), problems);
        if (!problems.isEmpty()) {
            throw new RuleViolationException(problems);
        }
        activities.log(new ActivityService.Input(current.accountId(), current.contactId(), in.channel(), in.occurredOn(),
            in.summary(), in.outcome()), id, user);
        jdbc.sql("""
                UPDATE conversation SET status = :status, next_step = :next, next_step_due = :due,
                    last_touch_on = GREATEST(last_touch_on, :on), version = version + 1
                WHERE id = :id
                """)
            .param("status", status).param("next", trimOrNull(in.nextStep())).param("due", in.nextStepDue())
            .param("on", in.occurredOn()).param("id", id).update();
        audit.record("conversation", id, AuditLog.Action.UPDATE, Map.of("status", status, "touch", true), user.id());
    }

    @Transactional
    void change(long id, Change in, CurrentUser user) {
        var current = lockAndCheck(id, in.version());
        List<String> problems = new ArrayList<>();
        String status = in.status() == null || in.status().isBlank() ? current.status()
            : in.status().trim().toUpperCase(Locale.ROOT);
        if (in.opportunityId() != null) {
            try {
                if (opportunities.get(in.opportunityId()).accountId() != current.accountId()) {
                    problems.add("The opportunity must be at the same account.");
                }
            } catch (NotFoundException e) {
                problems.add("Choose an existing opportunity.");
            }
            if (in.status() == null || in.status().isBlank()) {
                status = "OPPORTUNITY";
            }
        }
        if (!STATUSES.contains(status)) {
            problems.add("Choose a status.");
        }
        nextStepProblems(in.nextStep(), in.nextStepDue(), problems);
        if (in.ownerId() != null && jdbc.sql("SELECT COUNT(*) FROM app_user WHERE id = :id").param("id", in.ownerId())
                .query(Integer.class).single() == 0) {
            problems.add("Choose an existing user as owner.");
        }
        if (!problems.isEmpty()) {
            throw new RuleViolationException(problems);
        }
        jdbc.sql("""
                UPDATE conversation SET status = :status, next_step = :next, next_step_due = :due,
                    owner_user_id = COALESCE(:owner, owner_user_id), opportunity_id = COALESCE(:opp, opportunity_id),
                    version = version + 1
                WHERE id = :id
                """)
            .param("status", status).param("next", trimOrNull(in.nextStep())).param("due", in.nextStepDue())
            .param("owner", in.ownerId()).param("opp", in.opportunityId()).param("id", id).update();
        audit.record("conversation", id, AuditLog.Action.UPDATE, Map.of("status", status), user.id());
    }

    private ConversationQueries.Conversation lockAndCheck(long id, Integer version) {
        Integer stored = jdbc.sql("SELECT version FROM conversation WHERE id = :id FOR UPDATE").param("id", id)
            .query(Integer.class).optional().orElseThrow(() -> new NotFoundException("Conversation", id));
        if (version != null && !version.equals(stored)) {
            throw new ConflictException("Someone else updated this conversation. Reload and try again.");
        }
        return queries.get(id);
    }

    /** What a touch's outcome says about the conversation; other outcomes keep the status it had. */
    static String statusFor(String outcome, String otherwise) {
        if (outcome == null) {
            return otherwise;
        }
        return switch (outcome.trim().toUpperCase(Locale.ROOT)) {
            case "REPLIED" -> "REPLIED";
            case "MEETING_BOOKED" -> "MEETING_BOOKED";
            case "NOT_INTERESTED" -> "CLOSED";
            default -> otherwise;
        };
    }

    private static void nextStepProblems(String nextStep, LocalDate due, List<String> problems) {
        boolean hasStep = !blank(nextStep);
        if (hasStep && nextStep.trim().length() > 500) {
            problems.add("The next step can be at most 500 characters.");
        }
        if (hasStep && due == null) {
            problems.add("Give the next step a due date, so it shows in the due list.");
        }
        if (!hasStep && due != null) {
            problems.add("Say what the next step is, or clear its date.");
        }
        if (due != null && due.isBefore(LocalDate.now().minusYears(1))) {
            problems.add("The next step date looks wrong: it is more than a year ago.");
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String trimOrNull(String s) {
        return blank(s) ? null : s.trim();
    }
}
