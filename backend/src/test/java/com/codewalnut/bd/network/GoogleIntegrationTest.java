package com.codewalnut.bd.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.codewalnut.bd.TestData;
import com.codewalnut.bd.admin.UserDirectory;
import com.codewalnut.bd.google.GoogleApi;
import com.codewalnut.bd.shared.Role;
import com.jayway.jsonpath.JsonPath;

/** GTN phase 3 (ADR-0020): connect Google, send from Gmail, find replies, book on the calendar. Google is a stub. */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {"app.google.client-id=test-client", "app.google.client-secret=test-secret",
    "app.google.token-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="})
class GoogleIntegrationTest {

    static final String ADMIN = "admin@example-gcc.test";
    static final String DELIVERY = "delivery.person@example-gcc.test";

    static final List<String> SENT_RAW = new CopyOnWriteArrayList<>();
    static final List<String> SENT_THREADS = new CopyOnWriteArrayList<>();
    static final List<String> REVOKED = new CopyOnWriteArrayList<>();
    static final List<String> EVENTS = new CopyOnWriteArrayList<>();
    static volatile List<GoogleApi.Message> thread = List.of();
    static volatile List<GoogleApi.Busy> busy = List.of();

    @TestConfiguration
    static class Stubs {
        @Bean
        @Primary
        GoogleApi stubGoogle() {
            return new GoogleApi() {
                @Override
                public Tokens exchange(String code, String redirectUri) {
                    String email = "someone-else".equals(code) ? "other@example-gcc.test" : ADMIN;
                    return new Tokens("access-1", "refresh-1", 3600,
                        "openid email https://www.googleapis.com/auth/gmail.send https://www.googleapis.com/auth/gmail.metadata "
                            + "https://www.googleapis.com/auth/calendar.events https://www.googleapis.com/auth/calendar.freebusy", email);
                }

                @Override
                public Tokens refresh(String refreshToken) {
                    return new Tokens("access-2", refreshToken, 3600, null, null);
                }

                @Override
                public void revoke(String token) {
                    REVOKED.add(token);
                }

                @Override
                public Sent send(String accessToken, String raw, String threadId) {
                    SENT_RAW.add(raw);
                    SENT_THREADS.add(String.valueOf(threadId));
                    return new Sent("msg-" + SENT_RAW.size(), threadId == null ? "thread-1" : threadId);
                }

                @Override
                public Message message(String accessToken, String id) {
                    return new Message(id, "thread-1", ADMIN, "<" + id + "@mail.example-gcc.test>", 0, "", false, "Catching up");
                }

                @Override
                public List<Message> thread(String accessToken, String threadId) {
                    return thread;
                }

                @Override
                public List<Busy> freeBusy(String accessToken, Instant from, Instant to) {
                    return busy;
                }

                @Override
                public Event createEvent(String accessToken, String summary, String description, Instant start, Instant end,
                                         String attendeeEmail, String zone) {
                    EVENTS.add(summary + "|" + attendeeEmail + "|" + start + "|" + Duration.between(start, end).toMinutes());
                    return new Event("event-1", "https://meet.example-gcc.test/abc", "https://calendar.example-gcc.test/e/1");
                }
            };
        }
    }

    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    @Autowired UserDirectory users;
    @Autowired NetworkService network;

