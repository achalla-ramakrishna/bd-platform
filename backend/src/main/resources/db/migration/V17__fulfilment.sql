-- Realising an opportunity (ADR-0016): when each requirement came in, and the candidates proposed against it,
-- from our bench or from outside, through Proposed, Profile shared, Client interview, Selected, Onboarding and
-- Started (or Rejected or Withdrawn).

ALTER TABLE opportunity_requirement
    ADD COLUMN received_on DATE          NULL,
    ADD COLUMN notes       VARCHAR(1000) NULL,
    ADD COLUMN created_at  TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3);

ALTER TABLE submission DROP CHECK ck_submission_status;

ALTER TABLE submission
    MODIFY COLUMN engineer_id BIGINT NULL,
    ADD COLUMN candidate_name   VARCHAR(200) NULL,
    ADD COLUMN candidate_source VARCHAR(200) NULL,
    ADD COLUMN status_on        DATE         NULL,
    ADD COLUMN interview_on     DATE         NULL,
    ADD COLUMN start_on         DATE         NULL,
    ADD COLUMN proposed_by      BIGINT       NULL,
    ADD COLUMN version          INT          NOT NULL DEFAULT 0,
    ADD CONSTRAINT fk_submission_proposed_by FOREIGN KEY (proposed_by) REFERENCES app_user (id),
    ADD CONSTRAINT ck_submission_status
        CHECK (status IN ('PROPOSED', 'SHARED', 'INTERVIEW', 'SELECTED', 'ONBOARDING', 'STARTED', 'REJECTED', 'WITHDRAWN')),
    ADD CONSTRAINT ck_submission_candidate CHECK (engineer_id IS NOT NULL OR candidate_name IS NOT NULL);
