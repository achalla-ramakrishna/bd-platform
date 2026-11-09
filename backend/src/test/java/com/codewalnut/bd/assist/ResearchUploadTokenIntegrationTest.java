package com.codewalnut.bd.assist;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/** Research upload with a bearer token instead of a browser session. Fictional data. */
@SpringBootTest(properties = {
    "app.research-upload.token=test-token-0123456789-0123456789-abcdef",
    "app.research-upload.as=admin@example-gcc.test"})
@ActiveProfiles("test")
class ResearchUploadTokenIntegrationTest {

    static final String TOKEN = "test-token-0123456789-0123456789-abcdef";

    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    @Autowired UserDirectory users;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        TestData.clear(jdbc);
        long admin = users.recordSignIn("admin@example-gcc.test", "Admin Person").id();
        jdbc.sql("INSERT INTO parent_company (name, hq_country) VALUES ('Alpha Corp', 'USA')").update();
        jdbc.sql("""
                INSERT INTO account (parent_company_id, name, city, website_domain, relationship, owner_user_id, source)
                SELECT id, 'Alpha Bengaluru', 'Bengaluru', 'alpha-gcc.test', 'PROSPECT', :owner, 'test' FROM parent_company
                """).param("owner", admin).update();
    }

    @Test
    void theRightTokenUploadsWithoutASession() throws Exception {
        mvc.perform(post("/api/v1/research/uploads").header("Authorization", "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(ResearchUploadIntegrationTest.GOOD))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].websiteDomain").value("alpha-gcc.test"));
    }

    @Test
    void aWrongTokenIsRefused() throws Exception {
        mvc.perform(post("/api/v1/research/uploads").header("Authorization", "Bearer wrong-token-0123456789-0123456789-xx")
                .contentType(MediaType.APPLICATION_JSON).content(ResearchUploadIntegrationTest.GOOD))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void theTokenOpensNoOtherEndpoint() throws Exception {
        mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + TOKEN))
            .andExpect(status().isUnauthorized());
    }
}
