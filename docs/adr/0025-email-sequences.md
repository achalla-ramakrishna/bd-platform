# ADR-0025: Email sequences: the agent writes, the owner approves once, the app sends within guardrails

- Status: Accepted. Sending stays **off** until a legal review has been done (see "Before switching on")
- Date: 2026-11-21

## Context

Step 4 of the BD agent ([bd-agent-plan.md](../bd-agent-plan.md)). Most prospects need several touches before they
reply, and people forget to follow up. LinkedIn stays manual (ADR-0005), but email can be sent from our own
mailboxes through the Gmail connection each person already has (ADR-0020). Sending without a click per email is the
first time the app contacts people on its own, so the rules that make it safe have to be in code.

## Decision

1. **Who.** Sequences go to **contacts** (prospects at accounts) with an email address. Connections on GTN are not
   sequenced: GTN already paces their follow-ups (ADR-0017).
2. **Sequences are configuration.** BD leads, Leadership and Admins keep a short list of sequences, each with up to
   4 steps: days to wait (at least 3), a subject and a body that says what the email should do. One generic sequence
   is seeded.
3. **The agent writes, the owner approves once.** Starting a sequence for a contact makes one Claude call (no
   tools) that writes each step's email for that person from the templates, their brief and our history with them:
   plain text, under 120 words, no links, nothing about CodeWalnut beyond the offers we list, steps 2+ written as
   follow-ups in the same thread. The owner reads and edits every email, then clicks **Approve and schedule**. Both
   the agent's text and the approved text are kept.
4. **The app sends**, from the owner's Gmail, step 1 on the next sending slot and each later step after its wait,
   in the same Gmail thread. Every send is checked again, in code, just before it goes:
   - sending is switched on (`app.agent.email.sending`, default **false**);
   - a weekday, between 09:00 and 18:00 IST (`app.agent.email.window`);
   - the contact has not opted out and the address is not on the suppression list;
   - their consent basis is one of `app.agent.email.consent-bases` (default: existing relationship, consented,
     business contact; to be confirmed by the legal review);
   - no reply since the sequence started: no reply in Gmail, no reply logged on their conversation, conversation not
     closed;
   - the owner's daily cap (`app.agent.email.per-sender-per-day`, default 25) and at most one email to the same
     person in 3 days;
   - the owner's Google connection can send.
   A check that fails for a reason that can change (window, cap, connection) waits for the next run; one that
   cannot (opt-out, reply, suppression, consent) ends the sequence with the reason shown.
5. **Unsubscribe in every email.** A footer line that the owner cannot remove links to a page on our domain
   (`/u/{token}`) where one click opts them out; the message also carries `List-Unsubscribe` and
   `List-Unsubscribe-Post` headers so Gmail shows its own unsubscribe button (RFC 8058). Unsubscribing opts the
   contact out, adds the address to the suppression list and stops every sequence to it.
6. **A reply stops it.** Sequence threads are checked in Gmail (headers and preview only, as ADR-0020). A reply
   stops the sequence and goes to the reply agent (ADR-0024) on the Replies page. The owner can stop or pause a
   sequence at any time.
7. **Logged like any other touch.** Each sent email is an activity on the contact's conversation; the first send
   opens the conversation if there is none.
8. **Where it lives**: the copy agent in `assist`; sequences in `prospecting`. A small MIME helper moves to
   `google` so GTN and sequences build messages the same way.

## Before switching on

`AGENT_EMAIL_SENDING=true` is an Admin decision after a lawyer has confirmed, for the countries we email: the lawful
basis for emailing business contacts (India's DPDP Act 2023 and Rules; GDPR and PECR for UK and EU contacts;
CAN-SPAM for US contacts), the consent bases allowed, the footer wording (sender identity and postal address where
required) and how long sequence data is kept. Until then, sequences can be written and approved but nothing is sent.

## Alternatives considered

- **Approve each email**: safest, but the follow-ups are what people forget; one approval of the whole sequence,
  with the checks in code, keeps the person in charge of what is said.
- **A bulk email service**: better deliverability tooling, but cold-looking mail from a shared domain and another
  processor of personal data; a few personal emails a day from each person's own mailbox fit B2B prospecting.
- **Sequences on GTN tracks**: GTN already nudges people we know, with its own rules.

## Consequences

- Deliverability depends on each person's mailbox; the low daily cap and plain text help.
- An address on the suppression list can only be removed by an Admin, deliberately.
