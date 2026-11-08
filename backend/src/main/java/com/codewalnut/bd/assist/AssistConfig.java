package com.codewalnut.bd.assist;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;

/** Switches Claude-assisted features on when ANTHROPIC_API_KEY is set (ADR-0009). */
@Configuration
class AssistConfig {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AssistConfig.class);

    @Bean
    ProfileReader profileReader(@Value("${app.ai.anthropic-api-key:}") String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            LOG.info("AI profile reading: DISABLED (no ANTHROPIC_API_KEY).");
            return new DisabledProfileReader();
        }
        LOG.info("AI profile reading: ENABLED ({}).", ClaudeProfileReader.MODEL);
        return new ClaudeProfileReader(AnthropicOkHttpClient.builder().apiKey(apiKey.strip()).build());
    }
}
