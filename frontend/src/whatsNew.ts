/**
 * What's new: release notes in the app, newest first. Add an entry at the top with every change people will
 * notice (see CLAUDE.md): a plain-words title, one-line summary, the steps to try it, and a link. Never reuse or
 * change an entry's id: it is how "seen" is remembered.
 */
export interface WhatsNewEntry {
  /** Stable and unique, e.g. "2026-11-21-gtn-team". */
  id: string;
  date: string;
  title: string;
  summary: string;
  steps: string[];
  link?: { to: string; label: string };
}

export const WHATS_NEW: WhatsNewEntry[] = [
  {
    id: '2026-11-21-bd-agent-research',
    date: '2026-11-21',
    title: 'BD agent: research a person before you reach out',
    summary: 'The agent reads the public web (never LinkedIn) and our records, then writes who they are, why now, the best angle and a draft connection note, with a 0–100 score. You still send everything yourself.',
    steps: [
      'An account → Contacts → Research contacts (up to 10 at a time), or Brief on one person.',
      'A connection or a GTN contact → Research this person.',
      'Read the sources and cautions, copy the note, edit it and send it yourself on LinkedIn.',
    ],
    link: { to: '/accounts', label: 'Open accounts' },
  },
  {
    id: '2026-11-21-whats-new',
    date: '2026-11-21',
    title: "What's new",
    summary: 'This page. Every change you will notice is listed here, newest first, with how to try it. The menu shows how many you haven\'t seen.',
    steps: ['Platform → What\'s new.'],
  },
  {
    id: '2026-11-21-gtn-team',
    date: '2026-11-21',
    title: 'GTN for the whole team: owners, hand-over, team view',
    summary: 'Each contact has one owner who works it; Leadership, BD leads and admins can work any. Hand contacts to a teammate, see everyone\'s numbers, and see who has overdue actions.',
    steps: [
      'A contact\'s page → Hand over → choose a teammate, add a note. They get an email and see it in their Today.',
      'GTN → List → tick contacts → Hand over selected.',
      'GTN → Team: each person\'s contacts, overdue actions and results. Managers can hand over all of someone\'s contacts.',
      'GTN → Today → choose My Today, Everyone\'s, or a teammate\'s.',
    ],
    link: { to: '/network?tab=team', label: 'Open the Team tab' },
  },
  {
    id: '2026-11-21-gtn-gmail-calendar',
    date: '2026-11-21',
    title: 'GTN: send from Gmail, replies found for you, calls booked on your calendar',
    summary: 'Connect your Google account once. GTN then sends emails from your Gmail, spots replies in those threads, and lets people book your free times from your profile page with a Google Meet link.',
    steps: [
      'GTN → My profile page → Connect Google (needs the admin set-up in the deployment guide).',
      'On a contact, choose Email → Send from Gmail.',
      'Replies appear at the top of Today: say whether they were positive or "not the right time".',
      'My profile page → People book a call → From my free times.',
    ],
    link: { to: '/network?tab=profile', label: 'Open My profile page' },
  },
  {
    id: '2026-11-21-gtn-profile-page',
    date: '2026-11-21',
    title: 'GTN: your profile page, tracked links and "ask now"',
    summary: 'A page with your intro video, proof points and one-tap buttons. Each person gets their own link; when someone who hasn\'t replied opens it, "ask now" goes to the top of Today and you get an email. Plus the GTN dashboard.',
    steps: [
      'GTN → My profile page → upload a short video, write the headline and proof points → Save.',
      'Use {profile_link} in a message template; each person gets their own link.',
      'GTN → Dashboard: response rate, conversion, why people went quiet, page visits.',
    ],
    link: { to: '/network?tab=profile', label: 'Set up your page' },
  },
  {
    id: '2026-11-21-gtn',
    date: '2026-11-21',
    title: 'GTN, Go To Network: work the people who already know us',
    summary: 'A pipeline for your network: first message, reply, intro, call, warm commitment, monthly beep. Silence gets a Why? reason and the right nudge, spaced and capped. Today lists who to contact with the message ready.',
    steps: [
      'Connections → Import CSV: our columns, or the Connections file LinkedIn gives you.',
      'GTN → Start someone → send the first message from LinkedIn, WhatsApp or Gmail → "I sent it".',
      'GTN → Today: do what is due. GTN → Board: drag people between stages.',
      'GTN → Templates: edit the messages for each stage and reason.',
    ],
    link: { to: '/network', label: 'Open GTN' },
  },
  {
    id: '2026-11-12-voice',
    date: '2026-11-12',
    title: 'Speak instead of typing',
    summary: 'Dictate notes to Log with AI and to Walnut in Chrome or Edge.',
    steps: ['Log with AI or Walnut → Speak → talk → Stop.'],
    link: { to: '/assistant', label: 'Open Log with AI' },
  },
  {
    id: '2026-11-12-account-picker',
    date: '2026-11-12',
    title: 'Searchable account picker, clients first',
    summary: 'Wherever you choose an account, type to search; clients are listed before other accounts.',
    steps: ['Opportunities → New opportunity → Account: start typing.'],
  },
  {
    id: '2026-11-12-opportunities',
    date: '2026-11-12',
    title: 'Opportunities: new requirements and candidates to a start',
    summary: 'Log requirements that come in on an opportunity, and track each candidate (bench or external) from proposed to started.',
    steps: ['Sales → Opportunities → an account → New requirement.', 'Fulfilment: propose candidates and move them on.'],
    link: { to: '/opportunities', label: 'Open Opportunities' },
  },
  {
    id: '2026-11-12-walnut',
    date: '2026-11-12',
    title: 'Walnut, the BD agent',
    summary: 'A morning plan every weekday (in the app and by email): follow-ups, deals to push, accounts to approach, drafts ready. Ask it anything in the chat; it suggests and you approve.',
    steps: ['Plan → Walnut → read today\'s plan → click an action, or ask a question.'],
    link: { to: '/walnut', label: 'Open Walnut' },
  },
  {
    id: '2026-11-11-connection-cards',
    date: '2026-11-11',
    title: 'Connections as people cards',
    summary: 'See connections as cards with photos, like LinkedIn, or switch back to the table.',
    steps: ['Network → Connections → Cards / Table.'],
    link: { to: '/connections', label: 'Open Connections' },
  },
  {
    id: '2026-11-11-log-with-ai-more',
    date: '2026-11-11',
    title: 'Log with AI: conferences, interviews, accounts and opportunities too',
    summary: 'Paste a note or a screenshot about an event, a leader interview, a new account or a new requirement, and Log with AI proposes the right record.',
    steps: ['Network → Log with AI → paste or attach → check the card → Save.'],
    link: { to: '/assistant', label: 'Open Log with AI' },
  },
  {
    id: '2026-11-10-log-with-ai',
    date: '2026-11-10',
    title: 'Log with AI',
    summary: 'A chat that turns what you did (text or a screenshot) into conversations and connections, including the person\'s photo.',
    steps: ['Network → Log with AI → describe what happened or attach a screenshot.'],
    link: { to: '/assistant', label: 'Open Log with AI' },
  },
  {
    id: '2026-11-10-network-pages',
    date: '2026-11-10',
    title: 'Connections, Conversations, Conferences and Interviews',
    summary: 'People we know and warm paths into accounts; who we spoke to and the next step; the events radar; leader interviews.',
    steps: ['Network → Connections, Conversations, Conferences, Interviews.'],
  },
  {
    id: '2026-11-10-heatmap-strategy',
    date: '2026-11-10',
    title: 'Heatmap, Strategy and Improvement areas',
    summary: 'Daily BD activity at a glance, the BD strategy in one place, and a board for suggesting improvements to this platform.',
    steps: ['Plan → Heatmap, Strategy.', 'Platform → Improvement areas → Suggest.'],
  },
  {
    id: '2026-11-10-accounts-scoring',
    date: '2026-11-10',
    title: 'Accounts ranked by fit',
    summary: 'Accounts carry year formed, size and open roles, with filters and sorts, and are ranked by a fit score that counts the research rating.',
    steps: ['Sales → Accounts → sort by fit.'],
    link: { to: '/accounts', label: 'Open Accounts' },
  },
  {
    id: '2026-11-08-find-gccs-research',
    date: '2026-11-08',
    title: 'Find GCCs and AI account research',
    summary: 'Discover GCCs that fit, and get research on how CodeWalnut can help each account; upload research written elsewhere too.',
    steps: ['Sales → Find GCCs.', 'An account → Research.'],
    link: { to: '/find-gccs', label: 'Open Find GCCs' },
  },
];
