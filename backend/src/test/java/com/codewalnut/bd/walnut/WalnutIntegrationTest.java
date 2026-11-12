package com.codewalnut.bd.walnut;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.codewalnut.bd.TestData;
import com.codewalnut.bd.admin.UserDirectory;
import com.codewalnut.bd.assist.Coach;
import com.codewalnut.bd.shared.Role;
import com.jayway.jsonpath.JsonPath;

/** Walnut, the BD agent: briefs once a day with follow-ups, targets and questions; chat; email; who may use it. */
@SpringBootTest
@ActiveProfiles("test")
class WalnutIntegrationTest {

    static final AtomicReference<Coach.Request> ASKED = new AtomicReference<>();
    static final AtomicReference<Coach.Reply> ANSWER = new AtomicReference<>();
    static final List<String[]> SENT = new ArrayList<>();

    @TestConfiguration
    static class Stubs {
        @Bean
        @Primary
        Coach stubCoach() {
            return new Coach() {
                @Override
                public boolean enabled() {
                    return true;
                }

                @Override
                public Reply reply(Request request) {
                    ASKED.set(request);
                    return ANSWER.get();
                }
            };
        }

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
    static final String SECOND = "second.admin@example-gcc.test";
    static final String SALES = "sales.person@example-gcc.test";

    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    @Autowired UserDirectory users;
    @Autowired WalnutService walnut;

    MockMvc mvc;
    long account;
    long overdue;
    LocalDate today;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        TestData.clear(jdbc);
        SENT.clear();
        today = walnut.today();
        long admin = users.recordSignIn(ADMIN, "Admin Person").id();
        users.setRoles(users.recordSignIn(SECOND, "Second Admin").id(), EnumSet.of(Role.ADMIN), admin);
        users.setRoles(users.recordSignIn(SALES, "Sales Person").id(), EnumSet.of(Role.ACCOUNT_EXECUTIVE), admin);
        jdbc.sql("INSERT INTO parent_company (name) VALUES ('Alpha Corp')").update();
        for (String[] a : new String[][] {{"Alpha Bengaluru", "alpha-gcc.test"}, {"Beta Pune", "beta-gcc.test"},
                {"Gamma Chennai", "gamma-gcc.test"}, {"Delta Hyderabad", "delta-gcc.test"}}) {
            jdbc.sql("""
                    INSERT INTO account (parent_company_id, name, city, website_domain, relationship, owner_user_id, source)
                    SELECT id, :n, 'Bengaluru', :d, 'PROSPECT', :o, 'test' FROM parent_company
                    """).param("n", a[0]).param("d", a[1]).param("o", admin).update();
        }
        account = jdbc.sql("SELECT id FROM account WHERE name = 'Alpha Bengaluru'").query(Long.class).single();
        String body = mvc.perform(post("/api/v1/conversations").with(user(ADMIN)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"accountId": %d, "personName": "Person One", "topic": "AI pod", "channel": "EMAIL",
                     "occurredOn": "%s", "summary": "Sent a note", "outcome": "NO_REPLY", "nextStep": "Call them", "nextStepDue": "%s"}
                    """.formatted(account, today, today.plusDays(1))))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        overdue = ((Number) JsonPath.read(body, "$.conversation.id")).longValue();
        jdbc.sql("UPDATE conversation SET next_step_due = :d WHERE id = :id").param("d", today.minusDays(3)).param("id", overdue).update();
        mvc.perform(post("/api/v1/opportunities").with(user(ADMIN)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountId\": %d, \"name\": \"React team\", \"type\": \"STAFF_AUGMENTATION\"}".formatted(account)))
            .andExpect(status().isCreated());
    }

    @Test
    void writesOneBriefADayWithFollowUpsTargetsAndQuestions() throws Exception {
        String first = mvc.perform(post("/api/v1/walnut/brief").with(user(ADMIN)).with(csrf()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String body = JsonPath.read(first, "$.body");
        assertThat(body).contains("[Person One](/conversations/" + overdue + ")").contains("overdue since")
            .contains("[React team](/opportunities/").contains("Missing: decision maker")
            .contains("accounts to approach").contains("send a LinkedIn connection request").contains("## Questions for you")
            .contains("Did you follow up with Person One at Alpha Bengaluru?");
        assertThat(first).contains("Connection note for").contains("Hi [name], I'm with CodeWalnut");
        Number id = JsonPath.read(first, "$.id");
        mvc.perform(post("/api/v1/walnut/brief").with(user(ADMIN)).with(csrf())).andExpect(jsonPath("$.id").value(id));

        // The two Admins are dealt different new accounts.
        String second = JsonPath.read(mvc.perform(post("/api/v1/walnut/brief").with(user(SECOND)).with(csrf()))
            .andReturn().getResponse().getContentAsString(), "$.body");
        List<String> mine = targets(body);
        List<String> theirs = targets(second);
        assertThat(mine).isNotEmpty();
        assertThat(theirs).isNotEmpty().doesNotContainAnyElementsOf(mine);

        mvc.perform(get("/api/v1/walnut/messages").with(user(ADMIN))).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/v1/walnut/messages").with(user(SECOND))).andExpect(jsonPath("$.length()").value(1));
    }

    static List<String> targets(String brief) {
        String section = brief.substring(brief.indexOf("accounts to approach"));
        section = section.substring(0, section.indexOf("\n\n"));
        return section.lines().filter(l -> l.startsWith("- [")).map(l -> l.substring(3, l.indexOf(']'))).toList();
    }

    long conversation(String person, String accountName, String channel, LocalDate on) throws Exception {
        long acc = jdbc.sql("SELECT id FROM account WHERE name = :n").param("n", accountName).query(Long.class).single();
        String body = mvc.perform(post("/api/v1/conversations").with(user(ADMIN)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"accountId": %d, "personName": "%s", "topic": "Introduction", "channel": "%s",
                     "occurredOn": "%s", "summary": "Sent a connection request", "outcome": "NO_REPLY"}
                    """.formatted(acc, person, channel, on)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.conversation.id")).longValue();
    }

