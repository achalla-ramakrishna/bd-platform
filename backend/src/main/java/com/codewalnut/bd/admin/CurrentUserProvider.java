package com.codewalnut.bd.admin;

import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

import com.codewalnut.bd.shared.CurrentUser;

/**
 * Resolves the signed-in user from the security context. The principal's name is the email
 * (Google's user-name attribute is set to email). Roles are read from the database on each call,
 * so role changes apply at once.
 */
@Component
public class CurrentUserProvider {

    private final UserDirectory users;

    public CurrentUserProvider(UserDirectory users) {
        this.users = users;
    }

    public CurrentUser get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            throw new InsufficientAuthenticationException("Not signed in");
        }
        String email = auth.getPrincipal() instanceof OidcUser oidc ? oidc.getEmail() : auth.getName();
        return users.findByEmail(email).orElseGet(() -> users.recordSignIn(email, displayName(auth)));
    }

    private static String displayName(Authentication auth) {
        return auth.getPrincipal() instanceof OidcUser oidc ? oidc.getFullName() : null;
    }
}
