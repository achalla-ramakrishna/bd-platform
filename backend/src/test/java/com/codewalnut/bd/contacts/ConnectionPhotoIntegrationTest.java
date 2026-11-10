package com.codewalnut.bd.contacts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.EnumSet;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.codewalnut.bd.TestData;
import com.codewalnut.bd.admin.UserDirectory;
import com.codewalnut.bd.shared.Role;
import com.jayway.jsonpath.JsonPath;

/** Connection photos (ADR-0014): set, read, replace, remove; only images; pipeline roles change them. */
@SpringBootTest
@ActiveProfiles("test")
class ConnectionPhotoIntegrationTest {

    static final String ADMIN = "admin@example-gcc.test";
    static final String DELIVERY = "delivery.person@example-gcc.test";

    @Autowired WebApplicationContext context;
    @Autowired JdbcClient jdbc;
    @Autowired UserDirectory users;

    MockMvc mvc;
    long id;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        TestData.clear(jdbc);
        long admin = users.recordSignIn(ADMIN, "Admin Person").id();
        users.setRoles(users.recordSignIn(DELIVERY, "Delivery Person").id(), EnumSet.of(Role.DELIVERY), admin);
        String body = mvc.perform(post("/api/v1/connections").with(user(ADMIN)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"person": {"fullName": "Person Photo"}, "knowing": {"strength": "ACQUAINTANCE"}}
                    """))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        id = ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    static byte[] png(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(40, 120, 200));
        g.fillOval(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    MockMultipartHttpServletRequestBuilder upload(byte[] bytes, String type) {
        MockMultipartHttpServletRequestBuilder b = multipart("/api/v1/connections/{id}/photo", id);
        b.file(new MockMultipartFile("file", "photo", type, bytes));
        b.with(r -> { r.setMethod("PUT"); return r; });
        return b;
    }

    @Test
    void setReadReplaceAndRemove() throws Exception {
        mvc.perform(get("/api/v1/connections/{id}", id).with(user(ADMIN)))
            .andExpect(jsonPath("$.photoVersion").doesNotExist());

        mvc.perform(upload(png(600, 400), "image/png").with(user(ADMIN)).with(csrf()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.photoVersion").isNumber());

        byte[] jpeg = mvc.perform(get("/api/v1/connections/{id}/photo", id).with(user(DELIVERY)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsByteArray();
        BufferedImage stored = ImageIO.read(new ByteArrayInputStream(jpeg));
        assertThat(stored.getWidth()).isEqualTo(256);
        assertThat(stored.getHeight()).isEqualTo(256);

        mvc.perform(upload(png(64, 64), "image/png").with(user(ADMIN)).with(csrf())).andExpect(status().isOk());
        assertThat(ImageIO.read(new ByteArrayInputStream(mvc.perform(get("/api/v1/connections/{id}/photo", id)
            .with(user(ADMIN))).andReturn().getResponse().getContentAsByteArray())).getWidth()).isEqualTo(64);

        mvc.perform(delete("/api/v1/connections/{id}/photo", id).with(user(ADMIN)).with(csrf()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.photoVersion").doesNotExist());
        mvc.perform(get("/api/v1/connections/{id}/photo", id).with(user(ADMIN))).andExpect(status().isNotFound());
    }

    @Test
    void rejectsWhatIsNotAPictureAndChecksRoles() throws Exception {
        mvc.perform(upload("<svg onload=alert(1)>".getBytes(), "image/png").with(user(ADMIN)).with(csrf()))
            .andExpect(status().isUnprocessableContent())
            .andExpect(jsonPath("$.problems[0]").value("Use a JPEG or PNG photo of at least 16 by 16 pixels."));
        mvc.perform(upload(png(64, 64), "image/png").with(user(DELIVERY)).with(csrf()))
            .andExpect(status().isForbidden());
    }

    @Test
    void goesWithTheConnection() throws Exception {
        mvc.perform(upload(png(64, 64), "image/png").with(user(ADMIN)).with(csrf())).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/connections/{id}/knowers/me", id).with(user(ADMIN)).with(csrf())).andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM connection_photo").query(Integer.class).single()).isZero();
    }
}
