import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  CANDIDATE_FLOW, CANDIDATE_STATUS_LABELS, ApiError, api,
  type Candidate, type CandidateStatus, type Engineer, type Requirement,
} from '../lib/api';
import { formatDate, humanise } from '../lib/format';
import { today } from './ConversationsPage';

/*
 * Realising an opportunity (ADR-0016): for each requirement, the candidates proposed (bench or external) and
 * their path to starting at the client. Profiles go to the client only with the engineer's consent (BR-BD-10).
 */

export function CandidateStatusPill({ s }: { s: CandidateStatus }) {
  const tone = s === 'STARTED' ? 'good' : s === 'SELECTED' || s === 'ONBOARDING' ? 'accent'
    : s === 'REJECTED' || s === 'WITHDRAWN' ? '' : 'warn';
  return <span className={`pill ${tone}`}>{CANDIDATE_STATUS_LABELS[s]}</span>;
}

/** The next moves offered as buttons for a candidate at a stage. */
export function nextMoves(s: CandidateStatus): CandidateStatus[] {
  if (s === 'REJECTED' || s === 'WITHDRAWN') return ['PROPOSED'];
  if (s === 'STARTED') return [];
  const i = CANDIDATE_FLOW.indexOf(s);
  const forward = CANDIDATE_FLOW[i + 1] ? [CANDIDATE_FLOW[i + 1]!] : [];
  // A client may select straight after seeing the profile.
  if (s === 'SHARED') forward.push('SELECTED');
  return [...forward, 'REJECTED', 'WITHDRAWN'];
}

const MOVE_LABEL: Partial<Record<CandidateStatus, string>> = {
  SHARED: 'Profile shared', INTERVIEW: 'Interview booked', SELECTED: 'Selected', ONBOARDING: 'Onboarding',
  STARTED: 'Started', REJECTED: 'Rejected', WITHDRAWN: 'Withdraw', PROPOSED: 'Reopen',
};

export function CandidatesPanel({ opportunityId, requirements, editable }: {
  opportunityId: number; requirements: Requirement[]; editable: boolean;
}) {
  const candidates = useQuery({
    queryKey: ['candidates', opportunityId],
    queryFn: () => api.get<Candidate[]>(`/opportunities/${opportunityId}/candidates`),
  });
  const saved = requirements.filter((r) => r.id);
  if (saved.length === 0) return null;
  return (
    <section className="candidates" aria-label="Candidates">
      <h2>Realise it: candidates</h2>
      <p className="muted small">
        For each requirement, propose engineers from the bench or external candidates and move them to a start:
        Proposed → Profile shared → Client interview → Selected → Onboarding → Started. Profiles go to the client only
        with the engineer's consent.
      </p>
      {saved.map((r) => {
        const list = (candidates.data ?? []).filter((c) => c.requirementId === r.id);
        const started = list.filter((c) => c.status === 'STARTED').length;
        return (
          <div key={r.id} className="requirement-candidates">
            <h3>
              {r.role} <span className="muted" style={{ fontWeight: 400 }}>· {humanise(r.seniority)} · {started} of {r.headcount} started · from {formatDate(r.startDate)}</span>
            </h3>
            {list.length === 0 && <p className="muted small">No candidates yet.</p>}
            {list.length > 0 && (
              <div className="table-wrap">
                <table>
                  <thead><tr><th>Candidate</th><th>Stage</th><th>Dates</th>{editable && <th>Move to</th>}</tr></thead>
                  <tbody>
                    {list.map((c) => <CandidateRow key={c.id} c={c} opportunityId={opportunityId} editable={editable} />)}
                  </tbody>
                </table>
              </div>
            )}
            {editable && <ProposeForm requirementId={r.id!} opportunityId={opportunityId} />}
          </div>
        );
      })}
    </section>
  );
}

function problemsOf(e: unknown) {
  return e instanceof ApiError && e.problems.length ? e.problems.join(' ') : e instanceof Error ? e.message : '';
}

