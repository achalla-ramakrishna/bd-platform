# ADR-0017: GTN runs on Connections, one track per person, rules in one place

- Status: Accepted
- Date: 2026-11-21

## Context

The team's GTN (Go To Network) requirements (see [gtn-plan.md](../gtn-plan.md)) ask for a people-first
pipeline: work the people who already know us from a first message to a warm commitment, diagnose why quiet
people are quiet, nudge them on a schedule, and keep warm people warm with a monthly beep. The platform's
conversations are account-first (`conversation.account_id` is required), but most of the network
(former colleagues, alumni, event acquaintances) is not at a target account. Connections (backlog item 4)
already hold the people the team knows, with who knows them and how.

## Decision

1. **A GTN contact is a Connection.** GTN adds a WhatsApp number and a source tag to `connection`, and an
   import of connections from CSV, including the *Connections* export LinkedIn gives a member of their own
   account (an upload, which ADR-0005 allows). Import de-duplicates on LinkedIn URL, email, WhatsApp number,
   or name and company; a duplicate adds the importer as someone who knows the person.
2. **One track per person** (`network_track`, unique on `connection_id`), owned by whoever starts it; others
   see it and can be given it. It holds the stage, the Why? reason, the one next action and its date, the
   nudges sent, the revisit date and reason, the beep interval and the call date. Every contact on a track
   therefore always has a stage, an owner and a next-action date (GTN goal 1).
3. **Stages** are Initiate, Responded, Unresponsive, Intro made, Call scheduled, Warm commitment and Monthly
   beep. A track starts at Initiate when the first message is sent. **Actions** move it, with the PRD's rules
   enforced in one service (`NetworkService`):

   | Action | Allowed from | Result |
   | --- | --- | --- |
   | Sent (outreach, nudge or beep) | any | logged out; Initiate: check back in *unresponsive-days*; Unresponsive: nudge count +1, next nudge in *nudge-spacing-days*, or parked in Monthly beep at *nudge-cap*; Warm commitment or Monthly beep: next beep in the track's interval (Warm commitment becomes Monthly beep) |
   | Replied: positive | Initiate, Unresponsive | Responded (next: make the intro) |
   | Replied: positive | Monthly beep | Call scheduled (next: book the call), the "re-engage" loop |
   | Replied: not the right time | Initiate, Unresponsive, Responded | Monthly beep, first beep on the revisit date (default next month) with the reason kept for the beep |
   | Diagnosed (Not seen / Inertia) | Unresponsive | the reason; a nudge is due now |
   | Diagnosed (Not the right time) | Unresponsive | parked in Monthly beep with a revisit date |
   | Intro made | Responded | Intro made (next: book a call) |
   | Call booked | Responded, Intro made, Monthly beep, Call scheduled | Call scheduled on that date |
   | Call held + commitment | Call scheduled | Warm commitment, commitment recorded, first beep in the interval |
   | No-show | Call scheduled | stays, next: reschedule tomorrow |
   | Committed (no call) | Intro made, Call scheduled | Warm commitment, commitment recorded |
   | Moved (override) | any | any stage, with a note |

4. **Automatic moves** run in a daily sweep (and before the Today list loads): Initiate with no reply
   *unresponsive-days* after the last outreach becomes Unresponsive with "diagnose" due; Warm commitment whose
   first beep is due becomes Monthly beep. Automatic moves are recorded with no user.
5. **Every move is recorded** in `network_stage_change` (from, to, who, when, note) and in the audit log.
6. **Thresholds are configuration** (`app.gtn.*`): unresponsive after 7 days, nudges 5 days apart, at most 3,
   beeps every 30 days (changeable per track).
7. **Today** lists the signed-in user's tracks whose next action is due, overdue first, then diagnoses, nudges,
   beeps and the rest, each with a suggested message rendered from a template (ADR-0018). Walnut's morning
   brief and email include it.
8. Account conversations stay as they are. A GTN contact who leads to business at a target account is
   followed there as a conversation or opportunity; GTN does not count it twice.

## Alternatives considered

- **Extend conversations** with GTN stages: they need an account, and their statuses mean something else.
- **A task table with many open tasks per contact** (the PRD's suggested Task/Nudge entity): one next action per
  track covers every rule in the PRD and keeps "what do I do next" unambiguous; history lives in the touches.
  A task table can be added if several parallel actions per person are ever needed.
- **One track per person per knower**: two people messaging the same contact would collide.

## Consequences

- A person must be in Connections to be worked in GTN; the import makes that quick.
- The same rules serve the board (drag = override with a note), the contact page and Today.
- Profile pages, Gmail and booking (GTN phases 2–3) attach to tracks later without changing these rules.
