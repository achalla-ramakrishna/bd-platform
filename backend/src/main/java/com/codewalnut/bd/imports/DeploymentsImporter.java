package com.codewalnut.bd.imports;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.codewalnut.bd.accounts.AccountKey;
import com.codewalnut.bd.accounts.AccountRegistry;
import com.codewalnut.bd.contracts.AllocationRule;
import com.codewalnut.bd.contracts.ContractRegistry;
import com.codewalnut.bd.shared.AuditLog;
import com.codewalnut.bd.talent.EngineerRegistry;

/**
 * 5-deployments: engineers working at clients (FR-EXI-03, FR-EXI-04). Checks BR-BD-14 across the
 * file and what is already loaded: a row that replaces a loaded deployment (same engineer, account
 * and start date) is checked in place of it.
 */
@Component
class DeploymentsImporter implements EntityImporter {

    static final Map<String, String> RATE_UNITS = Map.of("month", "MONTH", "day", "DAY", "hour", "HOUR");
    static final Map<String, String> STATUSES = Map.of("planned", "PLANNED", "active", "ACTIVE", "ended", "ENDED");

    private final AccountRegistry accounts;
    private final EngineerRegistry engineers;
    private final ContractRegistry contracts;
    private final AuditLog audit;

    DeploymentsImporter(AccountRegistry accounts, EngineerRegistry engineers, ContractRegistry contracts,
                        AuditLog audit) {
        this.accounts = accounts;
        this.engineers = engineers;
        this.contracts = contracts;
        this.audit = audit;
    }

    @Override
    public ImportEntity entity() {
        return ImportEntity.DEPLOYMENTS;
    }

    private record Row(int rowNumber, EngineerRegistry.EngineerRef engineer, ContractRegistry.DeploymentData data) {}

