package com.codewalnut.bd.prospecting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.codewalnut.bd.TestData;
import com.codewalnut.bd.admin.UserDirectory;
import com.codewalnut.bd.assist.ReplyReader;
import com.codewalnut.bd.engagement.Conversations;
import com.codewalnut.bd.network.NetworkService;
import com.codewalnut.bd.shared.CurrentUser;
import com.codewalnut.bd.shared.Role;
import com.jayway.jsonpath.JsonPath;

/** The reply agent (ADR-0024). Claude is a stub that sorts by keywords; people are fictional. */
@SpringBootTest
@ActiveProfiles("test")
class ReplyAgentIntegrationTest {

    static final AtomicReference<ReplyReader.Request> SEEN = new AtomicReference<>();

    @TestConfiguration
    static class Stub {
        @Bean
        @Primary
        ReplyReader stubReplyReader() {
            return new ReplyReader() {
                public boolean enabled() {
                    return true;
                }

                public Reading read(Request r) {
                    SEEN.set(r);
                    String t = r.reply().toLowerCase();
                    if (t.contains("stop")) {
                        return new Reading("UNSUBSCRIBE", "HIGH", "Asked us to stop", null, null, null, null, null, "stub");
                    }
                    if (t.contains("next quarter")) {
                        return new Reading("NOT_NOW", "HIGH", "Busy until next quarter", r.today().plusMonths(3), null, null,
                            "Check back next quarter", "Thanks, I'll check back then.", "stub");
                    }
                    return new Reading("INTERESTED", "HIGH", "Wants to talk", null, null, null, "Book a call",
                        "Great, does Tuesday or Wednesday suit you?", "stub");
                }
            };
        }
    }

    static final String ADMIN = "admin@example-gcc.test";
    static final String DELIVERY = "delivery.person@example-gcc.test";

    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    @Autowired UserDirectory users;
    @Autowired Conversations conversations;
    @Autowired NetworkService network;

