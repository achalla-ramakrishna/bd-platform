import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate } from 'react-router';
import {
  ApiError, COMMITMENT_LABELS, GTN_CHANNEL_LABELS, GTN_STAGES, GTN_STAGE_LABELS, STRENGTH_LABELS, WHY_HINTS, WHY_LABELS, api,
  canEditPipeline,
  type CommitmentKind, type Connection, type GtnNext, type GtnStage, type GtnSuggestion, type GtnTemplate, type GtnTrack,
  type GtnTrackPage, type ImportResult, type Me, type WhyReason,
} from '../lib/api';
import { formatDate } from '../lib/format';
import { today } from './ConversationsPage';

/** GTN, Go To Network (ADR-0017, ADR-0018): shared pieces for Today, the board and a person's track. */

const STAGE_TONE: Record<GtnStage, string> = {
  INITIATE: '', RESPONDED: 'accent', UNRESPONSIVE: 'warn', INTRO_MADE: 'accent', CALL_SCHEDULED: 'accent',
  WARM_COMMITMENT: 'good', MONTHLY_BEEP: 'good',
};

export function StagePill({ stage }: { stage: GtnStage }) {
  return <span className={`pill ${STAGE_TONE[stage]}`}>{GTN_STAGE_LABELS[stage]}</span>;
}

export function problemsOf(e: unknown): string[] | null {
  return e instanceof ApiError && e.problems.length ? e.problems : null;
}

export function ErrorNote({ error }: { error: unknown }) {
  if (!error) return null;
  const problems = problemsOf(error);
  return (
    <div className="notice bad" role="alert">
      {problems ? <ul className="problems">{problems.map((p) => <li key={p}>{p}</li>)}</ul> : (error as Error).message}
    </div>
  );
}

type Person = Pick<GtnTrack, 'fullName' | 'linkedinUrl' | 'email' | 'whatsapp'>;

/** Where the message goes: WhatsApp click-to-chat, Gmail compose, or LinkedIn (copied, then the profile opens). */
export function sendLink(channel: string, p: Person, subject: string, body: string): string | null {
  if (channel === 'WHATSAPP') return p.whatsapp ? `https://wa.me/${p.whatsapp}?text=${encodeURIComponent(body)}` : null;
  if (channel === 'EMAIL') {
    return p.email ? `https://mail.google.com/mail/?view=cm&fs=1&to=${encodeURIComponent(p.email)}&su=${encodeURIComponent(subject)}`
      + `&body=${encodeURIComponent(body)}` : null;
  }
  if (channel === 'LINKEDIN') return p.linkedinUrl ?? null;
  return null;
}

const MESSAGE_CHANNELS = ['LINKEDIN', 'WHATSAPP', 'EMAIL'] as const;

/**
 * The message to send: pick a template and channel, edit the text, open the right app with it filled in, then
 * confirm "I sent it" so it is logged and the track moves. Nothing is sent by the platform (ADR-0005, ADR-0018).
 */
