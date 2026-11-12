package com.codewalnut.bd.assist;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.codewalnut.bd.accounts.AccountKey;
import com.codewalnut.bd.accounts.AccountRegistry;
import com.codewalnut.bd.shared.AuditLog;
import com.codewalnut.bd.shared.CurrentUser;
import com.codewalnut.bd.shared.NotFoundException;
import com.codewalnut.bd.shared.RuleViolationException;

import tools.jackson.databind.json.JsonMapper;

/** Find GCCs: background web searches whose results people review and add as prospects (ADR-0011). */
@Service
class DiscoveryService {

    record CandidateView(GccFinder.Candidate candidate, String domain, boolean validDomain, boolean alreadyInApp) {}

    record Run(long id, String city, String industry, String status, List<CandidateView> candidates, String error,
               String requestedBy, Instant requestedAt, Instant finishedAt) {}

    record Added(int created, int skipped, List<String> problems) {}

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(DiscoveryService.class);

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final GccFinder finder;
    private final AccountRegistry accounts;
    private final TaskExecutor executor;
    private final AuditLog audit;

    DiscoveryService(JdbcClient jdbc, JsonMapper json, GccFinder finder, AccountRegistry accounts, @org.springframework.beans.factory.annotation.Qualifier("applicationTaskExecutor") TaskExecutor executor,
                     AuditLog audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.finder = finder;
        this.accounts = accounts;
        this.executor = executor;
        this.audit = audit;
    }

    boolean enabled() {
        return finder.enabled();
    }

    Run start(String city, String industry, CurrentUser user) {
        if (!finder.enabled()) {
            throw new RuleViolationException(List.of("Find GCCs is not switched on. Ask an Admin to set ANTHROPIC_API_KEY."));
        }
        String c = city == null ? "" : city.trim();
        String i = industry == null || industry.isBlank() ? null : industry.trim();
        if (c.isEmpty() || c.length() > 80) {
            throw new RuleViolationException(List.of("Choose a city."));
        }
        if (i != null && i.length() > 120) {
            throw new RuleViolationException(List.of("The industry can be at most 120 characters."));
        }
        jdbc.sql("""
                UPDATE gcc_discovery SET status = 'FAILED', error = 'Stopped before it finished. Run it again.',
                    finished_at = CURRENT_TIMESTAMP(3)
                WHERE status = 'RUNNING' AND requested_at < NOW() - INTERVAL 15 MINUTE
                """).update();
        List<String> known = accounts.parentNamesIn(c);
        var keys = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO gcc_discovery (city, industry, status, requested_by) VALUES (:city, :industry, 'RUNNING', :user)")
            .param("city", c).param("industry", i).param("user", user.id()).update(keys);
        long id = keys.getKey().longValue();
        executor.execute(() -> run(id, c, i, known));
        return get(id);
    }

    private void run(long id, String city, String industry, List<String> known) {
        try {
            GccFinder.Result result = finder.find(city, industry, known);
            jdbc.sql("UPDATE gcc_discovery SET status = 'DONE', candidates = :c, model = :m, finished_at = CURRENT_TIMESTAMP(3) WHERE id = :id")
                .param("c", json.writeValueAsString(result.candidates())).param("m", result.model()).param("id", id).update();
        } catch (ResearchException e) {
            fail(id, e.getMessage());
        } catch (RuntimeException e) {
            LOG.error("Find GCCs run {} failed", id, e);
            fail(id, "Something went wrong during the search. Try again.");
        }
    }

    private void fail(long id, String message) {
        jdbc.sql("UPDATE gcc_discovery SET status = 'FAILED', error = :e, finished_at = CURRENT_TIMESTAMP(3) WHERE id = :id")
            .param("e", message.length() > 500 ? message.substring(0, 500) : message).param("id", id).update();
    }

    List<Run> recent() {
        List<Long> ids = jdbc.sql("SELECT id FROM gcc_discovery ORDER BY requested_at DESC, id DESC LIMIT 30").query(Long.class).list();
        return ids.stream().map(this::get).toList();
    }

    Run get(long id) {
        Set<AccountKey> existing = accounts.allKeys().keySet();
        return jdbc.sql("""
                SELECT d.id, d.city, d.industry, d.status, d.candidates, d.error, u.full_name, d.requested_at, d.finished_at
                FROM gcc_discovery d JOIN app_user u ON u.id = d.requested_by WHERE d.id = :id
                """)
            .param("id", id)
            .query((rs, n) -> {
                List<CandidateView> views = new ArrayList<>();
                if (rs.getString(5) != null) {
                    for (GccFinder.Candidate c : json.readValue(rs.getString(5), GccFinder.Candidate[].class)) {
                        String domain = AccountKey.normaliseDomain(c.websiteDomain());
                        views.add(new CandidateView(c, domain, AccountKey.isValidDomain(domain),
                            existing.contains(new AccountKey(domain, c.city()))));
                    }
                }
                return new Run(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), views, rs.getString(6),
                    rs.getString(7), rs.getTimestamp(8).toInstant(),
                    rs.getTimestamp(9) == null ? null : rs.getTimestamp(9).toInstant());
            })
            .optional().orElseThrow(() -> new NotFoundException("Search", id));
    }

    /** Adds the chosen candidates as prospects owned by the user. Ones already in the app are skipped. */
    @Transactional
    Added add(long runId, List<String> domains, CurrentUser user) {
        Run run = get(runId);
        if (!"DONE".equals(run.status())) {
            throw new RuleViolationException(List.of("This search has not finished."));
        }
        Set<String> wanted = domains == null ? Set.of()
            : Set.copyOf(domains.stream().map(d -> AccountKey.normaliseDomain(d)).toList());
        int created = 0;
        int skipped = 0;
        List<String> problems = new ArrayList<>();
        for (CandidateView v : run.candidates()) {
            if (!wanted.contains(v.domain())) {
                continue;
            }
            if (v.alreadyInApp()) {
                skipped++;
                continue;
            }
            var c = v.candidate();
            if (!v.validDomain() || blank(c.company()) || blank(c.parentCompany())) {
                problems.add((blank(c.company()) ? v.domain() : c.company()) + ": missing a name or a valid website domain, so it was not added.");
                continue;
            }
            String source = ("Find GCCs: " + c.source());
            var result = accounts.upsert(new AccountRegistry.AccountData(cut(c.company(), 200), cut(c.parentCompany(), 200),
                blank(c.hqCountry()) ? null : cut(c.hqCountry(), 80), c.city(), v.domain(),
                blank(c.industry()) ? null : cut(c.industry(), 120), null, null, List.of(), "PROSPECT", user.id(),
                cut(source, 200), blank(c.description()) ? null : c.description()));
            audit.record("account", result.id(), AuditLog.Action.CREATE, Map.of("from", "Find GCCs", "run", runId), user.id());
            created++;
        }
        return new Added(created, skipped, problems);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String cut(String s, int max) {
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

}
