-- GTN phase 2 (ADR-0019): profile pages, per-person links, visit tracking, ask-now.

CREATE TABLE profile_page (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    owner_user_id      BIGINT        NOT NULL,
    headline           VARCHAR(200)  NOT NULL,
    intro              VARCHAR(1500) NULL,
    proof_points       VARCHAR(1500) NULL,
    booking_url        VARCHAR(400)  NULL,
    whatsapp           VARCHAR(20)   NULL,
    interested_enabled BOOLEAN       NOT NULL DEFAULT TRUE,
    video_type         VARCHAR(40)   NULL,
    video_bytes        BIGINT        NULL,
    video_updated_at   TIMESTAMP(3)  NULL,
    captions           MEDIUMTEXT    NULL,
    created_at         TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at         TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    version            INT           NOT NULL DEFAULT 0,
    CONSTRAINT pk_profile_page PRIMARY KEY (id),
    CONSTRAINT uq_profile_page_owner UNIQUE (owner_user_id),
    CONSTRAINT fk_profile_page_owner FOREIGN KEY (owner_user_id) REFERENCES app_user (id)
);

-- The intro video in 1 MB pieces, so it fits MySQL's packet limit and byte ranges can be served.
CREATE TABLE profile_video_chunk (
    page_id BIGINT     NOT NULL,
    seq     INT        NOT NULL,
    data    MEDIUMBLOB NOT NULL,
    CONSTRAINT pk_profile_video_chunk PRIMARY KEY (page_id, seq),
    CONSTRAINT fk_profile_video_page FOREIGN KEY (page_id) REFERENCES profile_page (id) ON DELETE CASCADE
);

-- One unguessable link per person (/p/{token}).
CREATE TABLE profile_link (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    connection_id BIGINT       NOT NULL,
    owner_user_id BIGINT       NOT NULL,
    token         CHAR(22)     NOT NULL,
    created_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_profile_link PRIMARY KEY (id),
    CONSTRAINT uq_profile_link_connection UNIQUE (connection_id),
    CONSTRAINT uq_profile_link_token UNIQUE (token),
    CONSTRAINT fk_profile_link_connection FOREIGN KEY (connection_id) REFERENCES connection (id) ON DELETE CASCADE,
    CONSTRAINT fk_profile_link_owner FOREIGN KEY (owner_user_id) REFERENCES app_user (id)
);

-- What the page's script reported. No device details are kept.
CREATE TABLE profile_event (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    link_id     BIGINT       NOT NULL,
    kind        VARCHAR(20)  NOT NULL,
    value       VARCHAR(20)  NULL,
    occurred_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_profile_event PRIMARY KEY (id),
    CONSTRAINT fk_profile_event_link FOREIGN KEY (link_id) REFERENCES profile_link (id) ON DELETE CASCADE,
    CONSTRAINT ck_profile_event_kind CHECK (kind IN ('VISIT','VIDEO_PLAY','VIDEO_PROGRESS','CTA_CLICK','INTERESTED'))
);
CREATE INDEX ix_profile_event_link ON profile_event (link_id, occurred_at);
CREATE INDEX ix_profile_event_time ON profile_event (occurred_at);

-- What the page told us about a person on GTN.
ALTER TABLE network_track
    ADD COLUMN visited_at     TIMESTAMP(3) NULL,
    ADD COLUMN best_video_pct INT          NOT NULL DEFAULT 0,
    ADD COLUMN interested_at  TIMESTAMP(3) NULL,
    ADD COLUMN ask_now        BOOLEAN      NOT NULL DEFAULT FALSE;

ALTER TABLE network_touch DROP CHECK ck_network_touch_kind;
ALTER TABLE network_touch ADD CONSTRAINT ck_network_touch_kind
    CHECK (kind IN ('OUTREACH','NUDGE','BEEP','REPLY','INTRO','CALL','NOTE','VISIT'));

-- The direct ask now points to the person's own page.
UPDATE message_template SET body = REPLACE(body, 'www.codewalnut.com', '{profile_link}')
WHERE name = 'Inertia: make it easy';
