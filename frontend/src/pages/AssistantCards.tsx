import { useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import {
  EVENT_KIND_LABELS, EVENT_ROLE_LABELS, ApiError, api,
  type AccountDetail, type AssistantAnswer, type BdEvent, type BdEventDetail, type EventKind, type EventRole,
  type Opportunity, type OpportunityType,
} from '../lib/api';
import { formatDate } from '../lib/format';
import { Done, Problems, useRefresh } from './AssistantPage';
import { FLOW, FORMAT, STATUS, type Format, type Status } from './InterviewsPage';
import { today } from './ConversationsPage';
import { useReference } from './PipelinePage';

/*
 * Log with AI cards for conferences and leader interviews (ADR-0013). Like the conversation and connection
 * cards, they are prefilled from what Claude read, editable, and save only through the usual endpoints.
 */

const GOING: EventRole[] = ['SIGNED_UP', 'ATTENDING', 'PRESENTING', 'STALL', 'ORGANISING'];

/** Add a conference, or update the one we already have with that name; optionally mark me as going. */
export function EventCard({ answer }: { answer: AssistantAnswer }) {
  const refresh = useRefresh();
  const e = answer.reading.event!;
  const existing = answer.events ?? [];
  const [id, setId] = useState<number | null>(existing[0]?.id ?? null);
  const chosen = existing.find((x) => x.id === id) ?? null;
  const fromReading = () => ({
    name: e.name, kind: e.kind, startsOn: e.startsOn, endsOn: e.endsOn, city: e.city, online: e.online, url: e.url,
    organiser: e.organiser, ourRole: e.ourRole, notes: e.notes,
  });
  // For an event we have, keep what is stored and take what was read where it says something.
  const merged = (x: BdEvent) => ({
    name: x.name, kind: e.kind || x.kind, startsOn: e.startsOn || x.startsOn, endsOn: e.endsOn || x.endsOn || '',
    city: e.city || x.city || '', online: e.online || x.online, url: x.url || e.url, organiser: x.organiser || e.organiser,
    ourRole: e.ourRole === 'WATCHING' ? x.ourRole : e.ourRole, notes: [x.notes, e.notes].filter(Boolean).join('\n'),
  });
  const [form, setForm] = useState(() => (chosen ? merged(chosen) : fromReading()));
  const [going, setGoing] = useState(GOING.includes(e.ourRole));
  const choose = (value: string) => {
    const next = value ? existing.find((x) => x.id === Number(value)) ?? null : null;
    setId(next?.id ?? null);
    setForm(next ? merged(next) : fromReading());
  };

  const save = useMutation({
    mutationFn: async () => {
      const body = {
        ...form, endsOn: form.endsOn || null, city: form.city || null, url: form.url || null, organiser: form.organiser || null,
        notes: form.notes || null, outcome: chosen?.outcome ?? null, version: chosen?.version ?? null,
      };
      const d = chosen ? await api.put<BdEventDetail>(`/events/${chosen.id}`, body) : await api.post<BdEventDetail>('/events', body);
      if (going) await api.put<BdEventDetail>(`/events/${d.event.id}/attendees/me`, {});
      return d.event;
    },
    onSuccess: refresh,
  });
  if (save.data) return <Done to={`/events/${save.data.id}`} label={`Open ${save.data.name}`} />;

  const field = (key: 'name' | 'city' | 'url' | 'organiser', label: string) => (
    <label>{label}
      <input id={`as-ev-${key}`} value={form[key]} onChange={(x) => setForm({ ...form, [key]: x.target.value })} />
    </label>
  );
  return (
    <form className="form proposal" aria-label={chosen ? 'Update conference' : 'Add conference'} onSubmit={(x) => { x.preventDefault(); save.mutate(); }}>
      {existing.length > 0 && (
        <label className="wide">Conference
          <select id="as-ev" value={id ?? ''} onChange={(x) => choose(x.target.value)}>
            {existing.map((x) => <option key={x.id} value={x.id}>{x.name} ({formatDate(x.startsOn)})</option>)}
            <option value="">A different event: add it</option>
          </select>
        </label>
      )}
      <div className="grid">
        {field('name', 'Name')}
        <label>Kind
          <select id="as-ev-kind" value={form.kind} onChange={(x) => setForm({ ...form, kind: x.target.value as EventKind })}>
            {Object.entries(EVENT_KIND_LABELS).map(([code, label]) => <option key={code} value={code}>{label}</option>)}
          </select>
        </label>
        <label>Our role
          <select id="as-ev-role" value={form.ourRole} onChange={(x) => setForm({ ...form, ourRole: x.target.value as EventRole })}>
            {Object.entries(EVENT_ROLE_LABELS).map(([code, label]) => <option key={code} value={code}>{label}</option>)}
          </select>
        </label>
        <label>Starts
          <input id="as-ev-starts" type="date" value={form.startsOn} onChange={(x) => setForm({ ...form, startsOn: x.target.value })} />
        </label>
        <label>Ends (if more than a day)
          <input id="as-ev-ends" type="date" value={form.endsOn} min={form.startsOn} onChange={(x) => setForm({ ...form, endsOn: x.target.value })} />
        </label>
        {field('city', 'City')}
        {field('url', 'Event page')}
        {field('organiser', 'Organiser')}
        <label className="wide">Notes
          <textarea id="as-ev-notes" rows={2} value={form.notes} onChange={(x) => setForm({ ...form, notes: x.target.value })} />
        </label>
      </div>
      <div className="checks">
        <label className="check">
          <input type="checkbox" checked={form.online} onChange={(x) => setForm({ ...form, online: x.target.checked })} /> Online
        </label>
        <label className="check">
          <input type="checkbox" checked={going} onChange={(x) => setGoing(x.target.checked)} /> I'm going
        </label>
      </div>
      <Problems error={save.error} />
      <div className="actions">
        <button type="submit" disabled={save.isPending || !form.name.trim() || !form.startsOn}>
          {save.isPending ? 'Saving…' : chosen ? 'Update conference' : 'Add conference'}
        </button>
      </div>
    </form>
  );
}

/**
 * The moves from one interview stage to another, one step at a time as the interview rules require:
 * via IDEA when it was declined; DECLINED directly.
 */
export function stepsTo(from: Status, to: Status): Status[] {
  if (from === to) return [];
  if (to === 'DECLINED') return ['DECLINED'];
  const steps: Status[] = [];
  let at = from;
  if (at === 'DECLINED') {
    steps.push('IDEA');
    at = 'IDEA';
  }
  let i = FLOW.indexOf(at);
  const j = FLOW.indexOf(to);
  while (i !== j) {
    i += i < j ? 1 : -1;
    steps.push(FLOW[i]!);
  }
  return steps;
}

type Saved = { id: number; status: Status; version: number; leaderName: string };

/** Add a leader interview, or move the one we have with that leader to the stage the material shows. */
export function InterviewCard({ answer }: { answer: AssistantAnswer }) {
  const refresh = useRefresh();
  const v = answer.reading.interview!;
  const existing = answer.interviews ?? [];
  const [id, setId] = useState<number | null>(existing[0]?.id ?? null);
  const chosen = existing.find((x) => x.id === id) ?? null;
  const [form, setForm] = useState({
    leaderName: v.leaderName, leaderTitle: v.leaderTitle, company: v.company, topic: v.topic, format: v.format as Format, notes: v.notes,
  });
  const [status, setStatus] = useState<Status>(v.status as Status);
  const [on, setOn] = useState(v.on || (v.status === 'BOOKED' ? '' : today()));
  const [url, setUrl] = useState(v.publishedUrl);
  const [stepProblem, setStepProblem] = useState<string | null>(null);

  const save = useMutation({
    mutationFn: async (): Promise<Saved> => {
      setStepProblem(null);
      let current: Saved = chosen
        ? { id: chosen.id, status: chosen.status as Status, version: chosen.version, leaderName: chosen.leaderName }
        : await api.post<Saved>('/interviews', { ...form, accountId: null, ownerId: null, notes: form.notes || null });
      for (const step of stepsTo(current.status, status)) {
        const last = step === status;
        try {
          current = await api.post<Saved>(`/interviews/${current.id}/move`, {
            status: step, version: current.version, publishedUrl: step === 'PUBLISHED' ? url : null,
            on: last ? on || null : step === 'BOOKED' ? on || today() : null,
          });
        } catch (e) {
          // The interview exists; say how far it got.
          setStepProblem(`It is saved as ${STATUS[current.status]}; moving it to ${STATUS[step]} failed: ${e instanceof Error ? e.message : e}`);
          break;
        }
      }
      return current;
    },
    onSuccess: refresh,
  });
  if (save.data) {
    return (
      <>
        <Done to={`/interviews/${save.data.id}`} label={`Open the interview with ${save.data.leaderName}`} />
        {stepProblem && <div className="notice warn">{stepProblem}</div>}
      </>
    );
  }

  const needsDate = status === 'BOOKED';
  const dateLabel: Partial<Record<Status, string>> = { ASKED: 'Asked on', BOOKED: 'Booked for', RECORDED: 'Recorded on', PUBLISHED: 'Published on' };
  const field = (key: 'leaderName' | 'leaderTitle' | 'company' | 'topic', label: string) => (
    <label className={key === 'topic' ? 'wide' : undefined}>{label}
      <input id={`as-iv-${key}`} value={form[key]} onChange={(x) => setForm({ ...form, [key]: x.target.value })} />
    </label>
  );
  return (
    <form className="form proposal" aria-label={chosen ? 'Update interview' : 'Add interview'} onSubmit={(x) => { x.preventDefault(); save.mutate(); }}>
      {existing.length > 0 && (
        <label className="wide">Interview
          <select id="as-iv" value={id ?? ''} onChange={(x) => setId(x.target.value ? Number(x.target.value) : null)}>
            {existing.map((x) => (
              <option key={x.id} value={x.id}>{x.leaderName}: {x.topic} ({STATUS[x.status as Status] ?? x.status})</option>
            ))}
            <option value="">A new interview: add it</option>
          </select>
        </label>
      )}
      <div className="grid">
        {!chosen && field('leaderName', 'Leader')}
        {!chosen && field('leaderTitle', 'Title')}
        {!chosen && field('company', 'Company')}
        {!chosen && (
          <label>Format
            <select id="as-iv-format" value={form.format} onChange={(x) => setForm({ ...form, format: x.target.value as Format })}>
              {Object.entries(FORMAT).map(([code, label]) => <option key={code} value={code}>{label}</option>)}
            </select>
          </label>
        )}
        {!chosen && field('topic', 'Topic')}
        <label>{chosen ? `Move from ${STATUS[chosen.status as Status] ?? chosen.status} to` : 'Stage'}
          <select id="as-iv-status" value={status} onChange={(x) => setStatus(x.target.value as Status)}>
            {Object.entries(STATUS).map(([code, label]) => <option key={code} value={code}>{label}</option>)}
          </select>
        </label>
        {dateLabel[status] && (
          <label>{dateLabel[status]}
            <input id="as-iv-on" type="date" value={on} max={needsDate ? undefined : today()} onChange={(x) => setOn(x.target.value)} />
          </label>
        )}
        {status === 'PUBLISHED' && (
          <label>Published at
            <input id="as-iv-url" value={url} placeholder="https://" onChange={(x) => setUrl(x.target.value)} />
          </label>
        )}
        {!chosen && (
          <label className="wide">Notes
            <textarea id="as-iv-notes" rows={2} value={form.notes} onChange={(x) => setForm({ ...form, notes: x.target.value })} />
          </label>
        )}
      </div>
      <Problems error={save.error} />
      <div className="actions">
        <button type="submit" disabled={save.isPending || (!chosen && !form.leaderName.trim()) || (needsDate && !on) || (status === 'PUBLISHED' && !url.trim())}>
          {save.isPending ? 'Saving…' : chosen ? 'Update interview' : 'Add interview'}
        </button>
      </div>
    </form>
  );
}

/** An account added from this reply, so the opportunity card can use it straight away. */
export type NewAccount = { id: number; name: string };

/**
 * Add a centre we do not have, or update the targeting facts (year formed, size, open roles) of one we do.
 * The account key is the website domain and city (BR-BD-01).
 */
export function AccountCard({ answer, onAdded }: { answer: AssistantAnswer; onAdded: (a: NewAccount) => void }) {
  const refresh = useRefresh();
  const a = answer.reading.account!;
  const matches = answer.accounts;
  const [id, setId] = useState<number | null>(matches[0]?.id ?? null);
  const num = (v?: number | null) => (v ? String(v) : '');
  const [form, setForm] = useState({
    name: a.name, parentCompany: a.parentCompany, parentHqCountry: a.parentHqCountry, city: a.city, websiteDomain: a.websiteDomain,
    industry: a.industry, notes: a.notes, yearEstablished: num(a.yearEstablished), employeeCount: num(a.employeeCount),
    openRoles: num(a.openRoles), openRolesSource: '',
  });
  const detail = useQuery({
    queryKey: ['account', String(id)],
    queryFn: () => api.get<AccountDetail>(`/accounts/${id}`),
    enabled: id !== null,
  });
  const toInt = (v: string) => (v.trim() ? Number(v) : null);

  const save = useMutation({
    mutationFn: async (): Promise<NewAccount> => {
      const facts = {
        yearEstablished: toInt(form.yearEstablished), employeeCount: toInt(form.employeeCount), openRoles: toInt(form.openRoles),
      };
      const rolesGiven = facts.openRoles !== null;
      if (id !== null) {
        const now = detail.data!.account;
        // Keep what is stored where the reading says nothing.
        await api.put(`/accounts/${id}/profile`, {
          yearEstablished: facts.yearEstablished ?? now.yearEstablished ?? null,
          employeeCount: facts.employeeCount ?? now.employeeCount ?? null,
          headcountBand: facts.employeeCount !== null ? null : now.headcountBand ?? null,
          openRoles: facts.openRoles ?? now.openRoles ?? null,
          openRolesAsOf: rolesGiven ? today() : now.openRolesAsOf ?? null,
          openRolesSource: rolesGiven ? form.openRolesSource || 'Log with AI' : now.openRolesSource ?? null,
        });
        return { id, name: now.name };
      }
      const created = await api.post<{ id: number }>('/accounts', {
        name: form.name, parentCompany: form.parentCompany, parentHqCountry: form.parentHqCountry || null, city: form.city,
        websiteDomain: form.websiteDomain, industry: form.industry || null, notes: form.notes || null, source: 'Log with AI',
        profile: { ...facts, openRolesAsOf: rolesGiven ? today() : null, openRolesSource: rolesGiven ? form.openRolesSource || 'Log with AI' : null },
      });
      return { id: created.id, name: form.name };
    },
    onSuccess: (saved) => {
      refresh();
      if (id === null) onAdded(saved);
    },
  });
  if (save.data) return <Done to={`/accounts/${save.data.id}`} label={`Open ${save.data.name}`} />;

  const field = (key: keyof typeof form, label: string, extra: object = {}) => (
    <label>{label}
      <input id={`as-acc-${key}`} value={form[key]} onChange={(x) => setForm({ ...form, [key]: x.target.value })} {...extra} />
    </label>
  );
  const number = { inputMode: 'numeric', pattern: '[0-9]*' };
  return (
    <form className="form proposal" aria-label={id !== null ? 'Update account' : 'Add account'} onSubmit={(x) => { x.preventDefault(); save.mutate(); }}>
      {matches.length > 0 && (
        <label className="wide">Account
          <select id="as-acc" value={id ?? ''} onChange={(x) => setId(x.target.value ? Number(x.target.value) : null)}>
            {matches.map((m) => <option key={m.id} value={m.id}>{m.name} ({m.city}, {m.websiteDomain})</option>)}
            <option value="">A different centre: add it</option>
          </select>
        </label>
      )}
      <div className="grid">
        {id === null && field('name', 'Centre name')}
        {id === null && field('parentCompany', 'Parent company')}
        {id === null && field('parentHqCountry', 'Parent HQ country')}
        {id === null && field('city', 'City')}
        {id === null && field('websiteDomain', 'Website domain', { placeholder: 'example.com' })}
        {id === null && field('industry', 'Industry')}
        {field('yearEstablished', 'Year formed', number)}
        {field('employeeCount', 'Employees', number)}
        {field('openRoles', 'Open roles', number)}
        {form.openRoles.trim() && field('openRolesSource', 'Open roles seen on', { placeholder: 'e.g. their careers page' })}
        {id === null && (
          <label className="wide">Notes
            <textarea id="as-acc-notes" rows={2} value={form.notes} onChange={(x) => setForm({ ...form, notes: x.target.value })} />
          </label>
        )}
      </div>
      {id !== null && detail.data && (
        <p className="muted small">
          Now: formed {detail.data.account.yearEstablished ?? '—'} · {detail.data.account.employeeCount ?? detail.data.account.headcountBand ?? '—'} employees
          · {detail.data.account.openRoles ?? '—'} open roles. Empty fields keep what is there.
        </p>
      )}
      <Problems error={save.error} />
      <div className="actions">
        <button type="submit" disabled={save.isPending || (id !== null && !detail.data)}>
          {save.isPending ? 'Saving…' : id !== null ? 'Update account' : 'Add account'}
        </button>
      </div>
    </form>
  );
}

/**
 * Add an opportunity at the account, or update an open one there, and move it to the stage read. The stage
 * rules (decision maker, need, start date, requirements) still apply; what is missing is shown with a link.
 */
export function OpportunityCard({ answer, added }: { answer: AssistantAnswer; added: NewAccount | null }) {
  const refresh = useRefresh();
  const reference = useReference();
  const o = answer.reading.opportunity!;
  const accounts = [...(added ? [{ id: added.id, name: added.name, city: '' }] : []), ...answer.accounts];
  const [accountId, setAccountId] = useState<number | null>(accounts[0]?.id ?? null);
  const existing = accountId === answer.accounts[0]?.id ? answer.opportunities ?? [] : [];
  const [oppId, setOppId] = useState<number | null>(existing[0]?.id ?? null);
  const chosen = existing.find((x) => x.id === oppId) ?? null;
  const [form, setForm] = useState({
    name: o.name, type: o.type, valueInr: o.valueInr ? String(o.valueInr) : '', expectedCloseDate: o.expectedCloseDate,
    needSummary: o.needSummary, targetStartDate: o.targetStartDate,
  });
  const [stage, setStage] = useState(o.stage);
  const [lossReason, setLossReason] = useState('');
  const [stageProblem, setStageProblem] = useState<string[] | null>(null);
  const stages = reference.data?.stages ?? [];
  const lost = stages.find((s) => s.code === stage)?.outcome === 'LOST';
  const lossReasons = reference.data?.picklists.LOSS_REASON ?? [];

  const save = useMutation({
    mutationFn: async (): Promise<Opportunity> => {
      setStageProblem(null);
      const value = form.valueInr.trim() ? Number(form.valueInr) : null;
      let saved: Opportunity;
      if (chosen) {
        saved = await api.patch<Opportunity>(`/opportunities/${chosen.id}`, {
          accountId: chosen.accountId, name: chosen.name, type: chosen.type, ownerId: chosen.ownerId,
          valueInr: value ?? chosen.valueInr ?? null, probability: chosen.probability,
          expectedCloseDate: form.expectedCloseDate || chosen.expectedCloseDate || null,
          decisionMakerContactId: chosen.decisionMakerContactId ?? null,
          needSummary: form.needSummary || chosen.needSummary || null,
          targetStartDate: form.targetStartDate || chosen.targetStartDate || null,
          requirements: null, // keep the requirement lines as they are
          scopeSummary: chosen.scopeSummary ?? null, pricingModel: chosen.pricingModel ?? null, estimateInr: chosen.estimateInr ?? null,
          teamShape: chosen.teamShape ?? null, timeline: chosen.timeline ?? null, version: chosen.version,
        });
      } else {
        const contact = accountId === answer.accounts[0]?.id ? answer.contactId ?? null : null;
        saved = await api.post<Opportunity>('/opportunities', {
          accountId, name: form.name, type: form.type, valueInr: value, expectedCloseDate: form.expectedCloseDate || null,
          decisionMakerContactId: contact, needSummary: form.needSummary || null, targetStartDate: form.targetStartDate || null,
          requirements: null,
        });
      }
      if (stage && stage !== saved.stage) {
        try {
          saved = await api.post<Opportunity>(`/opportunities/${saved.id}/stage`, { stage, lossReasonCode: lost ? lossReason : null });
        } catch (e) {
          setStageProblem(e instanceof ApiError && e.problems.length ? e.problems : [e instanceof Error ? e.message : String(e)]);
        }
      }
      return saved;
    },
    onSuccess: () => {
      refresh();
      reference.refetch();
    },
  });
  if (save.data) {
    return (
      <>
        <Done to={`/opportunities/${save.data.id}`} label={`Open ${save.data.name} (${save.data.stageLabel})`} />
        {stageProblem && (
          <div className="notice warn">
            Saved at {save.data.stageLabel}; it could not move on yet:
            <ul className="problems">{stageProblem.map((p) => <li key={p}>{p}</li>)}</ul>
            Add these on the opportunity page, then move it.
          </div>
        )}
      </>
    );
  }
  if (accounts.length === 0) {
    return (
      <p className="muted small">
        An opportunity needs an account. {answer.reading.account ? 'Add the centre on the Add account tab first.' : 'Add the company on the Accounts page first.'}
      </p>
    );
  }

  return (
    <form className="form proposal" aria-label={chosen ? 'Update opportunity' : 'Add opportunity'} onSubmit={(x) => { x.preventDefault(); save.mutate(); }}>
      <div className="grid">
        <label>Account
          <select id="as-opp-account" value={accountId ?? ''} onChange={(x) => { setAccountId(Number(x.target.value)); setOppId(null); }}>
            {accounts.map((a) => <option key={a.id} value={a.id}>{a.name}{a.city ? ` (${a.city})` : ''}</option>)}
          </select>
        </label>
        {existing.length > 0 && (
          <label>Opportunity
            <select id="as-opp" value={oppId ?? ''} onChange={(x) => setOppId(x.target.value ? Number(x.target.value) : null)}>
              {existing.map((x) => <option key={x.id} value={x.id}>{x.name} ({x.stageLabel})</option>)}
              <option value="">A new one: add it</option>
            </select>
          </label>
        )}
        {!chosen && (
          <label>Name
            <input id="as-opp-name" value={form.name} maxLength={200} onChange={(x) => setForm({ ...form, name: x.target.value })} />
          </label>
        )}
        {!chosen && (
          <label>Type
            <select id="as-opp-type" value={form.type} onChange={(x) => setForm({ ...form, type: x.target.value as OpportunityType })}>
              <option value="STAFF_AUGMENTATION">Staff augmentation</option>
              <option value="PROJECT">Project</option>
            </select>
          </label>
        )}
        <label>Value (INR)
          <input id="as-opp-value" inputMode="numeric" value={form.valueInr} placeholder={chosen?.valueInr ? String(chosen.valueInr) : ''}
            onChange={(x) => setForm({ ...form, valueInr: x.target.value.replace(/[^0-9]/g, '') })} />
        </label>
        <label>Expected close
          <input id="as-opp-close" type="date" value={form.expectedCloseDate} onChange={(x) => setForm({ ...form, expectedCloseDate: x.target.value })} />
        </label>
        <label>Target start
          <input id="as-opp-start" type="date" value={form.targetStartDate} onChange={(x) => setForm({ ...form, targetStartDate: x.target.value })} />
        </label>
        <label>{chosen ? `Move from ${chosen.stageLabel} to` : 'Stage'}
          <select id="as-opp-stage" value={stage} onChange={(x) => setStage(x.target.value)}>
            <option value="">{chosen ? 'Keep the stage' : 'The first stage'}</option>
            {stages.map((s) => <option key={s.code} value={s.code}>{s.label}</option>)}
          </select>
        </label>
        {lost && (
          <label>Loss reason
            <select id="as-opp-loss" value={lossReason} onChange={(x) => setLossReason(x.target.value)}>
              <option value="">Choose a loss reason</option>
              {lossReasons.map((r) => <option key={r.code} value={r.code}>{r.label}</option>)}
            </select>
          </label>
        )}
        <label className="wide">The need
          <textarea id="as-opp-need" rows={2} value={form.needSummary} placeholder={chosen?.needSummary ?? ''}
            onChange={(x) => setForm({ ...form, needSummary: x.target.value })} />
        </label>
      </div>
      {chosen && <p className="muted small">Empty fields keep what is there. Requirement lines are edited on the opportunity page.</p>}
      <Problems error={save.error} />
      <div className="actions">
        <button type="submit" disabled={save.isPending || !accountId || (!chosen && !form.name.trim()) || (lost && !lossReason)}>
          {save.isPending ? 'Saving…' : chosen ? 'Update opportunity' : 'Add opportunity'}
        </button>
      </div>
    </form>
  );
}
