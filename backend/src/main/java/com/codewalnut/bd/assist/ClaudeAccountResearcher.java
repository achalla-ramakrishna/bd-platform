package com.codewalnut.bd.assist;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

import com.codewalnut.bd.reporting.ResearchContextQueries;

/**
 * Two Claude calls: (1) web research on the account's India centre with the web search tool, written
 * up as sourced notes; (2) a structured summary combining those notes with the application's own data.
 */
class ClaudeAccountResearcher implements AccountResearcher {

    static final String MODEL = "claude-opus-5-5";
    private static final int MAX_CONTINUATIONS = 3;

    /** What CodeWalnut sells (business requirements and the 2027-2028 plan). */
    static final String OFFERS = """
        CodeWalnut offers:
        - AI-fluent senior engineers as staff augmentation (contractors in the client's own team): React and \
        TypeScript front end, Java and Spring, Node, cloud, data and QA automation. Billed per engineer per month.
        - AI pods: 3 to 5 senior engineers plus AI tooling, sold per sprint or per outcome.
        - AI agent and GenAI application builds.
        - AI-assisted modernisation of legacy code.
        - Data platforms for AI.
        - AI evaluation and governance.
        - Outsourced product development projects, fixed price or time and materials.
        Strengths: senior engineers deployed within 15 working days; flexible short engagements.
        """;

    private final AnthropicClient client;

    ClaudeAccountResearcher(AnthropicClient client) {
        this.client = client;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public Result research(ResearchContextQueries.Context ctx) {
        Map<String, Source> sources = new LinkedHashMap<>();
        String notes = webResearch(ctx, sources);
        Summary summary = summarise(ctx, notes, sources);
        return new Result(summary, new ArrayList<>(sources.values()), MODEL);
    }

    // ---- Call 1: web research ------------------------------------------------------------------

    private String webResearch(ResearchContextQueries.Context ctx, Map<String, Source> sources) {
        var a = ctx.account();
        String prompt = """
            Research the India operations of %s (website %s), focusing on its centre in %s, for a business \
            development team that sells engineering services to Global Capability Centres (GCCs).

            Find, preferring information from the last 18 months:
            1. What the India or %s centre does, its size, when it opened, and who leads it.
            2. Hiring: the engineering, data and AI roles it is hiring for, and the skills and technology named.
            3. Its technology stack and platforms.
            4. Recent news: expansions, new centres, AI or innovation centres of excellence, leadership changes, \
            layoffs or cost programmes.
            5. Any IT services vendors or partners it is known to work with.

            Write your findings as short bullet notes. End every bullet with the web address it came from, in \
            square brackets. If you cannot find something, say so; do not guess. Keep it under 600 words.
            """.formatted(a.parentCompany(), a.domain(), a.city(), a.city());

        MessageCreateParams.Builder params = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(16000L)
            .addTool(WebSearchTool20260209.builder().maxUses(8L).build())
            .addUserMessage(prompt)
            .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));

