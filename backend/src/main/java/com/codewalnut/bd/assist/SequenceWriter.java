package com.codewalnut.bd.assist;

import java.util.List;

/**
 * The BD agent's copy step for email sequences (ADR-0025): writes each step's email for one person from the team's
 * templates. No tools; the owner reads and approves every email before anything is sent.
 */
public interface SequenceWriter {

    /** A template step: the subject pattern and what the email should do. */
    record Step(int position, int waitDays, String subject, String instructions) {}

    /** {@code history}: our touches with them, newest first; {@code brief}: their research brief as text, or null. */
    record Request(String personName, String personTitle, String company, String senderName, List<String> history, String brief,
                   List<Step> steps) {}

    record Email(int position, String subject, String body) {}

    record Result(List<Email> emails, String model) {}

    boolean enabled();

    Result write(Request request);
}
