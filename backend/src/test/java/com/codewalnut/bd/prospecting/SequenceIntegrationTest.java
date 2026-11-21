package com.codewalnut.bd.prospecting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.codewalnut.bd.TestData;
import com.codewalnut.bd.admin.UserDirectory;
import com.codewalnut.bd.assist.ReplyReader;
import com.codewalnut.bd.assist.SequenceWriter;
import com.codewalnut.bd.google.GoogleApi;
import com.codewalnut.bd.shared.Role;
import com.jayway.jsonpath.JsonPath;

/** Email sequences (ADR-0025). Google and Claude are stubs; people and addresses are fictional. */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {"app.google.client-id=test-client", "app.google.client-secret=test-secret",
    "app.google.token-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=", "app.agent.email.sending=true",
    "app.walnut.base-url=https://bd.example-gcc.test",
    // Every day, so the test runs on any day; production sends Monday to Friday.
    "app.agent.email.days=MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY,SUNDAY"})
class SequenceIntegrationTest {

    static final String ADMIN = "admin@example-gcc.test";
    static final String SALES = "sales.person@example-gcc.test";
    static final String DELIVERY = "delivery.person@example-gcc.test";
    static final List<String> SENT = new CopyOnWriteArrayList<>();
    static final List<String> THREADS = new CopyOnWriteArrayList<>();
    static volatile List<GoogleApi.Message> thread = List.of();

    @TestConfiguration
    static class Stubs {
        @Bean
        @Primary
        GoogleApi stubSequenceGoogle() {
            return new GoogleApi() {
                public Tokens exchange(String code, String redirectUri) {
                    return new Tokens("access-1", "refresh-1", 3600, "openid email https://www.googleapis.com/auth/gmail.send "
                        + "https://www.googleapis.com/auth/gmail.metadata", ADMIN);
                }

                public Tokens refresh(String refreshToken) {
                    return new Tokens("access-2", refreshToken, 3600, null, null);
                }

                public void revoke(String token) {
                }

                public Sent send(String accessToken, String raw, String threadId) {
                    SENT.add(new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8));
                    THREADS.add(String.valueOf(threadId));
                    return new Sent("msg-" + SENT.size(), threadId == null ? "seq-thread-1" : threadId);
                }

                public Message message(String accessToken, String id) {
                    return new Message(id, "seq-thread-1", ADMIN, "<" + id + "@mail.example-gcc.test>", 0, "", false, "A subject");
                }

                public List<Message> thread(String accessToken, String threadId) {
                    return thread;
                }

                public List<Busy> freeBusy(String accessToken, Instant from, Instant to) {
                    return List.of();
                }

                public Event createEvent(String accessToken, String summary, String description, Instant start, Instant end,
                                         String attendeeEmail, String zone) {
                    return new Event("e", null, null);
                }
            };
        }

        @Bean
        @Primary
        SequenceWriter stubWriter() {
            return new SequenceWriter() {
                public boolean enabled() {
                    return true;
                }

                public Result write(Request r) {
                    return new Result(r.steps().stream().map(s -> new Email(s.position(), "Step " + s.position() + " for " + r.company(),
                        "Hello " + r.personName() + ", this is a fictional email number " + s.position() + " from " + r.senderName() + "."))
                        .toList(), "stub");
                }
            };
        }

