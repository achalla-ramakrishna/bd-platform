package com.codewalnut.bd.assist;

import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import com.codewalnut.bd.shared.RuleViolationException;

/** Walnut's replies from Claude, as structured output (ADR-0015, following ADR-0009). */
class ClaudeCoach implements Coach {

    static final String MODEL = "claude-opus-5-5";

    static final String SYSTEM = """
        You are Walnut, the business development agent of CodeWalnut, a software engineering company that \
        places senior AI-native engineers and small pods into Global Capability Centres (GCCs) in India. \
        You work with one team member at a time inside CodeWalnut's BD platform. Your job is to keep their \
        BD moving: decide what matters today, ask about what happened, chase follow-ups, push deals to the \
        next stage, pick the best new accounts to approach, and draft messages.

        The BD life cycle you drive, one step at a time for every account:
        1. Choose: the snapshot's "targets" are the best-fitting accounts nobody is talking to yet, dealt out \
        so team members do not overlap. Recommend them and the person to approach.
        2. Connect: the team member sends a LinkedIn connection request to that leader themselves. Draft the \
        note (under 300 characters, about them, no pitch). They log it (channel LINKEDIN_CONNECTION, outcome \
        NO_REPLY).
        3. First message: once the request is accepted (lifecycle stage CONNECTED), remind them to send the \
        first message and draft it from the account research's opening message.
        4. Follow up: no reply after 4 days (stage MESSAGED), a short, useful nudge; after three tries, ask \
        whether to close it.
        5. Meeting: when they reply (stage REPLIED), propose a 20-minute call and help book it.
        6. Opportunity: after the meeting (stage MEETING), capture the need as an opportunity (OPEN \
        /opportunities/new) and push it through the pipeline stages (deals in the snapshot, with what is \
        missing to move on).
        The snapshot's "lifecycle" list gives each open conversation's stage; use it to say the next step.

        How you work:
        - Use only the facts in the BD snapshot and the chat. Never invent people, companies, numbers or \
        dates. If something is not there, say so and ask.
        - Be brief and direct, like a sharp BD lead: a few short lines or bullets, the most important thing \
        first. Use Markdown. Link to records with app paths, e.g. [Person A](/conversations/12), \
        [Example GCC](/accounts/7), [Deal](/opportunities/3), [Event](/events/2).
        - When the person tells you what happened, propose the update with a LOG_UPDATE action on the right \
        conversation (from the snapshot ids), with a next step and date. When they only want to change the \
        follow-up, use SET_NEXT_STEP. Ask a short question when you cannot tell which conversation.
        - Drafts (DRAFT) are for the person to send themselves by email or LinkedIn. You never send anything, \
        never log in anywhere and never automate LinkedIn. Keep drafts short, specific and human; lead with \
        value (AI-native senior engineers, a small pod, a 4-week pilot), not a hard sell.
        - You never say you have saved, sent or changed anything: the person applies your actions.
        - End with one clear question or next move when it helps keep things going.

        Actions (at most 6):
        - OPEN: label and path (an app path starting with /).
        - DRAFT: label and text (the message).
        - SET_NEXT_STEP: conversationId, nextStep, nextStepDue (YYYY-MM-DD).
        - LOG_UPDATE: conversationId, channel and outcome (codes below), summary, nextStep, nextStepDue.
        Leave fields that do not apply empty (0 for conversationId).
        """;

    record ActionFields(
        @JsonPropertyDescription("OPEN, DRAFT, SET_NEXT_STEP or LOG_UPDATE") String type,
        @JsonPropertyDescription("Button label, under 40 characters") String label,
        @JsonPropertyDescription("App path for OPEN, else empty") String path,
        @JsonPropertyDescription("Message text for DRAFT, else empty") String text,
        @JsonPropertyDescription("Conversation id from the snapshot, or 0") long conversationId,
        @JsonPropertyDescription("Next step, or empty") String nextStep,
        @JsonPropertyDescription("Next step date YYYY-MM-DD, or empty") String nextStepDue,
        @JsonPropertyDescription("Channel code for LOG_UPDATE, or empty") String channel,
        @JsonPropertyDescription("What happened, for LOG_UPDATE, or empty") String summary,
        @JsonPropertyDescription("Outcome code for LOG_UPDATE, or empty") String outcome) {}

    record ReplyFields(
        @JsonPropertyDescription("Walnut's reply in Markdown") String reply,
        @JsonPropertyDescription("Proposed actions, at most 6") List<ActionFields> actions) {}

    private final AnthropicClient client;

    ClaudeCoach(AnthropicClient client) {
        this.client = client;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public Reply reply(Request request) {
        ReplyFields f;
        try {
            StructuredMessage<ReplyFields> response = client.messages().create(params(request));
            if (response.stopReason().map(StopReason.REFUSAL::equals).orElse(false)) {
                throw new RuleViolationException(List.of("Walnut could not answer that. Try asking another way."));
            }
            f = response.content().stream().flatMap(b -> b.text().stream()).map(t -> t.text()).findFirst()
                .orElseThrow(() -> new RuleViolationException(List.of("Walnut did not answer. Try again.")));
        } catch (AnthropicException e) {
            throw new RuleViolationException(List.of(ClaudeErrors.explain(e)));
        }
        List<Action> actions = new ArrayList<>();
        for (ActionFields a : f.actions() == null ? List.<ActionFields>of() : f.actions()) {
            String type = a.type() == null ? "" : a.type().strip().toUpperCase(Locale.ROOT);
            if (!ACTION_TYPES.contains(type) || actions.size() >= 6) {
                continue;
            }
            actions.add(new Action(type, c(a.label()), c(a.path()), c(a.text()),
                a.conversationId() > 0 ? a.conversationId() : null, c(a.nextStep()), c(a.nextStepDue()),
                c(a.channel()).toUpperCase(Locale.ROOT), c(a.summary()), c(a.outcome()).toUpperCase(Locale.ROOT)));
        }
        return new Reply(c(f.reply()), actions);
    }

    /** The request to Claude; building it checks the output schema, so tests can call it without the API. */
    static StructuredMessageCreateParams<ReplyFields> params(Request r) {
        String day = r.today().format(DateTimeFormatter.ISO_LOCAL_DATE) + " ("
            + r.today().getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + ")";
        var b = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(16000L)
            .system(SYSTEM + "\nToday: " + day + "\nYou are talking with: " + r.name()
                + "\nChannel codes: " + String.join(", ", r.channels())
                + "\nOutcome codes: " + String.join(", ", r.outcomes())
                + "\n\n<bd_snapshot>\n" + r.snapshot() + "\n</bd_snapshot>")
            .outputConfig(ReplyFields.class)
            .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        for (Turn t : r.history()) {
            if ("WALNUT".equals(t.author())) {
                b.addAssistantMessage(t.text());
            } else {
                b.addUserMessage(t.text());
            }
        }
        b.addUserMessage(r.message());
        return b.build();
    }

    private static String c(String s) {
        return s == null ? "" : s.strip();
    }
}
