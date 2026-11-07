package com.codewalnut.bd.opportunities;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Exit criteria for moving an opportunity between stages (FR-OPP-04). Stages are configurable; the
 * rules hang on the QUALIFIED and PROPOSAL stages' positions, so any stage placed after them inherits
 * their checks.
 */
final class StageRules {

    record Stage(String code, String label, int sortOrder, int defaultProbability, String outcome) {

        boolean isOpen() {
            return "OPEN".equals(outcome);
        }
    }

    private StageRules() {}

    /** Every problem with moving {@code opp} to {@code target}; empty when the move is allowed. */
    static List<String> check(OpportunityRepository.State opp, Stage target, Map<String, Stage> stages,
                              String lossReason, Set<String> lossReasons) {
        List<String> problems = new ArrayList<>();
        Stage current = stages.get(opp.stage());
        if (current != null && "WON".equals(current.outcome())) {
            problems.add("This opportunity is won. Won opportunities cannot change stage.");
            return problems;
        }
        if (target.code().equals(opp.stage())) {
            problems.add("The opportunity is already at " + target.label() + ".");
            return problems;
        }
        if ("LOST".equals(target.outcome())) {
            // BR-BD-13: every lost opportunity carries a loss reason from the configured list.
            if (lossReason == null || !lossReasons.contains(lossReason)) {
                problems.add("Choose a loss reason (BR-BD-13).");
            }
            return problems;
        }
        int qualified = order(stages, "QUALIFIED");
        int proposal = order(stages, "PROPOSAL");
        if (target.sortOrder() >= qualified) {
            // BR-BD-07: a named decision maker, a stated need and a target start date.
            if (opp.decisionMakerContactId() == null) {
                problems.add("Name the decision maker at the client before Qualified (BR-BD-07).");
            }
            if (opp.needSummary() == null || opp.needSummary().isBlank()) {
                problems.add("Describe the client's need before Qualified (BR-BD-07).");
            }
            if (opp.targetStartDate() == null) {
                problems.add("Set a target start date before Qualified (BR-BD-07).");
            }
        }
        if (target.sortOrder() >= proposal && "STAFF_AUGMENTATION".equals(opp.type())) {
            // BR-BD-08: at least one fully described requirement (FR-OPP-02).
            boolean complete = opp.requirements().stream().anyMatch(StageRules::isComplete);
            if (!complete) {
                problems.add("Add at least one complete requirement (role, skills, seniority, headcount, location, "
                    + "start date, duration, rate and rate unit) before Proposal (BR-BD-08).");
            }
        }
        if (target.sortOrder() >= proposal && "PROJECT".equals(opp.type())
            && (opp.scopeSummary() == null || opp.scopeSummary().isBlank())) {
            problems.add("Describe the project scope before Proposal.");
        }
        return problems;
    }

    static boolean isComplete(OpportunityRepository.Requirement r) {
        return r.role() != null && !r.role().isBlank() && r.skills() != null && !r.skills().isEmpty()
            && r.seniority() != null && r.headcount() >= 1 && r.locationMode() != null && r.startDate() != null
            && r.durationMonths() != null && r.rateInr() != null && r.rateUnit() != null;
    }

    private static int order(Map<String, Stage> stages, String code) {
        Stage s = stages.get(code);
        return s == null ? Integer.MAX_VALUE : s.sortOrder();
    }
}