    @Test
    void followsEachConversationThroughTheLifeCycle() throws Exception {
        long waiting = conversation("Person Two", "Beta Pune", "LINKEDIN_CONNECTION", today.minusDays(5));
        long accepted = conversation("Person Three", "Gamma Chennai", "LINKEDIN_CONNECTION", today.minusDays(2));
        long gamma = jdbc.sql("SELECT id FROM account WHERE name = 'Gamma Chennai'").query(Long.class).single();
        mvc.perform(post("/api/v1/connections").with(user(ADMIN)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"person": {"fullName": "Person Three", "accountId": %d, "accountLink": "WORKS_THERE"},
                     "knowing": {"strength": "ACQUAINTANCE"}}""".formatted(gamma)))
            .andExpect(status().isCreated());
        long nudge = conversation("Person Four", "Delta Hyderabad", "EMAIL", today.minusDays(6));

        String body = JsonPath.read(mvc.perform(post("/api/v1/walnut/brief").with(user(ADMIN)).with(csrf()))
            .andReturn().getResponse().getContentAsString(), "$.body");
        assertThat(body).contains("## Move each conversation one step")
            .contains("[Person Two](/conversations/" + waiting + ") at Beta Pune: connection request sent 5 days ago")
            .contains("Has Person Two at Beta Pune accepted your connection request?")
            .contains("[Person Three](/conversations/" + accepted + ") at Gamma Chennai: **connected: send the first message now.**")
            .contains("[Person Four](/conversations/" + nudge + ") at Delta Hyderabad: no reply for 6 days");
        String actions = mvc.perform(get("/api/v1/walnut/messages").with(user(ADMIN))).andReturn().getResponse().getContentAsString();
        assertThat(actions).contains("First message to Person Three").contains("Hi Person, thanks for connecting")
            .doesNotContain("accounts to approach");
    }

    @Test
    void remindsAboutRealisingTheDeals() throws Exception {
        long opp = jdbc.sql("SELECT id FROM opportunity").query(Long.class).single();
        for (String role : new String[] {"React engineer", "QA engineer"}) {
            mvc.perform(post("/api/v1/opportunities/{id}/requirements", opp).with(user(ADMIN)).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("""
                        {"role": "%s", "skills": ["React"], "seniority": "SENIOR", "headcount": 2, "locationMode": "REMOTE",
                         "startDate": "%s", "durationMonths": 6}""".formatted(role, today.plusDays(20))))
                .andExpect(status().isCreated());
        }
        long react = jdbc.sql("SELECT id FROM opportunity_requirement WHERE role = 'React engineer'").query(Long.class).single();
        mvc.perform(post("/api/v1/requirements/{id}/candidates", react).with(user(ADMIN)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"candidateName\": \"Candidate C\"}"))
            .andExpect(status().isCreated());
        jdbc.sql("UPDATE submission SET status = 'SHARED', status_on = :d").param("d", today.minusDays(4)).update();

        String body = JsonPath.read(mvc.perform(post("/api/v1/walnut/brief").with(user(ADMIN)).with(csrf()))
            .andReturn().getResponse().getContentAsString(), "$.body");
        assertThat(body).contains("## Realise the deals")
            .contains("[2 × QA engineer](/opportunities/" + opp + ") at Alpha Bengaluru: nobody proposed yet")
            .contains("[Candidate C](/opportunities/" + opp + ") for React engineer at Alpha Bengaluru: profile shared 4 days ago")
            .contains("Who can we put forward for 2 × QA engineer at Alpha Bengaluru?")
            .doesNotContain("2 × React engineer](/opportunities/" + opp + ") at Alpha Bengaluru: nobody proposed");
    }

    @Test
    void chatsAndKeepsOnlyActionsThePageCanApply() throws Exception {
        ANSWER.set(new Coach.Reply("Logged? Not yet: here is the update for [Person One](/conversations/" + overdue + ").", List.of(
            new Coach.Action("LOG_UPDATE", "Log the call", "", "", overdue, "Send profiles", today.plusDays(2).toString(),
                "PIGEON", "Called; they want two React engineers", "MEETING_BOOKED"),
            new Coach.Action("SET_NEXT_STEP", "", "", "", 999_999L, "Ghost", "", "", "", ""),
            new Coach.Action("OPEN", "Bad link", "https://evil.example/", "", null, "", "", "", "", ""),
            new Coach.Action("OPEN", "Open account", "/accounts/" + account, "", null, "", "", "", "", ""),
            new Coach.Action("DRAFT", "Follow-up", "", "Hi Person One, sharing two profiles.", null, "", "", "", "", ""))));
        mvc.perform(post("/api/v1/walnut/messages").with(user(ADMIN)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\": \"Called Person One, they want two React engineers\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].author").value("PERSON"))
            .andExpect(jsonPath("$[1].author").value("WALNUT"))
            .andExpect(jsonPath("$[1].actions.length()").value(3))
            .andExpect(jsonPath("$[1].actions[0].type").value("LOG_UPDATE"))
            .andExpect(jsonPath("$[1].actions[0].channel").value("LINKEDIN_MESSAGE"))
            .andExpect(jsonPath("$[1].actions[1].path").value("/accounts/" + account))
            .andExpect(jsonPath("$[1].actions[2].type").value("DRAFT"));
        assertThat(ASKED.get().name()).isEqualTo("Admin Person");
        assertThat(ASKED.get().snapshot()).contains("Person One").contains("React team");
        assertThat(ASKED.get().history()).isEmpty();

        ANSWER.set(new Coach.Reply("Noted.", List.of()));
        mvc.perform(post("/api/v1/walnut/messages").with(user(ADMIN)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\": \"Thanks\"}")).andExpect(status().isOk());
        assertThat(ASKED.get().history()).hasSize(2);
        mvc.perform(get("/api/v1/walnut/messages").with(user(ADMIN))).andExpect(jsonPath("$.length()").value(4));
        mvc.perform(get("/api/v1/walnut/messages").with(user(SECOND))).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(post("/api/v1/walnut/messages").with(user(ADMIN)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\": \"  \"}")).andExpect(status().isUnprocessableContent());
    }

    @Test
    void morningRunBriefsAndEmailsEachAdminOnce() throws Exception {
        mvc.perform(put("/api/v1/walnut/settings").with(user(SECOND)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"emailBrief\": false}")).andExpect(jsonPath("$.emailBrief").value(false));
        walnut.morning(today);
        walnut.morning(today);
        assertThat(SENT).hasSize(1);
        assertThat(SENT.get(0)[0]).isEqualTo(ADMIN);
        assertThat(SENT.get(0)[2]).contains("<li>").contains("Person One");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM walnut_brief").query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM walnut_brief w JOIN app_user u ON u.id = w.user_id WHERE u.email = :e")
            .param("e", SALES).query(Integer.class).single()).isZero();
        mvc.perform(post("/api/v1/walnut/brief/email").with(user(SECOND)).with(csrf())).andExpect(jsonPath("$.sent").value(true));
        assertThat(SENT).hasSize(2);
    }

    @Test
    void onlyThePeopleWalnutWorksWith() throws Exception {
        mvc.perform(get("/api/v1/walnut/status").with(user(SALES)))
            .andExpect(jsonPath("$.available").value(false))
            .andExpect(jsonPath("$.worksWith.length()").value(2));
        mvc.perform(get("/api/v1/walnut/messages").with(user(SALES))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/walnut/brief").with(user(SALES)).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/walnut/status").with(user(ADMIN))).andExpect(jsonPath("$.available").value(true));
    }

    @Test
    void emailHtmlKeepsLinksInsideTheApp() {
        String html = MailHtml.of("## Follow up\n- [Person <A>](/conversations/3) **today**\n- [x](javascript:alert(1))\n1. Question?",
            "https://bd.example-gcc.test");
        assertThat(html).contains("<h3").contains("href=\"https://bd.example-gcc.test/conversations/3\"")
            .contains("Person &lt;A&gt;").contains("<strong>today</strong>").contains("<ol").doesNotContain("javascript");
    }
}
