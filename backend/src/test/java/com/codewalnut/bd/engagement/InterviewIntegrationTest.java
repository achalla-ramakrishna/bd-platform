package com.codewalnut.bd.engagement;

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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.codewalnut.bd.TestData;
import com.codewalnut.bd.admin.UserDirectory;
import com.codewalnut.bd.shared.Role;
import com.jayway.jsonpath.JsonPath;

/** Leader interviews: from idea to published, with the rules on each step. Fictional data. */
@SpringBootTest
@ActiveProfiles("test")
class InterviewIntegrationTest {

    static final String ADMIN = "admin@example-gcc.test";
    static final String DELIVERY = "delivery.person@example-gcc.test";
    static final LocalDate TODAY = LocalDate.now();

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

    ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, String who, String json) throws Exception {
        return mvc.perform(req.with(user(who)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    long add() throws Exception {
        String body = send(post("/api/v1/interviews"), ADMIN, """
                {"leaderName": "Person One", "leaderTitle": "Site head", "company": "Alpha Corp",
                 "topic": "Scaling AI-native delivery", "format": "PODCAST"}""")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("IDEA"))
            .andExpect(jsonPath("$.ownerName").value("Admin Person"))
            .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    int version(long id) {
        return jdbc.sql("SELECT version FROM interview WHERE id = :id").param("id", id).query(Integer.class).single();
    }

    ResultActions move(long id, String status, String extra) throws Exception {
        return send(post("/api/v1/interviews/" + id + "/move"), ADMIN,
            "{\"status\": \"" + status + "\", \"version\": " + version(id) + (extra.isEmpty() ? "" : ", " + extra) + "}");
    }

    @Test
    void walksFromIdeaToPublishedAndCountsOnTheHeatmap() throws Exception {
        long id = add();
        move(id, "ASKED", "").andExpect(jsonPath("$.askedOn").value(TODAY.toString()));
        move(id, "AGREED", "").andExpect(status().isOk());
        move(id, "BOOKED", "").andExpect(status().isUnprocessableEntity());
        move(id, "BOOKED", "\"on\": \"" + TODAY.plusDays(7) + "\"").andExpect(jsonPath("$.scheduledOn").value(TODAY.plusDays(7).toString()));
        mvc.perform(get("/api/v1/interviews").with(user(DELIVERY)))
            .andExpect(jsonPath("$.summary.bookedNext30Days").value(1))
            .andExpect(jsonPath("$.summary.inProgress").value(1));
        move(id, "RECORDED", "").andExpect(status().isOk());
        move(id, "PUBLISHED", "\"publishedUrl\": \"not a link\"").andExpect(status().isUnprocessableEntity());
        move(id, "PUBLISHED", "\"publishedUrl\": \"https://blog.example-gcc.test/person-one\"")
            .andExpect(jsonPath("$.status").value("PUBLISHED"))
            .andExpect(jsonPath("$.publishedOn").value(TODAY.toString()));
        mvc.perform(get("/api/v1/interviews").with(user(DELIVERY)))
            .andExpect(jsonPath("$.summary.publishedThisQuarter").value(1));
        // Recorded and published today: two items on today's square.
        String heat = mvc.perform(get("/api/v1/heatmap?weeks=4").with(user(DELIVERY))).andReturn().getResponse().getContentAsString();
        int last = ((Number) JsonPath.read(heat, "$.days.length()")).intValue() - 1;
        org.assertj.core.api.Assertions.assertThat((Integer) JsonPath.read(heat, "$.days[" + last + "].interviews")).isEqualTo(2);
    }

    @Test
    void stepsCannotBeSkippedAndDeclinedCanRestart() throws Exception {
        long id = add();
        move(id, "BOOKED", "\"on\": \"" + TODAY + "\"").andExpect(status().isUnprocessableEntity());
        move(id, "DECLINED", "").andExpect(jsonPath("$.status").value("DECLINED"));
        move(id, "ASKED", "").andExpect(status().isUnprocessableEntity());
        move(id, "IDEA", "").andExpect(jsonPath("$.status").value("IDEA"));
        send(post("/api/v1/interviews/" + id + "/move"), ADMIN, "{\"status\": \"ASKED\", \"version\": 0}")
            .andExpect(status().isConflict());
    }

    @Test
    void onlyPipelineRolesWriteAndTheFormIsChecked() throws Exception {
        send(post("/api/v1/interviews"), DELIVERY, "{\"leaderName\": \"X\", \"topic\": \"Y\", \"format\": \"BLOG\"}")
            .andExpect(status().isForbidden());
        send(post("/api/v1/interviews"), ADMIN, "{\"leaderName\": \" \", \"topic\": \"\", \"format\": \"TV\"}")
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.problems.length()").value(3));
        long id = add();
        send(put("/api/v1/interviews/" + id), ADMIN, """
                {"leaderName": "Person One", "topic": "A new angle", "format": "BLOG", "version": %d}""".formatted(version(id)))
            .andExpect(jsonPath("$.topic").value("A new angle"))
            .andExpect(jsonPath("$.format").value("BLOG"));
    }
}
