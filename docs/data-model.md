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
| walnut | V16 | `walnut_message` (each person's thread with Walnut, private), `walnut_brief` (one plan per person per day, emailed or not), `walnut_settings` (email the plan or not) |
| opportunities, talent | V17 | `opportunity_requirement` gains `received_on`, `notes`, `created_at`; `submission` takes external candidates (`candidate_name`, `candidate_source`, `engineer_id` optional), the stages Onboarding and Withdrawn, `status_on`, `interview_on`, `start_on`, `proposed_by`, `version` (ADR-0016) |
| improvements | V12 | `improvement` (status, build-queue rank, decision and delivery) |
| engagement | V13 | `bd_event`, `bd_event_attendee`; `conversation.event_id` and `connection.met_at_event_id` record who we met where |
| engagement | V14 | `interview` (leader interviews and their stage dates) |
| strategy | V9 | `strategy_version` (every save kept; the newest is current) |
| contacts, network | V18 | GTN (ADR-0017, ADR-0018): `connection` gains `whatsapp` (digits with country code) and `source_tag`; `network_track` (one per person: stage, Why? reason, next action and date, nudges sent, revisit date and reason, beep interval, call date, owner), `network_stage_change` (every move; no user for the daily sweep), `network_touch` (channel, direction, kind, template, summary), `network_commitment`, `message_template` (seeded with starter templates) |
| network | V19 | GTN profile pages (ADR-0019): `profile_page` (one per team member), `profile_video_chunk` (the intro video in 1 MB pieces), `profile_link` (one unguessable token per person), `profile_event` (visit, video play and progress, clicks); `network_track` gains `visited_at`, `best_video_pct`, `interested_at`, `ask_now`; touches gain the kind `VISIT` |
| google, network | V20 | GTN phase 3 (ADR-0020): `google_account` (each person's own connection; tokens encrypted with AES-GCM), `network_touch` gains `gmail_thread_id`, `gmail_message_id` (unique), `rfc_message_id`; `network_track` gains `reply_check`, `call_at`, `calendar_event_id`; `profile_page` gains `booking_mode` (LINK or CALENDAR), `slot_minutes`, `day_start`, `day_end`; page events gain `BOOKED` |
| network | V21 | GTN team use (ADR-0021): `network_touch.due_on`, when a nudge or beep was due, so "sent on time" can be measured |
| assist | V22 | BD agent research (ADR-0022): `prospect_brief`, one row per research run on a contact or connection: status, the brief and sources (JSON), role fit, timing, access, score, who asked |
| prospecting | V23 | The daily prospecting queue (ADR-0023): `prospect_queue_item`, one row per person queued for one BD person on one day: the brief it came from, score, the agent's draft and the note actually sent, status (QUEUED, SENT, SKIPPED, EXPIRED), skip reason, and the conversation or GTN track it started |

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