        @Bean
        @Primary
        ReplyReader stubSequenceReplyReader() {
            return new ReplyReader() {
                public boolean enabled() {
                    return true;
                }

                public Reading read(Request r) {
                    return new Reading("INTERESTED", "HIGH", "Wants to talk", null, null, null, "Book a call", "Great!", "stub");
                }
            };
        }
    }

    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    @Autowired UserDirectory users;
    @Autowired SequenceService sequences;

    MockMvc mvc;
    long contact;
    long other;
    long noEmail;
    long sequence;
    ZonedDateTime workday;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        TestData.clear(jdbc);
        SENT.clear();
        THREADS.clear();
        thread = List.of();
        long admin = users.recordSignIn(ADMIN, "Admin Person").id();
        users.setRoles(users.recordSignIn(SALES, "Sales Person").id(), EnumSet.of(Role.ACCOUNT_EXECUTIVE), admin);
        users.setRoles(users.recordSignIn(DELIVERY, "Delivery Person").id(), EnumSet.of(Role.DELIVERY), admin);
        jdbc.sql("INSERT INTO parent_company (name) VALUES ('Alpha Corp')").update();
        jdbc.sql("""
                INSERT INTO account (parent_company_id, name, city, website_domain, relationship, owner_user_id, source)
                SELECT id, 'Alpha Bengaluru', 'Bengaluru', 'alpha-gcc.test', 'PROSPECT', :o, 'test' FROM parent_company
                """).param("o", admin).update();
        long account = jdbc.sql("SELECT id FROM account").query(Long.class).single();
        jdbc.sql("""
                INSERT INTO contact (account_id, full_name, title, persona, source, consent_basis, email)
                VALUES (:a, 'Person One', 'Head of Engineering', 'CENTRE_HEAD', 'test', 'BUSINESS_CONTACT', 'person.one@alpha-gcc.test'),
                       (:a, 'Person Two', 'Director', 'CENTRE_HEAD', 'test', 'BUSINESS_CONTACT', 'person.two@alpha-gcc.test'),
                       (:a, 'Person Three', 'Director', 'CENTRE_HEAD', 'test', 'BUSINESS_CONTACT', NULL)
                """).param("a", account).update();
        contact = id("Person One");
        other = id("Person Two");
        noEmail = id("Person Three");
        sequence = jdbc.sql("SELECT id FROM email_sequence WHERE name = 'Introduction, three emails'").query(Long.class).single();
        connect();
        // Today at 10:00 IST, inside the sending window.
        workday = LocalDate.now(ZoneId.of("Asia/Kolkata")).atTime(LocalTime.of(10, 0)).atZone(ZoneId.of("Asia/Kolkata"));
    }

    long id(String name) {
        return jdbc.sql("SELECT id FROM contact WHERE full_name = :n").param("n", name).query(Long.class).single();
    }

    void connect() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String location = mvc.perform(get("/api/v1/google/connect").with(user(ADMIN)).session(session))
            .andReturn().getResponse().getRedirectedUrl();
        String state = location.replaceAll(".*[?&]state=([^&]+).*", "$1");
        mvc.perform(get("/api/v1/google/callback").param("code", "ok").param("state", state).with(user(ADMIN)).session(session));
    }

    long startAndApprove(long who) throws Exception {
        String r = mvc.perform(post("/api/v1/sequences").with(user(ADMIN)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"contactId\":" + who + ",\"sequenceId\":" + sequence + "}"))
            .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(r, "$.id")).longValue();
        for (int i = 0; i < 100 && !"REVIEW".equals(sequences.get(id).status()); i++) {
            Thread.sleep(50);
        }
        assertThat(sequences.get(id).status()).isEqualTo("REVIEW");
        mvc.perform(post("/api/v1/sequences/" + id + "/approve").with(user(ADMIN)).with(csrf())).andExpect(jsonPath("$.status").value("ACTIVE"));
        return id;
    }

    @Test
    void theAgentWritesTheOwnerApprovesAndTheAppSendsWithAnUnsubscribeLinkUntilTheyReply() throws Exception {
        String r = mvc.perform(post("/api/v1/sequences").with(user(ADMIN)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"contactId\":" + contact + ",\"sequenceId\":" + sequence + "}"))
            .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(r, "$.id")).longValue();
        for (int i = 0; i < 100 && !"REVIEW".equals(sequences.get(id).status()); i++) {
            Thread.sleep(50);
        }
        var e = sequences.get(id);
        assertThat(e.emails()).hasSize(3);
        assertThat(e.emails().get(0).subject()).isEqualTo("Step 1 for Alpha Bengaluru");

        // Guardrails at the start.
        mvc.perform(post("/api/v1/sequences").with(user(ADMIN)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"contactId\":" + contact + ",\"sequenceId\":" + sequence + "}"))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.problems[0]").value(Matchers.containsString("already in a sequence")));
        mvc.perform(post("/api/v1/sequences").with(user(ADMIN)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"contactId\":" + noEmail + ",\"sequenceId\":" + sequence + "}"))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.problems[0]").value(Matchers.containsString("no email address")));
        mvc.perform(get("/api/v1/sequences").with(user(DELIVERY))).andExpect(status().isForbidden());

        // The owner edits, then approves. Nothing is sent before approval.
        assertThat(sequences.sendDue(workday)).isZero();
        mvc.perform(put("/api/v1/sequences/" + id + "/emails/1").with(user(ADMIN)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"subject\":\"A quick idea\",\"body\":\"Hello Person One, an edited fictional first email from Admin.\"}"))
            .andExpect(jsonPath("$.emails[0].edited").value(true));
        mvc.perform(post("/api/v1/sequences/" + id + "/approve").with(user(ADMIN)).with(csrf())).andExpect(jsonPath("$.status").value("ACTIVE"));

        // Outside the sending window, nothing goes.
        assertThat(sequences.sendDue(workday.withHour(20))).isZero();

        assertThat(sequences.sendDue(workday)).isEqualTo(1);
        String token = jdbc.sql("SELECT unsubscribe_token FROM sequence_enrolment WHERE id = :id").param("id", id).query(String.class).single();
        String mail = SENT.get(0);
        assertThat(mail).contains("List-Unsubscribe: <https://bd.example-gcc.test/u/" + token + ">")
            .contains("List-Unsubscribe-Post: List-Unsubscribe=One-Click").contains("To: ");
        String body = new String(Base64.getMimeDecoder().decode(mail.substring(mail.indexOf("\r\n\r\n") + 4)), StandardCharsets.UTF_8);
        assertThat(body).startsWith("Hello Person One, an edited fictional first email").contains("unsubscribe here: https://bd.example-gcc.test/u/" + token);
        // Logged: the first email opened a conversation.
        assertThat(jdbc.sql("SELECT summary FROM activity WHERE contact_id = :c").param("c", contact).query(String.class).single())
            .isEqualTo("Sequence email 1 sent: A quick idea");
        assertThat(sequences.get(id).nextPosition()).isEqualTo(2);

        // Not again today; the second email waits for its days.
        assertThat(sequences.sendDue(workday)).isZero();

        // They reply in the Gmail thread: the sequence ends and the reply goes to Replies.
        thread = List.of(new GoogleApi.Message("reply-1", "seq-thread-1", "Person One <person.one@alpha-gcc.test>", "<r1@x>",
            Instant.now().toEpochMilli(), "Sounds interesting, let's talk", false, "Re: A quick idea"));
        assertThat(sequences.checkReplies()).isEqualTo(1);
        assertThat(sequences.get(id).status()).isEqualTo("REPLIED");
        assertThat(sequences.get(id).emails()).extracting(SequenceService.Email::status).containsExactly("SENT", "SKIPPED", "SKIPPED");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM prospect_reply WHERE person_id = :c AND source = 'GMAIL'").param("c", contact)
            .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void oneClickUnsubscribeStopsEverythingForGood() throws Exception {
        long id = startAndApprove(other);
        String token = jdbc.sql("SELECT unsubscribe_token FROM sequence_enrolment WHERE id = :id").param("id", id).query(String.class).single();
        // Opening the link (or a mail scanner opening it) changes nothing.
        mvc.perform(get("/u/" + token)).andExpect(status().isOk()).andExpect(content().string(Matchers.containsString("Person?")));
        assertThat(sequences.get(id).status()).isEqualTo("ACTIVE");
        mvc.perform(get("/u/not-a-token")).andExpect(status().isNotFound());
        // The button, or Gmail's one-click POST, without a CSRF token.
        mvc.perform(post("/u/" + token)).andExpect(status().isOk()).andExpect(content().string(Matchers.containsString("Unsubscribed")));
        assertThat(sequences.get(id).status()).isEqualTo("STOPPED");
        assertThat(sequences.get(id).endedReason()).isEqualTo("They unsubscribed");
        assertThat(jdbc.sql("SELECT opted_out FROM contact WHERE id = :c").param("c", other).query(Boolean.class).single()).isTrue();
        assertThat(sequences.sendDue(workday)).isZero();
        assertThat(SENT).isEmpty();
        // Even if someone clears the opt-out, the address stays suppressed.
        jdbc.sql("UPDATE contact SET opted_out = FALSE WHERE id = :c").param("c", other).update();
        mvc.perform(post("/api/v1/sequences").with(user(ADMIN)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"contactId\":" + other + ",\"sequenceId\":" + sequence + "}"))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.problems[0]").value(Matchers.containsString("do-not-email")));
    }

    @Test
    void anOptOutOrAManualReplyEndsASequenceBeforeTheNextEmail() throws Exception {
        long id = startAndApprove(contact);
        jdbc.sql("UPDATE contact SET opted_out = TRUE WHERE id = :c").param("c", contact).update();
        assertThat(sequences.sendDue(workday)).isZero();
        assertThat(sequences.get(id).status()).isEqualTo("STOPPED");
        assertThat(sequences.get(id).endedReason()).isEqualTo("They asked not to be contacted.");

        long second = startAndApprove(other);
        jdbc.sql("""
                INSERT INTO prospect_reply (owner_user_id, person_type, person_id, source, channel, reply_text, received_on, status)
                SELECT id, 'CONTACT', :c, 'PASTED', 'LINKEDIN', 'Replied on LinkedIn', CURRENT_DATE, 'READY' FROM app_user WHERE email = :e
                """).param("c", other).param("e", ADMIN).update();
        assertThat(sequences.sendDue(workday)).isZero();
        assertThat(sequences.get(second).status()).isEqualTo("REPLIED");
        assertThat(SENT).isEmpty();
    }

    @Test
    void onlyLeadsEditTemplatesAndFollowUpsWaitAtLeastThreeDays() throws Exception {
        String body = "{\"name\":\"Short\",\"active\":true,\"steps\":[{\"waitDays\":0,\"subject\":\"Hi {firstName}\",\"body\":\"Introduce us.\"},"
            + "{\"waitDays\":1,\"subject\":\"Re: Hi\",\"body\":\"Follow up.\"}]}";
        mvc.perform(post("/api/v1/sequences/templates").with(user(SALES)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/sequences/templates").with(user(ADMIN)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.problems[0]").value(Matchers.containsString("wait 3 to 60 days")));
        mvc.perform(post("/api/v1/sequences/templates").with(user(ADMIN)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body.replace("\"waitDays\":1", "\"waitDays\":5")))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.steps.length()").value(2));
    }
}
