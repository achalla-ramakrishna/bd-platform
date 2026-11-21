import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../lib/api';
import { formatDate } from '../lib/format';
import { ErrorNote } from './gtn';

/** The BD agent's research on a person (ADR-0022). */
export type PersonType = 'CONTACT' | 'CONNECTION';

export interface ProspectBrief {
  headline: string;
  role: string;
  summary: string;
  whyNow: { statement: string; source: string }[];
  angle?: { offer: string; why: string } | null;
  talkingPoints: string[];
  connectionNote: string;
  cautions: string[];
  roleFit: number;
  roleFitReason: string;
  timing: number;
  timingReason: string;
}

export interface ProspectView {
  id: number;
  personType: PersonType;
  personId: number;
  personName: string;
  accountId?: number | null;
  accountName?: string | null;
  status: 'RUNNING' | 'DONE' | 'FAILED';
  brief?: ProspectBrief | null;
  sources: { url: string; title: string }[];
  roleFit?: number | null;
  timing?: number | null;
  access?: number | null;
  accessReason?: string | null;
  score?: number | null;
  error?: string | null;
  requestedBy: string;
  requestedAt: string;
}

export interface ResearchBatch {
  started: string[];
  skipped: string[];
  remainingToday: number;
}

export function useResearchStatus() {
  return useQuery({ queryKey: ['prospects', 'status'], queryFn: () => api.get<{ enabled: boolean; remainingToday: number }>('/prospects/status') });
}

export function ScorePill({ score }: { score?: number | null }) {
  if (score == null) return null;
  const tone = score >= 75 ? 'good' : score >= 50 ? 'accent' : '';
  return <span className={`pill ${tone}`} title="Agent score: role fit, timing and our access">{score}</span>;
}

const isLink = (s: string) => /^https?:\/\//.test(s);

