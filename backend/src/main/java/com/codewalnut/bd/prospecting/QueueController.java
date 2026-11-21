package com.codewalnut.bd.prospecting;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.codewalnut.bd.admin.CurrentUserProvider;
import com.codewalnut.bd.shared.CurrentUser;
import com.codewalnut.bd.shared.Role;

/** The daily prospecting queue (ADR-0023): pipeline roles each have their own. */
@RestController
@RequestMapping("/api/v1/prospecting")
class QueueController {

    static final int RESEARCH_ON_DEMAND = 5;

    private final QueueService queue;
    private final CurrentUserProvider currentUser;

    QueueController(QueueService queue, CurrentUserProvider currentUser) {
        this.queue = queue;
        this.currentUser = currentUser;
    }

    /** My queue for today, topped up from new briefs first. */
    @GetMapping("/queue")
    QueueService.Queue mine() {
        CurrentUser user = currentUser.get();
        user.require(Role.PIPELINE_EDITORS);
        return queue.queue(user);
    }

    /** Start research on up to 5 more people I could approach. */
    @PostMapping("/research")
    @ResponseStatus(HttpStatus.ACCEPTED)
    QueueService.Research research() {
        CurrentUser user = currentUser.get();
        user.require(Role.PIPELINE_EDITORS);
        return queue.research(user, RESEARCH_ON_DEMAND);
    }

    @PostMapping("/queue/{id}/sent")
    QueueService.Item sent(@PathVariable long id, @RequestBody(required = false) QueueService.Sent in) {
        return queue.sent(id, in, currentUser.get());
    }

    @PostMapping("/queue/{id}/skip")
    QueueService.Item skip(@PathVariable long id, @RequestBody QueueService.Skip in) {
        return queue.skip(id, in, currentUser.get());
    }
}
