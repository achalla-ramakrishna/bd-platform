package com.codewalnut.bd.prospecting;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.codewalnut.bd.admin.CurrentUserProvider;

/** Replies read by the reply agent (ADR-0024). Owners (and managers) confirm them. */
@RestController
@RequestMapping("/api/v1/replies")
class ReplyController {

    private final ReplyService replies;
    private final CurrentUserProvider currentUser;

    ReplyController(ReplyService replies, CurrentUserProvider currentUser) {
        this.replies = replies;
        this.currentUser = currentUser;
    }

    /** My replies: waiting, and handled in the last 7 days. */
    @GetMapping
    ReplyService.Inbox inbox() {
        return replies.inbox(currentUser.get());
    }

    @GetMapping("/conversation/{id}")
    List<ReplyService.Reply> forConversation(@PathVariable long id) {
        currentUser.get();
        return replies.forConversation(id);
    }

    @GetMapping("/track/{id}")
    List<ReplyService.Reply> forTrack(@PathVariable long id) {
        currentUser.get();
        return replies.forTrack(id);
    }

    /** Paste a reply on a conversation or a GTN track; the agent reads it in the background. */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    ReplyService.Reply paste(@RequestBody ReplyService.Paste in) {
        return replies.paste(in, currentUser.get());
    }

    @PostMapping("/{id}/done")
    ReplyService.Reply done(@PathVariable long id, @RequestBody(required = false) ReplyService.Done in) {
        return replies.done(id, in, currentUser.get());
    }

    @PostMapping("/{id}/dismiss")
    ReplyService.Reply dismiss(@PathVariable long id) {
        return replies.dismiss(id, currentUser.get());
    }

    @PostMapping("/{id}/again")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ReplyService.Reply again(@PathVariable long id) {
        return replies.again(id, currentUser.get());
    }

    @PostMapping("/{id}/undo-opt-out")
    ReplyService.Reply undoOptOut(@PathVariable long id) {
        return replies.undoOptOut(id, currentUser.get());
    }
}
