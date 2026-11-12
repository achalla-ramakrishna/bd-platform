# ADR-0016: Requirements come in on opportunities; candidates are tracked to a start

- Status: Proposed
- Date: 2026-11-12

## Context

Accounts send new requirements (roles, headcount, start dates) at any time,
and winning the business is only half the job: each requirement has to be
filled with people who start at the client. The opportunity form held
requirement lines, but there was no place to log a requirement quickly, no
view of everything an account had asked for, and nothing between "won" and
"deployed" to track who was proposed, interviewed and selected. The
`submission` table (V4) was designed for this (FR-TAL-03) but never used.
Saving the opportunity form also deleted and re-inserted every requirement
line, which would have lost anything attached to them.

## Decision

1. **Opportunities menu.** A page lists opportunities by account with their
   requirements and how far each is from being filled, plus a Fulfilment view
   of every requirement not yet filled, soonest start first, with the next
   thing to do.
2. **New requirement.** A requirement that comes in is added to an open (or
   won) staff augmentation opportunity at the account, or starts a new one
   (`POST /opportunities/{id}/requirements`). The stage rules stay as they
   are (BR-BD-07, BR-BD-08). On a won opportunity it also becomes a request
   to Delivery (BR-BD-12). Adding one raises the opportunity's version, so a
   form opened earlier cannot silently drop it.
3. **Requirement lines keep their identity.** Saving the opportunity updates
   lines in place by id, adds new ones and removes left-out ones only if they
   have no candidates.
4. **Candidates per requirement** (`submission`, extended in V17): an engineer
   from our bench or a named external candidate (with a source, such as a
   hiring partner). Stages: Proposed → Profile shared → Client interview →
   Selected → Onboarding → Started, or Rejected or Withdrawn. Forward moves
   may skip stages; one step back corrects a mistake; closed candidates can
   be reopened. Interview and start dates are recorded. A bench engineer's
   profile goes to the client only with their consent (BR-BD-10). When as
   many candidates have started as the headcount, the request to Delivery is
   marked filled.
5. **Who.** BD roles add requirements. BD and Delivery propose and move
   candidates. Everyone signed in can see them.
6. **Walnut chases it** (ADR-0015): the morning plan adds requirements with
   nobody proposed, profiles waiting for feedback for three days, interviews
   today or tomorrow and past ones without a decision, selections without a
   start date and starts in the next week.

## Alternatives considered

- **Requirements as their own records, grouped into opportunities later:**
  quicker to log, but pipeline value and stage rules would miss them.
- **One status per requirement:** simpler, but no record of who was proposed,
  rejected or started, which Delivery and the next requirement need.

## Consequences

- One place shows what each account wants and what is stuck.
- Starting a candidate does not create the deployment record: Delivery still
  adds it (with the SOW) on Deployed teams; the request shows as filled.
- External candidates are stored by name and source only.
