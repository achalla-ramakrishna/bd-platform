-- Find GCCs runs (module: assist, ADR-0011). Candidates are kept so the user can add them later.

CREATE TABLE gcc_discovery (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    city         VARCHAR(80)  NOT NULL,
    industry     VARCHAR(120) NULL,
    status       VARCHAR(10)  NOT NULL,
    candidates   JSON         NULL,
    error        VARCHAR(500) NULL,
    model        VARCHAR(60)  NULL,
    requested_by BIGINT       NOT NULL,
    requested_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    finished_at  TIMESTAMP(3) NULL,
    CONSTRAINT pk_gcc_discovery PRIMARY KEY (id),
    CONSTRAINT fk_gcc_discovery_user FOREIGN KEY (requested_by) REFERENCES app_user (id),
    CONSTRAINT ck_gcc_discovery_status CHECK (status IN ('RUNNING','DONE','FAILED'))
);
CREATE INDEX ix_gcc_discovery_requested ON gcc_discovery (requested_at);
