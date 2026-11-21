package com.codewalnut.bd.google;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.codewalnut.bd.shared.AuditLog;
import com.codewalnut.bd.shared.CurrentUser;
import com.codewalnut.bd.shared.RuleViolationException;

import tools.jackson.databind.json.JsonMapper;

/**
 * Team members' own Google connections for GTN (ADR-0020): connect with incremental consent, keep tokens
 * encrypted, hand out fresh access tokens, disconnect (revoking at Google).
 */
@Service
public class GoogleAccounts {

    public static final String SEND = "https://www.googleapis.com/auth/gmail.send";
    public static final String READ = "https://www.googleapis.com/auth/gmail.metadata";
    public static final String EVENTS = "https://www.googleapis.com/auth/calendar.events";
    public static final String FREEBUSY = "https://www.googleapis.com/auth/calendar.freebusy";
    static final List<String> SCOPES = List.of("openid", "email", SEND, READ, EVENTS, FREEBUSY);

    /** {@code available}: the app is set up for it; the rest is this person's connection. */
    public record Status(boolean available, boolean connected, String email, boolean canSend, boolean canRead,
                         boolean canCalendar, Instant connectedAt, String problem) {}

    private final JdbcClient jdbc;
    private final GoogleApi api;
    private final GoogleProperties props;
    private final AuditLog audit;
    private final TokenCipher cipher;

    GoogleAccounts(JdbcClient jdbc, GoogleApi api, GoogleProperties props, AuditLog audit) {
        this.jdbc = jdbc;
        this.api = api;
        this.props = props;
        this.audit = audit;
        this.cipher = props.key() == null ? null : new TokenCipher(props.key());
    }

    public boolean available() {
        return props.enabled();
    }

    public Status status(long userId) {
        if (!available()) {
            return new Status(false, false, null, false, false, false, null, null);
        }
        return jdbc.sql("SELECT email, scopes, connected_at, last_error FROM google_account WHERE user_id = :u").param("u", userId)
            .query((rs, n) -> {
                Set<String> scopes = Set.of(rs.getString(2).split(" "));
                return new Status(true, rs.getString(4) == null, rs.getString(1), scopes.contains(SEND), scopes.contains(READ),
                    scopes.contains(EVENTS) && scopes.contains(FREEBUSY), rs.getTimestamp(3).toInstant(), rs.getString(4));
            }).optional().orElse(new Status(true, false, null, false, false, false, null, null));
    }

    /** Google's consent page for this person, asking for GTN's scopes on top of sign-in. */
    public String authUrl(CurrentUser user, String state, String redirectUri) {
        require();
        return "https://accounts.google.com/o/oauth2/v2/auth?client_id=" + enc(props.clientId()) + "&redirect_uri=" + enc(redirectUri)
            + "&response_type=code&scope=" + enc(String.join(" ", SCOPES)) + "&access_type=offline&prompt=consent"
            + "&include_granted_scopes=true&state=" + enc(state) + "&login_hint=" + enc(user.email());
    }

    /** Finishes the consent: only the person's own mailbox (the address they sign in with) can be connected. */
    @Transactional
    public Status complete(CurrentUser user, String code, String redirectUri) {
        require();
        GoogleApi.Tokens t = api.exchange(code, redirectUri);
        if (t.email() == null || !t.email().equalsIgnoreCase(user.email())) {
            if (t.refreshToken() != null) {
                api.revoke(t.refreshToken());
            }
            throw new RuleViolationException(List.of("Connect your own Google account, " + user.email() + "."));
        }
        if (t.refreshToken() == null) {
            throw new RuleViolationException(List.of("Google didn't grant offline access. Disconnect the app in your Google account "
                + "settings and connect again."));
        }
        jdbc.sql("DELETE FROM google_account WHERE user_id = :u").param("u", user.id()).update();
        jdbc.sql("""
                INSERT INTO google_account (user_id, email, refresh_token_enc, access_token_enc, access_expires_at, scopes)
                VALUES (:u, :e, :r, :a, :x, :s)
                """).param("u", user.id()).param("e", t.email().toLowerCase(Locale.ROOT)).param("r", cipher.encrypt(t.refreshToken()))
            .param("a", cipher.encrypt(t.accessToken())).param("x", java.sql.Timestamp.from(Instant.now().plusSeconds(t.expiresInSeconds())))
            .param("s", t.scope() == null ? "" : t.scope()).update();
        audit.record("google_account", user.id(), com.codewalnut.bd.shared.AuditLog.Action.CREATE, Map.of("scopes", t.scope() == null ? "" : t.scope()),
            user.id());
        return status(user.id());
    }

