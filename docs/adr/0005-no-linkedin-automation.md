# ADR-0005: No LinkedIn automation or scraping

- Status: Proposed
- Date: 2026-11-05

## Context

LinkedIn is CodeWalnut's main prospecting channel. LinkedIn's User Agreement
forbids scraping and automated actions, and accounts that use such tools risk
restriction. Sales Navigator's CRM sync is available only to LinkedIn's CRM
partners, which a custom application is not (ADR-0001).

## Decision

1. The application never sends connection requests, messages or InMail, and
   never reads LinkedIn pages, through any API, browser extension or headless
   browser (BR-BD-03).
2. People act on LinkedIn and log what they did as activities (FR-ENG-01).
3. The application stores a contact's LinkedIn profile URL and opens it in a
   new tab; that is the only link.
4. If LinkedIn later offers an API open to this kind of application, a new ADR
   will consider it.

## Alternatives considered

- **Automation tools** (connection and message bots): against LinkedIn's terms,
  and they put the team's accounts at risk.
- **Scraping profiles into contacts**: against LinkedIn's terms and raises data
  protection issues under India's DPDP Act.

## Consequences

- Logging LinkedIn activity must be quick: a few clicks from a contact or from
  the daily task list.
- Contact data comes from people, uploads or licensed providers, with its
  source recorded (FR-CON-06).
- The same applies to job boards such as Naukri (added 2026-11-10): open-role
  counts and hiring signals are gathered by people or from public pages, with
  the source recorded, never by bots or scraping.
