-- BD agent step 1 (ADR-0022): research briefs on people. Each run is kept; the newest is shown.
CREATE TABLE prospect_brief (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    person_type  VARCHAR(12)  NOT NULL,
    person_id    BIGINT       NOT NULL,
    account_id   BIGINT       NULL,
    status       VARCHAR(10)  NOT NULL,
    brief        JSON         NULL,
    sources      JSON         NULL,
    role_fit     INT          NULL,
    timing       INT          NULL,
    access       INT          NULL,
    score        INT          NULL,
    error        VARCHAR(500) NULL,
    model        VARCHAR(60)  NULL,
    requested_by BIGINT       NOT NULL,
    requested_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    finished_at  TIMESTAMP(3) NULL,
    CONSTRAINT pk_prospect_brief PRIMARY KEY (id),
    CONSTRAINT fk_prospect_brief_account FOREIGN KEY (account_id) REFERENCES account (id) ON DELETE SET NULL,
    CONSTRAINT fk_prospect_brief_user FOREIGN KEY (requested_by) REFERENCES app_user (id),
    CONSTRAINT ck_prospect_brief_type CHECK (person_type IN ('CONTACT', 'CONNECTION')),
    CONSTRAINT ck_prospect_brief_status CHECK (status IN ('RUNNING', 'DONE', 'FAILED'))
);
CREATE INDEX ix_prospect_brief_person ON prospect_brief (person_type, person_id, requested_at);
CREATE INDEX ix_prospect_brief_account ON prospect_brief (account_id, requested_at);
CREATE INDEX ix_prospect_brief_day ON prospect_brief (requested_at);
