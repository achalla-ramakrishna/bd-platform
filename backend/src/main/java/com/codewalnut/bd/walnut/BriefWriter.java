package com.codewalnut.bd.walnut;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.codewalnut.bd.assist.Coach;
import com.codewalnut.bd.reporting.BdSnapshot;

/**
 * Writes the morning brief from the snapshot alone, so it works without Claude (ADR-0015): today's
 * follow-ups, deals that need a push, new accounts to approach, events, questions for the person and, on
 * Fridays, the team's week. Links are app paths; emails make them absolute.
 */
final class BriefWriter {

    record Brief(String subject, String body, List<Coach.Action> actions) {}

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

    private BriefWriter() {}

    static Brief write(BdSnapshot.Snapshot s) {
        boolean friday = s.today().getDayOfWeek() == DayOfWeek.FRIDAY;
        String first = s.me().name().split("\\s+")[0];
        StringBuilder b = new StringBuilder();
        List<Coach.Action> actions = new ArrayList<>();
        List<String> questions = new ArrayList<>();

        b.append("Good morning ").append(first).append(friday ? ". Here is what will close the week well." : ". Here is what will move BD forward today.").append("\n\n");

        List<BdSnapshot.FollowUp> due = new ArrayList<>(s.overdue());
        due.addAll(s.dueSoon());
        if (!due.isEmpty()) {
            b.append("## Follow up\n");
            for (BdSnapshot.FollowUp f : due) {
                boolean late = f.due() != null && f.due().isBefore(s.today());
                b.append("- [").append(who(f)).append("](/conversations/").append(f.conversationId()).append(") at ")
                    .append(f.account()).append(": ").append(f.nextStep() == null ? "follow up" : f.nextStep())
                    .append(late ? " (overdue since " : " (due ").append(f.due() == null ? "" : f.due().format(DAY)).append(")\n");
            }
            b.append('\n');
            for (BdSnapshot.FollowUp f : s.overdue().stream().limit(3).toList()) {
                questions.add("Did you follow up with " + who(f) + " at " + f.account() + "? Tell me what happened and I will draft the update.");
            }
        }

        if (!s.deals().isEmpty()) {
            b.append("## Deals to push\n");
            for (BdSnapshot.Deal d : s.deals()) {
                b.append("- [").append(d.name()).append("](/opportunities/").append(d.opportunityId()).append("), ")
                    .append(d.account()).append(": ").append(d.stage());
                if (d.valueInr() != null) b.append(", ").append(inr(d.valueInr()));
                if (d.expectedClose() != null) b.append(", close ").append(d.expectedClose().format(DAY));
                if (!d.missing().isEmpty()) b.append(". Missing: ").append(String.join(", ", d.missing()));
                b.append('\n');
            }
            b.append('\n');
            s.deals().stream().filter(d -> !d.missing().isEmpty()).limit(2).forEach(d -> questions.add(
                "For " + d.name() + " (" + d.account() + "), what do we know about " + d.missing().get(0) + "?"));
        }

        java.util.Set<Long> listed = new java.util.HashSet<>();
        due.forEach(f -> listed.add(f.conversationId()));
        List<BdSnapshot.Step> steps = s.lifecycle().stream().filter(st -> !listed.contains(st.conversationId())).toList();
        if (!steps.isEmpty()) {
            b.append("## Move each conversation one step\n");
            boolean drafted = false;
            for (BdSnapshot.Step st : steps.stream().limit(8).toList()) {
                long days = st.since() == null ? 0 : java.time.temporal.ChronoUnit.DAYS.between(st.since(), s.today());
                b.append("- [").append(st.person() == null ? st.account() : st.person()).append("](/conversations/")
                    .append(st.conversationId()).append(") at ").append(st.account()).append(": ");
                switch (st.stage()) {
                    case "REQUEST_SENT" -> {
                        b.append("connection request sent ").append(ago(days)).append(". Waiting for them to accept.");
                        if (days >= 3) {
                            questions.add("Has " + name(st) + " at " + st.account() + " accepted your connection request?");
                        }
                    }
                    case "CONNECTED" -> {
                        b.append("**connected: send the first message now.**");
                        if (!drafted) {
                            actions.add(new Coach.Action("DRAFT", "First message to " + cut(name(st), 20), "",
                                firstMessage(st), null, "", "", "", "", ""));
                            drafted = true;
                        }
                        questions.add(name(st) + " accepted. Shall I tailor the first message, or have you sent it?");
                    }
                    case "MESSAGED" -> b.append(days >= 4 ? "no reply for " + days + " days: send a short, useful nudge."
                        : "message sent " + ago(days) + ". Give it until " + s.today().plusDays(4 - days).format(DAY) + ".");
                    case "REPLIED" -> b.append("**they replied: propose a 20-minute call this week.**");
                    case "MEETING" -> {
                        b.append("meeting booked: prepare, and afterwards record the need as an opportunity.");
                        questions.add("How did the meeting with " + name(st) + " go? If there is a need, I will set up the opportunity.");
                    }
                    default -> b.append(st.stage().toLowerCase(Locale.ROOT));
                }
                b.append('\n');
            }
            b.append('\n');
        }

        if (!s.targets().isEmpty()) {
            b.append("## Top ").append(s.targets().size()).append(" accounts to approach\n");
            for (BdSnapshot.Target t : s.targets()) {
                String who = t.whoToContact() != null && !t.whoToContact().isBlank() && !"null".equals(t.whoToContact())
                    ? t.whoToContact() : "the engineering or centre head";
                b.append("- [").append(t.name()).append("](/accounts/").append(t.accountId()).append(")")
                    .append(t.city() == null ? "" : ", " + t.city()).append(" (fit ").append(t.fitScore()).append(")")
                    .append(": send a LinkedIn connection request to ").append(who);
                if (t.warmPaths() > 0) {
                    b.append(", or ask one of our ").append(t.warmPaths()).append(" warm path").append(t.warmPaths() == 1 ? "" : "s")
                        .append(" for an introduction");
                }
                b.append('\n');
            }
            b.append("\nSend the requests yourself on LinkedIn, then log each one (Log with AI, or tell me here).\n\n");
            BdSnapshot.Target top = s.targets().get(0);
            actions.add(new Coach.Action("DRAFT", "Connection note for " + cut(top.name(), 18), "", connectionNote(top),
                null, "", "", "", "", ""));
            questions.add("Which of these will you send a connection request to today?");
        }

        if (!s.quiet().isEmpty()) {
            b.append("## Gone quiet\n");
            for (BdSnapshot.FollowUp f : s.quiet()) {
                b.append("- [").append(who(f)).append("](/conversations/").append(f.conversationId()).append(") at ")
                    .append(f.account()).append(": no touch since ").append(f.lastTouchOn().format(DAY)).append('\n');
            }
            b.append('\n');
            BdSnapshot.FollowUp q = s.quiet().get(0);
            questions.add(who(q) + " at " + q.account() + " has been quiet since " + q.lastTouchOn().format(DAY)
                + ". Shall we try once more, or close it?");
        }

        if (!s.events().isEmpty()) {
            b.append("## Coming up\n");
            for (BdSnapshot.EventItem e : s.events()) {
                b.append("- [").append(e.name()).append("](/events/").append(e.eventId()).append("), ")
                    .append(e.startsOn().format(DAY)).append(e.city() == null ? "" : ", " + e.city())
                    .append(e.going() ? ". You are going" : "").append('\n');
            }
            b.append('\n');
            s.events().stream().filter(e -> !e.going() && e.startsOn().isBefore(s.today().plusDays(8))).findFirst()
                .ifPresent(e -> questions.add("Is anyone from CodeWalnut going to " + e.name() + " on " + e.startsOn().format(DAY) + "?"));
        }

        if (!s.interviews().isEmpty()) {
            b.append("## Leader interviews\n");
            for (BdSnapshot.InterviewItem i : s.interviews()) {
                b.append("- [").append(i.leader()).append("](/interviews/").append(i.interviewId()).append("): ")
                    .append(i.topic()).append(" (").append(i.status().toLowerCase(Locale.ROOT))
                    .append(i.scheduledOn() == null ? "" : ", " + i.scheduledOn().format(DAY)).append(")\n");
            }
            b.append('\n');
        }

        if (friday || s.teamWeek().stream().anyMatch(w -> w.touches() > 0)) {
            b.append(friday ? "## Your week\n" : "## Team, last 7 days\n");
            for (BdSnapshot.TeamWeek w : s.teamWeek()) {
                b.append("- ").append(w.name()).append(": ").append(w.touches()).append(" touches, ")
                    .append(w.started()).append(" new conversations, ").append(w.meetings()).append(" meetings booked, ")
                    .append(w.connections()).append(" new connections\n");
            }
            b.append('\n');
            if (friday) {
                questions.add("What was the best thing that happened in BD this week, and what got stuck?");
            }
        }

        if (due.isEmpty() && s.deals().isEmpty() && s.targets().isEmpty() && s.quiet().isEmpty() && steps.isEmpty()) {
            b.append("Nothing is due today. A good day to add the people you know as connections and log last week's touches.\n\n");
        }

        if (!questions.isEmpty()) {
            b.append("## Questions for you\n");
            for (int i = 0; i < Math.min(questions.size(), 5); i++) {
                b.append(i + 1).append(". ").append(questions.get(i)).append('\n');
            }
            b.append("\nReply here and I will turn your answers into updates for you to approve.\n");
        }

        actions.add(new Coach.Action("OPEN", "Open conversations", "/conversations", "", null, "", "", "", "", ""));
        if (!s.targets().isEmpty()) {
            actions.add(new Coach.Action("OPEN", "Best accounts by fit", "/accounts", "", null, "", "", "", "", ""));
        }
        String subject = (friday ? "Your Friday review, " : "Your BD plan for ") + s.today().format(DAY);
        return new Brief(subject, b.toString().strip(), actions);
    }

