package com.codewalnut.bd.imports;

import java.time.Year;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.codewalnut.bd.accounts.AccountKey;
import com.codewalnut.bd.accounts.AccountRegistry;
import com.codewalnut.bd.admin.SecurityProperties;
import com.codewalnut.bd.admin.UserDirectory;
import com.codewalnut.bd.shared.AuditLog;

/** 1-accounts: one row per centre (FR-EXI-01, FR-ACC-03, BR-BD-01, BR-BD-02). */
@Component
class AccountsImporter implements EntityImporter {

    static final Map<String, String> RELATIONSHIPS = Map.of(
        "client", "CLIENT", "past_client", "PAST_CLIENT", "prospect", "PROSPECT");
    static final Set<String> HEADCOUNT_BANDS = Set.of("<500", "500-2000", "2000-5000", "5000+");

    private final AccountRegistry accounts;
    private final UserDirectory users;
    private final SecurityProperties security;
    private final AuditLog audit;

    AccountsImporter(AccountRegistry accounts, UserDirectory users, SecurityProperties security, AuditLog audit) {
        this.accounts = accounts;
        this.users = users;
        this.security = security;
        this.audit = audit;
    }

    @Override
    public ImportEntity entity() {
        return ImportEntity.ACCOUNTS;
    }

    private record Row(AccountKey key, String name, String parent, String hq, String city, String industry,
                       String band, Integer year, List<String> tech, String relationship, String ownerEmail,
                       String source, String notes) {}

    @Override
    public ImportPlan prepare(List<SpreadsheetReader.SheetRow> sheetRows, ImportErrors errors) {
        Map<AccountKey, Long> existing = accounts.allKeys();
        Map<AccountKey, Integer> seen = new HashMap<>();
        List<Row> rows = new ArrayList<>();
        int creates = 0;
        int updates = 0;
        for (var sheetRow : sheetRows) {
            RowReader r = new RowReader(sheetRow, errors);
            String name = r.text("account_name", true, 200);
            String parent = r.text("parent_company", true, 200);
            String hq = r.text("parent_hq_country", false, 80);
            String city = r.text("city", true, 80);
            String rawDomain = r.text("website_domain", true, 200);
            String industry = r.text("industry", false, 120);
            String band = r.text("headcount_band", false, 20);
            if (band != null && !HEADCOUNT_BANDS.contains(band.replace(" ", ""))) {
                r.error("headcount_band", "Use one of: <500, 500-2000, 2000-5000, 5000+.");
            }
            Integer year = r.integer("year_established", false, 1900, Year.now().getValue());
            List<String> tech = r.list("tech_stack", false);
            String relationship = r.choice("relationship", true, RELATIONSHIPS);
            String owner = r.email("owner_email", true);
            if (owner != null && !security.isAllowedEmail(owner)) {
                r.error("owner_email", "Must be a CodeWalnut address (@" + security.allowedDomain() + ").");
            }
            String source = r.text("source", true, 200);
            String notes = r.text("notes", false, 4000);

            AccountKey key = null;
            if (rawDomain != null && city != null) {
                key = new AccountKey(rawDomain, city);
                if (!AccountKey.isValidDomain(key.domain())) {
                    r.error("website_domain", "Use the domain only, for example example.com.");
                } else if (seen.containsKey(key)) {
                    r.error("website_domain", "Same domain and city as row " + seen.get(key) + " (BR-BD-01).");
                } else {
                    seen.put(key, r.rowNumber());
                }
            }
            if (r.ok()) {
                rows.add(new Row(key, name, parent, hq, city, industry, band == null ? null : band.replace(" ", ""),
                    year, tech, relationship, owner, source, notes));
                if (existing.containsKey(key)) {
                    updates++;
                } else {
                    creates++;
                }
            }
        }
        return new ImportPlan(creates, updates, userId -> {
            for (Row row : rows) {
                long ownerId = users.ensureUser(row.ownerEmail());
                var result = accounts.upsert(new AccountRegistry.AccountData(row.name(), row.parent(), row.hq(),
                    row.city(), row.key().domain(), row.industry(), row.band(), row.year(), row.tech(),
                    row.relationship(), ownerId, row.source(), row.notes()));
                audit.record("account", result.id(), AuditLog.Action.IMPORT,
                    Map.of("created", result.created()), userId);
            }
        });
    }
}
