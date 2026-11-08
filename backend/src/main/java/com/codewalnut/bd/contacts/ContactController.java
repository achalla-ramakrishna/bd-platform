package com.codewalnut.bd.contacts;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.codewalnut.bd.admin.CurrentUserProvider;
import com.codewalnut.bd.shared.CurrentUser;
import com.codewalnut.bd.shared.Role;

@RestController
@RequestMapping("/api/v1/contacts")
class ContactController {

    private final ContactService contacts;
    private final CurrentUserProvider currentUser;

    ContactController(ContactService contacts, CurrentUserProvider currentUser) {
        this.contacts = contacts;
        this.currentUser = currentUser;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Long> create(@RequestBody ContactService.Input input) {
        CurrentUser user = currentUser.get();
        user.require(Role.PIPELINE_EDITORS);
        return Map.of("id", contacts.create(input, user));
    }
}
