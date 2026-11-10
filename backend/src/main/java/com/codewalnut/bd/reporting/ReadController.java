package com.codewalnut.bd.reporting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.codewalnut.bd.admin.CurrentUserProvider;
import com.codewalnut.bd.shared.CurrentUser;
import com.codewalnut.bd.shared.NotFoundException;

import tools.jackson.databind.json.JsonMapper;

/**
 * Read-only views across modules (ADR-0003 allows the reporting module to read across tables).
 * Contract values and bill rates are null unless the caller may see them (FR-EXI-08).
 */
@RestController
@RequestMapping("/api/v1")
class ReadController {

    /** Working days in a month and hours in a day, for monthly-equivalent billing. Approximate. */
    private static final BigDecimal DAYS_PER_MONTH = BigDecimal.valueOf(21);
    private static final BigDecimal HOURS_PER_MONTH = BigDecimal.valueOf(168);

    record Account(long id, String name, String parentCompany, String parentHqCountry, String city,
                   String websiteDomain, String industry, String headcountBand, Integer yearEstablished,
                   List<String> techStack, String relationship, long ownerId, String ownerName, String source,
                   String notes, int activeEngineers, java.time.Instant researchedAt, Integer fitScore,
                   List<String> fitReasons) {

        Account withFit(FitScorer.Fit fit) {
            return new Account(id, name, parentCompany, parentHqCountry, city, websiteDomain, industry, headcountBand,
                yearEstablished, techStack, relationship, ownerId, ownerName, source, notes, activeEngineers,
                researchedAt, fit == null ? null : fit.score(), fit == null ? List.of() : fit.reasons());
        }
    }

    record AccountPage(List<Account> items, int page, int size, long total) {}

    record AccountDetail(Account account, List<Contact> contacts, List<Contract> contracts,
                         List<Deployment> deployments, BigDecimal monthlyBillingInr) {}

    record Contact(long id, long accountId, String accountName, String fullName, String title, String persona,
                   String linkedinUrl, String email, String phone, String city, String source, String consentBasis,
                   boolean optedOut) {}

    record Contract(long id, String contractRef, long accountId, String accountName, String contractType,
                    String parentMsaRef, String workType, String pricingModel, BigDecimal totalValueInr,
                    LocalDate startDate, LocalDate endDate, LocalDate renewalDate, String status,
                    boolean clientPermissionToName) {}

    record Engineer(long id, String employeeCode, String fullName, String workEmail, String primaryRole,
                    String seniority, List<String> skills, String location, String availability,
                    LocalDate availableFrom, boolean consentToShareProfile, int currentAllocationPercent) {}

    record Deployment(long id, long engineerId, String engineerName, String employeeCode, String seniority,
                      long accountId, String accountName, String sowRef, String teamOrProject, String role,
                      String clientManagerName, int allocationPercent, BigDecimal billRateInr, String rateUnit,
                      LocalDate startDate, LocalDate expectedEndDate, String status) {}

    private final JdbcClient jdbc;
    private final CurrentUserProvider currentUser;
    private final JsonMapper json;
    private final OpportunityQueries opportunities;
    private final ActivityQueries activities;
    private final FitScorer fit;

