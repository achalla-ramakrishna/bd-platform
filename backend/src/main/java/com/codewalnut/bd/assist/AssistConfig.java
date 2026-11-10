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
            LOG.info("AI profile reading and account research: DISABLED (no ANTHROPIC_API_KEY).");
            return new DisabledProfileReader();
        }
        LOG.info("AI profile reading: ENABLED ({}).", ClaudeProfileReader.MODEL);
        return new ClaudeProfileReader(client(apiKey));
    }

    @Bean
    AccountResearcher accountResearcher(@Value("${app.ai.anthropic-api-key:}") String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return new DisabledAccountResearcher();
        }
        LOG.info("AI account research: ENABLED ({} with web search).", ClaudeAccountResearcher.MODEL);
        return new ClaudeAccountResearcher(client(apiKey));
    }

    @Bean
    GccFinder gccFinder(@Value("${app.ai.anthropic-api-key:}") String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return new DisabledGccFinder();
        }
        LOG.info("Find GCCs: ENABLED ({} with web search).", ClaudeGccFinder.MODEL);
        return new ClaudeGccFinder(client(apiKey));
    }

    @Bean
    NoteReader noteReader(@Value("${app.ai.anthropic-api-key:}") String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return new DisabledNoteReader();
        }
        LOG.info("BD assistant: ENABLED ({}).", ClaudeNoteReader.MODEL);
        return new ClaudeNoteReader(client(apiKey));
    }

    private static com.anthropic.client.AnthropicClient client(String apiKey) {
        // Research runs several web searches; allow up to 5 minutes per call.
        return AnthropicOkHttpClient.builder().apiKey(apiKey.strip()).timeout(java.time.Duration.ofMinutes(5)).build();
    }
}
