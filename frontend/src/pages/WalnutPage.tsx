import { useEffect, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router';
import {
  CHANNEL_LABELS, OUTCOME_LABELS, ApiError, api,
  type ConversationDetail, type WalnutAction, type WalnutMessage, type WalnutSettings, type WalnutStatus,
} from '../lib/api';
import { formatDate } from '../lib/format';
import { Markdown } from '../lib/markdown';
import { today } from './ConversationsPage';

/*
 * Walnut, the BD agent (ADR-0015). Each weekday morning it writes a plan (and emails it); people reply in
 * the chat, and Walnut proposes updates and drafts. Nothing is saved or sent until the person presses a button.
 */

const PROMPTS = [
  'Plan my day',
  'Which 5 accounts should I approach this week, and who should I connect with?',
  'Who accepted my connection requests? Draft the first messages.',
  'Draft follow-ups for my overdue conversations',
  'What is stuck in the pipeline, and what do I do about it?',
];

function problemText(e: unknown) {
  if (e instanceof ApiError && e.problems.length) return e.problems.join(' ');
  return (e instanceof Error && e.message) || 'Something went wrong. Try again.';
}

export function WalnutPage() {
  const queryClient = useQueryClient();
  const status = useQuery({ queryKey: ['walnut', 'status'], queryFn: () => api.get<WalnutStatus>('/walnut/status') });
  const available = status.data?.available ?? false;
  const messages = useQuery({
    queryKey: ['walnut', 'messages'],
    queryFn: () => api.get<WalnutMessage[]>('/walnut/messages'),
    enabled: available,
  });
  const settings = useQuery({ queryKey: ['walnut', 'settings'], queryFn: () => api.get<WalnutSettings>('/walnut/settings'), enabled: available });
  const [text, setText] = useState('');
  const [pending, setPending] = useState<string | null>(null);
  const end = useRef<HTMLDivElement>(null);
  const setThread = (add: WalnutMessage[]) => queryClient.setQueryData<WalnutMessage[]>(['walnut', 'messages'],
    (old) => [...(old ?? []).filter((m) => !add.some((a) => a.id === m.id)), ...add]);

  const brief = useMutation({ mutationFn: () => api.post<WalnutMessage>('/walnut/brief', {}), onSuccess: (m) => setThread([m]) });
  const send = useMutation({
    mutationFn: (t: string) => api.post<WalnutMessage[]>('/walnut/messages', { text: t }),
    onMutate: (t) => setPending(t),
    onSuccess: (ms) => { setThread(ms); setText(''); },
    onSettled: () => setPending(null),
  });
  const emailPref = useMutation({
    mutationFn: (on: boolean) => api.put<WalnutSettings>('/walnut/settings', { emailBrief: on }),
    onSuccess: (s) => queryClient.setQueryData(['walnut', 'settings'], s),
  });
  const emailNow = useMutation({ mutationFn: () => api.post<{ sent: boolean }>('/walnut/brief/email', {}) });

  useEffect(() => { end.current?.scrollIntoView?.({ block: 'end' }); }, [messages.data?.length, pending]);

  const submit = (t: string) => {
    if (!t.trim() || send.isPending) return;
    if (t === 'Plan my day') {
      brief.mutate();
      return;
    }
    send.mutate(t.trim());
  };

  if (status.isPending) return <p className="muted">Loading…</p>;
  if (!available) {
    return (
      <>
        <h1>Walnut</h1>
        <div className="notice">Walnut, the BD agent, works with {status.data?.worksWith.join(', ') || 'Admins'}. Ask an Admin if you need access.</div>
      </>
    );
  }
  const thread = messages.data ?? [];
  const chatOff = status.data && !status.data.chatEnabled;

  return (
    <>
      <div className="title-row">
        <div>
          <h1>Walnut <span className="muted" style={{ fontWeight: 400, fontSize: '0.6em' }}>your BD agent</span></h1>
        </div>
        <div className="actions">
          <button type="button" onClick={() => brief.mutate()} disabled={brief.isPending}>{brief.isPending ? 'Planning…' : 'Plan my day'}</button>
        </div>
      </div>
      <p className="lead">
        Every weekday at 8:30 Walnut writes your plan: who to follow up, which accounts to approach and who to connect
        with there, who accepted and needs a first message, deals to push and questions for you. Reply here; it drafts
        messages and proposes updates. Nothing is saved or sent until you press a button. Walnut works with{' '}
        {status.data?.worksWith.join(' and ')}.
      </p>
      <div className="walnut-settings small">
        {settings.data && (
          <label className="inline-check">
            <input type="checkbox" checked={settings.data.emailBrief} disabled={emailPref.isPending}
              onChange={(e) => emailPref.mutate(e.target.checked)} />
            Email me the morning plan ({settings.data.email})
          </label>
        )}
        {settings.data && !settings.data.emailAvailable && <span className="muted"> · email is not set up yet (MAIL_API_KEY)</span>}
        {settings.data?.emailAvailable && (
          <button type="button" className="link-plain" disabled={emailNow.isPending} onClick={() => emailNow.mutate()}>
            {emailNow.isSuccess ? 'Sent' : emailNow.isPending ? 'Sending…' : "Email me today's plan now"}
          </button>
        )}
        {emailNow.error && <span role="alert" style={{ color: 'var(--bad)' }}> {problemText(emailNow.error)}</span>}
      </div>
      {chatOff && (
        <div className="notice warn">Walnut writes your morning plan, but chatting needs <code>ANTHROPIC_API_KEY</code> to be set.</div>
      )}

      <div className="chat walnut" aria-live="polite">
        {messages.isPending && <p className="muted">Loading…</p>}
        {!messages.isPending && thread.length === 0 && (
          <div className="chat-empty muted">
            <p>Hi, I'm Walnut. Press <strong>Plan my day</strong> for today's plan, or ask me something below.</p>
          </div>
        )}
        {thread.map((m) => <Bubble key={m.id} m={m} />)}
        {pending && <div className="bubble you"><p className="pre">{pending}</p></div>}
        {(send.isPending || brief.isPending) && <div className="bubble ai muted">Walnut is thinking…</div>}
        {(send.error || brief.error) && <div className="notice bad" role="alert">{problemText(send.error ?? brief.error)}</div>}
        <div ref={end} />
      </div>

      <div className="prompts">
        {PROMPTS.map((p) => (
          <button key={p} type="button" className="chip" disabled={send.isPending || (chatOff && p !== 'Plan my day')}
            onClick={() => submit(p)}>{p}</button>
        ))}
      </div>
      <form className="composer" aria-label="Message Walnut" onSubmit={(e) => { e.preventDefault(); submit(text); }}>
        <textarea id="walnut-text" rows={2} value={text} maxLength={4000} disabled={chatOff} aria-label="Message Walnut"
          placeholder="Tell Walnut what happened, or ask what to do next"
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); submit(text); } }} />
        <div className="actions">
          <button type="submit" disabled={chatOff || send.isPending || !text.trim()}>{send.isPending ? 'Sending…' : 'Send'}</button>
          <span className="muted small">Enter to send · Shift+Enter for a new line</span>
        </div>
      </form>
    </>
  );
}

