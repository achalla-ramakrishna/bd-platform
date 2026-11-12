package com.codewalnut.bd.walnut;

/** Sends Walnut's morning brief by email (ADR-0015). */
interface Mailer {

    boolean enabled();

    void send(String to, String subject, String html);
}
