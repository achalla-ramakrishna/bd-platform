-- GTN, Go To Network (ADR-0017, ADR-0018): a pipeline for the people the team already knows.

-- M1: how to reach them on WhatsApp, and where they came from (e.g. "college alumni").
ALTER TABLE connection
    ADD COLUMN whatsapp   VARCHAR(20)  NULL AFTER email,
    ADD COLUMN source_tag VARCHAR(100) NULL AFTER whatsapp;
CREATE INDEX ix_connection_linkedin ON connection (linkedin_url(191));
CREATE INDEX ix_connection_email ON connection (email);
CREATE INDEX ix_connection_whatsapp ON connection (whatsapp);
CREATE INDEX ix_connection_source ON connection (source_tag);

-- Message templates per stage and Why? reason, with merge fields (ADR-0018).
CREATE TABLE message_template (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    name        VARCHAR(120)  NOT NULL,
    stage       VARCHAR(30)   NOT NULL,
    reason      VARCHAR(30)   NULL,
    channel     VARCHAR(20)   NOT NULL,
    subject     VARCHAR(200)  NULL,
    body        VARCHAR(2000) NOT NULL,
    active      BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    version     INT           NOT NULL DEFAULT 0,
    CONSTRAINT pk_message_template PRIMARY KEY (id),
    CONSTRAINT ck_template_stage CHECK (stage IN ('INITIATE','RESPONDED','UNRESPONSIVE','INTRO_MADE','CALL_SCHEDULED',
        'WARM_COMMITMENT','MONTHLY_BEEP')),
    CONSTRAINT ck_template_reason CHECK (reason IS NULL OR reason IN ('NOT_SEEN','INERTIA','NOT_RIGHT_TIME')),
    CONSTRAINT ck_template_channel CHECK (channel IN ('ANY','LINKEDIN','EMAIL','WHATSAPP'))
);

-- One GTN track per person (ADR-0017).
CREATE TABLE network_track (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    connection_id      BIGINT        NOT NULL,
    owner_user_id      BIGINT        NOT NULL,
    stage              VARCHAR(30)   NOT NULL,
    stage_since        TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    why_reason         VARCHAR(30)   NULL,
    next_action        VARCHAR(200)  NOT NULL,
    next_action_on     DATE          NOT NULL,
    last_out_on        DATE          NULL,
    last_in_on         DATE          NULL,
    last_channel       VARCHAR(20)   NULL,
    nudges_sent        INT           NOT NULL DEFAULT 0,
    revisit_reason     VARCHAR(300)  NULL,
    beep_interval_days INT           NOT NULL,
    call_on            DATE          NULL,
    created_at         TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at         TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    version            INT           NOT NULL DEFAULT 0,
    CONSTRAINT pk_network_track PRIMARY KEY (id),
    CONSTRAINT uq_network_track_connection UNIQUE (connection_id),
    CONSTRAINT fk_network_track_connection FOREIGN KEY (connection_id) REFERENCES connection (id) ON DELETE CASCADE,
    CONSTRAINT fk_network_track_owner FOREIGN KEY (owner_user_id) REFERENCES app_user (id),
    CONSTRAINT ck_network_track_stage CHECK (stage IN ('INITIATE','RESPONDED','UNRESPONSIVE','INTRO_MADE','CALL_SCHEDULED',
        'WARM_COMMITMENT','MONTHLY_BEEP')),
    CONSTRAINT ck_network_track_reason CHECK (why_reason IS NULL OR why_reason IN ('NOT_SEEN','INERTIA','NOT_RIGHT_TIME')),
    CONSTRAINT ck_network_track_beep CHECK (beep_interval_days BETWEEN 7 AND 180)
);
CREATE INDEX ix_network_track_due ON network_track (owner_user_id, next_action_on);
CREATE INDEX ix_network_track_stage ON network_track (stage);

-- Every stage move: who, when, from, to, why. changed_by is NULL for the daily sweep.
CREATE TABLE network_stage_change (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    track_id    BIGINT       NOT NULL,
    from_stage  VARCHAR(30)  NULL,
    to_stage    VARCHAR(30)  NOT NULL,
    changed_by  BIGINT       NULL,
    changed_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    note        VARCHAR(500) NULL,
    CONSTRAINT pk_network_stage_change PRIMARY KEY (id),
    CONSTRAINT fk_network_change_track FOREIGN KEY (track_id) REFERENCES network_track (id) ON DELETE CASCADE,
    CONSTRAINT fk_network_change_user FOREIGN KEY (changed_by) REFERENCES app_user (id)
);
CREATE INDEX ix_network_change_track ON network_stage_change (track_id, changed_at);

