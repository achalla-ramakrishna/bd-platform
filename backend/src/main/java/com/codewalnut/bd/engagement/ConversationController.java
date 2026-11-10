package com.codewalnut.bd.engagement;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.codewalnut.bd.admin.CurrentUserProvider;
import com.codewalnut.bd.reporting.ConversationQueries;
import com.codewalnut.bd.shared.CurrentUser;
import com.codewalnut.bd.shared.Role;

/** Conversations (backlog item 3). Everyone signed in reads; pipeline roles write. */
@RestController
@RequestMapping("/api/v1/conversations")
class ConversationController {

    private final ConversationService conversations;
    private final ConversationQueries queries;
    private final CurrentUserProvider currentUser;

    ConversationController(ConversationService conversations, ConversationQueries queries, CurrentUserProvider currentUser) {
        this.conversations = conversations;
        this.queries = queries;
        this.currentUser = currentUser;
    }

    @GetMapping
    ConversationQueries.Page list(@RequestParam(required = false) String status,
                                  @RequestParam(required = false) Boolean mine,
                                  @RequestParam(required = false) Long accountId,
                                  @RequestParam(required = false) String due,
                                  @RequestParam(required = false) String q) {
        CurrentUser user = currentUser.get();
        return queries.list(status, Boolean.TRUE.equals(mine) ? user.id() : null, accountId, due, q);
    }

    @GetMapping("/{id}")
    ConversationQueries.Detail get(@PathVariable long id) {
        currentUser.get();
        return queries.detail(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ConversationQueries.Detail start(@RequestBody ConversationService.Start start) {
        CurrentUser user = currentUser.get();
        user.require(Role.PIPELINE_EDITORS);
        return queries.detail(conversations.start(start, user));
    }

    @PostMapping("/{id}/updates")
    ConversationQueries.Detail update(@PathVariable long id, @RequestBody ConversationService.Update update) {
        CurrentUser user = currentUser.get();
        user.require(Role.PIPELINE_EDITORS);
        conversations.update(id, update, user);
        return queries.detail(id);
    }

    @PatchMapping("/{id}")
    ConversationQueries.Detail change(@PathVariable long id, @RequestBody ConversationService.Change change) {
        CurrentUser user = currentUser.get();
        user.require(Role.PIPELINE_EDITORS);
        conversations.change(id, change, user);
        return queries.detail(id);
    }
}
