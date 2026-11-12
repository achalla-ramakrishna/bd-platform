package com.codewalnut.bd.walnut;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.codewalnut.bd.assist.Coach;
import com.codewalnut.bd.reporting.BdSnapshot;
import com.codewalnut.bd.shared.CurrentUser;
import com.codewalnut.bd.shared.RuleViolationException;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Walnut, the BD agent (ADR-0015): one chat thread per person, a brief each weekday morning, and email.
 * Walnut proposes; people apply. Only the people Walnut works with (by role) can use it.
 */
@Service
class WalnutService {

    record Message(long id, String author, String kind, String body, List<Coach.Action> actions, Instant createdAt) {}

    record Settings(boolean emailBrief, boolean emailAvailable, String email) {}

    static final List<String> CHANNELS = List.of("LINKEDIN_CONNECTION", "LINKEDIN_MESSAGE", "INMAIL", "EMAIL", "CALL",
        "MEETING", "EVENT", "REFERRAL");
    static final List<String> OUTCOMES = List.of("NO_REPLY", "REPLIED", "MEETING_BOOKED", "NOT_INTERESTED", "REFERRED_ON", "DONE");
    static final int MAX_TEXT = 4000;
    private static final Pattern APP_PATH = Pattern.compile(
        "^/(accounts|conversations|opportunities|events|interviews|connections|pipeline|strategy|heatmap|walnut|assistant|improvements|find-gccs)(/\\d+|/new)?$");
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(WalnutService.class);

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final BdSnapshot snapshots;
    private final Coach coach;
    private final Mailer mailer;
    private final WalnutProperties props;

    WalnutService(JdbcClient jdbc, JsonMapper json, BdSnapshot snapshots, Coach coach, Mailer mailer, WalnutProperties props) {
        this.jdbc = jdbc;
        this.json = json;
        this.snapshots = snapshots;
        this.coach = coach;
        this.mailer = mailer;
        this.props = props;
    }

    LocalDate today() {
        return LocalDate.now(ZoneId.of(props.zone()));
    }

    boolean worksWith(CurrentUser user) {
        return user.roles().stream().anyMatch(r -> props.roles().contains(r.name()));
    }

    void require(CurrentUser user) {
        if (!worksWith(user)) {
            throw new AccessDeniedException("Walnut works with " + String.join(", ", props.roles()) + " users.");
        }
    }

    boolean chatEnabled() {
        return coach.enabled();
    }

    boolean emailEnabled() {
        return mailer.enabled();
    }

    List<BdSnapshot.Person> team() {
        return snapshots.team(props.roles());
    }

    List<Message> messages(long userId, Long before, int limit) {
        List<Message> newestFirst = jdbc.sql("""
                SELECT id, author, kind, body, actions, created_at FROM walnut_message
                WHERE user_id = :u AND (:before IS NULL OR id < :before) ORDER BY id DESC LIMIT :limit
                """).param("u", userId).param("before", before).param("limit", Math.min(Math.max(limit, 1), 100))
            .query((rs, n) -> new Message(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                actions(rs.getString(5)), rs.getTimestamp(6).toInstant()))
            .list();
        List<Message> out = new ArrayList<>(newestFirst);
        Collections.reverse(out);
        return out;
    }

    /** The person's message and Walnut's reply. Nothing is stored if Walnut cannot answer. */
    @Transactional
    List<Message> chat(CurrentUser user, String text) {
        String t = text == null ? "" : text.strip();
        if (t.isEmpty()) {
            throw new RuleViolationException(List.of("Type a message for Walnut."));
        }
        if (t.length() > MAX_TEXT) {
            throw new RuleViolationException(List.of("That is too long. Keep messages under " + MAX_TEXT + " characters."));
        }
        BdSnapshot.Person me = person(user);
        BdSnapshot.Snapshot snap = snapshots.of(me, team(), today());
        List<Coach.Turn> history = messages(user.id(), null, 12).stream()
            .map(m -> new Coach.Turn(m.author(), m.body().length() > 3000 ? m.body().substring(0, 3000) : m.body()))
            .toList();
        Coach.Reply reply = coach.reply(new Coach.Request(me.name(), today(), json.writeValueAsString(snap), history, t,
            CHANNELS, OUTCOMES));
        Message mine = save(user.id(), "PERSON", "CHAT", t, List.of());
        Message walnut = save(user.id(), "WALNUT", "CHAT", reply.reply().isBlank() ? "…" : reply.reply(), checked(reply.actions()));
        return List.of(mine, walnut);
    }

