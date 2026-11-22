package com.codewalnut.bd.engagement;

import java.time.LocalDate;

/**
 * A conversation's status became "meeting booked" (ADR-0026). {@code on}: the next step's date, the best guess of the
 * meeting day, or null. Published inside the change; listeners act after it commits.
 */
public record MeetingBooked(long conversationId, long accountId, Long contactId, long ownerId, LocalDate on) {}
