package com.codewalnut.bd.reporting;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.codewalnut.bd.TestData;
import com.codewalnut.bd.admin.UserDirectory;
import com.codewalnut.bd.shared.Role;
import com.jayway.jsonpath.JsonPath;

/** The activity heatmap counts touches, new connections and events per day, for the team or a person. Fictional data. */
@SpringBootTest
@ActiveProfiles("test")
class HeatmapIntegrationTest {

    static final String ADMIN = "admin@example-gcc.test";
    static final String SALES = "sales.person@example-gcc.test";
    static final LocalDate TODAY = LocalDate.now();

    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    @Autowired UserDirectory users;

    MockMvc mvc;
    long account;
    long sales;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        TestData.clear(jdbc);
        long admin = users.recordSignIn(ADMIN, "Admin Person").id();
        sales = users.recordSignIn(SALES, "Sales Person").id();
        users.setRoles(sales, EnumSet.of(Role.ACCOUNT_EXECUTIVE), admin);
        jdbc.sql("INSERT INTO parent_company (name) VALUES ('Alpha Corp')").update();
        jdbc.sql("""
                INSERT INTO account (parent_company_id, name, city, website_domain, relationship, owner_user_id, source)
                SELECT id, 'Alpha Bengaluru', 'Bengaluru', 'alpha-gcc.test', 'PROSPECT', :owner, 'test' FROM parent_company
                """).param("owner", admin).update();
        account = jdbc.sql("SELECT id FROM account").query(Long.class).single();

        // Today: the admin starts a conversation with a meeting booked; the sales person adds a connection.
        send(post("/api/v1/conversations"), ADMIN, """
                {"accountId": %d, "personName": "Person One", "topic": "AI pod", "channel": "CALL", "occurredOn": "%s",
                 "summary": "Agreed to meet", "outcome": "MEETING_BOOKED"}""".formatted(account, TODAY));
        send(post("/api/v1/connections"), SALES, """
                {"person": {"fullName": "Person Two"}, "knowing": {"strength": "CLOSE", "connectedOn": "%s"}}""".formatted(TODAY));
        // Yesterday: an event the admin went to.
        String body = send(post("/api/v1/events"), ADMIN, """
                {"name": "GCC meetup", "kind": "MEETUP", "startsOn": "%s", "ourRole": "ATTENDING"}""".formatted(TODAY.minusDays(1)));
        long event = ((Number) JsonPath.read(body, "$.event.id")).longValue();
        send(put("/api/v1/events/" + event + "/attendees/me"), ADMIN, "{}");
        // Not counted: an event nobody from the team went to.
        send(post("/api/v1/events"), ADMIN, """
                {"name": "Skipped webinar", "kind": "WEBINAR", "startsOn": "%s", "ourRole": "WATCHING"}""".formatted(TODAY.minusDays(1)));
    }

    String send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, String who, String json) throws Exception {
        return mvc.perform(req.with(user(who)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json))
            .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void theTeamViewCountsEveryoneByDay() throws Exception {
        String body = mvc.perform(get("/api/v1/heatmap?weeks=4").with(user(SALES)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.to").value(TODAY.toString()))
            .andExpect(jsonPath("$.summary.total").value(3))
            .andExpect(jsonPath("$.summary.activeDaysLast30").value(2))
            .andExpect(jsonPath("$.weeks.length()").value(4))
            .andExpect(jsonPath("$.people[*].name", hasItem("Sales Person")))
            .andReturn().getResponse().getContentAsString();
        int last = ((Number) JsonPath.read(body, "$.days.length()")).intValue() - 1;
        org.assertj.core.api.Assertions.assertThat((Integer) JsonPath.read(body, "$.days[" + last + "].total")).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat((Integer) JsonPath.read(body, "$.days[" + last + "].meetings")).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat((Integer) JsonPath.read(body, "$.days[" + last + "].conversationsStarted")).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat((Integer) JsonPath.read(body, "$.days[" + (last - 1) + "].events")).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat((String) JsonPath.read(body, "$.days[" + (last - 1) + "].items[0].link")).startsWith("/events/");
    }

    @Test
    void onePersonsViewCountsOnlyTheirs() throws Exception {
        mvc.perform(get("/api/v1/heatmap?weeks=4&userId=" + sales).with(user(ADMIN)))
            .andExpect(jsonPath("$.summary.total").value(1))
            .andExpect(jsonPath("$.summary.thisWeek").value(1));
    }
}
