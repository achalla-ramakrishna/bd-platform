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
        LOG.info("AI profile reading: ENABLED ({}). Anthropic key: {}", ClaudeProfileReader.MODEL, hint(apiKey));
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

    @Bean
    Coach coach(@Value("${app.ai.anthropic-api-key:}") String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return new DisabledCoach();
        }
        LOG.info("Walnut chat: ENABLED ({}).", ClaudeCoach.MODEL);
        return new ClaudeCoach(client(apiKey));
    }

    private static com.anthropic.client.AnthropicClient client(String apiKey) {
        ClaudeErrors.keyHint = hint(apiKey);
        // Research runs several web searches; allow up to 5 minutes per call.
        return AnthropicOkHttpClient.builder().apiKey(clean(apiKey)).timeout(java.time.Duration.ofMinutes(5)).build();
    }

    /** The key as pasted into Railway, without surrounding spaces, quotes or a "Bearer " / "KEY=" prefix. */
    static String clean(String apiKey) {
        String k = apiKey.strip();
        if (k.startsWith("ANTHROPIC_API_KEY=")) {
            k = k.substring("ANTHROPIC_API_KEY=".length()).strip();
        }
        if (k.regionMatches(true, 0, "Bearer ", 0, 7)) {
            k = k.substring(7).strip();
        }
        while (k.length() >= 2 && (k.charAt(0) == '"' || k.charAt(0) == '\'') && k.charAt(k.length() - 1) == k.charAt(0)) {
            k = k.substring(1, k.length() - 1).strip();
        }
        return k;
    }

    /**
     * A safe description of the key for the deploy logs, to compare with the Claude Console's key list:
     * whether it looks like an Anthropic key, its length and its last 4 characters. Never the key.
     */
    static String hint(String apiKey) {
        String k = clean(apiKey);
        String shape = k.startsWith("sk-ant-") ? "starts sk-ant-" : "does NOT start sk-ant- (check the value)";
        String end = k.length() > 12 ? "ends ..." + k.substring(k.length() - 4) : "too short";
        String spaces = k.chars().anyMatch(Character::isWhitespace) ? ", contains spaces (check the value)" : "";
        return shape + ", " + k.length() + " characters, " + end + spaces;
    }
}
