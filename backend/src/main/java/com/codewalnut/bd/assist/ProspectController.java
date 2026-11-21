package com.codewalnut.bd.assist;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.codewalnut.bd.admin.CurrentUserProvider;
import com.codewalnut.bd.shared.CurrentUser;
import com.codewalnut.bd.shared.Role;

/** The BD agent's research on people (ADR-0022). Everyone reads briefs; pipeline roles start research. */
@RestController
@RequestMapping("/api/v1/prospects")
class ProspectController {

    record Status(boolean enabled, int remainingToday) {}

    private final ProspectService prospects;
    private final CurrentUserProvider currentUser;

    ProspectController(ProspectService prospects, CurrentUserProvider currentUser) {
        this.prospects = prospects;
        this.currentUser = currentUser;
    }

    @GetMapping("/status")
    Status status() {
        currentUser.get();
        return new Status(prospects.enabled(), prospects.remainingToday());
    }

    /** The newest brief on a person (CONTACT or CONNECTION); 204 when there is none. */
    @GetMapping("/{type}/{id}")
    ResponseEntity<ProspectService.View> brief(@PathVariable String type, @PathVariable long id) {
        currentUser.get();
        return prospects.briefOf(type, id).map(ResponseEntity::ok)
            .orElse(ResponseEntity.noContent().build());
    }

    /** Research this person now; {@code again=true} ignores a recent brief. */
    @PostMapping("/{type}/{id}/research")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ProspectService.View research(@PathVariable String type, @PathVariable long id, @RequestParam(required = false) Boolean again) {
        CurrentUser user = currentUser.get();
        user.require(Role.PIPELINE_EDITORS);
        return prospects.start(type, id, Boolean.TRUE.equals(again), user);
    }

    /** Briefs on an account's people, best score first. */
    @GetMapping("/for-account/{accountId}")
    List<ProspectService.View> forAccount(@PathVariable long accountId) {
        currentUser.get();
        return prospects.forAccount(accountId);
    }

    /** Research up to 10 of an account's contacts who have no recent brief. */
    @PostMapping("/for-account/{accountId}/research")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ProspectService.Batch researchAccount(@PathVariable long accountId) {
        CurrentUser user = currentUser.get();
        user.require(Role.PIPELINE_EDITORS);
        return prospects.startForAccount(accountId, user);
    }
}
