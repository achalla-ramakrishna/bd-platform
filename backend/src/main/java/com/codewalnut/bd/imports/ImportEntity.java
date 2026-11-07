package com.codewalnut.bd.imports;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** The five upload templates, in load order (docs/data-import/README.md). */
enum ImportEntity {

    ACCOUNTS(List.of("account_name", "parent_company", "parent_hq_country", "city", "website_domain", "industry",
        "headcount_band", "year_established", "tech_stack", "relationship", "owner_email", "source", "notes")),
    CONTACTS(List.of("account_website_domain", "account_city", "full_name", "title", "persona", "linkedin_url",
        "email", "phone", "contact_city", "source", "consent_basis", "notes")),
    CONTRACTS(List.of("contract_ref", "account_website_domain", "account_city", "contract_type", "parent_msa_ref",
        "work_type", "pricing_model", "total_value", "start_date", "end_date", "renewal_date", "status",
        "client_permission_to_name", "notes")),
    ENGINEERS(List.of("employee_code", "full_name", "work_email", "primary_role", "seniority", "skills", "location",
        "availability", "available_from", "consent_to_share_profile", "notes")),
    DEPLOYMENTS(List.of("employee_code", "account_website_domain", "account_city", "sow_ref", "team_or_project", "role",
        "client_manager_name", "allocation_percent", "bill_rate", "rate_unit", "start_date", "expected_end_date",
        "status", "notes"));

    private final List<String> columns;

    ImportEntity(List<String> columns) {
        this.columns = columns;
    }

    List<String> columns() {
        return columns;
    }

    String pathName() {
        return name().toLowerCase(Locale.ROOT);
    }

    static ImportEntity fromPath(String path) {
        return Arrays.stream(values()).filter(e -> e.pathName().equals(path)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown upload type: " + path));
    }
}
