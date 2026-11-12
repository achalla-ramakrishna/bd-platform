# ADR-0015: Walnut, a BD agent that plans, asks and drafts; people decide

- Status: Proposed
- Date: 2026-11-12

## Context

The platform records BD work well, but nobody is pushing it forward each day:
follow-ups slip, new accounts wait, accepted LinkedIn connections never get a
first message, and deals stall at the same stage. The BD team (the Admins
today, and any future ones) asked for an agent with a name that chats with them,
says what to do, recommends the top accounts and who to connect with, reminds
them to message once a connection is accepted, and keeps the whole BD life
cycle moving.

## Decision

1. **Walnut** is the BD agent. It works with active users holding any of the
   roles in `app.walnut.roles` (default `ADMIN`), so who it works with is
   configuration, not code.
2. **The BD life cycle** Walnut drives, per account:
   choose (top accounts by fit, nobody talking to them yet, dealt out so two
   people are never sent to the same account) → connect (the person sends a
   LinkedIn connection request; Walnut drafts the note) → first message (once
   accepted; drafted from the account research) → follow up (a nudge after 4
   days without a reply) → meeting (when they reply) → opportunity (after the
   meeting) → pipeline stages. Each open conversation's stage is worked out
   from its latest logged touch and whether the person is now a connection.
3. **Morning plan.** At 08:30 IST on weekdays (`app.walnut.brief-cron`)
   Walnut writes each person's plan into their thread: follow-ups due, the next
   step for each conversation, the top 5 accounts to approach and who to
   connect with there, deals with what is missing to move on, events,
   interviews, the team's last 7 days, and **questions for the person** (did
   X accept? how did the meeting go?). Fridays add a week review. The plan is
   built from the data alone, so it works without an Anthropic key. One plan
   per person per day (a key on person and date), so reruns never repeat it.
4. **Email.** The plan is emailed to people who keep "Email me the morning
   plan" on, through Resend's HTTP API (`MAIL_API_KEY`, `MAIL_FROM`); links
   point to `APP_BASE_URL`. Without the key, the plan is only in the app.
5. **Chat.** People reply in the thread. Claude (ADR-0009 pattern) reads the
   person's BD snapshot and the last 12 messages, answers briefly and proposes
   actions: open a page, a draft to copy, set a conversation's next step, or
   log a touch. The server keeps only actions the page can apply (known app
   paths, existing conversations, valid codes and dates).
6. **People decide.** Walnut never saves, sends or changes anything itself.
   Each action is a button the person presses; saving goes through the usual
   endpoints and rules. Drafts are sent by the person on LinkedIn or email;
   nothing automates LinkedIn (BR-BD-03, ADR-0005).
7. **Threads are stored and private.** Unlike Log with AI (ADR-0013), the
   thread is kept (`walnut_message`, V16) so Walnut remembers the
   conversation and the plans; each person sees only their own thread.

## Alternatives considered

- **Slack or WhatsApp:** where people already are, but each needs an app,
  credentials and its own permissions; the in-app chat plus email covers the
  need first.
- **Let Walnut save routine updates itself:** faster, but a wrong guess would
  change the pipeline unseen; one click per update is cheap.
- **Plan written by Claude each morning:** richer wording, but it fails when
  the key or the API is down, and costs a call per person per day; the
  data-built plan is reliable, and chat adds judgement when asked.

## Consequences

- The team gets one place to start the day and a nudge that does not depend
  on remembering to open the app.
- Stage detection depends on touches being logged with the right channel
  (LINKEDIN_CONNECTION for requests); Log with AI and Walnut's own LOG_UPDATE
  actions make that easy.
- Email content (names, accounts, deal values) goes to Resend for delivery.
- The thread grows with one plan a day per person; old messages can be
  pruned later if needed.
