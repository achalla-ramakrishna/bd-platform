package com.codewalnut.bd.engagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.EnumSet;

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

/** Conversations: start, update, due list, status rules and linking an opportunity. Fictional data. */
@SpringBootTest
@ActiveProfiles("test")
class ConversationIntegrationTest {

    static final String ADMIN = "admin@example-gcc.test";
    static final String DELIVERY = "delivery.person@example-gcc.test";
    static final LocalDate TODAY = LocalDate.now();

    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    @Autowired UserDirectory users;

    MockMvc mvc;
    long alpha;
    long beta;
    long contact;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        TestData.clear(jdbc);
        long admin = users.recordSignIn(ADMIN, "Admin Person").id();
        users.setRoles(users.recordSignIn(DELIVERY, "Delivery Person").id(), EnumSet.of(Role.DELIVERY), admin);
        jdbc.sql("INSERT INTO parent_company (name) VALUES ('Alpha Corp'), ('Beta Group')").update();
        jdbc.sql("""
                INSERT INTO account (parent_company_id, name, city, website_domain, relationship, owner_user_id, source)
                SELECT id, CONCAT(name, ' Bengaluru'), 'Bengaluru', CONCAT(LOWER(SUBSTRING_INDEX(name, ' ', 1)), '-gcc.test'),
                    'PROSPECT', :owner, 'test' FROM parent_company
                """).param("owner", admin).update();
        alpha = jdbc.sql("SELECT id FROM account WHERE website_domain = 'alpha-gcc.test'").query(Long.class).single();
        beta = jdbc.sql("SELECT id FROM account WHERE website_domain = 'beta-gcc.test'").query(Long.class).single();
        jdbc.sql("""
                INSERT INTO contact (account_id, full_name, title, persona, source, consent_basis)
                VALUES (:a, 'Person One', 'Engineering Director', 'ENGINEERING_LEADER', 'test', 'BUSINESS_CONTACT')
                """).param("a", alpha).update();
        contact = jdbc.sql("SELECT id FROM contact").query(Long.class).single();
    }

    ResultActions start(String json) throws Exception {
        return mvc.perform(post("/api/v1/conversations").with(user(ADMIN)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    long startWithContact(LocalDate due) throws Exception {
        String body = start("""
                {"accountId": %d, "contactId": %d, "topic": "React team for checkout", "channel": "LINKEDIN_MESSAGE",
                 "occurredOn": "%s", "summary": "Sent a note about their React hiring", "outcome": "NO_REPLY",
                 "nextStep": "Follow up on reply", "nextStepDue": "%s"}
                """.formatted(alpha, contact, TODAY, due))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.conversation.id")).longValue();
    }

    @Test
    void startsWithAFirstTouchThatAlsoShowsInTheActivityLog() throws Exception {
        long id = startWithContact(TODAY.plusDays(3));
        mvc.perform(get("/api/v1/conversations/" + id).with(user(DELIVERY)))
            .andExpect(jsonPath("$.conversation.status").value("STARTED"))
            .andExpect(jsonPath("$.conversation.personName").value("Person One"))
            .andExpect(jsonPath("$.conversation.personTitle").value("Engineering Director"))
            .andExpect(jsonPath("$.conversation.ownerName").value("Admin Person"))
            .andExpect(jsonPath("$.updates.length()").value(1))
            .andExpect(jsonPath("$.updates[0].conversationId").value(id));
        mvc.perform(get("/api/v1/activities?accountId=" + alpha).with(user(DELIVERY)))
            .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void aPersonCanBeNamedWithoutAContactRecord() throws Exception {
        start("""
                {"accountId": %d, "personName": "Person Two", "topic": "Intro at a meetup", "channel": "EVENT",
                 "occurredOn": "%s", "summary": "Met at a meetup", "outcome": "REPLIED"}
                """.formatted(beta, TODAY))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.conversation.personName").value("Person Two"))
            .andExpect(jsonPath("$.conversation.status").value("REPLIED"));
    }

    @Test
    void startIsCheckedAndSavesNothingWhenWrong() throws Exception {
        start("""
                {"accountId": %d, "topic": " ", "nextStep": "Call", "channel": "CALL", "occurredOn": "%s",
                 "summary": "x", "outcome": "DONE"}
                """.formatted(alpha, TODAY))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.problems.length()").value(3));
        // A contact from another account is refused by the activity rules, and the conversation is rolled back.
        start("""
                {"accountId": %d, "contactId": %d, "topic": "Wrong account", "channel": "CALL", "occurredOn": "%s",
                 "summary": "x", "outcome": "DONE"}
                """.formatted(beta, contact, TODAY))
            .andExpect(status().isUnprocessableEntity());
        start("{\"accountId\": 999999, \"personName\": \"X\", \"topic\": \"Y\"}")
            .andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM conversation").query(Integer.class).single()).isZero();
    }

    @Test
    void updatesMoveTheStatusAndNextStepAndStaleOnesAreRefused() throws Exception {
        long id = startWithContact(TODAY.plusDays(3));
        String update = """
                {"channel": "CALL", "occurredOn": "%s", "summary": "Agreed to meet on Friday", "outcome": "MEETING_BOOKED",
                 "nextStep": "Show and tell", "nextStepDue": "%s", "version": 0}
                """.formatted(TODAY, TODAY.plusDays(4));
        mvc.perform(post("/api/v1/conversations/" + id + "/updates").with(user(ADMIN)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(update))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.conversation.status").value("MEETING_BOOKED"))
            .andExpect(jsonPath("$.conversation.nextStep").value("Show and tell"))
            .andExpect(jsonPath("$.conversation.lastSummary").value("Agreed to meet on Friday"))
            .andExpect(jsonPath("$.updates.length()").value(2));
        mvc.perform(post("/api/v1/conversations/" + id + "/updates").with(user(ADMIN)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(update))
            .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/conversations/" + id + "/updates").with(user(DELIVERY)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(update.replace("\"version\": 0", "\"version\": 1")))
            .andExpect(status().isForbidden());
    }

    @Test
    void theDueListShowsOverdueAndThisWeeksOpenNextSteps() throws Exception {
        long overdue = startWithContact(TODAY.minusDays(2));
        long soon = startWithContact(TODAY.plusDays(5));
        startWithContact(TODAY.plusDays(30));
        long quiet = startWithContact(TODAY.minusDays(9));
        mvc.perform(patch("/api/v1/conversations/" + quiet).with(user(ADMIN)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\": \"GONE_QUIET\", \"version\": 0}"))
            .andExpect(status().isOk());

        mvc.perform(get("/api/v1/conversations?due=overdue").with(user(DELIVERY)))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].id").value(overdue));
        mvc.perform(get("/api/v1/conversations?due=week").with(user(DELIVERY)))
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[1].id").value(soon));
        mvc.perform(get("/api/v1/conversations").with(user(DELIVERY)))
            .andExpect(jsonPath("$.items.length()").value(4))
            .andExpect(jsonPath("$.items[3].id").value(quiet))
            .andExpect(jsonPath("$.summary.open").value(3))
            .andExpect(jsonPath("$.summary.overdue").value(1))
            .andExpect(jsonPath("$.summary.dueThisWeek").value(2))
            .andExpect(jsonPath("$.summary.goneQuiet").value(1));
        mvc.perform(get("/api/v1/conversations?mine=true").with(user(DELIVERY)))
            .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void linkingAnOpportunityMarksTheConversationAndChecksTheAccount() throws Exception {
        long id = startWithContact(TODAY.plusDays(3));
        long owner = users.findId(ADMIN).orElseThrow();
        jdbc.sql("""
                INSERT INTO opportunity (account_id, name, opportunity_type, owner_user_id, stage_code, probability)
                VALUES (:a, 'Checkout team', 'STAFF_AUGMENTATION', :o,
                    (SELECT code FROM opportunity_stage ORDER BY sort_order LIMIT 1), 10)
                """).param("a", beta).param("o", owner).update();
        long opp = jdbc.sql("SELECT id FROM opportunity").query(Long.class).single();
        mvc.perform(patch("/api/v1/conversations/" + id).with(user(ADMIN)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"opportunityId\": " + opp + ", \"version\": 0}"))
            .andExpect(status().isUnprocessableEntity());
        jdbc.sql("UPDATE opportunity SET account_id = :a").param("a", alpha).update();
        mvc.perform(patch("/api/v1/conversations/" + id).with(user(ADMIN)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"opportunityId\": " + opp + ", \"version\": 0}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.conversation.status").value("OPPORTUNITY"))
            .andExpect(jsonPath("$.conversation.opportunityName").value("Checkout team"));
    }
}
