package com.codewalnut.bd.improvements;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import com.jayway.jsonpath.JsonPath;

/** Improvement areas: suggest, approve, rank, deliver. Fictional data. */
@SpringBootTest
@ActiveProfiles("test")
class ImprovementIntegrationTest {

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

    ResultActions call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, String who, String json) throws Exception {
        return mvc.perform(req.with(user(who)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    long suggest(String who, String title) throws Exception {
        String body = call(post("/api/v1/improvements"), who, "{\"title\": \"" + title + "\", \"details\": \"Why it helps\"}")
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    int version(long id) {
        return jdbc.sql("SELECT version FROM improvement WHERE id = :id").param("id", id).query(Integer.class).single();
    }

    String decide(String status, String note, long id) {
        return "{\"status\": \"" + status + "\", \"note\": " + (note == null ? "null" : "\"" + note + "\"")
            + ", \"deliveredRef\": \"https://example-gcc.test/change/1\", \"version\": " + version(id) + "}";
    }

    @Test
    void anyoneSuggestsAndOnlyApproversDecide() throws Exception {
        long id = suggest(DELIVERY, "Add a heatmap");
        mvc.perform(get("/api/v1/improvements").with(user(DELIVERY)))
            .andExpect(jsonPath("$.canApprove").value(false))
            .andExpect(jsonPath("$.items[0].status").value("SUGGESTED"))
            .andExpect(jsonPath("$.items[0].suggestedBy").value("Delivery Person"))
            .andExpect(jsonPath("$.items[0].canEdit").value(true));
        call(post("/api/v1/improvements/" + id + "/decision"), DELIVERY, decide("APPROVED", null, id))
            .andExpect(status().isForbidden());
        call(post("/api/v1/improvements/" + id + "/decision"), ADMIN, decide("APPROVED", "Good idea", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED"))
            .andExpect(jsonPath("$.rank").value(1))
            .andExpect(jsonPath("$.decidedBy").value("Admin Person"));
        // Once approved, the person who suggested it can no longer edit it.
        call(put("/api/v1/improvements/" + id), DELIVERY, "{\"title\": \"Changed\", \"version\": " + version(id) + "}")
            .andExpect(status().isForbidden());
        call(post("/api/v1/improvements/" + id + "/decision"), ADMIN, decide("DONE", null, id))
            .andExpect(jsonPath("$.status").value("DONE"))
            .andExpect(jsonPath("$.rank").doesNotExist())
            .andExpect(jsonPath("$.deliveredRef").value("https://example-gcc.test/change/1"))
            .andExpect(jsonPath("$.doneAt").isNotEmpty());
    }

    @Test
    void decliningNeedsAReasonAndMovesMustMakeSense() throws Exception {
        long id = suggest(ADMIN, "Something");
        call(post("/api/v1/improvements/" + id + "/decision"), ADMIN, decide("DECLINED", null, id))
            .andExpect(status().isUnprocessableEntity());
        call(post("/api/v1/improvements/" + id + "/decision"), ADMIN, decide("DONE", null, id))
            .andExpect(status().isUnprocessableEntity());
        call(post("/api/v1/improvements/" + id + "/decision"), ADMIN, decide("DECLINED", "Out of scope for now", id))
            .andExpect(jsonPath("$.status").value("DECLINED"))
            .andExpect(jsonPath("$.decisionNote").value("Out of scope for now"));
        call(post("/api/v1/improvements/" + id + "/decision"), ADMIN, "{\"status\": \"APPROVED\", \"version\": 0}")
            .andExpect(status().isConflict());
    }

    @Test
    void theApprovedQueueCanBeReordered() throws Exception {
        long a = suggest(ADMIN, "First");
        long b = suggest(ADMIN, "Second");
        call(post("/api/v1/improvements/" + a + "/decision"), ADMIN, decide("APPROVED", null, a)).andExpect(status().isOk());
        call(post("/api/v1/improvements/" + b + "/decision"), ADMIN, decide("APPROVED", null, b)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/improvements?status=APPROVED").with(user(DELIVERY)))
            .andExpect(jsonPath("$.items[0].title").value("First"));
        call(post("/api/v1/improvements/" + b + "/move"), ADMIN, "{\"direction\": \"UP\", \"version\": " + version(b) + "}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].title").value("Second"))
            .andExpect(jsonPath("$.items[1].title").value("First"));
        call(post("/api/v1/improvements/" + b + "/move"), DELIVERY, "{\"direction\": \"DOWN\"}")
            .andExpect(status().isForbidden());
    }

    @Test
    void aSuggestionNeedsATitle() throws Exception {
        call(post("/api/v1/improvements"), DELIVERY, "{\"title\": \"  \"}").andExpect(status().isUnprocessableEntity());
    }
}
