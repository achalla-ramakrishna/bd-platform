package com.codewalnut.bd.assist;

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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.codewalnut.bd.TestData;
import com.codewalnut.bd.admin.UserDirectory;
import com.codewalnut.bd.reporting.ProspectContextQueries;
import com.codewalnut.bd.shared.Role;
import com.jayway.jsonpath.JsonPath;

/** The BD agent's research step (ADR-0022): briefs on people, the score, the cap. Claude is a stub; people are fictional. */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "app.agent.research-per-day=4")
class ProspectResearchIntegrationTest {

    static final AtomicReference<ProspectContextQueries.Context> SEEN = new AtomicReference<>();
    static final AtomicInteger CALLS = new AtomicInteger();
    static final AtomicReference<RuntimeException> FAIL = new AtomicReference<>();

    @TestConfiguration
    static class Stub {
        @Bean
        @Primary
        ProspectResearcher stubProspectResearcher() {
            return new ProspectResearcher() {
                public boolean enabled() {
                    return true;
                }

                public Result research(ProspectContextQueries.Context context) {
                    CALLS.incrementAndGet();
                    SEEN.set(context);
                    if (FAIL.get() != null) {
                        throw FAIL.get();
                    }
                    return new Result(new Brief("Engineering head of the payments platform", "Owns payments engineering, about 80 people",
                        "Leads the payments platform build in Bengaluru.",
                        List.of(new Signal("Hiring 30 Java engineers", "https://jobs.example.test/1")),
                        new Angle("AI pods", "A fast build-out needs senior engineers"), List.of("Their platform migration"),
                        "Saw the payments platform build. We help GCC teams ship faster; open to a chat?", List.of("Check the team size"),
                        5, "Heads the platform", 4, "Recent hiring push"),
                        List.of(new Source("https://jobs.example.test/1", "Jobs")), "stub-model");
                }
            };
        }
    }

    static final String ADMIN = "admin@example-gcc.test";
    static final String DELIVERY = "delivery.person@example-gcc.test";

    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    @Autowired UserDirectory users;

