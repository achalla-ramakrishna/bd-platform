import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router';
import {
  ApiError, api, canEditFulfilment, canEditPipeline,
  type Account, type Contact, type Me, type Opportunity, type OpportunityType, type Page, type Requirement,
} from '../lib/api';
import { formatDate, formatInr, humanise } from '../lib/format';
import { typeLabel, useReference } from './PipelinePage';
import { CandidatesPanel } from './Candidates';

interface FormState {
  accountId: string;
  name: string;
  type: OpportunityType;
  ownerId: string;
  valueInr: string;
  probability: string;
  expectedCloseDate: string;
  decisionMakerContactId: string;
  needSummary: string;
  targetStartDate: string;
  scopeSummary: string;
  pricingModel: string;
  estimateInr: string;
  teamShape: string;
  timeline: string;
  requirements: RequirementRow[];
}

interface RequirementRow {
  id?: number;
  role: string;
  skills: string;
  seniority: string;
  headcount: string;
  locationMode: string;
  location: string;
  startDate: string;
  durationMonths: string;
  allocationPercent: string;
  rateInr: string;
  rateUnit: string;
  deploymentRequest?: string | null;
}

const emptyRequirement: RequirementRow = {
  role: '', skills: '', seniority: 'SENIOR', headcount: '1', locationMode: 'HYBRID', location: 'Bengaluru',
  startDate: '', durationMonths: '6', allocationPercent: '100', rateInr: '', rateUnit: 'MONTH',
};

const str = (v: number | string | null | undefined) => (v === null || v === undefined ? '' : String(v));
const num = (v: string) => (v.trim() === '' ? null : Number(v));

function toForm(o: Opportunity): FormState {
  return {
    accountId: String(o.accountId), name: o.name, type: o.type, ownerId: String(o.ownerId),
    valueInr: str(o.valueInr), probability: String(o.probability), expectedCloseDate: str(o.expectedCloseDate),
    decisionMakerContactId: str(o.decisionMakerContactId), needSummary: str(o.needSummary),
    targetStartDate: str(o.targetStartDate), scopeSummary: str(o.scopeSummary), pricingModel: str(o.pricingModel),
    estimateInr: str(o.estimateInr), teamShape: str(o.teamShape), timeline: str(o.timeline),
    requirements: o.requirements.map((r: Requirement) => ({
      id: r.id, role: r.role, skills: r.skills.join('; '), seniority: r.seniority, headcount: String(r.headcount),
      locationMode: r.locationMode, location: str(r.location), startDate: str(r.startDate),
      durationMonths: str(r.durationMonths), allocationPercent: String(r.allocationPercent), rateInr: str(r.rateInr),
      rateUnit: str(r.rateUnit), deploymentRequest: r.deploymentRequest,
    })),
  };
}

function toBody(f: FormState, version?: number) {
  return {
    accountId: num(f.accountId), name: f.name, type: f.type, ownerId: num(f.ownerId), valueInr: num(f.valueInr),
    probability: num(f.probability), expectedCloseDate: f.expectedCloseDate || null,
    decisionMakerContactId: num(f.decisionMakerContactId), needSummary: f.needSummary,
    targetStartDate: f.targetStartDate || null, scopeSummary: f.scopeSummary, pricingModel: f.pricingModel || null,
    estimateInr: num(f.estimateInr), teamShape: f.teamShape, timeline: f.timeline,
    requirements: f.type === 'STAFF_AUGMENTATION'
      ? f.requirements.map((r) => ({
        id: r.id ?? null, role: r.role, skills: r.skills.split(/[;,]/).map((s) => s.trim()).filter(Boolean), seniority: r.seniority,
        headcount: num(r.headcount), locationMode: r.locationMode, location: r.location || null,
        startDate: r.startDate || null, durationMonths: num(r.durationMonths), allocationPercent: num(r.allocationPercent),
        rateInr: num(r.rateInr), rateUnit: r.rateInr.trim() ? r.rateUnit : null,
      }))
      : [],
    version,
  };
}