export function Composer({ person, suggestion, trackId, connectionId, stage, reason, confirmLabel = 'I sent it', busy, onSent }: {
  person: Person;
  suggestion?: GtnSuggestion | null;
  trackId?: number;
  connectionId?: number;
  stage: GtnStage;
  reason?: WhyReason | null;
  confirmLabel?: string;
  busy?: boolean;
  onSent: (m: { channel: string; summary: string; templateId: number | null }) => void;
}) {
  const templates = useQuery({ queryKey: ['gtn-templates'], queryFn: () => api.get<GtnTemplate[]>('/network/templates') });
  const [channel, setChannel] = useState<string>(suggestion?.channel ?? (person.linkedinUrl ? 'LINKEDIN' : person.whatsapp ? 'WHATSAPP' : 'EMAIL'));
  const [subject, setSubject] = useState(suggestion?.subject ?? '');
  const [body, setBody] = useState(suggestion?.body ?? '');
  const [templateId, setTemplateId] = useState<number | null>(suggestion?.templateId ?? null);
  const [copied, setCopied] = useState(false);
  const render = useMutation({
    mutationFn: (v: { templateId: number; channel: string }) => api.post<GtnSuggestion>('/network/render',
      { trackId: trackId ?? null, connectionId: trackId ? null : connectionId ?? null, ...v }),
    onSuccess: (s) => {
      setSubject(s.subject ?? '');
      setBody(s.body);
      setTemplateId(s.templateId ?? null);
    },
  });
  // Starting someone: fill the first template for the stage once they load.
  const options = (templates.data ?? []).filter((t) => t.stage === stage && (stage !== 'UNRESPONSIVE' || !reason || t.reason === reason));
  useEffect(() => {
    if (!suggestion && !templateId && options.length && !render.isPending && !body) {
      render.mutate({ templateId: options[0].id, channel });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [options.length]);

  const link = sendLink(channel, person, subject, body);
  const missing = channel === 'WHATSAPP' ? 'No WhatsApp number: add it on their connection page.'
    : channel === 'EMAIL' ? 'No email address: add it on their connection page.' : 'No LinkedIn profile on their connection page.';
  const openLinkedIn = async () => {
    try {
      await navigator.clipboard.writeText(body);
      setCopied(true);
    } catch {
      setCopied(false);
    }
    if (link) window.open(link, '_blank', 'noopener');
  };
  return (
    <div className="composer">
      <div className="grid">
        <label>Template
          <select aria-label="Template" value={templateId ?? ''} onChange={(e) => e.target.value && render.mutate({ templateId: Number(e.target.value), channel })}>
            <option value="">{options.length ? 'Choose…' : 'No template for this stage'}</option>
            {(templates.data ?? []).filter((t) => t.stage === stage || t.stage === 'MONTHLY_BEEP' && stage === 'WARM_COMMITMENT').map((t) => (
              <option key={t.id} value={t.id}>{t.name}{t.reason ? ` (${WHY_LABELS[t.reason]})` : ''}</option>
            ))}
          </select>
        </label>
        <div className="field">
          <span className="field-label">Send by</span>
          <div className="segmented" role="group" aria-label="Send by">
            {MESSAGE_CHANNELS.map((c) => (
              <button key={c} type="button" className={channel === c ? 'on' : ''} aria-pressed={channel === c} onClick={() => setChannel(c)}>
                {GTN_CHANNEL_LABELS[c]}
              </button>
            ))}
          </div>
        </div>
      </div>
      {channel === 'EMAIL' && (
        <label className="wide">Subject
          <input aria-label="Subject" value={subject} maxLength={200} onChange={(e) => setSubject(e.target.value)} />
        </label>
      )}
      <label className="wide">Message
        <textarea aria-label="Message" rows={4} maxLength={2000} value={body} onChange={(e) => setBody(e.target.value)} />
      </label>
      <div className="toolbar">
        {!link ? <span className="muted small">{missing}</span>
          : channel === 'LINKEDIN'
            ? <button type="button" className="secondary" onClick={() => void openLinkedIn()}>Copy message and open LinkedIn</button>
            : <a className="button secondary" href={link} target="_blank" rel="noreferrer">
              {channel === 'WHATSAPP' ? 'Open WhatsApp with the message' : 'Open in Gmail'}</a>}
        {copied && <span className="muted small">Copied. Paste it in the LinkedIn message box.</span>}
        <button type="button" disabled={busy || !body.trim()} onClick={() => onSent({ channel, summary: body.trim(), templateId })}>
          {confirmLabel}
        </button>
      </div>
    </div>
  );
}

/** What happened next, by stage (ADR-0017's rules); the server checks every move. */
export function StageActions({ page, onDone, compact }: { page: GtnTrackPage; onDone: (p: GtnTrackPage) => void; compact?: boolean }) {
  const t = page.detail.track;
  const queryClient = useQueryClient();
  const [open, setOpen] = useState<'' | 'notNow' | 'call' | 'commit' | 'move' | 'replied'>('');
  const [revisitOn, setRevisitOn] = useState(today(30));
  const [revisitReason, setRevisitReason] = useState('');
  const [callOn, setCallOn] = useState(today(2));
  const [kind, setKind] = useState<CommitmentKind>('REFERRAL');
  const [commitment, setCommitment] = useState('');
  const [replyNote, setReplyNote] = useState('');
  const [stage, setStage] = useState<GtnStage>(t.stage);
  const act = useMutation({
    mutationFn: (body: object) => api.post<GtnTrackPage>(`/network/tracks/${t.id}/actions`, { ...body, version: t.version }),
    onSuccess: (p) => {
      setOpen('');
      queryClient.invalidateQueries({ queryKey: ['gtn'] });
      onDone(p);
    },
  });
  const can = (stages: GtnStage[]) => stages.includes(t.stage);
  const replyChannel = t.lastChannel && ['LINKEDIN', 'EMAIL', 'WHATSAPP'].includes(t.lastChannel) ? t.lastChannel : 'LINKEDIN';
  return (
    <div className="stage-actions">
      <div className="toolbar quick-actions">
        {t.stage === 'UNRESPONSIVE' && !t.whyReason && (Object.keys(WHY_LABELS) as WhyReason[]).map((r) => (
          <button key={r} type="button" className={page.next.suggestedReason === r ? '' : 'secondary'} title={WHY_HINTS[r]}
            disabled={act.isPending}
            onClick={() => (r === 'NOT_RIGHT_TIME' ? setOpen('notNow') : act.mutate({ action: 'DIAGNOSED', reason: r }))}>
            {WHY_LABELS[r]}{page.next.suggestedReason === r ? ' (likely)' : ''}
          </button>
        ))}
        <button type="button" className="secondary" disabled={act.isPending} onClick={() => setOpen(open === 'replied' ? '' : 'replied')}>They replied</button>
        {can(['INITIATE', 'UNRESPONSIVE', 'RESPONDED', 'MONTHLY_BEEP']) && !(t.stage === 'UNRESPONSIVE' && !t.whyReason) && (
          <button type="button" className="secondary" onClick={() => setOpen(open === 'notNow' ? '' : 'notNow')}>Not the right time</button>
        )}
        {can(['RESPONDED']) && <button type="button" className="secondary" disabled={act.isPending} onClick={() => act.mutate({ action: 'INTRO_DONE' })}>Intro made</button>}
        {can(['RESPONDED', 'INTRO_MADE', 'MONTHLY_BEEP', 'CALL_SCHEDULED']) && (
          <button type="button" className="secondary" onClick={() => setOpen(open === 'call' ? '' : 'call')}>
            {t.stage === 'CALL_SCHEDULED' && t.callOn ? 'Reschedule the call' : 'Call booked'}</button>
        )}
        {can(['CALL_SCHEDULED']) && t.callOn && (
          <button type="button" className="secondary" disabled={act.isPending} onClick={() => act.mutate({ action: 'NO_SHOW' })}>No-show</button>
        )}
        {can(['INTRO_MADE', 'CALL_SCHEDULED']) && (
          <button type="button" className="secondary" onClick={() => setOpen(open === 'commit' ? '' : 'commit')}>
            {t.stage === 'CALL_SCHEDULED' ? 'Call held: record the commitment' : 'Warm commitment (no call)'}</button>
        )}
        {!compact && <button type="button" className="secondary" onClick={() => setOpen(open === 'move' ? '' : 'move')}>Move stage…</button>}
      </div>
      {open === 'replied' && (
        <form className="inline-form" onSubmit={(e) => { e.preventDefault(); act.mutate({ action: 'REPLIED', outcome: 'POSITIVE', channel: replyChannel, summary: replyNote || null }); }}>
          <input aria-label="What they said" placeholder="What they said (optional)" value={replyNote} maxLength={2000} onChange={(e) => setReplyNote(e.target.value)} />
          <button type="submit" disabled={act.isPending}>Save reply</button>
        </form>
      )}
      {open === 'notNow' && (
        <form className="inline-form" aria-label="Not the right time" onSubmit={(e) => {
          e.preventDefault();
          act.mutate(t.stage === 'UNRESPONSIVE' && !t.whyReason
            ? { action: 'DIAGNOSED', reason: 'NOT_RIGHT_TIME', revisitOn, revisitReason }
            : { action: 'REPLIED', outcome: 'NOT_RIGHT_TIME', channel: replyChannel, revisitOn, revisitReason });
        }}>
          <label>Revisit on <input type="date" aria-label="Revisit on" value={revisitOn} min={today(1)} onChange={(e) => setRevisitOn(e.target.value)} /></label>
          <input aria-label="Why not now" placeholder="Why, e.g. budget cycle, busy quarter, role change" value={revisitReason} maxLength={300}
            onChange={(e) => setRevisitReason(e.target.value)} />
          <button type="submit" disabled={act.isPending}>Park in monthly beep</button>
        </form>
      )}
      {open === 'call' && (
        <form className="inline-form" onSubmit={(e) => { e.preventDefault(); act.mutate({ action: 'CALL_BOOKED', callOn }); }}>
          <label>Call on <input type="date" aria-label="Call on" value={callOn} min={today(-1)} onChange={(e) => setCallOn(e.target.value)} /></label>
          <button type="submit" disabled={act.isPending}>Save</button>
        </form>
      )}
      {open === 'commit' && (
        <form className="inline-form" onSubmit={(e) => {
          e.preventDefault();
          act.mutate({ action: t.stage === 'CALL_SCHEDULED' ? 'CALL_HELD' : 'COMMITTED', commitmentKind: kind, commitment });
        }}>
          <select aria-label="Commitment" value={kind} onChange={(e) => setKind(e.target.value as CommitmentKind)}>
            {Object.entries(COMMITMENT_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
          </select>
          <input aria-label="What they agreed to" placeholder="What they agreed to" value={commitment} maxLength={500} onChange={(e) => setCommitment(e.target.value)} />
          <button type="submit" disabled={act.isPending}>Save commitment</button>
        </form>
      )}
      {open === 'move' && (
        <form className="inline-form" onSubmit={(e) => { e.preventDefault(); act.mutate({ action: 'MOVED', stage }); }}>
          <select aria-label="Stage" value={stage} onChange={(e) => setStage(e.target.value as GtnStage)}>
            {GTN_STAGES.map((s) => <option key={s} value={s}>{GTN_STAGE_LABELS[s]}</option>)}
          </select>
          <button type="submit" disabled={act.isPending}>Move</button>
        </form>
      )}
      <ErrorNote error={act.error} />
    </div>
  );
}

/** The message step for a track's next action: send (then it's logged and the track moves). */
export function SendStep({ page, onDone }: { page: GtnTrackPage; onDone: (p: GtnTrackPage) => void }) {
  const queryClient = useQueryClient();
  const t = page.detail.track;
  const n = page.next;
  const send = useMutation({
    mutationFn: (m: { channel: string; summary: string; templateId: number | null }) =>
      api.post<GtnTrackPage>(`/network/tracks/${t.id}/actions`, { action: n.task === 'INTRO' ? 'INTRO_DONE' : 'SENT', ...m, version: t.version }),
    onSuccess: (p) => {
      queryClient.invalidateQueries({ queryKey: ['gtn'] });
      onDone(p);
    },
  });
  // Waiting for a first reply or a call outcome: nothing to send; the stage actions say what happened.
  if (!n.suggestion && !['ASK_NOW', 'BEEP', 'NUDGE', 'INTRO', 'BOOK_CALL', 'CALL_REMINDER'].includes(n.task)) return null;
  if (n.task === 'CHECK') return null;
  if (t.stage === 'UNRESPONSIVE' && !t.whyReason) return null;
  const ask = n.task === 'ASK_NOW';
  return (
    <>
      <Composer key={`${t.id}-${t.version}`} person={t} suggestion={n.suggestion} trackId={t.id}
        stage={ask ? 'UNRESPONSIVE' : n.task === 'BEEP' ? 'MONTHLY_BEEP' : t.stage} reason={ask ? 'INERTIA' : t.whyReason}
        confirmLabel={ask ? 'I sent the ask' : n.task === 'INTRO' ? 'I sent the intro' : n.task === 'NUDGE' ? 'I sent the nudge'
          : n.task === 'BEEP' ? 'I sent the beep' : 'I sent it'}
        busy={send.isPending} onSent={(m) => send.mutate(m)} />
      <ErrorNote error={send.error} />
    </>
  );
}

/** Start someone from Connections on GTN: write the first message, send it, confirm (Initiate). */
export function StartGtn({ c, onStarted, onCancel }: { c: Pick<Connection, 'id' | 'fullName' | 'linkedinUrl' | 'email' | 'whatsapp'>; onStarted: (p: GtnTrackPage) => void; onCancel?: () => void }) {
  const queryClient = useQueryClient();
  const start = useMutation({
    mutationFn: (m: { channel: string; summary: string; templateId: number | null }) =>
      api.post<GtnTrackPage>('/network/tracks', { connectionId: c.id, ...m }),
    onSuccess: (p) => {
      queryClient.invalidateQueries({ queryKey: ['gtn'] });
      queryClient.invalidateQueries({ queryKey: ['connections'] });
      onStarted(p);
    },
  });
  return (
    <section className="form" aria-label={`Start ${c.fullName} on GTN`}>
      <h2>First message to {c.fullName}</h2>
      <p className="muted small">Send it from your own LinkedIn, WhatsApp or Gmail, then confirm. They move to Initiate; if they don't reply, they
        come back as Unresponsive.</p>
      <Composer person={c} connectionId={c.id} stage="INITIATE" confirmLabel="I sent it: start GTN" busy={start.isPending}
        onSent={(m) => start.mutate(m)} />
      <ErrorNote error={start.error} />
      {onCancel && <button type="button" className="link-plain" onClick={onCancel}>Cancel</button>}
    </section>
  );
}

/** On a connection's page: their GTN stage and next action, or a button to start. */
export function GtnCard({ c, me }: { c: Connection; me: Me }) {
  const [starting, setStarting] = useState(false);
  const navigate = useNavigate();
  const track = useQuery({
    queryKey: ['gtn', 'for-connection', c.id],
    queryFn: () => api.get<GtnTrackPage>(`/network/tracks/for-connection/${c.id}`).catch((e) => {
      if (e instanceof ApiError && e.status === 404) return null;
      throw e;
    }),
  });
  if (track.isPending) return null;
  const t = track.data?.detail.track;
  return (
    <>
      <div className="card wide-card gtn-card">
        <div className="label">GTN, Go To Network</div>
        <div className="value value-text">
          {t ? <><StagePill stage={t.stage} /> <Link to={`/network/${t.id}`}>{t.nextAction}</Link>{' '}
            <span className={t.overdue ? 'sub bad-text' : 'sub'}>{t.overdue ? 'overdue since ' : 'on '}{formatDate(t.nextActionOn)} · {t.ownerName}</span></>
            : <span className="muted">Not on GTN yet</span>}
          {!t && canEditPipeline(me) && !starting && <> <button type="button" className="secondary" onClick={() => setStarting(true)}>Start GTN</button></>}
        </div>
      </div>
      {starting && <StartGtn c={c} onStarted={(p) => navigate(`/network/${p.detail.track.id}`)} onCancel={() => setStarting(false)} />}
    </>
  );
}

/** Import people from a CSV: our template or LinkedIn's own Connections export (GTN FR-1.1). Check first, then import. */
export function ImportConnections({ onDone }: { onDone: () => void }) {
  const queryClient = useQueryClient();
  const [csv, setCsv] = useState('');
  const [fileName, setFileName] = useState('');
  const [sourceTag, setSourceTag] = useState('');
  const [strength, setStrength] = useState('ACQUAINTANCE');
  const [howKnown, setHowKnown] = useState('');
  const run = useMutation({
    mutationFn: (dryRun: boolean) => api.post<ImportResult>('/connections/import', { csv, sourceTag, strength, howKnown, dryRun }),
    onSuccess: (r) => {
      if (!r.dryRun) {
        queryClient.invalidateQueries({ queryKey: ['connections'] });
        queryClient.invalidateQueries({ queryKey: ['connection-sources'] });
      }
    },
  });
  const r = run.data;
  return (
    <section className="form" aria-label="Import connections">
      <h2>Import connections from a CSV</h2>
      <p className="muted small">
        Use our columns (Full name, Company, Role, LinkedIn, Email, WhatsApp, Source, How we know them) or the file LinkedIn gives you under
        Settings → Data privacy → Get a copy of your data → Connections. People already here are matched on LinkedIn, email, WhatsApp, or
        name and company, and you are added as someone who knows them.
      </p>
      <div className="grid">
        <label>CSV file
          <input type="file" aria-label="CSV file" accept=".csv,text/csv" onChange={async (e) => {
            const f = e.target.files?.[0];
            if (!f) return;
            setFileName(f.name);
            setCsv(await f.text());
            run.reset();
          }} />
        </label>
        <label>Source tag
          <input aria-label="Source tag" value={sourceTag} maxLength={100} placeholder="e.g. college alumni" onChange={(e) => setSourceTag(e.target.value)} />
        </label>
        <label>How well you know them
          <select aria-label="How well you know them" value={strength} onChange={(e) => setStrength(e.target.value)}>
            {Object.entries(STRENGTH_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
          </select>
        </label>
        <label>How you know them
          <input aria-label="How you know them" value={howKnown} maxLength={400} placeholder="e.g. worked together at a past employer"
            onChange={(e) => setHowKnown(e.target.value)} />
        </label>
      </div>
      {r && (
        <div className={r.dryRun ? 'notice warn' : 'notice good'} role="status">
          {r.dryRun ? 'Check: ' : 'Imported: '}{r.added} new{r.sample.length ? ` (${r.sample.slice(0, 5).join(', ')}${r.added > 5 ? '…' : ''})` : ''},
          {' '}{r.linked} already here that you now know too, {r.alreadyKnown} you already had{r.skipped ? `, ${r.skipped} skipped` : ''}.
          {r.problems.length > 0 && <ul className="problems">{r.problems.map((p) => <li key={`${p.row}-${p.message}`}>Row {p.row}: {p.message}</li>)}</ul>}
        </div>
      )}
      <ErrorNote error={run.error} />
      <div className="actions">
        <button type="button" className="secondary" disabled={!csv || run.isPending} onClick={() => run.mutate(true)}>Check {fileName}</button>
        <button type="button" disabled={!csv || run.isPending || !r?.dryRun} onClick={() => run.mutate(false)}>Import</button>
        <button type="button" className="link-plain" onClick={onDone}>Close</button>
      </div>
    </section>
  );
}

/** Today's row: who, why, and the step, with the message ready. */
export function TodayItem({ item }: { item: GtnNext }) {
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const t = item.track;
  const page = useQuery({
    queryKey: ['gtn', 'track', t.id],
    queryFn: () => api.get<GtnTrackPage>(`/network/tracks/${t.id}`),
    enabled: open,
  });
  const done = () => {
    setOpen(false);
    queryClient.invalidateQueries({ queryKey: ['gtn'] });
  };
  return (
    <li className={`today-item${item.overdueDays > 0 ? ' overdue' : ''}`}>
      <div className="today-head">
        <div>
          <Link to={`/network/${t.id}`} className="board-title">{t.fullName}</Link>
          <span className="muted small">{[t.role, t.company].filter(Boolean).join(' · ')}</span>
        </div>
        <div className="today-task">
          <StagePill stage={t.stage} /> <strong>{item.taskLabel}</strong>
          {t.askNow && <span className="pill accent">Visited your page{t.bestVideoPct ? ` · watched ${t.bestVideoPct}%` : ''}</span>}
          {item.overdueDays > 0 && <span className="pill bad">{item.overdueDays} day{item.overdueDays === 1 ? '' : 's'} overdue</span>}
          <button type="button" className={open ? 'secondary' : ''} onClick={() => setOpen(!open)} aria-expanded={open}>{open ? 'Close' : 'Do it'}</button>
        </div>
      </div>
      {!open && item.suggestion && <p className="muted small today-preview">{item.suggestion.body}</p>}
      {open && page.data && (
        <div className="today-body">
          {item.task === 'DIAGNOSE' && <p className="small">{t.fullName} hasn't replied in {page.data.rules.unresponsiveDays} days. Why?</p>}
          {t.revisitReason && <p className="small muted">They said: {t.revisitReason}</p>}
          <SendStep page={page.data} onDone={done} />
          <StageActions page={page.data} onDone={done} compact />
        </div>
      )}
      {open && page.isPending && <p className="muted">Loading…</p>}
    </li>
  );
}