function Bubble({ m }: { m: WalnutMessage }) {
  if (m.author === 'PERSON') {
    return <div className="bubble you"><p className="pre">{m.body}</p></div>;
  }
  return (
    <div className={m.kind === 'CHAT' ? 'bubble ai' : 'bubble ai brief'}>
      <div className="walnut-meta small muted">Walnut · {formatDate(m.createdAt)}{m.kind !== 'CHAT' ? ' · morning plan' : ''}</div>
      <Markdown text={m.body} appLink={(path, label, key) => <Link key={key} to={path}>{label}</Link>} />
      {m.actions.length > 0 && (
        <div className="walnut-actions">
          {m.actions.map((a, i) => <ActionButton key={i} a={a} />)}
        </div>
      )}
    </div>
  );
}

/** One proposed action: open a page, copy a draft, or apply an update to a conversation. */
function ActionButton({ a }: { a: WalnutAction }) {
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [copied, setCopied] = useState(false);
  const apply = useMutation({
    mutationFn: async () => {
      const c = (await api.get<ConversationDetail>(`/conversations/${a.conversationId}`)).conversation;
      if (a.type === 'SET_NEXT_STEP') {
        return api.patch<ConversationDetail>(`/conversations/${a.conversationId}`, {
          nextStep: a.nextStep, nextStepDue: a.nextStepDue || null, version: c.version,
        });
      }
      return api.post<ConversationDetail>(`/conversations/${a.conversationId}/updates`, {
        channel: a.channel, occurredOn: today(), summary: a.summary, outcome: a.outcome, status: null,
        nextStep: a.nextStep || null, nextStepDue: a.nextStep ? a.nextStepDue || null : null, version: c.version,
      });
    },
    onSuccess: () => {
      for (const k of ['conversations', 'conversation', 'activities', 'heatmap']) queryClient.invalidateQueries({ queryKey: [k] });
    },
  });

  if (a.type === 'OPEN') return <Link className="button secondary small-button" to={a.path}>{a.label} →</Link>;
  if (a.type === 'DRAFT') {
    return (
      <div className="walnut-draft">
        <button type="button" className="secondary small-button" aria-expanded={open} onClick={() => setOpen(!open)}>✎ {a.label}</button>
        {open && (
          <div className="draft-box">
            <p className="pre">{a.text}</p>
            <button type="button" className="small-button" onClick={() => {
              void navigator.clipboard?.writeText(a.text).then(() => setCopied(true), () => setCopied(false));
            }}>{copied ? 'Copied' : 'Copy'}</button>
            <span className="muted small"> Send it yourself on LinkedIn or email, then tell me.</span>
          </div>
        )}
      </div>
    );
  }
  const what = a.type === 'SET_NEXT_STEP'
    ? <>Next step: <strong>{a.nextStep}</strong>{a.nextStepDue ? ` by ${formatDate(a.nextStepDue)}` : ''}</>
    : <>{CHANNEL_LABELS[a.channel] ?? a.channel}, {OUTCOME_LABELS[a.outcome] ?? a.outcome}: {a.summary}
        {a.nextStep && <> · next: <strong>{a.nextStep}</strong>{a.nextStepDue ? ` by ${formatDate(a.nextStepDue)}` : ''}</>}</>;
  return (
    <div className="walnut-update">
      <span className="small">{what}</span>
      {apply.isSuccess
        ? <span className="small" style={{ color: 'var(--good)' }}> Saved. <Link to={`/conversations/${a.conversationId}`}>Open</Link></span>
        : <button type="button" className="small-button" disabled={apply.isPending} onClick={() => apply.mutate()}>{apply.isPending ? 'Saving…' : a.label}</button>}
      {apply.error && <span className="small" role="alert" style={{ color: 'var(--bad)' }}> {problemText(apply.error)}</span>}
    </div>
  );
}
