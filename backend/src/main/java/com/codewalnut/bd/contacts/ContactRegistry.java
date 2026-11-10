package com.codewalnut.bd.contacts;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** Write side of the contacts module. A contact is keyed by account + full name. */
@Repository
public class ContactRegistry {

    public static final Set<String> CONSENT_BASES = Set.of("EXISTING_RELATIONSHIP", "BUSINESS_CONTACT", "CONSENTED");

    public record ContactData(long accountId, String fullName, String title, String persona, String linkedinUrl,
                              String email, String phone, String city, String source, String consentBasis,
                              String notes) {}

    public record Upserted(long id, boolean created) {}

    private final JdbcClient jdbc;

    public ContactRegistry(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public static String key(long accountId, String fullName) {
        return accountId + "|" + fullName.trim().toLowerCase(Locale.ROOT);
    }

    public Map<String, Long> allKeys() {
        Map<String, Long> keys = new HashMap<>();
        jdbc.sql("SELECT id, account_id, full_name FROM contact")
            .query((rs, n) -> keys.put(key(rs.getLong(2), rs.getString(3)), rs.getLong(1))).list();
        return keys;
    }

    /** The account a contact belongs to, if the contact exists. */
    /** A contact at the account with this name, ignoring case. */
    public java.util.Optional<Long> findByName(long accountId, String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return java.util.Optional.empty();
        }
        return jdbc.sql("SELECT id FROM contact WHERE account_id = :a AND LOWER(full_name) = LOWER(:n) LIMIT 1")
            .param("a", accountId).param("n", fullName.trim()).query(Long.class).optional();
    }

    public java.util.Optional<Long> accountOf(long contactId) {
        return jdbc.sql("SELECT account_id FROM contact WHERE id = :id").param("id", contactId).query(Long.class).optional();
    }

    public Set<String> personaCodes() {
        return Set.copyOf(jdbc.sql("SELECT code FROM picklist_value WHERE list_name = 'PERSONA' AND active")
            .query(String.class).list());
    }

    public Upserted upsert(ContactData d) {
        Long existing = jdbc.sql("SELECT id FROM contact WHERE account_id = :account AND full_name = :name")
            .param("account", d.accountId()).param("name", d.fullName().trim())
            .query(Long.class).optional().orElse(null);
        var spec = existing == null
            ? jdbc.sql("""
                INSERT INTO contact (account_id, full_name, title, persona, linkedin_url, email, phone, city,
                    source, consent_basis, notes)
                VALUES (:account, :name, :title, :persona, :linkedin, :email, :phone, :city, :source, :consent, :notes)
                """)
            : jdbc.sql("""
                UPDATE contact SET title = :title, persona = :persona, linkedin_url = :linkedin, email = :email,
                    phone = :phone, city = :city, source = :source, consent_basis = :consent, notes = :notes,
                    version = version + 1
                WHERE id = :id AND account_id = :account AND full_name = :name
                """).param("id", existing);
        spec = spec.param("account", d.accountId()).param("name", d.fullName().trim()).param("title", d.title())
            .param("persona", d.persona()).param("linkedin", d.linkedinUrl()).param("email", d.email())
            .param("phone", d.phone()).param("city", d.city()).param("source", d.source())
            .param("consent", d.consentBasis()).param("notes", d.notes());
        if (existing != null) {
            spec.update();
            return new Upserted(existing, false);
        }
        var keys = new GeneratedKeyHolder();
        spec.update(keys);
        return new Upserted(keys.getKey().longValue(), true);
    }
}
