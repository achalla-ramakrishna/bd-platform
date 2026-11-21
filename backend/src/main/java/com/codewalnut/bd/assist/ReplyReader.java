package com.codewalnut.bd.assist;

import java.time.LocalDate;
import java.util.List;

/**
 * The BD agent's reply step (ADR-0024): reads one reply, says what kind it is, and drafts the answer. It has no
 * tools: it cannot send, search or change anything. A person confirms.
 */
public interface ReplyReader {

    List<String> KINDS = List.of("INTERESTED", "QUESTION", "NOT_NOW", "REFERRAL", "OBJECTION", "NOT_INTERESTED", "UNSUBSCRIBE",
        "OUT_OF_OFFICE", "OTHER");

    /** {@code history}: our touches with them, newest first; {@code brief}: the research headline and angle, or null. */
    record Request(String personName, String personTitle, String company, String ownerName, String channel,
                   List<String> history, String brief, String reply, LocalDate today) {}

    /** Dates are ISO or null. {@code confidence}: HIGH, MEDIUM or LOW. */
    record Reading(String kind, String confidence, String summary, LocalDate revisitOn, String referralName,
                   String referralRole, String nextStep, String answer, String model) {}

    boolean enabled();

    Reading read(Request request);
}
