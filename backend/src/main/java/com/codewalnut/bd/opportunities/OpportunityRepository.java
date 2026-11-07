package com.codewalnut.bd.opportunities;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.json.JsonMapper;

/** SQL for opportunities, their requirement lines, stage history and deployment requests. */
@Repository
class OpportunityRepository {

    record Requirement(String role, List<String> skills, String seniority, int headcount, String locationMode,
                       String location, LocalDate startDate, Integer durationMonths, int allocationPercent,
                       BigDecimal rateInr, String rateUnit) {}

    /** The editable fields of an opportunity. */
    record Data(long accountId, String name, String type, long ownerUserId, BigDecimal valueInr, int probability,
                LocalDate expectedCloseDate, Long decisionMakerContactId, String needSummary,
                LocalDate targetStartDate, String scopeSummary, String pricingModel, BigDecimal estimateInr,
                String teamShape, String timeline) {}

    /** What the stage rules need to know. */
    record State(long id, long accountId, String type, String stage, Long decisionMakerContactId, String needSummary,
                 LocalDate targetStartDate, String scopeSummary, int version, List<Requirement> requirements) {}

    private final JdbcClient jdbc;
    private final JsonMapper json;

    OpportunityRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    Map<String, StageRules.Stage> stages() {
        Map<String, StageRules.Stage> stages = new LinkedHashMap<>();
        jdbc.sql("SELECT code, label, sort_order, default_probability, outcome FROM opportunity_stage ORDER BY sort_order")
            .query((rs, n) -> new StageRules.Stage(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getString(5)))
            .list()
            .forEach(s -> stages.put(s.code(), s));
        return stages;
    }

    Set<String> lossReasons() {
        return Set.copyOf(jdbc.sql("SELECT code FROM picklist_value WHERE list_name = 'LOSS_REASON' AND active")
            .query(String.class).list());
    }

    Set<String> seniorities() {
        return Set.copyOf(jdbc.sql("SELECT seniority FROM seniority_rule").query(String.class).list());
    }

    long insert(Data d, String stage) {
        var keys = new GeneratedKeyHolder();
        bind(jdbc.sql("""
                INSERT INTO opportunity (account_id, name, opportunity_type, owner_user_id, stage_code, value_inr,
                    probability, expected_close_date, decision_maker_contact_id, need_summary, target_start_date,
                    scope_summary, pricing_model, estimate_inr, team_shape, timeline)
                VALUES (:account, :name, :type, :owner, :stage, :value, :probability, :close, :dm, :need, :start,
                    :scope, :pricing, :estimate, :team, :timeline)
                """), d).param("stage", stage).update(keys);
        return keys.getKey().longValue();
    }

    /** Returns false when the version no longer matches (someone else saved in between). */
    boolean update(long id, Data d, int expectedVersion) {
        int rows = bind(jdbc.sql("""
                UPDATE opportunity SET account_id = :account, name = :name, opportunity_type = :type,
                    owner_user_id = :owner, value_inr = :value, probability = :probability,
                    expected_close_date = :close, decision_maker_contact_id = :dm, need_summary = :need,
                    target_start_date = :start, scope_summary = :scope, pricing_model = :pricing,
                    estimate_inr = :estimate, team_shape = :team, timeline = :timeline, version = version + 1
                WHERE id = :id AND version = :version
                """), d).param("id", id).param("version", expectedVersion).update();
        return rows == 1;
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, Data d) {
        return spec.param("account", d.accountId()).param("name", d.name()).param("type", d.type())
            .param("owner", d.ownerUserId()).param("value", d.valueInr()).param("probability", d.probability())
            .param("close", d.expectedCloseDate()).param("dm", d.decisionMakerContactId())
            .param("need", d.needSummary()).param("start", d.targetStartDate()).param("scope", d.scopeSummary())
            .param("pricing", d.pricingModel()).param("estimate", d.estimateInr()).param("team", d.teamShape())
            .param("timeline", d.timeline());
    }

    void replaceRequirements(long opportunityId, List<Requirement> requirements) {
        jdbc.sql("DELETE FROM opportunity_requirement WHERE opportunity_id = :id").param("id", opportunityId).update();
        for (Requirement r : requirements) {
            jdbc.sql("""
                    INSERT INTO opportunity_requirement (opportunity_id, role, skills, seniority, headcount,
                        location_mode, location, start_date, duration_months, allocation_percent, rate_inr, rate_unit)
                    VALUES (:opp, :role, :skills, :seniority, :headcount, :mode, :location, :start, :duration,
                        :allocation, :rate, :unit)
                    """)
                .param("opp", opportunityId).param("role", r.role()).param("skills", json.writeValueAsString(r.skills()))
                .param("seniority", r.seniority()).param("headcount", r.headcount()).param("mode", r.locationMode())
                .param("location", r.location()).param("start", r.startDate()).param("duration", r.durationMonths())
                .param("allocation", r.allocationPercent()).param("rate", r.rateInr()).param("unit", r.rateUnit())
                .update();
        }
    }

    boolean hasDeploymentRequests(long opportunityId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM deployment_request d JOIN opportunity_requirement r ON r.id = d.requirement_id
                WHERE r.opportunity_id = :id
                """).param("id", opportunityId).query(Integer.class).single() > 0;
    }

    Optional<State> load(long id) {
        return jdbc.sql("""
                SELECT id, account_id, opportunity_type, stage_code, decision_maker_contact_id, need_summary,
                    target_start_date, scope_summary, version
                FROM opportunity WHERE id = :id
                """)
            .param("id", id)
            .query((rs, n) -> new State(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                rs.getObject(5, Long.class), rs.getString(6), rs.getObject(7, LocalDate.class), rs.getString(8),
                rs.getInt(9), requirements(id)))
            .optional();
    }

    List<Requirement> requirements(long opportunityId) {
        return jdbc.sql("""
                SELECT role, skills, seniority, headcount, location_mode, location, start_date, duration_months,
                    allocation_percent, rate_inr, rate_unit
                FROM opportunity_requirement WHERE opportunity_id = :id ORDER BY id
                """)
            .param("id", opportunityId)
            .query((rs, n) -> new Requirement(rs.getString(1), List.of(json.readValue(rs.getString(2), String[].class)),
                rs.getString(3), rs.getInt(4), rs.getString(5), rs.getString(6), rs.getObject(7, LocalDate.class),
                rs.getObject(8, Integer.class), rs.getInt(9), rs.getBigDecimal(10), rs.getString(11)))
            .list();
    }

    void moveStage(long id, String stage, int probability, String lossReason) {
        jdbc.sql("""
                UPDATE opportunity SET stage_code = :stage, probability = :probability, loss_reason_code = :reason,
                    version = version + 1
                WHERE id = :id
                """)
            .param("stage", stage).param("probability", probability).param("reason", lossReason).param("id", id)
            .update();
    }

    void recordStage(long id, String from, String to, long userId) {
        jdbc.sql("INSERT INTO opportunity_stage_history (opportunity_id, from_stage, to_stage, changed_by) VALUES (:id, :from, :to, :user)")
            .param("id", id).param("from", from).param("to", to).param("user", userId).update();
    }

    /** BR-BD-12: one open request to Delivery per requirement line of a won opportunity. */
    int createDeploymentRequests(long opportunityId) {
        return jdbc.sql("""
                INSERT IGNORE INTO deployment_request (requirement_id)
                SELECT id FROM opportunity_requirement WHERE opportunity_id = :id
                """).param("id", opportunityId).update();
    }
}
