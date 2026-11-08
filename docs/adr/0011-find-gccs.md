# ADR-0011: Find GCCs with web search; people choose what to add

- Status: Proposed
- Date: 2026-11-08

## Context

India has more than 2,000 GCCs (Zinnov-Nasscom FY26). The starter list held
98. The team needs the rest, with sources, without buying a database first.

## Decision

1. **Find GCCs** page: choose a city and, optionally, an industry. A background
   run asks Claude (`claude-opus-5-5`, web search, at most 10 searches) for up
   to 25 GCCs there, leaving out companies we already have in that city and IT
   services firms, each with a source address. A second call turns the notes
   into a structured list; entries without a source are dropped.
2. The results are kept. Each candidate shows whether it is already an
   account (website domain plus city, BR-BD-01) and whether its domain is
   usable.
3. People open the sources, tick the companies worth pursuing, and add them as
   prospects owned by themselves, with the source recorded.
4. A purchased directory (for example Zinnov-Nasscom) can still be loaded
   through the accounts upload; Find GCCs then fills gaps.

## Alternatives considered

- **Lists from model memory**: fast but unsourced, and wrong entries are hard
  to spot.
- **Buying a directory only**: most complete, at a cost; compatible with this.

## Consequences

- Each search costs roughly ₹20 to ₹80.
- Coverage grows city by city and industry by industry; duplicates are
  prevented by the account key.
- With thousands of accounts, the fit score (FR-ACC-04) orders the work. Its
  weights are configuration (`app.fit.*`), with defaults that favour mid-size
  centres, technology we know, buying signals, decision-maker contacts, recent
  replies and industries where we already have clients.
