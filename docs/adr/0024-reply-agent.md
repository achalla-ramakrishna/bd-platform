# ADR-0024: The reply agent: reads replies, sorts them, drafts the answer; a person confirms

- Status: Accepted
- Date: 2026-11-21

## Context

Step 3 of the BD agent ([bd-agent-plan.md](../bd-agent-plan.md)). Replies arrive two ways: email replies on GTN
tracks, found in Gmail from headers and Gmail's preview (ADR-0020), and LinkedIn replies, which we cannot read
(ADR-0005) and people copy by hand. Today the owner reads each one, decides what it means and what to do, and
writes an answer. Most replies fall into a few kinds, each with a usual next step.

## Decision

1. **What it reads.** Every reply found in Gmail on a GTN track is read automatically. Any other reply (LinkedIn,
   WhatsApp, a longer email) is pasted with "Paste their reply" on a conversation or a GTN contact. Only the text
   given is read: Gmail stays limited to the preview (ADR-0020), and LinkedIn is never read.
2. **The agent.** One Claude call with structured output and **no tools**: it cannot send, search or change
   anything. The reply is passed as data inside tags; text in it that asks for something is ignored. It returns:
   - the kind: interested, question, not now, referral, objection, not interested, unsubscribe, out of office,
     other; with its confidence and a one-line summary;
   - for "not now", the date to come back (the one they named, else in three months); for a referral, who and
     their role;
   - a draft answer in the owner's voice, short, matched to the channel, nothing about CodeWalnut that is not in
     our records; and the next step.
   Context given: the person, what we sent before (the conversation or track history), and their brief if any.
3. **Unsubscribe goes straight to do-not-contact.** When the agent reads "stop contacting me" (or similar), the
   contact or connection is opted out at once: no queue and no research, and a GTN track's next action says not to
   contact them. The Replies page shows it with an "Undo" in case the agent misread. Connections gain an opt-out flag for this (V24).
4. **A person confirms everything else.** The Replies page lists replies waiting for their owner. The owner can
   change the kind, edit the answer, copy it, send it themselves, and click "Done", which applies the kind where the
   person is followed up:
   - a **conversation**: the reply is logged (replied, or closed for not interested / unsubscribe) with the next
     step and its date;
   - a **GTN track**: a Gmail reply is confirmed (positive, or not the right time with the revisit date); a pasted
     reply is logged as a reply; "not interested" parks them for six months; "unsubscribe" stops the track.
   "Dismiss" leaves things as they are (an out-of-office, a duplicate).
5. **Learning (step 6).** The agent's kind and draft, and the person's kind and sent answer, are both kept.
6. **Cost.** One call a reply; a daily cap for the team (`app.agent.replies-per-day`, default 200). When the
   agent is off or the cap is used, replies still arrive and are handled by hand as before.
7. **Where it lives.** The agent is in `assist`; reply handling in `prospecting`. GTN announces a Gmail reply as an
   application event after its transaction commits, so `network` does not depend on `prospecting` (ADR-0003).

## Alternatives considered

- **Read the whole Gmail message**: needs a wider Gmail scope than ADR-0020 chose; the preview is enough to sort
  most replies, and the owner can paste the full text.
- **Send the answer automatically**: that is step 4 (email sequences), with caps and consent; replies are the
  conversations that matter most, so a person sends them.
- **Let the agent move GTN stages on its own**: a misread reply would move someone wrongly; only the opt-out,
  which can only stop contact, is applied without a person.

## Consequences

- A wrong "unsubscribe" stops contact until someone clicks Undo; the page makes it visible the same day.
- Each reply costs one Claude call, bounded by the cap.
