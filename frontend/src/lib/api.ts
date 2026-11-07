// Thin client for the BD Platform API (docs/api/openapi.yaml).

export class ApiError extends Error {
  status: number;
  body: unknown;

  constructor(status: number, message: string, body: unknown) {
    super(message);
    this.status = status;
    this.body = body;
  }
}

function csrfToken(): string | undefined {
  const match = document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='));
  return match ? decodeURIComponent(match.substring('XSRF-TOKEN='.length)) : undefined;
}

async function request<T>(method: string, path: string, body?: BodyInit | object): Promise<T> {
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
  const data = text ? JSON.parse(text) : undefined;
  if (!res.ok && res.status !== 422) {
    const detail = (data && (data.detail || data.message)) || res.statusText;
    throw new ApiError(res.status, detail, data);
  }
  return data as T;
}

export const api = {
  get: <T>(path: string) => request<T>('GET', path),
  put: <T>(path: string, body: object) => request<T>('PUT', path, body),
  upload: <T>(path: string, file: File) => {
    const form = new FormData();
    form.append('file', file);
    return request<T>('POST', path, form);
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