    ReadController(JdbcClient jdbc, CurrentUserProvider currentUser, JsonMapper json, OpportunityQueries opportunities,
                   ActivityQueries activities, FitScorer fit) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.json = json;
        this.opportunities = opportunities;
        this.activities = activities;
        this.fit = fit;
    }

    @GetMapping("/opportunities")
    List<OpportunityQueries.Opportunity> opportunities(@RequestParam(required = false) Long accountId,
                                                       @RequestParam(required = false) Long ownerId,
                                                       @RequestParam(required = false) String type,
                                                       @RequestParam(required = false) String stage) {
        return opportunities.list(accountId, ownerId, type, stage);
    }

    @GetMapping("/opportunities/{id}")
    OpportunityQueries.Opportunity opportunity(@PathVariable long id) {
        return opportunities.get(id);
    }

    @GetMapping("/activities")
    List<ActivityQueries.Activity> activities(@RequestParam(required = false) Long accountId,
                                              @RequestParam(required = false) Long contactId) {
        return activities.list(accountId, contactId);
    }

    private static final String ACCOUNT_SELECT = """
        SELECT a.id, a.name, p.name AS parent, p.hq_country, a.city, a.website_domain, a.industry, a.headcount_band,
            a.year_established, a.tech_stack, a.relationship, a.owner_user_id, u.full_name AS owner, a.source, a.notes,
            (SELECT COUNT(DISTINCT d.engineer_id) FROM deployment d WHERE d.account_id = a.id AND d.status = 'ACTIVE')
                AS active_engineers,
            (SELECT MAX(r.finished_at) FROM account_research r WHERE r.account_id = a.id AND r.status = 'DONE')
                AS researched_at
        FROM account a
        JOIN parent_company p ON p.id = a.parent_company_id
        JOIN app_user u ON u.id = a.owner_user_id
        """;

    @GetMapping("/accounts")
    AccountPage accounts(@RequestParam(required = false) String q,
                         @RequestParam(required = false) String relationship,
                         @RequestParam(required = false) String city,
                         @RequestParam(required = false) Long ownerId,
                         @RequestParam(required = false) String sort,
                         @RequestParam(required = false) Boolean researched,
                         @RequestParam(defaultValue = "0") int page,
                         @RequestParam(defaultValue = "50") int size) {
        int limit = Math.clamp(size, 1, 1000);
        int offset = Math.max(page, 0) * limit;
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        Map<String, Object> params = new LinkedHashMap<>();
        if (q != null && !q.isBlank()) {
            where.append(" AND (a.name LIKE :q OR p.name LIKE :q OR a.website_domain LIKE :q)");
            params.put("q", "%" + q.trim() + "%");
        }
        if (relationship != null && !relationship.isBlank()) {
            where.append(" AND a.relationship = :rel");
            params.put("rel", relationship.toUpperCase());
        }
        if (city != null && !city.isBlank()) {
            where.append(" AND a.city = :city");
            params.put("city", city.trim());
        }
        if (researched != null) {
            where.append(researched ? " AND" : " AND NOT")
                .append(" EXISTS (SELECT 1 FROM account_research r WHERE r.account_id = a.id AND r.status = 'DONE')");
        }
        if (ownerId != null) {
            where.append(" AND a.owner_user_id = :owner");
            params.put("owner", ownerId);
        }
        long total = jdbc.sql("SELECT COUNT(*) FROM account a JOIN parent_company p ON p.id = a.parent_company_id" + where)
            .params(params).query(Long.class).single();
        if ("fit".equalsIgnoreCase(sort) || "fit_asc".equalsIgnoreCase(sort)) {
            // Score every matching account, then page: at most a few thousand accounts (NFR-BD-06).
            List<Account> all = jdbc.sql(ACCOUNT_SELECT + where).params(params).query((rs, n) -> account(rs)).list();
            Map<Long, FitScorer.Fit> fits = fit.score(all.stream().map(Account::id).toList());
            java.util.Comparator<Account> byScore = java.util.Comparator.comparing((Account a) -> a.fitScore() == null ? -1 : a.fitScore());
            List<Account> items = all.stream().map(a -> a.withFit(fits.get(a.id())))
                .sorted(("fit".equalsIgnoreCase(sort) ? byScore.reversed() : byScore).thenComparing(Account::name))
                .skip(offset).limit(limit).toList();
            return new AccountPage(items, Math.max(page, 0), limit, total);
        }
        List<Account> items = jdbc.sql(ACCOUNT_SELECT + where + " ORDER BY p.name, a.city LIMIT :limit OFFSET :offset")
            .params(params).param("limit", limit).param("offset", offset)
            .query((rs, n) -> account(rs)).list();
        Map<Long, FitScorer.Fit> fits = fit.score(items.stream().map(Account::id).toList());
        return new AccountPage(items.stream().map(a -> a.withFit(fits.get(a.id()))).toList(), Math.max(page, 0), limit, total);
    }

    @GetMapping("/accounts/{id}")
    AccountDetail account(@PathVariable long id) {
        Account plain = jdbc.sql(ACCOUNT_SELECT + " WHERE a.id = :id").param("id", id)
            .query((rs, n) -> account(rs)).optional().orElseThrow(() -> new NotFoundException("Account", id));
        Account account = plain.withFit(fit.score(List.of(id)).get(id));
        List<Deployment> deployments = deployments(id, null, null, null);
        BigDecimal monthly = null;
        if (currentUser.get().canSeeRestricted()) {
            monthly = deployments.stream()
                .filter(d -> "ACTIVE".equals(d.status()) && d.billRateInr() != null && d.rateUnit() != null)
                .map(d -> monthlyEquivalent(d.billRateInr(), d.rateUnit(), d.allocationPercent()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        }
        return new AccountDetail(account, contacts(id, null), contracts(id, null), deployments, monthly);
    }

    @GetMapping("/contacts")
    List<Contact> contacts(@RequestParam(required = false) Long accountId,
                           @RequestParam(required = false) String persona) {
        return jdbc.sql("""
                SELECT c.id, c.account_id, a.name, c.full_name, c.title, c.persona, c.linkedin_url, c.email, c.phone,
                    c.city, c.source, c.consent_basis, c.opted_out
                FROM contact c JOIN account a ON a.id = c.account_id
                WHERE (:account IS NULL OR c.account_id = :account) AND (:persona IS NULL OR c.persona = :persona)
                ORDER BY a.name, c.full_name LIMIT 1000
                """)
            .param("account", accountId).param("persona", persona == null ? null : persona.toUpperCase())
            .query((rs, n) -> new Contact(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
                rs.getString(11), rs.getString(12), rs.getBoolean(13)))
            .list();
    }

    @GetMapping("/contracts")
    List<Contract> contracts(@RequestParam(required = false) Long accountId,
                             @RequestParam(required = false) String status) {
        boolean restricted = currentUser.get().canSeeRestricted();
        return jdbc.sql("""
                SELECT c.id, c.contract_ref, c.account_id, a.name, c.contract_type, m.contract_ref, c.work_type,
                    c.pricing_model, c.total_value_inr, c.start_date, c.end_date, c.renewal_date, c.status,
                    c.client_permission_to_name
                FROM contract c JOIN account a ON a.id = c.account_id LEFT JOIN contract m ON m.id = c.parent_msa_id
                WHERE (:account IS NULL OR c.account_id = :account) AND (:status IS NULL OR c.status = :status)
                ORDER BY a.name, c.contract_type, c.start_date LIMIT 1000
                """)
            .param("account", accountId).param("status", status == null ? null : status.toUpperCase())
            .query((rs, n) -> new Contract(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                restricted ? rs.getBigDecimal(9) : null, date(rs, 10), date(rs, 11), date(rs, 12), rs.getString(13),
                rs.getBoolean(14)))
            .list();
    }

    @GetMapping("/engineers")
    List<Engineer> engineers(@RequestParam(required = false) String availability,
                             @RequestParam(required = false) String seniority,
                             @RequestParam(required = false) String skill) {
        CurrentUser user = currentUser.get();
        Map<Long, List<String>> skills = new LinkedHashMap<>();
        jdbc.sql("SELECT engineer_id, skill FROM engineer_skill ORDER BY skill")
            .query((rs, n) -> skills.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getString(2)))
            .list();
        return jdbc.sql("""
                SELECT e.id, e.employee_code, e.full_name, e.work_email, e.primary_role, e.seniority, e.location,
                    e.availability, e.available_from, e.consent_to_share_profile,
                    COALESCE((SELECT SUM(d.allocation_percent) FROM deployment d
                        WHERE d.engineer_id = e.id AND d.status = 'ACTIVE' AND d.start_date <= CURRENT_DATE
                          AND (d.expected_end_date IS NULL OR d.expected_end_date >= CURRENT_DATE)), 0)
                FROM engineer e
                WHERE (:availability IS NULL OR e.availability = :availability)
                  AND (:seniority IS NULL OR e.seniority = :seniority)
                  AND (:skill IS NULL OR EXISTS (SELECT 1 FROM engineer_skill s WHERE s.engineer_id = e.id AND s.skill = :skill))
                ORDER BY e.full_name
                """)
            .param("availability", availability == null ? null : availability.toUpperCase())
            .param("seniority", seniority == null ? null : seniority.toUpperCase())
            .param("skill", skill)
            .query((rs, n) -> new Engineer(rs.getLong(1), rs.getString(2), rs.getString(3),
                user.canSeeRestricted() ? rs.getString(4) : null, rs.getString(5), rs.getString(6),
                skills.getOrDefault(rs.getLong(1), List.of()), rs.getString(7), rs.getString(8), date(rs, 9),
                rs.getBoolean(10), rs.getInt(11)))
            .list();
    }

    @GetMapping("/deployments")
    List<Deployment> deployments(@RequestParam(required = false) Long accountId,
                                 @RequestParam(required = false) Long engineerId,
                                 @RequestParam(required = false) String status,
                                 @RequestParam(required = false) Integer endingWithinDays) {
        boolean restricted = currentUser.get().canSeeRestricted();
        return jdbc.sql("""
                SELECT d.id, d.engineer_id, e.full_name, e.employee_code, e.seniority, d.account_id, a.name,
                    s.contract_ref, d.team_or_project, d.role, d.client_manager_name, d.allocation_percent,
                    d.bill_rate_inr, d.rate_unit, d.start_date, d.expected_end_date, d.status
                FROM deployment d
                JOIN engineer e ON e.id = d.engineer_id
                JOIN account a ON a.id = d.account_id
                LEFT JOIN contract s ON s.id = d.sow_id
                WHERE (:account IS NULL OR d.account_id = :account)
                  AND (:engineer IS NULL OR d.engineer_id = :engineer)
                  AND (:status IS NULL OR d.status = :status)
                  AND (:within IS NULL OR (d.status <> 'ENDED' AND d.expected_end_date IS NOT NULL
                       AND d.expected_end_date <= DATE_ADD(CURRENT_DATE, INTERVAL :within DAY)))
                ORDER BY d.expected_end_date IS NULL, d.expected_end_date, e.full_name
                """)
            .param("account", accountId).param("engineer", engineerId)
            .param("status", status == null ? null : status.toUpperCase()).param("within", endingWithinDays)
            .query((rs, n) -> new Deployment(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getLong(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
                rs.getString(11), rs.getInt(12), restricted ? rs.getBigDecimal(13) : null, rs.getString(14),
                date(rs, 15), date(rs, 16), rs.getString(17)))
            .list();
    }

    static BigDecimal monthlyEquivalent(BigDecimal rate, String unit, int allocationPercent) {
        BigDecimal monthly = switch (unit) {
            case "DAY" -> rate.multiply(DAYS_PER_MONTH);
            case "HOUR" -> rate.multiply(HOURS_PER_MONTH);
            default -> rate;
        };
        // A monthly rate is already the agreed amount for the allocation; day and hour rates scale with it.
        if (!"MONTH".equals(unit)) {
            monthly = monthly.multiply(BigDecimal.valueOf(allocationPercent)).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        }
        return monthly;
    }

    private Account account(ResultSet rs) throws SQLException {
        String tech = rs.getString("tech_stack");
        List<String> techStack = tech == null ? List.of() : List.of(json.readValue(tech, String[].class));
        return new Account(rs.getLong("id"), rs.getString("name"), rs.getString("parent"), rs.getString("hq_country"),
            rs.getString("city"), rs.getString("website_domain"), rs.getString("industry"),
            rs.getString("headcount_band"), (Integer) rs.getObject("year_established", Integer.class), techStack,
            rs.getString("relationship"), rs.getLong("owner_user_id"), rs.getString("owner"), rs.getString("source"),
            rs.getString("notes"), rs.getInt("active_engineers"),
            rs.getTimestamp("researched_at") == null ? null : rs.getTimestamp("researched_at").toInstant(), null, List.of());
    }

    private static LocalDate date(ResultSet rs, int column) throws SQLException {
        return rs.getObject(column, LocalDate.class);
    }
}
