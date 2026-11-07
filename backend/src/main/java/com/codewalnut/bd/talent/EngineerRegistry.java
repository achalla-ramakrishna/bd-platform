package com.codewalnut.bd.talent;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** Write side of the talent module: engineers and their skills. */
@Repository
public class EngineerRegistry {

    public record EngineerData(String employeeCode, String fullName, String workEmail, String primaryRole,
                               String seniority, List<String> skills, String location, String availability,
                               LocalDate availableFrom, boolean consentToShareProfile, String notes) {}

    public record EngineerRef(long id, String employeeCode, String workEmail, String seniority) {}

    public record Upserted(long id, boolean created) {}

    private final JdbcClient jdbc;

    public EngineerRegistry(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Engineers keyed by lower-cased employee code. */
    public Map<String, EngineerRef> allByCode() {
        Map<String, EngineerRef> map = new HashMap<>();
        jdbc.sql("SELECT id, employee_code, work_email, seniority FROM engineer")
            .query((rs, n) -> new EngineerRef(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)))
            .list()
            .forEach(e -> map.put(e.employeeCode().toLowerCase(Locale.ROOT), e));
        return map;
    }

    /** Seniority level to the most clients an engineer at that level may work for at once (BR-BD-14). */
    public Map<String, Integer> maxConcurrentClients() {
        Map<String, Integer> rules = new LinkedHashMap<>();
        jdbc.sql("SELECT seniority, max_concurrent_clients FROM seniority_rule ORDER BY sort_order")
            .query((rs, n) -> rules.put(rs.getString(1), rs.getInt(2))).list();
        return rules;
    }

    public Upserted upsert(EngineerData d) {
        Long existing = jdbc.sql("SELECT id FROM engineer WHERE employee_code = :code")
            .param("code", d.employeeCode()).query(Long.class).optional().orElse(null);
        var spec = existing == null
            ? jdbc.sql("""
                INSERT INTO engineer (employee_code, full_name, work_email, primary_role, seniority, location,
                    availability, available_from, consent_to_share_profile, notes)
                VALUES (:code, :name, :email, :role, :seniority, :location, :availability, :from, :consent, :notes)
                """)
            : jdbc.sql("""
                UPDATE engineer SET full_name = :name, work_email = :email, primary_role = :role,
                    seniority = :seniority, location = :location, availability = :availability,
                    available_from = :from, consent_to_share_profile = :consent, notes = :notes,
                    version = version + 1
                WHERE id = :id AND employee_code = :code
                """).param("id", existing);
        spec = spec.param("code", d.employeeCode()).param("name", d.fullName()).param("email", d.workEmail())
            .param("role", d.primaryRole()).param("seniority", d.seniority()).param("location", d.location())
            .param("availability", d.availability()).param("from", d.availableFrom())
            .param("consent", d.consentToShareProfile()).param("notes", d.notes());
        long id;
        boolean created;
        if (existing != null) {
            spec.update();
            id = existing;
            created = false;
        } else {
            var keys = new GeneratedKeyHolder();
            spec.update(keys);
            id = keys.getKey().longValue();
            created = true;
        }
        jdbc.sql("DELETE FROM engineer_skill WHERE engineer_id = :id").param("id", id).update();
        for (String skill : d.skills()) {
            jdbc.sql("INSERT IGNORE INTO engineer_skill (engineer_id, skill) VALUES (:id, :skill)")
                .param("id", id).param("skill", skill).update();
        }
        return new Upserted(id, created);
    }

    /** An engineer with an active deployment today is deployed (domain model, "Engineer"). */
    public void markDeployed(Collection<Long> engineerIds) {
        if (engineerIds.isEmpty()) {
            return;
        }
        jdbc.sql("UPDATE engineer SET availability = 'DEPLOYED', available_from = NULL WHERE id IN (:ids)")
            .param("ids", engineerIds).update();
    }
}
