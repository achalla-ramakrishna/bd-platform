# GTN (Go To Network): gap analysis and plan

- Status: **Phases 1 and 2 built** (2026-11-21; ADR-0017 to ADR-0019). Phases 3–4 proposed.
- Date: 2026-11-21
- Source: the team's *GTN (Go To Network) — Product Requirements Document* (2026-11-20). Requirement IDs
  below are that document's (FR-1.1 … FR-10.4), prefixed `GTN-` to keep them apart from the BRD's IDs.

## 1. What GTN asks for, in one paragraph

Work the people who already know us (former colleagues, LinkedIn connections, alumni, clients, event
attendees) through a fixed pipeline: **Initiate → Responded → Intro made → Call scheduled → Warm commitment →
Monthly beep**, with side routes (Responded but "not the right time" → Monthly beep; Intro → Warm commitment
without a call; a beep that reopens a conversation → Call scheduled). Anyone who doesn't reply within N days
becomes **Unresponsive** and gets a **Why?** diagnosis (not seen on LinkedIn / inertia / not the right time)
that decides the next nudge, spaced and capped. Outreach links point to a **profile page** we control (video
first, one-tap response, visit tracking per contact), and a visit from a quiet contact triggers an "ask now".

## 2. How much is already built

The platform is account-first (accounts → contacts → conversations → opportunities), with a people layer
(Connections) beside it. GTN is people-first. A good share of the plumbing exists; the GTN-specific logic does
not.

| Module | Status | What exists today | What's missing for GTN |
| --- | --- | --- | --- |
| M1 Contacts | **Partial (≈60%)** | Connections (backlog 4): name, company, role, city, LinkedIn URL, email, notes, photo; who knows them, how well and how (`connection_knower`); optional target account; duplicates by name + company refused with "I know them too". Log with AI adds people from screenshots. | WhatsApp number; source tag (e.g. "college alumni", "past employer"); import from CSV, including LinkedIn's own *Connections* export; de-duplication on LinkedIn URL, email or phone; filters by stage and Why? reason. |
| M2 Pipeline board | **Partial (≈30%)** | Conversations list with statuses and due/overdue cards (backlog 3); a drag-free card board pattern on Improvements (backlog 5). | A GTN board with one column per stage and drag to move; cards with days in stage, last touch, next action, overdue highlight; list view with search, filters, sort. |
| M3 Outreach and logging | **Partial (≈40%)** | Activity log with channels (LinkedIn connection/message, InMail, email, call, meeting, event, referral) and outcomes; per-conversation timeline; Walnut drafts connection notes and first messages; LinkedIn profile opens in a new tab (ADR-0005). | Message templates per stage and per Why? reason with merge fields; **WhatsApp** channel and click-to-chat with the message pre-filled; "copy message + open LinkedIn profile" in one click; direction (out/in) on interactions; **send from Gmail** and log automatically. |
| M4 Response tracking | **Partial (≈35%)** | Logging a reply moves a conversation to Replied; Walnut lists conversations quiet for 14 days. | One-tap "replied" on a GTN contact with outcome (positive / not the right time + revisit date + reason); automatic **Initiate → Unresponsive** after N days (default 7); Gmail reply detection. |
| M5 Diagnosis and nudge engine | **Missing (≈10%)** | Walnut suggests follow-ups in general terms. | The Why? prompt with a suggested reason (default Inertia); the nudge playbook (channel, template, date); spacing (5 days) and cap (3), then park in Monthly beep; a profile visit by a quiet contact creates an "ask now" task. |
| M6 Calls | **Partial (≈20%)** | Conversation status Meeting booked; meeting activities. | A booking link; booking → Call scheduled (+ calendar event); after the call time, prompt held / no-show / rescheduled, and capture the **commitment** (referral, intro, pilot talk, follow-up). |
| M7 Monthly beep | **Missing** | — | A recurring touch per contact (default 30 days, per-contact interval); suggested content (business update, article, congratulations, check-in), referring back to the "not the right time" reason; beep log; a reply that leads somewhere → Call scheduled. |
| M8 Profile page | **Missing** | Content exists to draw on (the CodeWalnut vendor kit deck); no public pages at all — everything is behind Google sign-in. | A public page per contact (`/p/{token}`): video first, 2–3 proof points, one CTA (book / WhatsApp / "Interested"); visit, video play, completion % and CTA-click tracking; editable content; alert "watched 80% of the video". |
| M9 Today and reminders | **Mostly built (≈70%)** | Walnut's weekday morning brief by email (Resend) to admins, Walnut chat, Conversations "due" view, the life-cycle steps (request sent → connected → messaged → replied → meeting). | A GTN-ordered Today list (overdue first, then profile visitors, then due nudges and beeps) with the suggested message ready to send; GTN items in the morning email. WhatsApp digest needs the WhatsApp Business API (not built). |
| M10 Dashboard | **Partial (≈25%)** | Heatmap of activity, pipeline and conversation counts. | GTN funnel by stage with conversion between stages; unresponsive by Why? reason and nudge outcome; overdue actions and missed beeps; filters by period, source tag, owner. |
| Data model (§7) | **Partial** | `connection`, `connection_knower`, `activity`, `conversation`, users, audit log. | Stage + stage history, Why? reason, next action, beep interval, nudge count, revisit date per contact; tasks/nudges; commitments; templates; profile links and events. `activity.account_id` is required today, so network contacts without an account can't be logged. |

