-- The reply agent (ADR-0024, module: prospecting): each reply read, what the agent made of it and drafted, and
-- what the owner confirmed and sent. Connections gain an opt-out, set when someone asks not to be contacted.

ALTER TABLE connection
    ADD COLUMN opted_out    BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN opted_out_at TIMESTAMP(3) NULL;

CREATE TABLE prospect_reply (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    owner_user_id    BIGINT        NOT NULL,
    person_type      VARCHAR(12)   NOT NULL,
    person_id        BIGINT        NOT NULL,
    conversation_id  BIGINT        NULL,
    track_id         BIGINT        NULL,
    source           VARCHAR(10)   NOT NULL,
    channel          VARCHAR(20)   NOT NULL,
    reply_text       TEXT          NOT NULL,
    received_on      DATE          NOT NULL,
    gmail_message_id VARCHAR(100)  NULL,
    status           VARCHAR(10)   NOT NULL DEFAULT 'READING',
    category         VARCHAR(20)   NULL,
    confidence       VARCHAR(10)   NULL,
    summary          VARCHAR(500)  NULL,
    revisit_on       DATE          NULL,
    referral_name    VARCHAR(200)  NULL,
    referral_role    VARCHAR(200)  NULL,
    next_step        VARCHAR(300)  NULL,
    draft_answer     VARCHAR(2000) NULL,
    final_category   VARCHAR(20)   NULL,
    sent_answer      VARCHAR(2000) NULL,
    opted_out        BOOLEAN       NOT NULL DEFAULT FALSE,
    error            VARCHAR(500)  NULL,
    model            VARCHAR(60)   NULL,
    created_by       BIGINT        NULL,
    created_at       TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    acted_at         TIMESTAMP(3)  NULL,
    version          INT           NOT NULL DEFAULT 0,
    CONSTRAINT pk_prospect_reply PRIMARY KEY (id),
    CONSTRAINT fk_reply_owner FOREIGN KEY (owner_user_id) REFERENCES app_user (id),
    CONSTRAINT fk_reply_creator FOREIGN KEY (created_by) REFERENCES app_user (id),
    CONSTRAINT fk_reply_conversation FOREIGN KEY (conversation_id) REFERENCES conversation (id) ON DELETE SET NULL,
    CONSTRAINT fk_reply_track FOREIGN KEY (track_id) REFERENCES network_track (id) ON DELETE SET NULL,
    CONSTRAINT ck_reply_person CHECK (person_type IN ('CONTACT','CONNECTION')),
    CONSTRAINT ck_reply_source CHECK (source IN ('GMAIL','PASTED')),
    CONSTRAINT ck_reply_status CHECK (status IN ('READING','READY','FAILED','DONE','DISMISSED')),
    CONSTRAINT uq_reply_gmail UNIQUE (gmail_message_id)
);
CREATE INDEX ix_reply_owner ON prospect_reply (owner_user_id, status);
CREATE INDEX ix_reply_day ON prospect_reply (created_at);
