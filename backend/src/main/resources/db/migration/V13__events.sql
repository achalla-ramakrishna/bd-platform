-- Conferences and events (module: engagement, backlog item 6): a radar of webinars, roundtables,
-- meetups and conferences, our role in each, who goes, and the people we met there.

CREATE TABLE bd_event (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    name          VARCHAR(200)  NOT NULL,
    kind          VARCHAR(20)   NOT NULL,
    starts_on     DATE          NOT NULL,
    ends_on       DATE          NULL,
    city          VARCHAR(80)   NULL,
    online        BOOLEAN       NOT NULL DEFAULT FALSE,
    url           VARCHAR(500)  NULL,
    organiser     VARCHAR(200)  NULL,
    our_role      VARCHAR(20)   NOT NULL,
    notes         TEXT          NULL,
    outcome       TEXT          NULL,
    created_by    BIGINT        NOT NULL,
    created_at    TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    version       INT           NOT NULL DEFAULT 0,
    CONSTRAINT pk_bd_event PRIMARY KEY (id),
    CONSTRAINT fk_bd_event_creator FOREIGN KEY (created_by) REFERENCES app_user (id),
    CONSTRAINT ck_bd_event_kind CHECK (kind IN ('CONFERENCE','MEETUP','WEBINAR','ROUNDTABLE','OTHER')),
    CONSTRAINT ck_bd_event_role CHECK (our_role IN ('WATCHING','SIGNED_UP','ATTENDING','PRESENTING','STALL','ORGANISING')),
    CONSTRAINT ck_bd_event_dates CHECK (ends_on IS NULL OR ends_on >= starts_on)
);
CREATE INDEX ix_bd_event_starts ON bd_event (starts_on);

-- Who from the team goes.
CREATE TABLE bd_event_attendee (
    event_id BIGINT NOT NULL,
    user_id  BIGINT NOT NULL,
    CONSTRAINT pk_bd_event_attendee PRIMARY KEY (event_id, user_id),
    CONSTRAINT fk_bd_event_attendee_event FOREIGN KEY (event_id) REFERENCES bd_event (id) ON DELETE CASCADE,
    CONSTRAINT fk_bd_event_attendee_user FOREIGN KEY (user_id) REFERENCES app_user (id)
);

-- What came out of an event: conversations started there and connections first met there.
ALTER TABLE conversation ADD COLUMN event_id BIGINT NULL AFTER opportunity_id,
    ADD CONSTRAINT fk_conversation_event FOREIGN KEY (event_id) REFERENCES bd_event (id) ON DELETE SET NULL;
ALTER TABLE connection ADD COLUMN met_at_event_id BIGINT NULL AFTER contact_id,
    ADD CONSTRAINT fk_connection_event FOREIGN KEY (met_at_event_id) REFERENCES bd_event (id) ON DELETE SET NULL;
