package com.codewalnut.bd.assist;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

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

/** Claude reads the pasted profile and returns the fields as structured output. */
class ClaudeProfileReader implements ProfileReader {

    static final String MODEL = "claude-opus-5-5";

    private static final String INSTRUCTIONS = """
        You help CodeWalnut's business development team add one person to their contacts. The user has \
        pasted what they can see on one LinkedIn profile: copied page text, a screenshot, or both, plus \
        the profile address. Fill in the fields from what is shown. Use only information visible in what \
        was pasted; never guess or add facts from elsewhere. Use an empty string for anything not shown.

        - fullName: the person's name as written, without pronouns, degrees or emoji.
        - title: their current job title (the headline or current position), not past roles.
        - company: their current employer.
        - city: the city they are based in, without state or country (for example "Bengaluru"; write \
        Bengaluru for Bangalore and Greater Bengaluru Area).
        - persona: exactly one of the codes listed below, for their role in buying engineering services:
          CENTRE_HEAD: heads or leads the India centre or site (site leader, managing director, centre head).
          ENGINEERING_LEADER: directs engineering (VP, director, head or senior manager of engineering, CTO).
          PRODUCT_LEADER: leads product management.
          TALENT_ACQUISITION: HR, talent acquisition or recruitment leaders, HR business partners.
          VENDOR_MANAGEMENT: procurement, sourcing or vendor management.
          OTHER: anyone else.
        - personaReason: one short sentence on why that persona fits.
        - notes: up to two short facts useful for outreach that are shown, such as mutual connections or \
        the time in their current role. Empty if none.
        """;

    /** The shape Claude must return (structured outputs). */
    record ProfileFields(
        @JsonPropertyDescription("Full name") String fullName,
        @JsonPropertyDescription("Current job title") String title,
        @JsonPropertyDescription("Current employer") String company,
        @JsonPropertyDescription("City, for example Bengaluru") String city,
        @JsonPropertyDescription("One persona code from the list") String persona,
        @JsonPropertyDescription("Why this persona fits") String personaReason,
        @JsonPropertyDescription("Short outreach notes, or empty") String notes) {}

    private final AnthropicClient client;

    ClaudeProfileReader(AnthropicClient client) {
        this.client = client;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public Suggestion read(Request request) {
        List<ContentBlockParam> content = new ArrayList<>();
        if (request.image() != null && request.image().length > 0) {
            content.add(ContentBlockParam.ofImage(Base64ImageSource.builder()
                .mediaType(Base64ImageSource.MediaType.of(request.imageMediaType()))
                .data(Base64.getEncoder().encodeToString(request.image()))
                .build()));
        }
        StringBuilder text = new StringBuilder(INSTRUCTIONS)
            .append("\nAllowed persona codes: ").append(String.join(", ", request.personaCodes())).append('\n');
        if (request.linkedinUrl() != null && !request.linkedinUrl().isBlank()) {
            text.append("\nProfile address: ").append(request.linkedinUrl().strip()).append('\n');
        }
        if (request.text() != null && !request.text().isBlank()) {
            text.append("\n<profile_text>\n").append(request.text().strip()).append("\n</profile_text>\n");
        }
        content.add(ContentBlockParam.ofText(TextBlockParam.builder().text(text.toString()).build()));

        StructuredMessageCreateParams<ProfileFields> params = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(16000L)
            .outputConfig(ProfileFields.class)
            .addUserMessageOfContentBlockParams(content)
            // If a safety classifier declines, the API retries on a suitable fallback model instead of stopping.
            .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .build();

        StructuredMessage<ProfileFields> response;
        try {
            response = client.messages().create(params);
        } catch (AnthropicServiceException e) {
            throw new RuleViolationException(List.of(ClaudeErrors.explain(e) + " Meanwhile, fill in the fields by hand."));
        }
        if (response.stopReason().map(StopReason.REFUSAL::equals).orElse(false)) {
            throw new RuleViolationException(List.of("The profile could not be read. Fill in the fields by hand."));
        }
        ProfileFields fields = response.content().stream()
            .flatMap(block -> block.text().stream())
            .map(typed -> typed.text())
            .findFirst()
            .orElseThrow(() -> new RuleViolationException(List.of("No profile details came back. Fill in the fields by hand.")));
        String persona = fields.persona() == null ? "" : fields.persona().strip().toUpperCase();
        if (!request.personaCodes().contains(persona)) {
            persona = request.personaCodes().contains("OTHER") ? "OTHER" : "";
        }
        return new Suggestion(clean(fields.fullName()), clean(fields.title()), clean(fields.company()),
            clean(fields.city()), persona, clean(fields.personaReason()), clean(fields.notes()));
    }

    private static String clean(String s) {
        return s == null ? "" : s.strip();
    }
}
