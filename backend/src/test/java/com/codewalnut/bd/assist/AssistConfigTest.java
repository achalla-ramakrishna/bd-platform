package com.codewalnut.bd.assist;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AssistConfigTest {

    private static final String KEY = "sk-ant-api03-example-not-a-real-key-WXYZ";

    @Test
    void cleansKeysPastedWithQuotesSpacesOrPrefixes() {
        assertThat(AssistConfig.clean(KEY)).isEqualTo(KEY);
        assertThat(AssistConfig.clean("  \"" + KEY + "\"\n")).isEqualTo(KEY);
        assertThat(AssistConfig.clean("'" + KEY + "'")).isEqualTo(KEY);
        assertThat(AssistConfig.clean("ANTHROPIC_API_KEY=\"" + KEY + "\"")).isEqualTo(KEY);
        assertThat(AssistConfig.clean("Bearer " + KEY)).isEqualTo(KEY);
    }

    @Test
    void hintNeverShowsTheKey() {
        String hint = AssistConfig.hint("\"" + KEY + "\"");
        assertThat(hint).isEqualTo("starts sk-ant-, " + KEY.length() + " characters, ends ...WXYZ");
        assertThat(hint).doesNotContain("example-not-a-real");
        assertThat(AssistConfig.hint("abc")).contains("does NOT start sk-ant-").contains("too short");
    }
}
