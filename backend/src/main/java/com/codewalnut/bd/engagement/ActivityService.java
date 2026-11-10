package com.codewalnut.bd.engagement;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.codewalnut.bd.accounts.AccountRegistry;
import com.codewalnut.bd.contacts.ContactRegistry;
import com.codewalnut.bd.shared.AuditLog;
import com.codewalnut.bd.shared.CurrentUser;
import com.codewalnut.bd.shared.RuleViolationException;

/**
 * Logs touches on any channel (FR-ENG-01). People act on LinkedIn themselves and log what they did
 * here; the application never automates LinkedIn (BR-BD-03, ADR-0005). Activities are append-only.
 */
@Service
public class ActivityService {

    static final Set<String> CHANNELS = Set.of("LINKEDIN_CONNECTION", "LINKEDIN_MESSAGE", "INMAIL", "EMAIL", "CALL",
        "MEETING", "EVENT", "REFERRAL");
    static final Set<String> OUTCOMES = Set.of("NO_REPLY", "REPLIED", "MEETING_BOOKED", "NOT_INTERESTED", "REFERRED_ON",
        "DONE");

    public record Input(Long accountId, Long contactId, String channel, LocalDate occurredOn, String summary,
                        String outcome) {}

    private final JdbcClient jdbc;
    private final AccountRegistry accounts;
    private final ContactRegistry contacts;
    private final AuditLog audit;

    ActivityService(JdbcClient jdbc, AccountRegistry accounts, ContactRegistry contacts, AuditLog audit) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.contacts = contacts;
        this.audit = audit;
    }

    @Transactional
    public long log(Input in, CurrentUser user) {
        return log(in, null, user);
    }

    /** Logs a touch, optionally as an update in a conversation (backlog item 3). */
    @Transactional
    long log(Input in, Long conversationId, CurrentUser user) {
        List<String> problems = new ArrayList<>();
        if (in.accountId() == null || !accounts.exists(in.accountId())) {
            problems.add("Choose an existing account.");
        }
        if (in.contactId() != null) {
            var contactAccount = contacts.accountOf(in.contactId());
            if (contactAccount.isEmpty() || !contactAccount.get().equals(in.accountId())) {
                problems.add("The contact must be someone at this account.");
            }
        }
        String channel = upper(in.channel());
        if (!isIn(CHANNELS, channel)) {
            problems.add("Choose a channel.");
        }
        String outcome = upper(in.outcome());
        if (!isIn(OUTCOMES, outcome)) {
            problems.add("Choose an outcome.");
        }
        if (in.occurredOn() == null) {
            problems.add("Give the date of the touch.");
        } else if (in.occurredOn().isAfter(LocalDate.now().plusDays(1))) {
            problems.add("The date cannot be in the future. Log a touch after it happens.");
        }
        if (in.summary() == null || in.summary().isBlank()) {
            problems.add("Write a short summary.");
        } else if (in.summary().length() > 2000) {
            problems.add("The summary can be at most 2,000 characters.");
        }
        if (!problems.isEmpty()) {
            throw new RuleViolationException(problems);
        }
        var keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO activity (account_id, contact_id, conversation_id, user_id, channel, occurred_on, summary, outcome)
                VALUES (:account, :contact, :conversation, :user, :channel, :on, :summary, :outcome)
                """)
            .param("account", in.accountId()).param("contact", in.contactId()).param("conversation", conversationId)
            .param("user", user.id())
            .param("channel", channel).param("on", in.occurredOn()).param("summary", in.summary().trim())
            .param("outcome", outcome)
            .update(keys);
        long id = keys.getKey().longValue();
        audit.record("activity", id, AuditLog.Action.CREATE, Map.of("channel", channel), user.id());
        return id;
    }

    private static String upper(String s) {
        return s == null ? null : s.trim().toUpperCase(Locale.ROOT);
    }

    /** Null-safe membership check; immutable sets reject {@code contains(null)}. */
    private static boolean isIn(Set<String> allowed, String value) {
        return value != null && allowed.contains(value);
    }
}
