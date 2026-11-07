-- Users, roles, configuration and audit (module: admin). See docs/data-model.md.

CREATE TABLE app_user (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    email       VARCHAR(254) NOT NULL,
    full_name   VARCHAR(200) NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    last_login_at TIMESTAMP(3) NULL,
    CONSTRAINT pk_app_user PRIMARY KEY (id),
    CONSTRAINT uq_app_user_email UNIQUE (email)
);

CREATE TABLE user_role (
    user_id BIGINT      NOT NULL,
    role    VARCHAR(30) NOT NULL,
    CONSTRAINT pk_user_role PRIMARY KEY (user_id, role),
    CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE,
    CONSTRAINT ck_user_role CHECK (role IN ('LEADERSHIP','BD_LEAD','ACCOUNT_EXECUTIVE','DELIVERY','SOLUTIONS','ADMIN'))
);

-- Configurable lists (FR-ADM-02): personas, industries, loss reasons, cities, roles.
CREATE TABLE picklist_value (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    list_name  VARCHAR(40)  NOT NULL,
    code       VARCHAR(60)  NOT NULL,
    label      VARCHAR(120) NOT NULL,
    sort_order INT          NOT NULL DEFAULT 0,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT pk_picklist_value PRIMARY KEY (id),
    CONSTRAINT uq_picklist_value UNIQUE (list_name, code)
);

-- Opportunity stages (FR-OPP-04), configurable.
CREATE TABLE opportunity_stage (
    code                VARCHAR(30)  NOT NULL,
    label               VARCHAR(60)  NOT NULL,
    sort_order          INT          NOT NULL,
    default_probability INT          NOT NULL,
    outcome             VARCHAR(10)  NOT NULL DEFAULT 'OPEN',
    CONSTRAINT pk_opportunity_stage PRIMARY KEY (code),
    CONSTRAINT ck_opportunity_stage_outcome CHECK (outcome IN ('OPEN','WON','LOST')),
    CONSTRAINT ck_opportunity_stage_prob CHECK (default_probability BETWEEN 0 AND 100)
);

-- Engineer allocation limits by seniority (BR-BD-14), configurable.
CREATE TABLE seniority_rule (
    seniority              VARCHAR(20) NOT NULL,
    sort_order             INT         NOT NULL,
    max_concurrent_clients INT         NOT NULL,
    CONSTRAINT pk_seniority_rule PRIMARY KEY (seniority),
    CONSTRAINT ck_seniority_rule_max CHECK (max_concurrent_clients >= 1)
);

-- Who changed what and when (FR-ADM-03, NFR-BD-03).
CREATE TABLE audit_event (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    entity_type VARCHAR(40)  NOT NULL,
    entity_id   BIGINT       NOT NULL,
    action      VARCHAR(20)  NOT NULL,
    changes     JSON         NULL,
    user_id     BIGINT       NULL,
    occurred_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_audit_event PRIMARY KEY (id),
    CONSTRAINT fk_audit_event_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT ck_audit_event_action CHECK (action IN ('CREATE','UPDATE','DELETE','IMPORT'))
);
CREATE INDEX ix_audit_event_entity ON audit_event (entity_type, entity_id);

INSERT INTO opportunity_stage (code, label, sort_order, default_probability, outcome) VALUES
 ('LEAD','Lead',10,5,'OPEN'),
 ('QUALIFIED','Qualified',20,20,'OPEN'),
 ('DISCOVERY','Discovery',30,35,'OPEN'),
 ('PROPOSAL','Proposal',40,50,'OPEN'),
 ('NEGOTIATION','Negotiation',50,75,'OPEN'),
 ('WON','Won',60,100,'WON'),
 ('LOST','Lost',70,0,'LOST');

INSERT INTO seniority_rule (seniority, sort_order, max_concurrent_clients) VALUES
 ('JUNIOR',10,1), ('MID',20,1), ('SENIOR',30,2), ('LEAD',40,2), ('PRINCIPAL',50,2);

INSERT INTO picklist_value (list_name, code, label, sort_order) VALUES
 ('PERSONA','CENTRE_HEAD','Centre head',10),
 ('PERSONA','ENGINEERING_LEADER','Engineering leader',20),
 ('PERSONA','PRODUCT_LEADER','Product leader',30),
 ('PERSONA','TALENT_ACQUISITION','Talent acquisition',40),
 ('PERSONA','VENDOR_MANAGEMENT','Vendor management or procurement',50),
 ('PERSONA','OTHER','Other',90),
 ('LOSS_REASON','PRICE','Price',10),
 ('LOSS_REASON','NO_BUDGET','No budget',20),
 ('LOSS_REASON','LOST_TO_COMPETITOR','Lost to competitor',30),
 ('LOSS_REASON','HIRED_IN_HOUSE','Hired in-house',40),
 ('LOSS_REASON','NO_MATCHING_PROFILE','No matching profile in time',50),
 ('LOSS_REASON','REQUIREMENT_CANCELLED','Requirement cancelled',60),
 ('LOSS_REASON','NO_RESPONSE','No response',70),
 ('LOSS_REASON','OTHER','Other',90),
 ('HEADCOUNT_BAND','LT_500','<500',10),
 ('HEADCOUNT_BAND','500_2000','500-2000',20),
 ('HEADCOUNT_BAND','2000_5000','2000-5000',30),
 ('HEADCOUNT_BAND','GT_5000','5000+',40);
