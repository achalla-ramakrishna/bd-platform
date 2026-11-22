import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router';
import { api, canEditPipeline, type Me } from '../lib/api';
import { formatDate } from '../lib/format';
import { ErrorNote } from './gtn';

/** The ops agent (ADR-0026): a call brief and a draft opportunity for a booked call. */
export interface CallPrep {
  id: number;
  personType: 'CONTACT' | 'CONNECTION';
  personId: number;
  personName: string;
  accountId?: number | null;
  accountName?: string | null;
  conversationId?: number | null;
  trackId?: number | null;
  ownerName: string;
  callOn?: string | null;
  status: 'RUNNING' | 'DONE' | 'FAILED';
  prep?: {
    goal: string; about: string; needs: { point: string; source: string }[]; recap: string; questions: string[];
    concerns: { concern: string; answer: string }[]; nextStep: string;
  } | null;
  draft?: { verdict: 'YES' | 'MAYBE' | 'NOT_YET'; name: string; type: 'STAFF_AUGMENTATION' | 'PROJECT'; need: string; roles: string[] } | null;
  opportunityId?: number | null;
  opportunityName?: string | null;
  error?: string | null;
  createdAt: string;
}

const VERDICTS = { YES: 'There is an opportunity', MAYBE: 'Maybe an opportunity', NOT_YET: 'No opportunity yet' };

/** The prep for one conversation or GTN contact, with "Prepare for the call". */
export function CallPrepPanel({ conversationId, trackId, editable }: { conversationId?: number; trackId?: number; editable: boolean }) {
  const queryClient = useQueryClient();
  const key = ['call-prep', conversationId ? 'conversation' : 'track', conversationId ?? trackId];
  const prep = useQuery({
    queryKey: key,
    queryFn: () => api.get<CallPrep | undefined>(conversationId ? `/call-prep/conversation/${conversationId}` : `/call-prep/track/${trackId}`),
    refetchInterval: (q) => (q.state.data?.status === 'RUNNING' ? 3000 : false),
  });
  const run = useMutation({
    mutationFn: (again: boolean) => api.post<CallPrep>('/call-prep', { conversationId: conversationId ?? null, trackId: trackId ?? null, again }),
    onSuccess: (v) => queryClient.setQueryData(key, v),
  });
  if (prep.isPending) return null;
  const v = prep.data;
  return (
    <section aria-label="Call prep">
      <div className="title-row">
        <h2>Call prep{v?.callOn && <span className="muted" style={{ fontWeight: 400 }}> · call on {formatDate(v.callOn)}</span>}</h2>
        {editable && v?.status !== 'RUNNING' && (
          <button type="button" className="secondary" style={{ marginTop: 22 }} disabled={run.isPending} onClick={() => run.mutate(!!v)}>
            {v ? 'Prepare again' : 'Prepare for the call'}</button>
        )}
      </div>
      {!v && <p className="muted small">When a call is booked, the agent writes a brief (goal, what we know, questions to ask, likely
        concerns) and drafts the opportunity. You can also ask for it now.</p>}
      <ErrorNote error={run.error} />
      {v && <PrepBody v={v} editable={editable} />}
    </section>
  );
}

export function PrepBody({ v, editable }: { v: CallPrep; editable: boolean }) {
  if (v.status === 'RUNNING') return <p className="muted" role="status">The agent is preparing the call…</p>;
  if (v.status === 'FAILED') return <div className="notice warn">{v.error}</div>;
  const p = v.prep;
  if (!p) return null;
  return (
    <div className="prospect-brief">
      <p className="lead"><strong>Goal:</strong> {p.goal}</p>
      <p>{p.about}</p>
      {p.needs.length > 0 && (
        <>
          <div className="label">What we know they need</div>
          <ul>{p.needs.map((n) => <li key={n.point}>{n.point} <span className="muted small">({n.source})</span></li>)}</ul>
        </>
      )}
      <p><strong>So far:</strong> {p.recap}</p>
      <div className="label">Questions to ask</div>
      <ol>{p.questions.map((q) => <li key={q}>{q}</li>)}</ol>
      {p.concerns.length > 0 && (
        <>
          <div className="label">If they raise…</div>
          <ul>{p.concerns.map((c) => <li key={c.concern}><strong>{c.concern}:</strong> {c.answer}</li>)}</ul>
        </>
      )}
      <p><strong>Propose next:</strong> {p.nextStep}</p>
      <OpportunityDraft v={v} editable={editable} />
      <p className="muted small">Prepared {formatDate(v.createdAt)} for {v.ownerName}. AI-written: check it against what you know.</p>
    </div>
  );
}

