package com.codewalnut.bd.google;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.codewalnut.bd.admin.CurrentUserProvider;
import com.codewalnut.bd.shared.CurrentUser;
import com.codewalnut.bd.shared.Role;
import com.codewalnut.bd.shared.RuleViolationException;
import com.codewalnut.bd.walnut.WalnutProperties;

/** Connect or disconnect my Google account for GTN (ADR-0020). The state check stops forged callbacks. */
@RestController
@RequestMapping("/api/v1/google")
class GoogleController {

    static final String STATE = "gtn.google.state";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final GoogleAccounts accounts;
    private final WalnutProperties walnut;
    private final CurrentUserProvider currentUser;

    GoogleController(GoogleAccounts accounts, WalnutProperties walnut, CurrentUserProvider currentUser) {
        this.accounts = accounts;
        this.walnut = walnut;
        this.currentUser = currentUser;
    }

    @GetMapping("/status")
    GoogleAccounts.Status status() {
        return accounts.status(currentUser.get().id());
    }

    /** Sends the browser to Google's consent page. */
    @GetMapping("/connect")
    void connect(HttpSession session, HttpServletResponse res) throws IOException {
        CurrentUser user = currentUser.get();
        user.require(Role.PIPELINE_EDITORS);
        byte[] b = new byte[24];
        RANDOM.nextBytes(b);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        session.setAttribute(STATE, state);
        res.sendRedirect(accounts.authUrl(user, state, redirectUri()));
    }

    /** Google sends the browser back here; then back to GTN with the outcome. */
    @GetMapping("/callback")
    void callback(@RequestParam(required = false) String code, @RequestParam(required = false) String state,
                  @RequestParam(required = false) String error, HttpSession session, HttpServletResponse res) throws IOException {
        CurrentUser user = currentUser.get();
        Object expected = session.getAttribute(STATE);
        session.removeAttribute(STATE);
        String outcome;
        if (error != null) {
            outcome = "Google said: " + error;
        } else if (expected == null || code == null || !expected.equals(state)) {
            outcome = "The connection request expired or didn't match. Try again.";
        } else {
            try {
                accounts.complete(user, code, redirectUri());
                outcome = "connected";
            } catch (RuleViolationException e) {
                outcome = String.join(" ", e.problems());
            } catch (GoogleApi.GoogleException e) {
                outcome = "Google refused the connection. Try again.";
            }
        }
        res.sendRedirect("/network?tab=profile&google=" + URLEncoder.encode(outcome, StandardCharsets.UTF_8));
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void disconnect() {
        accounts.disconnect(currentUser.get());
    }

    private String redirectUri() {
        String base = walnut.baseUrl().isBlank()
            ? ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString().replaceAll("/+$", "")
            : walnut.baseUrl();
        return base + "/api/v1/google/callback";
    }
}
