# Scope by release

Status: **draft for review**.

Release 1 gets CodeWalnut's current business into the application and gives
the team one place to work accounts and the pipeline. Release 2 adds
structured engagement and selling tools. Release 3 adds AI assistance and
the new AI offers from the 2027–2028 plan.

## Release 1: Foundation

Goal: every client, contract, deployed engineer and target account is in the
application, and the team runs its pipeline there instead of in spreadsheets.

| Area | Requirements | Notes |
|---|---|---|
| Sign-in and roles | FR-ADM-01, NFR-BD-01, NFR-BD-02 | Google Workspace sign-in, six roles |
| Spreadsheet upload | FR-EXI-01 to FR-EXI-05, FR-EXI-07, FR-ACC-03 | Accounts, contacts, contracts, engineers, deployments ([data-import](data-import/README.md)) |
| Accounts | FR-ACC-01, FR-ACC-02, FR-ACC-06, FR-ACC-07 | Parent companies and centres, owner, filters and saved lists |
| Contacts | FR-CON-01, FR-CON-02, FR-CON-04, FR-CON-06 | Personas, warm paths, consent and source |
| Activity log | FR-ENG-01, FR-ENG-07 | Every touch on any channel, logged by people; referral tracking |
| Opportunities | FR-OPP-01 to FR-OPP-07 | Staff augmentation (with requirement lines) and projects; board and table |
| Existing clients | FR-GRO-01, FR-GRO-02, FR-GRO-03, FR-EXI-06, FR-EXI-08 | Deployed teams per client, end-date alerts at 60 days |
| Bench | FR-TAL-01, FR-TAL-03 | Engineer list, availability, proposing engineers to requirements |
| Contracts | FR-PRO-06 | MSAs and SOWs with dates and values |
| Reports | FR-REP-01, FR-REP-02, FR-REP-04, FR-REP-05 | Funnel, forecast, bench utilisation, CSV export |
| Audit | FR-ADM-03, NFR-BD-03 | Who changed what and when |
| Configuration | FR-ADM-02 | Stages, personas, picklists |

Business rules enforced in release 1: BR-BD-01, 02, 03, 07, 08, 10, 11, 12, 13.

## Release 2: Engage and sell

| Area | Requirements |
|---|---|
| Cadences and daily task list | FR-ENG-02, FR-ENG-03, BR-BD-05, BR-BD-06 |
| Gmail and Calendar logging | FR-ENG-04, FR-ENG-05 |
| Events | FR-ENG-06 |
| Fit scoring and buying signals | FR-ACC-04, FR-ACC-05, FR-ACC-08 |
| Buying committee and job changes | FR-CON-03, FR-CON-05 |
| Engineer matching and demand view | FR-TAL-02, FR-TAL-05, FR-AI-05 |
| Rate card, approvals, proposals, case studies | FR-PRO-01 to FR-PRO-05, FR-PRO-08, BR-BD-09 |
| Client health | FR-GRO-04 |
| Activity report | FR-REP-03 |

## Release 3: AI

| Area | Requirements |
|---|---|
| Account summaries, message drafts, call briefs | FR-AI-01 to FR-AI-04, BR-BD-04 |
| AI pod opportunities and outcome pricing | FR-OPP-08, FR-PRO-07 |
| Revenue by offer | FR-REP-06 |
| Thought-leadership planning | FR-ENG-08 |

## Out of scope for all three releases

Invoicing, timesheets, payroll, a client or engineer portal, recruitment of
new engineers, and any automated LinkedIn activity.

## Open questions

1. Confirm which of the six roles the 10 users hold, and whether one person
   can hold several.
2. Confirm the opportunity stages and their exit criteria (FR-OPP-04).
3. Confirm the 60-day end-date alert (FR-GRO-02).
4. Bill rates: monthly only, or also daily and hourly?
5. Which currencies are used in contracts (INR, USD, others)?
