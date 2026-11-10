package com.codewalnut.bd.assist;

import java.time.LocalDate;
import java.util.List;

/**
 * Reads what a person pasted about a BD touch (a note, an email, a screenshot of a chat) and says who it
 * was with, what happened and the next step (ADR-0013). Nothing is saved: the person checks the
 * suggestions and applies them. Nothing is read from or sent to LinkedIn (ADR-0005).
 */
public interface NoteReader {

    record Image(byte[] bytes, String mediaType) {}

    record Request(String text, List<Image> images, LocalDate today, List<String> channels, List<String> outcomes) {}

    /** Empty strings mean "not shown". Dates are ISO (YYYY-MM-DD) or empty. */
    record Reading(String reply, String fullName, String title, String company, String city, String linkedinUrl,
                   String topic, String channel, String occurredOn, String summary, String outcome, String nextStep,
                   String nextStepDue, boolean newConnection) {}

    boolean enabled();

    Reading read(Request request);
}
