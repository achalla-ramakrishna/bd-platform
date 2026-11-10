package com.codewalnut.bd.accounts;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.codewalnut.bd.admin.CurrentUserProvider;
import com.codewalnut.bd.shared.AuditLog;
import com.codewalnut.bd.shared.CurrentUser;
import com.codewalnut.bd.shared.NotFoundException;
import com.codewalnut.bd.shared.Role;
import com.codewalnut.bd.shared.RuleViolationException;

/** Edit an account's targeting facts: year formed, size and open roles (backlog item 1). */
@RestController
class AccountProfileController {

    private final AccountRegistry accounts;
    private final CurrentUserProvider currentUser;
    private final AuditLog audit;

    AccountProfileController(AccountRegistry accounts, CurrentUserProvider currentUser, AuditLog audit) {
        this.accounts = accounts;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    @PutMapping("/api/v1/accounts/{id}/profile")
    @Transactional
    Map<String, Long> update(@PathVariable long id, @RequestBody AccountRegistry.Profile profile) {
        CurrentUser user = currentUser.get();
        user.require(Role.PIPELINE_EDITORS);
        if (!accounts.exists(id)) {
            throw new NotFoundException("Account", id);
        }
        List<String> problems = AccountRegistry.check(profile);
        if (!problems.isEmpty()) {
            throw new RuleViolationException(problems);
        }
        accounts.updateProfile(id, profile, false);
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("yearEstablished", profile.yearEstablished());
        changes.put("headcountBand", profile.headcountBand());
        changes.put("employeeCount", profile.employeeCount());
        changes.put("openRoles", profile.openRoles());
        audit.record("account", id, AuditLog.Action.UPDATE, changes, user.id());
        return Map.of("id", id);
    }
}
