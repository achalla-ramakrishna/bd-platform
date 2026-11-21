package com.codewalnut.bd.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.codewalnut.bd.TestData;
import com.codewalnut.bd.admin.UserDirectory;
import com.codewalnut.bd.shared.Role;
import com.jayway.jsonpath.JsonPath;

/** GTN, Go To Network (ADR-0017, ADR-0018): import, the stage rules, Today, templates. Fictional people only. */
@SpringBootTest
@ActiveProfiles("test")
class NetworkIntegrationTest {

    static final String ADMIN = "admin@example-gcc.test";
    static final String SALES = "sales.person@example-gcc.test";
    static final String DELIVERY = "delivery.person@example-gcc.test";

    /** The shape of LinkedIn's Connections export: a notes preamble, then the header. */
    static final String LINKEDIN_EXPORT = """
        Notes:
        "When exporting your connection data, you may notice that some of the email addresses are missing."

        First Name,Last Name,URL,Email Address,Company,Position,Connected On
        Engineer,One,https://www.linkedin.com/in/engineer-one,,Alpha Corp,Engineering Manager,12 Oct 2026
        Leader,Two,https://www.linkedin.com/in/leader-two/,leader.two@example-gcc.test,"Beta, Inc",Director,01 Sep 2025
        ,,,,,,
        Person,Three,not-a-url,bad-email,Gamma Labs,Architect,05 Jan 2024
        Engineer,One,https://linkedin.com/in/Engineer-One?trk=x,,Alpha Corp,Engineering Manager,12 Oct 2026
        """;

    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    @Autowired UserDirectory users;
    @Autowired NetworkService network;

