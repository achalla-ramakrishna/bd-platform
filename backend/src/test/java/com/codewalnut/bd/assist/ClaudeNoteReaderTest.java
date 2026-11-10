package com.codewalnut.bd.assist;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The request to Claude builds (its output schema is valid) and its answer maps to a Reading. No API calls. */
class ClaudeNoteReaderTest {

    static final NoteReader.Request REQUEST = new NoteReader.Request("Registered for a summit",
        List.of(new NoteReader.Image(new byte[] {1}, "image/png")), LocalDate.of(2026, 11, 10),
        AssistantController.CHANNELS, AssistantController.OUTCOMES);

    static ClaudeNoteReader.EventFields event(String name) {
        return new ClaudeNoteReader.EventFields(name, "conference", "2026-12-01", "", "Bengaluru", false, "", "",
            "registered?", "");
    }

    static ClaudeNoteReader.InterviewFields interview(String leader) {
        return new ClaudeNoteReader.InterviewFields(leader, "", "", "", "PODCAST", "booked", "2026-12-02", "", "");
    }

    static ClaudeNoteReader.NoteFields fields(String about, ClaudeNoteReader.EventFields e, ClaudeNoteReader.InterviewFields i) {
        return new ClaudeNoteReader.NoteFields("ok", "Person A", "", "", "", "", "", "carrier pigeon", "", "", "",
            "", "", false, 0, 0.1, 0.1, 0.1, 0.1, about, e, i);
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
}
