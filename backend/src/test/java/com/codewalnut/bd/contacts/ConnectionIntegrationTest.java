package com.codewalnut.bd.contacts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

/** Connections: shared people the team knows, warm paths into accounts, growth. Fictional data. */
@SpringBootTest
@ActiveProfiles("test")
class ConnectionIntegrationTest {

    static final String ADMIN = "admin@example-gcc.test";
    static final String SALES = "sales.person@example-gcc.test";
    static final String DELIVERY = "delivery.person@example-gcc.test";

    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    @Autowired UserDirectory users;

    MockMvc mvc;
    long account;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        TestData.clear(jdbc);
        long admin = users.recordSignIn(ADMIN, "Admin Person").id();
        users.setRoles(users.recordSignIn(SALES, "Sales Person").id(), EnumSet.of(Role.ACCOUNT_EXECUTIVE), admin);
        users.setRoles(users.recordSignIn(DELIVERY, "Delivery Person").id(), EnumSet.of(Role.DELIVERY), admin);
        jdbc.sql("INSERT INTO parent_company (name) VALUES ('Alpha Corp')").update();
        jdbc.sql("""
                INSERT INTO account (parent_company_id, name, city, website_domain, relationship, owner_user_id, source)
                SELECT id, 'Alpha Bengaluru', 'Bengaluru', 'alpha-gcc.test', 'PROSPECT', :owner, 'test' FROM parent_company
                """).param("owner", admin).update();
        account = jdbc.sql("SELECT id FROM account").query(Long.class).single();
    }

    ResultActions add(String who, String json) throws Exception {
        return mvc.perform(post("/api/v1/connections").with(user(who)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    String person(String name, String link) {
        return """
            {"person": {"fullName": "%s", "currentCompany": "Alpha Corp", "currentRole": "Site lead",
              "linkedinUrl": "https://www.linkedin.com/in/example-person", "accountId": %s, "accountLink": %s},
             "knowing": {"strength": "CLOSE", "howKnown": "Worked together for five years", "connectedOn": "%s"}}
            """.formatted(name, link == null ? "null" : String.valueOf(account), link == null ? "null" : "\"" + link + "\"",
            LocalDate.now().minusDays(3));
    }

    long id(String body) {
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    @Test
    void addsAConnectionWithWhoKnowsThemAndShowsWarmPathsAndGrowth() throws Exception {
        String body = add(ADMIN, person("Person One", "WORKS_THERE"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.accountName").value("Alpha Bengaluru"))
            .andExpect(jsonPath("$.knownBy[0].userName").value("Admin Person"))
            .andExpect(jsonPath("$.knownBy[0].strength").value("CLOSE"))
            .andReturn().getResponse().getContentAsString();
        long id = id(body);
        // A second team member who knows the same person adds themselves instead of a duplicate.
        add(SALES, person("person one", null)).andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/api/v1/connections/" + id + "/knowers/me").with(user(SALES)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"strength\": \"ACQUAINTANCE\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.knownBy.length()").value(2))
            .andExpect(jsonPath("$.knownBy[1].userName").value("Sales Person"));
        add(SALES, person("Person Two", null)).andExpect(status().isCreated());

        mvc.perform(get("/api/v1/connections/for-account/" + account).with(user(DELIVERY)))
            .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/v1/connections?mine=true").with(user(SALES)))
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.summary.total").value(2))
            .andExpect(jsonPath("$.summary.knownByMe").value(2))
            .andExpect(jsonPath("$.summary.linkedToAccounts").value(1))
            .andExpect(jsonPath("$.summary.addedLast30Days").value(2))
            .andExpect(jsonPath("$.summary.weekly.length()").value(12));
        mvc.perform(get("/api/v1/connections?linked=false&q=two").with(user(DELIVERY)))
            .andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/v1/connections?strength=CLOSE").with(user(DELIVERY)))
            .andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    void checksTheFormAndRolesAndRefusesStaleEdits() throws Exception {
        add(ADMIN, """
            {"person": {"fullName": " ", "linkedinUrl": "https://example.test/x", "accountId": %d},
             "knowing": {"strength": "BEST_FRIENDS"}}""".formatted(account))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.problems.length()").value(4));
        add(DELIVERY, person("Person One", null)).andExpect(status().isForbidden());
        long id = id(add(ADMIN, person("Person One", null)).andReturn().getResponse().getContentAsString());
        String edit = "{\"fullName\": \"Person One\", \"currentCompany\": \"Beta Group\", \"version\": 0}";
        mvc.perform(put("/api/v1/connections/" + id).with(user(ADMIN)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(edit))
            .andExpect(status().isOk()).andExpect(jsonPath("$.currentCompany").value("Beta Group"));
        mvc.perform(put("/api/v1/connections/" + id).with(user(ADMIN)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(edit))
            .andExpect(status().isConflict());
    }

    @Test
    void onlyPeopleWhoWorkThereBecomeContactsAndOnceOnly() throws Exception {
        long intro = id(add(ADMIN, person("Person Three", "CAN_INTRODUCE")).andReturn().getResponse().getContentAsString());
        mvc.perform(post("/api/v1/connections/" + intro + "/contact").with(user(ADMIN)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"persona\": \"CENTRE_HEAD\"}"))
            .andExpect(status().isUnprocessableEntity());
        long inside = id(add(ADMIN, person("Person Four", "WORKS_THERE")).andReturn().getResponse().getContentAsString());
        mvc.perform(post("/api/v1/connections/" + inside + "/contact").with(user(ADMIN)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"persona\": \"CENTRE_HEAD\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.contactId").isNumber());
        mvc.perform(post("/api/v1/connections/" + inside + "/contact").with(user(ADMIN)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"persona\": \"CENTRE_HEAD\"}"))
            .andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM contact WHERE full_name = 'Person Four'").query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT source FROM contact WHERE full_name = 'Person Four'").query(String.class).single())
            .isEqualTo("Connection of Admin Person");
    }

    @Test
    void theConnectionGoesWhenTheLastPersonWhoKnowsThemLeavesIt() throws Exception {
        long id = id(add(ADMIN, person("Person Five", null)).andReturn().getResponse().getContentAsString());
        mvc.perform(put("/api/v1/connections/" + id + "/knowers/me").with(user(SALES)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"strength\": \"KNOW_WELL\"}"))
            .andExpect(status().isOk());
        mvc.perform(delete("/api/v1/connections/" + id + "/knowers/me").with(user(ADMIN)).with(csrf()))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/connections/" + id).with(user(ADMIN)))
            .andExpect(jsonPath("$.knownBy.length()").value(1));
        mvc.perform(delete("/api/v1/connections/" + id + "/knowers/me").with(user(SALES)).with(csrf()))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/connections/" + id).with(user(ADMIN))).andExpect(status().isNotFound());
    }
}