    MockMvc mvc;
    LocalDate today;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        TestData.clear(jdbc);
        long admin = users.recordSignIn(ADMIN, "Admin Person").id();
        users.setRoles(users.recordSignIn(SALES, "Sales Person").id(), EnumSet.of(Role.ACCOUNT_EXECUTIVE), admin);
        users.setRoles(users.recordSignIn(DELIVERY, "Delivery Person").id(), EnumSet.of(Role.DELIVERY), admin);
        today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
    }

    ResultActions send(String who, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, String json)
            throws Exception {
        return mvc.perform(req.with(user(who)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    String importCsv(String who, String csv, String tag, boolean dryRun) throws Exception {
        String body = """
            {"csv": %s, "sourceTag": "%s", "dryRun": %s}
            """.formatted(new tools.jackson.databind.json.JsonMapper().writeValueAsString(csv), tag, dryRun);
        return send(who, post("/api/v1/connections/import"), body).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    }

    long connection(String name) {
        return jdbc.sql("SELECT id FROM connection WHERE full_name = :n").param("n", name).query(Long.class).single();
    }

    long start(String who, long connectionId, String channel) throws Exception {
        String body = send(who, post("/api/v1/network/tracks"),
            "{\"connectionId\": %d, \"channel\": \"%s\", \"summary\": \"Hi, long time!\"}".formatted(connectionId, channel))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.detail.track.id")).longValue();
    }

    ResultActions act(String who, long track, String json) throws Exception {
        return send(who, post("/api/v1/network/tracks/" + track + "/actions"), json);
    }

    @Test
    void importsLinkedInsExportAndOurTemplateWithoutDuplicates() throws Exception {
        String dry = importCsv(ADMIN, LINKEDIN_EXPORT, "Alumni", true);
        assertThat((Integer) JsonPath.read(dry, "$.added")).isEqualTo(3);
        assertThat((Boolean) JsonPath.read(dry, "$.dryRun")).isTrue();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM connection").query(Integer.class).single()).isZero();

        String done = importCsv(ADMIN, LINKEDIN_EXPORT, "Alumni", false);
        assertThat((Integer) JsonPath.read(done, "$.added")).isEqualTo(3);
        List<String> problems = JsonPath.read(done, "$.problems[*].message");
        assertThat(problems).anyMatch(p -> p.contains("LinkedIn address isn't one"))
            .anyMatch(p -> p.contains("email address isn't valid"))
            .anyMatch(p -> p.contains("appears more than once")); // same profile, other spelling
        assertThat(jdbc.sql("SELECT source_tag FROM connection WHERE full_name = 'Leader Two'").query(String.class).single())
            .isEqualTo("Alumni");
        assertThat(jdbc.sql("SELECT current_company FROM connection WHERE full_name = 'Leader Two'").query(String.class).single())
            .isEqualTo("Beta, Inc");
        assertThat(jdbc.sql("SELECT connected_on FROM connection_knower k JOIN connection n ON n.id = k.connection_id "
            + "WHERE n.full_name = 'Engineer One'").query(LocalDate.class).single()).isEqualTo(LocalDate.of(2026, 10, 12));

        // Again: nothing new. Another person importing the same people is added as someone who knows them.
        String again = importCsv(ADMIN, LINKEDIN_EXPORT, "Alumni", false);
        assertThat((Integer) JsonPath.read(again, "$.added")).isZero();
        assertThat((Integer) JsonPath.read(again, "$.alreadyKnown")).isEqualTo(3);
        String ours = """
            Full name,Company,Role,WhatsApp,Email,Source,How we know them
            Leader Two,Beta Inc,Director,+91 98765 43210,leader.two@example-gcc.test,,Same team in 2019
            New Person,Delta Ltd,CTO,9876500000,,Conference,Met at a meetup
            """;
        String sales = importCsv(SALES, ours, "Past colleagues", false);
        assertThat((Integer) JsonPath.read(sales, "$.linked")).isEqualTo(1); // matched on email
        assertThat((Integer) JsonPath.read(sales, "$.added")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT whatsapp FROM connection WHERE full_name = 'Leader Two'").query(String.class).single())
            .isEqualTo("919876543210"); // blank filled in
        assertThat(jdbc.sql("SELECT source_tag FROM connection WHERE full_name = 'New Person'").query(String.class).single())
            .isEqualTo("Conference"); // the column wins over the tag
        assertThat(jdbc.sql("SELECT COUNT(*) FROM connection_knower").query(Integer.class).single()).isEqualTo(5);
        mvc.perform(get("/api/v1/connections?source=Alumni").with(user(SALES)))
            .andExpect(jsonPath("$.items.length()").value(3));
        mvc.perform(get("/api/v1/connections/sources").with(user(SALES)))
            .andExpect(jsonPath("$", Matchers.hasItems("Alumni", "Conference")));

        send(DELIVERY, post("/api/v1/connections/import"), "{\"csv\": \"Full name\\nX Y\"}").andExpect(status().isForbidden());
        send(ADMIN, post("/api/v1/connections/import"), "{\"csv\": \"Company\\nAlpha\"}")
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void quietContactsAreDiagnosedNudgedAndParkedAfterTheCap() throws Exception {
        importCsv(ADMIN, LINKEDIN_EXPORT, "Alumni", false);
        long person = connection("Leader Two");
        long track = start(ADMIN, person, "LINKEDIN");
        mvc.perform(get("/api/v1/network/tracks/" + track).with(user(ADMIN)))
            .andExpect(jsonPath("$.detail.track.stage").value("INITIATE"))
            .andExpect(jsonPath("$.detail.track.nextActionOn").value(today.plusDays(7).toString()))
            .andExpect(jsonPath("$.detail.touches[0].kind").value("OUTREACH"))
            .andExpect(jsonPath("$.detail.changes[0].toStage").value("INITIATE"));
        send(ADMIN, post("/api/v1/network/tracks"), "{\"connectionId\": %d, \"channel\": \"EMAIL\"}".formatted(person))
            .andExpect(status().isConflict());
        mvc.perform(get("/api/v1/connections?tracked=false").with(user(ADMIN))).andExpect(jsonPath("$.items.length()").value(2));

        // Eight days without a reply: the sweep makes them Unresponsive with a diagnosis due today.
        jdbc.sql("UPDATE network_track SET last_out_on = :d WHERE id = :id").param("d", today.minusDays(8)).param("id", track).update();
        mvc.perform(get("/api/v1/network/today").with(user(ADMIN)))
            .andExpect(jsonPath("$.items[0].track.stage").value("UNRESPONSIVE"))
            .andExpect(jsonPath("$.items[0].task").value("DIAGNOSE"))
            .andExpect(jsonPath("$.items[0].suggestedReason").value("INERTIA"))
            .andExpect(jsonPath("$.counts.byStage.UNRESPONSIVE").value(1))
            .andExpect(jsonPath("$.rules.nudgeCap").value(3));
        assertThat(jdbc.sql("SELECT changed_by FROM network_stage_change WHERE track_id = :t AND to_stage = 'UNRESPONSIVE'")
            .param("t", track).query(Long.class).optional()).isEmpty(); // automatic

        act(ADMIN, track, "{\"action\": \"SENT\", \"channel\": \"EMAIL\"}").andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.problems[0]").value(Matchers.containsString("why they haven't replied")));
        act(ADMIN, track, "{\"action\": \"DIAGNOSED\", \"reason\": \"NOT_SEEN\"}").andExpect(status().isOk())
            .andExpect(jsonPath("$.next.task").value("NUDGE"))
            .andExpect(jsonPath("$.next.suggestion.channel").value("EMAIL")) // not seen on LinkedIn: go elsewhere
            .andExpect(jsonPath("$.next.suggestion.body").value(Matchers.startsWith("Hi Leader, dropped you a note on LinkedIn")));
        for (int i = 1; i <= 2; i++) {
            act(ADMIN, track, "{\"action\": \"SENT\", \"channel\": \"EMAIL\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.detail.track.stage").value("UNRESPONSIVE"))
                .andExpect(jsonPath("$.detail.track.nudgesSent").value(i))
                .andExpect(jsonPath("$.detail.track.nextActionOn").value(today.plusDays(5).toString()));
        }
        act(ADMIN, track, "{\"action\": \"SENT\", \"channel\": \"WHATSAPP\"}").andExpect(status().isOk())
            .andExpect(jsonPath("$.detail.track.stage").value("MONTHLY_BEEP"))
            .andExpect(jsonPath("$.detail.track.nextActionOn").value(today.plusDays(30).toString()))
            .andExpect(jsonPath("$.detail.changes[0].note").value(Matchers.containsString("3 nudges")));

        // A beep that re-opens the conversation goes to Call scheduled; the call leads to a commitment.
        act(ADMIN, track, "{\"action\": \"REPLIED\", \"outcome\": \"POSITIVE\", \"channel\": \"WHATSAPP\"}")
            .andExpect(jsonPath("$.detail.track.stage").value("CALL_SCHEDULED"))
            .andExpect(jsonPath("$.next.task").value("BOOK_CALL"));
        act(ADMIN, track, "{\"action\": \"CALL_BOOKED\", \"callOn\": \"%s\"}".formatted(today.plusDays(2)))
            .andExpect(jsonPath("$.detail.track.callOn").value(today.plusDays(2).toString()))
            .andExpect(jsonPath("$.next.task").value("CALL_REMINDER"));
        act(ADMIN, track, "{\"action\": \"NO_SHOW\"}").andExpect(jsonPath("$.detail.track.nextAction").value("Reschedule the call"));
        act(ADMIN, track, "{\"action\": \"CALL_HELD\", \"commitmentKind\": \"REFERRAL\"}").andExpect(status().isUnprocessableEntity());
        act(ADMIN, track, "{\"action\": \"CALL_HELD\", \"commitmentKind\": \"REFERRAL\", \"commitment\": \"Intro to their platform head\"}")
            .andExpect(jsonPath("$.detail.track.stage").value("WARM_COMMITMENT"))
            .andExpect(jsonPath("$.detail.commitments[0].kind").value("REFERRAL"));
        // When the first beep is due, the sweep moves them to Monthly beep.
        jdbc.sql("UPDATE network_track SET next_action_on = :d WHERE id = :id").param("d", today).param("id", track).update();
        assertThat(network.sweep(today)).isEqualTo(1);
        assertThat(network.sweep(today)).isZero();
        mvc.perform(get("/api/v1/network/tracks/" + track).with(user(ADMIN)))
            .andExpect(jsonPath("$.detail.track.stage").value("MONTHLY_BEEP"))
            .andExpect(jsonPath("$.next.task").value("BEEP"));
    }

    @Test
    void notTheRightTimeParksWithARevisitDateAndTheBeepRemembersWhy() throws Exception {
        importCsv(ADMIN, LINKEDIN_EXPORT, "Alumni", false);
        long track = start(ADMIN, connection("Engineer One"), "LINKEDIN");
        act(ADMIN, track, "{\"action\": \"INTRO_DONE\"}").andExpect(status().isUnprocessableEntity());
        act(ADMIN, track, "{\"action\": \"REPLIED\", \"outcome\": \"NOT_RIGHT_TIME\", \"channel\": \"LINKEDIN\", "
                + "\"revisitOn\": \"%s\", \"revisitReason\": \"Budget cycle closes in March\"}".formatted(today.plusDays(60)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.detail.track.stage").value("MONTHLY_BEEP"))
            .andExpect(jsonPath("$.detail.track.nextActionOn").value(today.plusDays(60).toString()))
            .andExpect(jsonPath("$.detail.touches[0].direction").value("IN"));
        jdbc.sql("UPDATE network_track SET next_action_on = :d WHERE id = :id").param("d", today).param("id", track).update();
        mvc.perform(get("/api/v1/network/today").with(user(ADMIN)))
            .andExpect(jsonPath("$.items[0].task").value("BEEP"))
            .andExpect(jsonPath("$.items[0].suggestion.body").value(Matchers.containsString("You mentioned budget cycle closes in March")))
            .andExpect(jsonPath("$.items[0].suggestion.channel").value("LINKEDIN"));
        // Sales sees only their own Today; everyone's on request.
        mvc.perform(get("/api/v1/network/today").with(user(SALES))).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/v1/network/today?everyone=true").with(user(SALES))).andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void theHappyPathOverridesConflictsAndPermissions() throws Exception {
        importCsv(ADMIN, LINKEDIN_EXPORT, "Alumni", false);
        long track = start(SALES, connection("Person Three"), "WHATSAPP");
        act(SALES, track, "{\"action\": \"REPLIED\", \"outcome\": \"POSITIVE\", \"channel\": \"WHATSAPP\", \"summary\": \"Happy to talk\"}")
            .andExpect(jsonPath("$.detail.track.stage").value("RESPONDED"))
            .andExpect(jsonPath("$.next.task").value("INTRO"))
            .andExpect(jsonPath("$.next.suggestion.body").value(Matchers.startsWith("Thanks Person!")));
        act(SALES, track, "{\"action\": \"INTRO_DONE\", \"channel\": \"WHATSAPP\", \"summary\": \"Sent the intro\"}")
            .andExpect(jsonPath("$.detail.track.stage").value("INTRO_MADE"))
            .andExpect(jsonPath("$.detail.touches[0].kind").value("INTRO"));
        act(SALES, track, "{\"action\": \"COMMITTED\", \"commitmentKind\": \"KEEP_POSTED\", \"commitment\": \"Keep me posted\"}")
            .andExpect(jsonPath("$.detail.track.stage").value("WARM_COMMITMENT"));
        act(SALES, track, "{\"action\": \"SENT\", \"channel\": \"WHATSAPP\"}")
            .andExpect(jsonPath("$.detail.track.stage").value("MONTHLY_BEEP"))
            .andExpect(jsonPath("$.detail.touches[0].kind").value("BEEP"));
        act(SALES, track, "{\"action\": \"MOVED\", \"stage\": \"INTRO_MADE\", \"note\": \"Dragged on the board\"}")
            .andExpect(jsonPath("$.detail.track.stage").value("INTRO_MADE"))
            .andExpect(jsonPath("$.detail.changes[0].note").value("Moved by hand: Dragged on the board"))
            .andExpect(jsonPath("$.detail.changes[0].changedBy").value("Sales Person"));
        act(SALES, track, "{\"action\": \"MOVED\", \"stage\": \"NOWHERE\"}").andExpect(status().isUnprocessableEntity());
        act(SALES, track, "{\"action\": \"MOVED\", \"stage\": \"RESPONDED\", \"version\": 0}").andExpect(status().isConflict());
        act(DELIVERY, track, "{\"action\": \"MOVED\", \"stage\": \"RESPONDED\"}").andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/network/tracks/" + track).with(user(DELIVERY))).andExpect(status().isOk());

        send(SALES, patch("/api/v1/network/tracks/" + track), "{\"nextAction\": \"Send the case study\", \"nextActionOn\": \"%s\", \"beepIntervalDays\": 45}"
                .formatted(today.plusDays(1)))
            .andExpect(jsonPath("$.detail.track.nextAction").value("Send the case study"))
            .andExpect(jsonPath("$.detail.track.beepIntervalDays").value(45));
        send(SALES, patch("/api/v1/network/tracks/" + track), "{\"beepIntervalDays\": 2}").andExpect(status().isUnprocessableEntity());

        mvc.perform(get("/api/v1/network/tracks?stage=INTRO_MADE").with(user(ADMIN)))
            .andExpect(jsonPath("$.tracks.length()").value(1))
            .andExpect(jsonPath("$.tracks[0].sourceTag").value("Alumni"))
            .andExpect(jsonPath("$.tracks[0].ownerName").value("Sales Person"));
        mvc.perform(get("/api/v1/network/tracks?mine=true").with(user(ADMIN))).andExpect(jsonPath("$.tracks.length()").value(0));
        mvc.perform(get("/api/v1/network/tracks/for-connection/" + connection("Person Three")).with(user(ADMIN)))
            .andExpect(jsonPath("$.detail.track.id").value(track));

        // The heatmap counts GTN touches.
        mvc.perform(get("/api/v1/heatmap").with(user(SALES)).param("days", "7")).andExpect(status().isOk());

        mvc.perform(delete("/api/v1/network/tracks/" + track).with(user(SALES)).with(csrf())).andExpect(status().isNoContent());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM connection WHERE full_name = 'Person Three'").query(Integer.class).single()).isOne();
    }

    @Test
    void templatesAreEditableAndFilledForAnyone() throws Exception {
        importCsv(ADMIN, LINKEDIN_EXPORT, "Alumni", false);
        long person = connection("Leader Two");
        jdbc.sql("UPDATE connection_knower SET how_known = 'Worked together at a past employer'").update();
        List<Integer> ids = JsonPath.read(mvc.perform(get("/api/v1/network/templates").with(user(ADMIN)))
            .andReturn().getResponse().getContentAsString(), "$[?(@.stage == 'INITIATE')].id");
        send(ADMIN, post("/api/v1/network/render"), "{\"connectionId\": %d, \"templateId\": %d}".formatted(person, ids.get(0)))
            .andExpect(jsonPath("$.channel").value("LINKEDIN"))
            .andExpect(jsonPath("$.body").value(Matchers.startsWith("Hi Leader, it has been a while since worked together at a past employer")));

        String created = send(ADMIN, post("/api/v1/network/templates"), """
                {"name": "Short WhatsApp nudge", "stage": "UNRESPONSIVE", "reason": "INERTIA", "channel": "WHATSAPP",
                 "body": "{first_name}, a quick nudge from {my_name}: 20 minutes next week? {unknown}"}
                """).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long tpl = ((Number) JsonPath.read(created, "$.id")).longValue();
        send(ADMIN, post("/api/v1/network/render"), "{\"connectionId\": %d, \"templateId\": %d, \"channel\": \"WHATSAPP\"}".formatted(person, tpl))
            .andExpect(jsonPath("$.body").value("Leader, a quick nudge from Admin: 20 minutes next week? {unknown}"));
        send(ADMIN, post("/api/v1/network/templates"), "{\"name\": \"No reason\", \"stage\": \"UNRESPONSIVE\", \"body\": \"x\"}")
            .andExpect(status().isUnprocessableEntity());
        send(ADMIN, put("/api/v1/network/templates/" + tpl), """
                {"name": "Short nudge", "stage": "UNRESPONSIVE", "reason": "INERTIA", "channel": "ANY", "body": "Hi", "active": false,
                 "version": 0}
                """).andExpect(jsonPath("$.active").value(false));
        mvc.perform(delete("/api/v1/network/templates/" + tpl).with(user(ADMIN)).with(csrf())).andExpect(status().isNoContent());
        send(DELIVERY, post("/api/v1/network/templates"), "{}").andExpect(status().isForbidden());
    }

    @Test
    void walnutsMorningBriefListsTheNetworkActionsDue() throws Exception {
        importCsv(ADMIN, LINKEDIN_EXPORT, "Alumni", false);
        long track = start(ADMIN, connection("Leader Two"), "LINKEDIN");
        jdbc.sql("UPDATE network_track SET last_out_on = :d WHERE id = :id").param("d", today.minusDays(9)).param("id", track).update();
        network.sweep(today);
        String body = JsonPath.read(mvc.perform(post("/api/v1/walnut/brief").with(user(ADMIN)).with(csrf()))
            .andReturn().getResponse().getContentAsString(), "$.body");
        assertThat(body).contains("## Your network (GTN)")
            .contains("[Leader Two](/network/" + track + ") (Beta, Inc): Diagnose: why no reply?")
            .contains("Leader Two hasn't replied. Not seen on LinkedIn, inertia, or not the right time?");
    }
}