        StringBuilder text = new StringBuilder();
        Message response = call(params.build());
        for (int i = 0; ; i++) {
            collect(response, text, sources);
            StopReason stop = response.stopReason().orElse(null);
            if (StopReason.REFUSAL.equals(stop)) {
                throw new ResearchException("The research was declined. Try again, or research this account by hand.");
            }
            // The server-side search loop paused: send the turn back and it resumes where it left off.
            if (StopReason.PAUSE_TURN.equals(stop) && i < MAX_CONTINUATIONS) {
                params.addMessage(response);
                response = call(params.build());
                continue;
            }
            break;
        }
        if (text.isEmpty()) {
            throw new ResearchException("The web research returned nothing. Try again later.");
        }
        return text.toString();
    }

    private static void collect(Message response, StringBuilder text, Map<String, Source> sources) {
        for (ContentBlock block : response.content()) {
            block.webSearchToolResult().ifPresent(r -> r.content().resultBlocks().ifPresent(results ->
                results.forEach(s -> sources.putIfAbsent(s.url(), new Source(s.url(), s.title())))));
            block.text().ifPresent(t -> {
                text.append(t.text());
                t.citations().ifPresent(cs -> cs.forEach(c -> c.webSearchResultLocation().ifPresent(loc ->
                    sources.putIfAbsent(loc.url(), new Source(loc.url(), loc.title().orElse(loc.url()))))));
            });
        }
    }

    private Message call(MessageCreateParams params) {
        try {
            return client.messages().create(params);
        } catch (AnthropicServiceException e) {
            throw new ResearchException(ClaudeErrors.explain(e));
        }
    }

    // ---- Call 2: structured summary ------------------------------------------------------------

    record FactOut(@JsonPropertyDescription("One fact") String statement,
                   @JsonPropertyDescription("Web address from the notes, or 'Our records'") String source) {}

    record OfferOut(@JsonPropertyDescription("A CodeWalnut offer from the list") String offer,
                    @JsonPropertyDescription("Why it fits this account, citing the evidence") String why) {}

    record TargetOut(@JsonPropertyDescription("Persona or role to approach") String persona,
                     @JsonPropertyDescription("Why this person matters for the sale") String why,
                     @JsonPropertyDescription("Name of a matching contact already in our records, or empty") String knownContact) {}

    record SummaryOut(
        @JsonPropertyDescription("Two or three sentences on what the centre does") String centreOverview,
        @JsonPropertyDescription("Key facts about the centre, each with its source") List<FactOut> facts,
        @JsonPropertyDescription("Recent signals that they may buy engineering help, each with its source") List<FactOut> buyingSignals,
        @JsonPropertyDescription("Their likely engineering needs") List<String> likelyNeeds,
        @JsonPropertyDescription("Two or three CodeWalnut offers that fit best") List<OfferOut> recommendedOffers,
        @JsonPropertyDescription("Who to approach, in order") List<TargetOut> whoToContact,
        @JsonPropertyDescription("LinkedIn connection note, at most 280 characters") String openingMessage,
        @JsonPropertyDescription("Three to five points for a first call") List<String> talkingPoints,
        @JsonPropertyDescription("What is uncertain or should be checked") List<String> cautions,
        @JsonPropertyDescription("How well the account fits CodeWalnut: STRONG, MEDIUM or WEAK, as defined in the instructions")
        FitRating fitRating) {}

    private Summary summarise(ResearchContextQueries.Context ctx, String notes, Map<String, Source> sources) {
        String prompt = """
            You are preparing an account brief for CodeWalnut's business development team. Use only the web \
            research notes and our records below. Do not add facts from anywhere else. Every fact and buying \
            signal must carry the web address it came from in the notes, or "Our records" for our own data. \
            If the notes are thin, say so in cautions instead of filling gaps.

            Rate fitRating from the notes: STRONG when the centre builds software, data or AI products and is \
            hiring or growing engineering teams; MEDIUM when it does some software work but the need for outside \
            engineers is unclear; WEAK when its work is mainly hardware, chip, specialist or non-software, or it \
            is cutting staff. When the notes are too thin to tell, choose MEDIUM and say why in cautions.

            %s
            Client names: you may mention only the clients listed as nameable. Describe any other client by \
            industry only (for example "a global logistics company").

            The opening message is a LinkedIn connection note to the first person in whoToContact: at most 280 \
            characters, specific to something in the notes, no flattery, no attachments or links, and it must \
            not claim anything about CodeWalnut that is not stated above. A person will edit it before sending.

            <our_records>
            %s
            </our_records>

            <web_research_notes>
            %s
            </web_research_notes>
            """.formatted(OFFERS, records(ctx), notes);

        StructuredMessageCreateParams<SummaryOut> params = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(16000L)
            .outputConfig(SummaryOut.class)
            .addUserMessage(prompt)
            .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .build();
        StructuredMessage<SummaryOut> response;
        try {
            response = client.messages().create(params);
        } catch (AnthropicServiceException e) {
            throw new ResearchException(ClaudeErrors.explain(e));
        }
        if (response.stopReason().map(StopReason.REFUSAL::equals).orElse(false)) {
            throw new ResearchException("The summary was declined. Try again, or research this account by hand.");
        }
        SummaryOut out = response.content().stream().flatMap(b -> b.text().stream()).map(t -> t.text()).findFirst()
            .orElseThrow(() -> new ResearchException("No summary came back. Try again."));
        return new Summary(out.centreOverview(), facts(out.facts()), facts(out.buyingSignals()), list(out.likelyNeeds()),
            out.recommendedOffers() == null ? List.of()
                : out.recommendedOffers().stream().map(o -> new Offer(o.offer(), o.why())).toList(),
            out.whoToContact() == null ? List.of()
                : out.whoToContact().stream().map(t -> new Target(t.persona(), t.why(), t.knownContact())).toList(),
            out.openingMessage(), list(out.talkingPoints()), list(out.cautions()), out.fitRating());
    }

    private static List<Fact> facts(List<FactOut> in) {
        return in == null ? List.of() : in.stream().map(f -> new Fact(f.statement(), f.source())).toList();
    }

    private static List<String> list(List<String> in) {
        return in == null ? List.of() : in;
    }

    /** Our own data about the account and CodeWalnut, as plain text. No rates or contract values. */
    static String records(ResearchContextQueries.Context ctx) {
        var a = ctx.account();
        StringBuilder s = new StringBuilder();
        s.append("Account: ").append(a.name()).append(" (parent ").append(a.parentCompany());
        if (a.hqCountry() != null) s.append(", headquartered in ").append(a.hqCountry());
        s.append("), ").append(a.city()).append(", ").append(a.domain()).append('\n');
        s.append("Relationship with CodeWalnut: ").append(a.relationship().toLowerCase().replace('_', ' ')).append('\n');
        if (a.industry() != null) s.append("Industry: ").append(a.industry()).append('\n');
        if (a.headcountBand() != null) s.append("Headcount band: ").append(a.headcountBand()).append('\n');
        if (a.techStack() != null) s.append("Known tech stack: ").append(a.techStack()).append('\n');
        if (a.notes() != null) s.append("Our notes: ").append(a.notes()).append('\n');
        s.append("\nContacts we have:\n");
        if (ctx.contacts().isEmpty()) s.append("- none yet\n");
        ctx.contacts().forEach(c -> s.append("- ").append(c.name()).append(", ").append(c.title() == null ? "" : c.title())
            .append(" (").append(c.persona()).append(")\n"));
        s.append("\nRecent activity:\n");
        if (ctx.recentActivities().isEmpty()) s.append("- none yet\n");
        ctx.recentActivities().forEach(x -> s.append("- ").append(x.on()).append(' ').append(x.channel())
            .append(x.contact() == null ? "" : " with " + x.contact()).append(": ").append(x.summary())
            .append(" (").append(x.outcome()).append(")\n"));
        s.append("\nOpportunities:\n");
        if (ctx.opportunities().isEmpty()) s.append("- none yet\n");
        ctx.opportunities().forEach(o -> s.append("- ").append(o.name()).append(" (").append(o.type()).append(", ")
            .append(o.stage()).append(")").append(o.need() == null ? "" : ": " + o.need()).append('\n'));
        if (!ctx.deployedTeam().isEmpty()) {
            s.append("\nCodeWalnut engineers working here now:\n");
            ctx.deployedTeam().forEach(d -> s.append("- ").append(d.count()).append(" x ").append(d.role())
                .append(" (").append(d.seniority().toLowerCase()).append(")\n"));
        }
        s.append("\nNameable CodeWalnut clients: ").append(ctx.nameableClients().isEmpty() ? "none"
            : ctx.nameableClients().stream().map(c -> c.name() + (c.industry() == null ? "" : " (" + c.industry() + ")"))
                .collect(Collectors.joining(", "))).append('\n');
        if (!ctx.unnamedClientIndustries().isEmpty()) {
            s.append("Other clients, by industry only: ").append(String.join(", ", ctx.unnamedClientIndustries())).append('\n');
        }
        s.append("Skills across CodeWalnut engineers: ").append(skills(ctx.allSkills())).append('\n');
        s.append("Skills of engineers available now: ").append(skills(ctx.benchSkills())).append('\n');
        return s.toString();
    }

    private static String skills(List<ResearchContextQueries.SkillCount> list) {
        return list.isEmpty() ? "not recorded"
            : list.stream().map(k -> k.skill() + " (" + k.engineers() + ")").collect(Collectors.joining(", "));
    }
}
