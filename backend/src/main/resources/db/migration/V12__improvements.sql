-- Improvement areas (module: improvements, backlog item 5): ideas for the platform, from suggested to
-- done. Approved items, in rank order, are the next work to build.

CREATE TABLE improvement (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    title         VARCHAR(200)  NOT NULL,
    details       TEXT          NULL,
    status        VARCHAR(20)   NOT NULL,
    rank_order    INT           NULL,
    suggested_by  BIGINT        NOT NULL,
    decided_by    BIGINT        NULL,
    decided_at    TIMESTAMP(3)  NULL,
    decision_note VARCHAR(1000) NULL,
    delivered_ref VARCHAR(500)  NULL,
    done_at       TIMESTAMP(3)  NULL,
    created_at    TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    version       INT           NOT NULL DEFAULT 0,
    CONSTRAINT pk_improvement PRIMARY KEY (id),
    CONSTRAINT fk_improvement_suggested_by FOREIGN KEY (suggested_by) REFERENCES app_user (id),
    CONSTRAINT fk_improvement_decided_by FOREIGN KEY (decided_by) REFERENCES app_user (id),
    CONSTRAINT ck_improvement_status CHECK (status IN ('SUGGESTED','APPROVED','IN_PROGRESS','DONE','DECLINED'))
);
CREATE INDEX ix_improvement_status ON improvement (status, rank_order);
