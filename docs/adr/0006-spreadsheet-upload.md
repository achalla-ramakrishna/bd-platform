# ADR-0006: Starting data through validated spreadsheet upload

- Status: Proposed
- Date: 2026-11-05

## Context

No system holds CodeWalnut's clients, contracts, contacts or deployed
engineers today; the data is in spreadsheets. All of it must be in the
application on day one (FR-EXI-01 to FR-EXI-05), and target account lists
will keep arriving as spreadsheets (FR-ACC-03).

## Decision

1. Users upload one file per entity type, using the templates in
   [docs/data-import](../data-import/README.md): accounts, contacts, contracts,
   engineers, deployments. CSV and XLSX are accepted.
2. An upload runs in two stages:
   - **Validate**: every row is checked (required fields, formats, picklist
     values, references to other records, duplicates). Nothing is saved. The
     user sees a summary and every failing row with the reason.
   - **Commit**: only when no row fails. All rows are saved in one transaction;
     a file is either fully loaded or not loaded at all.
3. Rows are matched to existing records by natural keys (account: website
   domain + city; contract: contract reference; engineer: employee code). A
   matching row updates the record; a new key creates one. Uploading the same
   file twice changes nothing.
4. Files load in dependency order: accounts, then contacts and contracts, then
   engineers, then deployments. A row that refers to a record not yet loaded
   fails validation.
5. Every upload is recorded as an import batch: who, when, file name, counts,
   and the records it created or changed, so it can be audited.
6. Uploaded files are not kept after commit; only the resulting records and the
   batch summary are.

## Alternatives considered

- **Manual entry only**: too slow for the starting data.
- **Load valid rows and skip failing ones**: leaves half-loaded data that is
  hard to fix. Rejected in favour of all-or-nothing per file.
- **One workbook with all entities**: harder to validate and to fix.

## Consequences

- People must clean their spreadsheets into the templates; the validation
  report tells them exactly what to fix.
- The same mechanism serves later bulk loads, such as new target account
  lists.
- Upload is restricted to Admin, BD lead and Delivery roles; engineer and rate
  data only to roles allowed to see it (FR-EXI-08).
