# Architecture decision records

Each record states one decision that is costly to reverse: the context, the
decision, the alternatives considered, and the consequences. Records are never
edited after they are accepted; a later record supersedes them instead.

Status values: **Proposed** (under review), **Accepted**, **Superseded by ADR-nnnn**.

| ADR | Title | Status |
|---|---|---|
| [0001](0001-custom-build.md) | Build a custom application rather than buy a CRM | Accepted |
| [0002](0002-technology-stack.md) | Technology stack: React + Spring Boot + MySQL | Accepted |
| [0003](0003-modular-monolith.md) | One deployable, modular inside | Proposed |
| [0004](0004-google-workspace-sign-in.md) | Google Workspace sign-in, roles held in the application | Proposed |
| [0005](0005-no-linkedin-automation.md) | No LinkedIn automation or scraping | Proposed |
| [0006](0006-spreadsheet-upload.md) | Starting data through validated spreadsheet upload | Proposed |
| [0007](0007-hosting-on-railway.md) | Host on Railway for now | Accepted |
| [0008](0008-data-access-with-jdbc.md) | Data access with Spring JDBC, not JPA | Proposed |
| [0009](0009-claude-for-profile-reading.md) | Claude reads pasted LinkedIn profiles; people decide and save | Proposed |
| [0010](0010-ai-account-research.md) | AI account research with web search | Proposed |
| [0011](0011-find-gccs.md) | Find GCCs with web search; people choose what to add | Proposed |
| [0012](0012-research-fit-rating.md) | Research fit rating counts towards the fit score | Proposed |
| [0013](0013-ai-assistant-for-logging.md) | An AI assistant logs BD work from notes and screenshots | Proposed |
| [0014](0014-connection-photos.md) | Connection photos, cropped from the team member's own screenshots | Proposed |
| [0015](0015-walnut-bd-agent.md) | Walnut, a BD agent that plans, asks and drafts; people decide | Proposed |

Template: Context → Decision → Alternatives considered → Consequences.
