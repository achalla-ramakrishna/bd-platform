package com.codewalnut.bd.network;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import com.codewalnut.bd.google.GoogleAccounts;
import com.codewalnut.bd.google.GoogleApi;

/**
 * Finds replies in the Gmail threads GTN started (ADR-0020): only headers and Gmail's preview are read. Runs every
 * few minutes for everyone connected, and on demand for one person.
 */
@Service
public class ReplyChecker {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ReplyChecker.class);
    static final int DAYS = 60;

    private final JdbcClient jdbc;
    private final GoogleAccounts accounts;
    private final GoogleApi google;
    private final NetworkService network;
    private final GtnProperties gtn;

    ReplyChecker(JdbcClient jdbc, GoogleAccounts accounts, GoogleApi google, NetworkService network, GtnProperties gtn) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.google = google;
        this.network = network;
        this.gtn = gtn;
    }

    /** Checks everyone connected; returns the replies found. */
    public int pollAll() {
        int found = 0;
        for (long userId : accounts.readers()) {
            try {
                found += poll(userId);
            } catch (RuntimeException e) {
                LOG.warn("GTN reply check for user {} failed: {}", userId, e.getMessage());
            }
        }
        return found;
    }

    /** Checks one person's threads. */
    public int poll(long userId) {
        String token = accounts.accessToken(userId).orElse(null);
        String me = accounts.emailOf(userId);
        if (token == null || me == null) {
            return 0;
        }
        record Thread(String id, long trackId) {}
        List<Thread> threads = jdbc.sql("""
                SELECT DISTINCT x.gmail_thread_id, x.track_id FROM network_touch x
                WHERE x.user_id = :u AND x.direction = 'OUT' AND x.gmail_thread_id IS NOT NULL AND x.occurred_on >= :since
                """).param("u", userId).param("since", network.today().minusDays(DAYS))
            .query((rs, n) -> new Thread(rs.getString(1), rs.getLong(2))).list();
        int found = 0;
        for (Thread t : threads) {
            for (GoogleApi.Message m : google.thread(token, t.id())) {
                if (m.autoReply() || m.from() == null || m.from().toLowerCase(Locale.ROOT).contains(me.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                LocalDate on = Instant.ofEpochMilli(m.internalDateMillis()).atZone(ZoneId.of(gtn.zone())).toLocalDate();
                if (network.emailReply(t.trackId(), m.id(), t.id(), unescape(m.snippet()), on)) {
                    found++;
                }
            }
        }
        return found;
    }

    /** Gmail's preview is HTML-escaped. */
    static String unescape(String s) {
        return s == null ? null : s.replace("&#39;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&amp;", "&");
    }

    @Component
    @ConditionalOnProperty(name = "app.google.poll-enabled", havingValue = "true", matchIfMissing = true)
    static class Poller {
        private final ReplyChecker checker;

        Poller(ReplyChecker checker) {
            this.checker = checker;
        }

        @Scheduled(fixedDelayString = "${app.google.poll-minutes:10}", initialDelay = 2, timeUnit = TimeUnit.MINUTES)
        void poll() {
            int found = checker.pollAll();
            if (found > 0) {
                LOG.info("GTN found {} new email repl{}.", found, found == 1 ? "y" : "ies");
            }
        }
    }
}