    /** A LinkedIn connection note: under 300 characters, about them, no pitch. */
    static String connectionNote(BdSnapshot.Target t) {
        String note = "Hi [name], I'm with CodeWalnut. We work with GCC engineering teams in India on AI-native "
            + "product engineering, and I follow " + t.name() + "'s work. I'd be glad to connect and share notes.";
        return note.length() <= 300 ? note : note.substring(0, 300);
    }

    /** The account research's opening message with their first name, or a short default. */
    static String firstMessage(BdSnapshot.Step st) {
        String first = st.person() == null ? "[name]" : st.person().split("\\s+")[0];
        String opening = st.openingMessage();
        if (opening == null || opening.isBlank() || "null".equals(opening)) {
            opening = "Hi [name], thanks for connecting. CodeWalnut adds senior engineers who build with AI daily to GCC "
                + "teams, or a small pod that owns one use case end to end, within weeks. Would a 20-minute call to "
                + "compare notes on what " + st.account() + " is building be useful?";
        }
        return opening.replace("[name]", first);
    }

    private static String name(BdSnapshot.Step st) {
        return st.person() == null || st.person().isBlank() ? "the contact" : st.person();
    }

    private static String ago(long days) {
        return days <= 0 ? "today" : days == 1 ? "yesterday" : days + " days ago";
    }

    private static String who(BdSnapshot.FollowUp f) {
        return f.person() == null || f.person().isBlank() ? f.topic() : f.person();
    }

    static String inr(BigDecimal v) {
        double d = v.doubleValue();
        if (d >= 1_00_00_000) return String.format(Locale.ENGLISH, "₹%.1f Cr", d / 1_00_00_000);
        if (d >= 1_00_000) return String.format(Locale.ENGLISH, "₹%.1f L", d / 1_00_000);
        return String.format(Locale.ENGLISH, "₹%,.0f", d);
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    static boolean isWeekday(LocalDate d) {
        return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY;
    }
}
