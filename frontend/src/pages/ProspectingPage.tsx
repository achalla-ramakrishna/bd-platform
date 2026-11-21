import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router';
import { api, canEditPipeline, type Me } from '../lib/api';
import { ErrorNote } from './gtn';
import { ProspectBriefPanel, ScorePill, type PersonType } from './ProspectBrief';
import { StartSequence } from './SequencesPage';

/** The daily prospecting queue (ADR-0023): the agent picks and drafts; you send on LinkedIn yourself. */
export interface QueueItem {
  id: number;
  personType: PersonType;
  personId: number;
  name: string;
  title?: string | null;
  company?: string | null;
  linkedinUrl?: string | null;
  accountId?: number | null;
  accountName?: string | null;
  score: number;
  headline?: string | null;
  whyNow?: string | null;
  draftNote: string;
  sentNote?: string | null;
  status: 'QUEUED' | 'SENT' | 'SKIPPED' | 'EXPIRED';
  skipReason?: string | null;
  skipNote?: string | null;
  conversationId?: number | null;
  trackId?: number | null;
}

export interface ProspectQueue {
  day: string;
  size: number;
  items: QueueItem[];
  last30Days: { sent: number; skipped: number; expired: number; edited: number; skipReasons: Record<string, number> };
  researchEnabled: boolean;
  researchLeftToday: number;
}

export const SKIP_REASONS: Record<string, string> = {
  WRONG_PERSON: 'Not the right person',
  NOT_NOW: 'Not now',
  IN_TOUCH: 'Already in touch',
  OTHER: 'Other',
};

/** The profile, or a LinkedIn people search by name and company when we have no profile address. Opened by a person. */
export function linkedinLink(item: Pick<QueueItem, 'linkedinUrl' | 'name' | 'company'>): string {
  if (item.linkedinUrl) return item.linkedinUrl;
  const q = [item.name, item.company].filter(Boolean).join(' ');
  return `https://www.linkedin.com/search/results/people/?keywords=${encodeURIComponent(q)}`;
}

