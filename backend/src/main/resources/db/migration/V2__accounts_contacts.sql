-- Parent companies, accounts (centres) and contacts (modules: accounts, contacts).

CREATE TABLE parent_company (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    name              VARCHAR(200) NOT NULL,
    hq_country        VARCHAR(80)  NULL,
    created_at        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_parent_company PRIMARY KEY (id),
    CONSTRAINT uq_parent_company_name UNIQUE (name)
);

CREATE TABLE account (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    parent_company_id BIGINT       NOT NULL,
    name              VARCHAR(200) NOT NULL,
    city              VARCHAR(80)  NOT NULL,
    website_domain    VARCHAR(200) NOT NULL,
    industry          VARCHAR(120) NULL,
    headcount_band    VARCHAR(20)  NULL,
    year_established  SMALLINT     NULL,
    tech_stack        JSON         NULL,
    relationship      VARCHAR(20)  NOT NULL,
    owner_user_id     BIGINT       NOT NULL,
    source            VARCHAR(200) NOT NULL,
    notes             TEXT         NULL,
    created_at        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    version           INT          NOT NULL DEFAULT 0,
    CONSTRAINT pk_account PRIMARY KEY (id),
    -- BR-BD-01: one account per centre, keyed on domain and city.
    CONSTRAINT uq_account_domain_city UNIQUE (website_domain, city),
    CONSTRAINT fk_account_parent FOREIGN KEY (parent_company_id) REFERENCES parent_company (id),
    CONSTRAINT fk_account_owner FOREIGN KEY (owner_user_id) REFERENCES app_user (id),
    CONSTRAINT ck_account_relationship CHECK (relationship IN ('PROSPECT','CLIENT','PAST_CLIENT'))
);
CREATE INDEX ix_account_owner ON account (owner_user_id);
CREATE INDEX ix_account_relationship ON account (relationship);

CREATE TABLE contact (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    account_id    BIGINT       NOT NULL,
    full_name     VARCHAR(200) NOT NULL,
    title         VARCHAR(200) NULL,
    persona       VARCHAR(60)  NOT NULL,
    linkedin_url  VARCHAR(400) NULL,
    email         VARCHAR(254) NULL,
    phone         VARCHAR(40)  NULL,
    city          VARCHAR(80)  NULL,
    source        VARCHAR(200) NOT NULL,
    consent_basis VARCHAR(30)  NOT NULL,
    opted_out     BOOLEAN      NOT NULL DEFAULT FALSE,
    opted_out_at  TIMESTAMP(3) NULL,
    notes         TEXT         NULL,
    created_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    version       INT          NOT NULL DEFAULT 0,
    CONSTRAINT pk_contact PRIMARY KEY (id),
    CONSTRAINT fk_contact_account FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT uq_contact_account_name UNIQUE (account_id, full_name),
    CONSTRAINT ck_contact_consent CHECK (consent_basis IN ('EXISTING_RELATIONSHIP','BUSINESS_CONTACT','CONSENTED'))
);

-- FR-CON-04: how CodeWalnut can reach a contact.
CREATE TABLE warm_path (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    contact_id  BIGINT       NOT NULL,
    path_type   VARCHAR(30)  NOT NULL,
    description VARCHAR(400) NOT NULL,
    created_by  BIGINT       NOT NULL,
    created_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_warm_path PRIMARY KEY (id),
    CONSTRAINT fk_warm_path_contact FOREIGN KEY (contact_id) REFERENCES contact (id) ON DELETE CASCADE,
    CONSTRAINT fk_warm_path_user FOREIGN KEY (created_by) REFERENCES app_user (id),
    CONSTRAINT ck_warm_path_type CHECK (path_type IN ('FORMER_EMPLOYER','EMPLOYEE_CONNECTION','CLIENT_REFERRAL','ALUMNI','OTHER'))
);
