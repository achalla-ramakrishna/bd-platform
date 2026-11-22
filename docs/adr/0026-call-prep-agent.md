# ADR-0026: The ops agent: a call-prep brief and a draft opportunity when a call is booked

- Status: Accepted
- Date: 2026-11-22

## Context

Step 5 of the BD agent ([bd-agent-plan.md](../bd-agent-plan.md)). A booked call is the point where prospecting turns
into business. Before it, the owner gathers what we know (the research, what was said so far, the account) and thinks
of questions; after it, an opportunity should exist in the pipeline. Both are often skipped or done late.

## Decision

1. **When.** A call is booked in two places: a conversation whose status becomes "meeting booked", and a GTN track
   whose call is booked (by hand, or by the person on the profile page, ADR-0020). Each publishes an application event
   after its transaction commits (`engagement.MeetingBooked`, `network.CallBooked`), so neither module depends on the
   agent (ADR-0003). "Prepare for the call" on a conversation or GTN contact does the same on demand, and "Prepare
   again" after something changes.
2. **The agent.** One structured Claude call, no tools, in `assist` (`CallPrepWriter`). Given the person, their
   newest brief (ADR-0022), our history with them (touches, replies), their account in our records with its latest
   research, and open opportunities at the account, it writes:
   - the call brief: the goal of the call, who they are (two or three lines), what we know about their needs (each
     point marked as from our records or from research), a recap of the conversation so far, five to eight questions
     to ask, likely concerns with a short answer each, and the next step to propose;
   - a draft opportunity: whether there is one yet (yes, maybe, not yet), a name, the type (staff augmentation or
     project), the need in one paragraph, and the roles or skills mentioned. **No value, rate or date is invented**:
     those stay empty for the owner.
   Facts must come from what it was given; it says what is unknown.
3. **The owner decides.** The brief is shown on the conversation or GTN contact and on the Calls page (upcoming calls
   with their prep). **Create opportunity** (pipeline roles) creates it from the draft, as edited by the owner, at the
   person's account, with the contact as decision maker, and links it to the conversation. A GTN connection with no
   account gets the brief only, with a note to link them to an account first.
4. **Cost.** One call per booked meeting; a daily cap (`app.agent.call-prep-per-day`, default 50). A prep for the
   same conversation or track is not repeated for the same call date unless asked.
5. **Where it lives**: `prospecting` (with the other agent steps), storing each prep in `call_prep` (V26).

## Alternatives considered

- **Create the opportunity automatically**: the pipeline is what Leadership reports on; an opportunity should exist
  only when the owner thinks there is one. The draft makes it one click.
- **Prep the morning of the call**: briefs written at booking can be read days ahead; "Prepare again" refreshes them.

## Consequences

- The brief is only as good as our records; the agent says when it has little to go on.
- One Claude call per booked meeting, bounded by the cap.
