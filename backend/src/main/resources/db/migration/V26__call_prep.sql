-- The ops agent (ADR-0026, module: prospecting): a call-prep brief and a draft opportunity for a booked call on a
-- conversation or a GTN track, and the opportunity created from it.

CREATE TABLE call_prep (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    owner_user_id   BIGINT       NOT NULL,
    person_type     VARCHAR(12)  NOT NULL,
    person_id       BIGINT       NOT NULL,
    account_id      BIGINT       NULL,
    conversation_id BIGINT       NULL,
    track_id        BIGINT       NULL,
    call_on         DATE         NULL,
    status          VARCHAR(10)  NOT NULL DEFAULT 'RUNNING',
    brief           JSON         NULL,
    draft           JSON         NULL,
    opportunity_id  BIGINT       NULL,
    error           VARCHAR(500) NULL,
    model           VARCHAR(60)  NULL,
    requested_by    BIGINT       NULL,
    created_at      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    finished_at     TIMESTAMP(3) NULL,
    CONSTRAINT pk_call_prep PRIMARY KEY (id),
    CONSTRAINT fk_prep_owner FOREIGN KEY (owner_user_id) REFERENCES app_user (id),
    CONSTRAINT fk_prep_requester FOREIGN KEY (requested_by) REFERENCES app_user (id),
    CONSTRAINT fk_prep_account FOREIGN KEY (account_id) REFERENCES account (id) ON DELETE SET NULL,
    CONSTRAINT fk_prep_conversation FOREIGN KEY (conversation_id) REFERENCES conversation (id) ON DELETE SET NULL,
    CONSTRAINT fk_prep_track FOREIGN KEY (track_id) REFERENCES network_track (id) ON DELETE SET NULL,
    CONSTRAINT fk_prep_opportunity FOREIGN KEY (opportunity_id) REFERENCES opportunity (id) ON DELETE SET NULL,
    CONSTRAINT ck_prep_person CHECK (person_type IN ('CONTACT','CONNECTION')),
    CONSTRAINT ck_prep_status CHECK (status IN ('RUNNING','DONE','FAILED'))
);
CREATE INDEX ix_prep_conversation ON call_prep (conversation_id, id);
CREATE INDEX ix_prep_track ON call_prep (track_id, id);
CREATE INDEX ix_prep_owner ON call_prep (owner_user_id, call_on);
CREATE INDEX ix_prep_day ON call_prep (created_at);
