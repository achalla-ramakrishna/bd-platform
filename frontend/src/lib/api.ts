// Thin client for the BD Platform API (docs/api/openapi.yaml).

export class ApiError extends Error {
  status: number;
  body: unknown;
  /** Business rules not met (HTTP 422 problem details), one message each. */
  problems: string[];

  constructor(status: number, message: string, body: unknown) {
    super(message);
    this.status = status;
    this.body = body;
    const p = (body as { problems?: unknown } | undefined)?.problems;
    this.problems = Array.isArray(p) ? p.map(String) : [];
  }
}

function csrfToken(): string | undefined {
  const match = document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='));
  return match ? decodeURIComponent(match.substring('XSRF-TOKEN='.length)) : undefined;
}

/** Always a readable message, even when the body says nothing (HTTP/2 has no status text). */
export function errorMessage(status: number, data: unknown): string {
  const d = data as { detail?: unknown; message?: unknown; error?: unknown } | undefined;
  const given = [d?.detail, d?.message].find((v) => typeof v === 'string' && v.trim());
  if (given) return given as string;
  switch (status) {
    case 401: return 'Your session has ended. Reload the page and sign in again.';
    case 403: return 'Your role cannot do this (HTTP 403).';
    case 404: return 'Not found (HTTP 404).';
    case 413: return 'That is too large to send (HTTP 413). Use fewer or smaller screenshots.';
    case 502: case 503: case 504: return `The server did not answer in time (HTTP ${status}). Try again in a minute.`;
    default: return `Something went wrong on the server (HTTP ${status}). Try again; if it keeps happening, tell an Admin.`;
  }
}

async function request<T>(method: string, path: string, body?: BodyInit | object, accept422 = false): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  let payload: BodyInit | undefined;
  if (body instanceof FormData) {
    payload = body;
  } else if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
    payload = JSON.stringify(body);
  }
  if (method !== 'GET') {
    const token = csrfToken();
    if (token) headers['X-XSRF-TOKEN'] = token;
  }
  const res = await fetch(`/api/v1${path}`, { method, headers, body: payload, credentials: 'same-origin' });
  const text = await res.text();
  let data: unknown;
  try {
    data = text ? JSON.parse(text) : undefined;
  } catch {
    data = undefined; // an HTML or plain-text error page from a proxy
  }
  if (!res.ok && !(accept422 && res.status === 422)) {
    throw new ApiError(res.status, errorMessage(res.status, data), data);
  }
  if (res.ok && text && data === undefined) {
    throw new ApiError(res.status, 'The server sent an answer the page could not read. Reload and try again.', undefined);
  }
  return data as T;
}

export const api = {
  get: <T>(path: string) => request<T>('GET', path),
  put: <T>(path: string, body: object) => request<T>('PUT', path, body),
  post: <T>(path: string, body: object) => request<T>('POST', path, body),
  patch: <T>(path: string, body: object) => request<T>('PATCH', path, body),
  del: <T>(path: string) => request<T>('DELETE', path),
  postForm: <T>(path: string, form: FormData) => request<T>('POST', path, form),
  putForm: <T>(path: string, form: FormData) => request<T>('PUT', path, form),
  /** Uploads return their validation report with HTTP 422 when rows fail. */
  upload: <T>(path: string, file: File) => {
    const form = new FormData();
    form.append('file', file);
    return request<T>('POST', path, form, true);
  },
};

// Types mirror the OpenAPI schemas.

export type Role = 'LEADERSHIP' | 'BD_LEAD' | 'ACCOUNT_EXECUTIVE' | 'DELIVERY' | 'SOLUTIONS' | 'ADMIN';

export const ROLE_LABELS: Record<Role, string> = {
  LEADERSHIP: 'Leadership',
  BD_LEAD: 'BD lead',
  ACCOUNT_EXECUTIVE: 'Account executive',
  DELIVERY: 'Delivery and resourcing',
  SOLUTIONS: 'Solutions and pre-sales',
  ADMIN: 'Admin',
};

export interface Me {
  id: number;
  email: string;
  name: string;
  roles: Role[];
  canSeeRestricted: boolean;
  canImport: boolean;
}

export interface SignInInfo {
  url: string;
  allowedDomain: string;
  devLogin: boolean;
  preview: boolean;
}

export interface RowError {
  row: number;
  column?: string;
  value?: string;
  message: string;
}

export interface ImportReport {
  entityType: string;
  fileName: string;
  totalRows: number;
  valid: boolean;
  toCreate: number;
  toUpdate: number;
  committed: boolean;
  batchId?: number;
  errorCount: number;
  errors: RowError[];
}

export interface ImportBatch {
  id: number;
  entityType: string;
  fileName: string;
  status: 'FAILED' | 'COMMITTED';
  totalRows: number;
  createdCount: number;
  updatedCount: number;
  failedCount: number;
  uploadedBy: string;
  uploadedAt: string;
}

