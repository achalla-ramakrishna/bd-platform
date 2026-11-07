package com.codewalnut.bd.imports;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.codewalnut.bd.shared.AuditLog;
import com.codewalnut.bd.talent.EngineerRegistry;

/** 4-engineers: everyone, deployed or on the bench (FR-EXI-03, FR-TAL-01, BR-BD-10). */
@Component
class EngineersImporter implements EntityImporter {

    static final Map<String, String> AVAILABILITY = Map.of(
        "deployed", "DEPLOYED", "bench", "BENCH", "notice", "NOTICE", "leave", "LEAVE");

    private final EngineerRegistry engineers;
    private final AuditLog audit;

    EngineersImporter(EngineerRegistry engineers, AuditLog audit) {
        this.engineers = engineers;
        this.audit = audit;
    }

    @Override
    public ImportEntity entity() {
        return ImportEntity.ENGINEERS;
    }

    @Override
    public ImportPlan prepare(List<SpreadsheetReader.SheetRow> sheetRows, ImportErrors errors) {
        Map<String, EngineerRegistry.EngineerRef> existing = engineers.allByCode();
        Map<String, String> emailOwner = existing.values().stream()
            .collect(Collectors.toMap(e -> e.workEmail().toLowerCase(Locale.ROOT), e -> e.employeeCode().toLowerCase(Locale.ROOT)));
        Map<String, String> seniorities = engineers.maxConcurrentClients().keySet().stream()
            .collect(Collectors.toMap(s -> s.toLowerCase(Locale.ROOT), Function.identity()));
        Map<String, Integer> seenCodes = new HashMap<>();
        Map<String, Integer> seenEmails = new HashMap<>();
        List<EngineerRegistry.EngineerData> rows = new ArrayList<>();
        int creates = 0;
        int updates = 0;
        for (var sheetRow : sheetRows) {
            RowReader r = new RowReader(sheetRow, errors);
            String code = r.text("employee_code", true, 40);
            String name = r.text("full_name", true, 200);
            String email = r.email("work_email", true);
            String role = r.text("primary_role", true, 120);
            String seniority = r.choice("seniority", true, seniorities);
            List<String> skills = r.list("skills", true);
            String location = r.text("location", false, 80);
            String availability = r.choice("availability", true, AVAILABILITY);
            LocalDate availableFrom = r.date("available_from", false);
            Boolean consent = r.yesNo("consent_to_share_profile", true);
            String notes = r.text("notes", false, 4000);

            if (("NOTICE".equals(availability) || "LEAVE".equals(availability)) && availableFrom == null
                && sheetRow.get("available_from") == null) {
                r.error("available_from", "Required when availability is notice or leave.");
            }
            String codeKey = code == null ? null : code.toLowerCase(Locale.ROOT);
            if (codeKey != null) {
                if (seenCodes.containsKey(codeKey)) {
                    r.error("employee_code", "Same employee code as row " + seenCodes.get(codeKey) + ".");
                } else {
                    seenCodes.put(codeKey, r.rowNumber());
                }
            }
            if (email != null) {
                if (seenEmails.containsKey(email)) {
                    r.error("work_email", "Same email as row " + seenEmails.get(email) + ".");
                } else {
                    seenEmails.put(email, r.rowNumber());
                }
                String owner = emailOwner.get(email);
                if (owner != null && codeKey != null && !owner.equals(codeKey)) {
                    r.error("work_email", "This email already belongs to another employee code.");
                }
            }
            if (r.ok()) {
                rows.add(new EngineerRegistry.EngineerData(code, name, email, role, seniority, skills, location,
                    availability, availableFrom, consent, notes));
                if (existing.containsKey(codeKey)) {
                    updates++;
                } else {
                    creates++;
                }
            }
        }
        return new ImportPlan(creates, updates, userId -> {
            for (var row : rows) {
                var result = engineers.upsert(row);
                audit.record("engineer", result.id(), AuditLog.Action.IMPORT, Map.of("created", result.created()), userId);
            }
        });
    }
}
