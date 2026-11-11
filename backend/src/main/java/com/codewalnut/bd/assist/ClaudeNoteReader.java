package com.codewalnut.bd.assist;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
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
        an event, LINKEDIN_CONNECTION if a connection request was sent or accepted.
        - occurredOn: the date of the latest message or touch as YYYY-MM-DD. Today is given below; resolve \
        "yesterday" or weekday names against it. Empty if not shown.
        - summary: what happened, in one or two sentences, written for the record.
        - outcome: exactly one of the outcome codes listed below. REPLIED if they answered, \
        MEETING_BOOKED if a meeting or call was agreed, NOT_INTERESTED if they declined, NO_REPLY if we \
        reached out and they have not answered, REFERRED_ON if they pointed us to someone else.
        - nextStep: what the team member should do next, as a short instruction. Empty if nothing is due.
        - nextStepDue: the date for the next step as YYYY-MM-DD, resolved against today. If a next step \
        has no date, use three working days after today.
        - newConnection: true if the material shows an accepted LinkedIn connection or a first meeting \
        with this person. A LinkedIn invitation that was sent but not yet accepted ("Invitation sent", \
        "Pending") is NOT a new connection: use channel LINKEDIN_CONNECTION, outcome NO_REPLY, a next step \
        to follow up once they accept, and say in the reply that they can add the connection when it is \
        accepted.
        - photoImage, photoX, photoY, photoWidth, photoHeight: if a screenshot shows the other person's own \
        profile photo (a picture of them, usually round, next to or above their name), give the 0-based \
        index of that screenshot in the order given and the photo's bounding box as fractions of that \
        screenshot's width and height (photoX and photoY are the top-left corner). Use the largest clear \
        photo of them. If no photo of this person is shown, or only initials, a logo or a placeholder \
        silhouette, set photoImage to -1 and the box to 0. Never pick a photo of the team member or anyone \
        else.

        The material may instead be mainly about a conference or event, or about a leader interview. Set \
        "about" to TOUCH (a conversation or connection with a person), EVENT (a conference, meetup, webinar \
        or roundtable: an invitation, a registration, an agenda, or a note about attending) or INTERVIEW \
        (interviewing a GCC or engineering leader for CodeWalnut's blog, podcast, video or a LinkedIn article).
        - event: fill it when an event is mentioned, whatever "about" is (for example a person met at an \
        event). name as the event calls itself; kind is one of the event kind codes; startsOn and endsOn \
        as YYYY-MM-DD (endsOn empty for one day); online true for virtual events; url only if shown; \
        ourRole is one of the event role codes: WATCHING if we are only considering it, SIGNED_UP if \
        registered, ATTENDING if going or went, PRESENTING if we speak, STALL if we have a booth, ORGANISING \
        if we run it; notes in one or two sentences. Leave name empty if no event is mentioned.
        - interview: fill it when the material is about such an interview. leaderName, leaderTitle and \
        company are the leader's; topic under 12 words; format is one of the interview format codes; status \
        is one of the interview status codes (IDEA not yet asked, ASKED, AGREED, BOOKED with a date, \
        RECORDED, PUBLISHED with a link, DECLINED); on is the date of that stage as YYYY-MM-DD (the booked \
        date for BOOKED); publishedUrl only if published. Leave leaderName empty if there is no interview. \
        When about is INTERVIEW also fill the person fields with the leader.

        "about" can also be ACCOUNT (a company or GCC centre we might sell to: a news item, a careers page, \
        a note that a centre is opening or growing) or OPPORTUNITY (a client need that could become \
        business: a request for engineers, a project, a proposal, a deal moving forward or lost).
        - account: fill it when a company or centre is described, whatever "about" is. name as the centre \
        is known (for example "Example Corp India Technology Centre"), parentCompany the global company, \
        parentHqCountry its home country, city of the centre, websiteDomain the company's main web domain \
        only if shown or certain (example.com, no www), industry in a few words, yearEstablished, \
        employeeCount and openRoles only if stated (0 when not), notes with the key facts in one or two \
        sentences. Leave name empty if no company is described.
        - opportunity: fill it when there is a concrete need or deal. name a short title (for example \
        "4 React engineers for payments"), type STAFF_AUGMENTATION for engineers joining their team or \
        PROJECT for work we deliver, valueInr only if a value in rupees is stated or can be computed from \
        stated rates and headcount (0 when not), expectedCloseDate and targetStartDate as YYYY-MM-DD if \
        stated, needSummary in one or two sentences, stage one of the stage codes if the material says how \
        far it got, else empty. Leave name empty if there is no need or deal. When about is OPPORTUNITY \
        and a person is named, fill the person fields too.
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
        @JsonPropertyDescription("An accepted connection or first meeting") boolean newConnection,
        @JsonPropertyDescription("0-based screenshot index showing their profile photo, or -1") int photoImage,
        @JsonPropertyDescription("Photo box left edge, fraction of image width") double photoX,
        @JsonPropertyDescription("Photo box top edge, fraction of image height") double photoY,
        @JsonPropertyDescription("Photo box width, fraction of image width") double photoWidth,
        @JsonPropertyDescription("Photo box height, fraction of image height") double photoHeight,
        @JsonPropertyDescription("TOUCH, EVENT or INTERVIEW") String about,
        @JsonPropertyDescription("An event mentioned, or an empty name") EventFields event,
        @JsonPropertyDescription("A leader interview, or an empty leaderName") InterviewFields interview,
        @JsonPropertyDescription("A company or centre, or an empty name") AccountFields account,
        @JsonPropertyDescription("A need or deal, or an empty name") OpportunityFields opportunity) {}

    record AccountFields(
        @JsonPropertyDescription("The centre's name, or empty") String name,
        @JsonPropertyDescription("The global parent company") String parentCompany,
        @JsonPropertyDescription("Parent's home country") String parentHqCountry,
        @JsonPropertyDescription("City of the centre") String city,
        @JsonPropertyDescription("Main web domain, e.g. example.com") String websiteDomain,
        @JsonPropertyDescription("Industry in a few words") String industry,
        @JsonPropertyDescription("Year formed, or 0") int yearEstablished,
        @JsonPropertyDescription("Employees at the centre, or 0") int employeeCount,
        @JsonPropertyDescription("Open engineering roles, or 0") int openRoles,
        @JsonPropertyDescription("Key facts, one or two sentences") String notes) {}

    record OpportunityFields(
        @JsonPropertyDescription("Short title, or empty") String name,
        @JsonPropertyDescription("STAFF_AUGMENTATION or PROJECT") String type,
        @JsonPropertyDescription("Value in rupees, or 0") long valueInr,
        @JsonPropertyDescription("Expected close, YYYY-MM-DD") String expectedCloseDate,
        @JsonPropertyDescription("The need, one or two sentences") String needSummary,
        @JsonPropertyDescription("Target start, YYYY-MM-DD") String targetStartDate,
        @JsonPropertyDescription("One stage code, or empty") String stage) {}

    record EventFields(
        @JsonPropertyDescription("Event name, or empty") String name,
        @JsonPropertyDescription("One event kind code") String kind,
        @JsonPropertyDescription("First day, YYYY-MM-DD") String startsOn,
        @JsonPropertyDescription("Last day if more than one, YYYY-MM-DD") String endsOn,
        @JsonPropertyDescription("City, empty if online") String city,
        @JsonPropertyDescription("True if virtual") boolean online,
        @JsonPropertyDescription("Event page address if shown") String url,
        @JsonPropertyDescription("Who runs it") String organiser,
        @JsonPropertyDescription("One event role code for CodeWalnut") String ourRole,
        @JsonPropertyDescription("One or two sentences") String notes) {}

    record InterviewFields(
        @JsonPropertyDescription("The leader's full name, or empty") String leaderName,
        @JsonPropertyDescription("The leader's title") String leaderTitle,
        @JsonPropertyDescription("The leader's company") String company,
        @JsonPropertyDescription("Topic, under 12 words") String topic,
        @JsonPropertyDescription("One interview format code") String format,
        @JsonPropertyDescription("One interview status code") String status,
        @JsonPropertyDescription("Date of that stage, YYYY-MM-DD") String on,
        @JsonPropertyDescription("Where it is published, if it is") String publishedUrl,
        @JsonPropertyDescription("One or two sentences") String notes) {}

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
        StructuredMessageCreateParams<NoteFields> params = params(request);
        NoteFields f;
        try {
            StructuredMessage<NoteFields> response = client.messages().create(params);
            if (response.stopReason().map(StopReason.REFUSAL::equals).orElse(false)) {
                throw new RuleViolationException(List.of("That could not be read. Log it by hand on the Conversations page."));
            }
            f = response.content().stream().flatMap(b -> b.text().stream()).map(t -> t.text()).findFirst()
                .orElseThrow(() -> new RuleViolationException(List.of("Nothing came back. Try again, or log it by hand.")));
        } catch (AnthropicException e) {
            throw new RuleViolationException(List.of(ClaudeErrors.explain(e) + " Meanwhile, log it by hand."));
        }
        return reading(f, request);
    }

    /** The request to Claude. Building it checks the output schema, so tests can call it without the API. */
    static StructuredMessageCreateParams<NoteFields> params(Request request) {
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
            .append("\nOutcome codes: ").append(String.join(", ", request.outcomes()))
            .append("\nEvent kind codes: ").append(String.join(", ", EVENT_KINDS))
            .append("\nEvent role codes: ").append(String.join(", ", EVENT_ROLES))
            .append("\nInterview format codes: ").append(String.join(", ", INTERVIEW_FORMATS))
            .append("\nInterview status codes: ").append(String.join(", ", INTERVIEW_STATUSES))
            .append("\nOpportunity stage codes, in order: ").append(String.join(", ", request.stages())).append('\n');
        if (request.text() != null && !request.text().isBlank()) {
            text.append("\n<pasted_by_team_member>\n").append(request.text().strip()).append("\n</pasted_by_team_member>\n");
        }
        content.add(ContentBlockParam.ofText(TextBlockParam.builder().text(text.toString()).build()));

        return MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(16000L)
            .outputConfig(NoteFields.class)
            .addUserMessageOfContentBlockParams(content)
            .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .build();
    }

    /** Claude's fields as a Reading: codes kept to the allowed lists, empty sections dropped. */
    static Reading reading(NoteFields f, Request request) {
        Event event = null;
        if (f.event() != null && !c(f.event().name()).isEmpty()) {
            EventFields e = f.event();
            event = new Event(c(e.name()), code(e.kind(), EVENT_KINDS, "CONFERENCE"), c(e.startsOn()), c(e.endsOn()),
                c(e.city()), e.online(), c(e.url()), c(e.organiser()), code(e.ourRole(), EVENT_ROLES, "WATCHING"), c(e.notes()));
        }
        Interview interview = null;
        if (f.interview() != null && !c(f.interview().leaderName()).isEmpty()) {
            InterviewFields i = f.interview();
            interview = new Interview(c(i.leaderName()), c(i.leaderTitle()), c(i.company()), c(i.topic()),
                code(i.format(), INTERVIEW_FORMATS, "BLOG"), code(i.status(), INTERVIEW_STATUSES, "IDEA"), c(i.on()),
                c(i.publishedUrl()), c(i.notes()));
        }
        Account account = null;
        if (f.account() != null && !c(f.account().name()).isEmpty()) {
            AccountFields a = f.account();
            account = new Account(c(a.name()), c(a.parentCompany()), c(a.parentHqCountry()), c(a.city()),
                c(a.websiteDomain()).toLowerCase(Locale.ROOT), c(a.industry()), positive(a.yearEstablished()),
                positive(a.employeeCount()), positive(a.openRoles()), c(a.notes()));
        }
        Opportunity opportunity = null;
        if (f.opportunity() != null && !c(f.opportunity().name()).isEmpty()) {
            OpportunityFields o = f.opportunity();
            String stage = c(o.stage()).toUpperCase(Locale.ROOT);
            opportunity = new Opportunity(c(o.name()), code(o.type(), OPPORTUNITY_TYPES, "STAFF_AUGMENTATION"),
                o.valueInr() > 0 ? o.valueInr() : null, c(o.expectedCloseDate()), c(o.needSummary()),
                c(o.targetStartDate()), request.stages().contains(stage) ? stage : "");
        }
        String about = code(f.about(), ABOUT, "TOUCH");
        if ("EVENT".equals(about) && event == null || "INTERVIEW".equals(about) && interview == null
            || "ACCOUNT".equals(about) && account == null || "OPPORTUNITY".equals(about) && opportunity == null) {
            about = "TOUCH";
        }
        return new Reading(c(f.reply()), c(f.fullName()), c(f.title()), c(f.company()), c(f.city()), c(f.linkedinUrl()),
            c(f.topic()), code(f.channel(), request.channels(), "LINKEDIN_MESSAGE"), c(f.occurredOn()), c(f.summary()),
            code(f.outcome(), request.outcomes(), "DONE"), c(f.nextStep()), c(f.nextStepDue()), f.newConnection(),
            photo(f, request.images().size()), about, event, interview, account, opportunity);
    }

    /** The photo box, if it names a screenshot that was sent and is a sensible size; otherwise null. */
    static Photo photo(NoteFields f, int images) {
        if (f.photoImage() < 0 || f.photoImage() >= images) {
            return null;
        }
        double x = clamp(f.photoX());
        double y = clamp(f.photoY());
        double w = Math.min(clamp(f.photoWidth()), 1 - x);
        double h = Math.min(clamp(f.photoHeight()), 1 - y);
        if (w < 0.01 || h < 0.01 || w > 0.9 || h > 0.9) {
            return null;
        }
        return new Photo(f.photoImage(), x, y, w, h);
    }

    private static Integer positive(int v) {
        return v > 0 ? v : null;
    }

    private static double clamp(double v) {
        return Double.isFinite(v) ? Math.max(0, Math.min(1, v)) : 0;
    }

    private static String c(String s) {
        return s == null ? "" : s.strip();
    }

    private static String code(String s, List<String> allowed, String fallback) {
        String v = c(s).toUpperCase(Locale.ROOT);
        return allowed.contains(v) ? v : fallback;
    }
}
