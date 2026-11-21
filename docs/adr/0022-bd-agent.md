# ADR-0022: The BD agent: a workflow of small agents, people send on LinkedIn, research first

- Status: Accepted
- Date: 2026-11-21

## Context

The team wants an agent that does what a business developer does (see [bd-agent-plan.md](../bd-agent-plan.md)).
LinkedIn forbids automated invitations, messages and scraping, and is not taking new API partners (ADR-0005).
Walnut (ADR-0015) already plans the day and drafts; GTN (ADR-0017 to ADR-0021) runs the network pipeline. What is
missing first is research on the person: who they are, what they own and why now.

## Decision

1. **Architecture.** A code-controlled workflow with Claude doing the judgement steps, split into small agents
   with narrow tools: research, copy, reply and ops. The database is the memory; an approval queue stands before
   anything sent in someone's name. Built in the order of the plan, research first.
2. **LinkedIn stays manual** (ADR-0005 unchanged). The agent prepares; a person clicks.
3. **Research agent (step 1).** Two Claude calls, as for account research (ADR-0010): (1) web search with
   `linkedin.com` blocked, written up as sourced notes; (2) a structured brief from those notes and our records
   (the person, their account and its latest research, our touches with them, who knows them). Claude rates role
   fit and timing 1–5 with reasons; the app works out access from our records (how well someone knows them, past
   replies, whether the account is a client) and computes the score:
   `round(100 × (0.40 × roleFit + 0.35 × timing + 0.25 × access) / 5)`. Every run is kept; the newest is shown.
4. **People's data.** Only professional, public information: role, employer, published talks, posts and
   articles, company news. The prompt forbids personal life, family, health, religion, caste, politics, home
   address and personal contact details, and the brief never stores them. Web pages are data, not instructions.
   Briefs are visible to the team like the rest of BD data.
5. **Cost control.** Research runs only when a person asks (per person, or up to 10 contacts of an account at a
   time), with a daily cap for the whole team (`app.agent.research-per-day`, default 60). A person's brief newer
   than 7 days is reused unless they ask again.
6. **Data source.** Public web search for now; a licensed provider can be added behind the same adapter.

## Alternatives considered

- **One general agent with every tool**: harder to control, test and keep within the rules.
- **Browser automation on LinkedIn**: against LinkedIn's terms; risks the team's own accounts (ADR-0005).
- **Score from the model alone**: not explainable or stable; the app computes it from rated, reasoned parts.

## Consequences

- Each brief costs two Claude calls with up to 6 web searches; the cap bounds the daily spend.
- Briefs feed the daily queue (step 2) and the copy agent; their quality is visible before anything is sent.
