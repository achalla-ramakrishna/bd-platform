package com.codewalnut.bd.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import org.hamcrest.Matchers;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.codewalnut.bd.TestData;
import com.codewalnut.bd.admin.UserDirectory;
import com.codewalnut.bd.shared.Role;
import com.codewalnut.bd.walnut.Mailer;
import com.jayway.jsonpath.JsonPath;

/** GTN for the team (ADR-0021): owners work their contacts, managers oversee, contacts change hands. Fictional. */
@SpringBootTest
@ActiveProfiles("test")
class TeamIntegrationTest {

    static final List<String[]> SENT = new ArrayList<>();

    @TestConfiguration
    static class Stubs {
        @Bean
        @Primary
        Mailer stubMailer() {
            return new Mailer() {
                @Override
                public boolean enabled() {
                    return true;
                }

                @Override
                public void send(String to, String subject, String html) {
                    SENT.add(new String[] {to, subject, html});
                }
            };
        }
    }

    static final String ADMIN = "admin@example-gcc.test";
    static final String SALES = "sales.person@example-gcc.test";
    static final String SECOND = "second.seller@example-gcc.test";
    static final String DELIVERY = "delivery.person@example-gcc.test";

    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    @Autowired UserDirectory users;
    @Autowired NetworkService network;

    MockMvc mvc;
    LocalDate today;
    long salesId;
    long secondId;
    long deliveryId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        TestData.clear(jdbc);
        SENT.clear();
        long admin = users.recordSignIn(ADMIN, "Admin Person").id();
        salesId = users.recordSignIn(SALES, "Sales Person").id();
        secondId = users.recordSignIn(SECOND, "Second Seller").id();
        deliveryId = users.recordSignIn(DELIVERY, "Delivery Person").id();
        users.setRoles(salesId, EnumSet.of(Role.ACCOUNT_EXECUTIVE), admin);
        users.setRoles(secondId, EnumSet.of(Role.ACCOUNT_EXECUTIVE), admin);
        users.setRoles(deliveryId, EnumSet.of(Role.DELIVERY), admin);
        today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        for (String name : List.of("Person One", "Person Two", "Person Three")) {
            jdbc.sql("INSERT INTO connection (full_name, source_tag, created_by) VALUES (:n, 'Alumni', :u)").param("n", name).param("u", admin).update();
        }
    }

    ResultActions json(String who, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, String body) throws Exception {
        return mvc.perform(req.with(user(who)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    long start(String who, String name) throws Exception {
        long c = jdbc.sql("SELECT id FROM connection WHERE full_name = :n").param("n", name).query(Long.class).single();
        String body = json(who, post("/api/v1/network/tracks"), "{\"connectionId\": %d, \"channel\": \"LINKEDIN\"}".formatted(c))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.detail.track.id")).longValue();
    }

    @Test
    void ownersWorkTheirContactsAndHandThemOver() throws Exception {
        long one = start(SALES, "Person One");
        long two = start(SALES, "Person Two");
        long three = start(SECOND, "Person Three");

        // Someone else's contact: readable, not workable; starting them again says whose they are.
        mvc.perform(get("/api/v1/network/tracks/" + one).with(user(SECOND))).andExpect(status().isOk());
        json(SECOND, post("/api/v1/network/tracks/" + one + "/actions"), "{\"action\": \"REPLIED\", \"channel\": \"LINKEDIN\"}")
            .andExpect(status().isForbidden());
        json(SECOND, post("/api/v1/network/tracks/" + one + "/hand-over"), "{\"toUserId\": %d}".formatted(secondId))
            .andExpect(status().isForbidden());
        long c1 = jdbc.sql("SELECT connection_id FROM network_track WHERE id = :id").param("id", one).query(Long.class).single();
        json(SECOND, post("/api/v1/network/tracks"), "{\"connectionId\": %d, \"channel\": \"LINKEDIN\"}".formatted(c1))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail").value(Matchers.containsString("already on GTN with Sales Person")));
        // A manager may work anyone's.
        json(ADMIN, post("/api/v1/network/tracks/" + one + "/actions"), "{\"action\": \"REPLIED\", \"channel\": \"LINKEDIN\"}")
            .andExpect(status().isOk());

        // The owner hands one over: recorded, the new owner is told and sees it.
        json(SALES, post("/api/v1/network/tracks/" + one + "/hand-over"), "{\"toUserId\": %d}".formatted(deliveryId))
            .andExpect(status().isUnprocessableEntity()); // not a pipeline role
        json(SALES, post("/api/v1/network/tracks/" + one + "/hand-over"), "{\"toUserId\": %d, \"note\": \"They know each other\"}".formatted(secondId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.detail.track.ownerName").value("Second Seller"))
            .andExpect(jsonPath("$.detail.track.stage").value("RESPONDED"))
            .andExpect(jsonPath("$.detail.changes[0].note").value("Handed over from Sales Person to Second Seller: They know each other"));
        assertThat(SENT).hasSize(1);
        assertThat(SENT.get(0)[0]).isEqualTo(SECOND);
        assertThat(SENT.get(0)[1]).isEqualTo("Sales Person handed Person One over to you");
        json(SALES, post("/api/v1/network/tracks/" + one + "/actions"), "{\"action\": \"INTRO_DONE\"}").andExpect(status().isForbidden());
        json(SECOND, post("/api/v1/network/tracks/" + one + "/actions"), "{\"action\": \"INTRO_DONE\"}").andExpect(status().isOk());
        json(SECOND, post("/api/v1/network/tracks/" + one + "/hand-over"), "{\"toUserId\": %d}".formatted(secondId))
            .andExpect(status().isUnprocessableEntity());

        // Several at once: what isn't theirs is skipped, the rest go.
        String many = json(SALES, post("/api/v1/network/hand-over"), "{\"trackIds\": [%d, %d], \"toUserId\": %d}".formatted(two, three, secondId))
            .andExpect(status().isOk()).andExpect(jsonPath("$.handedOver").value(1)).andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(many, "$.skipped")).containsExactly("Person Three: someone else's contact");

        // Everything one person has, by a manager only (e.g. they leave).
        json(SECOND, post("/api/v1/network/hand-over-all"), "{\"fromUserId\": %d, \"toUserId\": %d}".formatted(secondId, salesId))
            .andExpect(status().isForbidden());
        json(ADMIN, post("/api/v1/network/hand-over-all"), "{\"fromUserId\": %d, \"toUserId\": %d, \"note\": \"Leaving\"}".formatted(secondId, salesId))
            .andExpect(status().isOk()).andExpect(jsonPath("$.handedOver").value(3)).andExpect(jsonPath("$.toName").value("Sales Person"));
        assertThat(SENT.get(SENT.size() - 1)[1]).isEqualTo("Admin Person handed 3 GTN contacts over to you");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM network_track WHERE owner_user_id = :u").param("u", salesId).query(Integer.class).single())
            .isEqualTo(3);
        json(ADMIN, post("/api/v1/network/hand-over-all"), "{\"fromUserId\": %d, \"toUserId\": %d}".formatted(secondId, salesId))
            .andExpect(status().isUnprocessableEntity());

        // A teammate's Today.
        jdbc.sql("UPDATE network_track SET next_action_on = :d").param("d", today.minusDays(2)).update();
        mvc.perform(get("/api/v1/network/today?ownerId=" + salesId).with(user(ADMIN))).andExpect(jsonPath("$.items.length()").value(3));
        mvc.perform(get("/api/v1/network/today").with(user(ADMIN))).andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void theTeamViewAndBeepsOnTime() throws Exception {
        long one = start(SALES, "Person One");
        long two = start(SECOND, "Person Two");
        for (long t : List.of(one, two)) {
            json(ADMIN, post("/api/v1/network/tracks/" + t + "/actions"), "{\"action\": \"MOVED\", \"stage\": \"MONTHLY_BEEP\"}").andExpect(status().isOk());
        }
        // One beep sent on its day, one five days late.
        jdbc.sql("UPDATE network_track SET next_action_on = :d WHERE id = :id").param("d", today).param("id", one).update();
        jdbc.sql("UPDATE network_track SET next_action_on = :d WHERE id = :id").param("d", today.minusDays(5)).param("id", two).update();
        json(SALES, post("/api/v1/network/tracks/" + one + "/actions"), "{\"action\": \"SENT\", \"channel\": \"WHATSAPP\"}").andExpect(status().isOk());
        json(SECOND, post("/api/v1/network/tracks/" + two + "/actions"), "{\"action\": \"SENT\", \"channel\": \"WHATSAPP\"}").andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT due_on FROM network_touch WHERE track_id = :t AND kind = 'BEEP'").param("t", two).query(LocalDate.class).single())
            .isEqualTo(today.minusDays(5));

        String team = mvc.perform(get("/api/v1/network/team").with(user(DELIVERY))).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(team, "$[*].name")).containsExactly("Sales Person", "Second Seller");
        assertThat((Integer) JsonPath.read(team, "$[0].beepsOnTime")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(team, "$[1].beepsOnTime")).isEqualTo(0);
        assertThat((Integer) JsonPath.read(team, "$[1].beepsSent")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(team, "$[0].started")).isEqualTo(1);
        mvc.perform(get("/api/v1/network/dashboard").with(user(ADMIN)))
            .andExpect(jsonPath("$.beepsSent").value(2)).andExpect(jsonPath("$.beepsOnTime").value(1))
            .andExpect(jsonPath("$.team.length()").value(2));

        // Walnut's brief names teammates with overdue GTN actions.
        jdbc.sql("UPDATE network_track SET next_action_on = :d").param("d", today.minusDays(3)).update();
        String body = JsonPath.read(mvc.perform(post("/api/v1/walnut/brief").with(user(ADMIN)).with(csrf()))
            .andReturn().getResponse().getContentAsString(), "$.body");
        assertThat(body).contains("Team GTN, overdue: Sales 1, Second 1");
    }
}
