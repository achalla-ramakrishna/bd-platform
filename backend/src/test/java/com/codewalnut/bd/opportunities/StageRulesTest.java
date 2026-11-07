package com.codewalnut.bd.opportunities;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** BR-BD-07, BR-BD-08, BR-BD-13 and the won-is-final rule. */
class StageRulesTest {

    static final Map<String, StageRules.Stage> STAGES = new LinkedHashMap<>();
    static {
        String[][] rows = {{"LEAD", "10", "OPEN"}, {"QUALIFIED", "20", "OPEN"}, {"DISCOVERY", "30", "OPEN"},
            {"PROPOSAL", "40", "OPEN"}, {"NEGOTIATION", "50", "OPEN"}, {"WON", "60", "WON"}, {"LOST", "70", "LOST"}};
        for (String[] r : rows) {
            STAGES.put(r[0], new StageRules.Stage(r[0], r[0], Integer.parseInt(r[1]), 10, r[2]));
        }
    }

    static final OpportunityRepository.Requirement COMPLETE = new OpportunityRepository.Requirement("Backend engineer",
        List.of("Java"), "SENIOR", 2, "HYBRID", "Bengaluru", LocalDate.parse("2027-01-04"), 6, 100,
        BigDecimal.valueOf(250000), "MONTH");

    static OpportunityRepository.State opp(String type, String stage, Long dm, String need, LocalDate start,
                                           List<OpportunityRepository.Requirement> reqs) {
        return new OpportunityRepository.State(1, 1, type, stage, dm, need, start, null, 0, reqs);
    }

    static List<String> move(OpportunityRepository.State o, String to) {
        return StageRules.check(o, STAGES.get(to), STAGES, null, Set.of("PRICE"));
    }

    @Test
    void leadToQualifiedNeedsDecisionMakerNeedAndStartDate() {
        var problems = move(opp("STAFF_AUGMENTATION", "LEAD", null, null, null, List.of()), "QUALIFIED");
        assertThat(problems).hasSize(3).allMatch(p -> p.contains("BR-BD-07"));
        assertThat(move(opp("STAFF_AUGMENTATION", "LEAD", 5L, "Two Java engineers", LocalDate.now(), List.of()), "QUALIFIED"))
            .isEmpty();
    }

    @Test
    void skippingAheadStillAppliesEarlierChecks() {
        var problems = move(opp("STAFF_AUGMENTATION", "LEAD", null, null, null, List.of()), "PROPOSAL");
        assertThat(problems).anyMatch(p -> p.contains("BR-BD-07")).anyMatch(p -> p.contains("BR-BD-08"));
    }

    @Test
    void staffAugmentationNeedsACompleteRequirementForProposal() {
        var incomplete = new OpportunityRepository.Requirement("Backend engineer", List.of("Java"), "SENIOR", 2,
            "HYBRID", null, LocalDate.parse("2027-01-04"), null, 100, null, null);
        var base = opp("STAFF_AUGMENTATION", "DISCOVERY", 5L, "Need", LocalDate.now(), List.of(incomplete));
        assertThat(move(base, "PROPOSAL")).singleElement().asString().contains("BR-BD-08");
        var ready = opp("STAFF_AUGMENTATION", "DISCOVERY", 5L, "Need", LocalDate.now(), List.of(incomplete, COMPLETE));
        assertThat(move(ready, "PROPOSAL")).isEmpty();
    }

    @Test
    void projectNeedsScopeForProposal() {
        var project = opp("PROJECT", "DISCOVERY", 5L, "Need", LocalDate.now(), List.of());
        assertThat(move(project, "PROPOSAL")).singleElement().asString().contains("scope");
    }

    @Test
    void lostNeedsAKnownLossReason() {
        var o = opp("PROJECT", "LEAD", null, null, null, List.of());
        assertThat(StageRules.check(o, STAGES.get("LOST"), STAGES, null, Set.of("PRICE")))
            .singleElement().asString().contains("BR-BD-13");
        assertThat(StageRules.check(o, STAGES.get("LOST"), STAGES, "MADE_UP", Set.of("PRICE"))).hasSize(1);
        // A lead can be lost without passing the qualification checks.
        assertThat(StageRules.check(o, STAGES.get("LOST"), STAGES, "PRICE", Set.of("PRICE"))).isEmpty();
    }

    @Test
    void wonIsFinal() {
        var won = opp("STAFF_AUGMENTATION", "WON", 5L, "Need", LocalDate.now(), List.of(COMPLETE));
        assertThat(move(won, "NEGOTIATION")).singleElement().asString().contains("cannot change stage");
    }

    @Test
    void lostCanBeReopened() {
        var lost = opp("PROJECT", "LOST", null, null, null, List.of());
        assertThat(move(lost, "LEAD")).isEmpty();
    }
}
