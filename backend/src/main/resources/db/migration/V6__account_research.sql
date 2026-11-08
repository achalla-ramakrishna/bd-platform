-- AI account research (module: assist, ADR-0010). Each run is kept; the newest is shown.

CREATE TABLE account_research (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    account_id   BIGINT       NOT NULL,
    status       VARCHAR(10)  NOT NULL,
    summary      JSON         NULL,
    sources      JSON         NULL,
    error        VARCHAR(500) NULL,
    model        VARCHAR(60)  NULL,
    requested_by BIGINT       NOT NULL,
    requested_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    finished_at  TIMESTAMP(3) NULL,
    CONSTRAINT pk_account_research PRIMARY KEY (id),
    CONSTRAINT fk_account_research_account FOREIGN KEY (account_id) REFERENCES account (id) ON DELETE CASCADE,
    CONSTRAINT fk_account_research_user FOREIGN KEY (requested_by) REFERENCES app_user (id),
    CONSTRAINT ck_account_research_status CHECK (status IN ('RUNNING','DONE','FAILED'))
);
CREATE INDEX ix_account_research_account ON account_research (account_id, requested_at);
