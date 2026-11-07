-- Activities, opportunities, requirements and submissions (modules: engagement,
-- opportunities, talent). Code for these arrives in the next slice.

CREATE TABLE activity (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    account_id  BIGINT       NOT NULL,
    contact_id  BIGINT       NULL,
    user_id     BIGINT       NOT NULL,
    channel     VARCHAR(30)  NOT NULL,
    occurred_on DATE         NOT NULL,
    summary     VARCHAR(2000) NOT NULL,
    outcome     VARCHAR(30)  NOT NULL,
    created_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_activity PRIMARY KEY (id),
    CONSTRAINT fk_activity_account FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT fk_activity_contact FOREIGN KEY (contact_id) REFERENCES contact (id),
    CONSTRAINT fk_activity_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT ck_activity_channel CHECK (channel IN ('LINKEDIN_CONNECTION','LINKEDIN_MESSAGE','INMAIL','EMAIL','CALL','MEETING','EVENT','REFERRAL')),
    CONSTRAINT ck_activity_outcome CHECK (outcome IN ('NO_REPLY','REPLIED','MEETING_BOOKED','NOT_INTERESTED','REFERRED_ON','DONE'))
);
CREATE INDEX ix_activity_account ON activity (account_id, occurred_on);

CREATE TABLE opportunity (
    id                         BIGINT        NOT NULL AUTO_INCREMENT,
    account_id                 BIGINT        NOT NULL,
    name                       VARCHAR(200)  NOT NULL,
    opportunity_type           VARCHAR(30)   NOT NULL,
    owner_user_id              BIGINT        NOT NULL,
    stage_code                 VARCHAR(30)   NOT NULL,
    value_inr                  DECIMAL(15,2) NULL,
    probability                INT           NOT NULL,
    expected_close_date        DATE          NULL,
    decision_maker_contact_id  BIGINT        NULL,
    need_summary               VARCHAR(2000) NULL,
    target_start_date          DATE          NULL,
    loss_reason_code           VARCHAR(60)   NULL,
    scope_summary              TEXT          NULL,
    pricing_model              VARCHAR(30)   NULL,
    estimate_inr               DECIMAL(15,2) NULL,
    team_shape                 VARCHAR(400)  NULL,
    timeline                   VARCHAR(400)  NULL,
    created_at                 TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at                 TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    version                    INT           NOT NULL DEFAULT 0,
    CONSTRAINT pk_opportunity PRIMARY KEY (id),
    CONSTRAINT fk_opportunity_account FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT fk_opportunity_owner FOREIGN KEY (owner_user_id) REFERENCES app_user (id),
    CONSTRAINT fk_opportunity_stage FOREIGN KEY (stage_code) REFERENCES opportunity_stage (code),
    CONSTRAINT fk_opportunity_dm FOREIGN KEY (decision_maker_contact_id) REFERENCES contact (id),
    CONSTRAINT ck_opportunity_type CHECK (opportunity_type IN ('STAFF_AUGMENTATION','PROJECT')),
    CONSTRAINT ck_opportunity_probability CHECK (probability BETWEEN 0 AND 100),
    CONSTRAINT ck_opportunity_pricing CHECK (pricing_model IS NULL OR pricing_model IN ('FIXED_PRICE','TIME_AND_MATERIALS'))
);
CREATE INDEX ix_opportunity_stage ON opportunity (stage_code);

CREATE TABLE opportunity_stage_history (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    opportunity_id BIGINT       NOT NULL,
    from_stage     VARCHAR(30)  NULL,
    to_stage       VARCHAR(30)  NOT NULL,
    changed_by     BIGINT       NOT NULL,
    changed_at     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_opportunity_stage_history PRIMARY KEY (id),
    CONSTRAINT fk_osh_opportunity FOREIGN KEY (opportunity_id) REFERENCES opportunity (id) ON DELETE CASCADE,
    CONSTRAINT fk_osh_user FOREIGN KEY (changed_by) REFERENCES app_user (id)
);

-- Staff augmentation requirement lines (FR-OPP-02).
CREATE TABLE opportunity_requirement (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    opportunity_id     BIGINT        NOT NULL,
    role               VARCHAR(120)  NOT NULL,
    skills             JSON          NOT NULL,
    seniority          VARCHAR(20)   NOT NULL,
    headcount          INT           NOT NULL,
    location_mode      VARCHAR(20)   NOT NULL,
    location           VARCHAR(80)   NULL,
    start_date         DATE          NOT NULL,
    duration_months    INT           NULL,
    allocation_percent INT           NOT NULL DEFAULT 100,
    rate_inr           DECIMAL(12,2) NULL,
    rate_unit          VARCHAR(10)   NULL,
    CONSTRAINT pk_opportunity_requirement PRIMARY KEY (id),
    CONSTRAINT fk_requirement_opportunity FOREIGN KEY (opportunity_id) REFERENCES opportunity (id) ON DELETE CASCADE,
    CONSTRAINT fk_requirement_seniority FOREIGN KEY (seniority) REFERENCES seniority_rule (seniority),
    CONSTRAINT ck_requirement_headcount CHECK (headcount >= 1),
    CONSTRAINT ck_requirement_location CHECK (location_mode IN ('ONSITE','HYBRID','REMOTE')),
    CONSTRAINT ck_requirement_allocation CHECK (allocation_percent BETWEEN 1 AND 100),
    CONSTRAINT ck_requirement_rate_unit CHECK (rate_unit IS NULL OR rate_unit IN ('MONTH','DAY','HOUR'))
);

-- Engineers proposed for a requirement (FR-TAL-03).
CREATE TABLE submission (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    requirement_id BIGINT       NOT NULL,
    engineer_id    BIGINT       NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    notes          VARCHAR(2000) NULL,
    created_at     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_submission PRIMARY KEY (id),
    CONSTRAINT uq_submission UNIQUE (requirement_id, engineer_id),
    CONSTRAINT fk_submission_requirement FOREIGN KEY (requirement_id) REFERENCES opportunity_requirement (id) ON DELETE CASCADE,
    CONSTRAINT fk_submission_engineer FOREIGN KEY (engineer_id) REFERENCES engineer (id),
    CONSTRAINT ck_submission_status CHECK (status IN ('PROPOSED','SHARED','INTERVIEW','SELECTED','REJECTED','STARTED'))
);

-- BR-BD-12: a won staff augmentation requirement becomes a request to Delivery.
CREATE TABLE deployment_request (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    requirement_id BIGINT       NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    created_at     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_deployment_request PRIMARY KEY (id),
    CONSTRAINT uq_deployment_request UNIQUE (requirement_id),
    CONSTRAINT fk_deployment_request_requirement FOREIGN KEY (requirement_id) REFERENCES opportunity_requirement (id),
    CONSTRAINT ck_deployment_request_status CHECK (status IN ('OPEN','FILLED','CANCELLED'))
);
