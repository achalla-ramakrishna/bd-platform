package com.codewalnut.bd;

import org.springframework.jdbc.core.simple.JdbcClient;

/** Empties every table between integration tests, children first. */
public final class TestData {

    private static final String[] TABLES = {"call_prep", "email_suppression", "sequence_email", "sequence_enrolment", "prospect_reply", "prospect_queue_item", "prospect_brief", "walnut_brief", "walnut_message", "walnut_settings", "interview", "improvement", "google_account", "profile_event", "profile_link", "profile_video_chunk", "profile_page", "network_commitment", "network_stage_change", "network_touch", "network_track", "connection_knower", "connection_photo", "connection", "bd_event_attendee", "strategy_version", "gcc_discovery", "account_research", "deployment_request", "submission", "opportunity_requirement",
        "opportunity_stage_history", "activity", "conversation", "bd_event", "opportunity", "import_row_error", "import_batch", "audit_event",
        "deployment", "engineer_skill", "engineer", "contract", "warm_path", "contact", "account", "parent_company",
        "user_role", "app_user"};

    private TestData() {}

    public static void clear(JdbcClient jdbc) {
        jdbc.sql("UPDATE contract SET parent_msa_id = NULL").update();
        for (String table : TABLES) {
            if ("user_role".equals(table)) {
                // Sequences people added go; the seeded one (no creator) stays (ADR-0025).
                jdbc.sql("DELETE FROM email_sequence WHERE created_by IS NOT NULL").update();
            }
            jdbc.sql("DELETE FROM " + table).update();
        }
    }
}
