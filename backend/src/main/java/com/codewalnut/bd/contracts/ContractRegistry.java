package com.codewalnut.bd.contracts;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** Write side of the contracts module: MSAs, SOWs and deployments of engineers to clients. */
@Repository
public class ContractRegistry {

    public record ContractData(String contractRef, long accountId, String contractType, Long parentMsaId,
                               String workType, String pricingModel, BigDecimal totalValueInr, LocalDate startDate,
                               LocalDate endDate, LocalDate renewalDate, String status, boolean clientPermissionToName,
                               String notes) {}

    public record ContractRef(long id, String contractRef, long accountId, String contractType, LocalDate startDate,
                              LocalDate endDate) {}

    public record DeploymentData(long engineerId, long accountId, Long sowId, String teamOrProject, String role,
                                 String clientManagerName, int allocationPercent, BigDecimal billRateInr,
                                 String rateUnit, LocalDate startDate, LocalDate expectedEndDate, String status,
                                 String notes) {}

    /** The part of a deployment that BR-BD-14 checks. A null end date means open-ended. */
    public record DeploymentSpan(long engineerId, long accountId, LocalDate startDate, LocalDate endDate,
                                 int allocationPercent) {}

    public record Upserted(long id, boolean created) {}

    private final JdbcClient jdbc;

    public ContractRegistry(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Contracts keyed by lower-cased reference. */
    public Map<String, ContractRef> allByRef() {
        Map<String, ContractRef> map = new HashMap<>();
        jdbc.sql("SELECT id, contract_ref, account_id, contract_type, start_date, end_date FROM contract")
            .query((rs, n) -> new ContractRef(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4),
                rs.getObject(5, LocalDate.class), rs.getObject(6, LocalDate.class)))
            .list()
            .forEach(c -> map.put(c.contractRef().toLowerCase(Locale.ROOT), c));
        return map;
    }

    public Upserted upsertContract(ContractData d) {
        Long existing = jdbc.sql("SELECT id FROM contract WHERE contract_ref = :ref")
            .param("ref", d.contractRef()).query(Long.class).optional().orElse(null);
        var spec = existing == null
            ? jdbc.sql("""
                INSERT INTO contract (contract_ref, account_id, contract_type, parent_msa_id, work_type, pricing_model,
                    total_value_inr, start_date, end_date, renewal_date, status, client_permission_to_name, notes)
                VALUES (:ref, :account, :type, :parent, :work, :pricing, :value, :start, :end, :renewal, :status,
                    :permission, :notes)
                """)
            : jdbc.sql("""
                UPDATE contract SET account_id = :account, contract_type = :type, parent_msa_id = :parent,
                    work_type = :work, pricing_model = :pricing, total_value_inr = :value, start_date = :start,
                    end_date = :end, renewal_date = :renewal, status = :status,
                    client_permission_to_name = :permission, notes = :notes, version = version + 1
                WHERE id = :id AND contract_ref = :ref
                """).param("id", existing);
        spec = spec.param("ref", d.contractRef()).param("account", d.accountId()).param("type", d.contractType())
            .param("parent", d.parentMsaId()).param("work", d.workType()).param("pricing", d.pricingModel())
            .param("value", d.totalValueInr()).param("start", d.startDate()).param("end", d.endDate())
            .param("renewal", d.renewalDate()).param("status", d.status())
            .param("permission", d.clientPermissionToName()).param("notes", d.notes());
        if (existing != null) {
            spec.update();
            return new Upserted(existing, false);
        }
        var keys = new GeneratedKeyHolder();
        spec.update(keys);
        return new Upserted(keys.getKey().longValue(), true);
    }

    public static String deploymentKey(long engineerId, long accountId, LocalDate startDate) {
        return engineerId + "|" + accountId + "|" + startDate;
    }

