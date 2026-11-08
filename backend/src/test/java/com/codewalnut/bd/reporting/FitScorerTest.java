package com.codewalnut.bd.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** FR-ACC-04 rules with the default weights. */
class FitScorerTest {

    static final FitProperties DEFAULTS = new FitProperties(null, null, null, null, null, null, null, null, null, null, null);
    static final Set<String> SKILLS = Set.of("java", "react", "kafka");
    static final Set<String> CLIENT_INDUSTRIES = Set.of("logistics");

    @Test
    void anUnknownAccountScoresLowAndSaysWhy() {
        var fit = FitScorer.rate(new FitScorer.Inputs(null, List.of(), null, 0, false, false, false, false),
            SKILLS, CLIENT_INDUSTRIES, DEFAULTS);
        assertThat(fit.score()).isEqualTo(5);
        assertThat(fit.reasons()).contains("No contacts yet", "Tech stack not recorded");
    }

    @Test
    void aWarmMidSizeAccountScoresHigh() {
        var fit = FitScorer.rate(new FitScorer.Inputs("500-2000", List.of("Java", "React", "Go"), "Logistics", 3,
            true, true, true, true), SKILLS, CLIENT_INDUSTRIES, DEFAULTS);
        // 20 size + 10 tech + 15 signals + 15 decision maker + 15 engagement + 10 industry
        assertThat(fit.score()).isEqualTo(85);
        assertThat(fit.reasons()).anyMatch(r -> r.contains("Java, React"));
    }

    @Test
    void capsApplyAndTheTotalNeverPasses100() {
        var many = List.of("java", "react", "kafka", "java ", "React");
        var fit = FitScorer.rate(new FitScorer.Inputs("500-2000", many, "Logistics", 10, true, true, true, true),
            SKILLS, CLIENT_INDUSTRIES, DEFAULTS);
        assertThat(fit.score()).isEqualTo(100);
    }

    @Test
    void weightsComeFromConfiguration() {
        var custom = new FitProperties(Map.of("5000+", 30), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        var fit = FitScorer.rate(new FitScorer.Inputs("5000+", List.of(), null, 0, false, false, false, false),
            SKILLS, CLIENT_INDUSTRIES, custom);
        assertThat(fit.score()).isEqualTo(30);
    }
}
