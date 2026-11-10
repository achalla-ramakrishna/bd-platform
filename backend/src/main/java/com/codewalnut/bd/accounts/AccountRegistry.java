package com.codewalnut.bd.accounts;

import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.json.JsonMapper;

/** Write side of the accounts module: parent companies and accounts. */
@Repository
public class AccountRegistry {

    public record AccountData(String name, String parentCompany, String parentHqCountry, String city,
                              String websiteDomain, String industry, String headcountBand, Integer yearEstablished,
                              List<String> techStack, String relationship, long ownerUserId, String source,
                              String notes) {}

    public record Upserted(long id, boolean created) {}

    /**
     * Targeting facts about a centre (backlog item 1): when it was formed, how big it is and how many
     * engineering roles it has open. Any field may be null.
     */
    public record Profile(Integer yearEstablished, String headcountBand, Integer employeeCount, Integer openRoles,
                          LocalDate openRolesAsOf, String openRolesSource) {

        public boolean isEmpty() {
            return yearEstablished == null && headcountBand == null && employeeCount == null && openRoles == null
                && openRolesAsOf == null && openRolesSource == null;
        }
    }

    public static final Set<String> HEADCOUNT_BANDS = Set.of("<500", "500-2000", "2000-5000", "5000+");

    /** The band an exact headcount falls in, so filters and the fit score work from either. */
    public static String bandFor(Integer employees) {
        if (employees == null) {
            return null;
        }
        return employees < 500 ? "<500" : employees < 2000 ? "500-2000" : employees < 5000 ? "2000-5000" : "5000+";
    }

    /** Problems with a profile, in plain words; empty when it can be saved. */
    public static List<String> check(Profile p) {
        List<String> problems = new ArrayList<>();
        if (p.yearEstablished() != null && (p.yearEstablished() < 1900 || p.yearEstablished() > Year.now().getValue())) {
            problems.add("Year formed must be between 1900 and " + Year.now().getValue() + ".");
        }
        if (p.headcountBand() != null && !HEADCOUNT_BANDS.contains(p.headcountBand().replace(" ", ""))) {
            problems.add("Headcount band must be one of: <500, 500-2000, 2000-5000, 5000+.");
        }
        if (p.employeeCount() != null && (p.employeeCount() < 0 || p.employeeCount() > 1_000_000)) {
            problems.add("Employee count must be between 0 and 1,000,000.");
        }
        if (p.openRoles() != null && (p.openRoles() < 0 || p.openRoles() > 100_000)) {
            problems.add("Open roles must be between 0 and 100,000.");
        }
        if (p.openRolesAsOf() != null && p.openRolesAsOf().isAfter(LocalDate.now())) {
            problems.add("The open roles date cannot be in the future.");
        }
        if (p.openRolesSource() != null && p.openRolesSource().length() > 500) {
            problems.add("The open roles source can be at most 500 characters.");
        }
        return problems;
    }

