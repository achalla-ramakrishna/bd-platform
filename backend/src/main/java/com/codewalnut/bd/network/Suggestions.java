package com.codewalnut.bd.network;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.codewalnut.bd.reporting.NetworkQueries.Track;
import com.codewalnut.bd.shared.CurrentUser;

/**
 * What to do next for a track and the message to send (ADR-0018): the task for its stage, the playbook's
 * channel, and the matching template with its merge fields filled in.
 */
@Service
public class Suggestions {

    /** The message to send, ready to edit. {@code channel}: LINKEDIN, EMAIL or WHATSAPP. */
    public record Suggestion(Long templateId, String templateName, String channel, String subject, String body) {}

    /**
     * One row of Today. {@code task}: CHECK, DIAGNOSE, NUDGE, INTRO, BOOK_CALL, CALL_REMINDER, CALL_OUTCOME or BEEP.
     * {@code suggestedReason}: for DIAGNOSE, the reason to pre-select.
     */
    public record Next(Track track, String task, String taskLabel, long overdueDays, String suggestedReason,
                       Suggestion suggestion) {}

    public record Template(long id, String name, String stage, String reason, String channel, String subject, String body,
                           boolean active, int version) {}

    private static final Pattern FIELD = Pattern.compile("\\{([a-z_]+)}");
    private static final List<String> ORDER = List.of("REPLY_CHECK", "ASK_NOW", "CALL_OUTCOME", "DIAGNOSE", "NUDGE", "INTRO", "BOOK_CALL",
        "CALL_REMINDER", "BEEP", "CHECK");

    private final JdbcClient jdbc;
    private final ProfileService profiles;
    private final PublicUrl publicUrl;

    Suggestions(JdbcClient jdbc, ProfileService profiles, PublicUrl publicUrl) {
        this.jdbc = jdbc;
        this.profiles = profiles;
        this.publicUrl = publicUrl;
    }

    public List<Template> templates(boolean activeOnly) {
        return jdbc.sql("""
                SELECT id, name, stage, reason, channel, subject, body, active, version FROM message_template
                WHERE (:all OR active) ORDER BY FIELD(stage, 'INITIATE','UNRESPONSIVE','RESPONDED','INTRO_MADE',
                    'CALL_SCHEDULED','WARM_COMMITMENT','MONTHLY_BEEP'), reason, name
                """).param("all", !activeOnly)
            .query((rs, n) -> new Template(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getBoolean(8), rs.getInt(9)))
            .list();
    }

    /** Today's rows, overdue first, then by how much the task moves someone on. */
    public List<Next> today(List<Track> due, LocalDate today, CurrentUser user) {
        List<Template> templates = templates(true);
        return due.stream().map(t -> next(t, today, user, templates))
            .sorted(Comparator.comparing((Next n) -> "REPLY_CHECK".equals(n.task()) ? 0 : n.overdueDays() > 0 ? 1
                : "ASK_NOW".equals(n.task()) ? 2 : 3)
                .thenComparing(Next::overdueDays, Comparator.reverseOrder())
                .thenComparing(n -> ORDER.indexOf(n.task()))
                .thenComparing(n -> n.track().fullName(), String.CASE_INSENSITIVE_ORDER))
            .toList();
    }

    public Next next(Track t, LocalDate today, CurrentUser user) {
        return next(t, today, user, templates(true));
    }

    private Next next(Track t, LocalDate today, CurrentUser user, List<Template> templates) {
        String task = task(t, today);
        long overdue = Math.max(0, ChronoUnit.DAYS.between(t.nextActionOn(), today));
        String channel = channel(t, task);
        boolean ask = "ASK_NOW".equals(task);
        String stage = ask ? "UNRESPONSIVE" : "BEEP".equals(task) ? "MONTHLY_BEEP" : t.stage();
        String reason = ask ? "INERTIA" : "UNRESPONSIVE".equals(t.stage()) ? t.whyReason() : null;
        Suggestion suggestion = "REPLY_CHECK".equals(task) || "DIAGNOSE".equals(task) || "CALL_OUTCOME".equals(task) || "CHECK".equals(task) ? null
            : pick(templates, stage, reason, channel).map(tpl -> render(tpl, channel, t, user)).orElse(null);
        return new Next(t, task, label(task, t), overdue, "DIAGNOSE".equals(task) ? suggestedReason(t) : null, suggestion);
    }

