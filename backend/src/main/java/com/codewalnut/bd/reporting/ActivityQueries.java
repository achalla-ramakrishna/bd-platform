package com.codewalnut.bd.reporting;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.codewalnut.bd.shared.NotFoundException;

/** Read view of the activity log, newest first. */
@Component
public class ActivityQueries {

    public record Activity(long id, long accountId, String accountName, Long contactId, String contactName,
                           String userName, String channel, LocalDate occurredOn, String summary, String outcome,
                           Instant createdAt, Long conversationId) {}

    private static final String SELECT = """
        SELECT x.id, x.account_id, a.name, x.contact_id, c.full_name, u.full_name, x.channel, x.occurred_on,
            x.summary, x.outcome, x.created_at, x.conversation_id
        FROM activity x
        JOIN account a ON a.id = x.account_id
        JOIN app_user u ON u.id = x.user_id
        LEFT JOIN contact c ON c.id = x.contact_id
        """;

    private final JdbcClient jdbc;

    ActivityQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Activity get(long id) {
        return jdbc.sql(SELECT + " WHERE x.id = :id").param("id", id).query(this::map).optional()
            .orElseThrow(() -> new NotFoundException("Activity", id));
    }

    public List<Activity> list(Long accountId, Long contactId) {
        return jdbc.sql(SELECT + """
                 WHERE (:account IS NULL OR x.account_id = :account) AND (:contact IS NULL OR x.contact_id = :contact)
                ORDER BY x.occurred_on DESC, x.id DESC LIMIT 500
                """)
            .param("account", accountId).param("contact", contactId)
            .query(this::map).list();
    }

    /** The updates in one conversation, newest first. */
    public List<Activity> forConversation(long conversationId) {
        return jdbc.sql(SELECT + " WHERE x.conversation_id = :c ORDER BY x.occurred_on DESC, x.id DESC")
            .param("c", conversationId).query(this::map).list();
    }

    private Activity map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Activity(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getObject(4, Long.class), rs.getString(5),
            rs.getString(6), rs.getString(7), rs.getObject(8, LocalDate.class), rs.getString(9), rs.getString(10),
            rs.getTimestamp(11).toInstant(), rs.getObject(12, Long.class));
    }
}
