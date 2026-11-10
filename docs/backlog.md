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

## 3. Conversations (Proposed)

Who we spoke to, at which account, when, on which channel, what was said, the
next step and its date, and a status (started, replied, meeting booked,
opportunity, gone quiet). A "next steps due" list. Converts to an opportunity.
Builds on the activity log rather than adding a second one.

## 4. Connections (Proposed)

People the team personally knows: who knows them and how well, their current
company and role, a profile link typed in by hand, and an optional link to an
account to show warm paths (the `warm_path` table from V2 is the starting
point). Connection growth over time. A connection can become a contact.

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
