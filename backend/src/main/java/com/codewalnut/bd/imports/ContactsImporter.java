package com.codewalnut.bd.imports;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.codewalnut.bd.accounts.AccountKey;
import com.codewalnut.bd.accounts.AccountRegistry;
import com.codewalnut.bd.contacts.ContactRegistry;
import com.codewalnut.bd.shared.AuditLog;

/** 2-contacts (FR-CON-01, FR-CON-02, FR-CON-06). LinkedIn URLs are copied by people (ADR-0005). */
@Component
class ContactsImporter implements EntityImporter {

    private final AccountRegistry accounts;
    private final ContactRegistry contacts;
    private final AuditLog audit;

    ContactsImporter(AccountRegistry accounts, ContactRegistry contacts, AuditLog audit) {
        this.accounts = accounts;
        this.contacts = contacts;
        this.audit = audit;
    }

    @Override
    public ImportEntity entity() {
        return ImportEntity.CONTACTS;
    }

    @Override
    public ImportPlan prepare(List<SpreadsheetReader.SheetRow> sheetRows, ImportErrors errors) {
        Map<AccountKey, Long> accountIds = accounts.allKeys();
        Map<String, Long> existing = contacts.allKeys();
        Set<String> personaCodes = contacts.personaCodes();
        Map<String, String> personas = personaCodes.stream()
            .collect(Collectors.toMap(c -> c.toLowerCase(Locale.ROOT), Function.identity()));
        Map<String, String> consent = ContactRegistry.CONSENT_BASES.stream()
            .collect(Collectors.toMap(c -> c.toLowerCase(Locale.ROOT), Function.identity()));
        Map<String, Integer> seen = new HashMap<>();
        List<ContactRegistry.ContactData> rows = new ArrayList<>();
        int creates = 0;
        int updates = 0;
        for (var sheetRow : sheetRows) {
            RowReader r = new RowReader(sheetRow, errors);
            Long accountId = ImportSupport.account(r, accountIds);
            String fullName = r.text("full_name", true, 200);
            String title = r.text("title", false, 200);
            String persona = r.choice("persona", true, personas);
            String linkedin = r.text("linkedin_url", false, 400);
            if (linkedin != null && !linkedin.matches("^https?://([a-z]+\\.)?linkedin\\.com/.+")) {
                r.error("linkedin_url", "Paste the profile address, starting https://www.linkedin.com/");
            }
            String email = r.email("email", false);
            String phone = r.text("phone", false, 40);
            String city = r.text("contact_city", false, 80);
            String source = r.text("source", true, 200);
            String consentBasis = r.choice("consent_basis", true, consent);
            String notes = r.text("notes", false, 4000);
            if (accountId != null && fullName != null) {
                String key = ContactRegistry.key(accountId, fullName);
                if (seen.containsKey(key)) {
                    r.error("full_name", "Same person at the same account as row " + seen.get(key) + ".");
                } else {
                    seen.put(key, r.rowNumber());
                }
                if (r.ok()) {
                    if (existing.containsKey(key)) {
                        updates++;
                    } else {
                        creates++;
                    }
                    rows.add(new ContactRegistry.ContactData(accountId, fullName, title, persona, linkedin, email,
                        phone, city, source, consentBasis, notes));
                }
            }
        }
        return new ImportPlan(creates, updates, userId -> {
            for (var row : rows) {
                var result = contacts.upsert(row);
                audit.record("contact", result.id(), AuditLog.Action.IMPORT, Map.of("created", result.created()), userId);
            }
        });
    }
}
