# Backlog

Features agreed in the BD working session of 2026-11-10, in build order. Each
item gets a short design in `docs/` (scope, data, screens) that is agreed
before it is built, as `CLAUDE.md` asks. Status: Proposed, Agreed, Built.

The strategy behind them: **go to network, not go to market.** Turn strangers
into connections, give them value, stay in their minds, then ask for a chance.
Within GCCs, aim at young (formed in the last two years), growing centres,
favour micro and mid-size ones, stay within React, Java and AI enablement, and
lead with travel and logistics, and banking (especially lending and
mortgages), where we already have delivery references.

## 1. Accounts: targeting columns, filters and scoring (Built)

- Columns: year formed, employees (exact count, or the band), open
  engineering roles with the date counted and where.
- Filters: city, formed in the last 2 or 5 years, size band (micro under 500
  up to 5,000+), industry, tech stack (Java, React).
- Sorts: best fit, newest GCC first, smallest first, most open roles, name.
- Fit score: +10 for a centre formed in the last 2 years (configurable);
  contact and engagement points drop from 15 to 10 so the defaults still add
  up to 100. Size uses the exact count when no band is recorded.
- Filled by: the account page (Edit profile), the accounts spreadsheet
  (optional columns `employee_count`, `open_roles`, `open_roles_as_of`,
  `open_roles_source`), or research uploads (`accountFacts`).
- Design notes: blank spreadsheet cells and missing research fields keep the
  stored value; the edit form can clear a field. Open roles are counted by a
  person or from public pages, never by automated scraping of job boards
  (ADR-0005 applies to job boards as it does to LinkedIn).

## 2. Strategy page (Built)

The agreed strategy in one page, first in the menu, so nobody has to look
elsewhere. Written in Markdown with a live preview; everyone reads it,
leadership and admins edit it. Every save is kept as a version with an
optional change note; the history shows who changed what, any version can be
viewed or used as the starting point for an edit, and a save based on an
out-of-date version is refused rather than overwriting someone else's change.
The starting text comes from the 2026-11-10 session; client names are added
in the app, not in the repository.

## 3. Conversations (Built)

A conversation is a thread with one person at an account (a contact, or just a
typed name when they are not a contact yet): a topic, an owner, a status
(started, replied, meeting booked, opportunity, gone quiet, closed), the next
step with its due date, and an optional linked opportunity.

- Started with its first touch; each later update is an activity in the
  existing activity log (so account activity and the fit score keep working),
  and moves the status (a reply, a meeting or "not interested" set it unless
  chosen by hand) and the next step.
- The Conversations page opens on what is due within 7 days, with cards for
  overdue next steps, due this week, open conversations, meetings booked,
  conversations that became opportunities and those started in the last 30
  days; filters for status, "mine only" and search.
- A conversation page shows every update, adds new ones, marks the
  conversation gone quiet, closed or reopened, and links an opportunity at the
  same account (which sets the status to Opportunity).
- Each account page lists its conversations and can start one.
- A next step needs a date so it shows in the due list. Saves based on an
  out-of-date copy are refused. Pipeline roles write; everyone reads.

## 4. Connections (Built)

People the team personally knows, in one shared list:

- Name, company, role, city, LinkedIn profile link and email, typed in by
  hand (nothing is read from LinkedIn, ADR-0005).
- Who knows them: each team member who does, how well (close, know well,
  acquaintance), how, and since when. A second person who knows someone adds
  themselves ("I know them too") instead of creating a duplicate; the
  connection is removed when the last person who knows them leaves it.
- An optional target account, marked "works there" or "can introduce us".
  Account pages show these as warm paths.
- From a connection: start a conversation at the linked account, or, for
  someone who works there, make a contact (source "Connection of ...").
- The page shows connections, known by me, warm paths into accounts, added
  in 30 days and added this week against last week, with filters for
  linked, closeness and "known by me". The weekly counts for the last 12
  weeks are in the API for the heatmap (item 7).
- The `connection` and `connection_knower` tables (V11) replace the unused
  `warm_path` table from V2.

## 5. Improvement areas (Built)

The tool is the source of improvement knowledge. A board of cards with four
columns (Suggested, Approved, In progress, Done) and a collapsed Declined
list.

