package com.codewalnut.bd.prospecting;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.codewalnut.bd.admin.CurrentUserProvider;

/** Email sequences (ADR-0025): templates, starting one for a contact, review and approval, pause and stop. */
@RestController
@RequestMapping("/api/v1/sequences")
class SequenceController {

    private final SequenceService sequences;
    private final CurrentUserProvider currentUser;

    SequenceController(SequenceService sequences, CurrentUserProvider currentUser) {
        this.sequences = sequences;
        this.currentUser = currentUser;
    }

    /** Whether sending is on, my sequences (running, and ended in the last 30 days), and the templates. */
    @GetMapping
    SequenceService.Overview overview() {
        return sequences.overview(currentUser.get());
    }

    @GetMapping("/contact/{id}")
    List<SequenceService.Enrolment> forContact(@PathVariable long id) {
        currentUser.get();
        return sequences.forContact(id);
    }

    @PostMapping("/templates")
    @ResponseStatus(HttpStatus.CREATED)
    SequenceService.Template create(@RequestBody SequenceService.Template in) {
        return sequences.saveTemplate(null, in, currentUser.get());
    }

    @PutMapping("/templates/{id}")
    SequenceService.Template update(@PathVariable long id, @RequestBody SequenceService.Template in) {
        return sequences.saveTemplate(id, in, currentUser.get());
    }

    /** Start a sequence for a contact; the agent writes the emails in the background. */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    SequenceService.Enrolment start(@RequestBody SequenceService.Start in) {
        return sequences.start(in, currentUser.get());
    }

    @GetMapping("/{id}")
    SequenceService.Enrolment get(@PathVariable long id) {
        currentUser.get();
        return sequences.get(id);
    }

    @PutMapping("/{id}/emails/{position}")
    SequenceService.Enrolment edit(@PathVariable long id, @PathVariable int position, @RequestBody SequenceService.EmailEdit in) {
        return sequences.editEmail(id, position, in, currentUser.get());
    }

    @PostMapping("/{id}/approve")
    SequenceService.Enrolment approve(@PathVariable long id, @RequestParam(required = false) Integer version) {
        return sequences.approve(id, version, currentUser.get());
    }

    @PostMapping("/{id}/pause")
    SequenceService.Enrolment pause(@PathVariable long id) {
        return sequences.pause(id, currentUser.get());
    }

    @PostMapping("/{id}/resume")
    SequenceService.Enrolment resume(@PathVariable long id) {
        return sequences.resume(id, currentUser.get());
    }

    @PostMapping("/{id}/stop")
    SequenceService.Enrolment stop(@PathVariable long id) {
        return sequences.stop(id, currentUser.get());
    }
}
