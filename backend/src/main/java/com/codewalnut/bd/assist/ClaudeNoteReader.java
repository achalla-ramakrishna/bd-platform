package com.codewalnut.bd.assist;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import com.codewalnut.bd.shared.RuleViolationException;

/** Claude reads the pasted note or screenshots and returns the touch as structured output (ADR-0013). */
class ClaudeNoteReader implements NoteReader {

    static final String MODEL = "claude-opus-5-5";

    private static final String INSTRUCTIONS = """
        You help CodeWalnut's business development team keep their records up to date. A team member has \
        pasted something about a touch with one person at a company: their own note, an email, or \
        screenshots of a LinkedIn or WhatsApp chat or an email thread. Work out who the touch was with \
        (the person outside CodeWalnut, never the team member), what happened, and the next step.

        Use only what is in the pasted material. Never guess. Use an empty string for anything not shown.
        - reply: one or two plain sentences to the team member saying what you understood, for example \
        "Call with Person One (Engineering Director, Alpha): she's back on Friday and wants a show and tell."
        - fullName, title, company, city, linkedinUrl: the other person. City without state or country \
        (Bengaluru for Bangalore).
        - topic: a short topic for the conversation, under 12 words.
        - channel: exactly one of the channel codes listed below; LINKEDIN_MESSAGE for LinkedIn chats, \
        EMAIL for email, MEETING for an in-person or video meeting, CALL for a phone call, EVENT if met at \
        an event, LINKEDIN_CONNECTION if they accepted or sent a connection request.
        - occurredOn: the date of the latest message or touch as YYYY-MM-DD. Today is given below; resolve \
        "yesterday" or weekday names against it. Empty if not shown.
        - summary: what happened, in one or two sentences, written for the record.
        - outcome: exactly one of the outcome codes listed below. REPLIED if they answered, \
        MEETING_BOOKED if a meeting or call was agreed, NOT_INTERESTED if they declined, NO_REPLY if we \
        reached out and they have not answered, REFERRED_ON if they pointed us to someone else.
        - nextStep: what the team member should do next, as a short instruction. Empty if nothing is due.
        - nextStepDue: the date for the next step as YYYY-MM-DD, resolved against today. If a next step \
        has no date, use three working days after today.
        - newConnection: true if the material shows a new LinkedIn connection or a first meeting with \
        this person.
        """;

    record NoteFields(
        @JsonPropertyDescription("One or two sentences back to the team member") String reply,
        @JsonPropertyDescription("The other person's full name") String fullName,
        @JsonPropertyDescription("Their job title") String title,
        @JsonPropertyDescription("Their company") String company,
        @JsonPropertyDescription("Their city") String city,
        @JsonPropertyDescription("Their LinkedIn profile address if shown") String linkedinUrl,
        @JsonPropertyDescription("Short topic for the conversation") String topic,
        @JsonPropertyDescription("One channel code") String channel,
        @JsonPropertyDescription("Date of the latest touch, YYYY-MM-DD") String occurredOn,
        @JsonPropertyDescription("What happened, for the record") String summary,
        @JsonPropertyDescription("One outcome code") String outcome,
        @JsonPropertyDescription("Next step for the team member") String nextStep,
        @JsonPropertyDescription("Next step date, YYYY-MM-DD") String nextStepDue,
        @JsonPropertyDescription("A new connection or first meeting") boolean newConnection) {}

    private final AnthropicClient client;

    ClaudeNoteReader(AnthropicClient client) {
        this.client = client;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public Reading read(Request request) {
        List<ContentBlockParam> content = new ArrayList<>();
        for (Image image : request.images()) {
            content.add(ContentBlockParam.ofImage(Base64ImageSource.builder()
                .mediaType(Base64ImageSource.MediaType.of(image.mediaType()))
                .data(Base64.getEncoder().encodeToString(image.bytes()))
                .build()));
        }
        String today = request.today().format(DateTimeFormatter.ISO_LOCAL_DATE) + " ("
            + request.today().getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH) + ")";
        StringBuilder text = new StringBuilder(INSTRUCTIONS)
            .append("\nToday: ").append(today)
            .append("\nChannel codes: ").append(String.join(", ", request.channels()))
            .append("\nOutcome codes: ").append(String.join(", ", request.outcomes())).append('\n');
        if (request.text() != null && !request.text().isBlank()) {
            text.append("\n<pasted_by_team_member>\n").append(request.text().strip()).append("\n</pasted_by_team_member>\n");
        }
        content.add(ContentBlockParam.ofText(TextBlockParam.builder().text(text.toString()).build()));

        StructuredMessageCreateParams<NoteFields> params = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(16000L)
            .outputConfig(NoteFields.class)
            .addUserMessageOfContentBlockParams(content)
            .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .build();
        StructuredMessage<NoteFields> response;
        try {
            response = client.messages().create(params);
        } catch (AnthropicServiceException e) {
            throw new RuleViolationException(List.of(ClaudeErrors.explain(e) + " Meanwhile, log it by hand."));
        }
        if (response.stopReason().map(StopReason.REFUSAL::equals).orElse(false)) {
            throw new RuleViolationException(List.of("That could not be read. Log it by hand on the Conversations page."));
        }
        NoteFields f = response.content().stream().flatMap(b -> b.text().stream()).map(t -> t.text()).findFirst()
            .orElseThrow(() -> new RuleViolationException(List.of("Nothing came back. Try again, or log it by hand.")));
        return new Reading(c(f.reply()), c(f.fullName()), c(f.title()), c(f.company()), c(f.city()), c(f.linkedinUrl()),
            c(f.topic()), code(f.channel(), request.channels(), "LINKEDIN_MESSAGE"), c(f.occurredOn()), c(f.summary()),
            code(f.outcome(), request.outcomes(), "DONE"), c(f.nextStep()), c(f.nextStepDue()), f.newConnection());
    }

    private static String c(String s) {
        return s == null ? "" : s.strip();
    }

    private static String code(String s, List<String> allowed, String fallback) {
        String v = c(s).toUpperCase(Locale.ROOT);
        return allowed.contains(v) ? v : fallback;
    }
}
