-- Walnut, the BD agent (ADR-0015): a chat thread per person, the morning briefs it writes, and each
-- person's email choice. Only the person a thread belongs to can read it.

CREATE TABLE walnut_message (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    user_id    BIGINT       NOT NULL,
    author     VARCHAR(10)  NOT NULL,
    kind       VARCHAR(10)  NOT NULL,
    body       MEDIUMTEXT   NOT NULL,
    actions    JSON         NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_walnut_message PRIMARY KEY (id),
    CONSTRAINT fk_walnut_message_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE,
    CONSTRAINT ck_walnut_message_author CHECK (author IN ('PERSON', 'WALNUT')),
    CONSTRAINT ck_walnut_message_kind CHECK (kind IN ('CHAT', 'BRIEF', 'REVIEW'))
);
CREATE INDEX ix_walnut_message_user ON walnut_message (user_id, id);

-- One brief per person per day, so a second scheduler run or a click on "Plan my day" never repeats it.
CREATE TABLE walnut_brief (
    user_id    BIGINT       NOT NULL,
    brief_on   DATE         NOT NULL,
    message_id BIGINT       NOT NULL,
    emailed_at TIMESTAMP(3) NULL,
    CONSTRAINT pk_walnut_brief PRIMARY KEY (user_id, brief_on),
    CONSTRAINT fk_walnut_brief_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_walnut_brief_message FOREIGN KEY (message_id) REFERENCES walnut_message (id) ON DELETE CASCADE
);

CREATE TABLE walnut_settings (
    user_id     BIGINT       NOT NULL,
    email_brief BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_walnut_settings PRIMARY KEY (user_id),
    CONSTRAINT fk_walnut_settings_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE
);