    @Transactional
    public void disconnect(CurrentUser user) {
        String refresh = jdbc.sql("SELECT refresh_token_enc FROM google_account WHERE user_id = :u").param("u", user.id())
            .query(String.class).optional().map(this::decryptQuietly).orElse(null);
        if (refresh != null) {
            api.revoke(refresh);
        }
        jdbc.sql("DELETE FROM google_account WHERE user_id = :u").param("u", user.id()).update();
        audit.record("google_account", user.id(), AuditLog.Action.DELETE, Map.of(), user.id());
    }

    /** A usable access token for this person, refreshed when close to expiry; empty when not connected. */
    @Transactional
    public Optional<String> accessToken(long userId) {
        if (!available()) {
            return Optional.empty();
        }
        record Row(String refresh, String access, Instant expires, String error) {}
        Row row = jdbc.sql("""
                SELECT refresh_token_enc, access_token_enc, access_expires_at, last_error FROM google_account WHERE user_id = :u FOR UPDATE
                """).param("u", userId)
            .query((rs, n) -> new Row(rs.getString(1), rs.getString(2),
                rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant(), rs.getString(4)))
            .optional().orElse(null);
        if (row == null || row.error() != null) {
            return Optional.empty();
        }
        if (row.access() != null && row.expires() != null && row.expires().isAfter(Instant.now().plusSeconds(60))) {
            return Optional.of(cipher.decrypt(row.access()));
        }
        try {
            GoogleApi.Tokens t = api.refresh(cipher.decrypt(row.refresh()));
            jdbc.sql("UPDATE google_account SET access_token_enc = :a, access_expires_at = :x WHERE user_id = :u")
                .param("a", cipher.encrypt(t.accessToken())).param("x", java.sql.Timestamp.from(Instant.now().plusSeconds(t.expiresInSeconds())))
                .param("u", userId).update();
            return Optional.of(t.accessToken());
        } catch (GoogleApi.GoogleException e) {
            if (e.unauthorized()) {
                jdbc.sql("UPDATE google_account SET last_error = :e WHERE user_id = :u")
                    .param("e", "Google access was revoked or expired. Connect again.").param("u", userId).update();
                return Optional.empty();
            }
            throw e;
        }
    }

    /** People whose connection can read Gmail, for reply checks. */
    public List<Long> readers() {
        if (!available()) {
            return List.of();
        }
        return jdbc.sql("SELECT user_id FROM google_account WHERE last_error IS NULL AND scopes LIKE :s")
            .param("s", "%" + READ + "%").query(Long.class).list();
    }

    public String emailOf(long userId) {
        return jdbc.sql("SELECT email FROM google_account WHERE user_id = :u").param("u", userId).query(String.class).optional().orElse(null);
    }

    private String decryptQuietly(String s) {
        try {
            return cipher.decrypt(s);
        } catch (IllegalStateException e) {
            return null;
        }
    }

    private void require() {
        if (!available()) {
            throw new RuleViolationException(List.of("Google isn't set up for GTN yet: an admin sets GOOGLE_TOKEN_KEY (see docs/deployment.md)."));
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    @Configuration
    static class Wiring {
        @Bean
        GoogleApi googleApi(GoogleProperties props, JsonMapper json) {
            return new HttpGoogleApi(props, json);
        }
    }
}
