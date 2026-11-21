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
}
