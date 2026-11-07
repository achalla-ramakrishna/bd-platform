package com.codewalnut.bd.imports;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.codewalnut.bd.accounts.AccountKey;
import com.codewalnut.bd.accounts.AccountRegistry;
import com.codewalnut.bd.contracts.ContractRegistry;
import com.codewalnut.bd.shared.AuditLog;

/** 3-contracts: MSAs and SOWs (FR-EXI-02, FR-PRO-06, BR-BD-11). MSAs are saved before SOWs. */
@Component
class ContractsImporter implements EntityImporter {

    static final Map<String, String> TYPES = Map.of("msa", "MSA", "sow", "SOW");
    static final Map<String, String> WORK_TYPES = Map.of("staff_augmentation", "STAFF_AUGMENTATION", "project", "PROJECT");
    static final Map<String, String> PRICING = Map.of(
        "monthly_rate", "MONTHLY_RATE", "time_and_materials", "TIME_AND_MATERIALS", "fixed_price", "FIXED_PRICE");
    static final Map<String, String> STATUSES = Map.of("draft", "DRAFT", "active", "ACTIVE", "ended", "ENDED");

    private final AccountRegistry accounts;
    private final ContractRegistry contracts;
    private final AuditLog audit;

    ContractsImporter(AccountRegistry accounts, ContractRegistry contracts, AuditLog audit) {
        this.accounts = accounts;
        this.contracts = contracts;
        this.audit = audit;
    }

    @Override
    public ImportEntity entity() {
        return ImportEntity.CONTRACTS;
    }

    private record Row(String ref, long accountId, String type, String parentRef, String workType, String pricing,
                       BigDecimal value, LocalDate start, LocalDate end, LocalDate renewal, String status,
                       boolean permission, String notes) {}

    @Override
    public ImportPlan prepare(List<SpreadsheetReader.SheetRow> sheetRows, ImportErrors errors) {
        Map<AccountKey, Long> accountIds = accounts.allKeys();
        Map<String, ContractRegistry.ContractRef> existing = contracts.allByRef();
        // First pass: which refs in this file are MSAs, so a SOW may point at an MSA anywhere in the file.
        Map<String, Integer> fileRefs = new HashMap<>();
        Set<String> fileMsas = new HashSet<>();
        for (var sheetRow : sheetRows) {
            String ref = sheetRow.get("contract_ref");
            String type = sheetRow.get("contract_type");
            if (ref != null && "msa".equalsIgnoreCase(type)) {
                fileMsas.add(ref.toLowerCase(Locale.ROOT));
            }
        }
        List<Row> rows = new ArrayList<>();
        int creates = 0;
        int updates = 0;
        for (var sheetRow : sheetRows) {
            RowReader r = new RowReader(sheetRow, errors);
            String ref = r.text("contract_ref", true, 60);
            Long accountId = ImportSupport.account(r, accountIds);
            String type = r.choice("contract_type", true, TYPES);
            String parentRef = r.text("parent_msa_ref", false, 60);
            String workType = r.choice("work_type", false, WORK_TYPES);
            String pricing = r.choice("pricing_model", false, PRICING);
            BigDecimal value = r.money("total_value", false);
            LocalDate start = r.date("start_date", true);
            LocalDate end = r.date("end_date", false);
            LocalDate renewal = r.date("renewal_date", false);
            String status = r.choice("status", true, STATUSES);
            Boolean permission = r.yesNo("client_permission_to_name", true);
            String notes = r.text("notes", false, 4000);

            if (ref != null) {
                String key = ref.toLowerCase(Locale.ROOT);
                if (fileRefs.containsKey(key)) {
                    r.error("contract_ref", "Same reference as row " + fileRefs.get(key) + ".");
                } else {
                    fileRefs.put(key, r.rowNumber());
                }
                var before = existing.get(key);
                if (before != null && accountId != null && before.accountId() != accountId) {
                    r.error("contract_ref", "This reference already belongs to another account.");
                }
            }
            if ("SOW".equals(type)) {
                if (workType == null && sheetRow.get("work_type") == null) {
                    r.error("work_type", "Required for a SOW.");
                }
                if (pricing == null && sheetRow.get("pricing_model") == null) {
                    r.error("pricing_model", "Required for a SOW.");
                }
                if (end == null && sheetRow.get("end_date") == null) {
                    r.error("end_date", "Required for a SOW.");
                }
            }
            if ("MSA".equals(type) && parentRef != null) {
                r.error("parent_msa_ref", "Only a SOW can sit under an MSA.");
            }
            if (parentRef != null && "SOW".equals(type)) {
                String parentKey = parentRef.toLowerCase(Locale.ROOT);
                var dbParent = existing.get(parentKey);
                boolean ok = fileMsas.contains(parentKey) || (dbParent != null && "MSA".equals(dbParent.contractType()));
                if (!ok) {
                    r.error("parent_msa_ref", "No MSA with reference " + parentRef + " in this file or already loaded.");
                }
            }
            if (start != null && end != null && end.isBefore(start)) {
                r.error("end_date", "Ends before it starts.");
            }
            if (r.ok()) {
                rows.add(new Row(ref, accountId, type, parentRef, workType, pricing, value, start, end, renewal,
                    status, permission, notes));
                if (existing.containsKey(ref.toLowerCase(Locale.ROOT))) {
                    updates++;
                } else {
                    creates++;
                }
            }
        }
        return new ImportPlan(creates, updates, userId -> {
            Map<String, Long> ids = new HashMap<>();
            existing.forEach((k, v) -> ids.put(k, v.id()));
            List<Row> ordered = rows.stream().sorted(Comparator.comparing(row -> "MSA".equals(row.type()) ? 0 : 1)).toList();
            Set<Long> touchedAccounts = new HashSet<>();
            for (Row row : ordered) {
                Long parentId = row.parentRef() == null ? null : ids.get(row.parentRef().toLowerCase(Locale.ROOT));
                var result = contracts.upsertContract(new ContractRegistry.ContractData(row.ref(), row.accountId(),
                    row.type(), parentId, row.workType(), row.pricing(), row.value(), row.start(), row.end(),
                    row.renewal(), row.status(), row.permission(), row.notes()));
                ids.put(row.ref().toLowerCase(Locale.ROOT), result.id());
                touchedAccounts.add(row.accountId());
                audit.record("contract", result.id(), AuditLog.Action.IMPORT, Map.of("created", result.created()), userId);
            }
            accounts.markClients(contracts.accountsWithActiveContracts(touchedAccounts));
        });
    }
}
