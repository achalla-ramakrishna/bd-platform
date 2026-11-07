package com.codewalnut.bd.admin;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.codewalnut.bd.shared.AuditLog;
import com.codewalnut.bd.shared.CurrentUser;
import com.codewalnut.bd.shared.NotFoundException;
import com.codewalnut.bd.shared.Role;

/** CodeWalnut users and their roles. Roles live in the application, not in Google (ADR-0004). */
@Service
public class UserDirectory {

    public record UserView(long id, String email, String name, boolean active, Set<Role> roles, Instant lastLoginAt) {}

    private final JdbcClient jdbc;
    private final SecurityProperties props;
    private final AuditLog audit;

    public UserDirectory(JdbcClient jdbc, SecurityProperties props, AuditLog audit) {
        this.jdbc = jdbc;
        this.props = props;
        this.audit = audit;
    }

    /** Called on every successful sign-in. Creates the user if new; bootstrap admins get ADMIN. */
    @Transactional
    public CurrentUser recordSignIn(String email, String name) {
        String normalised = email.toLowerCase();
        long id = findId(normalised).orElseGet(() -> insert(normalised, name));
        jdbc.sql("UPDATE app_user SET full_name = :name, last_login_at = CURRENT_TIMESTAMP(3) WHERE id = :id")
            .param("name", name == null || name.isBlank() ? nameFromEmail(normalised) : name)
            .param("id", id)
            .update();
        if (props.bootstrapAdmins().contains(normalised)) {
            jdbc.sql("INSERT IGNORE INTO user_role (user_id, role) VALUES (:id, 'ADMIN')").param("id", id).update();
        }
        return load(id);
    }

    /**
     * Finds or creates a user by email without signing them in, e.g. an account owner named in an
     * upload who has not signed in yet. The email must be in the allowed domain.
     */
    @Transactional
    public long ensureUser(String email) {
        String normalised = email.toLowerCase();
        return findId(normalised).orElseGet(() -> insert(normalised, nameFromEmail(normalised)));
    }

    public Optional<Long> findId(String email) {
        return jdbc.sql("SELECT id FROM app_user WHERE email = :email")
            .param("email", email.toLowerCase())
            .query(Long.class)
            .optional();
    }

    public Optional<CurrentUser> findByEmail(String email) {
        return findId(email).map(this::load);
    }

    public List<UserView> list() {
        Map<Long, Set<Role>> roles = allRoles();
        return jdbc.sql("SELECT id, email, full_name, active, last_login_at FROM app_user ORDER BY full_name")
            .query((rs, n) -> new UserView(rs.getLong("id"), rs.getString("email"), rs.getString("full_name"),
                rs.getBoolean("active"), roles.getOrDefault(rs.getLong("id"), EnumSet.noneOf(Role.class)),
                rs.getTimestamp("last_login_at") == null ? null : rs.getTimestamp("last_login_at").toInstant()))
            .list();
    }

    @Transactional
    public UserView setRoles(long userId, Set<Role> roles, long changedBy) {
        if (findEmail(userId).isEmpty()) {
            throw new NotFoundException("User", userId);
        }
        jdbc.sql("DELETE FROM user_role WHERE user_id = :id").param("id", userId).update();
        for (Role role : roles) {
            jdbc.sql("INSERT INTO user_role (user_id, role) VALUES (:id, :role)")
                .param("id", userId).param("role", role.name()).update();
        }
        audit.record("app_user", userId, AuditLog.Action.UPDATE, Map.of("roles", roles), changedBy);
        return list().stream().filter(u -> u.id() == userId).findFirst().orElseThrow();
    }

    private Optional<String> findEmail(long id) {
        return jdbc.sql("SELECT email FROM app_user WHERE id = :id").param("id", id).query(String.class).optional();
    }

    private CurrentUser load(long id) {
        return jdbc.sql("SELECT id, email, full_name FROM app_user WHERE id = :id AND active")
            .param("id", id)
            .query((rs, n) -> new CurrentUser(rs.getLong("id"), rs.getString("email"), rs.getString("full_name"), rolesOf(id)))
            .optional()
            .orElseThrow(() -> new NotFoundException("Active user", id));
    }

    private Set<Role> rolesOf(long id) {
        List<String> names = jdbc.sql("SELECT role FROM user_role WHERE user_id = :id").param("id", id).query(String.class).list();
        Set<Role> roles = EnumSet.noneOf(Role.class);
        names.forEach(n -> roles.add(Role.valueOf(n)));
        return roles;
    }

    private Map<Long, Set<Role>> allRoles() {
        return jdbc.sql("SELECT user_id, role FROM user_role")
            .query((rs, n) -> Map.entry(rs.getLong(1), Role.valueOf(rs.getString(2))))
            .list().stream()
            .collect(Collectors.groupingBy(Map.Entry::getKey,
                Collectors.mapping(Map.Entry::getValue, Collectors.toCollection(() -> EnumSet.noneOf(Role.class)))));
    }

    private long insert(String email, String name) {
        var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.sql("INSERT INTO app_user (email, full_name) VALUES (:email, :name)")
            .param("email", email)
            .param("name", name == null || name.isBlank() ? nameFromEmail(email) : name)
            .update(keys);
        return keys.getKey().longValue();
    }

    static String nameFromEmail(String email) {
        String local = email.substring(0, email.indexOf('@'));
        return java.util.Arrays.stream(local.split("[._-]+"))
            .filter(p -> !p.isBlank())
            .map(p -> Character.toUpperCase(p.charAt(0)) + p.substring(1))
            .collect(Collectors.joining(" "));
    }
}
