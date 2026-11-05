# CLAUDE.md

Guidance for Claude Code sessions in this repository.

- Start with `README.md` for the document map, then `docs/context.md`.
- The design is agreed step by step (scope, domain model, ADRs, contracts, data
  model, screens). Do not write implementation code for a feature until its
  design in `docs/` is agreed.
- Refer to requirements by their IDs from the business requirements document:
  FR-xxx-nn (functional), BR-BD-nn (business rules), NFR-BD-nn (non-functional),
  OBJ-nn (objectives).
- Record decisions that are costly to reverse as ADRs in `docs/adr/`, numbered
  sequentially, in the format of the existing records.
- Target stack: Java 25, Spring Boot 4, React 19 + TypeScript, MySQL 8.4,
  Flyway, one deployable (a modular monolith), hosted on Railway. See
  ADR-0002, ADR-0003 and ADR-0007.
- All amounts are in INR. Bill rates can be monthly, daily or hourly.
- **Never commit real data.** No client names, contact details, contract
  values, rates or engineer personal data in code, tests, fixtures, docs or
  commit messages. Use obviously fictional examples (`example-gcc.test`,
  "Engineer A"). The repository may be public.
- Never add code that automates LinkedIn actions or scrapes LinkedIn
  (BR-BD-03, ADR-0005).
- Treat behaviour that varies by client or team as configuration, not as
  code branches.