    /** Today's brief for the person: written once a day, then the same message is returned. */
    @Transactional
    Message brief(BdSnapshot.Person person, LocalDate day) {
        Optional<Long> existing = briefMessageId(person.id(), day);
        if (existing.isPresent()) {
            return message(existing.get());
        }
        BriefWriter.Brief brief = BriefWriter.write(snapshots.of(person, team(), day));
        String kind = day.getDayOfWeek() == java.time.DayOfWeek.FRIDAY ? "REVIEW" : "BRIEF";
        Message m = save(person.id(), "WALNUT", kind, "**" + brief.subject() + "**\n\n" + brief.body(),
            checked(brief.actions()));
        try {
            jdbc.sql("INSERT INTO walnut_brief (user_id, brief_on, message_id) VALUES (:u, :d, :m)")
                .param("u", person.id()).param("d", day).param("m", m.id()).update();
        } catch (DuplicateKeyException race) {
            jdbc.sql("DELETE FROM walnut_message WHERE id = :id").param("id", m.id()).update();
            return message(briefMessageId(person.id(), day).orElseThrow());
        }
        return m;
    }

    /** Emails today's brief once, if email is on for the person. Returns whether an email went out. */
    boolean email(BdSnapshot.Person person, LocalDate day, boolean force) {
        if (!mailer.enabled() || !force && !settings(person.id()).emailBrief()) {
            return false;
        }
        Message m = brief(person, day);
        boolean sent = jdbc.sql("SELECT emailed_at IS NOT NULL FROM walnut_brief WHERE user_id = :u AND brief_on = :d")
            .param("u", person.id()).param("d", day).query(Boolean.class).single();
        if (sent && !force) {
            return false;
        }
        String subject = "Walnut: " + m.body().lines().findFirst().orElse("your BD plan").replace("**", "");
        String link = props.baseUrl().isBlank() ? "" : "<p style=\"margin-top:18px\"><a href=\"" + props.baseUrl()
            + "/walnut\" style=\"background:#5b3a29;color:#fff;padding:8px 14px;border-radius:6px;text-decoration:none\">Reply to Walnut</a></p>";
        String body = m.body().lines().skip(1).reduce("", (a, b) -> a + b + "\n");
        mailer.send(person.email(), subject, MailHtml.of(body, props.baseUrl()) + link);
        jdbc.sql("UPDATE walnut_brief SET emailed_at = CURRENT_TIMESTAMP(3) WHERE user_id = :u AND brief_on = :d")
            .param("u", person.id()).param("d", day).update();
        return true;
    }

    /** The scheduled morning run: a brief (and email) for everyone Walnut works with. One failure does not stop the rest. */
    void morning(LocalDate day) {
        for (BdSnapshot.Person p : team()) {
            try {
                brief(p, day);
                email(p, day, false);
            } catch (RuntimeException e) {
                LOG.warn("Walnut could not brief {}: {}", p.email(), e.getMessage());
            }
        }
    }

    Settings settings(long userId) {
        boolean on = jdbc.sql("SELECT email_brief FROM walnut_settings WHERE user_id = :u").param("u", userId)
            .query(Boolean.class).optional().orElse(true);
        String email = jdbc.sql("SELECT email FROM app_user WHERE id = :u").param("u", userId).query(String.class).single();
        return new Settings(on, mailer.enabled(), email);
    }

    Settings updateSettings(long userId, boolean emailBrief) {
        jdbc.sql("""
                INSERT INTO walnut_settings (user_id, email_brief) VALUES (:u, :e)
                ON DUPLICATE KEY UPDATE email_brief = VALUES(email_brief), updated_at = CURRENT_TIMESTAMP(3)
                """).param("u", userId).param("e", emailBrief).update();
        return settings(userId);
    }

