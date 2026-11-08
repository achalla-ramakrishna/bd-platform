package com.codewalnut.bd;

import org.springframework.jdbc.core.simple.JdbcClient;

/** Empties every table between integration tests, children first. */
public final class TestData {

    private static final String[] TABLES = {"gcc_discovery", "account_research", "deployment_request", "submission", "opportunity_requirement",
        "opportunity_stage_history", "opportunity", "activity", "import_row_error", "import_batch", "audit_event",
        "deployment", "engineer_skill", "engineer", "contract", "warm_path", "contact", "account", "parent_company",
        "user_role", "app_user"};

    private TestData() {}

    public static void clear(JdbcClient jdbc) {
        jdbc.sql("UPDATE contract SET parent_msa_id = NULL").update();
        for (String table : TABLES) {
            jdbc.sql("DELETE FROM " + table).update();
        }
    }
}
