package com.codewalnut.bd.reporting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.codewalnut.bd.shared.NotFoundException;

import tools.jackson.databind.json.JsonMapper;

/** Read views of the pipeline, joining accounts, contacts and users (ADR-0003). */
@Component
public class OpportunityQueries {

    public record Requirement(long id, String role, List<String> skills, String seniority, int headcount,
                              String locationMode, String location, LocalDate startDate, Integer durationMonths,
                              int allocationPercent, BigDecimal rateInr, String rateUnit, String deploymentRequest) {}

    public record StageChange(String fromStage, String toStage, String changedBy, Instant changedAt) {}

    public record Opportunity(long id, long accountId, String accountName, String name, String type, long ownerId,
                              String ownerName, String stage, String stageLabel, String stageOutcome,
                              BigDecimal valueInr, int probability, BigDecimal weightedValueInr,
                              LocalDate expectedCloseDate, Long decisionMakerContactId, String decisionMakerName,
                              String needSummary, LocalDate targetStartDate, String lossReasonCode,
                              String scopeSummary, String pricingModel, BigDecimal estimateInr, String teamShape,
                              String timeline, int version, Instant updatedAt, List<Requirement> requirements,
                              List<StageChange> history) {}

    private static final String SELECT = """
        SELECT o.id, o.account_id, a.name AS account_name, o.name, o.opportunity_type, o.owner_user_id,
            u.full_name AS owner_name, o.stage_code, s.label AS stage_label, s.outcome, o.value_inr, o.probability,
            o.expected_close_date, o.decision_maker_contact_id, c.full_name AS dm_name, o.need_summary,
            o.target_start_date, o.loss_reason_code, o.scope_summary, o.pricing_model, o.estimate_inr,
            o.team_shape, o.timeline, o.version, o.updated_at
        FROM opportunity o
        JOIN account a ON a.id = o.account_id
        JOIN app_user u ON u.id = o.owner_user_id
        JOIN opportunity_stage s ON s.code = o.stage_code
        LEFT JOIN contact c ON c.id = o.decision_maker_contact_id
        """;

    private final JdbcClient jdbc;
    private final JsonMapper json;

    OpportunityQueries(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Opportunity get(long id) {
        Opportunity base = jdbc.sql(SELECT + " WHERE o.id = :id").param("id", id)
            .query((rs, n) -> map(rs, List.of(), history(id)))
            .optional().orElseThrow(() -> new NotFoundException("Opportunity", id));
        return withRequirements(base, requirements(List.of(id)).getOrDefault(id, List.of()));
    }

    /** The configured stage codes, in pipeline order. */
    public List<String> stageCodes() {
        return jdbc.sql("SELECT code FROM opportunity_stage ORDER BY sort_order").query(String.class).list();
    }

    /** Opportunities at the account that are still open (not won or lost), most recently changed first. */
    public List<Opportunity> openAt(long accountId) {
        return list(accountId, null, null, null).stream().filter(o -> "OPEN".equals(o.stageOutcome()))
            .sorted(java.util.Comparator.comparing(Opportunity::updatedAt).reversed()).toList();
    }

    public List<Opportunity> list(Long accountId, Long ownerId, String type, String stage) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        Map<String, Object> params = new LinkedHashMap<>();
        if (accountId != null) {
            where.append(" AND o.account_id = :account");
            params.put("account", accountId);
        }
        if (ownerId != null) {
            where.append(" AND o.owner_user_id = :owner");
            params.put("owner", ownerId);
        }
        if (type != null && !type.isBlank()) {
            where.append(" AND o.opportunity_type = :type");
            params.put("type", type.toUpperCase(Locale.ROOT));
        }
        if (stage != null && !stage.isBlank()) {
            where.append(" AND o.stage_code = :stage");
            params.put("stage", stage.toUpperCase(Locale.ROOT));
        }
        List<Opportunity> rows = jdbc
            .sql(SELECT + where + " ORDER BY s.sort_order, o.expected_close_date IS NULL, o.expected_close_date, o.id")
            .params(params)
            .query((rs, n) -> map(rs, List.of(), List.of()))
            .list();
        Map<Long, List<Requirement>> reqs = requirements(rows.stream().map(Opportunity::id).toList());
        return rows.stream().map(o -> withRequirements(o, reqs.getOrDefault(o.id(), List.of()))).toList();
    }

