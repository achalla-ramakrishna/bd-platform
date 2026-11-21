package com.codewalnut.bd.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
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

/** GTN profile pages (ADR-0019): the page, video ranges, links, what visits do, the dashboard. Fictional people. */
@SpringBootTest
@ActiveProfiles("test")
class ProfileIntegrationTest {

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
    static final String DELIVERY = "delivery.person@example-gcc.test";

    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    @Autowired UserDirectory users;
    @Autowired NetworkService network;

    MockMvc mvc;
    LocalDate today;
    long connection;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        TestData.clear(jdbc);
        SENT.clear();
        long admin = users.recordSignIn(ADMIN, "Admin Person").id();
        users.setRoles(users.recordSignIn(DELIVERY, "Delivery Person").id(), EnumSet.of(Role.DELIVERY), admin);
        today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        jdbc.sql("""
                INSERT INTO connection (full_name, current_company, email, whatsapp, created_by)
                VALUES ('Leader Two', 'Beta Inc', 'leader.two@example-gcc.test', '919800000002', :u)
                """).param("u", admin).update();
        connection = jdbc.sql("SELECT id FROM connection").query(Long.class).single();
        jdbc.sql("INSERT INTO connection_knower (connection_id, user_id, strength, connected_on) VALUES (:c, :u, 'CLOSE', :d)")
            .param("c", connection).param("u", admin).param("d", today.minusYears(2)).update();
    }

    ResultActions json(String who, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, String body)
            throws Exception {
        return mvc.perform(req.with(user(who)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    long startQuiet() throws Exception {
        String body = json(ADMIN, post("/api/v1/network/tracks"), "{\"connectionId\": %d, \"channel\": \"LINKEDIN\"}".formatted(connection))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long track = ((Number) JsonPath.read(body, "$.detail.track.id")).longValue();
        jdbc.sql("UPDATE network_track SET last_out_on = :d WHERE id = :id").param("d", today.minusDays(8)).param("id", track).update();
        network.sweep(today);
        return track;
    }

    String token(long track) throws Exception {
        String url = JsonPath.read(json(ADMIN, post("/api/v1/network/tracks/" + track + "/profile"), "{}")
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.url");
        return url.substring(url.lastIndexOf('/') + 1);
    }

    ResultActions event(String token, String json) throws Exception {
        return mvc.perform(post("/p/" + token + "/events").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    /** A fake MP4: the "ftyp" box marker, then 2.5 MB of numbered bytes so ranges across chunks can be checked. */
    static byte[] fakeMp4() {
        byte[] b = new byte[(int) (2.5 * 1024 * 1024)];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) (i % 251);
        }
        b[4] = 'f';
        b[5] = 't';
        b[6] = 'y';
        b[7] = 'p';
        return b;
    }

    @Test
    void eachPersonHasAPageWithVideoServedInRanges() throws Exception {
        mvc.perform(get("/api/v1/network/profile").with(user(ADMIN)))
            .andExpect(jsonPath("$.id").value(0))
            .andExpect(jsonPath("$.headline").value(ProfileService.DEFAULT_HEADLINE));
        json(ADMIN, put("/api/v1/network/profile"), """
                {"headline": "Engineering that ships", "intro": "Short intro.", "proofPoints": ["One", "Two", "Three", "Four"]}
                """).andExpect(status().isUnprocessableEntity());
        json(ADMIN, put("/api/v1/network/profile"), "{\"headline\": \"x\", \"bookingUrl\": \"http://insecure.test\"}")
            .andExpect(status().isUnprocessableEntity());
        json(ADMIN, put("/api/v1/network/profile"), """
                {"headline": "Engineering that ships <safely>", "intro": "Short intro.", "proofPoints": ["Proof one", "", "Proof two"],
                 "bookingUrl": "https://calendar.example-gcc.test/book", "whatsapp": "+91 98000 00001", "interestedEnabled": true}
                """).andExpect(status().isOk())
            .andExpect(jsonPath("$.proofPoints.length()").value(2))
            .andExpect(jsonPath("$.whatsapp").value("919800000001"));
        json(DELIVERY, put("/api/v1/network/profile"), "{\"headline\": \"x\"}").andExpect(status().isForbidden());

        mvc.perform(put("/api/v1/network/profile/video").with(user(ADMIN)).with(csrf()).contentType("video/mp4").content("not a video".getBytes()))
            .andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/api/v1/network/profile/video").with(user(ADMIN)).with(csrf()).contentType("image/png").content(fakeMp4()))
            .andExpect(status().isUnprocessableEntity());
        byte[] video = fakeMp4();
        mvc.perform(put("/api/v1/network/profile/video").with(user(ADMIN)).with(csrf()).contentType("video/mp4").content(video))
            .andExpect(status().isOk()).andExpect(jsonPath("$.videoBytes").value(video.length));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM profile_video_chunk").query(Integer.class).single()).isEqualTo(3);
        mvc.perform(put("/api/v1/network/profile/captions").with(user(ADMIN)).with(csrf()).contentType("text/vtt").content("nope"))
            .andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/api/v1/network/profile/captions").with(user(ADMIN)).with(csrf()).contentType("text/vtt")
                .content("WEBVTT\n\n00:00.000 --> 00:02.000\nHello"))
            .andExpect(jsonPath("$.hasCaptions").value(true));

        long track = startQuiet();
        String token = token(track);
        assertThat(token).hasSize(22);
        // Public: no sign-in. The page greets them, escapes content, and has a nonce-based CSP.
        String html = mvc.perform(get("/p/" + token)).andExpect(status().isOk())
            .andExpect(header().string("Content-Security-Policy", Matchers.containsString("script-src 'nonce-")))
            .andExpect(header().string("X-Robots-Tag", "noindex, nofollow"))
            .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Hi Leader,").contains("Engineering that ships &lt;safely&gt;").contains("Book a 20-minute call")
            .contains("https://wa.me/919800000001").contains("<track kind=\"captions\"").contains("Proof two")
            .doesNotContain("Beta Inc").doesNotContain("leader.two@");
        mvc.perform(get("/p/AAAAAAAAAAAAAAAAAAAAAA")).andExpect(status().isNotFound())
            .andExpect(content().string(Matchers.containsString("no longer active")));
        mvc.perform(get("/p/" + token + "/captions.vtt")).andExpect(content().string(Matchers.startsWith("WEBVTT")));

        // A range across the first chunk boundary returns exactly those bytes.
        int from = 1024 * 1024 - 10;
        byte[] part = mvc.perform(get("/p/" + token + "/video").header("Range", "bytes=" + from + "-" + (from + 19)))
            .andExpect(status().isPartialContent())
            .andExpect(header().string("Content-Range", "bytes " + from + "-" + (from + 19) + "/" + video.length))
            .andReturn().getResponse().getContentAsByteArray();
        assertThat(part).isEqualTo(Arrays.copyOfRange(video, from, from + 20));
        byte[] tail = mvc.perform(get("/p/" + token + "/video").header("Range", "bytes=-5")).andExpect(status().isPartialContent())
            .andReturn().getResponse().getContentAsByteArray();
        assertThat(tail).isEqualTo(Arrays.copyOfRange(video, video.length - 5, video.length));
        byte[] whole = mvc.perform(get("/p/" + token + "/video")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(whole).isEqualTo(video);
        mvc.perform(get("/p/" + token + "/video").header("Range", "bytes=99999999-")).andExpect(status().isRequestedRangeNotSatisfiable());

        mvc.perform(get("/api/v1/network/profile/preview").with(user(ADMIN))).andExpect(status().isOk())
            .andExpect(content().string(Matchers.containsString("Nothing is recorded while you look")));
        mvc.perform(delete("/api/v1/network/profile/video").with(user(ADMIN)).with(csrf())).andExpect(jsonPath("$.videoBytes").isEmpty());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM profile_video_chunk").query(Integer.class).single()).isZero();
    }

    @Test
    void aVisitFromAQuietContactMakesAskNowTheNextActionAndTellsTheOwner() throws Exception {
        long track = startQuiet();
        // The direct-ask template carries their own link.
        String today0 = mvc.perform(get("/api/v1/network/today").with(user(ADMIN))).andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(today0, "$.items[0].task")).isEqualTo("DIAGNOSE");
        String token = token(track);

        event(token, "{\"kind\": \"VISIT\"}").andExpect(status().isNoContent());
        event(token, "{\"kind\": \"VISIT\"}").andExpect(status().isNoContent());
        event(token, "{\"kind\": \"HACK\"}").andExpect(status().isNoContent());
        event(token, "{\"kind\": \"VIDEO_PROGRESS\", \"value\": \"33\"}").andExpect(status().isNoContent());
        event("AAAAAAAAAAAAAAAAAAAAAA", "{\"kind\": \"VISIT\"}").andExpect(status().isNoContent());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM profile_event").query(Integer.class).single()).isEqualTo(2);

        String body = mvc.perform(get("/api/v1/network/today").with(user(ADMIN)))
            .andExpect(jsonPath("$.items[0].task").value("ASK_NOW"))
            .andExpect(jsonPath("$.items[0].taskLabel").value("Ask now: they opened your page"))
            .andExpect(jsonPath("$.items[0].track.whyReason").value("INERTIA"))
            .andExpect(jsonPath("$.items[0].suggestion.channel").value("WHATSAPP"))
            .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(body, "$.items[0].suggestion.body")).contains("/p/" + token);
        assertThat(SENT).hasSize(1);
        assertThat(SENT.get(0)[0]).isEqualTo(ADMIN);
        assertThat(SENT.get(0)[1]).isEqualTo("Leader Two opened your page");
        assertThat(SENT.get(0)[2]).contains("/network/" + track);

        event(token, "{\"kind\": \"VIDEO_PROGRESS\", \"value\": \"75\"}");
        event(token, "{\"kind\": \"VIDEO_PROGRESS\", \"value\": \"50\"}");
        event(token, "{\"kind\": \"CTA_CLICK\", \"value\": \"WHATSAPP\"}");
        event(token, "{\"kind\": \"INTERESTED\"}");
        String page = mvc.perform(get("/api/v1/network/tracks/" + track).with(user(ADMIN)))
            .andExpect(jsonPath("$.detail.track.bestVideoPct").value(75))
            .andExpect(jsonPath("$.detail.track.askNow").value(true))
            .andExpect(jsonPath("$.detail.track.interestedAt").isNotEmpty())
            .andReturn().getResponse().getContentAsString();
        List<String> touches = JsonPath.read(page, "$.detail.touches[?(@.kind == 'VISIT')].summary");
        assertThat(touches).containsExactlyInAnyOrder("Opened your profile page", "Watched 75% of your video",
            "Clicked \"Chat on WhatsApp\" on your page", "Clicked \"Interested, tell me more\"");
        assertThat(SENT).hasSize(2); // Interested always tells the owner; the rest only once.
        assertThat(SENT.get(1)[1]).contains("Interested");

        mvc.perform(get("/api/v1/network/tracks/" + track + "/profile").with(user(ADMIN)))
            .andExpect(jsonPath("$.visits").value(2)) // two page opens
            .andExpect(jsonPath("$.bestVideoPct").value(75))
            .andExpect(jsonPath("$.ctaClicks").value(1))
            .andExpect(jsonPath("$.url").value(Matchers.endsWith("/p/" + token)));

        // Sending the ask clears it.
        json(ADMIN, post("/api/v1/network/tracks/" + track + "/actions"), "{\"action\": \"SENT\", \"channel\": \"WHATSAPP\"}")
            .andExpect(jsonPath("$.detail.track.askNow").value(false))
            .andExpect(jsonPath("$.detail.track.nudgesSent").value(1));

        // The dashboard.
        mvc.perform(get("/api/v1/network/dashboard").with(user(ADMIN)))
            .andExpect(jsonPath("$.byStage.UNRESPONSIVE").value(1))
            .andExpect(jsonPath("$.cohort.started").value(1))
            .andExpect(jsonPath("$.cohort.responded").value(0))
            .andExpect(jsonPath("$.reasons[0].reason").value("INERTIA"))
            .andExpect(jsonPath("$.reasons[0].stillQuiet").value(1))
            .andExpect(jsonPath("$.nudges").value(1))
            .andExpect(jsonPath("$.profile.links").value(1))
            .andExpect(jsonPath("$.profile.visitors").value(1))
            .andExpect(jsonPath("$.profile.watched75").value(1))
            .andExpect(jsonPath("$.profile.interested").value(1));
        json(ADMIN, post("/api/v1/network/tracks/" + track + "/actions"),
                "{\"action\": \"REPLIED\", \"outcome\": \"POSITIVE\", \"channel\": \"WHATSAPP\"}").andExpect(status().isOk());
        mvc.perform(get("/api/v1/network/dashboard?mine=true").with(user(ADMIN)))
            .andExpect(jsonPath("$.cohort.responded").value(1))
            .andExpect(jsonPath("$.nudgedThenReplied").value(1))
            .andExpect(jsonPath("$.reasons[0].replied").value(1));
        mvc.perform(get("/api/v1/network/dashboard?source=Nobody").with(user(ADMIN))).andExpect(jsonPath("$.cohort.started").value(0));
        mvc.perform(get("/api/v1/network/dashboard?from=2026-12-01&to=2026-11-01").with(user(ADMIN)))
            .andExpect(status().isUnprocessableEntity());

        // A visit from someone already talking is logged but asks nothing.
        event(token, "{\"kind\": \"CTA_CLICK\", \"value\": \"BOOK\"}");
        mvc.perform(get("/api/v1/network/tracks/" + track).with(user(ADMIN))).andExpect(jsonPath("$.detail.track.askNow").value(false));
    }
}
