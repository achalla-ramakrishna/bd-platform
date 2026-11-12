package com.codewalnut.bd.assist;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Walnut's request to Claude builds (its output schema is valid) and carries the history in order. No API calls. */
class ClaudeCoachTest {

    @Test
    void theRequestAndItsSchemaBuild() {
        var params = ClaudeCoach.params(new Coach.Request("Admin Person", LocalDate.of(2026, 11, 12), "{\"targets\":[]}",
            List.of(new Coach.Turn("PERSON", "Hi"), new Coach.Turn("WALNUT", "Hello")), "Plan my day",
            List.of("EMAIL"), List.of("DONE")));
        String text = params.toString();
        assertThat(text).contains("You are Walnut").contains("Plan my day").contains("<bd_snapshot>")
            .contains("Connect: the team member sends a LinkedIn connection request");
    }
}