export function ProspectingPage({ me }: { me: Me }) {
  const queryClient = useQueryClient();
  const queue = useQuery({
    queryKey: ['prospecting'],
    queryFn: () => api.get<ProspectQueue>('/prospecting/queue'),
    enabled: canEditPipeline(me),
  });
  const research = useMutation({
    mutationFn: () => api.post<{ started: number; message: string }>('/prospecting/research', {}),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['prospects'] }),
  });
  if (!canEditPipeline(me)) return <p className="muted">The prospecting queue is for people who work the pipeline.</p>;
  if (queue.isPending) return <p className="muted">Loading…</p>;
  if (queue.error) return <div className="notice bad">{queue.error.message}</div>;
  const q = queue.data;
  const open = q.items.filter((i) => i.status === 'QUEUED');
  const done = q.items.filter((i) => i.status !== 'QUEUED');
  const s = q.last30Days;
  return (
    <>
      <div className="title-row">
        <div>
          <h1>Prospecting</h1>
          <p className="lead">The agent picked the best people you can approach today and drafted a note for each. Edit it, copy it,
            send it yourself on LinkedIn, then mark it sent: the follow-up starts on its own.</p>
        </div>
        <div className="actions">
          <button type="button" className="secondary" style={{ marginTop: 22 }} onClick={() => queue.refetch()} disabled={queue.isFetching}>
            Refresh
          </button>
          {q.researchEnabled && (
            <button type="button" className="secondary" style={{ marginTop: 22 }} disabled={research.isPending || q.researchLeftToday === 0}
              onClick={() => research.mutate()}>Find more people</button>
          )}
        </div>
      </div>
      {research.data && <div className="notice" role="status">{research.data.message}</div>}
      <ErrorNote error={research.error} />

      <div className="cards">
        <div className="card"><div className="label">To do today</div><div className="value">{open.length}</div>
          <div className="muted small">of {q.size} a day</div></div>
        <div className="card"><div className="label">Sent, 30 days</div><div className="value">{s.sent}</div>
          <div className="muted small">{s.sent ? `${Math.round((100 * s.edited) / s.sent)}% notes edited` : 'none yet'}</div></div>
        <div className="card"><div className="label">Skipped, 30 days</div><div className="value">{s.skipped}</div>
          <div className="muted small">{Object.entries(s.skipReasons).map(([r, n]) => `${SKIP_REASONS[r] ?? r}: ${n}`).join(' · ') || '—'}</div></div>
        <div className="card"><div className="label">Research left today</div><div className="value">{q.researchLeftToday}</div>
          <div className="muted small">for the whole team</div></div>
      </div>

      {open.length === 0 && (
        <p className="muted">Nobody left in today's queue. {q.researchEnabled
          ? 'Find more people researches a few more you could approach; they join when they score well.'
          : 'The research agent needs ANTHROPIC_API_KEY.'} People join the queue from accounts you own and connections you know.</p>
      )}
      <div className="queue">
        {open.map((item) => <QueueCard key={item.id} item={item} />)}
      </div>

      {done.length > 0 && (
        <>
          <h2>Done today</h2>
          <div className="table-wrap">
            <table>
              <thead><tr><th>Person</th><th>Score</th><th>What happened</th></tr></thead>
              <tbody>
                {done.map((i) => (
                  <tr key={i.id}>
                    <td>{i.name}<span className="sub">{[i.title, i.company].filter(Boolean).join(' · ')}</span></td>
                    <td><ScorePill score={i.score} /></td>
                    <td>{i.status === 'SENT'
                      ? <>Sent{i.sentNote !== i.draftNote && ' (edited)'}{' · '}
                        {i.conversationId ? <Link to={`/conversations/${i.conversationId}`}>Conversation</Link>
                          : i.trackId ? <Link to={`/network/${i.trackId}`}>GTN</Link> : null}</>
                      : i.status === 'SKIPPED' ? `Skipped: ${SKIP_REASONS[i.skipReason ?? ''] ?? i.skipReason}${i.skipNote ? ` (${i.skipNote})` : ''}`
                        : 'Expired'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </>
  );
}

function QueueCard({ item }: { item: QueueItem }) {
  const queryClient = useQueryClient();
  const [note, setNote] = useState(item.draftNote);
  const [copied, setCopied] = useState(false);
  const [skipping, setSkipping] = useState(false);
  const [reason, setReason] = useState('');
  const [why, setWhy] = useState('');
  const [brief, setBrief] = useState(false);
  const done = () => queryClient.invalidateQueries({ queryKey: ['prospecting'] });
  const sent = useMutation({
    mutationFn: () => api.post<QueueItem>(`/prospecting/queue/${item.id}/sent`, { note }),
    onSuccess: done,
  });
  const skip = useMutation({
    mutationFn: () => api.post<QueueItem>(`/prospecting/queue/${item.id}/skip`, { reason, note: why }),
    onSuccess: done,
  });
  const copyAndOpen = async () => {
    try {
      await navigator.clipboard.writeText(note);
      setCopied(true);
    } catch {
      setCopied(false);
    }
    window.open(linkedinLink(item), '_blank', 'noopener');
  };
  const page = item.personType === 'CONTACT' ? (item.accountId ? `/accounts/${item.accountId}` : null) : `/connections/${item.personId}`;
  return (
    <section className="queue-card" aria-label={item.name}>
      <div className="title-row">
        <div>
          <h3>{page ? <Link to={page}>{item.name}</Link> : item.name} <ScorePill score={item.score} /></h3>
          <div className="muted small">{[item.title, item.company].filter(Boolean).join(' · ')}
            {item.personType === 'CONNECTION' && ' · in your network'}</div>
        </div>
      </div>
      {item.headline && <p>{item.headline}</p>}
      {item.whyNow && <p className="small"><strong>Why now:</strong> {item.whyNow}</p>}
      <label className="small" htmlFor={`note-${item.id}`}>Note ({note.length}/280)</label>
      <textarea id={`note-${item.id}`} rows={3} value={note} onChange={(e) => setNote(e.target.value)} />
      {note.length > 280 && <p className="small bad-text">Keep it under 280 characters: LinkedIn limits connection notes.</p>}
      <div className="actions">
        <button type="button" onClick={copyAndOpen}>{copied ? 'Copied · open LinkedIn again' : 'Copy note and open LinkedIn'}</button>
        <button type="button" className="secondary" disabled={sent.isPending || !note.trim()} onClick={() => sent.mutate()}>I sent it</button>
        <button type="button" className="secondary" onClick={() => setSkipping(!skipping)}>Skip</button>
        <button type="button" className="link-plain" aria-expanded={brief} onClick={() => setBrief(!brief)}>{brief ? 'Hide brief' : 'Brief'}</button>
      </div>
      {skipping && (
        <div className="actions">
          <select aria-label="Why skip" value={reason} onChange={(e) => setReason(e.target.value)}>
            <option value="">Why?</option>
            {Object.entries(SKIP_REASONS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
          </select>
          <input aria-label="Skip note" placeholder="A few words (optional)" value={why} onChange={(e) => setWhy(e.target.value)} />
          <button type="button" className="secondary" disabled={!reason || skip.isPending} onClick={() => skip.mutate()}>Skip them</button>
        </div>
      )}
      <ErrorNote error={sent.error ?? skip.error} />
      {item.personType === 'CONTACT' && <StartSequence contactId={item.personId} />}
      {brief && <ProspectBriefPanel type={item.personType} id={item.personId} editable />}
    </section>
  );
}