function CandidateRow({ c, opportunityId, editable }: { c: Candidate; opportunityId: number; editable: boolean }) {
  const queryClient = useQueryClient();
  const [asking, setAsking] = useState<CandidateStatus | null>(null);
  const [on, setOn] = useState('');
  const move = useMutation({
    mutationFn: (body: { status: CandidateStatus; on: string | null }) =>
      api.post<Candidate>(`/candidates/${c.id}/move`, { ...body, version: c.version }),
    onSuccess: () => {
      setAsking(null);
      queryClient.invalidateQueries({ queryKey: ['candidates', opportunityId] });
      queryClient.invalidateQueries({ queryKey: ['fulfilment'] });
    },
  });
  const needsDate = (s: CandidateStatus) => s === 'INTERVIEW' || s === 'ONBOARDING';
  const choose = (s: CandidateStatus) => {
    if (needsDate(s)) {
      setAsking(s);
      setOn(today(s === 'INTERVIEW' ? 2 : 14));
    } else {
      move.mutate({ status: s, on: s === 'STARTED' ? today() : null });
    }
  };
  return (
    <tr>
      <td>
        {c.name}
        <span className="sub">{c.source}{c.seniority ? ` · ${humanise(c.seniority)}` : ''}{c.availability ? ` · ${humanise(c.availability)}` : ''}
          {c.engineerId && c.consent === false ? ' · no consent to share' : ''}</span>
      </td>
      <td><CandidateStatusPill s={c.status} /><span className="sub">since {formatDate(c.statusOn ?? c.createdAt)}</span></td>
      <td className="small">
        {c.interviewOn && <div>Interview {formatDate(c.interviewOn)}</div>}
        {c.startOn && <div>Start {formatDate(c.startOn)}</div>}
        {c.proposedBy && <div className="muted">Proposed by {c.proposedBy}</div>}
      </td>
      {editable && (
        <td>
          {asking ? (
            <form className="inline-form" onSubmit={(e) => { e.preventDefault(); move.mutate({ status: asking, on }); }}>
              <label className="small">{asking === 'INTERVIEW' ? 'Interview on' : 'Starts on'}{' '}
                <input type="date" value={on} onChange={(e) => setOn(e.target.value)} required />
              </label>{' '}
              <button type="submit" className="small-button" disabled={move.isPending}>Save</button>{' '}
              <button type="button" className="small-button secondary" onClick={() => setAsking(null)}>Cancel</button>
            </form>
          ) : (
            <div className="move-buttons">
              {nextMoves(c.status).map((s) => (
                <button key={s} type="button" disabled={move.isPending}
                  className={s === 'REJECTED' || s === 'WITHDRAWN' ? 'small-button secondary' : 'small-button'}
                  onClick={() => choose(s)}>{MOVE_LABEL[s]}</button>
              ))}
            </div>
          )}
          {move.error && <div className="small" role="alert" style={{ color: 'var(--bad)' }}>{problemsOf(move.error)}</div>}
        </td>
      )}
    </tr>
  );
}

function ProposeForm({ requirementId, opportunityId }: { requirementId: number; opportunityId: number }) {
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [kind, setKind] = useState<'bench' | 'external'>('bench');
  const [engineerId, setEngineerId] = useState('');
  const [name, setName] = useState('');
  const [source, setSource] = useState('');
  const engineers = useQuery({
    queryKey: ['engineers', 'all'],
    queryFn: () => api.get<Engineer[]>('/engineers'),
    enabled: open && kind === 'bench',
  });
  const save = useMutation({
    mutationFn: () => api.post<Candidate>(`/requirements/${requirementId}/candidates`,
      kind === 'bench' ? { engineerId: Number(engineerId) } : { candidateName: name, candidateSource: source || null }),
    onSuccess: () => {
      setEngineerId(''); setName(''); setSource(''); setOpen(false);
      queryClient.invalidateQueries({ queryKey: ['candidates', opportunityId] });
      queryClient.invalidateQueries({ queryKey: ['fulfilment'] });
    },
  });
  if (!open) return <button type="button" className="secondary small-button" onClick={() => setOpen(true)}>+ Propose a candidate</button>;
  const sorted = [...(engineers.data ?? [])].sort((a, b) =>
    Number(b.availability === 'BENCH') - Number(a.availability === 'BENCH') || a.fullName.localeCompare(b.fullName));
  return (
    <form className="form propose-form" aria-label="Propose a candidate" onSubmit={(e) => { e.preventDefault(); save.mutate(); }}>
      <div className="segmented" role="group" aria-label="Candidate from">
        <button type="button" className={kind === 'bench' ? 'on' : ''} onClick={() => setKind('bench')}>From our bench</button>
        <button type="button" className={kind === 'external' ? 'on' : ''} onClick={() => setKind('external')}>External</button>
      </div>
      <div className="grid">
        {kind === 'bench' ? (
          <label>Engineer
            <select value={engineerId} onChange={(e) => setEngineerId(e.target.value)} required>
              <option value="">{engineers.isPending ? 'Loading…' : 'Choose an engineer'}</option>
              {sorted.map((e) => (
                <option key={e.id} value={e.id}>
                  {e.fullName} · {e.primaryRole} · {humanise(e.seniority)} · {humanise(e.availability)}{e.consentToShareProfile ? '' : ' · no consent'}
                </option>
              ))}
            </select>
          </label>
        ) : (
          <>
            <label>Name
              <input value={name} maxLength={200} onChange={(e) => setName(e.target.value)} required />
            </label>
            <label>Source
              <input value={source} maxLength={200} placeholder="e.g. hiring partner, referral" onChange={(e) => setSource(e.target.value)} />
            </label>
          </>
        )}
      </div>
      {save.error && <div className="notice bad" role="alert">{problemsOf(save.error)}</div>}
      <div className="actions">
        <button type="submit" disabled={save.isPending}>{save.isPending ? 'Saving…' : 'Propose'}</button>
        <button type="button" className="secondary" onClick={() => setOpen(false)}>Cancel</button>
      </div>
    </form>
  );
}
