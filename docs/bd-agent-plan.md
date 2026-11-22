# The BD agent: plan

- Status: **Agreed** (2026-11-21). Steps 1 to 5 built (step 4's sending is off until a legal review); step 6 proposed.
- Decisions: [ADR-0022](adr/0022-bd-agent.md), [ADR-0023](adr/0023-prospecting-queue.md), [ADR-0024](adr/0024-reply-agent.md), [ADR-0025](adr/0025-email-sequences.md), [ADR-0026](adr/0026-call-prep-agent.md). Builds on Walnut (ADR-0015), GTN (ADR-0017 to ADR-0021) and the
  no-LinkedIn-automation rule (ADR-0005).

## 1. What we want

An agent that does what a business developer does: chooses who to approach and why now, researches them,
writes the connection note and follow-ups, reads replies, books the meeting, opens the opportunity and
prepares the call, so a person spends minutes a day on decisions and clicks instead of hours on research
and writing.

## 2. The one thing it will not do

It will not send connection requests or messages on LinkedIn, or read LinkedIn pages. LinkedIn's User Agreement
forbids automated activity and scraping, accounts that use such tools are restricted, and LinkedIn is not taking
new partners for its Sales Navigator API (ADR-0005). The agent does the thinking; a person does the LinkedIn
click from a queue the agent prepared ("copy message and open profile", already in GTN). Email is different:
it can be sent automatically from our own mailboxes, within caps and consent rules (step 4).

## 3. Who does each step

| Step | Who | In bd-platform |
| --- | --- | --- |
| Ideal customer profile and target accounts | Person sets it; agent refines from wins and losses | Strategy, account fit scores, Find GCCs; learning from outcomes: step 6 |
| Research the person: role, what they own, recent public activity, why now | **Agent** | **Step 1 (built)** |
| Score and prioritise | **Agent** (the app computes the score from the agent's ratings) | **Steps 1 and 2 (built)** |
| Draft the connection note and follow-ups | Agent drafts, person approves | **Built**: the note in the daily queue (step 2); follow-up sequences: step 4 |
| Send on LinkedIn | **Person**, one click per prospect | Built (GTN) |
| Email touches | Agent writes, person approves once, app sends within caps | **Built** (step 4); sending off until the legal review |
| Classify replies, draft answers | Agent classifies and drafts; person approves | **Built** (step 3): the Replies page |
| Book the meeting | Agent (calendar) | Built (GTN profile page) |
| Create the opportunity, call-prep brief | Agent drafts, person creates in one click | **Built** (step 5): Network → Calls |

## 4. How it is built

- **A workflow with agent steps, not a free-roaming agent.** Each prospect moves through fixed stages the code
  controls (researched → queued → contacted → replied → meeting → opportunity); Claude does the steps that need
  judgement: research, scoring, writing, reading replies.
- **Small agents with narrow tools.** Research (web search, our records), copy (brief + playbook → messages),
  reply (classify + draft, no tools that send or export), ops (opportunity, call prep). Each has its own prompt.
- **The database is the memory.** Every fact, touch and decision is stored; each call loads only what it needs.
- **The approval queue is the checkpoint.** Anything sent in someone's name goes past a person until the numbers
  show a specific action is safe to let through (step 6).
- **Guardrails in code**: caps per prospect, per person and per day; a do-not-contact check before every send;
  nothing sent to someone who said no; consent and unsubscribe for email (DPDP, and GDPR or CAN-SPAM where they
  apply; to be reviewed by a lawyer before step 4).
- **Untrusted input stays data**: web pages and replies are passed to Claude as data; no agent that reads them
  has a tool that sends or exports.
- **Feedback**: every edit or rejection of a draft is kept; results per message version are measured; prompt
  changes are checked against a set of good examples before they ship (step 6).

## 5. Steps

1. **Research agent (built, ADR-0022).** For a contact or connection: Claude searches the public web (LinkedIn
   pages excluded) and reads our records (the account research, our history with them, who knows them) and
   writes a brief: who they are and what they own, why now (each point with its source), the best CodeWalnut
   angle, talking points, a draft connection note under 280 characters, and what to check. It rates role fit and
   timing 1–5; the app adds access (how well we know them) and computes a 0–100 score. Professional information
   only. "Research this person" on a contact, a connection or a GTN track; "Research contacts" on an account
   (up to 10 at a time); a daily cap. Public web only for now: a licensed data source (Apollo, ZoomInfo or
   similar) can be added behind the same adapter if the team buys one.
2. **Daily prospecting queue (built, ADR-0023).** Each morning the agent picks the best people at well-fitting accounts (score,
   no conversation yet, not on do-not-contact), drafts their notes from the briefs and queues 20 per person.
   The person edits and sends (copy + open LinkedIn), which starts a conversation or a GTN track. Edits and
   skips are stored.
3. **Reply agent (built, ADR-0024).** Replies found in Gmail (and pasted LinkedIn replies via Log with AI) are classified:
   interested, not now, referral, objection, unsubscribe; the agent drafts the answer for approval, and an
   unsubscribe goes straight to do-not-contact.
4. **Email sequences (built, ADR-0025; sending off until the legal review).** Follow-up emails the agent sends from the person's Gmail on a schedule, within caps,
   with an unsubscribe line, a do-not-contact check and consent recorded. Needs its own ADR and a legal review.
5. **Ops agent (built, ADR-0026).** A booked call drafts the opportunity at the account and writes a call-prep brief (the
   research, the conversation so far, questions to ask).
6. **Measured autonomy.** Acceptance and reply rates per message version; a golden set of good messages to test
   prompt changes against; settings that let specific low-risk actions skip approval once the numbers support it
   (for example sending the booking link to someone who said yes).
