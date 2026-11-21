package com.codewalnut.bd.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.codewalnut.bd.google.GoogleAccounts;
import com.codewalnut.bd.google.GoogleApi;
import com.codewalnut.bd.reporting.NetworkQueries;
import com.codewalnut.bd.shared.CurrentUser;
import com.codewalnut.bd.shared.RuleViolationException;

/**
 * Sends a GTN message from the person's own Gmail (ADR-0020), then logs it and moves the track as "I sent it" does.
 * Follow-ups to the same person continue the Gmail thread. If Gmail refuses, nothing is logged.
 */
@Service
public class GmailSender {

    /** {@code action}: SENT, or INTRO_DONE for the intro. */
    public record Email(String action, String subject, String body, Long templateId, Integer version) {}

    public record FirstEmail(Long connectionId, String subject, String body, Long templateId) {}

    private record Prior(String threadId, String messageId, String rfcId) {}

    private final JdbcClient jdbc;
    private final NetworkService network;
    private final NetworkQueries queries;
    private final GoogleAccounts accounts;
    private final GoogleApi google;

    GmailSender(JdbcClient jdbc, NetworkService network, NetworkQueries queries, GoogleAccounts accounts, GoogleApi google) {
        this.jdbc = jdbc;
        this.network = network;
        this.queries = queries;
        this.accounts = accounts;
        this.google = google;
    }

    @Transactional
    public long start(FirstEmail in, CurrentUser user) {
        String token = token(user);
        String to = in.connectionId() == null ? null : jdbc.sql("SELECT email FROM connection WHERE id = :c")
            .param("c", in.connectionId()).query(String.class).optional().orElse(null);
        check(in.subject(), in.body(), to);
        long id = network.start(new NetworkService.Start(in.connectionId(), "EMAIL", in.body(), in.templateId(), null), user);
        deliver(id, to, in.subject(), in.body(), user, token);
        return id;
    }

    @Transactional
    public void send(long trackId, Email in, CurrentUser user) {
        String token = token(user);
        NetworkQueries.Track t = queries.get(trackId, network.today());
        check(in.subject(), in.body(), t.email());
        String action = "INTRO_DONE".equalsIgnoreCase(in.action()) ? "INTRO_DONE" : "SENT";
        network.act(trackId, new NetworkService.Action(action, "EMAIL", in.body(), in.templateId(), null, null, null, null, null,
            null, null, null, null, in.version()), user);
        deliver(trackId, t.email(), in.subject(), in.body(), user, token);
    }

    private void deliver(long trackId, String to, String subject, String body, CurrentUser user, String token) {
        String name = jdbc.sql("SELECT n.full_name FROM connection n JOIN network_track t ON t.connection_id = n.id WHERE t.id = :t")
            .param("t", trackId).query(String.class).single();
        // Continue the thread this sender started with them, if any (Gmail threads replies with the same subject).
        Prior prior = jdbc.sql("""
                SELECT gmail_thread_id, gmail_message_id, rfc_message_id FROM network_touch
                WHERE track_id = :t AND user_id = :u AND direction = 'OUT' AND gmail_thread_id IS NOT NULL
                ORDER BY id DESC LIMIT 1
                """).param("t", trackId).param("u", user.id())
            .query((rs, n) -> new Prior(rs.getString(1), rs.getString(2), rs.getString(3))).optional().orElse(null);
        String finalSubject = subject.trim();
        if (prior != null) {
            String original = null;
            try {
                original = google.message(token, prior.messageId()).subject();
            } catch (GoogleApi.GoogleException e) {
                original = null;
            }
            if (original != null && !original.isBlank()) {
                finalSubject = original.toLowerCase(Locale.ROOT).startsWith("re:") ? original : "Re: " + original;
            }
        }
        String from = accounts.emailOf(user.id());
        String raw = mime(user.name(), from, name, to, finalSubject, body, prior == null ? null : prior.rfcId());
        GoogleApi.Sent sent;
        try {
            sent = google.send(token, raw, prior == null ? null : prior.threadId());
        } catch (GoogleApi.GoogleException e) {
            throw new RuleViolationException(List.of("Gmail didn't send it: " + e.getMessage()));
        }
        String rfc = null;
        try {
            rfc = google.message(token, sent.id()).rfcMessageId();
        } catch (GoogleApi.GoogleException e) {
            rfc = null; // threading the next message by Gmail thread id still works
        }
        network.sentThroughGmail(trackId, sent.threadId(), sent.id(), rfc);
    }

    private String token(CurrentUser user) {
        GoogleAccounts.Status status = accounts.status(user.id());
        if (!status.connected() || !status.canSend()) {
            throw new RuleViolationException(List.of("Connect your Gmail first (GTN → My profile page → Connect Google)."));
        }
        return accounts.accessToken(user.id())
            .orElseThrow(() -> new RuleViolationException(List.of("Your Google connection has expired. Connect again.")));
    }

    private static void check(String subject, String body, String to) {
        List<String> problems = new ArrayList<>();
        if (to == null || to.isBlank()) {
            problems.add("They have no email address: add it on their connection page.");
        }
        if (subject == null || subject.isBlank() || subject.trim().length() > 200) {
            problems.add("Write a subject of up to 200 characters.");
        }
        if (body == null || body.isBlank() || body.length() > 2000) {
            problems.add("Write the message, up to 2,000 characters.");
        }
        if (!problems.isEmpty()) {
            throw new RuleViolationException(problems);
        }
    }

    /** A plain-text RFC 2822 message, base64url as the Gmail API wants it. */
    static String mime(String fromName, String from, String toName, String to, String subject, String body, String inReplyTo) {
        return com.codewalnut.bd.google.Mime.plainText(fromName, from, toName, to, subject, body, inReplyTo, java.util.Map.of());
    }
}
