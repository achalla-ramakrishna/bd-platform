# ADR-0003: One deployable, modular inside

- Status: Proposed
- Date: 2026-11-05

## Context

About 10 users and a few thousand records (NFR-BD-06, NFR-BD-10). The domain
has clear areas (accounts, opportunities, talent, contracts and others) that
should not tangle as the application grows.

## Decision

1. One Spring Boot application, one MySQL database, one container image.
2. Code is split into modules by package (`accounts`, `contacts`,
   `engagement`, `opportunities`, `talent`, `contracts`, `imports`,
   `reporting`, `admin`). Each module owns its tables and exposes a Java API;
   other modules use that API, never its tables or internal classes.
3. Spring Modulith verifies the boundaries in a test that fails the build.
4. `reporting` may read across modules through read-only database views,
   since it only produces dashboards and exports.

## Alternatives considered

- **Microservices** (as on nPOS): independent scaling and deployment, which
  this workload does not need, at the cost of messaging, distributed
  transactions and more infrastructure.
- **No module boundaries**: fastest at first, hardest to change later.

## Consequences

- Cheap to run: one container and one managed database.
- Module boundaries let a module move into its own service later if a real
  need appears.
