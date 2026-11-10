-- BD strategy page (module: strategy, backlog item 2). Every save is a new row; the newest is current.

CREATE TABLE strategy_version (
    id        BIGINT       NOT NULL AUTO_INCREMENT,
    body      MEDIUMTEXT   NOT NULL,
    note      VARCHAR(300) NULL,
    edited_by BIGINT       NULL,
    edited_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT pk_strategy_version PRIMARY KEY (id),
    CONSTRAINT fk_strategy_version_user FOREIGN KEY (edited_by) REFERENCES app_user (id)
);

-- Starting text from the 2026-11-10 BD working session. Edit it in the app; client names belong
-- there, not in this file (the repository holds no client data).
INSERT INTO strategy_version (body, note) VALUES ('# Our BD strategy

## Go to network, not go to market

1. Turn strangers into connections.
2. Give our network value: ideas, events, introductions, published interviews.
3. Stay in their minds with steady, useful activity.
4. Then ask whether they have an opportunity to give us a chance.

## Who we go after

- **GCCs** are the broad segment.
- Within them, centres that are **young** (formed in the last two years), **growing** and adding headcount.
- **Micro and mid-size** centres (about 100 to 2,000 people) first: they decide faster and welcome a smaller partner.
- Lead with industries where we already deliver:
  - **Travel and logistics**
  - **Banking and finance**, especially lending, loan origination and mortgages

## What we offer

- **React, Java and AI enablement only** for the next six months. Bulletproof delivery comes before breadth.
- Requests outside this stack go to partner firms, with a referral share where possible and no CodeWalnut guarantee.
- Exceptions (for example Angular for a close client who can wait) are decided case by case.

## How we stay visible

- Attend GCC conferences and meetups; present where we can.
- Run an AI roundtable for engineering leaders.
- Interview GCC engineering leaders and publish the conversations.
- Post regularly on LinkedIn: everyone in the team, not just one voice.

## Rhythm

- Daily BD working session, Tuesday to Friday.
- Every week: new connections, conversations started, next steps due.
- Goal: the activity heatmap goes from pale to dark green in three months.

## Also on the table

- Put a small share of time into our own product idea, so we earn from something we own and can offer GCCs a partnership, not just people.
', 'Starting text from the BD working session of 2026-11-10');
