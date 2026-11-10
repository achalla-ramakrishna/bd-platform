package com.codewalnut.bd.assist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.EnumSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.codewalnut.bd.TestData;
import com.codewalnut.bd.admin.UserDirectory;
import com.codewalnut.bd.shared.Role;

/** Uploading research written outside the app. Works with research switched off. Fictional data. */
@SpringBootTest
@ActiveProfiles("test")
class ResearchUploadIntegrationTest {

    static final String ADMIN = "admin@example-gcc.test";
    static final String DELIVERY = "delivery.person@example-gcc.test";

    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    @Autowired UserDirectory users;

    MockMvc mvc;
    long accountId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        TestData.clear(jdbc);
        long admin = users.recordSignIn(ADMIN, "Admin Person").id();
        users.setRoles(users.recordSignIn(DELIVERY, "Delivery Person").id(), EnumSet.of(Role.DELIVERY), admin);
        jdbc.sql("INSERT INTO parent_company (name, hq_country) VALUES ('Alpha Corp', 'USA')").update();
        jdbc.sql("""
                INSERT INTO account (parent_company_id, name, city, website_domain, relationship, owner_user_id, source)
                SELECT id, 'Alpha Bengaluru', 'Bengaluru', 'alpha-gcc.test', 'PROSPECT', :owner, 'test' FROM parent_company
                """).param("owner", admin).update();
        accountId = jdbc.sql("SELECT id FROM account").query(Long.class).single();
    }

    static final String GOOD = """
            {"researchedBy": "Claude Code (web search)", "accounts": [{
              "websiteDomain": "https://www.Alpha-GCC.test/", "city": " bengaluru ",
              "summary": {"centreOverview": "Alpha's Bengaluru centre builds its online store.",
                "buyingSignals": [{"statement": "Hiring React engineers", "source": "https://jobs.example.test/alpha"}],
                "openingMessage": "Hello", "fitRating": "strong"},
              "sources": [{"url": "https://jobs.example.test/alpha", "title": "Jobs"}]}]}
            """;

    @Test
    void savesAFinishedRunMatchedByDomainAndCity() throws Exception {
        mvc.perform(post("/api/v1/research/uploads").with(user(ADMIN)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(GOOD))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].accountId").value(accountId));
        mvc.perform(get("/api/v1/accounts/" + accountId + "/research").with(user(DELIVERY)))
            .andExpect(jsonPath("$.status").value("DONE"))
            .andExpect(jsonPath("$.model").value("Claude Code (web search)"))
            .andExpect(jsonPath("$.requestedBy").value("Admin Person"))
            .andExpect(jsonPath("$.summary.buyingSignals[0].source").value("https://jobs.example.test/alpha"))
            .andExpect(jsonPath("$.summary.facts").isEmpty())
            .andExpect(jsonPath("$.summary.cautions").isArray())
            .andExpect(jsonPath("$.summary.fitRating").value("STRONG"));
        // The research fit rating counts towards the fit score (ADR-0012).
        mvc.perform(get("/api/v1/accounts?sort=fit").with(user(DELIVERY)))
            .andExpect(jsonPath("$.items[0].fitReasons", org.hamcrest.Matchers.hasItem("Research rates the fit strong (+20)")))
            .andExpect(jsonPath("$.items[0].fitScore").value(30));
        // The accounts list shows which accounts have research, and can filter by it.
        mvc.perform(get("/api/v1/accounts?researched=true").with(user(DELIVERY)))
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].researchedAt").isNotEmpty());
        mvc.perform(get("/api/v1/accounts?researched=false&sort=fit").with(user(DELIVERY)))
            .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void aLaterFailedRefreshDoesNotHideTheResearch() throws Exception {
        mvc.perform(post("/api/v1/research/uploads").with(user(ADMIN)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(GOOD))
            .andExpect(status().isOk());
        jdbc.sql("""
                INSERT INTO account_research (account_id, status, error, requested_by, requested_at, finished_at)
                SELECT :a, 'FAILED', 'The Anthropic API key was rejected (401).', id, NOW(3) + INTERVAL 1 SECOND, NOW(3)
                FROM app_user WHERE email = :e
                """).param("a", accountId).param("e", ADMIN).update();
        mvc.perform(get("/api/v1/accounts/" + accountId + "/research").with(user(ADMIN)))
            .andExpect(jsonPath("$.status").value("DONE"))
            .andExpect(jsonPath("$.summary.centreOverview").isNotEmpty())
            .andExpect(jsonPath("$.laterFailure").value("The Anthropic API key was rejected (401)."));
    }

    @Test
    void rejectsAnUnknownFitRating() throws Exception {
        mvc.perform(post("/api/v1/research/uploads").with(user(ADMIN)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(GOOD.replace("\"strong\"", "\"excellent\"")))
            .andExpect(status().is4xxClientError());
        org.assertj.core.api.Assertions.assertThat(
            jdbc.sql("SELECT COUNT(*) FROM account_research").query(Integer.class).single()).isZero();
    }

    @Test
    void savesNothingWhenAnyEntryIsWrong() throws Exception {
        String bad = GOOD.replace("\"accounts\": [{", "\"accounts\": [{\"websiteDomain\": \"missing.test\", \"city\": \"Pune\", \"summary\": {\"centreOverview\": \"x\"}}, {");
        mvc.perform(post("/api/v1/research/uploads").with(user(ADMIN)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(bad))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.problems[0]").value("Entry 1: no account with website missing.test in Pune."));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM account_research").query(Integer.class).single()).isZero();
    }

    @Test
    void deliveryCannotUpload() throws Exception {
        mvc.perform(post("/api/v1/research/uploads").with(user(DELIVERY)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(GOOD))
            .andExpect(status().isForbidden());
    }
}
