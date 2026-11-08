package com.codewalnut.bd.assist;

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
import com.codewalnut.bd.shared.CurrentUser;
import com.codewalnut.bd.shared.Role;

@RestController
@RequestMapping("/api/v1/discovery")
class DiscoveryController {

    record StartRequest(String city, String industry) {}

    record AddRequest(List<String> domains) {}

    private final DiscoveryService discovery;
    private final CurrentUserProvider currentUser;

    DiscoveryController(DiscoveryService discovery, CurrentUserProvider currentUser) {
        this.discovery = discovery;
        this.currentUser = currentUser;
    }

    @GetMapping
    List<DiscoveryService.Run> recent() {
        return discovery.recent();
    }

    @GetMapping("/{id}")
    DiscoveryService.Run get(@PathVariable long id) {
        return discovery.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    DiscoveryService.Run start(@RequestBody StartRequest request) {
        CurrentUser user = currentUser.get();
        user.require(Role.PIPELINE_EDITORS);
        return discovery.start(request.city(), request.industry(), user);
    }

    @PostMapping("/{id}/add")
    DiscoveryService.Added add(@PathVariable long id, @RequestBody AddRequest request) {
        CurrentUser user = currentUser.get();
        user.require(Role.PIPELINE_EDITORS);
        return discovery.add(id, request.domains(), user);
    }
}
