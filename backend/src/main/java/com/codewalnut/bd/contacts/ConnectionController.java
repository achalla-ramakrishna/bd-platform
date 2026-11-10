package com.codewalnut.bd.contacts;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import com.codewalnut.bd.reporting.ConnectionQueries;
import com.codewalnut.bd.shared.CurrentUser;
import com.codewalnut.bd.shared.Role;

/** Connections (backlog item 4). Everyone signed in reads; pipeline roles add and change. */
@RestController
@RequestMapping("/api/v1/connections")
class ConnectionController {

    private final ConnectionService connections;
    private final ConnectionQueries queries;
    private final CurrentUserProvider currentUser;

    ConnectionController(ConnectionService connections, ConnectionQueries queries, CurrentUserProvider currentUser) {
        this.connections = connections;
        this.queries = queries;
        this.currentUser = currentUser;
    }

    @GetMapping
    ConnectionQueries.Page list(@RequestParam(required = false) String q,
                                @RequestParam(required = false) Boolean mine,
                                @RequestParam(required = false) Long accountId,
                                @RequestParam(required = false) Boolean linked,
                                @RequestParam(required = false) String strength) {
        CurrentUser user = currentUser.get();
        return queries.list(q, Boolean.TRUE.equals(mine) ? user.id() : null, accountId, linked, strength, user.id());
    }

    @GetMapping("/{id}")
    ConnectionQueries.Connection get(@PathVariable long id) {
        currentUser.get();
        return queries.get(id);
    }

    /** Warm paths into an account, for the account page. */
    @GetMapping("/for-account/{accountId}")
    List<ConnectionQueries.Connection> forAccount(@PathVariable long accountId) {
        currentUser.get();
        return queries.forAccount(accountId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ConnectionQueries.Connection add(@RequestBody ConnectionService.Add add) {
        CurrentUser user = currentUser.get();
        user.require(Role.PIPELINE_EDITORS);
        return queries.get(connections.add(add, user));
    }

    @PutMapping("/{id}")
    ConnectionQueries.Connection update(@PathVariable long id, @RequestBody ConnectionService.Person person) {
        CurrentUser user = currentUser.get();
        user.require(Role.PIPELINE_EDITORS);
        connections.update(id, person, user);
        return queries.get(id);
    }

    /** I know this person (or update how well). */
    @PutMapping("/{id}/knowers/me")
    ConnectionQueries.Connection know(@PathVariable long id, @RequestBody ConnectionService.Knowing knowing) {
        CurrentUser user = currentUser.get();
        user.require(Role.PIPELINE_EDITORS);
        connections.know(id, knowing, user);
        return queries.get(id);
    }

    /** I no longer know this person; the connection is removed when nobody does. */
    @DeleteMapping("/{id}/knowers/me")
    Map<String, Boolean> forget(@PathVariable long id) {
        CurrentUser user = currentUser.get();
        user.require(Role.PIPELINE_EDITORS);
        connections.forget(id, user);
        return Map.of("ok", true);
    }

    @PostMapping("/{id}/contact")
    ConnectionQueries.Connection makeContact(@PathVariable long id, @RequestBody(required = false) ConnectionService.ToContact in) {
        CurrentUser user = currentUser.get();
        user.require(Role.PIPELINE_EDITORS);
        connections.makeContact(id, in, user);
        return queries.get(id);
    }
}