- Anyone signed in suggests a card: a title and "what and why" in Markdown.
  The person who suggested it can edit it until it is decided.
- Leadership and admins approve, decline (with a reason the suggester sees),
  start building, mark done (with an optional link to the change that
  delivered it) or send a card back. Only sensible moves are allowed.
- Approved cards form the build queue: each gets a place at the end, and
  approvers move them up or down. `GET /api/v1/improvements?status=APPROVED`
  returns the queue in order, which is what a coding assistant picks the next
  change from.
- Saves based on an out-of-date copy are refused (V12 `improvement`).

## 6. Conferences and events (Built)

A radar of webinars, roundtables, meetups and conferences (V13 `bd_event`):

- Each event has its kind, dates, city or online, link, organiser, our role
  (watching, signed up, attending, presenting, stall, organising), notes on
  why we go and, afterwards, how it went. Our own AI roundtable for
  engineering leaders is an event we organise.
- Anyone can say "I'm going" (or "I went"); pipeline roles add and edit
  events.
- People met: from an event page, log a conversation (account first, channel
  Event, linked to the event) or add a connection (marked "met at"). The
  event page lists both, and conversations and connections link back.
- The page shows upcoming, past or all events, filters by role, kind and
  "I'm going", and cards for the next 30 days, events where we present,
  organise or have a stall, this quarter, and people met in the last 90
  days.

## 7. Activity heatmap (Built)

A GitHub-style grid with one square per day for the last 12, 26 or 52 weeks,
for the whole team or one person:

- A day counts every logged touch (including conversation updates), every
  new connection (for the team, the first day anyone connected) and every
  event someone from the team went to. Meetings and conversations started
  show in the breakdown.
- Fixed levels (1, 2 to 3, 4 to 6, 7 or more a day), so the grid darkens as
  the team does more over the months rather than relative to its busiest
  day. One green ramp, checked with the palette validator against the light
  and dark surfaces; empty days use the neutral line colour.
- Hover shows the day's count and breakdown; clicking a day (or moving with
  the arrow keys) lists what happened with links. Cards show this week
  against last week, active days in the last 30, the best week and the
  period total. "Show as a table" gives the same data week by week.
- LinkedIn posts are not counted: nothing is read from LinkedIn (ADR-0005).
  Log a post as an activity if it should count.

## 8. Leader interviews (Built)

"I'm reaching out to interview you and publish it where AI engineering
leaders are featured", not "can I supply you engineers" (V14 `interview`):

- Each interview: the leader, their title and company, an optional account,
  the topic, the format (blog, podcast, video, LinkedIn article), an owner and
  notes.
- Stages: idea, asked, agreed, booked, recorded, published, one step at a
  time (forward or back), or declined before recording (and back to idea).
  The asked, booked, recorded and published dates are kept; booking needs
  the date and publishing needs the link.
- The page shows interviews not yet published (or all, or one stage), "mine
  only", and cards for in progress, booked in the next 30 days, recorded but
  not published and published this quarter.
- Recording and publishing an interview count on the activity heatmap.

## 9. Log with AI (Built)

A chat where people type what they did or paste screenshots of a thread
(ADR-0013):

- Claude reads who it was with, the channel, date, summary, outcome and next
  step. The page matches it to open conversations, connections and accounts.
- Editable cards offer to update the conversation, start one, or add or update
  the connection. Nothing is saved until the person presses the button.
- Up to 4 screenshots per message, pasted, dropped or chosen. Nothing from the
  chat is stored; the app reads nothing from LinkedIn.
- Linked from the Conversations and Connections pages.
- Photos (ADR-0014): when a screenshot shows the person's profile photo, the
  card crops it (drag to adjust) and saves it with the connection if ticked.
  Photos can also be added, changed or removed on the connection page.
- A LinkedIn invitation that is not yet accepted is logged as a conversation
  (connection request, no reply yet); add the connection once they accept.

## Outside the platform

- Vendor kit: one-page company write-up, 3 to 5 case studies, engagement
  models, sample profiles, references, rate card and proposal template.
- Move the repository to the CodeWalnut GitHub organisation.
- Bulk "research next 10 or 25" once a valid Anthropic API key is set.
