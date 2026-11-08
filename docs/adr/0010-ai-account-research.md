# ADR-0010: AI account research with web search

- Status: Proposed
- Date: 2026-11-08

## Context

For each target GCC the team needs to know what the centre does, whether it is
likely to buy engineering help now, which CodeWalnut offer fits, who to talk
to and what to say first. Doing this by hand takes 30 to 60 minutes an account.

## Decision

1. **Research this account** on the account page starts a background run (one
   or two minutes); the newest run is shown, and every run is kept.
2. Two Claude calls (`claude-opus-5-5`):
   - Web research with Anthropic's server-side web search tool (at most 8
     searches), written up as notes with a source address per point.
   - A structured summary that combines those notes with our records:
     overview, facts and buying signals each with a source, likely needs,
     recommended CodeWalnut offers, who to contact (matched to known
     contacts), a LinkedIn note of at most 280 characters, talking points,
     and what to check.
3. Our records sent to Claude are limited to: the account's details, contact
   names, titles and personas, recent activity summaries, opportunity names
   and needs, roles deployed there, skills across our engineers, and client
   names **only where permission to name them is recorded** (BR-BD-11). No
   rates, contract values or engineer names are sent.
4. The prompt forbids facts not in the notes or records, and asks Claude to
   list gaps under "check before relying on it".
5. Nothing is sent to the prospect. The note is copied, edited and sent by a
   person, and logged as an activity (BR-BD-03, BR-BD-04).
6. On only when `ANTHROPIC_API_KEY` is set; Delivery can read results but not
   start runs. Server-side refusal fallback is on.

## Alternatives considered

- **One call with search and structured output together**: fewer calls, but
  harder to keep sources attached and to recover from a paused search.
- **Paid company-intelligence data** (Zinnov, Crunchbase): more structured,
  but costly; can feed the same summary later.

## Consequences

- Each run costs roughly ₹20 to ₹80 of API usage (model tokens plus searches).
- Results reflect what is public and may be out of date or wrong; the brief
  shows sources and its own cautions so people can check.
- A run interrupted by a restart is marked failed after 15 minutes.
