package com.codewalnut.bd.prospecting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.EnumSet;
import java.util.List;

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
import com.codewalnut.bd.assist.ProspectResearcher;
import com.codewalnut.bd.reporting.ProspectContextQueries;
import com.codewalnut.bd.shared.Role;
import com.jayway.jsonpath.JsonPath;

/** The daily prospecting queue (ADR-0023). Claude is a stub; people are fictional. */
@SpringBootTest
@ActiveProfiles("test")
class ProspectingQueueIntegrationTest {

    @TestConfiguration
    static class Stub {
        @Bean
        @Primary
        ProspectResearcher stubQueueResearcher() {
            return new ProspectResearcher() {
                public boolean enabled() {
                    return true;
                }

                public Result research(ProspectContextQueries.Context context) {
                    return new Result(new Brief("Head of platform", "Platform", "Leads the platform.", List.of(), null, List.of(),
                        "Hello from the stub", List.of(), 4, "Senior", 3, "Steady"), List.of(), "stub-model");
                }
            };
        }
    }

    static final String ADMIN = "admin@example-gcc.test";
    static final String SALES = "sales.person@example-gcc.test";
    static final String DELIVERY = "delivery.person@example-gcc.test";

    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    @Autowired UserDirectory users;
    @Autowired QueueService queue;

