import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate } from 'react-router';
import {
  CANDIDATE_FLOW, CANDIDATE_STATUS_LABELS, ApiError, api, canEditPipeline,
  type Me, type Opportunity, type RequirementStatus,
} from '../lib/api';
import { formatDate, formatInr, humanise } from '../lib/format';
import { AccountPicker } from './AccountPicker';
import { typeLabel, useReference } from './PipelinePage';
import { today } from './ConversationsPage';

/*
 * Opportunities (ADR-0016): every opportunity by account with its requirements and how far each is from being
 * filled, new requirements as they come in, and the fulfilment view of requirements being realised.
 */

type View = 'accounts' | 'fulfilment';
type Show = 'ACTIVE' | 'OPEN' | 'WON' | 'LOST' | 'ALL';

export function OpportunitiesPage({ me }: { me: Me }) {
  const [view, setView] = useState<View>('accounts');
  const [adding, setAdding] = useState(false);
  const [mine, setMine] = useState(false);
  const editable = canEditPipeline(me);
  return (
    <>
      <div className="title-row">
        <h1>Opportunities</h1>
        <div className="actions">
          {editable && !adding && <button type="button" onClick={() => setAdding(true)}>New requirement</button>}
          {editable && <Link className="button secondary" to="/opportunities/new">New opportunity</Link>}
        </div>
      </div>
      <p className="lead">
        What each account needs from us and how far we are from filling it. Log a new requirement as soon as it comes
        in, then propose candidates and move them to a start on the opportunity.
      </p>
      {adding && <NewRequirement onDone={() => setAdding(false)} />}
      <div className="toolbar">
        <div className="segmented" role="group" aria-label="View">
          <button type="button" className={view === 'accounts' ? 'on' : ''} aria-pressed={view === 'accounts'} onClick={() => setView('accounts')}>By account</button>
          <button type="button" className={view === 'fulfilment' ? 'on' : ''} aria-pressed={view === 'fulfilment'} onClick={() => setView('fulfilment')}>Fulfilment</button>
        </div>
        <label className="inline-check"><input type="checkbox" checked={mine} onChange={(e) => setMine(e.target.checked)} /> Mine only</label>
      </div>
      {view === 'accounts' ? <ByAccount me={me} mine={mine} /> : <Fulfilment mine={mine} />}
    </>
  );
}

function progress(f?: RequirementStatus) {
  if (!f) return <span className="muted small">No candidates yet</span>;
  const parts = CANDIDATE_FLOW.filter((s) => s !== 'STARTED' && (f.byStatus[s] ?? 0) > 0)
    .map((s) => `${f.byStatus[s]} ${CANDIDATE_STATUS_LABELS[s].toLowerCase()}`);
  return (
    <span className="small">
      <strong>{f.started} of {f.headcount} started</strong>{parts.length ? ` · ${parts.join(' · ')}` : f.active === 0 && f.started < f.headcount ? ' · no candidates yet' : ''}
    </span>
  );
}

