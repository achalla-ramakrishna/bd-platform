import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router';
import { api, canEditPipeline, type Me } from '../lib/api';
import { formatDate } from '../lib/format';
import { ErrorNote } from './gtn';

/** Replies read by the reply agent (ADR-0024): what kind they are, a draft answer; the owner confirms. */
export type ReplyKind = 'INTERESTED' | 'QUESTION' | 'NOT_NOW' | 'REFERRAL' | 'OBJECTION' | 'NOT_INTERESTED' | 'UNSUBSCRIBE'
  | 'OUT_OF_OFFICE' | 'OTHER';

export const REPLY_KINDS: Record<ReplyKind, string> = {
  INTERESTED: 'Interested',
  QUESTION: 'Question',
  NOT_NOW: 'Not now',
  REFERRAL: 'Referral',
  OBJECTION: 'Objection',
  NOT_INTERESTED: 'Not interested',
  UNSUBSCRIBE: 'Stop contacting me',
  OUT_OF_OFFICE: 'Out of office',
  OTHER: 'Other',
};

/** What "Done" will do, so the owner knows before clicking. */
export const KIND_EFFECTS: Record<ReplyKind, string> = {
  INTERESTED: 'Logs the reply; next step: book the call.',
  QUESTION: 'Logs the reply; next step: answer them.',
  NOT_NOW: 'Logs the reply and sets a reminder for the date below (GTN: parked until then).',
  REFERRAL: 'Logs the reply; next step: reach the person they named.',
  OBJECTION: 'Logs the reply; next step: answer the concern.',
  NOT_INTERESTED: 'Closes the conversation (GTN: parked for six months).',
  UNSUBSCRIBE: 'Stops all contact: opted out, conversation closed, GTN track stopped.',
  OUT_OF_OFFICE: 'Nothing changes.',
  OTHER: 'Logs the reply.',
};

const TONES: Partial<Record<ReplyKind, string>> = {
  INTERESTED: 'good', QUESTION: 'accent', REFERRAL: 'accent', NOT_NOW: 'warn', OBJECTION: 'warn', NOT_INTERESTED: 'bad', UNSUBSCRIBE: 'bad',
};

export interface Reply {
  id: number;
  personType: 'CONTACT' | 'CONNECTION';
  personId: number;
  personName: string;
  personTitle?: string | null;
  company?: string | null;
  linkedinUrl?: string | null;
  email?: string | null;
  conversationId?: number | null;
  trackId?: number | null;
  ownerName: string;
  source: 'GMAIL' | 'PASTED';
  channel: 'LINKEDIN' | 'EMAIL' | 'WHATSAPP' | 'OTHER';
  replyText: string;
  receivedOn: string;
  status: 'READING' | 'READY' | 'FAILED' | 'DONE' | 'DISMISSED';
  kind?: ReplyKind | null;
  confidence?: string | null;
  summary?: string | null;
  revisitOn?: string | null;
  referralName?: string | null;
  referralRole?: string | null;
  nextStep?: string | null;
  draftAnswer?: string | null;
  finalKind?: ReplyKind | null;
  sentAnswer?: string | null;
  optedOut: boolean;
  error?: string | null;
  version: number;
}

export interface ReplyInbox {
  waiting: Reply[];
  recent: Reply[];
  last30Days: { read: number; confirmed: number; changed: number; optedOut: number; kinds: Record<string, number> };
  enabled: boolean;
}

export function KindPill({ kind }: { kind?: ReplyKind | null }) {
  if (!kind) return null;
  return <span className={`pill ${TONES[kind] ?? ''}`}>{REPLY_KINDS[kind]}</span>;
}

export function useReplyInbox(enabled: boolean) {
  return useQuery({
    queryKey: ['replies'],
    queryFn: () => api.get<ReplyInbox>('/replies'),
    enabled,
    refetchInterval: (q) => (q.state.data?.waiting.some((r) => r.status === 'READING') ? 3000 : 60_000),
  });
}