export interface Account {
  id: number;
  name: string;
  parentCompany: string;
  parentHqCountry?: string;
  city: string;
  websiteDomain: string;
  industry?: string;
  headcountBand?: string;
  yearEstablished?: number;
  techStack: string[];
  relationship: 'PROSPECT' | 'CLIENT' | 'PAST_CLIENT';
  ownerId: number;
  ownerName: string;
  source: string;
  notes?: string;
  activeEngineers: number;
  researchedAt: string | null;
  employeeCount?: number | null;
  openRoles?: number | null;
  openRolesAsOf?: string | null;
  openRolesSource?: string | null;
  fitScore?: number | null;
  fitReasons: string[];
}

export interface AccountFacets {
  cities: string[];
  industries: string[];
}

/** Size for display: the exact count when known, otherwise the band. */
export function accountSize(a: Pick<Account, 'employeeCount' | 'headcountBand'>): string {
  if (a.employeeCount !== null && a.employeeCount !== undefined) return a.employeeCount.toLocaleString('en-IN');
  return a.headcountBand ?? '—';
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

export interface Contact {
  id: number;
  accountId: number;
  accountName: string;
  fullName: string;
  title?: string;
  persona: string;
  linkedinUrl?: string;
  email?: string;
  phone?: string;
  city?: string;
  source: string;
  consentBasis: string;
  optedOut: boolean;
}

export interface Contract {
  id: number;
  contractRef: string;
  accountId: number;
  accountName: string;
  contractType: 'MSA' | 'SOW';
  parentMsaRef?: string;
  workType?: string;
  pricingModel?: string;
  totalValueInr?: number | null;
  startDate: string;
  endDate?: string;
  renewalDate?: string;
  status: string;
  clientPermissionToName: boolean;
}

export interface Engineer {
  id: number;
  employeeCode: string;
  fullName: string;
  workEmail?: string | null;
  primaryRole: string;
  seniority: string;
  skills: string[];
  location?: string;
  availability: string;
  availableFrom?: string;
  consentToShareProfile: boolean;
  currentAllocationPercent: number;
}

export interface Deployment {
  id: number;
  engineerId: number;
  engineerName: string;
  employeeCode: string;
  seniority: string;
  accountId: number;
  accountName: string;
  sowRef?: string;
  teamOrProject?: string;
  role: string;
  clientManagerName?: string;
  allocationPercent: number;
  billRateInr?: number | null;
  rateUnit?: 'MONTH' | 'DAY' | 'HOUR';
  startDate: string;
  expectedEndDate?: string;
  status: string;
}

export interface AccountDetail {
  account: Account;
  contacts: Contact[];
  contracts: Contract[];
  deployments: Deployment[];
  monthlyBillingInr?: number | null;
}

export interface UserView {
  id: number;
  email: string;
  name: string;
  active: boolean;
  roles: Role[];
  lastLoginAt?: string;
}

export type OpportunityType = 'STAFF_AUGMENTATION' | 'PROJECT';

export interface Requirement {
  id?: number;
  role: string;
  skills: string[];
  seniority: string;
  headcount: number;
  locationMode: 'ONSITE' | 'HYBRID' | 'REMOTE' | '';
  location?: string | null;
  startDate: string;
  durationMonths?: number | null;
  allocationPercent: number;
  rateInr?: number | null;
  rateUnit?: 'MONTH' | 'DAY' | 'HOUR' | '' | null;
  deploymentRequest?: string | null;
}

export interface StageChange {
  fromStage?: string | null;
  toStage: string;
  changedBy: string;
  changedAt: string;
}

export interface Opportunity {
  id: number;
  accountId: number;
  accountName: string;
  name: string;
  type: OpportunityType;
  ownerId: number;
  ownerName: string;
  stage: string;
  stageLabel: string;
  stageOutcome: 'OPEN' | 'WON' | 'LOST';
  valueInr?: number | null;
  probability: number;
  weightedValueInr?: number | null;
  expectedCloseDate?: string | null;
  decisionMakerContactId?: number | null;
  decisionMakerName?: string | null;
  needSummary?: string | null;
  targetStartDate?: string | null;
  lossReasonCode?: string | null;
  scopeSummary?: string | null;
  pricingModel?: string | null;
  estimateInr?: number | null;
  teamShape?: string | null;
  timeline?: string | null;
  version: number;
  updatedAt: string;
  requirements: Requirement[];
  history: StageChange[];
}

export interface Activity {
  id: number;
  accountId: number;
  accountName: string;
  contactId?: number | null;
  contactName?: string | null;
  userName: string;
  channel: string;
  occurredOn: string;
  summary: string;
  outcome: string;
  createdAt: string;
  conversationId?: number | null;
}

export type ConversationStatus = 'STARTED' | 'REPLIED' | 'MEETING_BOOKED' | 'OPPORTUNITY' | 'GONE_QUIET' | 'CLOSED';

export const CONVERSATION_STATUS_LABELS: Record<ConversationStatus, string> = {
  STARTED: 'Started',
  REPLIED: 'Replied',
  MEETING_BOOKED: 'Meeting booked',
  OPPORTUNITY: 'Opportunity',
  GONE_QUIET: 'Gone quiet',
  CLOSED: 'Closed',
};

export interface Conversation {
  id: number;
  accountId: number;
  accountName: string;
  city: string;
  websiteDomain: string;
  contactId?: number | null;
  personName: string;
  personTitle?: string | null;
  topic: string;
  ownerId: number;
  ownerName: string;
  status: ConversationStatus;
  nextStep?: string | null;
  nextStepDue?: string | null;
  opportunityId?: number | null;
  opportunityName?: string | null;
  lastTouchOn: string;
  lastSummary?: string | null;
  createdAt: string;
  version: number;
  eventId?: number | null;
  eventName?: string | null;
}

export interface ConversationDetail {
  conversation: Conversation;
  updates: Activity[];
}

export interface ConversationSummary {
  open: number;
  overdue: number;
  dueThisWeek: number;
  meetingsBooked: number;
  opportunities: number;
  goneQuiet: number;
  startedLast30Days: number;
}

export interface ConversationPage {
  items: Conversation[];
  summary: ConversationSummary;
}

export type Strength = 'CLOSE' | 'KNOW_WELL' | 'ACQUAINTANCE';
export const STRENGTH_LABELS: Record<Strength, string> = { CLOSE: 'Close', KNOW_WELL: 'Know well', ACQUAINTANCE: 'Acquaintance' };
export const ACCOUNT_LINK_LABELS: Record<string, string> = { WORKS_THERE: 'Works there', CAN_INTRODUCE: 'Can introduce us' };

export interface Knower { userId: number; userName: string; strength: Strength; howKnown?: string | null; connectedOn: string }

export interface Connection {
  id: number;
  fullName: string;
  currentCompany?: string | null;
  currentRole?: string | null;
  city?: string | null;
  linkedinUrl?: string | null;
  email?: string | null;
  accountId?: number | null;
  accountName?: string | null;
  accountLink?: 'WORKS_THERE' | 'CAN_INTRODUCE' | null;
  contactId?: number | null;
  notes?: string | null;
  knownBy: Knower[];
  firstConnectedOn?: string | null;
  createdAt: string;
  version: number;
  metAtEventId?: number | null;
  metAtEventName?: string | null;
  /** Set when the connection has a photo; changes whenever it does (ADR-0014). */
  photoVersion?: number | null;
}

export type EventKind = 'CONFERENCE' | 'MEETUP' | 'WEBINAR' | 'ROUNDTABLE' | 'OTHER';
export type EventRole = 'WATCHING' | 'SIGNED_UP' | 'ATTENDING' | 'PRESENTING' | 'STALL' | 'ORGANISING';
export const EVENT_KIND_LABELS: Record<EventKind, string> = {
  CONFERENCE: 'Conference', MEETUP: 'Meetup', WEBINAR: 'Webinar', ROUNDTABLE: 'Roundtable', OTHER: 'Other',
};
export const EVENT_ROLE_LABELS: Record<EventRole, string> = {
  WATCHING: 'Watching', SIGNED_UP: 'Signed up', ATTENDING: 'Attending', PRESENTING: 'Presenting', STALL: 'Stall',
  ORGANISING: 'Organising',
};

export interface BdEvent {
  id: number;
  name: string;
  kind: EventKind;
  startsOn: string;
  endsOn?: string | null;
  city?: string | null;
  online: boolean;
  url?: string | null;
  organiser?: string | null;
  ourRole: EventRole;
  notes?: string | null;
  outcome?: string | null;
  attendees: { userId: number; name: string }[];
  conversations: number;
  connections: number;
  version: number;
}

export interface BdEventDetail { event: BdEvent; conversations: Conversation[]; connections: Connection[] }

export interface BdEventPage {
  items: BdEvent[];
  summary: { next30Days: number; presentingOrOrganising: number; thisQuarter: number; peopleMetLast90Days: number };
}

export interface ConnectionPage {
  items: Connection[];
  summary: { total: number; knownByMe: number; linkedToAccounts: number; addedLast30Days: number; weekly: { weekStart: string; added: number }[] };
}

export interface Stage {
  code: string;
  label: string;
  defaultProbability: number;
  outcome: 'OPEN' | 'WON' | 'LOST';
}

export interface ReferenceData {
  stages: Stage[];
  seniorityRules: { seniority: string; maxConcurrentClients: number }[];
  picklists: Record<string, { code: string; label: string }[]>;
}

/** Roles that may change the pipeline and log activities. */
export function canEditPipeline(me: Me): boolean {
  return me.roles.some((r) => ['LEADERSHIP', 'BD_LEAD', 'ACCOUNT_EXECUTIVE', 'SOLUTIONS', 'ADMIN'].includes(r));
}

export const CHANNEL_LABELS: Record<string, string> = {
  LINKEDIN_CONNECTION: 'LinkedIn connection request',
  LINKEDIN_MESSAGE: 'LinkedIn message',
  INMAIL: 'InMail',
  EMAIL: 'Email',
  CALL: 'Call',
  MEETING: 'Meeting',
  EVENT: 'Event',
  REFERRAL: 'Referral',
};

export const OUTCOME_LABELS: Record<string, string> = {
  NO_REPLY: 'No reply yet',
  REPLIED: 'Replied',
  MEETING_BOOKED: 'Meeting booked',
  NOT_INTERESTED: 'Not interested',
  REFERRED_ON: 'Referred to someone else',
  DONE: 'Done',
};

export interface ProfileSuggestion {
  fullName: string;
  title: string;
  company: string;
  city: string;
  persona: string;
  personaReason: string;
  notes: string;
}

export interface ResearchFact { statement: string; source: string }

export interface AccountResearch {
  id: number;
  accountId: number;
  status: 'RUNNING' | 'DONE' | 'FAILED';
  summary?: {
    centreOverview: string;
    facts: ResearchFact[];
    buyingSignals: ResearchFact[];
    likelyNeeds: string[];
    recommendedOffers: { offer: string; why: string }[];
    whoToContact: { persona: string; why: string; knownContact?: string | null }[];
    openingMessage: string;
    talkingPoints: string[];
    cautions: string[];
    fitRating?: 'STRONG' | 'MEDIUM' | 'WEAK' | null;
  } | null;
  sources: { url: string; title: string }[];
  error?: string | null;
  model?: string | null;
  requestedBy: string;
  requestedAt: string;
  finishedAt?: string | null;
  laterFailure?: string | null;
}

export interface GccCandidate {
  candidate: {
    company: string; parentCompany: string; hqCountry: string; city: string; websiteDomain: string;
    industry: string; description: string; source: string;
  };
  domain: string;
  validDomain: boolean;
  alreadyInApp: boolean;
}

export interface GccSearch {
  id: number;
  city: string;
  industry?: string | null;
  status: 'RUNNING' | 'DONE' | 'FAILED';
  candidates: GccCandidate[];
  error?: string | null;
  requestedBy: string;
  requestedAt: string;
  finishedAt?: string | null;
}

/** What the BD assistant understood from a pasted note or screenshots (ADR-0013). */
export interface AssistantReading {
  reply: string;
  fullName: string;
  title: string;
  company: string;
  city: string;
  linkedinUrl: string;
  topic: string;
  channel: string;
  occurredOn: string;
  summary: string;
  outcome: string;
  nextStep: string;
  nextStepDue: string;
  newConnection: boolean;
  /** Where their profile photo is: screenshot index and a box as fractions of that screenshot. */
  photo?: PhotoBox | null;
  /** TOUCH (a person), EVENT (a conference or meetup) or INTERVIEW (a leader interview). */
  about?: 'TOUCH' | 'EVENT' | 'INTERVIEW';
  event?: AssistantEvent | null;
  interview?: AssistantInterview | null;
}

export interface AssistantEvent {
  name: string; kind: EventKind; startsOn: string; endsOn: string; city: string; online: boolean; url: string;
  organiser: string; ourRole: EventRole; notes: string;
}

export interface AssistantInterview {
  leaderName: string; leaderTitle: string; company: string; topic: string; format: string; status: string; on: string;
  publishedUrl: string; notes: string;
}

export interface InterviewMatch {
  id: number; leaderName: string; leaderTitle?: string | null; company?: string | null; topic: string; format: string;
  status: string; scheduledOn?: string | null; version: number;
}

export interface PhotoBox { image: number; x: number; y: number; width: number; height: number }

export interface FoundAccount { id: number; name: string; parentCompany: string; city: string; websiteDomain: string }

export type AssistantSuggestion = 'UPDATE_CONVERSATION' | 'START_CONVERSATION' | 'ADD_CONNECTION' | 'EVENT' | 'INTERVIEW';

export interface AssistantAnswer {
  reading: AssistantReading;
  conversations: Conversation[];
  connections: Connection[];
  accounts: FoundAccount[];
  contactId?: number | null;
  events?: BdEvent[];
  interviews?: InterviewMatch[];
  suggestion: AssistantSuggestion;
}
