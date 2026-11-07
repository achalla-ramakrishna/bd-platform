# ADR-0008: Data access with Spring JDBC, not JPA

- Status: Proposed
- Date: 2026-11-07
- Supersedes: the "Spring Data JPA" part of ADR-0002

## Context

ADR-0002 named Spring Data JPA. Release 1's heaviest work is the spreadsheet
upload (ADR-0006): validate thousands of rows, then upsert by natural keys in
one transaction. The read side is mostly list and detail views that join
several modules' tables (the reporting module, ADR-0003).

## Decision

1. Use Spring's `JdbcClient` with hand-written SQL for all data access.
2. Each module keeps its SQL in its own registry class; other modules call
   that class, never its tables (ADR-0003). The reporting module may read
   across tables.
3. Flyway migrations stay the single source of truth for the schema.

## Alternatives considered

- **Spring Data JPA / Hibernate**: less SQL for simple CRUD, but upserts by
  natural key, JSON columns and cross-module read views all need custom
  queries anyway, and an entity layer adds mapping and lazy-loading pitfalls.
- **jOOQ**: type-safe SQL, but code generation adds build steps for a small team.

## Consequences

- Queries are explicit and easy to review; no hidden queries.
- Simple create and update screens in later releases need a little more code
  than JPA would.
- Optimistic locking uses the `version` column in `UPDATE ... WHERE` clauses.
