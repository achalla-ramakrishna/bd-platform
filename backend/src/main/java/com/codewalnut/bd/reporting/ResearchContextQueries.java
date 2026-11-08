package com.codewalnut.bd.reporting;

import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.codewalnut.bd.shared.NotFoundException;

/**
 * Gathers what the application knows about an account, and what CodeWalnut can offer, as input to
 * AI account research (ADR-0010). Read-only, across modules (ADR-0003). No rates or contract values.
 */
@Component
public class ResearchContextQueries {

    public record Account(long id, String name, String parentCompany, String hqCountry, String city, String domain,
                          String industry, String headcountBand, String techStack, String relationship, String notes) {}

    public record Contact(String name, String title, String persona) {}

    public record Activity(LocalDate on, String channel, String contact, String summary, String outcome) {}

    public record Opportunity(String name, String type, String stage, String need) {}

    public record Deployed(String role, String seniority, int count) {}

    /** A client CodeWalnut may name in outreach (BR-BD-11), with its industry. */
    public record NameableClient(String name, String industry) {}

    public record SkillCount(String skill, int engineers) {}

    public record Context(Account account, List<Contact> contacts, List<Activity> recentActivities,
                          List<Opportunity> opportunities, List<Deployed> deployedTeam,
                          List<NameableClient> nameableClients, List<String> unnamedClientIndustries,
                          List<SkillCount> benchSkills, List<SkillCount> allSkills) {}

    private final JdbcClient jdbc;

    ResearchContextQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Context build(long accountId) {
        Account account = jdbc.sql("""
                SELECT a.id, a.name, p.name, p.hq_country, a.city, a.website_domain, a.industry, a.headcount_band,
                    a.tech_stack, a.relationship, a.notes
                FROM account a JOIN parent_company p ON p.id = a.parent_company_id WHERE a.id = :id
                """)
            .param("id", accountId)
            .query((rs, n) -> new Account(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
                rs.getString(11)))
            .optional().orElseThrow(() -> new NotFoundException("Account", accountId));

        List<Contact> contacts = jdbc.sql("""
                SELECT full_name, title, persona FROM contact WHERE account_id = :id AND NOT opted_out ORDER BY full_name
                """).param("id", accountId)
            .query((rs, n) -> new Contact(rs.getString(1), rs.getString(2), rs.getString(3))).list();

        List<Activity> activities = jdbc.sql("""
                SELECT x.occurred_on, x.channel, c.full_name, x.summary, x.outcome
                FROM activity x LEFT JOIN contact c ON c.id = x.contact_id
                WHERE x.account_id = :id ORDER BY x.occurred_on DESC, x.id DESC LIMIT 15
                """).param("id", accountId)
            .query((rs, n) -> new Activity(rs.getObject(1, LocalDate.class), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5))).list();

        List<Opportunity> opportunities = jdbc.sql("""
                SELECT o.name, o.opportunity_type, s.label, o.need_summary
                FROM opportunity o JOIN opportunity_stage s ON s.code = o.stage_code
                WHERE o.account_id = :id ORDER BY o.updated_at DESC LIMIT 10
                """).param("id", accountId)
            .query((rs, n) -> new Opportunity(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))).list();

        List<Deployed> deployed = jdbc.sql("""
                SELECT d.role, e.seniority, COUNT(*) FROM deployment d JOIN engineer e ON e.id = d.engineer_id
                WHERE d.account_id = :id AND d.status = 'ACTIVE' GROUP BY d.role, e.seniority ORDER BY COUNT(*) DESC
                """).param("id", accountId)
            .query((rs, n) -> new Deployed(rs.getString(1), rs.getString(2), rs.getInt(3))).list();

        List<NameableClient> nameable = jdbc.sql("""
                SELECT DISTINCT p.name, a.industry FROM contract c
                JOIN account a ON a.id = c.account_id JOIN parent_company p ON p.id = a.parent_company_id
                WHERE c.client_permission_to_name AND c.status IN ('ACTIVE', 'ENDED') AND a.id <> :id
                ORDER BY p.name
                """).param("id", accountId)
            .query((rs, n) -> new NameableClient(rs.getString(1), rs.getString(2))).list();

        // Clients without permission to be named are described only by industry.
        List<String> unnamedIndustries = jdbc.sql("""
                SELECT DISTINCT COALESCE(a.industry, 'technology') FROM account a
                WHERE a.relationship IN ('CLIENT', 'PAST_CLIENT') AND a.id <> :id
                  AND NOT EXISTS (SELECT 1 FROM contract c WHERE c.account_id = a.id AND c.client_permission_to_name)
                """).param("id", accountId).query(String.class).list();

        List<SkillCount> bench = skills("e.availability IN ('BENCH', 'NOTICE')");
        List<SkillCount> all = skills("1 = 1");
        return new Context(account, contacts, activities, opportunities, deployed, nameable, unnamedIndustries, bench, all);
    }

    private List<SkillCount> skills(String where) {
        return jdbc.sql("SELECT s.skill, COUNT(*) FROM engineer_skill s JOIN engineer e ON e.id = s.engineer_id WHERE "
                + where + " GROUP BY s.skill ORDER BY COUNT(*) DESC, s.skill LIMIT 25")
            .query((rs, n) -> new SkillCount(rs.getString(1), rs.getInt(2))).list();
    }
}
