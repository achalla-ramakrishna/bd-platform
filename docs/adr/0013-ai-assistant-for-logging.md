# ADR-0013: An AI assistant logs BD work from notes and screenshots

- Status: Proposed
- Date: 2026-11-10

## Context

People talk to leaders on LinkedIn, by email, by phone and at events, and then
have to log each touch by hand: find the conversation, pick the channel and
outcome, write a summary, set a next step. Much of this is skipped, so the
conversations page and the heatmap under-count the work. The team asked for "a
chat like ChatGPT" where they can type what they did, or paste a screenshot of
the thread, and have the conversation or connection added or updated.

## Decision

1. A **Log with AI** page (under Network) holds a chat. People type a note
   and/or add up to 4 screenshots (PNG, JPEG, WebP or GIF, 5 MB each) by
   pasting, dropping or choosing files.
2. `POST /api/v1/assistant/read` sends the note and screenshots to Claude
   (ADR-0009 pattern: structured output, server-side fallback). Claude returns
   who the touch was with (name, title, company, city), the topic, channel,
   date, a neutral summary, the outcome and a next step with a due date, using
   the app's channel and outcome codes. Any other code is replaced by a default.
3. Claude also says what the material is mainly about: a touch with a person,
   a conference or event (an invitation, a registration, an agenda), or a
   leader interview (asked, agreed, booked, recorded, published), an account
   (a GCC centre and its facts) or an opportunity (a client need or deal), and
   fills in the details with the app's codes and the configured stages.
4. The server matches the reading to open conversations and connections by the
   person's name, to accounts by company, to conferences by name and to
   interviews by the leader's name, and suggests one action: the conference or
   interview when that is what it is about; otherwise update the open
   conversation, start a conversation at the matched account, or add a
   connection.
5. The page shows editable cards for **Update conversation**, **Start
   conversation**, **Add / Update connection**, **Add / Update conference**
   (with "I'm going") and **Add / Update interview** (moving it one stage at a
   time to the stage read, as the interview rules require), **Add / Update
   account** (a new centre, keyed by website domain and city as in BR-BD-01, or
   the year formed, size and open roles of one we have) and **Add / Update
   opportunity** (moved to the stage read; the stage rules still apply, and
   what is missing is shown with a link), prefilled from the reading.
   Nothing is saved until the person presses the card's button, and saving
   uses the existing endpoints with their rules, roles and optimistic locking.
6. Nothing from the chat is stored: not the note, the screenshots or Claude's
   reply. The chat lives in the browser tab and is gone on reload. Only what
   the person saves is kept, as with any other update.
7. Only pipeline roles may use it. Without `ANTHROPIC_API_KEY` the page says
   it is switched off and points to the manual forms.

## Alternatives considered

- **Read LinkedIn messages directly**: automates LinkedIn and is ruled out by
  BR-BD-03 and ADR-0005. People take the screenshot themselves; the app reads
  only what they give it.
- **Save straight away without confirmation**: fast, but a misread name or
  date would land on the wrong conversation. One click to confirm is cheap.
- **Keep the chat history on the server**: would store third-party message
  content we do not need; the saved updates are the record.

## Consequences

- Logging a touch takes a paste and a click, so more of the team's work shows
  on the conversations page and the heatmap.
- Screenshots and notes may contain messages written by people outside
  CodeWalnut. They are sent to Anthropic to be read and are not kept by the
  app; people should crop out anything not needed.
- Matching is by name, so two people with the same name both appear; the
  person chooses. A company not yet in Accounts cannot start a conversation;
  the card says so and offers a connection instead.
