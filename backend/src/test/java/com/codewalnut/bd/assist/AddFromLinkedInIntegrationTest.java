package com.codewalnut.bd.assist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.codewalnut.bd.TestData;
import com.codewalnut.bd.admin.UserDirectory;
import com.codewalnut.bd.shared.Role;

/** Add from LinkedIn: read a pasted profile (stand-in reader, no network) and save the contact. Fictional data. */
@SpringBootTest
@ActiveProfiles("test")
class AddFromLinkedInIntegrationTest {

    static final AtomicReference<ProfileReader.Request> LAST = new AtomicReference<>();

    @TestConfiguration
    static class StubReader {
        @Bean
        @Primary
        ProfileReader stubProfileReader() {
            return new ProfileReader() {
                public boolean enabled() {
                    return true;
                }

                public Suggestion read(Request request) {
                    LAST.set(request);
                    return new Suggestion("Person One", "Director of Engineering", "Alpha Corp", "Bengaluru",
                        "ENGINEERING_LEADER", "Directs engineering.", "2nd-degree connection");
                }
            };
        }
    }

    static final String ADMIN = "admin@example-gcc.test";
    static final String DELIVERY = "delivery.person@example-gcc.test";

    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    @Autowired UserDirectory users;

    MockMvc mvc;
    long accountId;

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
        accountId = jdbc.sql("SELECT id FROM account").query(Long.class).single();
    }

    @Test
    void readsAPastedProfileWithoutSavingAnything() throws Exception {
        mvc.perform(get("/api/v1/assist/status").with(user(ADMIN))).andExpect(jsonPath("$.profileReading").value(true));
        mvc.perform(multipart("/api/v1/assist/read-profile")
                .file(new MockMultipartFile("image", "shot.png", "image/png", new byte[] {1, 2, 3}))
                .param("linkedinUrl", "https://www.linkedin.com/in/person-one-test/")
                .param("text", "Person One · 2nd\nDirector of Engineering at Alpha Corp\nBengaluru")
                .with(user(ADMIN)).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fullName").value("Person One"))
            .andExpect(jsonPath("$.persona").value("ENGINEERING_LEADER"));
        assertThat(LAST.get().imageMediaType()).isEqualTo("image/png");
        assertThat(LAST.get().personaCodes()).contains("CENTRE_HEAD", "OTHER");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM contact").query(Integer.class).single()).isZero();
    }

    @Test
    void needsTextOrAScreenshotAndRejectsOtherFiles() throws Exception {
        mvc.perform(multipart("/api/v1/assist/read-profile").with(user(ADMIN)).with(csrf()))
            .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/assist/read-profile")
                .file(new MockMultipartFile("image", "x.pdf", "application/pdf", new byte[] {1}))
                .with(user(ADMIN)).with(csrf()))
            .andExpect(status().isBadRequest());
    }

    @Test
    void savesTheCheckedContactOnceAndRecordsTheSource() throws Exception {
        String body = """
            {"accountId": %d, "fullName": "Person One", "title": "Director of Engineering",
             "persona": "engineering_leader", "linkedinUrl": "https://www.linkedin.com/in/person-one-test/",
             "city": "Bengaluru", "source": "LinkedIn", "consentBasis": "business_contact"}
            """.formatted(accountId);
        mvc.perform(post("/api/v1/contacts").with(user(ADMIN)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated());
        assertThat(jdbc.sql("SELECT source FROM contact").query(String.class).single()).isEqualTo("LinkedIn");
        mvc.perform(post("/api/v1/contacts").with(user(ADMIN)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.problems", hasItem(containsString("already a contact"))));
    }

    @Test
    void validatesTheContact() throws Exception {
        mvc.perform(post("/api/v1/contacts").with(user(ADMIN)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountId\": %d, \"fullName\": \"\", \"linkedinUrl\": \"https://example.com/x\"}".formatted(accountId)))
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.problems", hasItem("Enter the person's full name.")))
            .andExpect(jsonPath("$.problems", hasItem(containsString("linkedin.com"))))
            .andExpect(jsonPath("$.problems", hasItem(containsString("FR-CON-06"))));
    }

    @Test
    void deliveryCannotAddContactsOrReadProfiles() throws Exception {
        mvc.perform(multipart("/api/v1/assist/read-profile").param("text", "x").with(user(DELIVERY)).with(csrf()))
            .andExpect(status().isForbidden());
    }
}