    MockMvc mvc;
    long admin;
    long account;
    long strong;
    long weak;
    long unresearched;
    long optedOut;
    long others;
    long friend;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        TestData.clear(jdbc);
        admin = users.recordSignIn(ADMIN, "Admin Person").id();
        long sales = users.recordSignIn(SALES, "Sales Person").id();
        users.setRoles(sales, EnumSet.of(Role.ACCOUNT_EXECUTIVE), admin);
        users.setRoles(users.recordSignIn(DELIVERY, "Delivery Person").id(), EnumSet.of(Role.DELIVERY), admin);
        jdbc.sql("INSERT INTO parent_company (name) VALUES ('Alpha Corp')").update();
        long parent = jdbc.sql("SELECT id FROM parent_company").query(Long.class).single();
        jdbc.sql("""
                INSERT INTO account (parent_company_id, name, city, website_domain, relationship, owner_user_id, source)
                VALUES (:p, 'Alpha Bengaluru', 'Bengaluru', 'alpha-gcc.test', 'PROSPECT', :a, 'test'),
                       (:p, 'Alpha Pune', 'Pune', 'alpha-pune.test', 'PROSPECT', :s, 'test')
                """).param("p", parent).param("a", admin).param("s", sales).update();
        account = jdbc.sql("SELECT id FROM account WHERE name = 'Alpha Bengaluru'").query(Long.class).single();
        long pune = jdbc.sql("SELECT id FROM account WHERE name = 'Alpha Pune'").query(Long.class).single();
        jdbc.sql("""
                INSERT INTO contact (account_id, full_name, title, persona, source, consent_basis, opted_out, linkedin_url)
                VALUES (:a, 'Person Strong', 'Head of Engineering', 'CENTRE_HEAD', 'test', 'BUSINESS_CONTACT', FALSE, 'https://www.linkedin.com/in/example-strong'),
                       (:a, 'Person Weak', 'Engineer', 'ARCHITECT', 'test', 'BUSINESS_CONTACT', FALSE, NULL),
                       (:a, 'Person New', 'Director', 'CENTRE_HEAD', 'test', 'BUSINESS_CONTACT', FALSE, NULL),
                       (:a, 'Person Out', 'Director', 'CENTRE_HEAD', 'test', 'BUSINESS_CONTACT', TRUE, NULL),
                       (:p, 'Person Elsewhere', 'Director', 'CENTRE_HEAD', 'test', 'BUSINESS_CONTACT', FALSE, NULL)
                """).param("a", account).param("p", pune).update();
        strong = contact("Person Strong");
        weak = contact("Person Weak");
        unresearched = contact("Person New");
        optedOut = contact("Person Out");
        others = contact("Person Elsewhere");
        jdbc.sql("INSERT INTO connection (full_name, current_company, current_role, created_by) VALUES ('Friend Four', 'Beta Labs', 'CTO', :u)")
            .param("u", admin).update();
        friend = jdbc.sql("SELECT id FROM connection").query(Long.class).single();
        brief("CONTACT", strong, account, 88, "Saw the platform build; open to a chat?");
        brief("CONTACT", weak, account, 40, "Low score");
        brief("CONTACT", others, pune, 90, "Not admin's");
        brief("CONNECTION", friend, null, 70, "Been a while! Coffee?");
    }

    long contact(String name) {
        return jdbc.sql("SELECT id FROM contact WHERE full_name = :n").param("n", name).query(Long.class).single();
    }

    void brief(String type, long id, Long accountId, int score, String note) {
        jdbc.sql("""
                INSERT INTO prospect_brief (person_type, person_id, account_id, status, brief, sources, role_fit, timing, access, score,
                    requested_by, finished_at)
                VALUES (:t, :id, :a, 'DONE', JSON_OBJECT('headline', 'A headline', 'connectionNote', :note, 'roleFit', 4, 'timing', 4,
                    'whyNow', JSON_ARRAY(JSON_OBJECT('statement', 'Hiring', 'source', 'Our records'))), '[]', 4, 4, 2, :s, :u, NOW())
                """).param("t", type).param("id", id).param("a", accountId).param("note", note).param("s", score).param("u", admin).update();
    }

    @Test
    void queuesTheBestPeopleICanApproachAndTurnsSentNotesIntoFollowUps() throws Exception {
        String body = mvc.perform(get("/api/v1/prospecting/queue").with(user(ADMIN))).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        // Best first; not the low score, not someone else's account, not the opted-out or unresearched.
        assertThat((List<String>) JsonPath.read(body, "$.items[*].name")).containsExactly("Person Strong", "Friend Four");
        assertThat((String) JsonPath.read(body, "$.items[0].draftNote")).isEqualTo("Saw the platform build; open to a chat?");
        assertThat((String) JsonPath.read(body, "$.items[0].whyNow")).isEqualTo("Hiring");
        long contactItem = ((Number) JsonPath.read(body, "$.items[0].id")).longValue();
        long friendItem = ((Number) JsonPath.read(body, "$.items[1].id")).longValue();

        // Someone else's queue holds their own account's people; delivery has no queue.
        mvc.perform(get("/api/v1/prospecting/queue").with(user(SALES))).andExpect(jsonPath("$.items[*].name").value(org.hamcrest.Matchers.contains("Person Elsewhere")));
        mvc.perform(get("/api/v1/prospecting/queue").with(user(DELIVERY))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/prospecting/queue/" + contactItem + "/skip").with(user(SALES)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"NOT_NOW\"}")).andExpect(status().isForbidden());

        // Sent, with an edit: a conversation starts with a reminder to check for a reply.
        mvc.perform(post("/api/v1/prospecting/queue/" + contactItem + "/sent").with(user(ADMIN)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Saw the platform build. Coffee next week?\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SENT"));
        assertThat(jdbc.sql("SELECT next_step FROM conversation WHERE contact_id = :c").param("c", strong).query(String.class).single())
            .isEqualTo("Check for a reply");
        mvc.perform(post("/api/v1/prospecting/queue/" + contactItem + "/sent").with(user(ADMIN)).with(csrf()))
            .andExpect(status().isConflict());

        // Skipped, with a reason.
        mvc.perform(post("/api/v1/prospecting/queue/" + friendItem + "/skip").with(user(ADMIN)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"maybe\"}")).andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/v1/prospecting/queue/" + friendItem + "/skip").with(user(ADMIN)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"WRONG_PERSON\",\"note\":\"Moved on\"}"))
            .andExpect(jsonPath("$.status").value("SKIPPED"));

        mvc.perform(get("/api/v1/prospecting/queue").with(user(ADMIN)))
            .andExpect(jsonPath("$.last30Days.sent").value(1))
            .andExpect(jsonPath("$.last30Days.edited").value(1))
            .andExpect(jsonPath("$.last30Days.skipped").value(1))
            .andExpect(jsonPath("$.last30Days.skipReasons.WRONG_PERSON").value(1));

        // A sent connection note starts a GTN track instead (checked on a fresh connection tomorrow).
        jdbc.sql("INSERT INTO connection (full_name, created_by) VALUES ('Friend Five', :u)").param("u", admin).update();
        long five = jdbc.sql("SELECT id FROM connection WHERE full_name = 'Friend Five'").query(Long.class).single();
        brief("CONNECTION", five, null, 60, "Hi Five");
        assertThat(queue.fill(admin, queue.today().plusDays(1))).isEqualTo(1);
        long fiveItem = jdbc.sql("SELECT id FROM prospect_queue_item WHERE person_id = :p AND person_type = 'CONNECTION'")
            .param("p", five).query(Long.class).single();
        mvc.perform(post("/api/v1/prospecting/queue/" + fiveItem + "/sent").with(user(ADMIN)).with(csrf()))
            .andExpect(jsonPath("$.sentNote").value("Hi Five"));
        assertThat(jdbc.sql("SELECT stage FROM network_track WHERE connection_id = :c").param("c", five).query(String.class).single())
            .isEqualTo("INITIATE");
    }

    @Test
    void researchesPeopleWithoutABriefAndExpiresYesterdaysQueue() throws Exception {
        String r = mvc.perform(post("/api/v1/prospecting/research").with(user(ADMIN)).with(csrf()))
            .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        // Only Person New: the others have briefs, and Person Out asked not to be contacted.
        assertThat((Integer) JsonPath.read(r, "$.started")).isEqualTo(1);
        for (int i = 0; i < 100 && jdbc.sql("SELECT COUNT(*) FROM prospect_brief WHERE person_id = :p AND status = 'DONE'")
                .param("p", unresearched).query(Integer.class).single() == 0; i++) {
            Thread.sleep(50);
        }
        assertThat(jdbc.sql("SELECT person_id FROM prospect_brief WHERE status <> 'DONE' OR score IS NULL").query(Long.class).list()).isEmpty();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM prospect_brief WHERE person_id = :p").param("p", optedOut).query(Integer.class).single()).isZero();

        queue.fill(admin, queue.today());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM prospect_queue_item WHERE user_id = :u").param("u", admin).query(Integer.class).single())
            .isEqualTo(3); // Strong, Friend Four, and Person New (score 4/3/1 → 64)
        queue.fill(admin, queue.today().plusDays(1));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM prospect_queue_item WHERE status = 'EXPIRED'").query(Integer.class).single()).isEqualTo(3);
        // Expired people can come back on a later day.
        assertThat(jdbc.sql("SELECT COUNT(*) FROM prospect_queue_item WHERE status = 'QUEUED'").query(Integer.class).single()).isEqualTo(3);
    }
}