    MockMvc mvc;
    long account;
    long contact;
    long optedOut;
    long connection;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        TestData.clear(jdbc);
        jdbc.sql("DELETE FROM prospect_brief").update();
        CALLS.set(0);
        FAIL.set(null);
        long admin = users.recordSignIn(ADMIN, "Admin Person").id();
        users.setRoles(users.recordSignIn(DELIVERY, "Delivery Person").id(), EnumSet.of(Role.DELIVERY), admin);
        jdbc.sql("INSERT INTO parent_company (name) VALUES ('Alpha Corp')").update();
        jdbc.sql("""
                INSERT INTO account (parent_company_id, name, city, website_domain, relationship, owner_user_id, source)
                SELECT id, 'Alpha Bengaluru', 'Bengaluru', 'alpha-gcc.test', 'CLIENT', :o, 'test' FROM parent_company
                """).param("o", admin).update();
        account = jdbc.sql("SELECT id FROM account").query(Long.class).single();
        jdbc.sql("""
                INSERT INTO contact (account_id, full_name, title, persona, source, consent_basis, opted_out)
                VALUES (:a, 'Person One', 'Head of Engineering', 'CENTRE_HEAD', 'test', 'BUSINESS_CONTACT', FALSE),
                       (:a, 'Person Two', 'Director', 'CENTRE_HEAD', 'test', 'BUSINESS_CONTACT', TRUE),
                       (:a, 'Person Three', 'Architect', 'ARCHITECT', 'test', 'BUSINESS_CONTACT', FALSE)
                """).param("a", account).update();
        contact = jdbc.sql("SELECT id FROM contact WHERE full_name = 'Person One'").query(Long.class).single();
        optedOut = jdbc.sql("SELECT id FROM contact WHERE full_name = 'Person Two'").query(Long.class).single();
        jdbc.sql("INSERT INTO connection (full_name, current_company, created_by) VALUES ('Friend Four', 'Beta Labs', :u)").param("u", admin).update();
        connection = jdbc.sql("SELECT id FROM connection").query(Long.class).single();
        jdbc.sql("INSERT INTO connection_knower (connection_id, user_id, strength, how_known, connected_on) VALUES (:c, :u, 'CLOSE', 'Same team in 2019', :d)")
            .param("c", connection).param("u", admin).param("d", LocalDate.now().minusYears(3)).update();
    }

    String waitFor(String type, long id) throws Exception {
        for (int i = 0; i < 100; i++) {
            String body = mvc.perform(get("/api/v1/prospects/" + type + "/" + id).with(user(ADMIN))).andReturn().getResponse().getContentAsString();
            if (!body.isEmpty() && !"RUNNING".equals(JsonPath.read(body, "$.status"))) {
                return body;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("research did not finish");
    }

    @Test
    void researchesAPersonAndScoresThemFromTheAgentsRatingsAndOurAccess() throws Exception {
        mvc.perform(get("/api/v1/prospects/contact/" + contact).with(user(ADMIN))).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/prospects/contact/" + contact + "/research").with(user(DELIVERY)).with(csrf()))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/prospects/contact/" + contact + "/research").with(user(ADMIN)).with(csrf()))
            .andExpect(status().isAccepted());
        String body = waitFor("CONTACT", contact);
        // roleFit 5, timing 4 (agent); access 2: nobody knows them (1), the account is a client (+1).
        assertThat((Integer) JsonPath.read(body, "$.score")).isEqualTo(ProspectService.score(5, 4, 2)).isEqualTo(78);
        assertThat((String) JsonPath.read(body, "$.status")).isEqualTo("DONE");
        assertThat((String) JsonPath.read(body, "$.accessReason")).isEqualTo("No one in the team knows them yet; their company is a client");
        assertThat((String) JsonPath.read(body, "$.brief.whyNow[0].source")).isEqualTo("https://jobs.example.test/1");
        assertThat((String) JsonPath.read(body, "$.accountName")).isEqualTo("Alpha Bengaluru");
        assertThat(SEEN.get().person().title()).isEqualTo("Head of Engineering");
        assertThat(SEEN.get().account().account().name()).isEqualTo("Alpha Bengaluru");

        // A recent brief is reused unless asked again.
        mvc.perform(post("/api/v1/prospects/contact/" + contact + "/research").with(user(ADMIN)).with(csrf()))
            .andExpect(jsonPath("$.status").value("DONE"));
        assertThat(CALLS.get()).isEqualTo(1);

        // Someone who asked not to be contacted is never researched.
        mvc.perform(post("/api/v1/prospects/contact/" + optedOut + "/research").with(user(ADMIN)).with(csrf()))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.problems[0]").value(Matchers.containsString("asked not to be contacted")));

        // A connection someone knows closely: access 5.
        mvc.perform(post("/api/v1/prospects/connection/" + connection + "/research").with(user(ADMIN)).with(csrf()))
            .andExpect(status().isAccepted());
        String friend = waitFor("CONNECTION", connection);
        assertThat((Integer) JsonPath.read(friend, "$.access")).isEqualTo(5);
        assertThat(SEEN.get().knownBy()).containsExactly("Admin Person (close: Same team in 2019)");
        mvc.perform(get("/api/v1/prospects/someone/1").with(user(ADMIN))).andExpect(status().isNotFound());
    }

    @Test
    void researchesAnAccountsContactsWithinTheDailyCap() throws Exception {
        String batch = mvc.perform(post("/api/v1/prospects/for-account/" + account + "/research").with(user(ADMIN)).with(csrf()))
            .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(batch, "$.started")).containsExactlyInAnyOrder("Person One", "Person Three");
        waitFor("CONTACT", contact);
        mvc.perform(get("/api/v1/prospects/for-account/" + account).with(user(DELIVERY)))
            .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(post("/api/v1/prospects/for-account/" + account + "/research").with(user(ADMIN)).with(csrf()))
            .andExpect(status().isUnprocessableEntity());

        // The cap (4 a day in this test): two used; two more "again" runs, then refused.
        FAIL.set(new ResearchException("The AI is busy."));
        mvc.perform(post("/api/v1/prospects/contact/" + contact + "/research?again=true").with(user(ADMIN)).with(csrf()));
        assertThat((String) JsonPath.read(waitFor("CONTACT", contact), "$.error")).isEqualTo("The AI is busy.");
        mvc.perform(post("/api/v1/prospects/connection/" + connection + "/research").with(user(ADMIN)).with(csrf()));
        waitFor("CONNECTION", connection);
        mvc.perform(get("/api/v1/prospects/status").with(user(ADMIN))).andExpect(jsonPath("$.remainingToday").value(0));
        mvc.perform(post("/api/v1/prospects/contact/" + contact + "/research?again=true").with(user(ADMIN)).with(csrf()))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.problems[0]").value(Matchers.containsString("research limit (4)")));
    }
}
