package com.codewalnut.bd.assist;

import java.util.List;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.WebSearchTool20260209;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/** Web search for GCCs, then a structured list. Same two-step pattern as account research (ADR-0010). */
class ClaudeGccFinder implements GccFinder {

    static final String MODEL = "claude-opus-5-5";
    private static final int MAX_CONTINUATIONS = 3;
    private static final int MAX_KNOWN_IN_PROMPT = 400;

    private final AnthropicClient client;

    ClaudeGccFinder(AnthropicClient client) {
        this.client = client;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public Result find(String city, String industry, List<String> knownCompanies) {
        String notes = search(city, industry, knownCompanies);
        return new Result(structure(city, notes), MODEL);
    }

    private String search(String city, String industry, List<String> known) {
        String scope = industry == null || industry.isBlank() ? "any industry" : "the " + industry + " industry";
        String exclude = known.isEmpty() ? "none" : String.join("; ", known.subList(0, Math.min(known.size(), MAX_KNOWN_IN_PROMPT)));
        String prompt = """
            Find Global Capability Centres (GCCs, also called captive centres, global in-house centres, or \
            technology and innovation centres of multinational companies) in %s, India, in %s.

            Find up to 25 companies that run their own centre in %s. Leave out IT services and outsourcing \
            firms (such as TCS, Infosys, Wipro, Accenture) and Indian companies without a foreign parent. Leave \
            out these companies, which we already have: %s.

            For each company give: company name, parent company, parent headquarters country, the parent's main \
            website domain, industry, one line on what the %s centre does, and the web address that shows the \
            centre exists in %s. Only list companies you found a source for in your searches. Write one line \
            per company in this form:
            Company | Parent | HQ country | domain | industry | what the centre does | source address
            """.formatted(city, scope, city, exclude, city, city);

        MessageCreateParams.Builder params = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(16000L)
            .addTool(WebSearchTool20260209.builder().maxUses(10L).build())
            .addUserMessage(prompt)
            .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        StringBuilder text = new StringBuilder();
        Message response = call(params.build());
        for (int i = 0; ; i++) {
            for (ContentBlock block : response.content()) {
                block.text().ifPresent(t -> text.append(t.text()));
            }
            StopReason stop = response.stopReason().orElse(null);
            if (StopReason.REFUSAL.equals(stop)) {
                throw new ResearchException("The search was declined. Try a different city or industry.");
            }
            if (StopReason.PAUSE_TURN.equals(stop) && i < MAX_CONTINUATIONS) {
                params.addMessage(response);
                response = call(params.build());
                continue;
            }
            break;
        }
        if (text.isEmpty()) {
            throw new ResearchException("The search returned nothing. Try again later.");
        }
        return text.toString();
    }

    record CandidateOut(
        @JsonPropertyDescription("Name of the company's centre or the company") String company,
        @JsonPropertyDescription("Parent company") String parentCompany,
        @JsonPropertyDescription("Parent headquarters country") String hqCountry,
        @JsonPropertyDescription("Parent's main website domain only, for example example.com") String websiteDomain,
        @JsonPropertyDescription("Industry") String industry,
        @JsonPropertyDescription("What the centre does, one line") String description,
        @JsonPropertyDescription("Web address that shows the centre exists") String source) {}

    record CandidateList(@JsonPropertyDescription("The companies from the notes") List<CandidateOut> companies) {}

    private List<Candidate> structure(String city, String notes) {
        StructuredMessageCreateParams<CandidateList> params = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(16000L)
            .outputConfig(CandidateList.class)
            .addUserMessage("""
                Turn these research notes into a list. Include only companies that have a source web address in \
                the notes; copy values exactly and use an empty string for anything missing. Do not add companies.

                <notes>
                %s
                </notes>
                """.formatted(notes))
            .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .build();
        StructuredMessage<CandidateList> response;
        try {
            response = client.messages().create(params);
        } catch (AnthropicServiceException e) {
            throw new ResearchException(ClaudeErrors.explain(e));
        }
        if (response.stopReason().map(StopReason.REFUSAL::equals).orElse(false)) {
            throw new ResearchException("The list could not be prepared. Try again.");
        }
        CandidateList list = response.content().stream().flatMap(b -> b.text().stream()).map(t -> t.text()).findFirst()
            .orElseThrow(() -> new ResearchException("No companies came back. Try again."));
        return list.companies() == null ? List.of() : list.companies().stream()
            .filter(c -> c.source() != null && c.source().startsWith("http"))
            .map(c -> new Candidate(c.company(), c.parentCompany(), c.hqCountry(), city, c.websiteDomain(),
                c.industry(), c.description(), c.source()))
            .toList();
    }

    private Message call(MessageCreateParams params) {
        try {
            return client.messages().create(params);
        } catch (AnthropicServiceException e) {
            throw new ResearchException(ClaudeErrors.explain(e));
        }
    }
}
