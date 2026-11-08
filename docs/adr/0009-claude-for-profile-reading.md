# ADR-0009: Claude reads pasted LinkedIn profiles; people decide and save

- Status: Proposed
- Date: 2026-11-08

## Context

The team finds GCC contacts on LinkedIn and wants to add them without retyping.
LinkedIn offers no API that a custom application can use to read prospect
profiles, and scraping or automating LinkedIn breaks its terms (ADR-0005).

## Decision

1. **Add from LinkedIn** on an account page: the user pastes the profile
   address and either the copied profile text or a screenshot of what they
   are looking at.
2. The application sends that pasted material to Claude (model
   `claude-opus-5-5`, Anthropic API) with structured outputs, and gets back
   suggested fields: name, title, company, city, persona with a reason, and
   short outreach notes. Nothing is saved at this step.
3. The user checks the fields and saves. The contact's source is recorded
   (FR-CON-06); the default consent basis is "business contact".
4. The feature is on only when `ANTHROPIC_API_KEY` is set. Without it, the
   same form is filled in by hand.
5. Requests use the server-side refusal fallback (`fallbacks: "default"`), so
   a rare safety refusal retries on a suitable model instead of failing.
6. The application never fetches LinkedIn pages, never stores the pasted
   text or screenshot, and never sends anything to LinkedIn.

## Alternatives considered

- **Browser extension that reads the open profile**: faster, but it is the
  automated collection LinkedIn's terms forbid.
- **Contact data providers** (Apollo, Lusha, ZoomInfo): licensed bulk data;
  a later option for whole target accounts, through the existing upload.
- **Manual entry only**: slow, and typos in names and titles.

## Consequences

- Pasted profile content is processed by Anthropic as a data processor.
  Only business profile information should be pasted.
- Each read costs a few rupees of API usage.
- Accuracy depends on what was pasted; the user stays responsible for the
  saved record.
