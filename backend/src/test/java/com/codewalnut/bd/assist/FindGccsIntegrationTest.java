package com.codewalnut.bd.assist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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
import com.jayway.jsonpath.JsonPath;

/** Find GCCs and fit sorting end to end, with a stand-in finder (no network). Fictional data. */
@SpringBootTest
@ActiveProfiles("test")
class FindGccsIntegrationTest {

    static final AtomicReference<List<String>> KNOWN = new AtomicReference<>();

    @TestConfiguration
    static class StubFinder {
        @Bean
        @Primary
        GccFinder stubGccFinder() {
            return new GccFinder() {
                public boolean enabled() {
                    return true;
                }

                public Result find(String city, String industry, List<String> knownCompanies) {
                    KNOWN.set(knownCompanies);
                    return new Result(List.of(
                        new Candidate("Alpha Bengaluru", "Alpha Corp", "USA", city, "https://www.alpha-gcc.test/", "Retail", "Already ours", "https://news.example.test/a"),
                        new Candidate("Beta Tech Centre", "Beta Group", "Germany", city, "beta-gcc.test", "Automotive", "Builds car software", "https://news.example.test/b"),
                        new Candidate("Gamma Hub", "Gamma Inc", "USA", city, "gamma-gcc.test", "Banking", "Payments platform", "https://news.example.test/c"),
                        new Candidate("No Domain Co", "No Domain", "UK", city, "not a domain", "Retail", "x", "https://news.example.test/d")),
                        "test-model");
                }
            };
        }
    }

    static final String ADMIN = "admin@example-gcc.test";

    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    @Autowired UserDirectory users;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        TestData.clear(jdbc);
        long admin = users.recordSignIn(ADMIN, "Admin Person").id();
        jdbc.sql("INSERT INTO parent_company (name) VALUES ('Alpha Corp')").update();
        jdbc.sql("""
                INSERT INTO account (parent_company_id, name, city, website_domain, relationship, owner_user_id, source)
                SELECT id, 'Alpha Bengaluru', 'Bengaluru', 'alpha-gcc.test', 'PROSPECT', :owner, 'test' FROM parent_company
                """).param("owner", admin).update();
    }

    private String waitForFinish(long id) throws Exception {
        for (int i = 0; i < 100; i++) {
            String body = mvc.perform(get("/api/v1/discovery/" + id).with(user(ADMIN))).andReturn().getResponse().getContentAsString();
            if (!"RUNNING".equals(JsonPath.read(body, "$.status"))) {
                return body;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("search did not finish");
    }

    @Test
    void findsReviewsAndAddsOnlyTheChosenNewCompanies() throws Exception {
        String started = mvc.perform(post("/api/v1/discovery").with(user(ADMIN)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"city\": \"Bengaluru\", \"industry\": \"Banking\"}"))
            .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(started, "$.id")).longValue();
        String body = waitForFinish(id);

        assertThat(KNOWN.get()).containsExactly("Alpha Corp");
        assertThat((Boolean) JsonPath.read(body, "$.candidates[0].alreadyInApp")).isTrue();
        assertThat((String) JsonPath.read(body, "$.candidates[0].domain")).isEqualTo("alpha-gcc.test");
        assertThat((Boolean) JsonPath.read(body, "$.candidates[1].alreadyInApp")).isFalse();
        assertThat((Boolean) JsonPath.read(body, "$.candidates[3].validDomain")).isFalse();

        mvc.perform(post("/api/v1/discovery/" + id + "/add").with(user(ADMIN)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"domains\": [\"alpha-gcc.test\", \"beta-gcc.test\", \"not a domain\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.created").value(1))
            .andExpect(jsonPath("$.skipped").value(1))
            .andExpect(jsonPath("$.problems.length()").value(1));
        assertThat(jdbc.sql("SELECT relationship FROM account WHERE website_domain = 'beta-gcc.test'").query(String.class).single())
            .isEqualTo("PROSPECT");
        assertThat(jdbc.sql("SELECT source FROM account WHERE website_domain = 'beta-gcc.test'").query(String.class).single())
            .isEqualTo("Find GCCs: https://news.example.test/b");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM account WHERE website_domain = 'gamma-gcc.test'").query(Integer.class).single())
            .isZero();
    }

    @Test
    void accountsCanBeSortedByFitWithReasons() throws Exception {
        jdbc.sql("UPDATE account SET headcount_band = '500-2000' WHERE website_domain = 'alpha-gcc.test'").update();
        jdbc.sql("INSERT INTO parent_company (name) VALUES ('Zeta Corp')").update();
        long owner = users.findId(ADMIN).orElseThrow();
        jdbc.sql("""
                INSERT INTO account (parent_company_id, name, city, website_domain, relationship, owner_user_id, source)
                SELECT id, 'Zeta Bengaluru', 'Bengaluru', 'zeta-gcc.test', 'PROSPECT', :owner, 'test' FROM parent_company WHERE name = 'Zeta Corp'
                """).param("owner", owner).update();
        mvc.perform(get("/api/v1/accounts?sort=fit").with(user(ADMIN)))
            .andExpect(jsonPath("$.items[0].name").value("Alpha Bengaluru"))
            .andExpect(jsonPath("$.items[0].fitScore").value(20))
            .andExpect(jsonPath("$.items[1].fitScore").value(5))
            .andExpect(jsonPath("$.items[0].fitReasons[0]").value("Size 500-2000 (+20)"));
    }

    @Test
    void needsACity() throws Exception {
        mvc.perform(post("/api/v1/discovery").with(user(ADMIN)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"city\": \" \"}"))
            .andExpect(status().isUnprocessableContent());
    }
}
