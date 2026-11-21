# ADR-0021: GTN for the whole team: owners act, managers oversee, contacts change hands

- Status: Accepted
- Date: 2026-11-21

## Context

GTN phase 4 (see [gtn-plan.md](../gtn-plan.md); PRD "Users": "later, other team members doing outreach, each
owning their own contacts"; M10 FR-10.3, FR-10.4) moves GTN from one founder to the team. With several people
working the network, two people must not message the same contact at cross purposes, contacts must move
cleanly when someone is busy or leaves, and leaders need to see how each person's network is going.

## Decision

1. **The owner works the track.** Only the track's owner, or a GTN manager (Leadership, BD lead, Admin;
   `Role.NETWORK_MANAGERS`), can record what happened, send, change the next action or stop it. Everyone signed
   in still sees every track, so the team knows who is talking to whom. Starting someone who is already on GTN
   says whose contact they are.
2. **Hand-over.** The owner or a manager hands a contact to another active team member with a pipeline role,
   with an optional note. It is recorded on the timeline ("Handed over from A to B"), audited, and the new owner
   is emailed (when email is set up) and sees the contact in their Today. The stage and history stay; the profile
   link now opens the new owner's page; their Gmail messages start a new thread from their own mailbox. Several
   at once from the list, or all of one person's contacts (for someone leaving), by a manager.
3. **Team views.** Today, the board and the list default to "mine", and can show everyone's or one teammate's.
   A Team tab and the dashboard show, per owner: people on GTN, due, overdue, started, responded and warm in the
   period, commitments, and beeps sent on time.
4. **Beeps on time.** When a nudge or beep is sent, its due date is kept on the touch; a beep counts as on time
   when sent within 3 days of its due date. The dashboard shows the share on time and the beeps now missed, and
   nudges sent per Why? reason with how many of those people replied.
5. **Walnut's morning brief** adds a line per teammate with overdue GTN actions, so the team sees what slips.
6. **Not built: a WhatsApp morning digest.** It needs the WhatsApp Business API (a Meta business account and
   approved templates). The email brief covers the same ground; it can be added later through Walnut's mailer
   interface.

## Alternatives considered

- **Shared tracks with several owners**: no single person accountable, and contacts get two messages.
- **Hiding other people's contacts**: duplicates would go unnoticed and the team couldn't help each other.

## Consequences

- A team member who wants to help with someone else's contact asks the owner or a manager to hand it over.
- Deactivating a user leaves their contacts with them until a manager hands them over; the Team tab shows them.
