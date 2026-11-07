# CodeWalnut BD Platform

An internal web application for CodeWalnut's business development: finding and
winning Global Capability Centre (GCC) and enterprise clients, running
engagement across channels, managing the pipeline for staff augmentation and
outsourced projects, and matching engineers to client demand.

> Status: **release 1, slices 1 and 2 built**: Google sign-in, roles,
> spreadsheet upload, accounts, deployed teams, engineers, the opportunities
> pipeline with stage rules, and the activity log. Next: reports and alerts.

## Document map

| Document | What it covers |
|---|---|
| [docs/context.md](docs/context.md) | Business background and the business requirements this repo implements |
| [docs/scope.md](docs/scope.md) | What release 1 delivers, and what comes in later releases |
| [docs/architecture/overview.md](docs/architecture/overview.md) | Architecture, technology stack, modules and deployment |
| [docs/domain-model.md](docs/domain-model.md) | Entities, relationships, lifecycles and invariants |
| [docs/data-model.md](docs/data-model.md) | MySQL schema (Flyway migrations in `backend/`) |
| [docs/api/openapi.yaml](docs/api/openapi.yaml) | REST API contract for release 1 |
| [docs/deployment.md](docs/deployment.md) | Running locally and deploying to Railway |
| [docs/data-import/](docs/data-import/) | How starting data is uploaded, with spreadsheet templates |
| [docs/adr/](docs/adr/) | Architecture decision records |

The business requirements (FR-xxx-nn, BR-BD-nn, NFR-BD-nn) are kept in the
shared document *CodeWalnut BD Platform — Business Requirements*. This
repository refers to them by ID.

## Repository layout

| Folder | Contents |
|---|---|
| `backend/` | Spring Boot application (Java 25): API, sign-in, upload, Flyway migrations |
| `frontend/` | React 19 + TypeScript app, built into the back end's jar |
| `docs/` | Design documents and ADRs |

## How we work

1. Scope: what each release covers.
2. Domain model: entities, lifecycles, invariants.
3. Decisions: an ADR for every choice that is costly to reverse.
4. Contracts: OpenAPI for the REST API.
5. Data model: MySQL schema and Flyway migrations.
6. Screens: the React screens each release needs.
7. Implementation, in small, tested slices.

Each step is reviewed before the next one starts.

## Data protection

This repository must never contain real client, contact, contract or engineer
data. Templates contain column headings only. See [CLAUDE.md](CLAUDE.md).
