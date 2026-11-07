package com.codewalnut.bd.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.codewalnut.bd.contracts.ContractRegistry.DeploymentSpan;

/** BR-BD-14. */
class AllocationRuleTest {

    private static final LocalDate JAN = LocalDate.parse("2027-01-01");
    private static final LocalDate JUN = LocalDate.parse("2027-06-30");

    private static DeploymentSpan span(long account, LocalDate start, LocalDate end, int allocation) {
        return new DeploymentSpan(1, account, start, end, allocation);
    }

    @Test
    void juniorWithOneFullTimeClientIsFine() {
        assertThat(AllocationRule.check(List.of(span(10, JAN, JUN, 100)), "JUNIOR", 1)).isEmpty();
    }

    @Test
    void juniorCannotWorkForTwoClientsAtOnce() {
        var result = AllocationRule.check(List.of(span(10, JAN, JUN, 50), span(20, JAN, JUN, 50)), "JUNIOR", 1);
        assertThat(result).hasValueSatisfying(m -> assertThat(m).contains("2 clients").contains("at most 1"));
    }

    @Test
    void juniorMayMoveFromOneClientToAnotherWithoutOverlap() {
        var result = AllocationRule.check(
            List.of(span(10, JAN, LocalDate.parse("2027-03-31"), 100), span(20, LocalDate.parse("2027-04-01"), JUN, 100)),
            "JUNIOR", 1);
        assertThat(result).isEmpty();
    }

    @Test
    void seniorMaySplitAcrossTwoClients() {
        assertThat(AllocationRule.check(List.of(span(10, JAN, JUN, 50), span(20, JAN, JUN, 50)), "SENIOR", 2)).isEmpty();
    }

    @Test
    void seniorCannotWorkForThreeClients() {
        var spans = List.of(span(10, JAN, JUN, 30), span(20, JAN, JUN, 30), span(30, JAN, JUN, 30));
        assertThat(AllocationRule.check(spans, "SENIOR", 2)).hasValueSatisfying(m -> assertThat(m).contains("3 clients"));
    }

    @Test
    void allocationAbove100IsRejected() {
        var result = AllocationRule.check(List.of(span(10, JAN, JUN, 60), span(20, LocalDate.parse("2027-03-01"), JUN, 50)),
            "SENIOR", 2);
        assertThat(result).hasValueSatisfying(m -> assertThat(m).contains("2027-03-01").contains("110%"));
    }

    @Test
    void openEndedDeploymentsOverlapEverythingAfterThem() {
        var result = AllocationRule.check(List.of(span(10, JAN, null, 100), span(20, LocalDate.parse("2028-01-01"), null, 100)),
            "SENIOR", 2);
        assertThat(result).hasValueSatisfying(m -> assertThat(m).contains("200%"));
    }
}
