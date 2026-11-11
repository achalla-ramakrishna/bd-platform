package com.codewalnut.bd.assist;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The request to Claude builds (its output schema is valid) and its answer maps to a Reading. No API calls. */
class ClaudeNoteReaderTest {

    static final NoteReader.Request REQUEST = new NoteReader.Request("Registered for a summit",
        List.of(new NoteReader.Image(new byte[] {1}, "image/png")), LocalDate.of(2026, 11, 10),
        AssistantController.CHANNELS, AssistantController.OUTCOMES, List.of("LEAD", "QUALIFIED", "PROPOSAL", "WON", "LOST"));

    static ClaudeNoteReader.EventFields event(String name) {
        return new ClaudeNoteReader.EventFields(name, "conference", "2026-12-01", "", "Bengaluru", false, "", "",
            "registered?", "");
    }

    static ClaudeNoteReader.InterviewFields interview(String leader) {
        return new ClaudeNoteReader.InterviewFields(leader, "", "", "", "PODCAST", "booked", "2026-12-02", "", "");
    }

    static ClaudeNoteReader.NoteFields fields(String about, ClaudeNoteReader.EventFields e, ClaudeNoteReader.InterviewFields i) {
        return new ClaudeNoteReader.NoteFields("ok", "Person A", "", "", "", "", "", "carrier pigeon", "", "", "",
            "", "", false, 0, 0.1, 0.1, 0.1, 0.1, about, e, i, null, null);
    }

    @Test
    void theRequestAndItsSchemaBuild() {
        var params = ClaudeNoteReader.params(REQUEST);
        assertThat(params.toString()).contains("Event role codes").contains("Interview status codes");
    }

    @Test
    void keepsCodesToTheListsAndDropsEmptySections() {
        var r = ClaudeNoteReader.reading(fields("event", event("Example Summit"), interview("")), REQUEST);
        assertThat(r.about()).isEqualTo("EVENT");
        assertThat(r.event().kind()).isEqualTo("CONFERENCE");
        assertThat(r.event().ourRole()).isEqualTo("WATCHING");
        assertThat(r.interview()).isNull();
        assertThat(r.channel()).isEqualTo("LINKEDIN_MESSAGE");
        assertThat(r.photo()).isNotNull();

        var i = ClaudeNoteReader.reading(fields("INTERVIEW", event(""), interview("Person A")), REQUEST);
        assertThat(i.event()).isNull();
        assertThat(i.interview().status()).isEqualTo("BOOKED");

        // "about" an event with no event falls back to a touch.
        assertThat(ClaudeNoteReader.reading(fields("EVENT", null, null), REQUEST).about()).isEqualTo("TOUCH");
    }

    @Test
    void readsAccountsAndOpportunities() {
        var a = new ClaudeNoteReader.AccountFields("Example Centre", "Example Corp", "USA", "Pune", "WWW.Example.com", "Retail",
            0, 1500, 0, "Opened in 2024");
        var o = new ClaudeNoteReader.OpportunityFields("4 React engineers", "staff_augmentation", 0, "", "Payments", "", "qualified");
        var f = new ClaudeNoteReader.NoteFields("ok", "", "", "", "", "", "", "EMAIL", "", "", "", "", "", false, -1, 0, 0, 0, 0,
            "opportunity", null, null, a, o);
        var r = ClaudeNoteReader.reading(f, REQUEST);
        assertThat(r.about()).isEqualTo("OPPORTUNITY");
        assertThat(r.account().websiteDomain()).isEqualTo("www.example.com");
        assertThat(r.account().yearEstablished()).isNull();
        assertThat(r.account().employeeCount()).isEqualTo(1500);
        assertThat(r.opportunity().type()).isEqualTo("STAFF_AUGMENTATION");
        assertThat(r.opportunity().valueInr()).isNull();
        assertThat(r.opportunity().stage()).isEqualTo("QUALIFIED");
        assertThat(r.photo()).isNull();
    }
}