    MockMvc mvc;
    CurrentUser admin;
    long contact;
    long conversation;
    long connection;
    long track;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        TestData.clear(jdbc);
        admin = users.recordSignIn(ADMIN, "Admin Person");
        users.setRoles(users.recordSignIn(DELIVERY, "Delivery Person").id(), EnumSet.of(Role.DELIVERY), admin.id());
        jdbc.sql("INSERT INTO parent_company (name) VALUES ('Alpha Corp')").update();
        jdbc.sql("""
                INSERT INTO account (parent_company_id, name, city, website_domain, relationship, owner_user_id, source)
                SELECT id, 'Alpha Bengaluru', 'Bengaluru', 'alpha-gcc.test', 'PROSPECT', :o, 'test' FROM parent_company
                """).param("o", admin.id()).update();
        long account = jdbc.sql("SELECT id FROM account").query(Long.class).single();
        jdbc.sql("""
                INSERT INTO contact (account_id, full_name, title, persona, source, consent_basis)
                VALUES (:a, 'Person One', 'Head of Engineering', 'CENTRE_HEAD', 'test', 'BUSINESS_CONTACT')
                """).param("a", account).update();
        contact = jdbc.sql("SELECT id FROM contact").query(Long.class).single();
        conversation = conversations.startFromLinkedInNote(account, contact, "Saw the platform build", LocalDate.now(), admin);
        jdbc.sql("INSERT INTO connection (full_name, email, created_by) VALUES ('Friend Two', 'friend.two@example-gcc.test', :u)")
            .param("u", admin.id()).update();
        connection = jdbc.sql("SELECT id FROM connection").query(Long.class).single();
        track = network.start(new NetworkService.Start(connection, "EMAIL", "Long time! Coffee?", null, network.today()), admin);
    }

    String paste(String body) throws Exception {
        String r = mvc.perform(post("/api/v1/replies").with(user(ADMIN)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return waitRead(((Number) JsonPath.read(r, "$.id")).longValue());
    }

    String waitRead(long id) throws Exception {
        for (int i = 0; i < 100; i++) {
            String inbox = mvc.perform(get("/api/v1/replies").with(user(ADMIN))).andReturn().getResponse().getContentAsString();
            for (Object o : (java.util.List<?>) JsonPath.read(inbox, "$.waiting[?(@.id == " + id + ")]")) {
                java.util.Map<?, ?> m = (java.util.Map<?, ?>) o;
                if (!"READING".equals(m.get("status"))) {
                    return new tools.jackson.databind.json.JsonMapper().writeValueAsString(m);
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("reply not read");
    }

    @Test
    void readsAPastedReplyAndAppliesTheConfirmedKindToTheConversation() throws Exception {
        mvc.perform(post("/api/v1/replies").with(user(ADMIN)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"conversationId\":" + conversation + ",\"text\":\"Hi\",\"channel\":\"WHATSAPP\"}"))
            .andExpect(status().isUnprocessableEntity());
        String r = paste("{\"conversationId\":" + conversation + ",\"text\":\"Sounds good, let's talk\",\"channel\":\"LINKEDIN\"}");
        assertThat((String) JsonPath.read(r, "$.kind")).isEqualTo("INTERESTED");
        assertThat((String) JsonPath.read(r, "$.draftAnswer")).isEqualTo("Great, does Tuesday or Wednesday suit you?");
        assertThat(SEEN.get().ownerName()).isEqualTo("Admin Person");
        assertThat(SEEN.get().history()).anyMatch(h -> h.contains("Connection request sent"));
        long id = ((Number) JsonPath.read(r, "$.id")).longValue();

        mvc.perform(get("/api/v1/replies").with(user(DELIVERY))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/replies/" + id + "/done").with(user(ADMIN)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"answer\":\"Great. Tuesday 3pm?\",\"answerSent\":true}"))
            .andExpect(jsonPath("$.status").value("DONE")).andExpect(jsonPath("$.finalKind").value("INTERESTED"))
            .andExpect(jsonPath("$.sentAnswer").value("Great. Tuesday 3pm?"));
        assertThat(jdbc.sql("SELECT status FROM conversation WHERE id = :id").param("id", conversation).query(String.class).single())
            .isEqualTo("REPLIED");
        assertThat(jdbc.sql("SELECT next_step FROM conversation WHERE id = :id").param("id", conversation).query(String.class).single())
            .isEqualTo("Book a call");
        assertThat(jdbc.sql("SELECT summary FROM activity WHERE conversation_id = :id ORDER BY id").param("id", conversation)
            .query(String.class).list()).hasSize(3).last().asString().isEqualTo("We answered: Great. Tuesday 3pm?");
        mvc.perform(post("/api/v1/replies/" + id + "/done").with(user(ADMIN)).with(csrf())).andExpect(status().isConflict());
        mvc.perform(get("/api/v1/replies").with(user(ADMIN)))
            .andExpect(jsonPath("$.waiting.length()").value(0))
            .andExpect(jsonPath("$.recent[0].id").value(id))
            .andExpect(jsonPath("$.last30Days.confirmed").value(1));
    }

    @Test
    void anUnsubscribeOptsThePersonOutAtOnceAndCanBeUndone() throws Exception {
        String r = paste("{\"trackId\":" + track + ",\"text\":\"Please stop messaging me.\",\"channel\":\"LINKEDIN\"}");
        long id = ((Number) JsonPath.read(r, "$.id")).longValue();
        assertThat((Boolean) JsonPath.read(r, "$.optedOut")).isTrue();
        assertThat(jdbc.sql("SELECT opted_out FROM connection WHERE id = :id").param("id", connection).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT next_action FROM network_track WHERE id = :id").param("id", track).query(String.class).single())
            .startsWith("They asked not to be contacted");

        mvc.perform(post("/api/v1/replies/" + id + "/undo-opt-out").with(user(ADMIN)).with(csrf()))
            .andExpect(jsonPath("$.optedOut").value(false));
        assertThat(jdbc.sql("SELECT opted_out FROM connection WHERE id = :id").param("id", connection).query(Boolean.class).single()).isFalse();

        // Confirmed as an unsubscribe after all: opted out again, and the GTN track stops.
        mvc.perform(post("/api/v1/replies/" + id + "/done").with(user(ADMIN)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"kind\":\"UNSUBSCRIBE\"}")).andExpect(jsonPath("$.status").value("DONE"));
        assertThat(jdbc.sql("SELECT opted_out FROM connection WHERE id = :id").param("id", connection).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM network_track WHERE id = :id").param("id", track).query(Integer.class).single()).isZero();
    }

    @Test
    void readsGmailRepliesOnItsOwnAndConfirmsThemOnTheTrack() throws Exception {
        network.emailReply(track, "gmail-msg-1", "gmail-thread-1", "Busy until next quarter, ping me then", network.today());
        Long id = null;
        for (int i = 0; i < 100 && id == null; i++) {
            id = jdbc.sql("SELECT id FROM prospect_reply WHERE gmail_message_id = 'gmail-msg-1' AND status = 'READY'")
                .query(Long.class).optional().orElse(null);
            Thread.sleep(50);
        }
        assertThat(id).isNotNull();
        // A second notice of the same Gmail message is ignored.
        network.emailReply(track, "gmail-msg-1", "gmail-thread-1", "Busy until next quarter", network.today());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM prospect_reply").query(Integer.class).single()).isEqualTo(1);

        mvc.perform(post("/api/v1/replies/" + id + "/done").with(user(ADMIN)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{}")).andExpect(jsonPath("$.finalKind").value("NOT_NOW"));
        assertThat(jdbc.sql("SELECT stage FROM network_track WHERE id = :id").param("id", track).query(String.class).single())
            .isEqualTo("MONTHLY_BEEP");
        assertThat(jdbc.sql("SELECT next_action_on FROM network_track WHERE id = :id").param("id", track).query(LocalDate.class).single())
            .isEqualTo(network.today().plusMonths(3));
        assertThat(jdbc.sql("SELECT reply_check FROM network_track WHERE id = :id").param("id", track).query(Boolean.class).single()).isFalse();
    }
}
