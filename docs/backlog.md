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

## 5. Improvement areas (Proposed)

Cards for improving the platform: Suggested, Approved, In progress, Done.
Admins approve. Approved cards are the next work to build and link to the
change that delivered them.

## 6. Conferences and events (Proposed)

Webinars, roundtables, meetups and public conferences, with our role
(watching, signed up, attending, presenting, stall, organising), and the
people met linked to connections or conversations. Includes our own AI
roundtable for engineering leaders.

## 7. Activity heatmap (Proposed)

A grid with one square per day, darker for more conversations, meetings,
events and posts; per person and for the team; hover shows what happened.
Needs items 3 and 6 first.

## 8. Leader interviews (Proposed)

GCC leaders to interview for the blog, podcast or articles, with status
(asked, booked, recorded, published) and the published link.

## Outside the platform

- Vendor kit: one-page company write-up, 3 to 5 case studies, engagement
  models, sample profiles, references, rate card and proposal template.
- Move the repository to the CodeWalnut GitHub organisation.
- Bulk "research next 10 or 25" once a valid Anthropic API key is set.
