package com.codewalnut.bd.shared;

import java.util.Collections;
import java.util.Set;

import org.springframework.security.access.AccessDeniedException;

/** The signed-in CodeWalnut user and the roles held in the application (ADR-0004). */
public record CurrentUser(long id, String email, String name, Set<Role> roles) {

    public boolean hasAnyRole(Set<Role> wanted) {
        return !Collections.disjoint(roles, wanted);
    }

    public boolean hasAnyRole() {
        return !roles.isEmpty();
    }

    public boolean canSeeRestricted() {
        return hasAnyRole(Role.RESTRICTED_VIEWERS);
    }

    public boolean canImport() {
        return hasAnyRole(Role.IMPORTERS);
    }

    public void require(Set<Role> wanted) {
        if (!hasAnyRole(wanted)) {
            throw new AccessDeniedException("This needs one of the roles " + wanted);
        }
    }
}
