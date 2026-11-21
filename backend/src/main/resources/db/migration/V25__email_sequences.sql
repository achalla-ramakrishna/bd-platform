-- Email sequences (ADR-0025, module: prospecting): templates the team keeps, a contact enrolled in one with the
-- emails the agent wrote and the owner approved, what was sent, and addresses that must never be emailed again.

CREATE TABLE email_sequence (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(120) NOT NULL,
    description VARCHAR(500) NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by  BIGINT       NULL,
    created_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    version     INT          NOT NULL DEFAULT 0,
    CONSTRAINT pk_email_sequence PRIMARY KEY (id),
    CONSTRAINT uq_email_sequence_name UNIQUE (name),
    CONSTRAINT fk_email_sequence_creator FOREIGN KEY (created_by) REFERENCES app_user (id)
);

CREATE TABLE email_sequence_step (
    sequence_id BIGINT        NOT NULL,
    position    INT           NOT NULL,
    wait_days   INT           NOT NULL,
    subject     VARCHAR(200)  NOT NULL,
    body        VARCHAR(2000) NOT NULL,
    CONSTRAINT pk_email_sequence_step PRIMARY KEY (sequence_id, position),
    CONSTRAINT fk_step_sequence FOREIGN KEY (sequence_id) REFERENCES email_sequence (id) ON DELETE CASCADE,
    CONSTRAINT ck_step_position CHECK (position BETWEEN 1 AND 4),
    CONSTRAINT ck_step_wait CHECK (wait_days BETWEEN 0 AND 60)
);

CREATE TABLE sequence_enrolment (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    sequence_id       BIGINT       NOT NULL,
    contact_id        BIGINT       NOT NULL,
    owner_user_id     BIGINT       NOT NULL,
    conversation_id   BIGINT       NULL,
    to_email          VARCHAR(254) NOT NULL,
    consent_basis     VARCHAR(30)  NOT NULL,
    status            VARCHAR(10)  NOT NULL DEFAULT 'DRAFTING',
    next_position     INT          NOT NULL DEFAULT 1,
    next_send_on      DATE         NULL,
    gmail_thread_id   VARCHAR(100) NULL,
    unsubscribe_token VARCHAR(64)  NOT NULL,
    waiting_reason    VARCHAR(300) NULL,
    ended_reason      VARCHAR(300) NULL,
    error             VARCHAR(500) NULL,
    created_at        TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    approved_at       TIMESTAMP(3) NULL,
    ended_at          TIMESTAMP(3) NULL,
    version           INT          NOT NULL DEFAULT 0,
    CONSTRAINT pk_sequence_enrolment PRIMARY KEY (id),
    CONSTRAINT fk_enrolment_sequence FOREIGN KEY (sequence_id) REFERENCES email_sequence (id),
    CONSTRAINT fk_enrolment_contact FOREIGN KEY (contact_id) REFERENCES contact (id),
    CONSTRAINT fk_enrolment_owner FOREIGN KEY (owner_user_id) REFERENCES app_user (id),
    CONSTRAINT fk_enrolment_conversation FOREIGN KEY (conversation_id) REFERENCES conversation (id) ON DELETE SET NULL,
    CONSTRAINT uq_enrolment_token UNIQUE (unsubscribe_token),
    CONSTRAINT ck_enrolment_status CHECK (status IN ('DRAFTING','REVIEW','FAILED','ACTIVE','PAUSED','REPLIED','STOPPED','FINISHED'))
);
CREATE INDEX ix_enrolment_due ON sequence_enrolment (status, next_send_on);
CREATE INDEX ix_enrolment_contact ON sequence_enrolment (contact_id, status);

CREATE TABLE sequence_email (
    enrolment_id     BIGINT        NOT NULL,
    position         INT           NOT NULL,
    wait_days        INT           NOT NULL,
    agent_subject    VARCHAR(200)  NOT NULL,
    agent_body       VARCHAR(2000) NOT NULL,
    subject          VARCHAR(200)  NOT NULL,
    body             VARCHAR(2000) NOT NULL,
    status           VARCHAR(10)   NOT NULL DEFAULT 'PLANNED',
    sent_at          TIMESTAMP(3)  NULL,
    gmail_message_id VARCHAR(100)  NULL,
    rfc_message_id   VARCHAR(300)  NULL,
    CONSTRAINT pk_sequence_email PRIMARY KEY (enrolment_id, position),
    CONSTRAINT fk_email_enrolment FOREIGN KEY (enrolment_id) REFERENCES sequence_enrolment (id) ON DELETE CASCADE,
    CONSTRAINT ck_email_status CHECK (status IN ('PLANNED','SENT','SKIPPED'))
);

CREATE TABLE email_suppression (
    email      VARCHAR(254) NOT NULL,
    reason     VARCHAR(40)  NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_email_suppression PRIMARY KEY (email)
);

INSERT INTO email_sequence (name, description) VALUES
    ('Introduction, three emails', 'A short introduction, a follow-up with one useful point, and a polite close.');
INSERT INTO email_sequence_step (sequence_id, position, wait_days, subject, body)
SELECT id, 1, 0, 'A quick idea for {company}',
       'Introduce yourself in one line. Name one specific thing from their brief (why now) and the CodeWalnut offer that fits it. Ask for a 20-minute call.'
FROM email_sequence WHERE name = 'Introduction, three emails';
INSERT INTO email_sequence_step (sequence_id, position, wait_days, subject, body)
SELECT id, 2, 4, 'Re: A quick idea for {company}',
       'A short follow-up. Add one concrete, relevant point (how we would help with what they are doing), different from the first email. Ask again, lightly.'
FROM email_sequence WHERE name = 'Introduction, three emails';
INSERT INTO email_sequence_step (sequence_id, position, wait_days, subject, body)
SELECT id, 3, 7, 'Re: A quick idea for {company}',
       'A polite close: say you will not keep emailing, and that they can reply any time if it becomes relevant.'
FROM email_sequence WHERE name = 'Introduction, three emails';
