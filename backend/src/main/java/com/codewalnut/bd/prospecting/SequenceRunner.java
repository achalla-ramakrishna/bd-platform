package com.codewalnut.bd.prospecting;

import java.time.ZonedDateTime;
import java.util.concurrent.TimeUnit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every few minutes: look for replies to running sequences, then send what is due (ADR-0025). */
@Component
@ConditionalOnProperty(name = "app.agent.email.runner-enabled", havingValue = "true", matchIfMissing = true)
class SequenceRunner {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SequenceRunner.class);

    private final SequenceService sequences;

    SequenceRunner(SequenceService sequences) {
        this.sequences = sequences;
    }

    @Scheduled(fixedDelayString = "${app.agent.email.run-minutes:15}", initialDelay = 3, timeUnit = TimeUnit.MINUTES)
    void run() {
        int replies = sequences.checkReplies();
        int sent = sequences.sendDue(ZonedDateTime.now());
        if (replies + sent > 0) {
            LOG.info("Sequences: {} sent, {} replies found.", sent, replies);
        }
    }
}
