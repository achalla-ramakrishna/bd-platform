# ADR-0018: GTN touches are logged on the track; templates suggest every message

- Status: Accepted
- Date: 2026-11-21

## Context

GTN needs every interaction on a contact's timeline with its channel and direction (FR-3.5), WhatsApp as a
channel, and message templates per stage and per Why? reason with merge fields (FR-3.1). The existing
`activity` log requires an account and is the basis of account fit scores and account timelines; GTN
contacts usually have no account. Sending stays with people: LinkedIn is never automated (ADR-0005), WhatsApp
has no API here, and Gmail sending is GTN phase 3.

## Decision

1. **`network_touch`** logs GTN interactions: track, user, channel (LinkedIn, email, WhatsApp, call, meeting,
   other), direction (out/in), kind (outreach, nudge, beep, reply, intro, call, note), the template used and a
   summary. The activity heatmap counts them as touches. `activity` keeps its account rules.
2. **Sending is one click to the right place, then "I sent it"**: WhatsApp opens click-to-chat (`wa.me`) with
   the message filled in; LinkedIn copies the message and opens the profile; email opens Gmail's compose
   window with the message filled in. The person sends it and confirms, which logs the touch and moves the
   track. Nothing is sent by the platform.
3. **`message_template`**: name, stage, optional Why? reason, channel (or any), subject (email) and body with
   merge fields `{first_name}`, `{full_name}`, `{company}`, `{how_we_know}`, `{last_topic}`, `{revisit_note}` (the reason they gave, as a sentence),
   `{my_name}`. Starter templates for every stage and reason come with the migration; pipeline editors edit
   them. The suggestion for a track picks the template for its stage and reason, preferring the playbook's
   channel: Not seen → email or WhatsApp, a friendly no-ask note pointing back to LinkedIn; Inertia → the same
   channel first, then email or WhatsApp, a short direct ask; beeps → the last channel used.
4. Templates are plain text with merge fields filled by the server; unknown fields are left as typed so the
   person sees them before sending.

## Consequences

- A GTN contact's timeline is separate from account activity; when they lead to an account conversation, that
  conversation has its own log.
- Messages stay personal: the person always sees and can edit the text before sending.
- Gmail sending (phase 3) will log the same touch automatically instead of "I sent it".