    /** A chosen template filled in for this person (for the start dialog, or to switch template). */
    public Suggestion render(Template tpl, String channel, Track t, CurrentUser user) {
        String profileLink = tpl.body().contains("{profile_link}") || (tpl.subject() != null && tpl.subject().contains("{profile_link}"))
            ? publicUrl.base() + "/p/" + profiles.token(t.connectionId(), t.ownerId()) : "";
        Map<String, String> values = Map.of(
            "profile_link", profileLink,
            "first_name", firstName(t.fullName()),
            "full_name", t.fullName(),
            "company", t.company() == null || t.company().isBlank() ? "your team" : t.company(),
            "how_we_know", t.howWeKnow() == null || t.howWeKnow().isBlank() ? "we last spoke" : lowerFirst(t.howWeKnow()),
            "last_topic", t.lastTopic() == null || t.lastTopic().isBlank() ? "our last chat" : clip(t.lastTopic(), 80),
            "revisit_note", t.revisitReason() == null || t.revisitReason().isBlank() ? ""
                : "You mentioned " + lowerFirst(t.revisitReason()) + " last time; hope that has eased.",
            "my_name", firstName(user.name()));
        return new Suggestion(tpl.id(), tpl.name(), channel, tpl.subject() == null ? null : fill(tpl.subject(), values),
            fill(tpl.body(), values).trim());
    }

    static String fill(String text, Map<String, String> values) {
        Matcher m = FIELD.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String v = values.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(v == null ? m.group() : v));
        }
        m.appendTail(out);
        return out.toString().replaceAll(" {2,}", " ");
    }

    static String task(Track t, LocalDate today) {
        if (t.replyCheck()) {
            return "REPLY_CHECK";
        }
        if (t.askNow()) {
            return "ASK_NOW";
        }
        if ("CALL_SCHEDULED".equals(t.stage()) && t.callAt() != null) {
            return t.callAt().isAfter(java.time.Instant.now()) ? "CALL_REMINDER" : "CALL_OUTCOME";
        }
        return switch (t.stage()) {
            case "INITIATE" -> "CHECK";
            case "UNRESPONSIVE" -> t.whyReason() == null ? "DIAGNOSE" : "NUDGE";
            case "RESPONDED" -> "INTRO";
            case "INTRO_MADE" -> "BOOK_CALL";
            case "CALL_SCHEDULED" -> t.callOn() == null ? "BOOK_CALL" : t.callOn().isAfter(today) ? "CALL_REMINDER" : "CALL_OUTCOME";
            default -> "BEEP";
        };
    }

    private static String label(String task, Track t) {
        return switch (task) {
            case "ASK_NOW" -> t.nextAction();
            case "REPLY_CHECK" -> "They replied by email: what did they say?";
            case "CHECK" -> "No reply yet: check, or mark them replied";
            case "DIAGNOSE" -> "No reply: why?";
            case "NUDGE" -> "Nudge (" + NetworkService.label(t.whyReason()) + ")";
            case "INTRO" -> "Make the intro";
            case "BOOK_CALL" -> "Book a call";
            case "CALL_REMINDER" -> "Call on " + t.callOn();
            case "CALL_OUTCOME" -> "How did the call go?";
            default -> t.revisitReason() != null && "MONTHLY_BEEP".equals(t.stage()) ? "Monthly beep (revisit)" : "Monthly beep";
        };
    }

    /** The playbook's channel (PRD §4): Not seen → email or WhatsApp; Inertia → same channel first, then email or WhatsApp. */
    static String channel(Track t, String task) {
        String direct = t.whatsapp() != null ? "WHATSAPP" : t.email() != null ? "EMAIL" : t.linkedinUrl() != null ? "LINKEDIN" : null;
        String last = t.lastChannel() != null && List.of("LINKEDIN", "EMAIL", "WHATSAPP").contains(t.lastChannel())
            ? t.lastChannel() : null;
        if ("ASK_NOW".equals(task)) {
            return direct != null ? direct : last != null ? last : "LINKEDIN";
        }
        if ("NUDGE".equals(task)) {
            if ("NOT_SEEN".equals(t.whyReason())) {
                return direct != null && !"LINKEDIN".equals(direct) ? direct : last != null ? last : "LINKEDIN";
            }
            if (t.nudgesSent() == 0 && last != null) {
                return last;
            }
            return direct != null ? direct : last != null ? last : "LINKEDIN";
        }
        if (last != null) {
            return last;
        }
        return t.linkedinUrl() != null ? "LINKEDIN" : direct != null ? direct : "LINKEDIN";
    }

    /** Inertia is the default reason (GTN FR-5.1): the message was most likely seen and not answered. */
    private static String suggestedReason(Track t) {
        return "INERTIA";
    }

    private static java.util.Optional<Template> pick(List<Template> templates, String stage, String reason, String channel) {
        return templates.stream()
            .filter(t -> t.stage().equals(stage) && (reason == null ? t.reason() == null : reason.equals(t.reason())))
            .filter(t -> t.channel().equals(channel) || "ANY".equals(t.channel()))
            .min(Comparator.comparing((Template t) -> t.channel().equals(channel) ? 0 : 1).thenComparing(Template::id));
    }

    static String firstName(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        return name.trim().split("\\s+")[0];
    }

    private static String lowerFirst(String s) {
        String t = s.trim();
        if (t.length() > 1 && Character.isUpperCase(t.charAt(0)) && Character.isLowerCase(t.charAt(1))) {
            return t.substring(0, 1).toLowerCase(Locale.ROOT) + t.substring(1);
        }
        return t;
    }

    private static String clip(String s, int max) {
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max - 1).trim() + "…";
    }
}
