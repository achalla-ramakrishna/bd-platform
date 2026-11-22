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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.codewalnut.bd.TestData;
import com.codewalnut.bd.admin.UserDirectory;
import com.codewalnut.bd.assist.CallPrepWriter;
import com.codewalnut.bd.engagement.Conversations;
import com.codewalnut.bd.network.NetworkService;
import com.codewalnut.bd.shared.CurrentUser;
import com.codewalnut.bd.shared.Role;
import com.jayway.jsonpath.JsonPath;

/** The ops agent (ADR-0026): call prep when a call is booked, and the opportunity from its draft. Claude is a stub. */
@SpringBootTest
@ActiveProfiles("test")
class CallPrepIntegrationTest {

    static final AtomicReference<CallPrepWriter.Request> SEEN = new AtomicReference<>();
    static final AtomicInteger CALLS = new AtomicInteger();

    @TestConfiguration
    static class Stub {
        @Bean
        @Primary
        CallPrepWriter stubCallPrepWriter() {
            return new CallPrepWriter() {
                public boolean enabled() {
                    return true;
                }

                public Result write(Request r) {
                    CALLS.incrementAndGet();
                    SEEN.set(r);
                    return new Result(new Prep("Understand the platform plans", "Heads engineering", List.of(new Need("Hiring Java engineers", "Our records")),
                        "They replied and booked a call.", List.of("What does success look like this year?"),
                        List.of(new Concern("Cost", "Flexible short engagements")), "Propose a scoping session"),
                        new Draft("YES", "Java team for the platform", "STAFF_AUGMENTATION", "Senior Java engineers for the platform build.",
                            List.of("Java")), "stub");
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
    @Autowired CallPrepService preps;

    MockMvc mvc;
    CurrentUser admin;
    long account;
    long contact;
    long conversation;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        TestData.clear(jdbc);
        CALLS.set(0);
        admin = users.recordSignIn(ADMIN, "Admin Person");
        users.setRoles(users.recordSignIn(DELIVERY, "Delivery Person").id(), EnumSet.of(Role.DELIVERY), admin.id());
        jdbc.sql("INSERT INTO parent_company (name) VALUES ('Alpha Corp')").update();
        jdbc.sql("""
                INSERT INTO account (parent_company_id, name, city, website_domain, relationship, owner_user_id, source)
                SELECT id, 'Alpha Bengaluru', 'Bengaluru', 'alpha-gcc.test', 'PROSPECT', :o, 'test' FROM parent_company
                """).param("o", admin.id()).update();
        account = jdbc.sql("SELECT id FROM account").query(Long.class).single();
        jdbc.sql("""
                INSERT INTO contact (account_id, full_name, title, persona, source, consent_basis)
                VALUES (:a, 'Person One', 'Head of Engineering', 'CENTRE_HEAD', 'test', 'BUSINESS_CONTACT')
                """).param("a", account).update();
        contact = jdbc.sql("SELECT id FROM contact").query(Long.class).single();
        conversation = conversations.startFromLinkedInNote(account, contact, "Saw the platform build", LocalDate.now(), admin);
    }

    CallPrepService.View waitDone(java.util.function.Supplier<java.util.Optional<CallPrepService.View>> find) throws Exception {
        for (int i = 0; i < 100; i++) {
            var v = find.get();
            if (v.isPresent() && !"RUNNING".equals(v.get().status())) {
                return v.get();
            }
            Thread.sleep(50);
        }
        throw new AssertionError("call prep did not finish");
    }

    @Test
    void aBookedMeetingIsPreparedAndItsDraftBecomesAnOpportunityOnOneClick() throws Exception {
        mvc.perform(get("/api/v1/call-prep/conversation/" + conversation).with(user(ADMIN))).andExpect(status().isNoContent());
        LocalDate call = LocalDate.now().plusDays(3);
        conversations.logTouch(conversation, "LINKEDIN_MESSAGE", LocalDate.now(), "They agreed to a call", "MEETING_BOOKED", "Hold the call",
            call, admin);
        var v = waitDone(() -> preps.forConversation(conversation));
        assertThat(v.status()).isEqualTo("DONE");
        assertThat(v.callOn()).isEqualTo(call);
        assertThat(v.prep().questions()).containsExactly("What does success look like this year?");
        assertThat(SEEN.get().person().person().name()).isEqualTo("Person One");
        assertThat(SEEN.get().person().history()).anyMatch(h -> h.contains("They agreed to a call"));

        mvc.perform(get("/api/v1/call-prep").with(user(ADMIN))).andExpect(jsonPath("$[0].draft.name").value("Java team for the platform"));
        mvc.perform(get("/api/v1/call-prep").with(user(DELIVERY))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/call-prep/" + v.id() + "/opportunity").with(user(DELIVERY)).with(csrf())).andExpect(status().isForbidden());

        // The owner edits the name, then creates it: at the account, the contact as decision maker, linked to the conversation.
        String r = mvc.perform(post("/api/v1/call-prep/" + v.id() + "/opportunity").with(user(ADMIN)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Java pod for the platform\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long opportunity = ((Number) JsonPath.read(r, "$.opportunityId")).longValue();
        assertThat(jdbc.sql("SELECT name FROM opportunity WHERE id = :id AND account_id = :a AND decision_maker_contact_id = :c")
            .param("id", opportunity).param("a", account).param("c", contact).query(String.class).single()).isEqualTo("Java pod for the platform");
        assertThat(jdbc.sql("SELECT opportunity_id FROM conversation WHERE id = :id").param("id", conversation).query(Long.class).single())
            .isEqualTo(opportunity);
        mvc.perform(post("/api/v1/call-prep/" + v.id() + "/opportunity").with(user(ADMIN)).with(csrf())).andExpect(status().isConflict());

        // Asking again without a change reuses it; "again" writes a new one.
        mvc.perform(post("/api/v1/call-prep").with(user(ADMIN)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"conversationId\":" + conversation + "}")).andExpect(jsonPath("$.id").value(v.id()));
        assertThat(CALLS.get()).isEqualTo(1);
        mvc.perform(post("/api/v1/call-prep").with(user(ADMIN)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"conversationId\":" + conversation + ",\"again\":true}")).andExpect(status().isAccepted());
        waitDone(() -> preps.forConversation(conversation).filter(x -> x.id() != v.id()));
        assertThat(CALLS.get()).isEqualTo(2);
    }

    @Test
    void aGtnCallIsPreparedTooButNeedsAnAccountForTheOpportunity() throws Exception {
        jdbc.sql("INSERT INTO connection (full_name, current_company, created_by) VALUES ('Friend Two', 'Beta Labs', :u)").param("u", admin.id()).update();
        long connection = jdbc.sql("SELECT id FROM connection").query(Long.class).single();
        long track = network.start(new NetworkService.Start(connection, "LINKEDIN", "Long time! Coffee?", null, network.today()), admin);
        network.act(track, new NetworkService.Action("REPLIED", "LINKEDIN", "Happy to", null, "POSITIVE", null, null, null, null, null, null,
            null, null, null), admin);
        network.act(track, new NetworkService.Action("CALL_BOOKED", null, null, null, null, null, null, null, network.today().plusDays(2), null,
            null, null, null, null), admin);
        var v = waitDone(() -> preps.forTrack(track));
        assertThat(v.status()).isEqualTo("DONE");
        assertThat(v.callOn()).isEqualTo(network.today().plusDays(2));
        mvc.perform(get("/api/v1/call-prep/track/" + track).with(user(ADMIN))).andExpect(jsonPath("$.prep.goal").value("Understand the platform plans"));
        mvc.perform(post("/api/v1/call-prep/" + v.id() + "/opportunity").with(user(ADMIN)).with(csrf()))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.problems[0]").value(Matchers.containsString("to an account first")));
    }
}
