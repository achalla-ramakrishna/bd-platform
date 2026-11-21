package com.codewalnut.bd.network;

import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.codewalnut.bd.shared.CurrentUser;
import com.codewalnut.bd.shared.Role;
import com.codewalnut.bd.shared.RuleViolationException;
import com.codewalnut.bd.walnut.Mailer;
import com.codewalnut.bd.walnut.WalnutProperties;

/** Handing GTN contacts to a teammate: one, several, or all of someone's (ADR-0021); the new owner is emailed. */
@Service
public class HandOvers {

    /** {@code skipped}: contacts this person may not hand over, with why. */
    public record Result(int handedOver, String toName, List<String> skipped) {}

    private final JdbcClient jdbc;
    private final NetworkService network;
    private final Mailer mailer;
    private final WalnutProperties walnut;

    HandOvers(JdbcClient jdbc, NetworkService network, Mailer mailer, WalnutProperties walnut) {
        this.jdbc = jdbc;
        this.network = network;
        this.mailer = mailer;
        this.walnut = walnut;
    }

    @Transactional
    public Result some(List<Long> trackIds, long toUserId, String note, CurrentUser user) {
        if (trackIds == null || trackIds.isEmpty()) {
            throw new RuleViolationException(List.of("Choose the contacts to hand over."));
        }
        if (trackIds.size() > 500) {
            throw new RuleViolationException(List.of("Hand over at most 500 contacts at a time."));
        }
        // Checked before each hand-over rather than caught after, so one refusal doesn't undo the rest.
        String toName = jdbc.sql("""
                SELECT u.full_name FROM app_user u WHERE u.id = :id AND u.active
                  AND EXISTS (SELECT 1 FROM user_role r WHERE r.user_id = u.id AND r.role IN (:roles))
                """).param("id", toUserId).param("roles", Role.PIPELINE_EDITORS.stream().map(Enum::name).toList())
            .query(String.class).optional()
            .orElseThrow(() -> new RuleViolationException(List.of("Hand it to an active team member who works the pipeline.")));
        if (note != null && note.trim().length() > 300) {
            throw new RuleViolationException(List.of("Keep the hand-over note under 300 characters."));
        }
        boolean manager = user.hasAnyRole(Role.NETWORK_MANAGERS);
        List<String> skipped = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (long id : trackIds) {
            record Row(String name, long owner) {}
            Row row = jdbc.sql("SELECT n.full_name, t.owner_user_id FROM network_track t JOIN connection n ON n.id = t.connection_id WHERE t.id = :id")
                .param("id", id).query((rs, n) -> new Row(rs.getString(1), rs.getLong(2))).optional().orElse(null);
            if (row == null) {
                continue;
            }
            if (row.owner() != user.id() && !manager) {
                skipped.add(row.name() + ": someone else's contact");
            } else if (row.owner() == toUserId) {
                skipped.add(row.name() + ": already theirs");
            } else {
                network.handOver(id, toUserId, note, null, user);
                names.add(row.name());
            }
        }
        if (trackIds.size() == 1 && names.isEmpty() && !skipped.isEmpty()) {
            if (skipped.get(0).endsWith("someone else's contact")) {
                throw new AccessDeniedException("This is someone else's contact. Ask them, or a manager, to hand it over.");
            }
            throw new RuleViolationException(List.of("They already own this contact."));
        }
        tell(toUserId, names, user, note);
        return new Result(names.size(), toName, skipped);
    }

    /** Everything one person owns, e.g. when they leave. Managers only. */
    @Transactional
    public Result all(long fromUserId, long toUserId, String note, CurrentUser user) {
        user.require(Role.NETWORK_MANAGERS);
        if (fromUserId == toUserId) {
            throw new RuleViolationException(List.of("Choose two different people."));
        }
        List<Long> ids = jdbc.sql("SELECT id FROM network_track WHERE owner_user_id = :u").param("u", fromUserId).query(Long.class).list();
        if (ids.isEmpty()) {
            throw new RuleViolationException(List.of("They have no GTN contacts to hand over."));
        }
        return some(ids, toUserId, note, user);
    }

    private void tell(long toUserId, List<String> names, CurrentUser by, String note) {
        if (names.isEmpty() || !mailer.enabled() || toUserId == by.id()) {
            return;
        }
        String to = jdbc.sql("SELECT email FROM app_user WHERE id = :id AND active").param("id", toUserId).query(String.class)
            .optional().orElse(null);
        if (to == null) {
            return;
        }
        String who = names.size() == 1 ? names.get(0) : names.size() + " GTN contacts";
        String list = names.stream().limit(20).map(ProfileService::escape).reduce((a, b) -> a + ", " + b).orElse("");
        String html = "<p>" + ProfileService.escape(by.name()) + " handed " + ProfileService.escape(who) + " over to you"
            + (note == null || note.isBlank() ? "." : ": " + ProfileService.escape(note.trim())) + "</p>"
            + (names.size() > 1 ? "<p>" + list + (names.size() > 20 ? " and more" : "") + "</p>" : "")
            + "<p>They are in your GTN Today: <a href=\"" + ProfileService.escape(walnut.baseUrl() + "/network") + "\">"
            + ProfileService.escape(walnut.baseUrl() + "/network") + "</a></p>";
        try {
            mailer.send(to, by.name() + " handed " + who + " over to you", html);
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(HandOvers.class).warn("Hand-over email not sent: {}", e.getMessage());
        }
    }
}