**Overall: roughly a third is in place** — the contact list, activity logging, reminders and the email brief.
The pipeline itself (stages, rules, diagnosis, nudges, beeps), the profile page and the mailbox/calendar
integrations are new.

## 3. Design decisions to agree (ADRs before code)

1. **A GTN contact is a Connection, tracked per owner** (proposed ADR-0017). GTN people are often not at a
   target account (alumni, former colleagues), but `conversation` requires an account. So the GTN pipeline
   hangs off `connection`: a new `network_track` row per connection and owner holds the stage, Why? reason,
   next action date, nudge count, revisit date and reason, and beep interval. Conversations stay for
   account-led selling; when a GTN contact leads to a target account, the track links to a conversation or
   opportunity there, so nothing is counted twice.
2. **One interaction log** (proposed ADR-0018). Extend `activity` rather than add a second log: `account_id`
   becomes optional, add `connection_id`, `direction` (out/in) and a `WHATSAPP` channel. The heatmap and fit
   scores keep working.
3. **Public profile pages** (proposed ADR-0019). The first unauthenticated pages in the app: random unguessable
   tokens, no data about the contact on the page beyond an optional first name, rate-limited event endpoint,
   a short privacy note on the page (tracking a known person's visits is personal data under the DPDP Act).
   Video: self-hosted MP4 with captions on the Railway volume, so play and completion % are exact (an
   unlisted YouTube embed is the fallback).
4. **Gmail and Calendar** (proposed ADR-0020). Send from the user's Gmail and detect replies with
   incremental Google consent (`gmail.send`, `gmail.readonly` limited to threads we started, `calendar.events`).
   As an internal Google Workspace app this needs no Google verification. Tokens are encrypted at rest.
   Until then, email is "open in Gmail with the message filled in" and the user confirms.
5. **Booking.** Phase 1: the profile page's "Book a call" uses the owner's Google Calendar appointment
   schedule link and the user marks the call booked. Phase 3: a booking made through our page creates the
   event via the Calendar API and moves the stage automatically.
6. **Unchanged rules.** ADR-0005 stands: LinkedIn messages are copied and sent by a person; the platform
   never reads or sends on LinkedIn. Importing the CSV a user downloads from their own LinkedIn account is an
   upload, which ADR-0005 allows. Partner- or team-specific behaviour (thresholds, spacing, caps, intervals,
   templates) is configuration.

## 4. Plan

Each phase is shippable on its own. Estimates assume the current pace (one developer with Claude Code) and
include tests, docs and a browser check.

### Phase 0 — Agree the design (1–2 days)
- Agree this document, the stage/transition rules (§3 of the PRD) as a state table, and ADR-0017 to ADR-0020.
- Domain model and data model updates (`docs/domain-model.md`, `docs/data-model.md`), API contract
  additions in `docs/api/openapi.yaml`, and screen sketches for the board, the contact track and Today.
- Settle the open questions in §5.

### Phase 1 — The GTN pipeline (≈ 1.5–2 weeks) — covers M1, M2, M4, M5, M7, M9 and most of M3
- **Contacts:** WhatsApp number and source tag on connections; CSV import (our template and LinkedIn's
  *Connections* export) with de-duplication on LinkedIn URL, email or phone; filters by source, stage, owner,
  reason.
- **Pipeline:** `network_track`, `stage_history`, `network_task`, `commitment`, `message_template` (Flyway
  V18). Every transition rule from the PRD enforced in one service, manual override allowed, every move
  logged with user, previous stage and note.
- **Automation (scheduled, like Walnut):** Initiate → Unresponsive after N days; nudges generated from the
  playbook with spacing and cap; park in Monthly beep after the cap; beeps due every N days or on the revisit
  date.
- **Screens:** GTN board (drag between stages, overdue highlight) and list view; the contact's track and
  timeline; Why? prompt with suggestion; "Replied" with outcome; "Warm commitment" with type and text.
- **Outreach:** templates with merge fields (first name, shared history, mutual contact, last topic);
  WhatsApp click-to-chat (`wa.me`) and "copy + open LinkedIn", each followed by "I sent it" which logs the
  interaction; email via "open in Gmail" for now. Walnut drafts personalised versions of a template with
  Claude when asked.
- **Today:** a GTN Today list ordered overdue → nudges due → beeps due, each with the suggested message;
  the same list in Walnut's morning email.

### Phase 2 — Profile page and tracking (≈ 1 week) — M8, FR-5.4, first part of M10
- Profile page editor (video upload with captions, headline, 2–3 proof points, CTA options), one per owner.
- Tokenised link per contact, inserted by the templates' merge field `{profile_link}`.
- Events: visit, video play, progress (25/50/75/100%), CTA click; "Interested, tell me more" notifies the
  owner.
- A visit by an Unresponsive contact creates an "ask now" task at the top of Today and an alert
  ("watched 80% of the video").
- Dashboard v1: funnel by stage and conversion, unresponsive by reason, overdue actions, missed beeps,
  filters by period, source and owner.

### Phase 3 — Mailbox and calendar (≈ 1–1.5 weeks) — FR-3.2, FR-4.2, M6
- Connect Gmail (incremental consent); send from the user's mailbox from the template; log automatically.
- Reply detection on threads we started → Responded, with the outcome asked in Today.
- Booking through the profile page → Calendar event → Call scheduled; after the call time, prompt held /
  no-show (reschedule task) / rescheduled, and capture the commitment.

### Phase 4 — Team use and polish (≈ 3–5 days)
- Several owners: each person sees their own tracks by default; admins see all; hand-over of a contact.
- Dashboard v2: nudge outcome by reason, beeps sent on time, metrics as finally defined.
- Optional, if wanted: WhatsApp morning digest through the WhatsApp Business API (needs a Meta business
  account and approved templates).

**Total: about 4–5 weeks of build after the design is agreed**, with the founder able to run GTN end to end
(minus automatic email and booking) after Phase 1.

## 5. Open questions for the team

Phase 1 went ahead on the proposals below (ADR-0017, ADR-0018); the team can still change 3–7 before phase 2.
Where phase 1 differs from §3 above: GTN touches have their own log (`network_touch`, ADR-0018) instead of making
`activity.account_id` optional, so account rules and fit scores are untouched; the heatmap counts both.

1. Is a GTN contact always a Connection (someone a team member knows), or can it be anyone, e.g. an event
   attendee nobody has met? (Proposal: a Connection; importing creates them.)
2. When two team members know the same person, does each run their own track, or does one owner run it?
   (Proposal: one active track per person, owned by whoever starts it; others see it.)
3. Video for the profile page: who records it, and one page for the company or one per team member?
4. Booking: is a Google Calendar appointment schedule acceptable for Phase 1?
5. Gmail access: are `gmail.send` and read access to our own outreach threads acceptable to the founders?
6. Is the WhatsApp digest needed, or is the email brief enough?
7. Success metrics: confirm the five in the PRD and the period they are reported for (week / month).
8. Should Walnut (the BD agent) drive GTN Today, i.e. GTN replaces its current life-cycle steps for
   people, or run beside them? (Proposal: Walnut uses GTN for network contacts and keeps the account steps.)
