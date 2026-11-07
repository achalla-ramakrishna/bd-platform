package com.codewalnut.bd.contracts;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * BR-BD-14: on any day, an engineer's planned and active deployments must add up to 100% or less,
 * and cover no more clients than their seniority allows (Junior and Mid 1, Senior and above 2, set
 * in {@code seniority_rule}).
 */
public final class AllocationRule {

    private AllocationRule() {}

    /** Returns a message for the first day the rule is broken, or empty if it holds. */
    public static Optional<String> check(Collection<ContractRegistry.DeploymentSpan> spans, String seniority,
                                         int maxClients) {
        List<LocalDate> days = spans.stream().map(ContractRegistry.DeploymentSpan::startDate).distinct().sorted().toList();
        for (LocalDate day : days) {
            List<ContractRegistry.DeploymentSpan> active = spans.stream()
                .filter(s -> !s.startDate().isAfter(day) && (s.endDate() == null || !s.endDate().isBefore(day)))
                .toList();
            long clients = active.stream().map(ContractRegistry.DeploymentSpan::accountId).distinct().count();
            int allocation = active.stream().mapToInt(ContractRegistry.DeploymentSpan::allocationPercent).sum();
            if (clients > maxClients) {
                return Optional.of("On " + day + " this engineer would work for " + clients + " clients; a "
                    + seniority.toLowerCase() + " engineer may work for at most " + maxClients + " at a time (BR-BD-14).");
            }
            if (allocation > 100) {
                return Optional.of("On " + day + " this engineer's allocation would be " + allocation
                    + "%; it must be 100% or less (BR-BD-14).");
            }
        }
        return Optional.empty();
    }
}
