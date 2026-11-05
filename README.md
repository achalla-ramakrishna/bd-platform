# CodeWalnut BD Platform

An internal web application for CodeWalnut's business development: finding and
winning Global Capability Centre (GCC) and enterprise clients, running
engagement across channels, managing the pipeline for staff augmentation and
outsourced projects, and matching engineers to client demand.

> Status: **design phase**. No application code yet. The design is agreed step
> by step, and implementation starts once it is reviewed.

## Document map

| Document | What it covers |
|---|---|
| [docs/context.md](docs/context.md) | Business background and the business requirements this repo implements |
| [docs/scope.md](docs/scope.md) | What release 1 delivers, and what comes in later releases |
| [docs/architecture/overview.md](docs/architecture/overview.md) | Architecture, technology stack, modules and deployment |
| [docs/domain-model.md](docs/domain-model.md) | Entities, relationships, lifecycles and invariants |
| [docs/data-import/](docs/data-import/) | How starting data is uploaded, with spreadsheet templates |
| [docs/adr/](docs/adr/) | Architecture decision records |

The business requirements (FR-xxx-nn, BR-BD-nn, NFR-BD-nn) are kept in the
shared document *CodeWalnut BD Platform — Business Requirements*. This
repository refers to them by ID.

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
