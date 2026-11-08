package com.codewalnut.bd.reporting;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * Account fit score, 0 to 100, with the reasons behind it (FR-ACC-04). Transparent rules on data the
 * application already holds, so the team works the best accounts first.
 */
@Component
public class FitScorer {

    public record Fit(int score, List<String> reasons) {}

    /** The inputs for one account; kept separate so the rules can be unit tested. */
    record Inputs(String headcountBand, List<String> techStack, String industry, int buyingSignals,
                  boolean decisionContact, boolean anyContact, boolean positiveEngagement, boolean anyEngagement) {}

    private static final Set<String> DECISION_PERSONAS = Set.of("CENTRE_HEAD", "ENGINEERING_LEADER", "PRODUCT_LEADER");

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final FitProperties weights;

    FitScorer(JdbcClient jdbc, JsonMapper json, FitProperties weights) {
        this.jdbc = jdbc;
        this.json = json;
        this.weights = weights;
    }

    public Map<Long, Fit> score(Collection<Long> accountIds) {
        Map<Long, Fit> fits = new HashMap<>();
        if (accountIds.isEmpty()) {
            return fits;
        }
        Set<String> ourSkills = new HashSet<>();
        jdbc.sql("SELECT DISTINCT LOWER(skill) FROM engineer_skill").query(String.class).list().forEach(ourSkills::add);
        Set<String> clientIndustries = new HashSet<>();
        jdbc.sql("SELECT DISTINCT LOWER(industry) FROM account WHERE relationship = 'CLIENT' AND industry IS NOT NULL")
            .query(String.class).list().forEach(clientIndustries::add);

        Map<Long, Integer> signals = new HashMap<>();
        jdbc.sql("""
                SELECT r.account_id, COALESCE(JSON_LENGTH(r.summary, '$.buyingSignals'), 0)
                FROM account_research r
                JOIN (SELECT account_id, MAX(id) AS id FROM account_research WHERE status = 'DONE' GROUP BY account_id) l
                  ON l.id = r.id
                WHERE r.account_id IN (:ids)
                """).param("ids", accountIds)
            .query((rs, n) -> signals.put(rs.getLong(1), rs.getInt(2))).list();

        Map<Long, Set<String>> personas = new HashMap<>();
        jdbc.sql("SELECT account_id, persona FROM contact WHERE account_id IN (:ids) AND NOT opted_out")
            .param("ids", accountIds)
            .query((rs, n) -> personas.computeIfAbsent(rs.getLong(1), k -> new HashSet<>()).add(rs.getString(2))).list();

        Map<Long, Boolean> engagement = new HashMap<>();
        jdbc.sql("""
                SELECT account_id, MAX(outcome IN ('REPLIED', 'MEETING_BOOKED')) FROM activity
                WHERE account_id IN (:ids) AND occurred_on >= CURRENT_DATE - INTERVAL 90 DAY GROUP BY account_id
                """).param("ids", accountIds)
            .query((rs, n) -> engagement.put(rs.getLong(1), rs.getBoolean(2))).list();

        jdbc.sql("SELECT id, headcount_band, tech_stack, industry FROM account WHERE id IN (:ids)")
            .param("ids", accountIds)
            .query((rs, n) -> {
                long id = rs.getLong(1);
                List<String> tech = rs.getString(3) == null ? List.of() : List.of(json.readValue(rs.getString(3), String[].class));
                Set<String> p = personas.getOrDefault(id, Set.of());
                Inputs in = new Inputs(rs.getString(2), tech, rs.getString(4), signals.getOrDefault(id, 0),
                    p.stream().anyMatch(DECISION_PERSONAS::contains), !p.isEmpty(),
                    engagement.getOrDefault(id, false), engagement.containsKey(id));
                fits.put(id, rate(in, ourSkills, clientIndustries, weights));
                return null;
            }).list();
        return fits;
    }

    static Fit rate(Inputs in, Set<String> ourSkills, Set<String> clientIndustries, FitProperties w) {
        int score = 0;
        List<String> reasons = new ArrayList<>();

        if (in.headcountBand() != null && w.sizeByBand().containsKey(in.headcountBand())) {
            int pts = w.sizeByBand().get(in.headcountBand());
            score += pts;
            reasons.add("Size " + in.headcountBand() + " (+" + pts + ")");
        } else {
            score += w.sizeUnknown();
            reasons.add("Size not recorded (+" + w.sizeUnknown() + ")");
        }

        List<String> matches = in.techStack().stream().filter(t -> ourSkills.contains(t.toLowerCase(Locale.ROOT))).toList();
        if (!matches.isEmpty()) {
            int pts = Math.min(w.techMax(), matches.size() * w.perTechMatch());
            score += pts;
            reasons.add("Uses " + String.join(", ", matches) + ", which our engineers know (+" + pts + ")");
        } else if (in.techStack().isEmpty()) {
            reasons.add("Tech stack not recorded");
        }

        if (in.buyingSignals() > 0) {
            int pts = Math.min(w.signalsMax(), in.buyingSignals() * w.perBuyingSignal());
            score += pts;
            reasons.add(in.buyingSignals() + " buying signal" + (in.buyingSignals() == 1 ? "" : "s") + " in research (+" + pts + ")");
        } else {
            reasons.add("No research yet, or no buying signals found");
        }

        if (in.decisionContact()) {
            score += w.decisionContact();
            reasons.add("A decision maker is in our contacts (+" + w.decisionContact() + ")");
        } else if (in.anyContact()) {
            score += w.anyContact();
            reasons.add("We have contacts, but no decision maker yet (+" + w.anyContact() + ")");
        } else {
            reasons.add("No contacts yet");
        }

        if (in.positiveEngagement()) {
            score += w.positiveEngagement();
            reasons.add("Replied or met us in the last 90 days (+" + w.positiveEngagement() + ")");
        } else if (in.anyEngagement()) {
            score += w.anyEngagement();
            reasons.add("Contacted in the last 90 days, no reply yet (+" + w.anyEngagement() + ")");
        }

        if (in.industry() != null && clientIndustries.contains(in.industry().toLowerCase(Locale.ROOT))) {
            score += w.industryMatch();
            reasons.add("We already have a client in " + in.industry() + " (+" + w.industryMatch() + ")");
        }
        return new Fit(Math.min(100, score), reasons);
    }
}
