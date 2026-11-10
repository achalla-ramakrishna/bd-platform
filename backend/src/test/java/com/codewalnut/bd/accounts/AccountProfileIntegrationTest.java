package com.codewalnut.bd.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Year;
import java.util.EnumSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.codewalnut.bd.TestData;
import com.codewalnut.bd.admin.UserDirectory;
import com.codewalnut.bd.shared.Role;

/** Account profile (year formed, size, open roles): edit, upload, filter, sort and score. Fictional data. */
@SpringBootTest
@ActiveProfiles("test")
class AccountProfileIntegrationTest {

    static final String ADMIN = "admin@example-gcc.test";
    static final String DELIVERY = "delivery.person@example-gcc.test";
    static final int RECENT = Year.now().getValue() - 1;

    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    @Autowired UserDirectory users;

    MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        TestData.clear(jdbc);
        long admin = users.recordSignIn(ADMIN, "Admin Person").id();
        users.setRoles(users.recordSignIn(DELIVERY, "Delivery Person").id(), EnumSet.of(Role.DELIVERY), admin);
        String csv = """
            account_name,parent_company,parent_hq_country,city,website_domain,industry,headcount_band,year_established,tech_stack,relationship,owner_email,source,notes,employee_count,open_roles,open_roles_as_of,open_roles_source
            Alpha Bengaluru,Alpha Corp,USA,Bengaluru,alpha-gcc.test,Travel technology,,%d,Java;React,prospect,admin@example-gcc.test,test,,150,25,2026-11-01,https://jobs.example.test/alpha
            Beta Pune,Beta Group,Germany,Pune,beta-gcc.test,Banking,5000+,2010,JavaScript,prospect,admin@example-gcc.test,test,,,,,
            Gamma Bengaluru,Gamma Inc,USA,Bengaluru,gamma-gcc.test,Retail,,,,prospect,admin@example-gcc.test,test,,,,,
            """.formatted(RECENT);
        mvc.perform(multipart("/api/v1/imports/accounts/commit")
                .file(new MockMultipartFile("file", "accounts.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                .with(user(ADMIN)).with(csrf()))
            .andExpect(status().isOk());
    }

    long id(String domain) {
        return jdbc.sql("SELECT id FROM account WHERE website_domain = :d").param("d", domain).query(Long.class).single();
    }

    @Test
    void spreadsheetColumnsFillTheProfileAndTheBandFollowsTheCount() throws Exception {
        mvc.perform(get("/api/v1/accounts/" + id("alpha-gcc.test")).with(user(DELIVERY)))
            .andExpect(jsonPath("$.account.employeeCount").value(150))
            .andExpect(jsonPath("$.account.headcountBand").value("<500"))
            .andExpect(jsonPath("$.account.openRoles").value(25))
            .andExpect(jsonPath("$.account.openRolesAsOf").value("2026-11-01"))
            .andExpect(jsonPath("$.account.openRolesSource").value("https://jobs.example.test/alpha"));
    }

    @Test
    void filtersBySizeYearIndustryAndWholeTechEntries() throws Exception {
        mvc.perform(get("/api/v1/accounts?sizeBand=<500").with(user(DELIVERY)))
            .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].name").value("Alpha Bengaluru"));
        mvc.perform(get("/api/v1/accounts?formedSince=" + (RECENT - 1)).with(user(DELIVERY)))
            .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/v1/accounts?industry=bank").with(user(DELIVERY)))
            .andExpect(jsonPath("$.items[0].name").value("Beta Pune"));
        // "java" matches Alpha (Java) but not Beta (JavaScript).
        mvc.perform(get("/api/v1/accounts?tech=Java").with(user(DELIVERY)))
            .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].name").value("Alpha Bengaluru"));
        mvc.perform(get("/api/v1/accounts/facets").with(user(DELIVERY)))
            .andExpect(jsonPath("$.cities[0]").value("Bengaluru"))
            .andExpect(jsonPath("$.industries", hasItem("Travel technology")));
    }

    @Test
    void sortsByNewestSmallestAndOpenRolesWithUnknownsLast() throws Exception {
        mvc.perform(get("/api/v1/accounts?sort=newest").with(user(DELIVERY)))
            .andExpect(jsonPath("$.items[0].name").value("Alpha Bengaluru"))
            .andExpect(jsonPath("$.items[1].name").value("Beta Pune"))
            .andExpect(jsonPath("$.items[2].name").value("Gamma Bengaluru"));
        mvc.perform(get("/api/v1/accounts?sort=smallest").with(user(DELIVERY)))
            .andExpect(jsonPath("$.items[0].name").value("Alpha Bengaluru"))
            .andExpect(jsonPath("$.items[2].name").value("Gamma Bengaluru"));
        mvc.perform(get("/api/v1/accounts?sort=open_roles").with(user(DELIVERY)))
            .andExpect(jsonPath("$.items[0].openRoles").value(25));
        mvc.perform(get("/api/v1/accounts?sort=fit").with(user(DELIVERY)))
            .andExpect(jsonPath("$.items[0].name").value("Alpha Bengaluru"))
            .andExpect(jsonPath("$.items[0].fitReasons", hasItem("Formed in " + RECENT + ", in the last 2 years (+10)")));
    }

    @Test
    void editorsCanEditTheProfileAndClearFields() throws Exception {
        long gamma = id("gamma-gcc.test");
        String body = """
            {"yearEstablished": %d, "headcountBand": null, "employeeCount": 2500, "openRoles": 12,
             "openRolesAsOf": null, "openRolesSource": "Careers page"}""".formatted(RECENT);
        mvc.perform(put("/api/v1/accounts/" + gamma + "/profile").with(user(DELIVERY)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/accounts/" + gamma + "/profile").with(user(ADMIN)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/accounts/" + gamma).with(user(ADMIN)))
            .andExpect(jsonPath("$.account.headcountBand").value("2000-5000"))
            .andExpect(jsonPath("$.account.openRoles").value(12))
            .andExpect(jsonPath("$.account.openRolesAsOf").isNotEmpty());
        mvc.perform(put("/api/v1/accounts/" + gamma + "/profile").with(user(ADMIN)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"openRoles\": -1, \"yearEstablished\": 1800}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.problems.length()").value(2));
        mvc.perform(put("/api/v1/accounts/" + gamma + "/profile").with(user(ADMIN)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT open_roles FROM account WHERE id = :id").param("id", gamma)
            .query(Integer.class).optional()).isEmpty();
    }

    @Test
    void researchUploadsCanFillTheProfileWithoutClearingIt() throws Exception {
        String upload = """
            {"researchedBy": "Test", "accounts": [{"websiteDomain": "alpha-gcc.test", "city": "Bengaluru",
              "summary": {"centreOverview": "Builds booking software."},
              "accountFacts": {"openRoles": 40, "openRolesSource": "https://jobs.example.test/alpha2"}}]}""";
        mvc.perform(post("/api/v1/research/uploads").with(user(ADMIN)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(upload))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/accounts/" + id("alpha-gcc.test")).with(user(ADMIN)))
            .andExpect(jsonPath("$.account.openRoles").value(40))
            .andExpect(jsonPath("$.account.employeeCount").value(150))
            .andExpect(jsonPath("$.account.yearEstablished").value(RECENT));
    }
}
