package com.codewalnut.bd.prospecting;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Fills everyone's prospecting queue and starts research to feed it, on weekday mornings (ADR-0023). */
@Component
@ConditionalOnProperty(name = "app.agent.queue-enabled", havingValue = "true", matchIfMissing = true)
class QueueScheduler {

    private final QueueService queue;

    QueueScheduler(QueueService queue) {
        this.queue = queue;
    }

    @Scheduled(cron = "${app.agent.queue-cron:0 45 6 * * MON-FRI}", zone = "${app.gtn.zone:Asia/Kolkata}")
    void morning() {
        queue.morning(queue.today());
    }
}
