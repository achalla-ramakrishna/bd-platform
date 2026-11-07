package com.codewalnut.bd.admin;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Sign-in settings (ADR-0004).
 *
 * @param allowedDomain   Google Workspace domain whose accounts may sign in, e.g. codewalnut.com
 * @param bootstrapAdmins emails given the ADMIN role on first sign-in, so someone can assign roles
 * @param devLogin        local development only: enables a username/password form instead of Google
 * @param devPassword     password for the development form login (the access code in preview)
 * @param preview         shared preview with sample data only: the app shows a banner and refuses
 *                        to start without an access code of 12 or more characters
 */
@ConfigurationProperties("app.security")
public record SecurityProperties(String allowedDomain, List<String> bootstrapAdmins, boolean devLogin, String devPassword,
                                 boolean preview) {

    public SecurityProperties {
        bootstrapAdmins = bootstrapAdmins == null ? List.of() : bootstrapAdmins.stream().map(String::toLowerCase).toList();
        if (preview && (devPassword == null || devPassword.strip().length() < 12)) {
            throw new IllegalStateException(
                "Preview mode needs PREVIEW_ACCESS_CODE set to 12 or more characters. The app will not start without it.");
        }
    }

    public boolean isAllowedEmail(String email) {
        return email != null && allowedDomain != null
            && email.toLowerCase().endsWith("@" + allowedDomain.toLowerCase());
    }
}
