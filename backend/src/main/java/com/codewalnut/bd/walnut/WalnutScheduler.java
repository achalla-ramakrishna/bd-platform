package com.codewalnut.bd.walnut;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Writes (and emails) the morning brief for everyone Walnut works with, on weekdays (ADR-0015). */
@Component
@ConditionalOnProperty(name = "app.walnut.scheduler-enabled", havingValue = "true", matchIfMissing = true)
class WalnutScheduler {

    private final WalnutService walnut;

    WalnutScheduler(WalnutService walnut) {
        this.walnut = walnut;
    }

    @Scheduled(cron = "${app.walnut.brief-cron:0 30 8 * * MON-FRI}", zone = "${app.walnut.zone:Asia/Kolkata}")
    void morning() {
        walnut.morning(walnut.today());
    }
}
