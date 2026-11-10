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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.codewalnut.bd.TestData;
import com.codewalnut.bd.admin.UserDirectory;
import com.codewalnut.bd.reporting.ResearchContextQueries;
import com.codewalnut.bd.shared.Role;
import com.jayway.jsonpath.JsonPath;

/** Account research end to end with a stand-in researcher (no network). Fictional data. */
@SpringBootTest
@ActiveProfiles("test")
class AccountResearchIntegrationTest {

    /** What the stand-in does; each test sets it. */
    static final AtomicReference<Function<ResearchContextQueries.Context, AccountResearcher.Result>> BEHAVIOUR = new AtomicReference<>();
    static final AtomicReference<ResearchContextQueries.Context> SEEN = new AtomicReference<>();

    @TestConfiguration
    static class StubResearcher {
        @Bean
        @Primary
        AccountResearcher stubAccountResearcher() {
            return new AccountResearcher() {
                public boolean enabled() {
                    return true;
                }

                public Result research(ResearchContextQueries.Context context) {
                    SEEN.set(context);
                    return BEHAVIOUR.get().apply(context);
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
    long prospectId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        TestData.clear(jdbc);
        jdbc.sql("DELETE FROM account_research").update();
        long admin = users.recordSignIn(ADMIN, "Admin Person").id();
        users.setRoles(users.recordSignIn(DELIVERY, "Delivery Person").id(), EnumSet.of(Role.DELIVERY), admin);
        jdbc.sql("INSERT INTO parent_company (name, hq_country) VALUES ('Alpha Corp', 'USA'), ('Named Client Co', 'UK'), ('Secret Client Co', 'USA')").update();
        prospectId = account("Alpha Bengaluru", "alpha-gcc.test", "Alpha Corp", "PROSPECT", "Retail", admin);
        long named = account("Named Client Bengaluru", "named-gcc.test", "Named Client Co", "CLIENT", "Logistics", admin);
        long secret = account("Secret Client Bengaluru", "secret-gcc.test", "Secret Client Co", "CLIENT", "Fintech", admin);
        contract("SOW-N", named, true);
        contract("SOW-S", secret, false);
        jdbc.sql("""
                INSERT INTO contact (account_id, full_name, title, persona, source, consent_basis)
                VALUES (:a, 'Person One', 'Site Leader', 'CENTRE_HEAD', 'LinkedIn', 'BUSINESS_CONTACT')
                """).param("a", prospectId).update();
    }

    private long account(String name, String domain, String parent, String rel, String industry, long owner) {
        jdbc.sql("""
                INSERT INTO account (parent_company_id, name, city, website_domain, industry, relationship, owner_user_id, source)
                SELECT id, :name, 'Bengaluru', :domain, :industry, :rel, :owner, 'test' FROM parent_company WHERE name = :parent
                """).param("name", name).param("domain", domain).param("industry", industry).param("rel", rel)
            .param("owner", owner).param("parent", parent).update();
        return jdbc.sql("SELECT id FROM account WHERE website_domain = :d").param("d", domain).query(Long.class).single();
    }

    private void contract(String ref, long account, boolean nameable) {
        jdbc.sql("""
                INSERT INTO contract (contract_ref, account_id, contract_type, work_type, pricing_model, total_value_inr,
                    start_date, end_date, status, client_permission_to_name)
                VALUES (:ref, :a, 'SOW', 'STAFF_AUGMENTATION', 'MONTHLY_RATE', 9999999, '2026-01-01', '2026-12-31', 'ACTIVE', :n)
                """).param("ref", ref).param("a", account).param("n", nameable).update();
    }

    private static AccountResearcher.Result sample() {
        return new AccountResearcher.Result(new AccountResearcher.Summary(
            "Alpha's Bengaluru centre builds its e-commerce platform.",
            List.of(new AccountResearcher.Fact("About 1,500 people in Bengaluru", "https://news.example.test/alpha")),
            List.of(new AccountResearcher.Fact("Hiring 40 React engineers", "https://jobs.example.test/alpha")),
            List.of("Senior React engineers"),
            List.of(new AccountResearcher.Offer("AI-fluent senior engineers", "They are hiring React engineers")),
            List.of(new AccountResearcher.Target("Centre head", "Signs vendor contracts", "Person One")),
            "Saw Alpha is growing its React team in Bengaluru. We place senior React engineers in two weeks.",
            List.of("Ask about the checkout rebuild"), List.of("Headcount is from one article"),
            AccountResearcher.FitRating.STRONG),
            List.of(new AccountResearcher.Source("https://news.example.test/alpha", "Alpha expands")), "test-model");
    }

    private String waitForFinish() throws Exception {
        for (int i = 0; i < 100; i++) {
            String body = mvc.perform(get("/api/v1/accounts/" + prospectId + "/research").with(user(ADMIN)))
                .andReturn().getResponse().getContentAsString();
            String status = JsonPath.read(body, "$.status");
            if (!"RUNNING".equals(status)) {
                return body;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("research did not finish");
    }

    @Test
    void runsInTheBackgroundAndKeepsTheResult() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        BEHAVIOUR.set(ctx -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return sample();
        });
        mvc.perform(get("/api/v1/accounts/" + prospectId + "/research").with(user(ADMIN))).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/accounts/" + prospectId + "/research").with(user(ADMIN)).with(csrf()))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("RUNNING"));
        // A second click while it runs does not start another run.
        mvc.perform(post("/api/v1/accounts/" + prospectId + "/research").with(user(ADMIN)).with(csrf()))
            .andExpect(status().isAccepted());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM account_research").query(Integer.class).single()).isEqualTo(1);
        release.countDown();

        String body = waitForFinish();
        assertThat((String) JsonPath.read(body, "$.status")).isEqualTo("DONE");
        assertThat((String) JsonPath.read(body, "$.summary.buyingSignals[0].source")).isEqualTo("https://jobs.example.test/alpha");
        assertThat((String) JsonPath.read(body, "$.requestedBy")).isEqualTo("Admin Person");
        // Everyone can read it, including Delivery.
        mvc.perform(get("/api/v1/accounts/" + prospectId + "/research").with(user(DELIVERY)))
            .andExpect(jsonPath("$.summary.whoToContact[0].knownContact").value("Person One"));
    }

    @Test
    void failuresAreRecordedWithAPlainMessage() throws Exception {
        BEHAVIOUR.set(ctx -> { throw new ResearchException("Claude could not be reached (529). Try again later."); });
        mvc.perform(post("/api/v1/accounts/" + prospectId + "/research").with(user(ADMIN)).with(csrf()))
            .andExpect(status().isAccepted());
        String body = waitForFinish();
        assertThat((String) JsonPath.read(body, "$.status")).isEqualTo("FAILED");
        assertThat((String) JsonPath.read(body, "$.error")).contains("529");
    }

    @Test
    void ourRecordsNameOnlyPermittedClientsAndNoMoney() throws Exception {
        BEHAVIOUR.set(ctx -> sample());
        mvc.perform(post("/api/v1/accounts/" + prospectId + "/research").with(user(ADMIN)).with(csrf()));
        waitForFinish();
        String records = ClaudeAccountResearcher.records(SEEN.get());
        assertThat(records).contains("Named Client Co (Logistics)").contains("Fintech").contains("Person One");
        assertThat(records).doesNotContain("Secret Client Co").doesNotContain("9999999");
    }

    @Test
    void deliveryCannotStartResearch() throws Exception {
        mvc.perform(post("/api/v1/accounts/" + prospectId + "/research").with(user(DELIVERY)).with(csrf()))
            .andExpect(status().isForbidden());
    }
}