function Problems({ error }: { error: unknown }) {
  if (!error) return null;
  if (error instanceof ApiError && error.problems.length > 0) {
    return (
      <div className="notice bad" role="alert">
        <ul className="problems">{error.problems.map((p) => <li key={p}>{p}</li>)}</ul>
      </div>
    );
  }
  return <div className="notice bad" role="alert">{(error as Error).message}</div>;
}

export function OpportunityPage({ me }: { me: Me }) {
  const { id } = useParams();
  const isNew = id === undefined;
  const opportunity = useQuery({
    queryKey: ['opportunity', id],
    queryFn: () => api.get<Opportunity>(`/opportunities/${id}`),
    enabled: !isNew,
  });
  if (!isNew && opportunity.isPending) return <p className="muted">Loading…</p>;
  if (!isNew && opportunity.error) return <div className="notice bad">{opportunity.error.message}</div>;
  return <OpportunityEditor key={opportunity.data ? `${opportunity.data.id}-${opportunity.data.stage}` : 'new'} me={me} existing={opportunity.data} />;
}

function OpportunityEditor({ me, existing }: { me: Me; existing?: Opportunity }) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [search] = useSearchParams();
  const reference = useReference();
  const editable = canEditPipeline(me);
  const [form, setForm] = useState<FormState>(() => existing ? toForm(existing) : {
    accountId: search.get('accountId') ?? '', name: '', type: 'STAFF_AUGMENTATION', ownerId: String(me.id),
    valueInr: '', probability: '', expectedCloseDate: '', decisionMakerContactId: '', needSummary: '',
    targetStartDate: '', scopeSummary: '', pricingModel: '', estimateInr: '', teamShape: '', timeline: '',
    requirements: [],
  });
  const [saved, setSaved] = useState(false);
  const set = (patch: Partial<FormState>) => { setForm((f) => ({ ...f, ...patch })); setSaved(false); };

  const accounts = useQuery({ queryKey: ['accounts-all'], queryFn: () => api.get<Page<Account>>('/accounts?size=200') });
  const people = useQuery({ queryKey: ['people'], queryFn: () => api.get<{ id: number; name: string }[]>('/users') });
  const contacts = useQuery({
    queryKey: ['contacts', form.accountId],
    queryFn: () => api.get<Contact[]>(`/contacts?accountId=${form.accountId}`),
    enabled: form.accountId !== '',
  });

  const save = useMutation({
    mutationFn: () => existing
      ? api.patch<Opportunity>(`/opportunities/${existing.id}`, toBody(form, existing.version))
      : api.post<Opportunity>('/opportunities', toBody(form)),
    onSuccess: (o) => {
      queryClient.invalidateQueries({ queryKey: ['opportunities'] });
      queryClient.setQueryData(['opportunity', String(o.id)], o);
      setSaved(true);
      if (!existing) navigate(`/opportunities/${o.id}`, { replace: true });
    },
  });

  const requirementsLocked = existing?.requirements.some((r) => r.deploymentRequest) ?? false;
  const seniorities = reference.data?.seniorityRules.map((r) => r.seniority) ?? [];

  return (
    <>
      <p><Link to="/opportunities">← Opportunities</Link> · <Link to="/pipeline">Pipeline</Link></p>
      <div className="title-row">
        <div>
          <h1>{existing ? existing.name : 'New opportunity'}</h1>
          {existing && (
            <p className="lead">
              <Link to={`/accounts/${existing.accountId}`}>{existing.accountName}</Link> · {typeLabel(existing.type)} ·{' '}
              owner {existing.ownerName}
            </p>
          )}
        </div>
      </div>

      {existing && <StagePanel o={existing} editable={editable} />}

      <form
        className="form"
        onSubmit={(e) => { e.preventDefault(); save.mutate(); }}
        aria-label="Opportunity details"
      >
        <fieldset disabled={!editable || save.isPending}>
          <h2>Details</h2>
          <div className="grid">
            <label>Account
              <select id="opp-account" required value={form.accountId} onChange={(e) => set({ accountId: e.target.value, decisionMakerContactId: '' })}>
                <option value="">Choose an account</option>
                {accounts.data?.items.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
              </select>
            </label>
            <label>Name
              <input id="opp-name" required maxLength={200} value={form.name} onChange={(e) => set({ name: e.target.value })} placeholder="For example: Payments team extension" />
            </label>
            <label>Type
              <select id="opp-type" value={form.type} onChange={(e) => set({ type: e.target.value as OpportunityType })} disabled={!!existing && existing.stageOutcome === 'WON'}>
                <option value="STAFF_AUGMENTATION">Staff augmentation</option>
                <option value="PROJECT">Project</option>
              </select>
            </label>
            <label>Owner
              <select id="opp-owner" value={form.ownerId} onChange={(e) => set({ ownerId: e.target.value })}>
                {people.data?.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
              </select>
            </label>
            <label>Value (₹)
              <input id="opp-value" type="number" min="0" step="1" value={form.valueInr} onChange={(e) => set({ valueInr: e.target.value })} />
            </label>
            <label>Probability (%)
              <input id="opp-probability" type="number" min="0" max="100" value={form.probability} onChange={(e) => set({ probability: e.target.value })} placeholder="Set by stage" />
            </label>
            <label>Expected close
              <input id="opp-close" type="date" value={form.expectedCloseDate} onChange={(e) => set({ expectedCloseDate: e.target.value })} />
            </label>
            <label>Target start
              <input id="opp-start" type="date" value={form.targetStartDate} onChange={(e) => set({ targetStartDate: e.target.value })} />
            </label>
            <label>Decision maker
              <select id="opp-dm" value={form.decisionMakerContactId} onChange={(e) => set({ decisionMakerContactId: e.target.value })}>
                <option value="">{form.accountId ? 'Choose a contact' : 'Choose an account first'}</option>
                {contacts.data?.map((c) => <option key={c.id} value={c.id}>{c.fullName}{c.title ? `, ${c.title}` : ''}</option>)}
              </select>
            </label>
          </div>
          <label className="wide">Client's need
            <textarea id="opp-need" rows={2} value={form.needSummary} onChange={(e) => set({ needSummary: e.target.value })} placeholder="What the client asked for, in their words" />
          </label>

          {form.type === 'STAFF_AUGMENTATION' ? (
            <>
              <h2>Requirements</h2>
              <p className="muted small">
                One line per role. A complete line is needed before Proposal (BR-BD-08). Separate skills with ;
                {requirementsLocked && ' These requirements went to Delivery when the opportunity was won and can no longer change.'}
              </p>
              <fieldset disabled={requirementsLocked} className="plain">
                {form.requirements.map((r, i) => (
                  <RequirementEditor key={i} index={i} r={r} seniorities={seniorities}
                    onChange={(patch) => set({ requirements: form.requirements.map((x, j) => (j === i ? { ...x, ...patch } : x)) })}
                    onRemove={() => set({ requirements: form.requirements.filter((_, j) => j !== i) })} />
                ))}
                <button type="button" className="secondary" onClick={() => set({ requirements: [...form.requirements, { ...emptyRequirement, startDate: form.targetStartDate }] })}>
                  Add requirement
                </button>
              </fieldset>
            </>
          ) : (
            <>
              <h2>Project</h2>
              <div className="grid">
                <label>Pricing model
                  <select id="opp-pricing" value={form.pricingModel} onChange={(e) => set({ pricingModel: e.target.value })}>
                    <option value="">Not decided</option>
                    <option value="FIXED_PRICE">Fixed price</option>
                    <option value="TIME_AND_MATERIALS">Time and materials</option>
                  </select>
                </label>
                <label>Estimate (₹)
                  <input id="opp-estimate" type="number" min="0" value={form.estimateInr} onChange={(e) => set({ estimateInr: e.target.value })} />
                </label>
                <label>Team shape
                  <input id="opp-team" value={form.teamShape} onChange={(e) => set({ teamShape: e.target.value })} placeholder="For example: 1 lead, 3 engineers, 1 QA" />
                </label>
                <label>Timeline
                  <input id="opp-timeline" value={form.timeline} onChange={(e) => set({ timeline: e.target.value })} placeholder="For example: 4 months from kick-off" />
                </label>
              </div>
              <label className="wide">Scope (needed before Proposal)
                <textarea id="opp-scope" rows={3} value={form.scopeSummary} onChange={(e) => set({ scopeSummary: e.target.value })} />
              </label>
            </>
          )}

          <Problems error={save.error} />
          {saved && !save.isPending && <div className="notice good" role="status">Saved.</div>}
          {editable && (
            <div className="actions">
              <button type="submit">{save.isPending ? 'Saving…' : existing ? 'Save changes' : 'Create opportunity'}</button>
            </div>
          )}
        </fieldset>
      </form>

      {existing && existing.type === 'STAFF_AUGMENTATION' && (
        <CandidatesPanel opportunityId={existing.id} requirements={existing.requirements} editable={canEditFulfilment(me)} />
      )}

      {existing && existing.history.length > 0 && (
        <>
          <h2>Stage history</h2>
          <ul className="history">
            {existing.history.map((h, i) => (
              <li key={i}>
                {formatDate(h.changedAt)}: {h.fromStage ? `${humanise(h.fromStage)} → ` : 'Created at '}{humanise(h.toStage)}
                <span className="muted"> by {h.changedBy}</span>
              </li>
            ))}
          </ul>
        </>
      )}
    </>
  );
}

function RequirementEditor({ index, r, seniorities, onChange, onRemove }: {
  index: number;
  r: RequirementRow;
  seniorities: string[];
  onChange: (patch: Partial<RequirementRow>) => void;
  onRemove: () => void;
}) {
  const id = (f: string) => `req-${index}-${f}`;
  return (
    <div className="requirement" role="group" aria-label={`Requirement ${index + 1}`}>
      <div className="grid">
        <label>Role<input id={id('role')} value={r.role} onChange={(e) => onChange({ role: e.target.value })} placeholder="Senior backend engineer" /></label>
        <label>Skills<input id={id('skills')} value={r.skills} onChange={(e) => onChange({ skills: e.target.value })} placeholder="Java; Spring; Kafka" /></label>
        <label>Seniority
          <select id={id('seniority')} value={r.seniority} onChange={(e) => onChange({ seniority: e.target.value })}>
            {seniorities.map((s) => <option key={s} value={s}>{humanise(s)}</option>)}
          </select>
        </label>
        <label>Headcount<input id={id('headcount')} type="number" min="1" value={r.headcount} onChange={(e) => onChange({ headcount: e.target.value })} /></label>
        <label>Location
          <select id={id('mode')} value={r.locationMode} onChange={(e) => onChange({ locationMode: e.target.value })}>
            <option value="ONSITE">Onsite</option><option value="HYBRID">Hybrid</option><option value="REMOTE">Remote</option>
          </select>
        </label>
        <label>City<input id={id('location')} value={r.location} onChange={(e) => onChange({ location: e.target.value })} /></label>
        <label>Start<input id={id('start')} type="date" value={r.startDate} onChange={(e) => onChange({ startDate: e.target.value })} /></label>
        <label>Months<input id={id('duration')} type="number" min="1" value={r.durationMonths} onChange={(e) => onChange({ durationMonths: e.target.value })} /></label>
        <label>Allocation (%)<input id={id('allocation')} type="number" min="1" max="100" value={r.allocationPercent} onChange={(e) => onChange({ allocationPercent: e.target.value })} /></label>
        <label>Rate (₹)<input id={id('rate')} type="number" min="0" value={r.rateInr} onChange={(e) => onChange({ rateInr: e.target.value })} /></label>
        <label>Per
          <select id={id('unit')} value={r.rateUnit} onChange={(e) => onChange({ rateUnit: e.target.value })}>
            <option value="MONTH">Month</option><option value="DAY">Day</option><option value="HOUR">Hour</option>
          </select>
        </label>
      </div>
      <div className="requirement-foot">
        {r.deploymentRequest
          ? <span className="pill good">Sent to Delivery: {humanise(r.deploymentRequest)}</span>
          : <button type="button" className="link" onClick={onRemove}>Remove requirement {index + 1}</button>}
      </div>
    </div>
  );
}

/** Current stage, the next step, and moving to any stage including Lost with a reason (FR-OPP-04, FR-OPP-06). */
function StagePanel({ o, editable }: { o: Opportunity; editable: boolean }) {
  const queryClient = useQueryClient();
  const reference = useReference();
  const stages = reference.data?.stages ?? [];
  const lossReasons = reference.data?.picklists.LOSS_REASON ?? [];
  const [target, setTarget] = useState('');
  const [reason, setReason] = useState('');
  const move = useMutation({
    mutationFn: (body: { stage: string; lossReasonCode?: string }) => api.post<Opportunity>(`/opportunities/${o.id}/stage`, body),
    onSuccess: (updated) => {
      queryClient.setQueryData(['opportunity', String(o.id)], updated);
      queryClient.invalidateQueries({ queryKey: ['opportunities'] });
      setTarget('');
      setReason('');
    },
  });

  const open = stages.filter((s) => s.outcome === 'OPEN');
  const index = open.findIndex((s) => s.code === o.stage);
  const next = index >= 0 && index < open.length - 1 ? open[index + 1] : stages.find((s) => s.outcome === 'WON');
  const isClosed = o.stageOutcome !== 'OPEN';

  return (
    <section className="stage-panel" aria-label="Stage">
      <ol className="stages">
        {stages.filter((s) => s.outcome !== 'LOST').map((s) => {
          const pos = stages.findIndex((x) => x.code === s.code);
          const cur = stages.findIndex((x) => x.code === o.stage);
          const state = s.code === o.stage ? 'current' : o.stageOutcome !== 'LOST' && pos < cur ? 'done' : '';
          return <li key={s.code} className={state}>{s.label}</li>;
        })}
      </ol>
      <p>
        {o.stageOutcome === 'LOST'
          ? <span className="pill bad">Lost: {humanise(o.lossReasonCode)}</span>
          : o.stageOutcome === 'WON'
            ? <span className="pill good">Won</span>
            : <span className="pill accent">{o.stageLabel}</span>}{' '}
        <span className="muted">Probability {o.probability}% · weighted {formatInr(o.weightedValueInr)}</span>
      </p>
      {editable && o.stageOutcome !== 'WON' && (
        <div className="actions">
          {!isClosed && next && (
            <button type="button" disabled={move.isPending} onClick={() => move.mutate({ stage: next.code })}>
              Move to {next.label}
            </button>
          )}
          <select id="stage-target" aria-label="Move to stage" value={target} onChange={(e) => { setTarget(e.target.value); move.reset(); }}>
            <option value="">{isClosed ? 'Reopen at…' : 'Other stage…'}</option>
            {stages.filter((s) => s.code !== o.stage).map((s) => <option key={s.code} value={s.code}>{s.label}</option>)}
          </select>
          {target === 'LOST' && (
            <select id="stage-reason" aria-label="Loss reason" value={reason} onChange={(e) => setReason(e.target.value)}>
              <option value="">Choose a loss reason</option>
              {lossReasons.map((r) => <option key={r.code} value={r.code}>{r.label}</option>)}
            </select>
          )}
          {target && (
            <button type="button" className="secondary" disabled={move.isPending}
              onClick={() => move.mutate(target === 'LOST' ? { stage: target, lossReasonCode: reason } : { stage: target })}>
              Move
            </button>
          )}
        </div>
      )}
      <Problems error={move.error} />
    </section>
  );
}