    private static Opportunity withRequirements(Opportunity o, List<Requirement> r) {
        return new Opportunity(o.id(), o.accountId(), o.accountName(), o.name(), o.type(), o.ownerId(), o.ownerName(),
            o.stage(), o.stageLabel(), o.stageOutcome(), o.valueInr(), o.probability(), o.weightedValueInr(),
            o.expectedCloseDate(), o.decisionMakerContactId(), o.decisionMakerName(), o.needSummary(),
            o.targetStartDate(), o.lossReasonCode(), o.scopeSummary(), o.pricingModel(), o.estimateInr(),
            o.teamShape(), o.timeline(), o.version(), o.updatedAt(), r, o.history());
    }

    private Opportunity map(java.sql.ResultSet rs, List<Requirement> requirements, List<StageChange> history)
        throws java.sql.SQLException {
        BigDecimal value = rs.getBigDecimal("value_inr");
        int probability = rs.getInt("probability");
        BigDecimal weighted = value == null ? null
            : value.multiply(BigDecimal.valueOf(probability)).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        return new Opportunity(rs.getLong("id"), rs.getLong("account_id"), rs.getString("account_name"),
            rs.getString("name"), rs.getString("opportunity_type"), rs.getLong("owner_user_id"),
            rs.getString("owner_name"), rs.getString("stage_code"), rs.getString("stage_label"), rs.getString("outcome"),
            value, probability, weighted, rs.getObject("expected_close_date", LocalDate.class),
            rs.getObject("decision_maker_contact_id", Long.class), rs.getString("dm_name"), rs.getString("need_summary"),
            rs.getObject("target_start_date", LocalDate.class), rs.getString("loss_reason_code"),
            rs.getString("scope_summary"), rs.getString("pricing_model"), rs.getBigDecimal("estimate_inr"),
            rs.getString("team_shape"), rs.getString("timeline"), rs.getInt("version"),
            rs.getTimestamp("updated_at").toInstant(), requirements, history);
    }

    private Map<Long, List<Requirement>> requirements(List<Long> opportunityIds) {
        Map<Long, List<Requirement>> map = new LinkedHashMap<>();
        if (opportunityIds.isEmpty()) {
            return map;
        }
        jdbc.sql("""
                SELECT r.opportunity_id, r.id, r.role, r.skills, r.seniority, r.headcount, r.location_mode, r.location,
                    r.start_date, r.duration_months, r.allocation_percent, r.rate_inr, r.rate_unit, d.status
                FROM opportunity_requirement r LEFT JOIN deployment_request d ON d.requirement_id = r.id
                WHERE r.opportunity_id IN (:ids) ORDER BY r.id
                """)
            .param("ids", opportunityIds)
            .query((rs, n) -> map.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(new Requirement(
                rs.getLong(2), rs.getString(3), List.of(json.readValue(rs.getString(4), String[].class)), rs.getString(5),
                rs.getInt(6), rs.getString(7), rs.getString(8), rs.getObject(9, LocalDate.class),
                rs.getObject(10, Integer.class), rs.getInt(11), rs.getBigDecimal(12), rs.getString(13), rs.getString(14))))
            .list();
        return map;
    }

    private List<StageChange> history(long opportunityId) {
        return jdbc.sql("""
                SELECT h.from_stage, h.to_stage, u.full_name, h.changed_at
                FROM opportunity_stage_history h JOIN app_user u ON u.id = h.changed_by
                WHERE h.opportunity_id = :id ORDER BY h.changed_at DESC, h.id DESC
                """)
            .param("id", opportunityId)
            .query((rs, n) -> new StageChange(rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant()))
            .list();
    }
}
