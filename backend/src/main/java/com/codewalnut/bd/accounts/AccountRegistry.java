package com.codewalnut.bd.accounts;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