-- The contact's timeline (ADR-0018).
CREATE TABLE network_touch (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    track_id    BIGINT        NOT NULL,
    user_id     BIGINT        NOT NULL,
    channel     VARCHAR(20)   NOT NULL,
    direction   VARCHAR(3)    NOT NULL,
    kind        VARCHAR(20)   NOT NULL,
    template_id BIGINT        NULL,
    summary     VARCHAR(2000) NOT NULL,
    occurred_on DATE          NOT NULL,
    created_at  TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_network_touch PRIMARY KEY (id),
    CONSTRAINT fk_network_touch_track FOREIGN KEY (track_id) REFERENCES network_track (id) ON DELETE CASCADE,
    CONSTRAINT fk_network_touch_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT fk_network_touch_template FOREIGN KEY (template_id) REFERENCES message_template (id) ON DELETE SET NULL,
    CONSTRAINT ck_network_touch_channel CHECK (channel IN ('LINKEDIN','EMAIL','WHATSAPP','CALL','MEETING','OTHER')),
    CONSTRAINT ck_network_touch_direction CHECK (direction IN ('OUT','IN')),
    CONSTRAINT ck_network_touch_kind CHECK (kind IN ('OUTREACH','NUDGE','BEEP','REPLY','INTRO','CALL','NOTE'))
);
CREATE INDEX ix_network_touch_track ON network_touch (track_id, occurred_on);
CREATE INDEX ix_network_touch_day ON network_touch (occurred_on, user_id);

-- What a warm contact agreed to (FR-6.2).
CREATE TABLE network_commitment (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    track_id    BIGINT        NOT NULL,
    kind        VARCHAR(20)   NOT NULL,
    description VARCHAR(500)  NOT NULL,
    recorded_by BIGINT        NOT NULL,
    recorded_at TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_network_commitment PRIMARY KEY (id),
    CONSTRAINT fk_network_commitment_track FOREIGN KEY (track_id) REFERENCES network_track (id) ON DELETE CASCADE,
    CONSTRAINT fk_network_commitment_user FOREIGN KEY (recorded_by) REFERENCES app_user (id),
    CONSTRAINT ck_network_commitment_kind CHECK (kind IN ('REFERRAL','INTRO_ONWARD','PILOT_TALK','FOLLOW_UP','KEEP_POSTED'))
);

-- Starter templates (ADR-0018). Edit them in the app; the names are only labels.
INSERT INTO message_template (name, stage, reason, channel, subject, body) VALUES
('First message', 'INITIATE', NULL, 'ANY', 'Catching up',
 'Hi {first_name}, it has been a while since {how_we_know}. I am now building CodeWalnut, an AI-native engineering studio that helps product and GCC teams ship faster without losing quality. Would love to catch up and hear what you are working on. Open to a short chat?'),
('Not seen: gentle pointer', 'UNRESPONSIVE', 'NOT_SEEN', 'ANY', 'Sent you a note on LinkedIn',
 'Hi {first_name}, dropped you a note on LinkedIn last week. No rush at all, just wanted to make sure it did not get lost.'),
('Inertia: make it easy', 'UNRESPONSIVE', 'INERTIA', 'ANY', 'A two-minute look',
 'Hi {first_name}, I know inboxes get busy. Here is a short look at what we do: www.codewalnut.com. One direct ask: could we take 20 minutes next week to see if it fits anything at {company}?'),
('Not the right time: park it', 'UNRESPONSIVE', 'NOT_RIGHT_TIME', 'ANY', 'No rush',
 'Hi {first_name}, completely understand the timing. I will check back in a few weeks. Meanwhile, if anything changes, just reply here.'),
('Thanks for replying: the intro', 'RESPONDED', NULL, 'ANY', 'About CodeWalnut',
 'Thanks {first_name}! In short: CodeWalnut is an AI-native engineering studio. We work with product and GCC teams on React, Java and AI enablement, and we focus on release certainty. Happy to share more over a short call. Does a 20-minute slot this week or next work?'),
('Book the call', 'INTRO_MADE', NULL, 'ANY', 'A 20-minute call',
 'Hi {first_name}, shall we find 20 minutes? Pick any slot that suits you and I will send an invite.'),
('Call reminder', 'CALL_SCHEDULED', NULL, 'ANY', 'Looking forward to our chat',
 'Hi {first_name}, looking forward to our chat. Anything you would like me to come prepared with?'),
('Thanks for the commitment', 'WARM_COMMITMENT', NULL, 'ANY', 'Thank you',
 'Thanks {first_name}, really appreciate it. I will keep you posted on how it goes.'),
('Monthly beep: check-in', 'MONTHLY_BEEP', NULL, 'ANY', 'Quick hello',
 'Hi {first_name}, a quick hello from CodeWalnut. We have been busy with some interesting AI-native engineering work lately. How are things at {company}? {revisit_note}');
