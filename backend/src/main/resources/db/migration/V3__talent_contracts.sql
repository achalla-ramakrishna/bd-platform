-- Engineers, contracts and deployments (modules: talent, contracts). Amounts in INR.

CREATE TABLE engineer (
    id                       BIGINT       NOT NULL AUTO_INCREMENT,
    employee_code            VARCHAR(40)  NOT NULL,
    full_name                VARCHAR(200) NOT NULL,
    work_email               VARCHAR(254) NOT NULL,
    primary_role             VARCHAR(120) NOT NULL,
    seniority                VARCHAR(20)  NOT NULL,
    location                 VARCHAR(80)  NULL,
    availability             VARCHAR(20)  NOT NULL,
    available_from           DATE         NULL,
    consent_to_share_profile BOOLEAN      NOT NULL,
    notes                    TEXT         NULL,
    created_at               TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at               TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    version                  INT          NOT NULL DEFAULT 0,
    CONSTRAINT pk_engineer PRIMARY KEY (id),
    CONSTRAINT uq_engineer_code UNIQUE (employee_code),
    CONSTRAINT uq_engineer_email UNIQUE (work_email),
    CONSTRAINT fk_engineer_seniority FOREIGN KEY (seniority) REFERENCES seniority_rule (seniority),
    CONSTRAINT ck_engineer_availability CHECK (availability IN ('DEPLOYED','BENCH','NOTICE','LEAVE'))
);

CREATE TABLE engineer_skill (
    engineer_id BIGINT      NOT NULL,
    skill       VARCHAR(80) NOT NULL,
    CONSTRAINT pk_engineer_skill PRIMARY KEY (engineer_id, skill),
    CONSTRAINT fk_engineer_skill_engineer FOREIGN KEY (engineer_id) REFERENCES engineer (id) ON DELETE CASCADE
);
CREATE INDEX ix_engineer_skill_skill ON engineer_skill (skill);

CREATE TABLE contract (
    id                        BIGINT        NOT NULL AUTO_INCREMENT,
    contract_ref              VARCHAR(60)   NOT NULL,
    account_id                BIGINT        NOT NULL,
    contract_type             VARCHAR(10)   NOT NULL,
    parent_msa_id             BIGINT        NULL,
    work_type                 VARCHAR(30)   NULL,
    pricing_model             VARCHAR(30)   NULL,
    total_value_inr           DECIMAL(15,2) NULL,
    start_date                DATE          NOT NULL,
    end_date                  DATE          NULL,
    renewal_date              DATE          NULL,
    status                    VARCHAR(10)   NOT NULL,
    client_permission_to_name BOOLEAN       NOT NULL,
    notes                     TEXT          NULL,
    created_at                TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at                TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    version                   INT           NOT NULL DEFAULT 0,
    CONSTRAINT pk_contract PRIMARY KEY (id),
    CONSTRAINT uq_contract_ref UNIQUE (contract_ref),
    CONSTRAINT fk_contract_account FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT fk_contract_parent FOREIGN KEY (parent_msa_id) REFERENCES contract (id),
    CONSTRAINT ck_contract_type CHECK (contract_type IN ('MSA','SOW')),
    CONSTRAINT ck_contract_work_type CHECK (work_type IS NULL OR work_type IN ('STAFF_AUGMENTATION','PROJECT')),
    CONSTRAINT ck_contract_pricing CHECK (pricing_model IS NULL OR pricing_model IN ('MONTHLY_RATE','TIME_AND_MATERIALS','FIXED_PRICE')),
    CONSTRAINT ck_contract_status CHECK (status IN ('DRAFT','ACTIVE','ENDED')),
    CONSTRAINT ck_contract_dates CHECK (end_date IS NULL OR end_date >= start_date)
);
CREATE INDEX ix_contract_end ON contract (end_date);

CREATE TABLE deployment (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    engineer_id         BIGINT        NOT NULL,
    account_id          BIGINT        NOT NULL,
    sow_id              BIGINT        NULL,
    team_or_project     VARCHAR(200)  NULL,
    role                VARCHAR(120)  NOT NULL,
    client_manager_name VARCHAR(200)  NULL,
    allocation_percent  INT           NOT NULL DEFAULT 100,
    bill_rate_inr       DECIMAL(12,2) NULL,
    rate_unit           VARCHAR(10)   NULL,
    start_date          DATE          NOT NULL,
    expected_end_date   DATE          NULL,
    actual_end_date     DATE          NULL,
    status              VARCHAR(10)   NOT NULL,
    notes               TEXT          NULL,
    created_at          TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    version             INT           NOT NULL DEFAULT 0,
    CONSTRAINT pk_deployment PRIMARY KEY (id),
    -- Import key: one assignment per engineer, account and start date.
    CONSTRAINT uq_deployment_key UNIQUE (engineer_id, account_id, start_date),
    CONSTRAINT fk_deployment_engineer FOREIGN KEY (engineer_id) REFERENCES engineer (id),
    CONSTRAINT fk_deployment_account FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT fk_deployment_sow FOREIGN KEY (sow_id) REFERENCES contract (id),
    CONSTRAINT ck_deployment_allocation CHECK (allocation_percent BETWEEN 1 AND 100),
    CONSTRAINT ck_deployment_rate_unit CHECK (rate_unit IS NULL OR rate_unit IN ('MONTH','DAY','HOUR')),
    CONSTRAINT ck_deployment_status CHECK (status IN ('PLANNED','ACTIVE','ENDED')),
    CONSTRAINT ck_deployment_dates CHECK (expected_end_date IS NULL OR expected_end_date >= start_date)
);
CREATE INDEX ix_deployment_end ON deployment (expected_end_date);
