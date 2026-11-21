-- The daily prospecting queue (ADR-0023, module: prospecting): the people the agent picked for one BD person on
-- one day, the note it drafted, and what the person did: sent (with the note they sent), skipped (and why), or
-- nothing (expired the next morning).

CREATE TABLE prospect_queue_item (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    user_id         BIGINT        NOT NULL,
    queued_on       DATE          NOT NULL,
    person_type     VARCHAR(12)   NOT NULL,
    person_id       BIGINT        NOT NULL,
    account_id      BIGINT        NULL,
    brief_id        BIGINT        NOT NULL,
    score           INT           NOT NULL,
    draft_note      VARCHAR(1000) NOT NULL,
    sent_note       VARCHAR(1000) NULL,
    status          VARCHAR(10)   NOT NULL DEFAULT 'QUEUED',
    skip_reason     VARCHAR(20)   NULL,
    skip_note       VARCHAR(500)  NULL,
    conversation_id BIGINT        NULL,
    track_id        BIGINT        NULL,
    acted_at        TIMESTAMP(3)  NULL,
    created_at      TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    version         INT           NOT NULL DEFAULT 0,
    CONSTRAINT pk_prospect_queue_item PRIMARY KEY (id),
    CONSTRAINT fk_queue_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT fk_queue_account FOREIGN KEY (account_id) REFERENCES account (id) ON DELETE SET NULL,
    CONSTRAINT fk_queue_brief FOREIGN KEY (brief_id) REFERENCES prospect_brief (id),
    CONSTRAINT ck_queue_person CHECK (person_type IN ('CONTACT','CONNECTION')),
    CONSTRAINT ck_queue_status CHECK (status IN ('QUEUED','SENT','SKIPPED','EXPIRED')),
    CONSTRAINT ck_queue_skip CHECK (skip_reason IS NULL OR skip_reason IN ('WRONG_PERSON','NOT_NOW','IN_TOUCH','OTHER')),
    CONSTRAINT uq_queue_day UNIQUE (user_id, queued_on, person_type, person_id)
);
CREATE INDEX ix_queue_person ON prospect_queue_item (person_type, person_id, status);
CREATE INDEX ix_queue_user_day ON prospect_queue_item (user_id, queued_on);
