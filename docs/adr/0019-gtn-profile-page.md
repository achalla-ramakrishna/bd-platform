# ADR-0019: GTN profile pages: public, per person, tracked by the page itself

- Status: Accepted
- Date: 2026-11-21

## Context

GTN phase 2 (see [gtn-plan.md](../gtn-plan.md), PRD §5, FR-5.4, M8) asks for a compelling page that outreach links
point to: video first, two or three proof points, one-tap response, and tracking of who opened it, how much
of the video they watched and what they clicked, so a quiet contact who looks gets an immediate "ask now".
LinkedIn does not say who viewed a profile, so we track our own page. These are the app's first pages that
people outside the team open, without signing in.

## Decision

1. **One page per team member** (`profile_page`), edited by them in the app: headline, a short intro, up to
   three proof points, and the actions offered (book a call through their own booking link, chat on WhatsApp,
   and an "Interested, tell me more" button). Without a page, a sensible company default is shown, so a link
   always works.
2. **One link per person** (`profile_link`, unique per connection), with a random 128-bit token
   (`/p/{token}`). It is created when a message using the `{profile_link}` merge field is prepared, so the
   first message can carry it. The page shows the owner's page and greets the person by first name; nothing
   else about them is on it.
3. **The page is served by the backend** as a small, self-contained HTML page (no React, no sign-in, no cookies,
   escaped content). Everything else under `/api` stays behind sign-in.
4. **Video in MySQL, in 1 MB chunks** (`profile_video_chunk`, MP4 or WebM up to 40 MB, plus optional WebVTT
   captions). Railway has no volume set up for this app; chunks survive redeploys, fit MySQL's packet limit,
   and let the server answer byte-range requests so the video plays and seeks on phones. A hosted video
   service was not needed for a handful of short intro videos.
5. **Tracking is done by the page's own script**: a visit, video play, progress at 25/50/75/100%, and clicks.
   Link previews (WhatsApp, LinkedIn and email unfurlers fetch the page but run no script) are therefore not
   counted as visits. Events are limited per link per day; nothing identifying the device is stored. The page
   says plainly what it records.
6. **What a visit does** (FR-5.4): a touch on the person's GTN timeline (once per day per kind); for someone at
   Initiate, Unresponsive or Monthly beep, an **ask now** as their next action, due today and listed in Today
   right after overdue items, with the direct-ask template; an Unresponsive person without a Why? reason is
   marked Inertia (they saw it). The owner gets an email (when email is set up) for the first ask-now and for
   every "Interested" click. Sending the ask clears it.
7. **Dashboard v1** (M10): funnel by stage, conversion of the people started in a period (responded, intro,
   call, warm), unresponsive by Why? reason and what happened next, nudges, overdue actions and missed beeps,
   profile page visits, watches and clicks, by period, owner and source tag.

## Alternatives considered

- **Track visits on the server request**: link previews would count as visits and trigger false "ask now"s.
- **A video host (YouTube unlisted, Vimeo)**: no storage work, but less exact progress, third-party cookies on
  the page, and another account to manage. Can be added later as an alternative source.
- **A Railway volume for videos**: needs set-up per environment and is lost if misconfigured.

## Consequences

- The page is public to anyone with the link; links are unguessable and can be replaced by stopping and
  re-starting GTN for a person.
- Video upload takes the raw file (not a multipart form), so the app's 5 MB form-upload limit stays as it is.
- Gmail sending and calendar booking (phase 3) plug into the same page and events.
