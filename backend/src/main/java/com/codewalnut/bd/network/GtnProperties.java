package com.codewalnut.bd.network;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * GTN thresholds (ADR-0017), under {@code app.gtn}. Configuration, not code: the team tunes them.
 *
 * @param unresponsiveDays days without a reply before Initiate becomes Unresponsive (default 7)
 * @param nudgeSpacingDays days between nudges (default 5)
 * @param nudgeCap         nudges before the contact is parked in Monthly beep (default 3)
 * @param beepIntervalDays days between monthly beeps for a new track (default 30)
 * @param sweepCron        when the daily sweep runs (Spring cron, in {@code zone})
 * @param zone             time zone for "today"
 * @param sweepEnabled     set false to stop the scheduled sweep (tests); Today still sweeps first
 */
@ConfigurationProperties("app.gtn")
public record GtnProperties(Integer unresponsiveDays, Integer nudgeSpacingDays, Integer nudgeCap, Integer beepIntervalDays,
                            String sweepCron, String zone, Boolean sweepEnabled) {

    public GtnProperties {
        unresponsiveDays = unresponsiveDays == null || unresponsiveDays < 1 ? 7 : unresponsiveDays;
        nudgeSpacingDays = nudgeSpacingDays == null || nudgeSpacingDays < 1 ? 5 : nudgeSpacingDays;
        nudgeCap = nudgeCap == null || nudgeCap < 1 ? 3 : nudgeCap;
        beepIntervalDays = beepIntervalDays == null || beepIntervalDays < 7 || beepIntervalDays > 180 ? 30 : beepIntervalDays;
        sweepCron = sweepCron == null || sweepCron.isBlank() ? "0 0 7 * * *" : sweepCron;
        zone = zone == null || zone.isBlank() ? "Asia/Kolkata" : zone;
        sweepEnabled = sweepEnabled == null || sweepEnabled;
    }
}
