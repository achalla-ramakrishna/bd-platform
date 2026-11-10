# Data model (MySQL)

Status: **draft for review**. The schema for release 1, implementing
[domain-model.md](domain-model.md). The Flyway migrations in
`backend/src/main/resources/db/migration/` are the source of truth; this page
explains them.

## Conventions

- InnoDB, `utf8mb4`, `BIGINT AUTO_INCREMENT` primary keys.
- Money is `DECIMAL`, always INR, in columns ending `_inr`.
- Picklist values stored as upper-case codes, checked by `CHECK` constraints
  where the list is fixed in code, or held in `picklist_value` where admins
  configure them (FR-ADM-02).
- Editable tables have `created_at`, `updated_at` and a `version` column for
  optimistic locking.
- Every write also adds a row to `audit_event` (NFR-BD-03).

## Tables by module

| Module | Migration | Tables |
|---|---|---|
| admin | V1 | `app_user`, `user_role`, `picklist_value`, `opportunity_stage`, `seniority_rule`, `audit_event` |
| accounts, contacts | V2 | `parent_company`, `account`, `contact`, `warm_path` |
| talent, contracts | V3 | `engineer`, `engineer_skill`, `contract`, `deployment` |
| engagement, opportunities | V4 | `activity`, `opportunity`, `opportunity_stage_history`, `opportunity_requirement`, `submission`, `deployment_request` |
| imports | V5 | `import_batch`, `import_row_error` |
| assist | V6, V7 | `account_research`, `gcc_discovery` |
| accounts | V8 | `account` gains `employee_count`, `open_roles`, `open_roles_as_of`, `open_roles_source` (backlog item 1) |
| engagement | V10 | `conversation`; `activity.conversation_id` links updates to their conversation |
| contacts | V11 | `connection`, `connection_knower` (who in the team knows each person, how well, since when); replaces the unused `warm_path` |
| contacts | V15 | `connection_photo`: one small JPEG per connection (ADR-0014), removed with the connection |
| improvements | V12 | `improvement` (status, build-queue rank, decision and delivery) |
| engagement | V13 | `bd_event`, `bd_event_attendee`; `conversation.event_id` and `connection.met_at_event_id` record who we met where |
| engagement | V14 | `interview` (leader interviews and their stage dates) |
| strategy | V9 | `strategy_version` (every save kept; the newest is current) |

## Keys that carry business rules

| Rule | Where |
|---|---|
| BR-BD-01: one account per centre | `uq_account_domain_city` on `account (website_domain, city)` |
| BR-BD-02: one owner per account | `account.owner_user_id NOT NULL` |
| BR-BD-14: allocation limits | `seniority_rule.max_concurrent_clients` (Junior 1, Mid 1, Senior 2, Lead 2, Principal 2) and `deployment.allocation_percent`; checked in code across overlapping deployments |
| FR-OPP-04: configurable stages | `opportunity_stage`, seeded Lead to Lost with default probabilities |
| ADR-0006: re-uploads update, not duplicate | Natural keys: `account (website_domain, city)`, `contact (account_id, full_name)`, `contract.contract_ref`, `engineer.employee_code`, `deployment (engineer_id, account_id, start_date)` |

## Restricted columns (FR-EXI-08)

`contract.total_value_inr`, `deployment.bill_rate_inr` and engineers' personal
details are returned by the API only to Leadership, BD lead, Delivery and
Admin. The database does not enforce this; the API does.

## Open points

- The default probabilities per stage (5, 20, 35, 50, 75, 100, 0) are a
  starting proposal; they can be changed in the `opportunity_stage` table.
