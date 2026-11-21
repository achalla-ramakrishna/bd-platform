package com.codewalnut.bd.google;

import java.util.Base64;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connecting team members' Google accounts for GTN (ADR-0020), under {@code app.google}.
 *
 * @param clientId     the Google OAuth client (the one used for sign-in)
 * @param clientSecret its secret
 * @param tokenKey     32 random bytes, base64, that encrypt stored tokens (GOOGLE_TOKEN_KEY)
 * @param pollMinutes  how often replies are looked for (default 10)
 * @param pollEnabled  set false to stop polling (tests)
 */
@ConfigurationProperties("app.google")
public record GoogleProperties(String clientId, String clientSecret, String tokenKey, Integer pollMinutes, Boolean pollEnabled) {

    public GoogleProperties {
        pollMinutes = pollMinutes == null || pollMinutes < 1 ? 10 : pollMinutes;
        pollEnabled = pollEnabled == null || pollEnabled;
    }

    /** Client and key both set: the feature is on. */
    public boolean enabled() {
        return clientId != null && !clientId.isBlank() && !"not-configured".equals(clientId)
            && clientSecret != null && !clientSecret.isBlank() && !"not-configured".equals(clientSecret) && key() != null;
    }

    byte[] key() {
        if (tokenKey == null || tokenKey.isBlank()) {
            return null;
        }
        try {
            byte[] k = Base64.getDecoder().decode(tokenKey.trim());
            return k.length == 32 ? k : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
