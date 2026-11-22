package com.codewalnut.bd.prospecting;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.codewalnut.bd.admin.CurrentUserProvider;

/** Call prep (ADR-0026): the brief and draft opportunity for a booked call. */
@RestController
@RequestMapping("/api/v1/call-prep")
class CallPrepController {

    private final CallPrepService preps;
    private final CurrentUserProvider currentUser;

    CallPrepController(CallPrepService preps, CurrentUserProvider currentUser) {
        this.preps = preps;
        this.currentUser = currentUser;
    }

    /** My upcoming calls with their prep. */
    @GetMapping
    List<CallPrepService.View> upcoming() {
        return preps.upcoming(currentUser.get());
    }

    /** The newest prep for a conversation; 204 when there is none. */
    @GetMapping("/conversation/{id}")
    ResponseEntity<CallPrepService.View> forConversation(@PathVariable long id) {
        currentUser.get();
        return preps.forConversation(id).map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    /** The newest prep for a GTN track; 204 when there is none. */
    @GetMapping("/track/{id}")
    ResponseEntity<CallPrepService.View> forTrack(@PathVariable long id) {
        currentUser.get();
        return preps.forTrack(id).map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    /** Prepare (or prepare again) for a call on a conversation or a GTN contact. */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    CallPrepService.View prepare(@RequestBody CallPrepService.Prepare in) {
        return preps.prepare(in, currentUser.get());
    }

    @PostMapping("/{id}/opportunity")
    @ResponseStatus(HttpStatus.CREATED)
    CallPrepService.View createOpportunity(@PathVariable long id, @RequestBody(required = false) CallPrepService.NewOpportunity in) {
        return preps.createOpportunity(id, in, currentUser.get());
    }
}
