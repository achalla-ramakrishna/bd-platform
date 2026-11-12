package com.codewalnut.bd.talent;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.codewalnut.bd.admin.CurrentUserProvider;
import com.codewalnut.bd.reporting.FulfilmentQueries;
import com.codewalnut.bd.shared.CurrentUser;
import com.codewalnut.bd.shared.Role;

/** Realising opportunities (ADR-0016): requirements being filled and the candidates for them. */
@RestController
@RequestMapping("/api/v1")
class CandidateController {

    /** BD and Delivery both move candidates. */
    static final Set<Role> FULFILMENT_EDITORS = EnumSet.of(Role.LEADERSHIP, Role.BD_LEAD, Role.ACCOUNT_EXECUTIVE,
        Role.SOLUTIONS, Role.DELIVERY, Role.ADMIN);

    private final CandidateService candidates;
    private final FulfilmentQueries queries;
    private final CurrentUserProvider currentUser;

    CandidateController(CandidateService candidates, FulfilmentQueries queries, CurrentUserProvider currentUser) {
        this.candidates = candidates;
        this.queries = queries;
        this.currentUser = currentUser;
    }

    /** Requirements being realised; {@code all} adds filled ones and lost opportunities. */
    @GetMapping("/fulfilment")
    List<FulfilmentQueries.RequirementStatus> fulfilment(@RequestParam(required = false) Boolean mine,
                                                        @RequestParam(required = false) Long accountId,
                                                        @RequestParam(required = false) Boolean all) {
        CurrentUser user = currentUser.get();
        return queries.requirements(Boolean.TRUE.equals(mine) ? user.id() : null, accountId, Boolean.TRUE.equals(all));
    }

    @GetMapping("/opportunities/{id}/candidates")
    List<FulfilmentQueries.Candidate> forOpportunity(@PathVariable long id) {
        currentUser.get();
        return queries.forOpportunity(id);
    }

    @PostMapping("/requirements/{id}/candidates")
    @ResponseStatus(HttpStatus.CREATED)
    FulfilmentQueries.Candidate propose(@PathVariable long id, @RequestBody CandidateService.Propose in) {
        CurrentUser user = currentUser.get();
        user.require(FULFILMENT_EDITORS);
        return queries.candidate(candidates.propose(id, in, user));
    }

    @PostMapping("/candidates/{id}/move")
    FulfilmentQueries.Candidate move(@PathVariable long id, @RequestBody CandidateService.Move in) {
        CurrentUser user = currentUser.get();
        user.require(FULFILMENT_EDITORS);
        candidates.move(id, in, user);
        return queries.candidate(id);
    }
}