/** Where to open the conversation with them. */
export function personLink(r: Reply): string | null {
  return r.conversationId ? `/conversations/${r.conversationId}` : r.trackId ? `/network/${r.trackId}` : null;
}

export function RepliesPage({ me }: { me: Me }) {
  const inbox = useReplyInbox(canEditPipeline(me));
  if (!canEditPipeline(me)) return <p className="muted">Replies are for people who work the pipeline.</p>;
  if (inbox.isPending) return <p className="muted">Loading…</p>;
  if (inbox.error) return <div className="notice bad">{inbox.error.message}</div>;
  const { waiting, recent, last30Days: s, enabled } = inbox.data;
  return (
    <>
      <h1>Replies</h1>
      <p className="lead">The agent reads each reply, says what kind it is and drafts your answer. Check it, send the answer yourself,
        and click Done: the conversation or GTN contact is updated. Someone who asks us to stop is opted out straight away.</p>
      {!enabled && <div className="notice warn">The reply agent needs ANTHROPIC_API_KEY. Until then, handle replies on each page as before.</div>}
      <div className="cards">
        <div className="card"><div className="label">Waiting</div><div className="value">{waiting.length}</div></div>
        <div className="card"><div className="label">Read, 30 days</div><div className="value">{s.read}</div>
          <div className="muted small">{Object.entries(s.kinds).map(([k, n]) => `${REPLY_KINDS[k as ReplyKind] ?? k}: ${n}`).join(' · ') || '—'}</div></div>
        <div className="card"><div className="label">Agent's kind changed</div><div className="value">{s.confirmed ? `${Math.round((100 * s.changed) / s.confirmed)}%` : '—'}</div>
          <div className="muted small">of {s.confirmed} confirmed</div></div>
        <div className="card"><div className="label">Opted out</div><div className="value">{s.optedOut}</div></div>
      </div>
      {waiting.length === 0 && <p className="muted">No replies waiting. Gmail replies to GTN emails arrive here on their own; paste others with
        "Paste their reply" on a conversation or a GTN contact.</p>}
      <div className="queue">
        {waiting.map((r) => <ReplyCard key={r.id} reply={r} />)}
      </div>
      {recent.length > 0 && (
        <>
          <h2>Handled in the last 7 days</h2>
          <div className="table-wrap">
            <table>
              <thead><tr><th>Person</th><th>Reply</th><th>Kind</th><th /></tr></thead>
              <tbody>
                {recent.map((r) => (
                  <tr key={r.id}>
                    <td>{personLink(r) ? <Link to={personLink(r)!}>{r.personName}</Link> : r.personName}<span className="sub">{formatDate(r.receivedOn)}</span></td>
                    <td>{r.summary ?? r.replyText}</td>
                    <td>{r.status === 'DISMISSED' ? <span className="muted">Dismissed</span> : <KindPill kind={r.finalKind} />}
                      {r.finalKind && r.kind && r.finalKind !== r.kind && <span className="sub">agent said {REPLY_KINDS[r.kind]}</span>}</td>
                    <td>{r.optedOut && <UndoOptOut reply={r} />}</td>
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

function UndoOptOut({ reply }: { reply: Reply }) {
  const queryClient = useQueryClient();
  const undo = useMutation({
    mutationFn: () => api.post<Reply>(`/replies/${reply.id}/undo-opt-out`, {}),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['replies'] }),
  });
  return (
    <>
      <span className="pill bad">Opted out</span>{' '}
      <button type="button" className="link-plain" disabled={undo.isPending} onClick={() => undo.mutate()}>Undo</button>
      <ErrorNote error={undo.error} />
    </>
  );
}

export function ReplyCard({ reply: r }: { reply: Reply }) {
  const queryClient = useQueryClient();
  const [kind, setKind] = useState<ReplyKind | ''>(r.kind ?? '');
  const [answer, setAnswer] = useState(r.draftAnswer ?? '');
  const [sent, setSent] = useState(false);
  const [revisitOn, setRevisitOn] = useState(r.revisitOn ?? '');
  const [copied, setCopied] = useState(false);
  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ['replies'] });
    queryClient.invalidateQueries({ queryKey: ['conversation'] });
    queryClient.invalidateQueries({ queryKey: ['gtn'] });
  };
  const done = useMutation({
    mutationFn: () => api.post<Reply>(`/replies/${r.id}/done`, {
      kind: kind || null, answer: answer || null, answerSent: sent, revisitOn: kind === 'NOT_NOW' && revisitOn ? revisitOn : null, version: r.version,
    }),
    onSuccess: refresh,
  });
  const dismiss = useMutation({ mutationFn: () => api.post<Reply>(`/replies/${r.id}/dismiss`, {}), onSuccess: refresh });
  const again = useMutation({ mutationFn: () => api.post<Reply>(`/replies/${r.id}/again`, {}), onSuccess: refresh });
  const link = personLink(r);
  const where = r.channel === 'EMAIL' && r.email
    ? `https://mail.google.com/mail/?view=cm&fs=1&to=${encodeURIComponent(r.email)}&body=${encodeURIComponent(answer)}`
    : r.channel === 'LINKEDIN' && r.linkedinUrl ? r.linkedinUrl : null;
  return (
    <section className="queue-card" aria-label={`Reply from ${r.personName}`}>
      <h3>{link ? <Link to={link}>{r.personName}</Link> : r.personName} <KindPill kind={r.kind} />
        {r.confidence === 'LOW' && <span className="pill warn">agent unsure</span>}
        {r.optedOut && <span className="pill bad">Opted out</span>}</h3>
      <div className="muted small">{[r.personTitle, r.company].filter(Boolean).join(' · ')} · {r.channel.toLowerCase()} · {formatDate(r.receivedOn)}
        {r.source === 'GMAIL' && ' · found in Gmail (preview)'}</div>
      <blockquote className="reply-text">{r.replyText}</blockquote>
      {r.status === 'READING' && <p className="muted" role="status">The agent is reading it…</p>}
      {r.status === 'FAILED' && (
        <div className="notice warn">{r.error}{' '}
          <button type="button" className="link-plain" disabled={again.isPending} onClick={() => again.mutate()}>Read again</button></div>
      )}
      {r.optedOut && <p className="small">They asked us to stop, so they are opted out already. <UndoOptOut reply={r} /></p>}
      {r.summary && <p><strong>{r.summary}</strong>{r.nextStep && <span className="muted"> Next: {r.nextStep}</span>}</p>}
      {r.referralName && <p className="small">Referred to <strong>{r.referralName}</strong>{r.referralRole && `, ${r.referralRole}`}.</p>}
      {r.status !== 'READING' && (
        <>
          <div className="actions">
            <label className="small" htmlFor={`kind-${r.id}`}>Kind</label>
            <select id={`kind-${r.id}`} value={kind} onChange={(e) => setKind(e.target.value as ReplyKind)}>
              <option value="">Choose…</option>
              {(Object.keys(REPLY_KINDS) as ReplyKind[]).map((k) => <option key={k} value={k}>{REPLY_KINDS[k]}</option>)}
            </select>
            {kind === 'NOT_NOW' && (
              <>
                <label className="small" htmlFor={`revisit-${r.id}`}>Come back on</label>
                <input id={`revisit-${r.id}`} type="date" value={revisitOn} onChange={(e) => setRevisitOn(e.target.value)} />
              </>
            )}
          </div>
          {kind && <p className="muted small">Done: {KIND_EFFECTS[kind]}</p>}
          {kind !== 'UNSUBSCRIBE' && kind !== 'OUT_OF_OFFICE' && (
            <>
              <label className="small" htmlFor={`answer-${r.id}`}>Your answer (draft)</label>
              <textarea id={`answer-${r.id}`} rows={4} value={answer} onChange={(e) => setAnswer(e.target.value)} />
              <div className="actions">
                <button type="button" className="secondary" disabled={!answer} onClick={async () => {
                  try {
                    await navigator.clipboard.writeText(answer);
                    setCopied(true);
                  } catch {
                    setCopied(false);
                  }
                  if (where) window.open(where, '_blank', 'noopener');
                }}>{copied ? 'Copied' : where ? `Copy and open ${r.channel === 'EMAIL' ? 'Gmail' : 'LinkedIn'}` : 'Copy answer'}</button>
                <label className="small"><input type="checkbox" checked={sent} onChange={(e) => setSent(e.target.checked)} /> I sent this answer</label>
              </div>
            </>
          )}
          <div className="actions">
            <button type="button" disabled={!kind || done.isPending} onClick={() => done.mutate()}>Done</button>
            <button type="button" className="secondary" disabled={dismiss.isPending} onClick={() => dismiss.mutate()}>Dismiss</button>
          </div>
        </>
      )}
      <ErrorNote error={done.error ?? dismiss.error ?? again.error} />
    </section>
  );
}

/** "Paste their reply" on a conversation or a GTN contact: the agent reads it and it waits in Replies. */
export function PasteReply({ conversationId, trackId }: { conversationId?: number; trackId?: number }) {
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [text, setText] = useState('');
  const [channel, setChannel] = useState('LINKEDIN');
  const key = conversationId ? ['replies', 'conversation', conversationId] : ['replies', 'track', trackId];
  const list = useQuery({
    queryKey: key,
    queryFn: () => api.get<Reply[]>(conversationId ? `/replies/conversation/${conversationId}` : `/replies/track/${trackId}`),
    refetchInterval: (q) => (q.state.data?.some((r) => r.status === 'READING') ? 3000 : false),
  });
  const paste = useMutation({
    mutationFn: () => api.post<Reply>('/replies', { conversationId: conversationId ?? null, trackId: trackId ?? null, text, channel }),
    onSuccess: () => {
      setText('');
      setOpen(false);
      queryClient.invalidateQueries({ queryKey: ['replies'] });
    },
  });
  const waiting = (list.data ?? []).filter((r) => ['READING', 'READY', 'FAILED'].includes(r.status));
  return (
    <section aria-label="Replies">
      <div className="title-row">
        <h2>Their replies</h2>
        {!open && <button type="button" className="secondary" style={{ marginTop: 22 }} onClick={() => setOpen(true)}>Paste their reply</button>}
      </div>
      {open && (
        <div className="queue-card">
          <label className="small" htmlFor="paste-reply">What they wrote</label>
          <textarea id="paste-reply" rows={4} value={text} onChange={(e) => setText(e.target.value)} />
          <div className="actions">
            <select aria-label="Where they replied" value={channel} onChange={(e) => setChannel(e.target.value)}>
              <option value="LINKEDIN">LinkedIn</option>
              <option value="EMAIL">Email</option>
              {trackId && <option value="WHATSAPP">WhatsApp</option>}
              {trackId && <option value="OTHER">Other</option>}
            </select>
            <button type="button" disabled={!text.trim() || paste.isPending} onClick={() => paste.mutate()}>Read it</button>
            <button type="button" className="secondary" onClick={() => setOpen(false)}>Cancel</button>
          </div>
          <ErrorNote error={paste.error} />
        </div>
      )}
      {waiting.length > 0 && <div className="queue">{waiting.map((r) => <ReplyCard key={r.id} reply={r} />)}</div>}
      {waiting.length === 0 && !open && <p className="muted small">Paste a LinkedIn or other reply: the agent says what it means and drafts your answer.</p>}
    </section>
  );
}