/** The brief, with research / research again; polls while the agent works. */
export function ProspectBriefPanel({ type, id, editable }: { type: PersonType; id: number; editable: boolean }) {
  const queryClient = useQueryClient();
  const status = useResearchStatus();
  const key = ['prospects', type, id];
  const brief = useQuery({
    queryKey: key,
    queryFn: () => api.get<ProspectView | undefined>(`/prospects/${type.toLowerCase()}/${id}`),
    refetchInterval: (q) => (q.state.data?.status === 'RUNNING' ? 3000 : false),
  });
  const [copied, setCopied] = useState(false);
  const run = useMutation({
    mutationFn: (again: boolean) => api.post<ProspectView>(`/prospects/${type.toLowerCase()}/${id}/research${again ? '?again=true' : ''}`, {}),
    onSuccess: (v) => {
      queryClient.setQueryData(key, v);
      queryClient.invalidateQueries({ queryKey: ['prospects', 'status'] });
      queryClient.invalidateQueries({ queryKey: ['prospects', 'account'] });
    },
  });
  if (brief.isPending) return null;
  const v = brief.data;
  const b = v?.brief;
  const canRun = editable && status.data?.enabled;
  return (
    <section className="prospect-brief" aria-label="Research brief">
      <div className="title-row">
        <h2>Research brief {v?.score != null && <ScorePill score={v.score} />}</h2>
        {canRun && v?.status !== 'RUNNING' && (
          <button type="button" className={v ? 'secondary' : ''} disabled={run.isPending} onClick={() => run.mutate(!!v)}>
            {v ? 'Research again' : 'Research this person'}
          </button>
        )}
      </div>
      {!status.data?.enabled && !v && <p className="muted small">The research agent needs ANTHROPIC_API_KEY.</p>}
      {!v && status.data?.enabled && (
        <p className="muted small">The agent searches the public web (never LinkedIn) and our records, and writes who they are, why now, the
          best angle and a draft connection note. Professional information only. {status.data.remainingToday} research runs left today.</p>
      )}
      {v?.status === 'RUNNING' && <p className="muted" role="status">Researching… this takes a minute or two.</p>}
      {v?.error && <div className="notice bad">{v.error}</div>}
      <ErrorNote error={run.error} />
      {b && (
        <>
          <p className="lead">{b.headline}</p>
          <div className="cards">
            <div className="card"><div className="label">Role fit</div><div className="value">{b.roleFit}/5</div><div className="muted small">{b.roleFitReason}</div></div>
            <div className="card"><div className="label">Timing</div><div className="value">{b.timing}/5</div><div className="muted small">{b.timingReason}</div></div>
            <div className="card"><div className="label">Our access</div><div className="value">{v.access}/5</div><div className="muted small">{v.accessReason}</div></div>
          </div>
          <p><strong>What they own:</strong> {b.role}</p>
          <p>{b.summary}</p>
          {b.whyNow.length > 0 && (
            <>
              <div className="label">Why now</div>
              <ul>{b.whyNow.map((w) => <li key={w.statement}>{w.statement}{' '}
                {isLink(w.source) ? <a className="small" href={w.source} target="_blank" rel="noreferrer">source</a> : <span className="muted small">({w.source})</span>}</li>)}</ul>
            </>
          )}
          {b.angle && <p><strong>Angle:</strong> {b.angle.offer}. <span className="muted">{b.angle.why}</span></p>}
          {b.talkingPoints.length > 0 && (
            <>
              <div className="label">Talking points</div>
              <ul>{b.talkingPoints.map((t) => <li key={t}>{t}</li>)}</ul>
            </>
          )}
          <div className="label">Draft connection note ({b.connectionNote.length}/280)</div>
          <div className="draft-note">
            <p>{b.connectionNote}</p>
            <button type="button" className="secondary" onClick={async () => {
              try {
                await navigator.clipboard.writeText(b.connectionNote);
                setCopied(true);
              } catch {
                setCopied(false);
              }
            }}>{copied ? 'Copied' : 'Copy note'}</button>
            <span className="muted small"> Edit it and send it yourself on LinkedIn.</span>
          </div>
          {b.cautions.length > 0 && (
            <>
              <div className="label">Check before you reach out</div>
              <ul>{b.cautions.map((c) => <li key={c}>{c}</li>)}</ul>
            </>
          )}
          {v.sources.length > 0 && (
            <details>
              <summary className="muted small">{v.sources.length} {v.sources.length === 1 ? 'source' : 'sources'}</summary>
              <ul className="small">{v.sources.map((s) => <li key={s.url}><a href={s.url} target="_blank" rel="noreferrer">{s.title || s.url}</a></li>)}</ul>
            </details>
          )}
          <p className="muted small">Researched {formatDate(v.requestedAt)} for {v.requestedBy}. AI-written: check the facts.</p>
        </>
      )}
    </section>
  );
}

/** An account's people with their agent score, and "research contacts" (up to 10 at a time). */
export function useAccountBriefs(accountId: number) {
  return useQuery({
    queryKey: ['prospects', 'account', accountId],
    queryFn: () => api.get<ProspectView[]>(`/prospects/for-account/${accountId}`),
    refetchInterval: (q) => (q.state.data?.some((v) => v.status === 'RUNNING') ? 4000 : false),
  });
}

export function ResearchContactsButton({ accountId }: { accountId: number }) {
  const queryClient = useQueryClient();
  const status = useResearchStatus();
  const run = useMutation({
    mutationFn: () => api.post<ResearchBatch>(`/prospects/for-account/${accountId}/research`, {}),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['prospects'] });
    },
  });
  if (!status.data?.enabled) return null;
  return (
    <>
      <button type="button" className="secondary" style={{ marginTop: 22 }} disabled={run.isPending} onClick={() => run.mutate()}>
        Research contacts
      </button>
      {run.data && <span className="muted small" role="status" style={{ marginTop: 22 }}>
        Researching {run.data.started.length}{run.data.skipped.length ? `; ${run.data.skipped.length} skipped` : ''}.</span>}
      <ErrorNote error={run.error} />
    </>
  );
}
