# ADR-0023: The daily prospecting queue: the agent picks and drafts, a person sends

- Status: Accepted
- Date: 2026-11-21

## Context

Step 1 of the BD agent (ADR-0022) writes a brief and a score for one person when asked. Step 2 of the plan
([bd-agent-plan.md](../bd-agent-plan.md)) turns that into a daily routine: each morning, each BD person gets a short
list of the best people to approach, with the note already drafted, and spends minutes, not hours, on it. LinkedIn
stays manual (ADR-0005): the person copies the note, opens the profile and sends it themselves.

## Decision

1. **Whose queue.** Everyone who can edit the pipeline (`Role.PIPELINE_EDITORS`) gets a queue of people *they* can
   approach: contacts at accounts they own, and connections they know (or added). A person is in at most one
   queue at a time.
2. **Who is eligible.** A contact who has not opted out and has no conversation yet, or a connection with no GTN
   track. Not someone already sent to from the queue, and not someone skipped in the last 90 days. The person must
   have a brief from the last 30 days with a score of at least `app.agent.queue-min-score` (default 50).
3. **Choosing.** Best score first, up to `app.agent.queue-size` a day (default 20). The queue is filled every
   weekday morning (`app.agent.queue-cron`, 06:45 IST) and topped up when the page opens, as new briefs finish.
   Items not acted on by the next morning expire; the person can come back on a later day.
4. **Research to feed it.** The morning job also starts research on the most promising people with no recent brief
   (contacts at the best-fitting accounts first, then connections known closely), up to
   `app.agent.queue-research-per-person` (default 5) per person and always within the team's daily research cap
   (ADR-0022). "Find more people" does the same on demand. No other Claude calls: the draft is the brief's
   connection note.
5. **Acting.**
   - *Copy and open LinkedIn*: copies the (edited) note and opens the profile, or a LinkedIn people search by name and
     company when we have no profile address. Nothing is sent by the app.
   - *Mark sent*: stores the final note and starts the follow-up where it belongs: a conversation at the account for a
     contact (channel LinkedIn connection, next step "Check for a reply" in 7 days), or a GTN track for a connection
     (ADR-0017). From then on the existing reminders take over.
   - *Skip*, with a reason (not the right person, not now, already in touch, other).
6. **Learning (feeds step 6).** The agent's draft and the sent note are both kept, so edits can be measured; skip
   reasons are kept. The page shows the last 30 days: sent, skipped, and how often notes were edited.
7. **New module `prospecting`.** It uses `assist` (briefs), `engagement` (conversations) and `network` (GTN), and
   nothing uses it, so the module graph stays acyclic (ADR-0003). `engagement` gains a small public entry point to
   start a conversation.

## Alternatives considered

- **A fresh Claude call per queued person to rewrite the note**: doubles the cost for little gain; the brief's note
  is already specific. A copy agent with the team's playbook can come later.
- **One shared team queue**: people would collide on the same prospect; ownership already exists on accounts and
  connections.
- **Research everyone every night**: cost grows with the contact list; the per-person and daily caps keep it bounded.

## Consequences

- A queue is only as good as the briefs behind it; on the first days it is short while research catches up.
- Skips and edits give the first data on what the agent gets wrong.