    @Override
    public ImportPlan prepare(List<SpreadsheetReader.SheetRow> sheetRows, ImportErrors errors) {
        Map<AccountKey, Long> accountIds = accounts.allKeys();
        Map<String, EngineerRegistry.EngineerRef> engineerByCode = engineers.allByCode();
        Map<String, ContractRegistry.ContractRef> contractByRef = contracts.allByRef();
        Map<String, Long> existingKeys = contracts.deploymentKeys();
        Map<String, Integer> seenKeys = new HashMap<>();
        List<Row> rows = new ArrayList<>();
        for (var sheetRow : sheetRows) {
            RowReader r = new RowReader(sheetRow, errors);
            String code = r.text("employee_code", true, 40);
            EngineerRegistry.EngineerRef engineer = null;
            if (code != null) {
                engineer = engineerByCode.get(code.toLowerCase(Locale.ROOT));
                if (engineer == null) {
                    r.error("employee_code", "No engineer with this code. Upload them in 4-engineers first.");
                }
            }
            Long accountId = ImportSupport.account(r, accountIds);
            String sowRef = r.text("sow_ref", false, 60);
            String team = r.text("team_or_project", false, 200);
            String role = r.text("role", true, 120);
            String manager = r.text("client_manager_name", false, 200);
            Integer allocation = r.integer("allocation_percent", false, 1, 100);
            BigDecimal rate = r.money("bill_rate", false);
            String unit = r.choice("rate_unit", false, RATE_UNITS);
            LocalDate start = r.date("start_date", true);
            LocalDate end = r.date("expected_end_date", false);
            String status = r.choice("status", true, STATUSES);
            String notes = r.text("notes", false, 4000);

            if (rate != null && unit == null && sheetRow.get("rate_unit") == null) {
                r.error("rate_unit", "Required with a bill rate: month, day or hour.");
            }
            if (start != null && end != null && end.isBefore(start)) {
                r.error("expected_end_date", "Ends before it starts.");
            }
            Long sowId = null;
            if (sowRef != null) {
                var sow = contractByRef.get(sowRef.toLowerCase(Locale.ROOT));
                if (sow == null || !"SOW".equals(sow.contractType())) {
                    r.error("sow_ref", "No SOW with this reference. Upload it in 3-contracts first.");
                } else if (accountId != null && sow.accountId() != accountId) {
                    r.error("sow_ref", "This SOW belongs to a different account.");
                } else {
                    sowId = sow.id();
                    if (start != null && start.isBefore(sow.startDate())) {
                        r.error("start_date", "Starts before the SOW starts (" + sow.startDate() + ").");
                    }
                    if (end != null && sow.endDate() != null && end.isAfter(sow.endDate())) {
                        r.error("expected_end_date", "Ends after the SOW ends (" + sow.endDate() + ").");
                    }
                }
            }
            if (engineer != null && accountId != null && start != null) {
                String key = ContractRegistry.deploymentKey(engineer.id(), accountId, start);
                if (seenKeys.containsKey(key)) {
                    r.error("start_date", "Same engineer, account and start date as row " + seenKeys.get(key) + ".");
                } else {
                    seenKeys.put(key, r.rowNumber());
                }
            }
            if (r.ok()) {
                rows.add(new Row(r.rowNumber(), engineer, new ContractRegistry.DeploymentData(engineer.id(), accountId,
                    sowId, team, role, manager, allocation == null ? 100 : allocation, rate, unit, start, end, status,
                    notes)));
            }
        }

        checkAllocation(rows, errors);

        int creates = 0;
        int updates = 0;
        for (Row row : rows) {
            var d = row.data();
            if (existingKeys.containsKey(ContractRegistry.deploymentKey(d.engineerId(), d.accountId(), d.startDate()))) {
                updates++;
            } else {
                creates++;
            }
        }
        return new ImportPlan(creates, updates, userId -> {
            Set<Long> engineerIds = new HashSet<>();
            for (Row row : rows) {
                var result = contracts.upsertDeployment(row.data());
                engineerIds.add(row.data().engineerId());
                audit.record("deployment", result.id(), AuditLog.Action.IMPORT, Map.of("created", result.created()), userId);
            }
            engineers.markDeployed(contracts.engineersActiveToday(engineerIds));
        });
    }

    /** BR-BD-14, per engineer, over loaded deployments with this file's rows laid on top. */
    private void checkAllocation(List<Row> rows, ImportErrors errors) {
        Map<Long, List<Row>> byEngineer = new LinkedHashMap<>();
        rows.forEach(row -> byEngineer.computeIfAbsent(row.engineer().id(), k -> new ArrayList<>()).add(row));
        Map<String, ContractRegistry.DeploymentSpan> spans = contracts.openSpans(byEngineer.keySet());
        Map<String, Integer> maxClients = engineers.maxConcurrentClients();
        for (var entry : byEngineer.entrySet()) {
            long engineerId = entry.getKey();
            Map<String, ContractRegistry.DeploymentSpan> combined = new HashMap<>();
            spans.forEach((k, s) -> {
                if (s.engineerId() == engineerId) {
                    combined.put(k, s);
                }
            });
            for (Row row : entry.getValue()) {
                var d = row.data();
                String key = ContractRegistry.deploymentKey(d.engineerId(), d.accountId(), d.startDate());
                if ("ENDED".equals(d.status())) {
                    combined.remove(key);
                } else {
                    combined.put(key, new ContractRegistry.DeploymentSpan(d.engineerId(), d.accountId(), d.startDate(),
                        d.expectedEndDate(), d.allocationPercent()));
                }
            }
            var engineer = entry.getValue().getFirst().engineer();
            int max = maxClients.getOrDefault(engineer.seniority(), 1);
            AllocationRule.check(combined.values(), engineer.seniority(), max).ifPresent(message -> {
                int firstRow = entry.getValue().getFirst().rowNumber();
                errors.add(firstRow, "allocation_percent", null, engineer.employeeCode() + ": " + message);
            });
        }
    }
}
