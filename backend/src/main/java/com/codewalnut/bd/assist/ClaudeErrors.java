package com.codewalnut.bd.assist;

import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicInvalidDataException;
import com.anthropic.errors.AnthropicServiceException;

/** Turns Anthropic API errors into messages that say what to do. */
final class ClaudeErrors {

    private ClaudeErrors() {}

    /** Safe description of the key in use (AssistConfig.hint), shown with a 401 so an Admin can compare it. */
    static volatile String keyHint = "";

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ClaudeErrors.class);

    /** Any Claude failure, including network errors and answers that could not be read; logs the details. */
    static String explain(AnthropicException e) {
        if (e instanceof AnthropicServiceException se) {
            LOG.warn("Claude API error {}: {}", se.statusCode(), se.getMessage());
            return explain(se);
        }
        LOG.warn("Claude call failed", e);
        if (e instanceof AnthropicIoException) {
            return "Claude could not be reached (" + shortMessage(e) + "). Try again in a minute.";
        }
        if (e instanceof AnthropicInvalidDataException) {
            return "Claude's answer could not be read (" + shortMessage(e) + "). Try again; if it keeps happening, tell an Admin.";
        }
        return "Claude could not complete the request (" + shortMessage(e) + "). Try again; if it keeps happening, tell an Admin.";
    }

    private static String shortMessage(Throwable e) {
        String m = e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage().strip();
        m = m.replaceAll("\\s+", " ");
        return m.length() > 160 ? m.substring(0, 160) + "…" : m;
    }

    static String explain(AnthropicServiceException e) {
        String detail = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return switch (e.statusCode()) {
            case 401 -> "The Anthropic API key was rejected (401). An Admin should check ANTHROPIC_API_KEY in Railway: "
                + "it must be a valid key starting sk-ant-, with no quotes or spaces."
                + (keyHint.isEmpty() ? "" : " The key the app has " + keyHint + "; compare the last 4 with the Claude Console's API keys list.");
            case 403 -> "The Anthropic API key is not allowed to do this (403). Check the key's workspace permissions.";
            case 400 -> detail.contains("credit")
                ? "The Anthropic account has run out of credit. An Admin should add credit under Settings, Billing in the Anthropic Console."
                : "Claude rejected the request (400). Try again; if it keeps happening, tell an Admin.";
            case 429 -> "Too many requests to Claude just now (429). Wait a minute and try again.";
            case 529, 500, 502, 503 -> "Claude is busy or unavailable (" + e.statusCode() + "). Try again in a few minutes.";
            default -> "Claude could not complete the request (" + e.statusCode() + "). Try again later.";
        };
    }
}
