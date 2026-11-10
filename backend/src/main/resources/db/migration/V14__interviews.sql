-- Leader interviews (module: engagement, backlog item 8): GCC leaders we interview for the blog,
-- podcast or LinkedIn, from idea to published. A way to give value before asking for anything.

CREATE TABLE interview (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    leader_name    VARCHAR(200)  NOT NULL,
    leader_title   VARCHAR(200)  NULL,
    company        VARCHAR(200)  NULL,
    account_id     BIGINT        NULL,
    topic          VARCHAR(300)  NOT NULL,
    format         VARCHAR(20)   NOT NULL,
    status         VARCHAR(20)   NOT NULL,
    owner_user_id  BIGINT        NOT NULL,
    asked_on       DATE          NULL,
    scheduled_on   DATE          NULL,
    recorded_on    DATE          NULL,
    published_on   DATE          NULL,
    published_url  VARCHAR(500)  NULL,
    notes          TEXT          NULL,
    created_at     TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    version        INT           NOT NULL DEFAULT 0,
    CONSTRAINT pk_interview PRIMARY KEY (id),
    CONSTRAINT fk_interview_account FOREIGN KEY (account_id) REFERENCES account (id),
    CONSTRAINT fk_interview_owner FOREIGN KEY (owner_user_id) REFERENCES app_user (id),
    CONSTRAINT ck_interview_format CHECK (format IN ('BLOG','PODCAST','VIDEO','LINKEDIN_ARTICLE')),
    CONSTRAINT ck_interview_status CHECK (status IN ('IDEA','ASKED','AGREED','BOOKED','RECORDED','PUBLISHED','DECLINED'))
);
CREATE INDEX ix_interview_status ON interview (status, scheduled_on);