    MockMvc mvc;
    LocalDate today;
    long connection;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        TestData.clear(jdbc);
        SENT_RAW.clear();
        SENT_THREADS.clear();
        REVOKED.clear();
        EVENTS.clear();
        thread = List.of();
        busy = List.of();
        long admin = users.recordSignIn(ADMIN, "Admin Person").id();
        users.setRoles(users.recordSignIn(DELIVERY, "Delivery Person").id(), EnumSet.of(Role.DELIVERY), admin);
        today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        jdbc.sql("""
                INSERT INTO connection (full_name, current_company, email, created_by)
                VALUES ('Leader Two', 'Beta Inc', 'leader.two@example-gcc.test', :u)
                """).param("u", admin).update();
        connection = jdbc.sql("SELECT id FROM connection").query(Long.class).single();
    }

    ResultActions json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, String body) throws Exception {
        return mvc.perform(req.with(user(ADMIN)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    void connect() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String location = mvc.perform(get("/api/v1/google/connect").with(user(ADMIN)).session(session))
            .andExpect(status().isFound()).andReturn().getResponse().getRedirectedUrl();
        String state = location.replaceAll(".*[?&]state=([^&]+).*", "$1");
        mvc.perform(get("/api/v1/google/callback").param("code", "ok").param("state", state).with(user(ADMIN)).session(session))
            .andExpect(status().isFound());
    }

    static String decode(String raw) {
        return new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);
    }

    @Test
    void connectingAsksGoogleForGtnsScopesAndOnlyTheirOwnMailbox() throws Exception {
        mvc.perform(get("/api/v1/google/status").with(user(ADMIN)))
            .andExpect(jsonPath("$.available").value(true)).andExpect(jsonPath("$.connected").value(false));
        MockHttpSession session = new MockHttpSession();
        String location = mvc.perform(get("/api/v1/google/connect").with(user(ADMIN)).session(session))
            .andExpect(status().isFound()).andReturn().getResponse().getRedirectedUrl();
        assertThat(location).startsWith("https://accounts.google.com/o/oauth2/v2/auth?client_id=test-client")
            .contains("gmail.send").contains("gmail.metadata").contains("calendar.freebusy").contains("access_type=offline")
            .contains("redirect_uri=http%3A%2F%2Flocalhost%2Fapi%2Fv1%2Fgoogle%2Fcallback");
        String state = location.replaceAll(".*[?&]state=([^&]+).*", "$1");
        // A forged or replayed callback is refused.
        assertThat(mvc.perform(get("/api/v1/google/callback").param("code", "ok").param("state", "forged").with(user(ADMIN)).session(session))
            .andReturn().getResponse().getRedirectedUrl()).contains("google=The+connection+request+expired");
        mvc.perform(get("/api/v1/google/connect").with(user(ADMIN)).session(session));
        state = ((String) session.getAttribute("gtn.google.state"));
        assertThat(mvc.perform(get("/api/v1/google/callback").param("code", "someone-else").param("state", state).with(user(ADMIN))
                .session(session)).andReturn().getResponse().getRedirectedUrl()).contains("Connect+your+own+Google+account");
        assertThat(REVOKED).containsExactly("refresh-1");
        mvc.perform(get("/api/v1/google/connect").with(user(DELIVERY))).andExpect(status().isForbidden());

        connect();
        mvc.perform(get("/api/v1/google/status").with(user(ADMIN)))
            .andExpect(jsonPath("$.connected").value(true)).andExpect(jsonPath("$.canSend").value(true))
            .andExpect(jsonPath("$.canCalendar").value(true)).andExpect(jsonPath("$.email").value(ADMIN));
        String stored = jdbc.sql("SELECT refresh_token_enc FROM google_account").query(String.class).single();
        assertThat(stored).doesNotContain("refresh-1"); // encrypted at rest

        mvc.perform(delete("/api/v1/google").with(user(ADMIN)).with(csrf())).andExpect(status().isNoContent());
        assertThat(REVOKED).contains("refresh-1");
        mvc.perform(get("/api/v1/google/status").with(user(ADMIN))).andExpect(jsonPath("$.connected").value(false));
    }

    @Test
    void sendsFromGmailInOneThreadAndFindsTheReply() throws Exception {
        json(post("/api/v1/network/tracks/email"), "{\"connectionId\": %d, \"subject\": \"Catching up\", \"body\": \"Hi Leader\"}"
            .formatted(connection)).andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.problems[0]").value(Matchers.containsString("Connect your Gmail first")));
        connect();
        String created = json(post("/api/v1/network/tracks/email"),
                "{\"connectionId\": %d, \"subject\": \"Catching up\", \"body\": \"Hi Leader, long time!\"}".formatted(connection))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.detail.track.stage").value("INITIATE"))
            .andExpect(jsonPath("$.detail.touches[0].channel").value("EMAIL"))
            .andReturn().getResponse().getContentAsString();
        long track = ((Number) JsonPath.read(created, "$.detail.track.id")).longValue();
        String first = decode(SENT_RAW.get(0));
        assertThat(first).contains("To: =?UTF-8?B?").contains("<leader.two@example-gcc.test>").contains("<" + ADMIN + ">")
            .contains("Subject: =?UTF-8?B?" + Base64.getEncoder().encodeToString("Catching up".getBytes(StandardCharsets.UTF_8)) + "?=")
            .contains(Base64.getEncoder().encodeToString("Hi Leader, long time!".getBytes(StandardCharsets.UTF_8)));
        assertThat(SENT_THREADS.get(0)).isEqualTo("null");
        assertThat(jdbc.sql("SELECT gmail_thread_id FROM network_touch WHERE track_id = :t").param("t", track).query(String.class).single())
            .isEqualTo("thread-1");

        // Quiet: the nudge goes in the same thread, as a reply.
        jdbc.sql("UPDATE network_track SET last_out_on = :d WHERE id = :id").param("d", today.minusDays(8)).param("id", track).update();
        network.sweep(today);
        json(post("/api/v1/network/tracks/" + track + "/actions"), "{\"action\": \"DIAGNOSED\", \"reason\": \"INERTIA\"}").andExpect(status().isOk());
        json(post("/api/v1/network/tracks/" + track + "/email"), "{\"subject\": \"x\", \"body\": \"A quick nudge\"}")
            .andExpect(status().isOk()).andExpect(jsonPath("$.detail.track.nudgesSent").value(1));
        assertThat(SENT_THREADS.get(1)).isEqualTo("thread-1");
        assertThat(decode(SENT_RAW.get(1))).contains("In-Reply-To: <msg-1@mail.example-gcc.test>")
            .contains("Subject: =?UTF-8?B?" + Base64.getEncoder().encodeToString("Re: Catching up".getBytes(StandardCharsets.UTF_8)));
        // A stale version is refused and nothing goes out.
        json(post("/api/v1/network/tracks/" + track + "/email"), "{\"subject\": \"x\", \"body\": \"Again\", \"version\": 0}")
            .andExpect(status().isConflict());
        assertThat(SENT_RAW).hasSize(2);

        // Their reply (and an out-of-office) in the thread.
        long now = Instant.now().toEpochMilli();
        thread = List.of(
            new GoogleApi.Message("msg-1", "thread-1", "Admin Person <" + ADMIN + ">", null, now - 9_000_000, "Hi", false, "Catching up"),
            new GoogleApi.Message("ooo-1", "thread-1", "Leader Two <leader.two@example-gcc.test>", null, now - 5_000, "Away", true, "Re"),
            new GoogleApi.Message("rep-1", "thread-1", "Leader Two <leader.two@example-gcc.test>", null, now, "Sure, let&#39;s talk next week",
                false, "Re: Catching up"));
        mvc.perform(post("/api/v1/network/replies/check").with(user(ADMIN)).with(csrf())).andExpect(jsonPath("$.found").value(1));
        mvc.perform(post("/api/v1/network/replies/check").with(user(ADMIN)).with(csrf())).andExpect(jsonPath("$.found").value(0));
        mvc.perform(get("/api/v1/network/today").with(user(ADMIN)))
            .andExpect(jsonPath("$.items[0].task").value("REPLY_CHECK"))
            .andExpect(jsonPath("$.items[0].track.stage").value("RESPONDED"))
            .andExpect(jsonPath("$.items[0].track.lastTopic").value("Replied by email: Sure, let's talk next week"));
        json(post("/api/v1/network/tracks/" + track + "/actions"), "{\"action\": \"CONFIRM_REPLY\", \"outcome\": \"POSITIVE\"}")
            .andExpect(jsonPath("$.detail.track.replyCheck").value(false))
            .andExpect(jsonPath("$.next.task").value("INTRO"));
        json(post("/api/v1/network/tracks/" + track + "/actions"), "{\"action\": \"CONFIRM_REPLY\"}").andExpect(status().isUnprocessableEntity());

        // Disconnected: sending is refused again.
        mvc.perform(delete("/api/v1/google").with(user(ADMIN)).with(csrf()));
        json(post("/api/v1/network/tracks/" + track + "/email"), "{\"action\": \"INTRO_DONE\", \"subject\": \"x\", \"body\": \"Intro\"}")
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void peopleBookFreeSlotsOnTheOwnersCalendar() throws Exception {
        json(put("/api/v1/network/profile"), "{\"headline\": \"Hello\", \"bookingMode\": \"CALENDAR\"}").andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.problems[0]").value(Matchers.containsString("Connect your Google Calendar")));
        connect();
        json(put("/api/v1/network/profile"), "{\"headline\": \"Hello\", \"bookingMode\": \"CALENDAR\", \"slotMinutes\": 25}")
            .andExpect(status().isUnprocessableEntity());
        json(put("/api/v1/network/profile"), "{\"headline\": \"Hello\", \"bookingMode\": \"CALENDAR\", \"slotMinutes\": 30, \"dayStart\": \"18:00\", \"dayEnd\": \"10:00\"}")
            .andExpect(status().isUnprocessableEntity());
        json(put("/api/v1/network/profile"), "{\"headline\": \"Hello\", \"bookingMode\": \"CALENDAR\", \"slotMinutes\": 30, \"dayStart\": \"09:30\", \"dayEnd\": \"17:00\"}")
            .andExpect(jsonPath("$.bookingMode").value("CALENDAR")).andExpect(jsonPath("$.dayStart").value("09:30"));
        String created = json(post("/api/v1/network/tracks"), "{\"connectionId\": %d, \"channel\": \"LINKEDIN\"}".formatted(connection))
            .andReturn().getResponse().getContentAsString();
        long track = ((Number) JsonPath.read(created, "$.detail.track.id")).longValue();
        String url = JsonPath.read(json(post("/api/v1/network/tracks/" + track + "/profile"), "{}").andReturn().getResponse().getContentAsString(), "$.url");
        String token = url.substring(url.lastIndexOf('/') + 1);

        String html = mvc.perform(get("/p/" + token)).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("id=\"book\"").contains("Book a 30-minute call").doesNotContain("data-cta=\"BOOK\" target");
        String slots = mvc.perform(get("/p/" + token + "/slots")).andExpect(jsonPath("$.available").value(true))
            .andExpect(jsonPath("$.minutes").value(30)).andExpect(jsonPath("$.needEmail").value(false))
            .andReturn().getResponse().getContentAsString();
        List<String> free = JsonPath.read(slots, "$.slots");
        assertThat(free).isNotEmpty();
        Instant first = Instant.parse(free.get(0));
        assertThat(first).isAfter(Instant.now().plus(Duration.ofHours(2)).minusSeconds(60));
        // Busy at that time: it is no longer offered, and booking it is refused.
        busy = new ArrayList<>(List.of(new GoogleApi.Busy(first, first.plus(Duration.ofMinutes(30)))));
        List<String> later = JsonPath.read(mvc.perform(get("/p/" + token + "/slots")).andReturn().getResponse().getContentAsString(), "$.slots");
        assertThat(later).doesNotContain(free.get(0));
        mvc.perform(post("/p/" + token + "/book").contentType(MediaType.APPLICATION_JSON).content("{\"start\": \"" + free.get(0) + "\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.problems[0]").value(Matchers.containsString("just taken")));

        String booked = mvc.perform(post("/p/" + token + "/book").contentType(MediaType.APPLICATION_JSON)
                .content("{\"start\": \"" + later.get(0) + "\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.meetLink").value("https://meet.example-gcc.test/abc"))
            .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(booked, "$.when")).contains("Asia/Kolkata");
        assertThat(EVENTS).hasSize(1);
        assertThat(EVENTS.get(0)).contains("|leader.two@example-gcc.test|" + later.get(0) + "|30").contains("Admin (CodeWalnut) and Leader");
        mvc.perform(get("/api/v1/network/tracks/" + track).with(user(ADMIN)))
            .andExpect(jsonPath("$.detail.track.stage").value("CALL_SCHEDULED"))
            .andExpect(jsonPath("$.detail.track.callAt").value(Matchers.notNullValue()))
            .andExpect(jsonPath("$.next.task").value("CALL_REMINDER"))
            .andExpect(jsonPath("$.detail.touches[0].summary").value(Matchers.startsWith("Booked a call on your page for")));
        mvc.perform(post("/p/" + token + "/book").contentType(MediaType.APPLICATION_JSON).content("{\"start\": \"" + later.get(1) + "\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.problems[0]").value(Matchers.containsString("already have a call booked")));

        // After the call time, Today asks how it went.
        jdbc.sql("UPDATE network_track SET call_at = :t, call_on = :d, next_action_on = :d WHERE id = :id")
            .param("t", java.sql.Timestamp.from(Instant.now().minusSeconds(600))).param("d", today).param("id", track).update();
        mvc.perform(get("/api/v1/network/today").with(user(ADMIN)))
            .andExpect(jsonPath("$.items[0].task").value("CALL_OUTCOME"));
    }
}
