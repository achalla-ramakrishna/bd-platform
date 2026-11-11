package com.codewalnut.bd.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.codewalnut.bd.TestData;
import com.codewalnut.bd.admin.UserDirectory;
import com.codewalnut.bd.shared.Role;

/** Adding one account by hand: keyed by domain and city, with its profile facts. Fictional data. */
@SpringBootTest
@ActiveProfiles("test")
class AccountAddIntegrationTest {

    static final String ADMIN = "admin@example-gcc.test";
    static final String DELIVERY = "delivery.person@example-gcc.test";

    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    @Autowired UserDirectory users;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        TestData.clear(jdbc);
        long admin = users.recordSignIn(ADMIN, "Admin Person").id();
        users.setRoles(users.recordSignIn(DELIVERY, "Delivery Person").id(), EnumSet.of(Role.DELIVERY), admin);
    }

    ResultActions add(String who, String json) throws Exception {
        return mvc.perform(post("/api/v1/accounts").with(user(who)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    static final String CENTRE = """
        {"name": "Example Centre Pune", "parentCompany": "Example Corp", "city": "Pune",
         "websiteDomain": "https://www.Example-GCC.test/careers", "industry": "Retail", "source": "Log with AI",
         "profile": {"employeeCount": 1200, "openRoles": 35, "openRolesAsOf": "2026-11-01", "openRolesSource": "Careers page"}}""";

    @Test
    void addsAProspectWithItsFactsOnceOnly() throws Exception {
        add(ADMIN, CENTRE).andExpect(status().isCreated()).andExpect(jsonPath("$.id").isNumber());
        var row = jdbc.sql("SELECT website_domain, relationship, source, employee_count, open_roles, headcount_band FROM account")
            .query((rs, n) -> rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3) + "|" + rs.getInt(4) + "|"
                + rs.getInt(5) + "|" + rs.getString(6)).single();
        assertThat(row).isEqualTo("example-gcc.test|PROSPECT|Log with AI|1200|35|500-2000");
        add(ADMIN, CENTRE).andExpect(status().isConflict());
        add(ADMIN, CENTRE.replace("\"Pune\"", "\"Chennai\"")).andExpect(status().isCreated());
    }

    @Test
    void checksTheFormAndTheRole() throws Exception {
        add(ADMIN, """
            {"name": "", "parentCompany": "Example Corp", "city": "Pune", "websiteDomain": "not a domain"}""")
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.problems.length()").value(2));
        add(DELIVERY, CENTRE).andExpect(status().isForbidden());
    }
}
