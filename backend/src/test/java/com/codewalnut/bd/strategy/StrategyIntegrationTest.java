package com.codewalnut.bd.strategy;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.codewalnut.bd.TestData;
import com.codewalnut.bd.admin.UserDirectory;
import com.codewalnut.bd.shared.Role;

/** The BD strategy page: everyone reads, leadership and admins edit, every save is kept. Fictional data. */
@SpringBootTest
@ActiveProfiles("test")
class StrategyIntegrationTest {

    static final String ADMIN = "admin@example-gcc.test";
    static final String SALES = "sales.person@example-gcc.test";
    static final String LEADER = "leader.person@example-gcc.test";

    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    @Autowired UserDirectory users;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        TestData.clear(jdbc);
        long admin = users.recordSignIn(ADMIN, "Admin Person").id();
        users.setRoles(users.recordSignIn(SALES, "Sales Person").id(), EnumSet.of(Role.ACCOUNT_EXECUTIVE), admin);
        users.setRoles(users.recordSignIn(LEADER, "Leader Person").id(), EnumSet.of(Role.LEADERSHIP), admin);
    }

    String edit(String body, Long base) {
        return "{\"body\": \"" + body + "\", \"note\": \"test\", \"baseVersionId\": " + base + "}";
    }

    long latestId() {
        return jdbc.sql("SELECT MAX(id) FROM strategy_version").query(Long.class).single();
    }

    @Test
    void everyoneReadsOnlyEditorsSaveAndEverySaveIsKept() throws Exception {
        mvc.perform(get("/api/v1/strategy").with(user(SALES)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.current").doesNotExist())
            .andExpect(jsonPath("$.canEdit").value(false));
        mvc.perform(put("/api/v1/strategy").with(user(SALES)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(edit("# Plan", null)))
            .andExpect(status().isForbidden());

        mvc.perform(put("/api/v1/strategy").with(user(ADMIN)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(edit("# Plan A", null)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.editedBy").value("Admin Person"));
        long first = latestId();
        mvc.perform(put("/api/v1/strategy").with(user(LEADER)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(edit("# Plan B", first)))
            .andExpect(status().isOk());

        mvc.perform(get("/api/v1/strategy").with(user(SALES)))
            .andExpect(jsonPath("$.current.body").value("# Plan B"))
            .andExpect(jsonPath("$.current.editedBy").value("Leader Person"));
        mvc.perform(get("/api/v1/strategy/versions").with(user(SALES)))
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[1].id").value(first));
        mvc.perform(get("/api/v1/strategy/versions/" + first).with(user(SALES)))
            .andExpect(jsonPath("$.body").value("# Plan A"));
    }

    @Test
    void aSaveBasedOnAnOldVersionIsRefused() throws Exception {
        mvc.perform(put("/api/v1/strategy").with(user(ADMIN)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(edit("# One", null)))
            .andExpect(status().isOk());
        long one = latestId();
        mvc.perform(put("/api/v1/strategy").with(user(LEADER)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(edit("# Two", one)))
            .andExpect(status().isOk());
        mvc.perform(put("/api/v1/strategy").with(user(ADMIN)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(edit("# Three", one)))
            .andExpect(status().isConflict());
    }

    @Test
    void anEmptyStrategyIsRefusedAndAnUnchangedOneAddsNoVersion() throws Exception {
        mvc.perform(put("/api/v1/strategy").with(user(ADMIN)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(edit("   ", null)))
            .andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/api/v1/strategy").with(user(ADMIN)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(edit("# Same", null)))
            .andExpect(status().isOk());
        long id = latestId();
        mvc.perform(put("/api/v1/strategy").with(user(ADMIN)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(edit("# Same", id)))
            .andExpect(jsonPath("$.id").value(id));
    }
}
