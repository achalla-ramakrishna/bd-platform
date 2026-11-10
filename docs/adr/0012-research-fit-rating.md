# ADR-0012: Research fit rating counts towards the fit score

- Status: Proposed
- Date: 2026-11-10

## Context

The fit score (FR-ACC-04) adds points for size, technology we know, buying
signals, contacts, recent replies and client industries. Accounts added from
web research rarely have size or technology recorded and have no contacts yet,
so they all score about the same and the ranking does not separate a growing
software centre from a chip lab. Each research brief already judges the fit,
but only in its text.

## Decision

1. Research briefs carry an optional `fitRating`: `STRONG`, `MEDIUM` or `WEAK`.
   - STRONG: the centre builds software, data or AI products and is hiring or
     growing engineering teams.
   - MEDIUM: some software work, but the need for outside engineers is unclear,
     or the evidence is thin.
   - WEAK: mainly hardware, chip, specialist or non-software work, or the
     company is cutting staff.
2. In-app research (ADR-0010) asks Claude for the rating with these rules.
   Uploaded research may include it; any letter case is accepted and any other
   value rejects the upload.
3. The fit score uses the rating in the latest finished research: STRONG +20,
   MEDIUM +10, WEAK 0, configurable under `app.fit.research-fit`. To keep the
   defaults at 100, the caps on technology and buying-signal points drop from
   20 to 10 each. The reason appears with the score ("Research rates the fit
   strong (+20)").

## Alternatives considered

- **A rating column on the account**: one more field to keep in step by hand;
  the rating belongs with the evidence that supports it.
- **Score from buying signals only**: counts signals, not what the centre does,
  so a chip lab with two news items outranks a growing product centre.

## Consequences

- Researched accounts spread out in the ranking; older research without a
  rating scores as before, minus the lower caps, until it is refreshed.
- The rating is a judgement from public sources; the brief's cautions say how
  firm it is, and people can override it by uploading corrected research.
