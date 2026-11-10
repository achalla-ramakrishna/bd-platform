package com.codewalnut.bd.contacts;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.codewalnut.bd.accounts.AccountRegistry;
import com.codewalnut.bd.shared.AuditLog;
import com.codewalnut.bd.shared.ConflictException;
import com.codewalnut.bd.shared.CurrentUser;
import com.codewalnut.bd.shared.NotFoundException;
import com.codewalnut.bd.shared.RuleViolationException;

/**
 * Connections (backlog item 4): go to network, not go to market. People the team knows, typed in by
 * hand; who knows them and how well; and the target account they work at or can introduce us to.
 * Nothing is read from LinkedIn (ADR-0005).
 */
@Service
class ConnectionService {

    static final Set<String> STRENGTHS = Set.of("CLOSE", "KNOW_WELL", "ACQUAINTANCE");
    static final Set<String> LINKS = Set.of("WORKS_THERE", "CAN_INTRODUCE");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern LINKEDIN = Pattern.compile("^https?://([a-z]+\\.)?linkedin\\.com/.+", Pattern.CASE_INSENSITIVE);

    /** The person. {@code accountLink} is needed when {@code accountId} is given. */
    record Person(String fullName, String currentCompany, String currentRole, String city, String linkedinUrl,
                  String email, Long accountId, String accountLink, String notes, Integer version, Long metAtEventId) {}

    /** How the signed-in user knows them. */
    record Knowing(String strength, String howKnown, LocalDate connectedOn) {}

    record Add(Person person, Knowing knowing) {}

    /** Make a contact at the linked account from a connection who works there. */
    record ToContact(String persona) {}

    private final JdbcClient jdbc;
    private final AccountRegistry accounts;
    private final ContactService contacts;
    private final AuditLog audit;

