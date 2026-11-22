package com.codewalnut.bd.engagement;

import java.time.LocalDate;

import org.springframework.stereotype.Component;

import com.codewalnut.bd.shared.CurrentUser;

/** What other modules may do with conversations: start one when a LinkedIn note was sent (ADR-0023). */
@Component
public class Conversations {

    private final ConversationService service;
    private final com.codewalnut.bd.reporting.ConversationQueries queries;

    Conversations(ConversationService service, com.codewalnut.bd.reporting.ConversationQueries queries) {
        this.service = service;
        this.queries = queries;
    }

    /** Opens a conversation with a contact for a connection request the user sent today; follow up in 7 days. */
    public long startFromLinkedInNote(long accountId, long contactId, String note, LocalDate today, CurrentUser user) {
        return service.start(new ConversationService.Start(accountId, contactId, null, "LinkedIn connection request",
            "LINKEDIN_CONNECTION", today, "Connection request sent: " + note, "NO_REPLY", "Check for a reply",
            today.plusDays(7), null), user);
    }

    /**
     * Logs a touch in a conversation (ADR-0024): {@code outcome} REPLIED, NOT_INTERESTED (closes it) or NO_REPLY (our own
     * message; the status stays); {@code channel} one of the activity channels.
     */
    public void logTouch(long conversationId, String channel, LocalDate on, String summary, String outcome, String nextStep,
                         LocalDate nextStepDue, CurrentUser user) {
        service.update(conversationId, new ConversationService.Update(channel, on, summary, outcome, null, nextStep, nextStepDue,
            null), user);
    }

    /** Opens a conversation with a contact when the first sequence email went out (ADR-0025). */
    public long startFromSequenceEmail(long accountId, long contactId, String subject, String summary, LocalDate today,
                                       LocalDate nextStepDue, CurrentUser user) {
        String topic = subject.length() > 200 ? subject.substring(0, 200) : subject;
        return service.start(new ConversationService.Start(accountId, contactId, null, topic, "EMAIL", today, summary, "NO_REPLY",
            "Check for a reply", nextStepDue, null), user);
    }

    /** Links an opportunity created from a call prep (ADR-0026); the conversation moves to "opportunity". */
    public void linkOpportunity(long conversationId, long opportunityId, CurrentUser user) {
        var c = queries.get(conversationId);
        service.change(conversationId, new ConversationService.Change(null, c.nextStep(), c.nextStepDue(), null, opportunityId, null), user);
    }
}
