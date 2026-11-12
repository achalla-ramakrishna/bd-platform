package com.codewalnut.bd.walnut;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.codewalnut.bd.admin.CurrentUserProvider;
import com.codewalnut.bd.shared.CurrentUser;
import com.codewalnut.bd.shared.RuleViolationException;

/** Walnut, the BD agent (ADR-0015). Each person sees only their own thread. */
@RestController
@RequestMapping("/api/v1/walnut")
class WalnutController {

    record Status(boolean available, boolean chatEnabled, boolean emailEnabled, List<String> worksWith) {}

    record ChatIn(String text) {}

    record SettingsIn(Boolean emailBrief) {}

    private final WalnutService walnut;
    private final CurrentUserProvider currentUser;

    WalnutController(WalnutService walnut, CurrentUserProvider currentUser) {
        this.walnut = walnut;
        this.currentUser = currentUser;
    }

    @GetMapping("/status")
    Status status() {
        CurrentUser user = currentUser.get();
        return new Status(walnut.worksWith(user), walnut.chatEnabled(), walnut.emailEnabled(),
            walnut.team().stream().map(p -> p.name()).toList());
    }

    @GetMapping("/messages")
    List<WalnutService.Message> messages(@RequestParam(required = false) Long before) {
        CurrentUser user = currentUser.get();
        walnut.require(user);
        return walnut.messages(user.id(), before, 50);
    }

    @PostMapping("/messages")
    List<WalnutService.Message> chat(@RequestBody ChatIn in) {
        CurrentUser user = currentUser.get();
        walnut.require(user);
        return walnut.chat(user, in == null ? null : in.text());
    }

    /** Today's plan: written now if the morning run has not done it yet. */
    @PostMapping("/brief")
    WalnutService.Message brief() {
        CurrentUser user = currentUser.get();
        walnut.require(user);
        return walnut.brief(walnut.person(user), walnut.today());
    }

    /** Emails today's plan to me now, to check the email set-up. */
    @PostMapping("/brief/email")
    Map<String, Boolean> emailMe() {
        CurrentUser user = currentUser.get();
        walnut.require(user);
        if (!walnut.emailEnabled()) {
            throw new RuleViolationException(List.of("Email is not set up. An Admin needs to set MAIL_API_KEY and MAIL_FROM in Railway."));
        }
        return Map.of("sent", walnut.email(walnut.person(user), walnut.today(), true));
    }

    @GetMapping("/settings")
    WalnutService.Settings settings() {
        CurrentUser user = currentUser.get();
        walnut.require(user);
        return walnut.settings(user.id());
    }

    @PutMapping("/settings")
    WalnutService.Settings updateSettings(@RequestBody SettingsIn in) {
        CurrentUser user = currentUser.get();
        walnut.require(user);
        if (in == null || in.emailBrief() == null) {
            throw new RuleViolationException(List.of("Say whether to email the morning plan."));
        }
        return walnut.updateSettings(user.id(), in.emailBrief());
    }
}