    /** Deployment ids keyed by {@link #deploymentKey}. */
    public Map<String, Long> deploymentKeys() {
        Map<String, Long> map = new HashMap<>();
        jdbc.sql("SELECT id, engineer_id, account_id, start_date FROM deployment")
            .query((rs, n) -> map.put(deploymentKey(rs.getLong(2), rs.getLong(3), rs.getObject(4, LocalDate.class)),
                rs.getLong(1)))
            .list();
        return map;
    }

    /** Planned and active deployments of the given engineers, with their keys. */
    public Map<String, DeploymentSpan> openSpans(Collection<Long> engineerIds) {
        Map<String, DeploymentSpan> map = new HashMap<>();
        if (engineerIds.isEmpty()) {
            return map;
        }
        jdbc.sql("""
                SELECT engineer_id, account_id, start_date, COALESCE(actual_end_date, expected_end_date), allocation_percent
                FROM deployment WHERE engineer_id IN (:ids) AND status <> 'ENDED'
                """)
            .param("ids", engineerIds)
            .query((rs, n) -> new DeploymentSpan(rs.getLong(1), rs.getLong(2), rs.getObject(3, LocalDate.class),
                rs.getObject(4, LocalDate.class), rs.getInt(5)))
            .list()
            .forEach(s -> map.put(deploymentKey(s.engineerId(), s.accountId(), s.startDate()), s));
        return map;
    }

    public Upserted upsertDeployment(DeploymentData d) {
        Long existing = jdbc.sql("""
                SELECT id FROM deployment WHERE engineer_id = :engineer AND account_id = :account AND start_date = :start
                """)
            .param("engineer", d.engineerId()).param("account", d.accountId()).param("start", d.startDate())
            .query(Long.class).optional().orElse(null);
        var spec = existing == null
            ? jdbc.sql("""
                INSERT INTO deployment (engineer_id, account_id, sow_id, team_or_project, role, client_manager_name,
                    allocation_percent, bill_rate_inr, rate_unit, start_date, expected_end_date, status, notes)
                VALUES (:engineer, :account, :sow, :team, :role, :manager, :allocation, :rate, :unit, :start, :end,
                    :status, :notes)
                """)
            : jdbc.sql("""
                UPDATE deployment SET sow_id = :sow, team_or_project = :team, role = :role,
                    client_manager_name = :manager, allocation_percent = :allocation, bill_rate_inr = :rate,
                    rate_unit = :unit, expected_end_date = :end, status = :status, notes = :notes,
                    version = version + 1
                WHERE id = :id AND engineer_id = :engineer AND account_id = :account AND start_date = :start
                """).param("id", existing);
        spec = spec.param("engineer", d.engineerId()).param("account", d.accountId()).param("sow", d.sowId())
            .param("team", d.teamOrProject()).param("role", d.role()).param("manager", d.clientManagerName())
            .param("allocation", d.allocationPercent()).param("rate", d.billRateInr()).param("unit", d.rateUnit())
            .param("start", d.startDate()).param("end", d.expectedEndDate()).param("status", d.status())
            .param("notes", d.notes());
        if (existing != null) {
            spec.update();
            return new Upserted(existing, false);
        }
        var keys = new GeneratedKeyHolder();
        spec.update(keys);
        return new Upserted(keys.getKey().longValue(), true);
    }

    /** Accounts with at least one active contract, among those given. */
    public List<Long> accountsWithActiveContracts(Collection<Long> accountIds) {
        if (accountIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT DISTINCT account_id FROM contract WHERE status = 'ACTIVE' AND account_id IN (:ids)")
            .param("ids", accountIds).query(Long.class).list();
    }

    /** Engineers with an active deployment covering today, among those given. */
    public List<Long> engineersActiveToday(Collection<Long> engineerIds) {
        if (engineerIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT DISTINCT engineer_id FROM deployment
                WHERE status = 'ACTIVE' AND engineer_id IN (:ids) AND start_date <= CURRENT_DATE
                  AND (expected_end_date IS NULL OR expected_end_date >= CURRENT_DATE)
                """)
            .param("ids", engineerIds).query(Long.class).list();
    }
}