    ConnectionService(JdbcClient jdbc, AccountRegistry accounts, ContactService contacts, AuditLog audit) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.contacts = contacts;
        this.audit = audit;
    }

    @Transactional
    long add(Add in, CurrentUser user) {
        Person p = in.person() == null ? new Person(null, null, null, null, null, null, null, null, null, null, null) : in.person();
        Knowing k = in.knowing() == null ? new Knowing(null, null, null) : in.knowing();
        List<String> problems = personProblems(p);
        knowingProblems(k, problems);
        String name = trim(p.fullName());
        if (problems.isEmpty()) {
            Long same = jdbc.sql("""
                    SELECT id FROM connection WHERE LOWER(full_name) = LOWER(:name)
                      AND LOWER(COALESCE(current_company, '')) = LOWER(COALESCE(:company, ''))
                    """).param("name", name).param("company", trim(p.currentCompany())).query(Long.class).optional().orElse(null);
            if (same != null) {
                problems.add(name + " is already in Connections. Open them and add yourself as someone who knows them.");
            }
        }
        if (!problems.isEmpty()) {
            throw new RuleViolationException(problems);
        }
        var key = new GeneratedKeyHolder();
        bind(jdbc.sql("""
                INSERT INTO connection (full_name, current_company, current_role, city, linkedin_url, email, account_id,
                    account_link, notes, met_at_event_id, created_by)
                VALUES (:name, :company, :role, :city, :linkedin, :email, :account, :link, :notes, :event, :user)
                """), p).param("user", user.id()).update(key);
        long id = key.getKey().longValue();
        saveKnowing(id, k, user);
        audit.record("connection", id, AuditLog.Action.CREATE, Map.of("strength", upper(k.strength())), user.id());
        return id;
    }

    @Transactional
    void update(long id, Person p, CurrentUser user) {
        Integer stored = jdbc.sql("SELECT version FROM connection WHERE id = :id FOR UPDATE").param("id", id)
            .query(Integer.class).optional().orElseThrow(() -> new NotFoundException("Connection", id));
        if (p.version() != null && !p.version().equals(stored)) {
            throw new ConflictException("Someone else changed this connection. Reload and try again.");
        }
        List<String> problems = personProblems(p);
        if (!problems.isEmpty()) {
            throw new RuleViolationException(problems);
        }
        bind(jdbc.sql("""
                UPDATE connection SET full_name = :name, current_company = :company, current_role = :role, city = :city,
                    linkedin_url = :linkedin, email = :email, account_id = :account, account_link = :link, notes = :notes,
                    met_at_event_id = :event, version = version + 1
                WHERE id = :id
                """), p).param("id", id).update();
        audit.record("connection", id, AuditLog.Action.UPDATE, Map.of(), user.id());
    }

    /** Adds or updates how the signed-in user knows this person. */
    @Transactional
    void know(long id, Knowing k, CurrentUser user) {
        exists(id);
        List<String> problems = new ArrayList<>();
        knowingProblems(k, problems);
        if (!problems.isEmpty()) {
            throw new RuleViolationException(problems);
        }
        jdbc.sql("DELETE FROM connection_knower WHERE connection_id = :id AND user_id = :user")
            .param("id", id).param("user", user.id()).update();
        saveKnowing(id, k, user);
        audit.record("connection", id, AuditLog.Action.UPDATE, Map.of("knower", user.id()), user.id());
    }

    /** Removes the signed-in user as a knower; the connection goes when nobody knows them any more. */
    @Transactional
    void forget(long id, CurrentUser user) {
        exists(id);
        jdbc.sql("DELETE FROM connection_knower WHERE connection_id = :id AND user_id = :user")
            .param("id", id).param("user", user.id()).update();
        int left = jdbc.sql("SELECT COUNT(*) FROM connection_knower WHERE connection_id = :id").param("id", id)
            .query(Integer.class).single();
        if (left == 0) {
            jdbc.sql("DELETE FROM connection WHERE id = :id").param("id", id).update();
            audit.record("connection", id, AuditLog.Action.DELETE, Map.of(), user.id());
        } else {
            audit.record("connection", id, AuditLog.Action.UPDATE, Map.of("removedKnower", user.id()), user.id());
        }
    }

    /** Creates a contact at the linked account (FR-CON-01), with the connection recorded as the source. */
    @Transactional
    long makeContact(long id, ToContact in, CurrentUser user) {
        record Row(String name, String role, String city, String linkedin, String email, Long account, String link,
                   Long contact) {}
        Row r = jdbc.sql("""
                SELECT full_name, current_role, city, linkedin_url, email, account_id, account_link, contact_id
                FROM connection WHERE id = :id FOR UPDATE
                """).param("id", id)
            .query((rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getObject(6, Long.class), rs.getString(7), rs.getObject(8, Long.class)))
            .optional().orElseThrow(() -> new NotFoundException("Connection", id));
        if (r.contact() != null) {
            return r.contact();
        }
        if (r.account() == null || !"WORKS_THERE".equals(r.link())) {
            throw new RuleViolationException(List.of(
                "Link this connection to the account they work at first. People who can introduce us stay connections."));
        }
        Long already = jdbc.sql("SELECT id FROM contact WHERE account_id = :a AND full_name = :n")
            .param("a", r.account()).param("n", r.name()).query(Long.class).optional().orElse(null);
        long contactId = already != null ? already : contacts.create(new ContactService.Input(r.account(), r.name(), r.role(),
            in == null ? null : in.persona(), r.linkedin(), r.email(), null, r.city(), "Connection of " + user.name(),
            "EXISTING_RELATIONSHIP", null), user);
        jdbc.sql("UPDATE connection SET contact_id = :c, version = version + 1 WHERE id = :id")
            .param("c", contactId).param("id", id).update();
        return contactId;
    }

    private void saveKnowing(long id, Knowing k, CurrentUser user) {
        jdbc.sql("""
                INSERT INTO connection_knower (connection_id, user_id, strength, how_known, connected_on)
                VALUES (:id, :user, :strength, :how, :on)
                """)
            .param("id", id).param("user", user.id()).param("strength", upper(k.strength()))
            .param("how", trim(k.howKnown())).param("on", k.connectedOn() == null ? LocalDate.now() : k.connectedOn())
            .update();
    }

    private JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, Person p) {
        String email = trim(p.email());
        return spec.param("name", trim(p.fullName())).param("company", trim(p.currentCompany()))
            .param("role", trim(p.currentRole())).param("city", trim(p.city())).param("linkedin", trim(p.linkedinUrl()))
            .param("email", email == null ? null : email.toLowerCase(Locale.ROOT)).param("account", p.accountId())
            .param("link", p.accountId() == null ? null : upper(p.accountLink())).param("notes", trim(p.notes()))
            .param("event", p.metAtEventId());
    }

    private List<String> personProblems(Person p) {
        List<String> problems = new ArrayList<>();
        String name = trim(p.fullName());
        if (name == null) {
            problems.add("Enter the person's name.");
        } else if (name.length() > 200) {
            problems.add("The name can be at most 200 characters.");
        }
        if (length(p.currentCompany()) > 200 || length(p.currentRole()) > 200) {
            problems.add("Company and role can be at most 200 characters each.");
        }
        if (length(p.city()) > 80) {
            problems.add("The city can be at most 80 characters.");
        }
        String linkedin = trim(p.linkedinUrl());
        if (linkedin != null && (linkedin.length() > 400 || !LINKEDIN.matcher(linkedin).matches())) {
            problems.add("The LinkedIn address should start with https://www.linkedin.com/");
        }
        String email = trim(p.email());
        if (email != null && (email.length() > 254 || !EMAIL.matcher(email).matches())) {
            problems.add("The email address is not valid.");
        }
        if (p.metAtEventId() != null && jdbc.sql("SELECT COUNT(*) FROM bd_event WHERE id = :id")
                .param("id", p.metAtEventId()).query(Integer.class).single() == 0) {
            problems.add("Choose an existing event.");
        }
        if (p.accountId() != null) {
            if (!accounts.exists(p.accountId())) {
                problems.add("Choose an existing account.");
            }
            String link = upper(p.accountLink());
            if (link == null || !LINKS.contains(link)) {
                problems.add("Say whether they work at the account or can introduce us to it.");
            }
        }
        return problems;
    }

    private static void knowingProblems(Knowing k, List<String> problems) {
        String strength = upper(k.strength());
        if (strength == null || !STRENGTHS.contains(strength)) {
            problems.add("Say how well you know them.");
        }
        if (length(k.howKnown()) > 400) {
            problems.add("\"How you know them\" can be at most 400 characters.");
        }
        if (k.connectedOn() != null && k.connectedOn().isAfter(LocalDate.now())) {
            problems.add("The date you connected cannot be in the future.");
        }
    }

    private void exists(long id) {
        if (jdbc.sql("SELECT COUNT(*) FROM connection WHERE id = :id").param("id", id).query(Integer.class).single() == 0) {
            throw new NotFoundException("Connection", id);
        }
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String upper(String s) {
        return s == null || s.isBlank() ? null : s.trim().toUpperCase(Locale.ROOT);
    }

    private static int length(String s) {
        return s == null ? 0 : s.trim().length();
    }
}
