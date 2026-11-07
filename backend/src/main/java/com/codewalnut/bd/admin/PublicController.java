package com.codewalnut.bd.admin;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The one API call allowed before sign-in: where the sign-in button should go. */
@RestController
class PublicController {

    record SignIn(String url, String allowedDomain, boolean devLogin, boolean preview) {}

    private final SecurityProperties props;

    PublicController(SecurityProperties props) {
        this.props = props;
    }

    @GetMapping("/api/v1/public/sign-in")
    SignIn signIn() {
        return new SignIn(props.devLogin() ? "/login" : "/oauth2/authorization/google", props.allowedDomain(),
            props.devLogin(), props.preview());
    }
}
