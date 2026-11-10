-- Connections (module: contacts, backlog item 4): people the team personally knows, who knows them and
-- how well, and the target account they can open a door to. Typed in by people; nothing is read from
-- LinkedIn (ADR-0005). Supersedes warm_path, which stays unused.

CREATE TABLE connection (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    full_name       VARCHAR(200) NOT NULL,
    current_company VARCHAR(200) NULL,
    current_role    VARCHAR(200) NULL,
    city            VARCHAR(80)  NULL,
    linkedin_url    VARCHAR(400) NULL,
    email           VARCHAR(254) NULL,
    account_id      BIGINT       NULL,
    account_link    VARCHAR(20)  NULL,
    contact_id      BIGINT       NULL,
    notes           TEXT         NULL,
    created_by      BIGINT       NOT NULL,
    created_at      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    version         INT          NOT NULL DEFAULT 0,
    CONSTRAINT pk_connection PRIMARY KEY (id),
    CONSTRAINT fk_connection_account FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT fk_connection_contact FOREIGN KEY (contact_id) REFERENCES contact (id),
    CONSTRAINT fk_connection_creator FOREIGN KEY (created_by) REFERENCES app_user (id),
    CONSTRAINT ck_connection_link CHECK (
        (account_id IS NULL AND account_link IS NULL)
        OR (account_id IS NOT NULL AND account_link IN ('WORKS_THERE','CAN_INTRODUCE')))
);
CREATE INDEX ix_connection_account ON connection (account_id);
CREATE INDEX ix_connection_name ON connection (full_name);

-- One row per team member who knows the person.
CREATE TABLE connection_knower (
    connection_id BIGINT       NOT NULL,
    user_id       BIGINT       NOT NULL,
    strength      VARCHAR(20)  NOT NULL,
    how_known     VARCHAR(400) NULL,
    connected_on  DATE         NOT NULL,
    created_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_connection_knower PRIMARY KEY (connection_id, user_id),
    CONSTRAINT fk_connection_knower_connection FOREIGN KEY (connection_id) REFERENCES connection (id) ON DELETE CASCADE,
    CONSTRAINT fk_connection_knower_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT ck_connection_knower_strength CHECK (strength IN ('CLOSE','KNOW_WELL','ACQUAINTANCE'))
);
CREATE INDEX ix_connection_knower_date ON connection_knower (connected_on);