function ByAccount({ me, mine }: { me: Me; mine: boolean }) {
  const [show, setShow] = useState<Show>('ACTIVE');
  const opps = useQuery({
    queryKey: ['opportunities', 'all', mine],
    queryFn: () => api.get<Opportunity[]>(`/opportunities${mine ? `?ownerId=${me.id}` : ''}`),
  });
  const fulfilment = useQuery({ queryKey: ['fulfilment', 'all'], queryFn: () => api.get<RequirementStatus[]>('/fulfilment?all=true') });
  const byReq = new Map((fulfilment.data ?? []).map((f) => [f.requirementId, f]));
  const keep = (o: Opportunity) => show === 'ALL' || (show === 'ACTIVE' ? o.stageOutcome !== 'LOST' : o.stageOutcome === show);
  const list = (opps.data ?? []).filter(keep);
  const accounts = new Map<number, { name: string; items: Opportunity[] }>();
  for (const o of list) {
    if (!accounts.has(o.accountId)) accounts.set(o.accountId, { name: o.accountName, items: [] });
    accounts.get(o.accountId)!.items.push(o);
  }
  const total = list.filter((o) => o.stageOutcome === 'OPEN').reduce((sum, o) => sum + (o.valueInr ?? 0), 0);
  return (
    <>
      <div className="toolbar">
        <select aria-label="Which opportunities" value={show} onChange={(e) => setShow(e.target.value as Show)}>
          <option value="ACTIVE">Open and won</option>
          <option value="OPEN">Open</option>
          <option value="WON">Won</option>
          <option value="LOST">Lost</option>
          <option value="ALL">All</option>
        </select>
        <span className="muted">{list.length} {list.length === 1 ? 'opportunity' : 'opportunities'} at {accounts.size} {accounts.size === 1 ? 'account' : 'accounts'}{total ? ` · ${formatInr(total)} open` : ''}</span>
      </div>
      {opps.isPending && <p className="muted">Loading…</p>}
      {!opps.isPending && list.length === 0 && <p className="muted">No opportunities here yet. Log a new requirement when an account asks for engineers.</p>}
      <div className="account-opps">
        {[...accounts.entries()].map(([accountId, a]) => (
          <section key={accountId} className="card account-opp">
            <h2><Link to={`/accounts/${accountId}`}>{a.name}</Link></h2>
            {a.items.map((o) => (
              <div key={o.id} className="opp-row">
                <div className="opp-head">
                  <Link to={`/opportunities/${o.id}`} className="opp-name">{o.name}</Link>
                  <span className={`pill ${o.stageOutcome === 'WON' ? 'good' : o.stageOutcome === 'LOST' ? 'bad' : 'accent'}`}>{o.stageLabel}</span>
                  <span className="muted small">{typeLabel(o.type)}{o.valueInr ? ` · ${formatInr(o.valueInr)}` : ''}
                    {o.expectedCloseDate ? ` · close ${formatDate(o.expectedCloseDate)}` : ''} · {o.ownerName}</span>
                </div>
                {o.requirements.length > 0 && (
                  <ul className="req-list">
                    {o.requirements.map((r) => (
                      <li key={r.id}>
                        <span>{r.headcount} × {r.role} <span className="muted small">({humanise(r.seniority)}, from {formatDate(r.startDate)})</span></span>
                        {progress(r.id ? byReq.get(r.id) : undefined)}
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            ))}
          </section>
        ))}
      </div>
    </>
  );
}

function nextAction(f: RequirementStatus): string {
  const n = (s: keyof RequirementStatus['byStatus']) => f.byStatus[s] ?? 0;
  if (n('ONBOARDING') > 0) return 'Prepare the start';
  if (n('SELECTED') > 0) return 'Confirm the start date and onboard';
  if (n('INTERVIEW') > 0) return 'Get interview feedback';
  if (n('SHARED') > 0) return 'Chase feedback on profiles';
  if (n('PROPOSED') > 0) return 'Share profiles with the client';
  return 'Propose candidates';
}

function Fulfilment({ mine }: { mine: boolean }) {
  const reqs = useQuery({
    queryKey: ['fulfilment', 'open', mine],
    queryFn: () => api.get<RequirementStatus[]>(`/fulfilment${mine ? '?mine=true' : ''}`),
  });
  const list = reqs.data ?? [];
  const cols: (keyof RequirementStatus['byStatus'])[] = ['PROPOSED', 'SHARED', 'INTERVIEW', 'SELECTED', 'ONBOARDING'];
  return (
    <>
      <p className="muted small">Requirements on open and won opportunities that are not yet filled, soonest start first.</p>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Requirement</th><th>Starts</th>
              {cols.map((c) => <th key={c} className="num">{CANDIDATE_STATUS_LABELS[c]}</th>)}
              <th className="num">Started</th><th>Next</th>
            </tr>
          </thead>
          <tbody>
            {reqs.isPending && <tr><td colSpan={9} className="muted">Loading…</td></tr>}
            {!reqs.isPending && list.length === 0 && <tr><td colSpan={9} className="muted">Nothing to fill right now.</td></tr>}
            {list.map((f) => {
              const late = f.startDate < today() && f.started < f.headcount;
              return (
                <tr key={f.requirementId}>
                  <td>
                    <Link to={`/opportunities/${f.opportunityId}`}>{f.headcount} × {f.role}</Link>
                    <span className="sub">{f.accountName} · {f.opportunityName} · {f.stage}</span>
                  </td>
                  <td className={late ? 'bad-text' : ''}>{formatDate(f.startDate)}</td>
                  {cols.map((c) => <td key={c} className="num">{f.byStatus[c] ?? ''}</td>)}
                  <td className="num"><strong>{f.started}/{f.headcount}</strong></td>
                  <td className="small">{nextAction(f)}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </>
  );
}

const SENIORITY_DEFAULT = 'SENIOR';

/** A requirement that just came in: add it to an open opportunity at the account, or start one. */
function NewRequirement({ onDone }: { onDone: () => void }) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const reference = useReference();
  const [account, setAccount] = useState<{ id: number | null; name: string | null }>({ id: null, name: null });
  const [oppId, setOppId] = useState<string>('');
  const [oppName, setOppName] = useState('');
  const [r, setR] = useState({
    role: '', skills: '', seniority: SENIORITY_DEFAULT, headcount: '1', locationMode: 'HYBRID', location: 'Bengaluru',
    startDate: today(30), durationMonths: '6', rateInr: '', rateUnit: 'MONTH',
  });
  const opps = useQuery({
    queryKey: ['opportunities', 'at', account.id],
    queryFn: () => api.get<Opportunity[]>(`/opportunities?accountId=${account.id}`),
    enabled: account.id !== null,
  });
  const open = (opps.data ?? []).filter((o) => o.type === 'STAFF_AUGMENTATION' && o.stageOutcome !== 'LOST');
  const chosen = oppId === '' ? (open[0]?.id ?? null) : oppId === 'new' ? null : Number(oppId);
  const requirement = {
    role: r.role, skills: r.skills.split(/[;,]/).map((s) => s.trim()).filter(Boolean), seniority: r.seniority,
    headcount: Number(r.headcount) || null, locationMode: r.locationMode, location: r.location || null,
    startDate: r.startDate || null, durationMonths: r.durationMonths ? Number(r.durationMonths) : null, allocationPercent: 100,
    rateInr: r.rateInr ? Number(r.rateInr) : null, rateUnit: r.rateInr ? r.rateUnit : null,
  };
  const save = useMutation({
    mutationFn: () => chosen
      ? api.post<Opportunity>(`/opportunities/${chosen}/requirements`, requirement)
      : api.post<Opportunity>('/opportunities', {
        accountId: account.id, name: oppName || `${r.headcount} × ${r.role}`, type: 'STAFF_AUGMENTATION',
        targetStartDate: r.startDate || null, requirements: [requirement],
      }),
    onSuccess: (o) => {
      queryClient.invalidateQueries({ queryKey: ['opportunities'] });
      queryClient.invalidateQueries({ queryKey: ['fulfilment'] });
      onDone();
      navigate(`/opportunities/${o.id}`);
    },
  });
  const problems = save.error instanceof ApiError ? save.error.problems : [];
  const field = (key: keyof typeof r, label: string, extra: object = {}) => (
    <label>{label}
      <input value={r[key]} onChange={(e) => setR({ ...r, [key]: e.target.value })} {...extra} />
    </label>
  );
  const seniorities = reference.data?.seniorityRules.map((s) => s.seniority) ?? [SENIORITY_DEFAULT];
  return (
    <form className="form conversation-form" aria-label="New requirement" onSubmit={(e) => { e.preventDefault(); save.mutate(); }}>
      <h2>New requirement</h2>
      <div className="grid">
        <label>Account
          <AccountPicker id="req-account" value={account.id} valueName={account.name}
            onChange={(id, name) => { setAccount({ id, name }); setOppId(''); }} />
        </label>
        {account.id !== null && (
          <label>Opportunity
            <select value={chosen === null ? 'new' : String(chosen)} onChange={(e) => setOppId(e.target.value)}>
              {open.map((o) => <option key={o.id} value={o.id}>{o.name} ({o.stageLabel})</option>)}
              <option value="new">Start a new opportunity</option>
            </select>
          </label>
        )}
        {account.id !== null && chosen === null && (
          <label>Opportunity name
            <input value={oppName} maxLength={200} placeholder={`${r.headcount} × ${r.role || 'role'}`} onChange={(e) => setOppName(e.target.value)} />
          </label>
        )}
        {field('role', 'Role', { required: true, maxLength: 120, placeholder: 'e.g. React engineer' })}
        {field('skills', 'Skills', { required: true, placeholder: 'React; TypeScript; Node' })}
        <label>Seniority
          <select value={r.seniority} onChange={(e) => setR({ ...r, seniority: e.target.value })}>
            {seniorities.map((s) => <option key={s} value={s}>{humanise(s)}</option>)}
          </select>
        </label>
        {field('headcount', 'How many', { type: 'number', min: 1, required: true })}
        <label>Where
          <select value={r.locationMode} onChange={(e) => setR({ ...r, locationMode: e.target.value })}>
            <option value="ONSITE">Onsite</option><option value="HYBRID">Hybrid</option><option value="REMOTE">Remote</option>
          </select>
        </label>
        {field('location', 'City')}
        {field('startDate', 'Start by', { type: 'date', required: true })}
        {field('durationMonths', 'Months', { type: 'number', min: 1 })}
        {field('rateInr', 'Rate (₹, optional)', { type: 'number', min: 0 })}
        <label>Per
          <select value={r.rateUnit} onChange={(e) => setR({ ...r, rateUnit: e.target.value })}>
            <option value="MONTH">Month</option><option value="DAY">Day</option><option value="HOUR">Hour</option>
          </select>
        </label>
      </div>
      {save.error && (
        <div className="notice bad" role="alert">
          {problems.length > 0 ? <ul className="problems">{problems.map((p) => <li key={p}>{p}</li>)}</ul> : save.error.message}
        </div>
      )}
      <div className="actions">
        <button type="submit" disabled={save.isPending || account.id === null}>{save.isPending ? 'Saving…' : 'Add requirement'}</button>
        <button type="button" className="secondary" onClick={onDone}>Cancel</button>
      </div>
    </form>
  );
}