function OpportunityDraft({ v, editable }: { v: CallPrep; editable: boolean }) {
  const queryClient = useQueryClient();
  const d = v.draft;
  const [name, setName] = useState(d?.name ?? '');
  const [type, setType] = useState<string>(d?.type ?? 'STAFF_AUGMENTATION');
  const [need, setNeed] = useState(d?.need ?? '');
  const create = useMutation({
    mutationFn: () => api.post<CallPrep>(`/call-prep/${v.id}/opportunity`, { name, type, need }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['call-prep'] });
      queryClient.invalidateQueries({ queryKey: ['conversation'] });
    },
  });
  if (v.opportunityId) {
    return <p className="notice good">Opportunity created: <Link to={`/opportunities/${v.opportunityId}`}>{v.opportunityName}</Link></p>;
  }
  if (!d) return null;
  return (
    <div className="queue-card">
      <div className="label">Draft opportunity · {VERDICTS[d.verdict]}</div>
      {d.roles.length > 0 && <p className="small">Roles or skills mentioned: {d.roles.join(', ')}</p>}
      {editable && v.accountId ? (
        <>
          <label className="small" htmlFor={`opp-name-${v.id}`}>Name</label>
          <input id={`opp-name-${v.id}`} value={name} onChange={(e) => setName(e.target.value)} />
          <label className="small" htmlFor={`opp-type-${v.id}`}>Type</label>
          <select id={`opp-type-${v.id}`} value={type} onChange={(e) => setType(e.target.value)}>
            <option value="STAFF_AUGMENTATION">Staff augmentation</option>
            <option value="PROJECT">Project</option>
          </select>
          <label className="small" htmlFor={`opp-need-${v.id}`}>The need</label>
          <textarea id={`opp-need-${v.id}`} rows={3} value={need} onChange={(e) => setNeed(e.target.value)} />
          <p className="muted small">Created at {v.accountName} with no value or dates: add those on the opportunity when you know them.</p>
          <button type="button" disabled={!name.trim() || create.isPending} onClick={() => create.mutate()}>Create opportunity</button>
          <ErrorNote error={create.error} />
        </>
      ) : (
        <>
          <p><strong>{d.name}</strong> · {d.type === 'PROJECT' ? 'Project' : 'Staff augmentation'}</p>
          <p className="small">{d.need}</p>
          {!v.accountId && <p className="muted small">Link {v.personName} to an account (on their connection page) to create it.</p>}
        </>
      )}
    </div>
  );
}

/** Network → Calls: my upcoming calls with their prep. */
export function CallsPage({ me }: { me: Me }) {
  const calls = useQuery({
    queryKey: ['call-prep', 'upcoming'],
    queryFn: () => api.get<CallPrep[]>('/call-prep'),
    enabled: canEditPipeline(me),
    refetchInterval: (q) => (q.state.data?.some((c) => c.status === 'RUNNING') ? 3000 : false),
  });
  const [open, setOpen] = useState<number | null>(null);
  if (!canEditPipeline(me)) return <p className="muted">Calls are for people who work the pipeline.</p>;
  if (calls.isPending) return <p className="muted">Loading…</p>;
  if (calls.error) return <div className="notice bad">{calls.error.message}</div>;
  return (
    <>
      <h1>Calls</h1>
      <p className="lead">Your booked calls, each with the agent's prep: the goal, what we know, questions to ask, and a draft opportunity
        you can create in one click.</p>
      {calls.data.length === 0 && <p className="muted">No calls coming up. When you book one on a conversation (meeting booked) or on GTN,
        it shows here with its prep.</p>}
      <div className="queue">
        {calls.data.map((c) => {
          const link = c.conversationId ? `/conversations/${c.conversationId}` : c.trackId ? `/network/${c.trackId}` : null;
          return (
            <section key={c.id} className="queue-card" aria-label={`Call with ${c.personName}`}>
              <div className="title-row">
                <h3>{link ? <Link to={link}>{c.personName}</Link> : c.personName}{' '}
                  <span className="muted small">{c.accountName ?? ''}{c.callOn ? ` · ${formatDate(c.callOn)}` : ''}</span></h3>
                <button type="button" className="link-plain" aria-expanded={open === c.id} onClick={() => setOpen(open === c.id ? null : c.id)}>
                  {open === c.id ? 'Hide prep' : 'Open prep'}</button>
              </div>
              {c.prep && open !== c.id && <p className="small">{c.prep.goal}</p>}
              {(open === c.id || c.status !== 'DONE') && <PrepBody v={c} editable />}
            </section>
          );
        })}
      </div>
    </>
  );
}
