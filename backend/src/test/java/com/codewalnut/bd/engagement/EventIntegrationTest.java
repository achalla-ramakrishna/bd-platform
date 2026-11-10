package com.codewalnut.bd.engagement;

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

/** Conferences and events: the radar, who goes, and the people met there. Fictional data. */
@SpringBootTest
@ActiveProfiles("test")
class EventIntegrationTest {

    static final String ADMIN = "admin@example-gcc.test";
    static final String DELIVERY = "delivery.person@example-gcc.test";
    static final LocalDate TODAY = LocalDate.now();

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
        users.setRoles(users.recordSignIn(DELIVERY, "Delivery Person").id(), EnumSet.of(Role.DELIVERY), admin);
        jdbc.sql("INSERT INTO parent_company (name) VALUES ('Alpha Corp')").update();
        jdbc.sql("""
                INSERT INTO account (parent_company_id, name, city, website_domain, relationship, owner_user_id, source)
                SELECT id, 'Alpha Bengaluru', 'Bengaluru', 'alpha-gcc.test', 'PROSPECT', :owner, 'test' FROM parent_company
                """).param("owner", admin).update();
        account = jdbc.sql("SELECT id FROM account").query(Long.class).single();
    }

    ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, String who, String json) throws Exception {
        return mvc.perform(req.with(user(who)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    long event(String name, LocalDate on, String role) throws Exception {
        String body = send(post("/api/v1/events"), ADMIN, """
                {"name": "%s", "kind": "MEETUP", "startsOn": "%s", "city": "Bengaluru", "ourRole": "%s",
                 "url": "https://events.example-gcc.test/1"}
                """.formatted(name, on, role))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.event.id")).longValue();
    }

    @Test
    void theRadarSplitsUpcomingAndPastAndCounts() throws Exception {
        event("AI engineering roundtable", TODAY.plusDays(10), "ORGANISING");
        event("GCC summit", TODAY.plusDays(60), "ATTENDING");
        event("Old meetup", TODAY.minusDays(20), "ATTENDING");
        mvc.perform(get("/api/v1/events?when=upcoming").with(user(DELIVERY)))
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].name").value("AI engineering roundtable"))
            .andExpect(jsonPath("$.summary.next30Days").value(1))
            .andExpect(jsonPath("$.summary.presentingOrOrganising").value(1));
        mvc.perform(get("/api/v1/events?when=past").with(user(DELIVERY)))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].name").value("Old meetup"));
        mvc.perform(get("/api/v1/events?role=ORGANISING").with(user(DELIVERY)))
            .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void anyoneCanSayTheyAreGoingButOnlyPipelineRolesEdit() throws Exception {
        long id = event("GCC summit", TODAY.plusDays(5), "SIGNED_UP");
        send(put("/api/v1/events/" + id + "/attendees/me"), DELIVERY, "{}")
            .andExpect(jsonPath("$.event.attendees[0].name").value("Delivery Person"));
        mvc.perform(get("/api/v1/events?mine=true").with(user(DELIVERY))).andExpect(jsonPath("$.items.length()").value(1));
        send(delete("/api/v1/events/" + id + "/attendees/me"), DELIVERY, "{}")
            .andExpect(jsonPath("$.event.attendees.length()").value(0));
        String edit = """
                {"name": "GCC summit", "kind": "CONFERENCE", "startsOn": "%s", "ourRole": "PRESENTING",
                 "outcome": "Good crowd; three follow-ups", "version": 0}""".formatted(TODAY.plusDays(5));
        send(put("/api/v1/events/" + id), DELIVERY, edit).andExpect(status().isForbidden());
        send(put("/api/v1/events/" + id), ADMIN, edit)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.event.ourRole").value("PRESENTING"))
            .andExpect(jsonPath("$.event.outcome").value("Good crowd; three follow-ups"));
        send(put("/api/v1/events/" + id), ADMIN, edit).andExpect(status().isConflict());
    }

    @Test
    void eventsAreChecked() throws Exception {
        send(post("/api/v1/events"), ADMIN, """
                {"name": " ", "kind": "PARTY", "startsOn": "%s", "endsOn": "%s", "ourRole": "x", "url": "events.test"}
                """.formatted(TODAY, TODAY.minusDays(1)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.problems.length()").value(5));
    }

    @Test
    void conversationsAndConnectionsFromAnEventShowOnIt() throws Exception {
        long id = event("AI engineering roundtable", TODAY.minusDays(1), "ORGANISING");
        send(post("/api/v1/conversations"), ADMIN, """
                {"accountId": %d, "personName": "Person One", "topic": "Spoke after the panel", "channel": "EVENT",
                 "occurredOn": "%s", "summary": "Interested in an AI pod", "outcome": "REPLIED", "eventId": %d}
                """.formatted(account, TODAY, id))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.conversation.eventName").value("AI engineering roundtable"));
        send(post("/api/v1/connections"), ADMIN, """
                {"person": {"fullName": "Person Two", "currentCompany": "Beta Group", "metAtEventId": %d},
                 "knowing": {"strength": "ACQUAINTANCE"}}""".formatted(id))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.metAtEventName").value("AI engineering roundtable"));
        mvc.perform(get("/api/v1/events/" + id).with(user(DELIVERY)))
            .andExpect(jsonPath("$.event.conversations").value(1))
            .andExpect(jsonPath("$.event.connections").value(1))
            .andExpect(jsonPath("$.conversations[0].personName").value("Person One"))
            .andExpect(jsonPath("$.connections[0].fullName").value("Person Two"));
        mvc.perform(get("/api/v1/events").with(user(DELIVERY)))
            .andExpect(jsonPath("$.summary.peopleMetLast90Days").value(2));
    }
}
