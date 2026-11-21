package com.codewalnut.bd.assist;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;

import com.codewalnut.bd.reporting.ProspectContextQueries;
import com.codewalnut.bd.shared.AuditLog;
import com.codewalnut.bd.shared.CurrentUser;
import com.codewalnut.bd.shared.RuleViolationException;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the research agent on people (ADR-0022) in the background, keeps every run, and works out the score from the
 * agent's ratings and our own access. A daily cap bounds the spend; a recent brief is reused.
 */
@Service
public class ProspectService {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ProspectService.class);
    static final int REUSE_DAYS = 7;
    static final int ACCOUNT_BATCH = 10;

    /** One run. {@code score} 0–100 from roleFit, timing (the agent's) and access (ours). */
    public record View(long id, String personType, long personId, String personName, Long accountId, String accountName,
                       String status, ProspectResearcher.Brief brief, List<ProspectResearcher.Source> sources, Integer roleFit,
                       Integer timing, Integer access, String accessReason, Integer score, String error, String model,
                       String requestedBy, Instant requestedAt, Instant finishedAt) {}

    /** Started for an account: who was queued, who was skipped and why. */
    public record Batch(List<String> started, List<String> skipped, int remainingToday) {}

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final ProspectResearcher researcher;
    private final ProspectContextQueries context;
    private final TaskExecutor executor;
    private final AuditLog audit;
    private final int perDay;

    ProspectService(JdbcClient jdbc, JsonMapper json, ProspectResearcher researcher, ProspectContextQueries context,
                    @Qualifier("applicationTaskExecutor") TaskExecutor executor, AuditLog audit,
                    @Value("${app.agent.research-per-day:60}") int perDay) {
        this.jdbc = jdbc;
        this.json = json;
        this.researcher = researcher;
        this.context = context;
        this.executor = executor;
        this.audit = audit;
        this.perDay = Math.max(1, perDay);
    }

    public boolean enabled() {
        return researcher.enabled();
    }

    public int remainingToday() {
        int used = jdbc.sql("SELECT COUNT(*) FROM prospect_brief WHERE requested_at >= CURRENT_DATE").query(Integer.class).single();
        return Math.max(0, perDay - used);
    }

    /** Starts research on one person, unless a run is going or {@code again} is false and a recent brief exists. */
    public View start(String type, long id, boolean again, CurrentUser user) {
        if (!researcher.enabled()) {
            throw new RuleViolationException(List.of("The research agent is not switched on. Ask an Admin to set ANTHROPIC_API_KEY."));
        }
        ProspectContextQueries.Person p = context.person(type, id);
        if (p.optedOut()) {
            throw new RuleViolationException(List.of(p.name() + " asked not to be contacted, so they are not researched."));
        }
        expireStale();
        Optional<View> newest = newest(p.type(), id);
        if (newest.isPresent() && "RUNNING".equals(newest.get().status())) {
            return newest.get();
        }
        if (!again && newest.isPresent() && "DONE".equals(newest.get().status())
                && newest.get().requestedAt().isAfter(Instant.now().minusSeconds(REUSE_DAYS * 86_400L))) {
            return newest.get();
        }
        if (remainingToday() == 0) {
            throw new RuleViolationException(List.of("Today's research limit (" + perDay + ") is used up. Try again tomorrow, or ask an "
                + "Admin to raise AGENT_RESEARCH_PER_DAY."));
        }
        ProspectContextQueries.Context ctx = context.build(p.type(), id);
        var keys = new GeneratedKeyHolder();
        jdbc.sql("""
                INSERT INTO prospect_brief (person_type, person_id, account_id, status, access, requested_by)
                VALUES (:t, :id, :a, 'RUNNING', :access, :u)
                """).param("t", p.type()).param("id", id).param("a", p.accountId()).param("access", ctx.access())
            .param("u", user.id()).update(keys);
        long runId = keys.getKey().longValue();
        audit.record("prospect_brief", runId, AuditLog.Action.CREATE, Map.of("person", p.type() + ":" + id), user.id());
        executor.execute(() -> run(runId, ctx));
        return newest(p.type(), id).orElseThrow();
    }

    /** Researches up to 10 contacts at an account who have no brief from the last week. */
    public Batch startForAccount(long accountId, CurrentUser user) {
        List<Long> ids = jdbc.sql("""
                SELECT c.id FROM contact c WHERE c.account_id = :a AND NOT c.opted_out
                  AND NOT EXISTS (SELECT 1 FROM prospect_brief b WHERE b.person_type = 'CONTACT' AND b.person_id = c.id
                                  AND b.status IN ('RUNNING', 'DONE') AND b.requested_at > NOW() - INTERVAL 7 DAY)
                ORDER BY c.full_name LIMIT :n
                """).param("a", accountId).param("n", ACCOUNT_BATCH).query(Long.class).list();
        List<String> started = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        if (ids.isEmpty()) {
            throw new RuleViolationException(List.of("Everyone at this account has a recent brief, or there are no contacts yet."));
        }
        for (long id : ids) {
            String name = context.person("CONTACT", id).name();
            if (remainingToday() == 0) {
                skipped.add(name + ": today's research limit is used up");
                continue;
            }
            start("CONTACT", id, false, user);
            started.add(name);
        }
        return new Batch(started, skipped, remainingToday());
    }

    private void run(long runId, ProspectContextQueries.Context ctx) {
        try {
            ProspectResearcher.Result result = researcher.research(ctx);
            ProspectResearcher.Brief b = result.brief();
            jdbc.sql("""
                    UPDATE prospect_brief SET status = 'DONE', brief = :brief, sources = :sources, role_fit = :fit, timing = :timing,
                        score = :score, model = :model, finished_at = CURRENT_TIMESTAMP(3)
                    WHERE id = :id
                    """)
                .param("brief", json.writeValueAsString(b)).param("sources", json.writeValueAsString(result.sources()))
                .param("fit", b.roleFit()).param("timing", b.timing()).param("score", score(b.roleFit(), b.timing(), ctx.access()))
                .param("model", result.model()).param("id", runId).update();
        } catch (ResearchException e) {
            fail(runId, e.getMessage());
        } catch (RuntimeException e) {
            LOG.error("Prospect research {} failed", runId, e);
            fail(runId, "Something went wrong during research. Try again.");
        }
    }

    /** ADR-0022: round(100 × (0.40 × roleFit + 0.35 × timing + 0.25 × access) / 5). */
    static int score(int roleFit, int timing, int access) {
        return (int) Math.round(100 * (0.40 * roleFit + 0.35 * timing + 0.25 * access) / 5);
    }

    private void fail(long runId, String message) {
        jdbc.sql("UPDATE prospect_brief SET status = 'FAILED', error = :e, finished_at = CURRENT_TIMESTAMP(3) WHERE id = :id")
            .param("e", message.length() > 500 ? message.substring(0, 500) : message).param("id", runId).update();
    }

    private void expireStale() {
        jdbc.sql("""
                UPDATE prospect_brief SET status = 'FAILED', error = 'Stopped before it finished. Run it again.',
                    finished_at = CURRENT_TIMESTAMP(3)
                WHERE status = 'RUNNING' AND requested_at < NOW() - INTERVAL 15 MINUTE
                """).update();
    }

    /** The newest run for an existing person (404 for an unknown person or type), or empty when not researched yet. */
    public Optional<View> briefOf(String type, long id) {
        ProspectContextQueries.Person p = context.person(type, id);
        return newest(p.type(), id);
    }

    /** The newest run for a person, or empty. */
    public Optional<View> newest(String type, long id) {
        return list("b.person_type = :t AND b.person_id = :id", Map.of("t", type, "id", id), "b.id DESC", 1).stream().findFirst();
    }

    /** The newest run per contact at an account, best score first. */
    public List<View> forAccount(long accountId) {
        return list("""
                b.account_id = :a AND b.id = (SELECT MAX(x.id) FROM prospect_brief x WHERE x.person_type = b.person_type
                  AND x.person_id = b.person_id)
                """, Map.of("a", accountId), "b.score IS NULL, b.score DESC, b.id DESC", 200);
    }

    private List<View> list(String where, Map<String, Object> params, String order, int limit) {
        return jdbc.sql("""
                SELECT b.id, b.person_type, b.person_id,
                    CASE b.person_type WHEN 'CONTACT' THEN c.full_name ELSE n.full_name END,
                    b.account_id, a.name, b.status, b.brief, b.sources, b.role_fit, b.timing, b.access, b.score, b.error, b.model,
                    u.full_name, b.requested_at, b.finished_at
                FROM prospect_brief b
                LEFT JOIN contact c ON b.person_type = 'CONTACT' AND c.id = b.person_id
                LEFT JOIN connection n ON b.person_type = 'CONNECTION' AND n.id = b.person_id
                LEFT JOIN account a ON a.id = b.account_id
                JOIN app_user u ON u.id = b.requested_by
                WHERE\s""" + where + " ORDER BY " + order + " LIMIT " + limit)
            .params(params)
            .query((rs, n) -> new View(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getObject(5, Long.class),
                rs.getString(6), rs.getString(7),
                rs.getString(8) == null ? null : json.readValue(rs.getString(8), ProspectResearcher.Brief.class),
                rs.getString(9) == null ? List.of() : json.readValue(rs.getString(9), new TypeReference<List<ProspectResearcher.Source>>() {}),
                rs.getObject(10, Integer.class), rs.getObject(11, Integer.class), rs.getObject(12, Integer.class),
                null, rs.getObject(13, Integer.class), rs.getString(14), rs.getString(15), rs.getString(16),
                rs.getTimestamp(17).toInstant(), rs.getTimestamp(18) == null ? null : rs.getTimestamp(18).toInstant()))
            .list().stream().map(v -> v.access() == null ? v : withReason(v)).toList();
    }

    private View withReason(View v) {
        String reason;
        try {
            reason = context.build(v.personType(), v.personId()).accessReason();
        } catch (RuntimeException e) {
            reason = null;
        }
        return new View(v.id(), v.personType(), v.personId(), v.personName(), v.accountId(), v.accountName(), v.status(), v.brief(),
            v.sources(), v.roleFit(), v.timing(), v.access(), reason, v.score(), v.error(), v.model(), v.requestedBy(), v.requestedAt(),
            v.finishedAt());
    }
}
