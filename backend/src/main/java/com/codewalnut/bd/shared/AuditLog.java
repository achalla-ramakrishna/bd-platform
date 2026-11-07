package com.codewalnut.bd.shared;

import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * Writes who changed what and when (FR-ADM-03, NFR-BD-03). Call it inside the
 * same transaction as the change, so the audit row and the change commit together.
 */
@Component
public class AuditLog {

    public enum Action { CREATE, UPDATE, DELETE, IMPORT }

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public AuditLog(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void record(String entityType, long entityId, Action action, Map<String, ?> changes, Long userId) {
        jdbc.sql("""
                INSERT INTO audit_event (entity_type, entity_id, action, changes, user_id)
                VALUES (:type, :id, :action, :changes, :user)
                """)
            .param("type", entityType)
            .param("id", entityId)
            .param("action", action.name())
            .param("changes", changes == null ? null : json.writeValueAsString(changes))
            .param("user", userId)
            .update();
    }
}
