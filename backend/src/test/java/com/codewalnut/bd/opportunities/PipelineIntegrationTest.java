package com.codewalnut.bd.opportunities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

import com.codewalnut.bd.admin.UserDirectory;
import com.codewalnut.bd.shared.Role;
import com.jayway.jsonpath.JsonPath;

/** The pipeline end to end against MySQL (FR-OPP-01 to FR-OPP-07, FR-ENG-01). Fictional data only. */
@SpringBootTest
@ActiveProfiles("test")
class PipelineIntegrationTest {

    static final String ADMIN = "admin@example-gcc.test";
    static final String AE = "sales.person@example-gcc.test";
    static final String DELIVERY = "delivery.person@example-gcc.test";

    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    @Autowired UserDirectory users;

    MockMvc mvc;
    long accountId;
    long otherAccountId;
    long contactId;
    long otherContactId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        com.codewalnut.bd.TestData.clear(jdbc);
        long admin = users.recordSignIn(ADMIN, "Admin Person").id();
        users.setRoles(users.recordSignIn(AE, "Sales Person").id(), EnumSet.of(Role.ACCOUNT_EXECUTIVE), admin);
        users.setRoles(users.recordSignIn(DELIVERY, "Delivery Person").id(), EnumSet.of(Role.DELIVERY), admin);
        jdbc.sql("INSERT INTO parent_company (name) VALUES ('Alpha Corp'), ('Beta Group')").update();
        accountId = insertAccount("Alpha Bengaluru", "alpha-gcc.test", "Alpha Corp", admin);
        otherAccountId = insertAccount("Beta Pune", "beta-gcc.test", "Beta Group", admin);
        contactId = insertContact(accountId, "Person One");
        otherContactId = insertContact(otherAccountId, "Person Two");
    }

    private long insertAccount(String name, String domain, String parent, long owner) {
        jdbc.sql("""
                INSERT INTO account (parent_company_id, name, city, website_domain, relationship, owner_user_id, source)
                SELECT id, :name, 'Bengaluru', :domain, 'PROSPECT', :owner, 'test' FROM parent_company WHERE name = :parent
                """).param("name", name).param("domain", domain).param("owner", owner).param("parent", parent).update();
        return jdbc.sql("SELECT id FROM account WHERE website_domain = :d").param("d", domain).query(Long.class).single();
    }

    private long insertContact(long account, String name) {
        jdbc.sql("""
                INSERT INTO contact (account_id, full_name, persona, source, consent_basis)
                VALUES (:a, :n, 'ENGINEERING_LEADER', 'test', 'BUSINESS_CONTACT')
                """).param("a", account).param("n", name).update();
        return jdbc.sql("SELECT id FROM contact WHERE full_name = :n").param("n", name).query(Long.class).single();
    }

    private ResultActions postJson(String who, String path, String body) throws Exception {
        return mvc.perform(post(path).with(user(who)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long createStaffAug() throws Exception {
        String body = """
            {"accountId": %d, "name": "Payments team extension", "type": "staff_augmentation", "valueInr": 6000000}
            """.formatted(accountId);
        String json = postJson(AE, "/api/v1/opportunities", body)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.stage").value("LEAD"))
            .andExpect(jsonPath("$.probability").value(5))
            .andExpect(jsonPath("$.ownerName").value("Sales Person"))
            .andExpect(jsonPath("$.weightedValueInr").value(300000.0))
            .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }

    private ResultActions move(long id, String stage, String reason) throws Exception {
        String body = reason == null ? "{\"stage\": \"%s\"}".formatted(stage)
            : "{\"stage\": \"%s\", \"lossReasonCode\": \"%s\"}".formatted(stage, reason);
        return postJson(AE, "/api/v1/opportunities/" + id + "/stage", body);
    }

    private ResultActions save(long id, int version, String extra) throws Exception {
        String body = """
            {"accountId": %d, "name": "Payments team extension", "type": "STAFF_AUGMENTATION", "valueInr": 6000000,
             "probability": 20, "version": %d %s}
            """.formatted(accountId, version, extra);
        return mvc.perform(patch("/api/v1/opportunities/" + id).with(user(AE)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    static final String REQUIREMENT = """
        {"role": "Senior backend engineer", "skills": ["Java", "Kafka"], "seniority": "senior", "headcount": 2,
         "locationMode": "hybrid", "location": "Bengaluru", "startDate": "2027-01-04", "durationMonths": 6,
         "rateInr": 250000, "rateUnit": "month"}
        """;

    @Test
    void walksThroughThePipelineEnforcingEachRule() throws Exception {
        long id = createStaffAug();

        move(id, "QUALIFIED", null)
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.problems", hasSize(3)));

        save(id, 0, ", \"decisionMakerContactId\": %d, \"needSummary\": \"Two senior Java engineers\", \"targetStartDate\": \"2027-01-04\"".formatted(contactId))
            .andExpect(status().isOk());
        move(id, "QUALIFIED", null).andExpect(status().isOk()).andExpect(jsonPath("$.probability").value(20));

        move(id, "PROPOSAL", null)
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.problems[0]").value(containsString("BR-BD-08")));

        int version = jdbc.sql("SELECT version FROM opportunity WHERE id = :id").param("id", id).query(Integer.class).single();
        save(id, version, ", \"decisionMakerContactId\": %d, \"needSummary\": \"Two senior Java engineers\", \"targetStartDate\": \"2027-01-04\", \"requirements\": [%s]"
            .formatted(contactId, REQUIREMENT))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.requirements[0].skills", hasSize(2)));
        move(id, "PROPOSAL", null).andExpect(status().isOk());
        move(id, "WON", null).andExpect(status().isOk())
            .andExpect(jsonPath("$.requirements[0].deploymentRequest").value("OPEN"))
            .andExpect(jsonPath("$.history", hasSize(4)));

        // BR-BD-12: one deployment request per requirement line.
        assertThat(jdbc.sql("SELECT COUNT(*) FROM deployment_request").query(Integer.class).single()).isEqualTo(1);
        // Won is final.
        move(id, "NEGOTIATION", null).andExpect(status().isUnprocessableContent());
    }

    @Test
    void lostNeedsALossReason() throws Exception {
        long id = createStaffAug();
        move(id, "LOST", null).andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.problems[0]").value(containsString("BR-BD-13")));
        move(id, "LOST", "no_budget").andExpect(status().isOk()).andExpect(jsonPath("$.lossReasonCode").value("NO_BUDGET"));
    }

    @Test
    void staleSavesAreRejected() throws Exception {
        long id = createStaffAug();
        save(id, 0, "").andExpect(status().isOk());
        save(id, 0, "").andExpect(status().isConflict());
    }

    @Test
    void decisionMakerMustBeAtTheSameAccount() throws Exception {
        long id = createStaffAug();
        save(id, 0, ", \"decisionMakerContactId\": %d".formatted(otherContactId))
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.problems", hasItem(containsString("contact at this account"))));
    }

    @Test
    void requirementLinesAreValidated() throws Exception {
        long id = createStaffAug();
        save(id, 0, ", \"requirements\": [{\"role\": \"\", \"skills\": [], \"seniority\": \"guru\", \"headcount\": 0, \"rateInr\": 100}]")
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.problems", hasItem("Requirement 1: role is required.")))
            .andExpect(jsonPath("$.problems", hasItem("Requirement 1: choose a rate unit for the rate.")));
    }

    @Test
    void deliveryCanReadButNotChangeThePipeline() throws Exception {
        createStaffAug();
        mvc.perform(get("/api/v1/opportunities").with(user(DELIVERY))).andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)));
        postJson(DELIVERY, "/api/v1/opportunities", "{\"accountId\": %d, \"name\": \"x\", \"type\": \"PROJECT\"}".formatted(accountId))
            .andExpect(status().isForbidden());
    }

    @Test
    void activitiesAreLoggedAndListedNewestFirst() throws Exception {
        postJson(AE, "/api/v1/activities", """
            {"accountId": %d, "contactId": %d, "channel": "linkedin_connection", "occurredOn": "2026-11-01",
             "summary": "Sent a connection request", "outcome": "no_reply"}
            """.formatted(accountId, contactId))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.contactName").value("Person One"))
            .andExpect(jsonPath("$.userName").value("Sales Person"));
        postJson(AE, "/api/v1/activities", """
            {"accountId": %d, "contactId": %d, "channel": "MEETING", "occurredOn": "2026-11-05",
             "summary": "Intro call", "outcome": "MEETING_BOOKED"}
            """.formatted(accountId, contactId)).andExpect(status().isCreated());
        mvc.perform(get("/api/v1/activities?accountId=" + accountId).with(user(DELIVERY)))
            .andExpect(jsonPath("$", hasSize(2)))
            .andExpect(jsonPath("$[0].channel").value("MEETING"));
        // The contact must belong to the account.
        postJson(AE, "/api/v1/activities", """
            {"accountId": %d, "contactId": %d, "channel": "EMAIL", "occurredOn": "2026-11-05", "summary": "x", "outcome": "DONE"}
            """.formatted(accountId, otherContactId))
            .andExpect(status().isUnprocessableContent());
    }
}
