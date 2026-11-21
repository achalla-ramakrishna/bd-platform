package com.codewalnut.bd.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.codewalnut.bd.shared.AuditLog;
import com.codewalnut.bd.shared.ConflictException;
import com.codewalnut.bd.shared.CurrentUser;
import com.codewalnut.bd.shared.NotFoundException;
import com.codewalnut.bd.shared.RuleViolationException;

/** GTN message templates per stage and Why? reason, with merge fields (ADR-0018). */
@Service
class TemplateService {

    static final Set<String> CHANNELS = Set.of("ANY", "LINKEDIN", "EMAIL", "WHATSAPP");

    record Input(String name, String stage, String reason, String channel, String subject, String body, Boolean active,
                 Integer version) {}

    private final JdbcClient jdbc;
    private final AuditLog audit;

    TemplateService(JdbcClient jdbc, AuditLog audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional
    long create(Input in, CurrentUser user) {
        check(in);
        var key = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO message_template (name, stage, reason, channel, subject, body, active)
                VALUES (:name, :stage, :reason, :channel, :subject, :body, :active)
                """).params(params(in)).update(key);
        long id = key.getKey().longValue();
        audit.record("message_template", id, AuditLog.Action.CREATE, Map.of(), user.id());
        return id;
    }

    @Transactional
    void update(long id, Input in, CurrentUser user) {
        Integer stored = jdbc.sql("SELECT version FROM message_template WHERE id = :id FOR UPDATE").param("id", id)
            .query(Integer.class).optional().orElseThrow(() -> new NotFoundException("Message template", id));
        if (in.version() != null && !in.version().equals(stored)) {
            throw new ConflictException("Someone else changed this template. Reload and try again.");
        }
        check(in);
        jdbc.sql("""
                UPDATE message_template SET name = :name, stage = :stage, reason = :reason, channel = :channel,
                    subject = :subject, body = :body, active = :active, version = version + 1
                WHERE id = :id
                """).params(params(in)).param("id", id).update();
        audit.record("message_template", id, AuditLog.Action.UPDATE, Map.of(), user.id());
    }

    @Transactional
    void delete(long id, CurrentUser user) {
        if (jdbc.sql("DELETE FROM message_template WHERE id = :id").param("id", id).update() == 0) {
            throw new NotFoundException("Message template", id);
        }
        audit.record("message_template", id, AuditLog.Action.DELETE, Map.of(), user.id());
    }

    private static Map<String, Object> params(Input in) {
        Map<String, Object> p = new java.util.HashMap<>();
        p.put("name", in.name().trim());
        p.put("stage", upper(in.stage()));
        p.put("reason", "UNRESPONSIVE".equals(upper(in.stage())) ? upper(in.reason()) : null);
        p.put("channel", upper(in.channel()) == null ? "ANY" : upper(in.channel()));
        p.put("subject", in.subject() == null || in.subject().isBlank() ? null : in.subject().trim());
        p.put("body", in.body().trim());
        p.put("active", in.active() == null || in.active());
        return p;
    }

    private static void check(Input in) {
        List<String> problems = new ArrayList<>();
        if (in.name() == null || in.name().isBlank() || in.name().trim().length() > 120) {
            problems.add("Give the template a name of up to 120 characters.");
        }
        String stage = upper(in.stage());
        if (stage == null || !NetworkService.STAGES.contains(stage)) {
            problems.add("Choose the stage the template is for.");
        }
        String reason = upper(in.reason());
        if ("UNRESPONSIVE".equals(stage) && (reason == null || !NetworkService.REASONS.contains(reason))) {
            problems.add("Choose the Why? reason the nudge is for.");
        }
        String channel = upper(in.channel());
        if (channel != null && !CHANNELS.contains(channel)) {
            problems.add("Choose a channel: any, LinkedIn, email or WhatsApp.");
        }
        if (in.subject() != null && in.subject().trim().length() > 200) {
            problems.add("The subject can be at most 200 characters.");
        }
        if (in.body() == null || in.body().isBlank() || in.body().trim().length() > 2000) {
            problems.add("Write the message, up to 2,000 characters.");
        }
        if (!problems.isEmpty()) {
            throw new RuleViolationException(problems);
        }
    }

    private static String upper(String s) {
        return s == null || s.isBlank() ? null : s.trim().toUpperCase(Locale.ROOT);
    }
}
