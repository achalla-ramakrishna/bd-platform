package com.codewalnut.bd.walnut;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import tools.jackson.databind.json.JsonMapper;

/** Email for Walnut's briefs: on when MAIL_API_KEY and MAIL_FROM are set (ADR-0015). */
@Configuration
@EnableScheduling
class WalnutConfig {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(WalnutConfig.class);

    @Bean
    Mailer mailer(@Value("${app.mail.api-key:}") String apiKey, @Value("${app.mail.from:}") String from, JsonMapper json) {
        if (apiKey == null || apiKey.isBlank() || from == null || from.isBlank()) {
            LOG.info("Walnut email briefs: DISABLED (set MAIL_API_KEY and MAIL_FROM).");
            return new Mailer() {
                @Override
                public boolean enabled() {
                    return false;
                }

                @Override
                public void send(String to, String subject, String html) {
                    // Email is off; the brief is still in the app.
                }
            };
        }
        LOG.info("Walnut email briefs: ENABLED (from {}).", from);
        return new ResendMailer(apiKey, from, json);
    }
}
