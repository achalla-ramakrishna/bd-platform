-- GTN phase 3 (ADR-0020): each team member's Google connection, Gmail threads, reply checks, calendar booking.

CREATE TABLE google_account (
    user_id           BIGINT        NOT NULL,
    email             VARCHAR(254)  NOT NULL,
    refresh_token_enc VARCHAR(1024) NOT NULL,
    access_token_enc  VARCHAR(4096) NULL,
    access_expires_at TIMESTAMP(3)  NULL,
    scopes            VARCHAR(1000) NOT NULL,
    connected_at      TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    last_error        VARCHAR(300)  NULL,
    CONSTRAINT pk_google_account PRIMARY KEY (user_id),
    CONSTRAINT fk_google_account_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE
);

-- Which Gmail message a touch is: messages sent from GTN, and replies found in their threads.
ALTER TABLE network_touch
    ADD COLUMN gmail_thread_id  VARCHAR(64)  NULL,
    ADD COLUMN gmail_message_id VARCHAR(64)  NULL,
    ADD COLUMN rfc_message_id   VARCHAR(300) NULL,
    ADD CONSTRAINT uq_network_touch_gmail UNIQUE (gmail_message_id);
CREATE INDEX ix_network_touch_thread ON network_touch (gmail_thread_id);

-- A reply found in Gmail waits for the owner to say what it meant; the booked call's time and event.
ALTER TABLE network_track
    ADD COLUMN reply_check       BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN call_at           TIMESTAMP(3) NULL,
    ADD COLUMN calendar_event_id VARCHAR(200) NULL;

-- How the profile page books calls.
ALTER TABLE profile_page
    ADD COLUMN booking_mode VARCHAR(10) NOT NULL DEFAULT 'LINK',
    ADD COLUMN slot_minutes INT         NOT NULL DEFAULT 20,
    ADD COLUMN day_start    TIME        NOT NULL DEFAULT '10:00:00',
    ADD COLUMN day_end      TIME        NOT NULL DEFAULT '18:00:00',
    ADD CONSTRAINT ck_profile_booking_mode CHECK (booking_mode IN ('LINK', 'CALENDAR')),
    ADD CONSTRAINT ck_profile_slot CHECK (slot_minutes IN (15, 20, 30, 45, 60));

ALTER TABLE profile_event DROP CHECK ck_profile_event_kind;
ALTER TABLE profile_event ADD CONSTRAINT ck_profile_event_kind
    CHECK (kind IN ('VISIT','VIDEO_PLAY','VIDEO_PROGRESS','CTA_CLICK','INTERESTED','BOOKED'));
