package com.codewalnut.bd.opportunities;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.codewalnut.bd.admin.CurrentUserProvider;
import com.codewalnut.bd.reporting.OpportunityQueries;
import com.codewalnut.bd.shared.CurrentUser;
import com.codewalnut.bd.shared.Role;

/** Writes to the pipeline. Reads are served by the reporting module (ADR-0003). */
@RestController
@RequestMapping("/api/v1/opportunities")
class OpportunityController {

    private final OpportunityService opportunities;
    private final OpportunityQueries queries;
    private final CurrentUserProvider currentUser;

    OpportunityController(OpportunityService opportunities, OpportunityQueries queries, CurrentUserProvider currentUser) {
        this.opportunities = opportunities;
        this.queries = queries;
        this.currentUser = currentUser;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    OpportunityQueries.Opportunity create(@RequestBody OpportunityService.Input input) {
        CurrentUser user = currentUser.get();
        user.require(Role.PIPELINE_EDITORS);
        return queries.get(opportunities.create(input, user));
    }

    @PatchMapping("/{id}")
    OpportunityQueries.Opportunity update(@PathVariable long id, @RequestBody OpportunityService.Input input) {
        CurrentUser user = currentUser.get();
        user.require(Role.PIPELINE_EDITORS);
        opportunities.update(id, input, user);
        return queries.get(id);
    }

    @PostMapping("/{id}/stage")
    OpportunityQueries.Opportunity moveStage(@PathVariable long id, @RequestBody OpportunityService.StageMove move) {
        CurrentUser user = currentUser.get();
        user.require(Role.PIPELINE_EDITORS);
        opportunities.moveStage(id, move, user);
        return queries.get(id);
    }
}