    BdSnapshot.Person person(CurrentUser user) {
        return new BdSnapshot.Person(user.id(), user.name(), user.email());
    }

    /** Keeps only actions the page can apply safely: known app paths, real conversations, valid codes and dates. */
    List<Coach.Action> checked(List<Coach.Action> proposed) {
        List<Coach.Action> out = new ArrayList<>();
        for (Coach.Action a : proposed == null ? List.<Coach.Action>of() : proposed) {
            String label = a.label() == null || a.label().isBlank() ? null : a.label().strip();
            switch (a.type()) {
                case "OPEN" -> {
                    if (a.path() != null && APP_PATH.matcher(a.path()).matches()) {
                        out.add(new Coach.Action("OPEN", label == null ? "Open" : label, a.path(), "", null, "", "", "", "", ""));
                    }
                }
                case "DRAFT" -> {
                    if (a.text() != null && !a.text().isBlank()) {
                        out.add(new Coach.Action("DRAFT", label == null ? "Draft" : label, "", cut(a.text().strip(), 3000),
                            null, "", "", "", "", ""));
                    }
                }
                case "SET_NEXT_STEP", "LOG_UPDATE" -> {
                    if (a.conversationId() == null || !conversationExists(a.conversationId())) {
                        continue;
                    }
                    String due = date(a.nextStepDue());
                    String next = a.nextStep() == null ? "" : cut(a.nextStep().strip(), 500);
                    if ("SET_NEXT_STEP".equals(a.type())) {
                        if (!next.isEmpty()) {
                            out.add(new Coach.Action("SET_NEXT_STEP", label == null ? "Set next step" : label, "", "",
                                a.conversationId(), next, due, "", "", ""));
                        }
                    } else if (a.summary() != null && !a.summary().isBlank()) {
                        out.add(new Coach.Action("LOG_UPDATE", label == null ? "Log this update" : label, "", "",
                            a.conversationId(), next, next.isEmpty() ? "" : due,
                            CHANNELS.contains(a.channel()) ? a.channel() : "LINKEDIN_MESSAGE", cut(a.summary().strip(), 2000),
                            OUTCOMES.contains(a.outcome()) ? a.outcome() : "DONE"));
                    }
                }
                default -> { }
            }
            if (out.size() >= 6) {
                break;
            }
        }
        return out;
    }

    private boolean conversationExists(long id) {
        return jdbc.sql("SELECT COUNT(*) FROM conversation WHERE id = :id").param("id", id).query(Integer.class).single() > 0;
    }

    private static String date(String s) {
        if (s == null || s.isBlank()) {
            return "";
        }
        try {
            return LocalDate.parse(s.strip()).toString();
        } catch (DateTimeParseException e) {
            return "";
        }
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private Optional<Long> briefMessageId(long userId, LocalDate day) {
        return jdbc.sql("SELECT message_id FROM walnut_brief WHERE user_id = :u AND brief_on = :d")
            .param("u", userId).param("d", day).query(Long.class).optional();
    }

    private Message save(long userId, String author, String kind, String body, List<Coach.Action> actions) {
        var key = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO walnut_message (user_id, author, kind, body, actions) VALUES (:u, :a, :k, :b, :x)")
            .param("u", userId).param("a", author).param("k", kind).param("b", body)
            .param("x", actions.isEmpty() ? null : json.writeValueAsString(actions)).update(key);
        return message(key.getKey().longValue());
    }

    private Message message(long id) {
        return jdbc.sql("SELECT id, author, kind, body, actions, created_at FROM walnut_message WHERE id = :id")
            .param("id", id)
            .query((rs, n) -> new Message(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                actions(rs.getString(5)), rs.getTimestamp(6).toInstant()))
            .single();
    }

    private List<Coach.Action> actions(String stored) {
        return stored == null ? List.of() : json.readValue(stored, new TypeReference<List<Coach.Action>>() {});
    }
}
