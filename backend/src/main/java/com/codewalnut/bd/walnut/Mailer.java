package com.codewalnut.bd.walnut;

/** Sends email through Resend: Walnut's morning brief (ADR-0015) and GTN alerts (ADR-0019). */
public interface Mailer {

    boolean enabled();

    void send(String to, String subject, String html);
}
