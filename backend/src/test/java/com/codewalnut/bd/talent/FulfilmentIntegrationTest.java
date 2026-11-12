package com.codewalnut.bd.talent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

/** Realising an opportunity: requirements that came in and candidates through to starting. Fictional data. */
@SpringBootTest
@ActiveProfiles("test")
class FulfilmentIntegrationTest {

    static final String ADMIN = "admin@example-gcc.test";
    static final String DELIVERY = "delivery.person@example-gcc.test";
    static final String NOBODY = "no.role@example-gcc.test";

    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    @Autowired UserDirectory users;

    MockMvc mvc;
    long account;
    long opportunity;
    long requirement;
    long withConsent;
    long withoutConsent;
    final LocalDate today = LocalDate.now();

    static final String REQ = """
        {"role": "React engineer", "skills": ["React", "TypeScript"], "seniority": "SENIOR", "headcount": 1,
         "locationMode": "HYBRID", "location": "Bengaluru", "startDate": "%s", "durationMonths": 6, "allocationPercent": 100%s}""";

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        TestData.clear(jdbc);
        long admin = users.recordSignIn(ADMIN, "Admin Person").id();
        users.setRoles(users.recordSignIn(DELIVERY, "Delivery Person").id(), EnumSet.of(Role.DELIVERY), admin);
        users.recordSignIn(NOBODY, "No Role");
        jdbc.sql("INSERT INTO parent_company (name) VALUES ('Alpha Corp')").update();
        jdbc.sql("""
                INSERT INTO account (parent_company_id, name, city, website_domain, relationship, owner_user_id, source)
                SELECT id, 'Alpha Bengaluru', 'Bengaluru', 'alpha-gcc.test', 'PROSPECT', :owner, 'test' FROM parent_company
                """).param("owner", admin).update();
        account = jdbc.sql("SELECT id FROM account").query(Long.class).single();
        for (String[] e : new String[][] {{"E-1", "Engineer A", "1"}, {"E-2", "Engineer B", "0"}}) {
            jdbc.sql("""
                    INSERT INTO engineer (employee_code, full_name, work_email, primary_role, seniority, availability,
                        consent_to_share_profile) VALUES (:c, :n, :m, 'Frontend engineer', 'SENIOR', 'BENCH', :ok)
                    """).param("c", e[0]).param("n", e[1]).param("m", e[0].toLowerCase() + "@example-gcc.test")
                .param("ok", "1".equals(e[2])).update();
        }
        withConsent = jdbc.sql("SELECT id FROM engineer WHERE employee_code = 'E-1'").query(Long.class).single();
        withoutConsent = jdbc.sql("SELECT id FROM engineer WHERE employee_code = 'E-2'").query(Long.class).single();
        String body = mvc.perform(post("/api/v1/opportunities").with(user(ADMIN)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"accountId": %d, "name": "React team", "type": "STAFF_AUGMENTATION", "requirements": [%s]}
                    """.formatted(account, REQ.formatted(today.plusDays(30), ""))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        opportunity = ((Number) JsonPath.read(body, "$.id")).longValue();
        requirement = ((Number) JsonPath.read(body, "$.requirements[0].id")).longValue();
    }

    ResultActions propose(String who, String json) throws Exception {
        return mvc.perform(post("/api/v1/requirements/{id}/candidates", requirement).with(user(who)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    ResultActions move(long id, String status, LocalDate on, int version) throws Exception {
        return mvc.perform(post("/api/v1/candidates/{id}/move", id).with(user(DELIVERY)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\": \"%s\", \"on\": %s, \"version\": %d}".formatted(status, on == null ? "null" : "\"" + on + "\"", version)));
    }

    static long id(ResultActions r) throws Exception {
        return ((Number) JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }

    int version() throws Exception {
        return JsonPath.read(mvc.perform(get("/api/v1/opportunities/{id}", opportunity).with(user(ADMIN)))
            .andReturn().getResponse().getContentAsString(), "$.version");
    }

    @Test
    void savingTheOpportunityKeepsRequirementsAndTheirCandidates() throws Exception {
        long candidate = id(propose(ADMIN, "{\"engineerId\": %d}".formatted(withConsent)).andExpect(status().isCreated()));
        String form = """
            {"accountId": %d, "name": "React team (updated)", "type": "STAFF_AUGMENTATION", "requirements": [%s], "version": %d}
            """;
        mvc.perform(patch("/api/v1/opportunities/{id}", opportunity).with(user(ADMIN)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(form.formatted(account, REQ.formatted(today.plusDays(45), ", \"id\": " + requirement), version())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.requirements[0].id").value(requirement))
            .andExpect(jsonPath("$.requirements[0].startDate").value(today.plusDays(45).toString()));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM submission WHERE id = :id").param("id", candidate).query(Integer.class).single()).isOne();

        mvc.perform(patch("/api/v1/opportunities/{id}", opportunity).with(user(ADMIN)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(form.formatted(account, REQ.formatted(today.plusDays(45), ""), version())))
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.problems[0]").value("The React engineer requirement has candidates, so it cannot be removed. Withdraw or reject them first."));
    }

    @Test
    void aNewRequirementIsAddedToTheOpportunityAndStaleFormsAreRefused() throws Exception {
        int before = version();
        mvc.perform(post("/api/v1/opportunities/{id}/requirements", opportunity).with(user(ADMIN)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(REQ.formatted(today.plusDays(20), "").replace("React engineer", "QA engineer")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.requirements.length()").value(2))
            .andExpect(jsonPath("$.requirements[1].role").value("QA engineer"));
        mvc.perform(patch("/api/v1/opportunities/{id}", opportunity).with(user(ADMIN)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"accountId": %d, "name": "React team", "type": "STAFF_AUGMENTATION", "requirements": [%s], "version": %d}
                    """.formatted(account, REQ.formatted(today.plusDays(30), ", \"id\": " + requirement), before)))
            .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/opportunities/{id}/requirements", opportunity).with(user(ADMIN)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\": \"\"}"))
            .andExpect(status().isUnprocessableContent());
        mvc.perform(post("/api/v1/opportunities/{id}/requirements", opportunity).with(user(DELIVERY)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(REQ.formatted(today, "")))
            .andExpect(status().isForbidden());
    }

    @Test
    void candidatesMoveThroughToStartedAndFillTheRequirement() throws Exception {
        jdbc.sql("INSERT INTO deployment_request (requirement_id) VALUES (:r)").param("r", requirement).update();
        long noConsent = id(propose(DELIVERY, "{\"engineerId\": %d}".formatted(withoutConsent)).andExpect(status().isCreated()));
        move(noConsent, "SHARED", null, 0).andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.problems[0]").value("This engineer has not agreed to share their profile, so it cannot go to the client (BR-BD-10)."));
        propose(ADMIN, "{\"engineerId\": %d}".formatted(withoutConsent)).andExpect(status().isUnprocessableContent());

        long external = id(propose(ADMIN, "{\"candidateName\": \"Candidate C\", \"candidateSource\": \"Hiring partner\"}")
            .andExpect(jsonPath("$.source").value("Hiring partner")).andExpect(jsonPath("$.status").value("PROPOSED")));
        move(external, "SHARED", null, 0).andExpect(status().isOk());
        move(external, "INTERVIEW", null, 1).andExpect(status().isUnprocessableContent());
        move(external, "INTERVIEW", today.plusDays(2), 1).andExpect(jsonPath("$.interviewOn").value(today.plusDays(2).toString()));
        mvc.perform(get("/api/v1/fulfilment").with(user(ADMIN)))
            .andExpect(jsonPath("$[0].requirementId").value(requirement))
            .andExpect(jsonPath("$[0].byStatus.INTERVIEW").value(1))
            .andExpect(jsonPath("$[0].active").value(2));
        move(external, "SELECTED", null, 2).andExpect(status().isOk());
        move(external, "STARTED", today.plusDays(3), 3).andExpect(status().isUnprocessableContent());
        move(external, "STARTED", today, 3).andExpect(jsonPath("$.status").value("STARTED")).andExpect(jsonPath("$.startOn").value(today.toString()));
        move(external, "PROPOSED", null, 4).andExpect(status().isUnprocessableContent());
        move(external, "SELECTED", null, 3).andExpect(status().isConflict());

        assertThat(jdbc.sql("SELECT status FROM deployment_request").query(String.class).single()).isEqualTo("FILLED");
        mvc.perform(get("/api/v1/fulfilment").with(user(ADMIN))).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/v1/fulfilment?all=true").with(user(ADMIN))).andExpect(jsonPath("$[0].started").value(1));
        mvc.perform(get("/api/v1/opportunities/{id}/candidates", opportunity).with(user(DELIVERY)))
            .andExpect(jsonPath("$[0].name").value("Candidate C"));
        propose(NOBODY, "{\"candidateName\": \"Candidate D\"}").andExpect(status().isForbidden());
    }

    @Test
    void stageRules() {
        assertThat(CandidateService.allowed("PROPOSED", "SELECTED")).isTrue();
        assertThat(CandidateService.allowed("INTERVIEW", "SHARED")).isTrue();
        assertThat(CandidateService.allowed("INTERVIEW", "PROPOSED")).isFalse();
        assertThat(CandidateService.allowed("SELECTED", "REJECTED")).isTrue();
        assertThat(CandidateService.allowed("WITHDRAWN", "PROPOSED")).isTrue();
        assertThat(CandidateService.allowed("WITHDRAWN", "SHARED")).isFalse();
        assertThat(CandidateService.allowed("STARTED", "REJECTED")).isFalse();
    }
}
