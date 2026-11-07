package com.codewalnut.bd.admin;

import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * Accepts only Google accounts in CodeWalnut's Workspace domain (ADR-0004): the hosted-domain
 * claim and the email domain must both match, and the email must be verified.
 */
public class WorkspaceOidcUserService extends OidcUserService {

    private final SecurityProperties props;
    private final UserDirectory users;

    public WorkspaceOidcUserService(SecurityProperties props, UserDirectory users) {
        this.props = props;
        this.users = users;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) throws OAuth2AuthenticationException {
        OidcUser user = super.loadUser(request);
        if (!isAllowed(props, user.getClaimAsString("hd"), user.getEmail(), user.getEmailVerified())) {
            throw new OAuth2AuthenticationException(new OAuth2Error("access_denied",
                "Only " + props.allowedDomain() + " Google Workspace accounts can sign in.", null));
        }
        users.recordSignIn(user.getEmail(), user.getFullName());
        return user;
    }

    static boolean isAllowed(SecurityProperties props, String hostedDomain, String email, Boolean emailVerified) {
        return Boolean.TRUE.equals(emailVerified)
            && props.allowedDomain() != null
            && props.allowedDomain().equalsIgnoreCase(hostedDomain)
            && props.isAllowedEmail(email);
    }
}
