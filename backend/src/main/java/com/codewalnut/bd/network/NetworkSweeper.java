package com.codewalnut.bd.network;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs GTN's automatic moves every morning (ADR-0017); Today also sweeps before it loads. */
@Component
@ConditionalOnProperty(name = "app.gtn.sweep-enabled", havingValue = "true", matchIfMissing = true)
class NetworkSweeper {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(NetworkSweeper.class);

    private final NetworkService network;

    NetworkSweeper(NetworkService network) {
        this.network = network;
    }

    @Scheduled(cron = "${app.gtn.sweep-cron:0 0 7 * * *}", zone = "${app.gtn.zone:Asia/Kolkata}")
    void sweep() {
        int moved = network.sweep(network.today());
        if (moved > 0) {
            LOG.info("GTN sweep moved {} track(s).", moved);
        }
    }
}
