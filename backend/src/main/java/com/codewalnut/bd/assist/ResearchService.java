package com.codewalnut.bd.assist;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;

import com.codewalnut.bd.reporting.ResearchContextQueries;
import com.codewalnut.bd.shared.AuditLog;
import com.codewalnut.bd.shared.CurrentUser;
import com.codewalnut.bd.shared.RuleViolationException;

import tools.jackson.databind.json.JsonMapper;

/**
 * Runs account research in the background (it takes a minute or two) and keeps every run; the newest
 * is shown on the account page (ADR-0010).
 */
@Service
class ResearchService {

    record View(long id, long accountId, String status, AccountResearcher.Summary summary,
                List<AccountResearcher.Source> sources, String error, String model, String requestedBy,
                Instant requestedAt, Instant finishedAt) {}

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ResearchService.class);

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final AccountResearcher researcher;
    private final ResearchContextQueries context;
    private final TaskExecutor executor;
    private final AuditLog audit;

    ResearchService(JdbcClient jdbc, JsonMapper json, AccountResearcher researcher, ResearchContextQueries context,
                    TaskExecutor executor, AuditLog audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.researcher = researcher;
        this.context = context;
        this.executor = executor;
        this.audit = audit;
    }

    boolean enabled() {
        return researcher.enabled();
    }

    /** Starts a run unless one is already running for this account. Returns the run. */
    View start(long accountId, CurrentUser user) {
        if (!researcher.enabled()) {
            throw new RuleViolationException(List.of("Account research is not switched on. Ask an Admin to set ANTHROPIC_API_KEY."));
        }
        var ctx = context.build(accountId);
        // A run left RUNNING for over 15 minutes (for example after a restart) counts as failed.
        jdbc.sql("""
                UPDATE account_research SET status = 'FAILED', error = 'Stopped before it finished. Run it again.',
                    finished_at = CURRENT_TIMESTAMP(3)
                WHERE account_id = :id AND status = 'RUNNING' AND requested_at < NOW() - INTERVAL 15 MINUTE
                """).param("id", accountId).update();
        boolean running = jdbc.sql("SELECT COUNT(*) FROM account_research WHERE account_id = :id AND status = 'RUNNING'")
            .param("id", accountId).query(Integer.class).single() > 0;
        if (running) {
            return latest(accountId).orElseThrow();
        }
        var keys = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO account_research (account_id, status, requested_by) VALUES (:id, 'RUNNING', :user)")
            .param("id", accountId).param("user", user.id()).update(keys);
        long runId = keys.getKey().longValue();
        audit.record("account_research", runId, AuditLog.Action.CREATE, Map.of("accountId", accountId), user.id());
        executor.execute(() -> run(runId, ctx));
        return latest(accountId).orElseThrow();
    }

    private void run(long runId, ResearchContextQueries.Context ctx) {
        try {
            AccountResearcher.Result result = researcher.research(ctx);
            jdbc.sql("""
                    UPDATE account_research SET status = 'DONE', summary = :summary, sources = :sources, model = :model,
                        finished_at = CURRENT_TIMESTAMP(3)
                    WHERE id = :id
                    """)
                .param("summary", json.writeValueAsString(result.summary()))
                .param("sources", json.writeValueAsString(result.sources()))
                .param("model", result.model()).param("id", runId).update();
        } catch (ResearchException e) {
            fail(runId, e.getMessage());
        } catch (RuntimeException e) {
            LOG.error("Account research {} failed", runId, e);
            fail(runId, "Something went wrong during research. Try again.");
        }
    }

    private void fail(long runId, String message) {
        jdbc.sql("UPDATE account_research SET status = 'FAILED', error = :error, finished_at = CURRENT_TIMESTAMP(3) WHERE id = :id")
            .param("error", message.length() > 500 ? message.substring(0, 500) : message).param("id", runId).update();
    }

    Optional<View> latest(long accountId) {
        return jdbc.sql("""
                SELECT r.id, r.account_id, r.status, r.summary, r.sources, r.error, r.model, u.full_name, r.requested_at,
                    r.finished_at
                FROM account_research r JOIN app_user u ON u.id = r.requested_by
                WHERE r.account_id = :id ORDER BY r.requested_at DESC, r.id DESC LIMIT 1
                """)
            .param("id", accountId)
            .query((rs, n) -> new View(rs.getLong(1), rs.getLong(2), rs.getString(3),
                rs.getString(4) == null ? null : json.readValue(rs.getString(4), AccountResearcher.Summary.class),
                rs.getString(5) == null ? List.of() : List.of(json.readValue(rs.getString(5), AccountResearcher.Source[].class)),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getTimestamp(9).toInstant(),
                rs.getTimestamp(10) == null ? null : rs.getTimestamp(10).toInstant()))
            .optional();
    }
}
