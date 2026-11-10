package com.codewalnut.bd.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** FR-ACC-04 rules with the default weights. */
class FitScorerTest {

    static final FitProperties DEFAULTS = new FitProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    static final Set<String> SKILLS = Set.of("java", "react", "kafka");
    static final Set<String> CLIENT_INDUSTRIES = Set.of("logistics");

    @Test
    void anUnknownAccountScoresLowAndSaysWhy() {
        var fit = FitScorer.rate(new FitScorer.Inputs(null, null, List.of(), null, 0, null, false, false, false, false),
            SKILLS, CLIENT_INDUSTRIES, DEFAULTS);
        assertThat(fit.score()).isEqualTo(5);
        assertThat(fit.reasons()).contains("No contacts yet", "Tech stack not recorded", "No fit rating in research yet");
    }

    @Test
    void aWarmMidSizeAccountScoresHigh() {
        var fit = FitScorer.rate(new FitScorer.Inputs("500-2000", 2015, List.of("Java", "React", "Go"), "Logistics", 3, "MEDIUM",
            true, true, true, true), SKILLS, CLIENT_INDUSTRIES, DEFAULTS);
        // 20 size + 10 tech + 10 signals (capped) + 10 research fit + 10 decision maker + 10 engagement + 10 industry;
        // formed in 2015, so no points for being young
        assertThat(fit.score()).isEqualTo(80);
        assertThat(fit.reasons()).contains("Research rates the fit medium (+10)");
        assertThat(fit.reasons()).anyMatch(r -> r.contains("Java, React"));
    }

    @Test
    void capsApplyAndTheTotalNeverPasses100() {
        var many = List.of("java", "react", "kafka", "java ", "React");
        var fit = FitScorer.rate(new FitScorer.Inputs("500-2000", java.time.Year.now().getValue(), many, "Logistics", 10, "STRONG", true, true, true, true),
            SKILLS, CLIENT_INDUSTRIES, DEFAULTS);
        assertThat(fit.score()).isEqualTo(100);
    }

    @Test
    void weightsComeFromConfiguration() {
        var custom = new FitProperties(Map.of("5000+", 30), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, Map.of("WEAK", -10), 0, 2);
        var fit = FitScorer.rate(new FitScorer.Inputs("5000+", null, List.of(), null, 0, "WEAK", false, false, false, false),
            SKILLS, CLIENT_INDUSTRIES, custom);
        assertThat(fit.score()).isEqualTo(20);
    }

    @Test
    void aStrongResearchRatingLiftsAnOtherwiseUnknownAccount() {
        var strong = FitScorer.rate(new FitScorer.Inputs(null, null, List.of(), null, 1, "STRONG", false, false, false, false),
            SKILLS, CLIENT_INDUSTRIES, DEFAULTS);
        var weak = FitScorer.rate(new FitScorer.Inputs(null, null, List.of(), null, 1, "WEAK", false, false, false, false),
            SKILLS, CLIENT_INDUSTRIES, DEFAULTS);
        // 5 size unknown + 5 signal + 20 strong, against 5 + 5 + 0 weak
        assertThat(strong.score()).isEqualTo(30);
        assertThat(weak.score()).isEqualTo(10);
    }

    @Test
    void aRecentlyFormedCentreEarnsPoints() {
        int thisYear = java.time.Year.now().getValue();
        var young = FitScorer.rate(new FitScorer.Inputs(null, thisYear - 1, List.of(), null, 0, null, false, false, false, false),
            SKILLS, CLIENT_INDUSTRIES, DEFAULTS);
        var old = FitScorer.rate(new FitScorer.Inputs(null, thisYear - 10, List.of(), null, 0, null, false, false, false, false),
            SKILLS, CLIENT_INDUSTRIES, DEFAULTS);
        assertThat(young.score()).isEqualTo(15);
        assertThat(young.reasons()).anyMatch(r -> r.startsWith("Formed in " + (thisYear - 1)));
        assertThat(old.score()).isEqualTo(5);
    }
}
