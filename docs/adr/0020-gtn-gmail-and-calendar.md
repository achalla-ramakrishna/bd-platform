# ADR-0020: GTN sends from each person's Gmail, spots replies, and books calls on their calendar

- Status: Accepted
- Date: 2026-11-21

## Context

GTN phase 3 (see [gtn-plan.md](../gtn-plan.md); PRD FR-3.2, FR-4.2, FR-6.1, FR-6.2) asks for email sent from the
user's own mailbox and logged automatically, replies detected without anyone marking them, and calls booked
from the profile page straight into the owner's calendar, with a prompt after the call. Sign-in uses Google
with `openid email profile` only (ADR-0004). LinkedIn stays manual (ADR-0005).

## Decision

1. **Connect Google, separately from sign-in.** Each team member chooses to connect their own Google account
   from GTN; the app asks for these scopes then (incremental consent, offline access):
   - `gmail.send`: send the messages they write in GTN;
   - `gmail.metadata`: read the headers and the short preview of messages in threads GTN started, to spot
     replies; never message bodies or other mail;
   - `calendar.events` and `calendar.freebusy`: offer free slots and create the meeting.

   Only the person's own mailbox can be connected (its address must match their sign-in). They can disconnect
   at any time, which revokes the access at Google. As an internal Google Workspace app this needs no Google
   verification. Refresh tokens are encrypted at rest with AES-GCM (`GOOGLE_TOKEN_KEY`); without that key, or
   without the Google client, the feature is off and the phase 1 links ("Open in Gmail") remain.
2. **Send from Gmail** writes the message as the person, from their mailbox, then logs it and moves the track as
   "I sent it" does (ADR-0018). Later messages to the same person go in the same Gmail thread. If Gmail refuses,
   nothing is logged.
3. **Replies are found by polling** the threads GTN started (every 10 minutes by default, for 60 days after the
   last message out). A message from someone else is logged as a reply with its preview. For someone at
   Initiate or Unresponsive it moves them to Responded (any reply does, PRD rule 1) and Today asks the owner to
   confirm the outcome: positive, or "not the right time" with a revisit date. At other stages it is logged and
   flagged for the owner, who decides. Out-of-office auto-replies are skipped when Gmail marks them so
   (`Auto-Submitted` header).
4. **Booking on the profile page**: when the owner has connected their calendar and chosen calendar booking, the
   page offers their free slots (20 minutes by default, working hours and days they set, next 10 working days),
   from Google's free/busy. The person picks one and gives their email if we don't have it; the app checks the
   slot is still free and creates the event on the owner's calendar with a Google Meet link, inviting the
   person (Google sends the invitation). The track moves to Call scheduled with the call time. One upcoming
   booking per link. Otherwise the page keeps the owner's booking link.
5. **After the call time**, Today asks how it went: held (with the commitment), no-show, or rescheduled (the
   phase 1 actions).
6. Google is reached through one adapter (`GoogleApi`); everything else talks to it, so tests use a stub and a
   different provider can be added later.

## Alternatives considered

- **Gmail push notifications (Pub/Sub watch)**: faster, but needs a Google Cloud topic and a public push
  endpoint per environment; polling a handful of threads is enough.
- **`gmail.readonly`**: would allow reading bodies; the preview in metadata is enough to judge a reply.
- **Asking for the scopes at sign-in**: everyone would grant mailbox access just to use the app.

## Consequences

- The Google OAuth client needs the redirect URI `{APP_BASE_URL}/api/v1/google/callback` and the Gmail and
  Calendar APIs enabled in its Google Cloud project.
- Replies are seen within the polling interval, not instantly.
- A disconnected account stops reply detection and calendar booking for that person; GTN tells them.
