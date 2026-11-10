package com.codewalnut.bd.assist;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
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

/** The BD assistant matches what was pasted to conversations, connections and accounts. Stand-in reader; fictional data. */
@SpringBootTest
@ActiveProfiles("test")
class AssistantIntegrationTest {

    static final AtomicReference<NoteReader.Request> LAST = new AtomicReference<>();
    static final AtomicReference<NoteReader.Reading> NEXT = new AtomicReference<>();

    @TestConfiguration
    static class StubReader {
        @Bean
        @Primary
        NoteReader stubNoteReader() {
            return new NoteReader() {
                @Override
                public boolean enabled() {
                    return true;
                }

                @Override
                public Reading read(Request request) {
                    LAST.set(request);
                    return NEXT.get();
                }
            };
        }
    }

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

    NoteReader.Reading reading(String name, String company) {
        return new NoteReader.Reading("Call with " + name, name, "Engineering Director", company, "Bengaluru", "",
            "AI pod", "CALL", TODAY.toString(), "Back on Friday; wants a show and tell", "MEETING_BOOKED",
            "Book the show and tell", TODAY.plusDays(3).toString(), false);
    }

    @Test
    void anUnknownPersonAtAKnownAccountSuggestsStartingAConversation() throws Exception {
        NEXT.set(reading("Person One", "Alpha Corp"));
        mvc.perform(multipart("/api/v1/assistant/read").param("text", "Spoke to Person One at Alpha, back Friday")
                .with(user(ADMIN)).with(csrf()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.suggestion").value("START_CONVERSATION"))
            .andExpect(jsonPath("$.accounts[0].id").value(account))
            .andExpect(jsonPath("$.reading.reply").value("Call with Person One"));
        org.assertj.core.api.Assertions.assertThat(LAST.get().today()).isEqualTo(TODAY);
        org.assertj.core.api.Assertions.assertThat(LAST.get().channels()).contains("LINKEDIN_MESSAGE");
    }

    @Test
    void anOpenConversationWithThePersonSuggestsAnUpdate() throws Exception {
        mvc.perform(post("/api/v1/conversations").with(user(ADMIN)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"accountId": %d, "personName": "Person One", "topic": "AI pod", "channel": "EMAIL",
                     "occurredOn": "%s", "summary": "Sent a note", "outcome": "NO_REPLY"}""".formatted(account, TODAY)))
            .andExpect(status().isCreated());
        NEXT.set(reading("Person One", ""));
        mvc.perform(multipart("/api/v1/assistant/read")
                .file(new MockMultipartFile("images", "chat.png", "image/png", new byte[] {1, 2, 3}))
                .with(user(ADMIN)).with(csrf()))
            .andExpect(jsonPath("$.suggestion").value("UPDATE_CONVERSATION"))
            .andExpect(jsonPath("$.conversations[0].personName").value("Person One"))
            .andExpect(jsonPath("$.accounts[0].id").value(account));
        org.assertj.core.api.Assertions.assertThat(LAST.get().images()).hasSize(1);
    }

    @Test
    void someoneWeDoNotKnowSuggestsAddingAConnection() throws Exception {
        NEXT.set(reading("Person Two", "Gamma Group"));
        mvc.perform(multipart("/api/v1/assistant/read").param("text", "Met Person Two at a meetup").with(user(ADMIN)).with(csrf()))
            .andExpect(jsonPath("$.suggestion").value("ADD_CONNECTION"))
            .andExpect(jsonPath("$.accounts.length()").value(0));
    }

    @Test
    void needsSomethingToReadAndAPipelineRole() throws Exception {
        mvc.perform(multipart("/api/v1/assistant/read").with(user(ADMIN)).with(csrf())).andExpect(status().is4xxClientError());
        mvc.perform(multipart("/api/v1/assistant/read").param("text", "x").with(user(DELIVERY)).with(csrf()))
            .andExpect(status().isForbidden());
        mvc.perform(multipart("/api/v1/assistant/read")
                .file(new MockMultipartFile("images", "a.pdf", "application/pdf", new byte[] {1}))
                .with(user(ADMIN)).with(csrf()))
            .andExpect(status().is4xxClientError());
    }
}
