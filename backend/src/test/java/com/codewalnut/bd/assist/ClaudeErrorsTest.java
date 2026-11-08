package com.codewalnut.bd.assist;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.anthropic.core.http.Headers;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.UnauthorizedException;

class ClaudeErrorsTest {

    @Test
    void explainsARejectedKey() {
        AnthropicServiceException e = UnauthorizedException.builder().headers(Headers.builder().build()).body(com.anthropic.core.JsonValue.from(java.util.Map.of())).build();
        assertThat(ClaudeErrors.explain(e)).contains("ANTHROPIC_API_KEY").contains("sk-ant-");
    }
}
