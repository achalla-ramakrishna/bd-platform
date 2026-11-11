package com.codewalnut.bd.accounts;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.codewalnut.bd.admin.CurrentUserProvider;
import com.codewalnut.bd.shared.AuditLog;
import com.codewalnut.bd.shared.ConflictException;
import com.codewalnut.bd.shared.CurrentUser;
import com.codewalnut.bd.shared.Role;
import com.codewalnut.bd.shared.RuleViolationException;

/**
 * Add one account by hand, for example from Log with AI (ADR-0013). Uploads and Find GCCs stay the way to
 * add many. The key is the website domain and city (BR-BD-01); an account that exists is not changed.
 */
@RestController
class AccountController {

    record NewAccount(String name, String parentCompany, String parentHqCountry, String city, String websiteDomain,
                      String industry, String notes, String source, AccountRegistry.Profile profile) {}

    private final AccountRegistry accounts;
    private final CurrentUserProvider currentUser;
    private final AuditLog audit;

    AccountController(AccountRegistry accounts, CurrentUserProvider currentUser, AuditLog audit) {
        this.accounts = accounts;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    @PostMapping("/api/v1/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Map<String, Long> add(@RequestBody NewAccount in) {
        CurrentUser user = currentUser.get();
        user.require(Role.PIPELINE_EDITORS);
        List<String> problems = new ArrayList<>();
        String name = trim(in.name());
        String parent = trim(in.parentCompany());
        String city = trim(in.city());
        String domain = AccountKey.normaliseDomain(in.websiteDomain());
        if (name == null) problems.add("Give the account a name.");
        if (parent == null) problems.add("Give the parent company.");
        if (city == null) problems.add("Give the city the centre is in.");
        if (!AccountKey.isValidDomain(domain)) problems.add("Give the company's website domain, for example example.com.");
        if (name != null && name.length() > 200 || parent != null && parent.length() > 200) {
            problems.add("Names can be at most 200 characters.");
        }
        if (city != null && city.length() > 100) problems.add("The city can be at most 100 characters.");
        AccountRegistry.Profile profile = in.profile();
        if (profile != null) {
            problems.addAll(AccountRegistry.check(profile));
        }
        if (!problems.isEmpty()) {
            throw new RuleViolationException(problems);
        }
        if (accounts.allKeys().containsKey(new AccountKey(domain, city))) {
            throw new ConflictException(name + " in " + city + " (" + domain + ") is already in the app.");
        }
        String source = trim(in.source()) == null ? "Added by hand" : cut(trim(in.source()), 200);
        var result = accounts.upsert(new AccountRegistry.AccountData(name, parent, cut(trim(in.parentHqCountry()), 80), city,
            domain, cut(trim(in.industry()), 120), null, null, List.of(), "PROSPECT", user.id(), source, trim(in.notes())));
        if (profile != null && !profile.isEmpty()) {
            accounts.updateProfile(result.id(), profile, true);
        }
        audit.record("account", result.id(), AuditLog.Action.CREATE, Map.of("from", source), user.id());
        return Map.of("id", result.id());
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
