package com.codewalnut.bd.assist;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.codewalnut.bd.admin.CurrentUserProvider;
import com.codewalnut.bd.shared.CurrentUser;
import com.codewalnut.bd.shared.Role;

@RestController
@RequestMapping("/api/v1/accounts/{accountId}/research")
class ResearchController {

    private final ResearchService research;
    private final CurrentUserProvider currentUser;

    ResearchController(ResearchService research, CurrentUserProvider currentUser) {
        this.research = research;
        this.currentUser = currentUser;
    }

    /** The newest research run for the account, or 204 when there is none yet. */
    @GetMapping
    ResponseEntity<ResearchService.View> latest(@PathVariable long accountId) {
        return research.latest(accountId).map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    /** Starts research in the background; poll GET until the status is DONE or FAILED. */
    @PostMapping
    ResponseEntity<ResearchService.View> start(@PathVariable long accountId) {
        CurrentUser user = currentUser.get();
        user.require(Role.PIPELINE_EDITORS);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(research.start(accountId, user));
    }
}
