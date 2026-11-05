# ADR-0001: Build a custom application rather than buy a CRM

- Status: Accepted
- Date: 2026-11-05

## Context

Standard CRMs (HubSpot, Salesforce, Zoho) cover accounts, contacts, email and
a sales pipeline. CodeWalnut's needs go beyond that: GCC targeting with parent
and centre structure, staff augmentation deals made of role-level
requirements, a bench of engineers matched to those requirements, deployments
tied to SOWs, and growth of existing clients from deployment end dates.

## Decision

Build a custom web application. Leadership decided this on 5 November 2026.

## Alternatives considered

- **Buy a CRM and build only the specific parts on top.** Faster for the
  standard parts and gives LinkedIn Sales Navigator sync (open only to partner
  CRMs). Rejected: the specific parts are most of the value, and splitting
  data between a CRM and a custom application doubles the integration work.
- **Spreadsheets.** Current state; no shared view, no alerts, no history.

## Consequences

- CodeWalnut owns the build and running costs, and the data model fits its
  business exactly.
- No Sales Navigator CRM sync; LinkedIn work is logged by people (ADR-0005).
- The application doubles as a demonstration of CodeWalnut's engineering for
  prospective clients.
