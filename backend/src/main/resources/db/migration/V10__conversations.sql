-- Conversations (module: engagement, backlog item 3): a thread with one person at an account, its
-- status and next step. Each update is an activity linked to the conversation, so the activity log
-- and the fit score keep working as before.

CREATE TABLE conversation (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    account_id     BIGINT       NOT NULL,
    contact_id     BIGINT       NULL,
    person_name    VARCHAR(200) NULL,
    topic          VARCHAR(200) NOT NULL,
    owner_user_id  BIGINT       NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    next_step      VARCHAR(500) NULL,
    next_step_due  DATE         NULL,
    opportunity_id BIGINT       NULL,
    last_touch_on  DATE         NOT NULL,
    created_at     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    version        INT          NOT NULL DEFAULT 0,
    CONSTRAINT pk_conversation PRIMARY KEY (id),
    CONSTRAINT fk_conversation_account FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT fk_conversation_contact FOREIGN KEY (contact_id) REFERENCES contact (id),
    CONSTRAINT fk_conversation_owner FOREIGN KEY (owner_user_id) REFERENCES app_user (id),
    CONSTRAINT fk_conversation_opportunity FOREIGN KEY (opportunity_id) REFERENCES opportunity (id),
    CONSTRAINT ck_conversation_status CHECK (status IN ('STARTED','REPLIED','MEETING_BOOKED','OPPORTUNITY','GONE_QUIET','CLOSED')),
    CONSTRAINT ck_conversation_person CHECK (contact_id IS NOT NULL OR person_name IS NOT NULL)
);
CREATE INDEX ix_conversation_due ON conversation (status, next_step_due);
CREATE INDEX ix_conversation_account ON conversation (account_id);

ALTER TABLE activity
    ADD COLUMN conversation_id BIGINT NULL AFTER contact_id,
    ADD CONSTRAINT fk_activity_conversation FOREIGN KEY (conversation_id) REFERENCES conversation (id);
CREATE INDEX ix_activity_conversation ON activity (conversation_id, occurred_on);