    /**
     * Saves profile facts. With {@code keepMissing}, null fields leave what is stored (uploads that only
     * know some facts); without it, null clears the field (the edit form).
     */
    public void updateProfile(long accountId, Profile p, boolean keepMissing) {
        String set = keepMissing
            ? """
              year_established = COALESCE(:year, year_established), headcount_band = COALESCE(:band, headcount_band),
              employee_count = COALESCE(:employees, employee_count), open_roles = COALESCE(:roles, open_roles),
              open_roles_as_of = COALESCE(:asOf, open_roles_as_of), open_roles_source = COALESCE(:src, open_roles_source)
              """
            : """
              year_established = :year, headcount_band = :band, employee_count = :employees, open_roles = :roles,
              open_roles_as_of = :asOf, open_roles_source = :src
              """;
        String band = p.headcountBand() == null ? null : p.headcountBand().replace(" ", "");
        if (band == null && p.employeeCount() != null) {
            band = bandFor(p.employeeCount());
        }
        String source = p.openRolesSource() == null || p.openRolesSource().isBlank() ? null : p.openRolesSource().trim();
        LocalDate asOf = p.openRolesAsOf() == null && p.openRoles() != null ? LocalDate.now() : p.openRolesAsOf();
        jdbc.sql("UPDATE account SET " + set + ", version = version + 1 WHERE id = :id")
            .param("year", p.yearEstablished()).param("band", band).param("employees", p.employeeCount())
            .param("roles", p.openRoles()).param("asOf", asOf).param("src", source).param("id", accountId)
            .update();
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public AccountRegistry(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** All account keys with their ids. Release 1 holds at most a few thousand accounts. */
    public Map<AccountKey, Long> allKeys() {
        Map<AccountKey, Long> keys = new HashMap<>();
        jdbc.sql("SELECT id, website_domain, city FROM account")
            .query((rs, n) -> keys.put(new AccountKey(rs.getString(2), rs.getString(3)), rs.getLong(1)))
            .list();
        return keys;
    }

    public Upserted upsert(AccountData d) {
        long parentId = upsertParent(d.parentCompany(), d.parentHqCountry());
        AccountKey key = new AccountKey(d.websiteDomain(), d.city());
        Long existing = jdbc.sql("SELECT id FROM account WHERE website_domain = :domain AND city = :city")
            .param("domain", key.domain()).param("city", d.city().trim())
            .query(Long.class).optional().orElse(null);
        var spec = existing == null
            ? jdbc.sql("""
                INSERT INTO account (parent_company_id, name, city, website_domain, industry, headcount_band,
                    year_established, tech_stack, relationship, owner_user_id, source, notes)
                VALUES (:parent, :name, :city, :domain, :industry, :band, :year, :tech, :rel, :owner, :source, :notes)
                """)
            : jdbc.sql("""
                UPDATE account SET parent_company_id = :parent, name = :name, industry = :industry,
                    headcount_band = :band, year_established = :year, tech_stack = :tech, relationship = :rel,
                    owner_user_id = :owner, source = :source, notes = :notes, version = version + 1
                WHERE id = :id
                """).param("id", existing);
        spec = spec.param("parent", parentId).param("name", d.name()).param("city", d.city().trim())
            .param("domain", key.domain()).param("industry", d.industry()).param("band", d.headcountBand())
            .param("year", d.yearEstablished())
            .param("tech", d.techStack() == null || d.techStack().isEmpty() ? null : json.writeValueAsString(d.techStack()))
            .param("rel", d.relationship()).param("owner", d.ownerUserId()).param("source", d.source())
            .param("notes", d.notes());
        if (existing != null) {
            spec.update();
            return new Upserted(existing, false);
        }
        var keys = new GeneratedKeyHolder();
        spec.update(keys);
        return new Upserted(keys.getKey().longValue(), true);
    }

    /** Parent company names with an account in the city, to tell a search what we already have. */
    public List<String> parentNamesIn(String city) {
        return jdbc.sql("SELECT DISTINCT p.name FROM account a JOIN parent_company p ON p.id = a.parent_company_id WHERE a.city = :city ORDER BY p.name")
            .param("city", city.trim()).query(String.class).list();
    }

    public record Found(long id, String name, String parentCompany, String city, String websiteDomain) {}

    /** Accounts whose name, parent company or domain contains the text (for matching a company name). */
    public List<Found> search(String text, int limit) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT a.id, a.name, p.name, a.city, a.website_domain FROM account a
                JOIN parent_company p ON p.id = a.parent_company_id
                WHERE a.name LIKE :q OR p.name LIKE :q OR a.website_domain LIKE :q
                ORDER BY (p.name = :exact OR a.name = :exact) DESC, a.relationship = 'CLIENT' DESC, a.name
                LIMIT :limit
                """).param("q", "%" + text.trim() + "%").param("exact", text.trim()).param("limit", limit)
            .query((rs, n) -> new Found(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)))
            .list();
    }

    public boolean exists(long accountId) {
        return jdbc.sql("SELECT COUNT(*) FROM account WHERE id = :id").param("id", accountId).query(Integer.class).single() > 0;
    }

    /** An account with an active contract is a client (domain model, "Relationship"). */
    public void markClients(Collection<Long> accountIds) {
        if (accountIds.isEmpty()) {
            return;
        }
        jdbc.sql("UPDATE account SET relationship = 'CLIENT' WHERE id IN (:ids) AND relationship <> 'CLIENT'")
            .param("ids", accountIds).update();
    }

    private long upsertParent(String name, String hqCountry) {
        String trimmed = name.trim();
        Long id = jdbc.sql("SELECT id FROM parent_company WHERE name = :name").param("name", trimmed)
            .query(Long.class).optional().orElse(null);
        if (id != null) {
            if (hqCountry != null && !hqCountry.isBlank()) {
                jdbc.sql("UPDATE parent_company SET hq_country = :hq WHERE id = :id").param("hq", hqCountry).param("id", id).update();
            }
            return id;
        }
        var keys = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO parent_company (name, hq_country) VALUES (:name, :hq)")
            .param("name", trimmed).param("hq", hqCountry).update(keys);
        return keys.getKey().longValue();
    }
}
