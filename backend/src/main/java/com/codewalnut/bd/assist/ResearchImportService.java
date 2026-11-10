package com.codewalnut.bd.assist;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.codewalnut.bd.accounts.AccountKey;
import com.codewalnut.bd.accounts.AccountRegistry;
import com.codewalnut.bd.shared.AuditLog;
import com.codewalnut.bd.shared.CurrentUser;
import com.codewalnut.bd.shared.RuleViolationException;

import tools.jackson.databind.json.JsonMapper;

/**
 * Saves research written outside the app (for example by a person working with Claude Code) as a
 * finished research run, so it shows on the account page like research the app ran itself
 * (ADR-0010). Accounts are matched by website domain and city (BR-BD-01). The whole file is
 * checked first; nothing is saved unless every entry is valid.
 */
@Service
class ResearchImportService {

    record Entry(String websiteDomain, String city, AccountResearcher.Summary summary,
                 List<AccountResearcher.Source> sources) {}

    record Upload(String researchedBy, List<Entry> accounts) {}

    record Saved(long accountId, String websiteDomain, String city) {}

    static final int MAX_ACCOUNTS = 50;

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final AccountRegistry accounts;
    private final AuditLog audit;

    ResearchImportService(JdbcClient jdbc, JsonMapper json, AccountRegistry accounts, AuditLog audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.accounts = accounts;
        this.audit = audit;
    }

    @Transactional
    List<Saved> save(Upload upload, CurrentUser user) {
        List<String> problems = new ArrayList<>();
        List<Entry> entries = upload == null || upload.accounts() == null ? List.of() : upload.accounts();
        if (entries.isEmpty()) {
            problems.add("The file has no accounts.");
        }
        if (entries.size() > MAX_ACCOUNTS) {
            problems.add("Upload at most " + MAX_ACCOUNTS + " accounts at a time.");
        }
        Map<AccountKey, Long> keys = accounts.allKeys();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            String where = "Entry " + (i + 1) + ": ";
            AccountKey key = new AccountKey(e.websiteDomain(), e.city());
            Long id = keys.get(key);
            if (id == null) {
                problems.add(where + "no account with website " + key.domain() + " in " + (e.city() == null ? "?" : e.city().trim()) + ".");
            } else if (ids.contains(id)) {
                problems.add(where + key + " appears twice.");
            }
            if (e.summary() == null || blank(e.summary().centreOverview())) {
                problems.add(where + "the summary needs a centre overview.");
            }
            ids.add(id);
        }
        if (!problems.isEmpty()) {
            throw new RuleViolationException(problems);
        }
        String model = blank(upload.researchedBy()) ? "Uploaded research" : trim(upload.researchedBy(), 60);
        List<Saved> saved = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            long accountId = ids.get(i);
            var key = new GeneratedKeyHolder();
            jdbc.sql("""
                    INSERT INTO account_research (account_id, status, summary, sources, model, requested_by, finished_at)
                    VALUES (:account, 'DONE', :summary, :sources, :model, :user, CURRENT_TIMESTAMP(3))
                    """)
                .param("account", accountId)
                .param("summary", json.writeValueAsString(withEmptyLists(e.summary())))
                .param("sources", json.writeValueAsString(e.sources() == null ? List.of() : e.sources()))
                .param("model", model).param("user", user.id()).update(key);
            audit.record("account_research", key.getKey().longValue(), AuditLog.Action.CREATE,
                Map.of("accountId", accountId, "uploaded", true), user.id());
            saved.add(new Saved(accountId, new AccountKey(e.websiteDomain(), e.city()).domain(), e.city().trim()));
        }
        return saved;
    }

    /** Missing lists become empty ones, so the account page never meets a null. */
    private static AccountResearcher.Summary withEmptyLists(AccountResearcher.Summary s) {
        return new AccountResearcher.Summary(s.centreOverview(), orEmpty(s.facts()), orEmpty(s.buyingSignals()),
            orEmpty(s.likelyNeeds()), orEmpty(s.recommendedOffers()), orEmpty(s.whoToContact()),
            s.openingMessage() == null ? "" : s.openingMessage(), orEmpty(s.talkingPoints()), orEmpty(s.cautions()),
            s.fitRating());
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String trim(String s, int max) {
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
