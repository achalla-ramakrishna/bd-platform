package com.codewalnut.bd.walnut;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Walnut, the BD agent (ADR-0015), under {@code app.walnut}.
 *
 * @param roles            who Walnut works with: active users holding any of these roles (default ADMIN)
 * @param briefCron        when the morning brief is written (Spring cron, in {@code zone}); default 08:30 Mon-Fri
 * @param zone             time zone for the schedule and for "today"
 * @param schedulerEnabled set false to stop the scheduled briefs (tests, previews)
 * @param baseUrl          the app's public address, for links in emails (e.g. https://bd.example-gcc.test)
 */
@ConfigurationProperties("app.walnut")
public record WalnutProperties(List<String> roles, String briefCron, String zone, Boolean schedulerEnabled,
                               String baseUrl) {

    public WalnutProperties {
        roles = roles == null || roles.isEmpty() ? List.of("ADMIN") : roles;
        briefCron = briefCron == null || briefCron.isBlank() ? "0 30 8 * * MON-FRI" : briefCron;
        zone = zone == null || zone.isBlank() ? "Asia/Kolkata" : zone;
        schedulerEnabled = schedulerEnabled == null || schedulerEnabled;
        baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
    }
}
