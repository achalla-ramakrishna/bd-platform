package com.codewalnut.bd.engagement;

import java.time.LocalDate;

import org.springframework.stereotype.Component;

import com.codewalnut.bd.shared.CurrentUser;

/** What other modules may do with conversations: start one when a LinkedIn note was sent (ADR-0023). */
@Component
public class Conversations {

    private final ConversationService service;

    Conversations(ConversationService service) {
        this.service = service;
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
}
