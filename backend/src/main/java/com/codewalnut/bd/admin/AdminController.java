package com.codewalnut.bd.admin;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.codewalnut.bd.shared.CurrentUser;
import com.codewalnut.bd.shared.Role;

@RestController
@RequestMapping("/api/v1")
class AdminController {

    record Me(long id, String email, String name, Set<Role> roles, boolean canSeeRestricted, boolean canImport) {}

    record Stage(String code, String label, int defaultProbability, String outcome) {}

    record SeniorityRule(String seniority, int maxConcurrentClients) {}

    record Option(String code, String label) {}

    record ReferenceData(List<Stage> stages, List<SeniorityRule> seniorityRules, Map<String, List<Option>> picklists) {}

    private final CurrentUserProvider currentUser;
    private final UserDirectory users;
    private final JdbcClient jdbc;

    AdminController(CurrentUserProvider currentUser, UserDirectory users, JdbcClient jdbc) {
        this.currentUser = currentUser;
        this.users = users;
        this.jdbc = jdbc;
    }

    @GetMapping("/me")
    Me me() {
        CurrentUser u = currentUser.get();
        return new Me(u.id(), u.email(), u.name(), u.roles(), u.canSeeRestricted(), u.canImport());
    }

    @GetMapping("/reference")
    ReferenceData reference() {
        var stages = jdbc.sql("SELECT code, label, default_probability, outcome FROM opportunity_stage ORDER BY sort_order")
            .query((rs, n) -> new Stage(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4))).list();
        var rules = jdbc.sql("SELECT seniority, max_concurrent_clients FROM seniority_rule ORDER BY sort_order")
            .query((rs, n) -> new SeniorityRule(rs.getString(1), rs.getInt(2))).list();
        Map<String, List<Option>> picklists = new LinkedHashMap<>();
        jdbc.sql("SELECT list_name, code, label FROM picklist_value WHERE active ORDER BY list_name, sort_order")
            .query((rs, n) -> Map.entry(rs.getString(1), new Option(rs.getString(2), rs.getString(3))))
            .list()
            .forEach(e -> picklists.computeIfAbsent(e.getKey(), k -> new java.util.ArrayList<>()).add(e.getValue()));
        return new ReferenceData(stages, rules, picklists);
    }

    @GetMapping("/admin/users")
    List<UserDirectory.UserView> listUsers() {
        currentUser.get().require(EnumSet.of(Role.ADMIN));
        return users.list();
    }

    @PutMapping("/admin/users/{id}/roles")
    UserDirectory.UserView setRoles(@PathVariable long id, @RequestBody Set<Role> roles) {
        CurrentUser admin = currentUser.get();
        admin.require(EnumSet.of(Role.ADMIN));
        if (id == admin.id() && !roles.contains(Role.ADMIN)) {
            throw new IllegalArgumentException("You cannot remove your own Admin role.");
        }
        return users.setRoles(id, roles.isEmpty() ? EnumSet.noneOf(Role.class) : EnumSet.copyOf(roles), admin.id());
    }
}
