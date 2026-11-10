import { useEffect, useRef, useState, type ClipboardEvent, type DragEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router';
import {
  CHANNEL_LABELS, OUTCOME_LABELS, ApiError, api, canEditPipeline,
  type AssistantAnswer, type Connection, type ConversationDetail, type Me,
} from '../lib/api';
import { formatDate } from '../lib/format';
import { today } from './ConversationsPage';

/*
 * The BD assistant (ADR-0013). People type what they did, or paste screenshots of a conversation they had
 * themselves, and Claude reads who it was with and what happened. The page then offers to update the
 * matching conversation, start one, or add or update the connection. Nothing is saved until the person
 * presses a button, and then only through the usual endpoints. The chat itself lives in this browser tab.
 */

const MAX_IMAGES = 4;
const MAX_BYTES = 5 * 1024 * 1024;
const IMAGE_TYPES = ['image/png', 'image/jpeg', 'image/webp', 'image/gif'];

type Shot = { file: File; url: string };
type Message =
  | { id: number; from: 'you'; text: string; shots: Shot[] }
  | { id: number; from: 'ai'; answer: AssistantAnswer }
  | { id: number; from: 'error'; text: string };

let nextId = 1;

/** Longest side sent to Claude; it scales larger images down anyway, so this only saves upload time. */
const MAX_SIDE = 2000;
const SHRINK_ABOVE = 1.5 * 1024 * 1024;

/** Scales a large screenshot down in the browser; returns the original if it is small or cannot be drawn. */
async function shrink(file: File): Promise<File> {
  if (file.type === 'image/gif' || typeof createImageBitmap !== 'function') return file;
  try {
    const bitmap = await createImageBitmap(file);
    const scale = Math.min(1, MAX_SIDE / Math.max(bitmap.width, bitmap.height));
    if (scale === 1 && file.size <= SHRINK_ABOVE) return file;
    const canvas = document.createElement('canvas');
    canvas.width = Math.round(bitmap.width * scale);
    canvas.height = Math.round(bitmap.height * scale);
    canvas.getContext('2d')!.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
    const blob = await new Promise<Blob | null>((res) => canvas.toBlob(res, 'image/jpeg', 0.9));
    return blob && blob.size < file.size ? new File([blob], file.name.replace(/\.\w+$/, '') + '.jpg', { type: 'image/jpeg' }) : file;
  } catch {
    return file;
  }
}

export function AssistantPage({ me }: { me: Me }) {
  const status = useQuery({ queryKey: ['assistant', 'status'], queryFn: () => api.get<{ enabled: boolean }>('/assistant/status') });
  const [messages, setMessages] = useState<Message[]>([]);
  const [text, setText] = useState('');
  const [shots, setShots] = useState<Shot[]>([]);
  const [warning, setWarning] = useState<string | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const end = useRef<HTMLDivElement>(null);

  useEffect(() => { end.current?.scrollIntoView?.({ behavior: 'smooth', block: 'end' }); }, [messages.length]);

  const addFiles = async (files: File[]) => {
    const images = files.filter((f) => IMAGE_TYPES.includes(f.type));
    if (images.length < files.length) setWarning('Only PNG, JPEG, WebP or GIF images can be added.');
    const shrunk = await Promise.all(images.map(shrink));
    const small = shrunk.filter((f) => f.size <= MAX_BYTES);
    if (small.length < shrunk.length) setWarning('Each screenshot must be 5 MB or less.');
    setShots((current) => {
      const room = MAX_IMAGES - current.length;
      if (small.length > room) setWarning(`Add at most ${MAX_IMAGES} screenshots at a time.`);
      const added = small.slice(0, Math.max(room, 0)).map((file) => ({ file, url: URL.createObjectURL(file) }));
      return added.length ? [...current, ...added] : current;
    });
  };

  const send = useMutation({
    mutationFn: (m: { text: string; shots: Shot[] }) => {
      const form = new FormData();
      if (m.text.trim()) form.append('text', m.text.trim());
      m.shots.forEach((s) => form.append('images', s.file, s.file.name || 'screenshot.png'));
      return api.postForm<AssistantAnswer>('/assistant/read', form);
    },
    onSuccess: (answer) => setMessages((ms) => [...ms, { id: nextId++, from: 'ai', answer }]),
    onError: (e) => setMessages((ms) => [...ms, { id: nextId++, from: 'error', text: errorText(e) }]),
  });

  const submit = () => {
    if (send.isPending || (!text.trim() && shots.length === 0)) return;
    setMessages((ms) => [...ms, { id: nextId++, from: 'you', text: text.trim(), shots }]);
    send.mutate({ text, shots });
    setText('');
    setShots([]);
    setWarning(null);
  };

  const onPaste = (e: ClipboardEvent) => {
    const files = Array.from(e.clipboardData.files);
    if (files.length) {
      e.preventDefault();
      void addFiles(files);
    }
  };
  const onDrop = (e: DragEvent) => {
    e.preventDefault();
    void addFiles(Array.from(e.dataTransfer.files));
  };

  const editable = canEditPipeline(me);
  const disabled = status.data && !status.data.enabled;

  return (
    <>
      <div className="title-row">
        <h1>Log with AI</h1>
        {messages.length > 0 && <button type="button" className="secondary" onClick={() => setMessages([])}>New chat</button>}
      </div>
      <p className="lead">
        Tell it what you did, or paste screenshots of a conversation you had. It works out who it was with and what
        happened, then offers to update the conversation or connection. Nothing is saved until you press a button.
      </p>
      {!editable && <div className="notice">Your role can read conversations but not log them.</div>}
      {disabled && (
        <div className="notice warn" role="status">
          The assistant is switched off. An Admin needs to set <code>ANTHROPIC_API_KEY</code> on the server
          (see docs/deployment.md). You can still log touches on the <Link to="/conversations">Conversations</Link> page.
        </div>
      )}

      <div className="chat" aria-live="polite">
        {messages.length === 0 && (
          <div className="chat-empty muted">
            <p>For example:</p>
            <ul>
              <li>“Called Person A, Head of Engineering at Example GCC. They want two React engineers from March. Send profiles by Friday.”</li>
              <li>Paste a screenshot of a LinkedIn or email thread with Ctrl+V / ⌘V.</li>
              <li>“Met Person B at the meetup yesterday, connected on LinkedIn. Platform lead at Example Bank GCC.”</li>
            </ul>
            <p className="small">Screenshots go to Anthropic’s Claude to be read and are not stored. The app reads nothing from LinkedIn itself.</p>
          </div>
        )}
        {messages.map((m) => (
          m.from === 'you' ? (
            <div key={m.id} className="bubble you">
              {m.text && <p className="pre">{m.text}</p>}
              {m.shots.length > 0 && (
                <div className="shots">{m.shots.map((s) => <img key={s.url} src={s.url} alt="Screenshot you added" />)}</div>
              )}
            </div>
          ) : m.from === 'error' ? (
            <div key={m.id} className="bubble ai"><div className="notice bad" role="alert">{m.text}</div></div>
          ) : (
            <div key={m.id} className="bubble ai"><Proposals answer={m.answer} editable={editable} /></div>
          )
        ))}
        {send.isPending && <div className="bubble ai muted">Reading…</div>}
        <div ref={end} />
      </div>

      <form className="composer" aria-label="Message the assistant" onDragOver={(e) => e.preventDefault()} onDrop={onDrop}
        onSubmit={(e) => { e.preventDefault(); submit(); }}>
        {shots.length > 0 && (
          <div className="shots">
            {shots.map((s, i) => (
              <span key={s.url} className="shot">
                <img src={s.url} alt={`Screenshot ${i + 1}`} />
                <button type="button" className="link" aria-label={`Remove screenshot ${i + 1}`}
                  onClick={() => setShots(shots.filter((x) => x !== s))}>×</button>
              </span>
            ))}
          </div>
        )}
        <textarea id="assistant-text" rows={3} value={text} maxLength={20000} disabled={!editable || disabled}
          aria-label="What happened"
          placeholder="What happened? Type it, or paste or drop screenshots here."
          onPaste={onPaste} onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); submit(); } }} />
        {warning && <p className="small" role="alert" style={{ color: 'var(--warn)' }}>{warning}</p>}
        <div className="actions">
          <button type="submit" disabled={!editable || disabled || send.isPending || (!text.trim() && shots.length === 0)}>
            {send.isPending ? 'Reading…' : 'Send'}
          </button>
          <button type="button" className="secondary" disabled={!editable || disabled || shots.length >= MAX_IMAGES}
            onClick={() => fileInput.current?.click()}>Add screenshot</button>
          <input ref={fileInput} type="file" accept={IMAGE_TYPES.join(',')} multiple hidden aria-label="Choose screenshots"
            onChange={(e) => { void addFiles(Array.from(e.target.files ?? [])); e.target.value = ''; }} />
          <span className="muted small">Ctrl+Enter to send</span>
        </div>
      </form>
    </>
  );
}

function errorText(e: unknown): string {
  if (e instanceof ApiError && e.problems.length) return e.problems.join(' ');
  return e instanceof Error ? e.message : 'Something went wrong.';
}

type Tab = 'UPDATE_CONVERSATION' | 'START_CONVERSATION' | 'CONNECTION';

/** The assistant's reply: what it read, and one editable card per thing it can do. */
export function Proposals({ answer, editable }: { answer: AssistantAnswer; editable: boolean }) {
  const r = answer.reading;
  const hasConversation = answer.conversations.length > 0;
  const [tab, setTab] = useState<Tab>(answer.suggestion === 'ADD_CONNECTION' ? 'CONNECTION' : answer.suggestion);
  const tabs: { key: Tab; label: string }[] = [
    ...(hasConversation ? [{ key: 'UPDATE_CONVERSATION' as Tab, label: 'Update conversation' }] : []),
    { key: 'START_CONVERSATION', label: 'Start conversation' },
    { key: 'CONNECTION', label: answer.connections.length ? 'Update connection' : 'Add connection' },
  ];
  return (
    <>
      {r.reply && <p className="pre">{r.reply}</p>}
      <dl className="reading">
        {r.fullName && <><dt>Who</dt><dd>{r.fullName}{r.title ? `, ${r.title}` : ''}{r.company ? ` at ${r.company}` : ''}</dd></>}
        {r.channel && <><dt>How</dt><dd>{CHANNEL_LABELS[r.channel] ?? r.channel}{r.occurredOn ? ` on ${formatDate(r.occurredOn)}` : ''}</dd></>}
        {r.outcome && <><dt>Outcome</dt><dd>{OUTCOME_LABELS[r.outcome] ?? r.outcome}</dd></>}
        {r.nextStep && <><dt>Next</dt><dd>{r.nextStep}{r.nextStepDue ? ` by ${formatDate(r.nextStepDue)}` : ''}</dd></>}
      </dl>
      {editable && (
        <>
          <div className="tabs" role="tablist" aria-label="What to do">
            {tabs.map((t) => (
              <button key={t.key} type="button" role="tab" aria-selected={tab === t.key}
                className={tab === t.key ? 'tab active' : 'tab'} onClick={() => setTab(t.key)}>{t.label}</button>
            ))}
          </div>
          {/* Keep every card mounted so edits and "done" states survive switching tabs. */}
          <div hidden={tab !== 'UPDATE_CONVERSATION'}>{hasConversation && <UpdateConversation answer={answer} />}</div>
          <div hidden={tab !== 'START_CONVERSATION'}><StartConversation answer={answer} /></div>
          <div hidden={tab !== 'CONNECTION'}><ConnectionCard answer={answer} /></div>
        </>
      )}
    </>
  );
}

type Touch = { channel: string; occurredOn: string; summary: string; outcome: string; nextStep: string; nextStepDue: string };

function touchFrom(answer: AssistantAnswer): Touch {
  const r = answer.reading;
  return {
    channel: r.channel || 'LINKEDIN_MESSAGE', occurredOn: r.occurredOn || today(), summary: r.summary,
    outcome: r.outcome || 'DONE', nextStep: r.nextStep, nextStepDue: r.nextStepDue || today(3),
  };
}

function touchBody(t: Touch) {
  return { ...t, nextStep: t.nextStep || null, nextStepDue: t.nextStep ? t.nextStepDue || null : null };
}

function TouchFields({ touch, setTouch, prefix }: { touch: Touch; setTouch: (t: Touch) => void; prefix: string }) {
  return (
    <div className="grid">
      <label>Channel
        <select id={`${prefix}-channel`} value={touch.channel} onChange={(e) => setTouch({ ...touch, channel: e.target.value })}>
          {Object.entries(CHANNEL_LABELS).map(([code, label]) => <option key={code} value={code}>{label}</option>)}
        </select>
      </label>
      <label>When
        <input id={`${prefix}-on`} type="date" value={touch.occurredOn} max={today()} onChange={(e) => setTouch({ ...touch, occurredOn: e.target.value })} />
      </label>
      <label>Outcome
        <select id={`${prefix}-outcome`} value={touch.outcome} onChange={(e) => setTouch({ ...touch, outcome: e.target.value })}>
          {Object.entries(OUTCOME_LABELS).map(([code, label]) => <option key={code} value={code}>{label}</option>)}
        </select>
      </label>
      <label className="wide">What happened
        <textarea id={`${prefix}-summary`} rows={3} value={touch.summary} maxLength={2000} onChange={(e) => setTouch({ ...touch, summary: e.target.value })} />
      </label>
      <label className="wide">Next step
        <input id={`${prefix}-next`} value={touch.nextStep} maxLength={300} onChange={(e) => setTouch({ ...touch, nextStep: e.target.value })} />
      </label>
      <label>Due
        <input id={`${prefix}-due`} type="date" value={touch.nextStepDue} disabled={!touch.nextStep} onChange={(e) => setTouch({ ...touch, nextStepDue: e.target.value })} />
      </label>
    </div>
  );
}

function Problems({ error }: { error: Error | null }) {
  if (!error) return null;
  const problems = error instanceof ApiError ? error.problems : [];
  return (
    <div className="notice bad" role="alert">
      {problems.length > 0 ? <ul className="problems">{problems.map((p) => <li key={p}>{p}</li>)}</ul> : error.message}
    </div>
  );
}

function Done({ to, label }: { to: string; label: string }) {
  return <div className="notice good" role="status">Saved. <Link to={to}>{label}</Link></div>;
}

function useRefresh() {
  const queryClient = useQueryClient();
  return () => {
    for (const key of ['conversations', 'conversation', 'connections', 'activities', 'heatmap']) {
      queryClient.invalidateQueries({ queryKey: [key] });
    }
  };
}

function UpdateConversation({ answer }: { answer: AssistantAnswer }) {
  const refresh = useRefresh();
  const open = [...answer.conversations].sort((a, b) => Number(a.status === 'CLOSED') - Number(b.status === 'CLOSED'));
  const [id, setId] = useState(open[0].id);
  const [touch, setTouch] = useState(() => touchFrom(answer));
  const chosen = open.find((c) => c.id === id)!;
  const save = useMutation({
    mutationFn: () => api.post<ConversationDetail>(`/conversations/${id}/updates`, { ...touchBody(touch), status: null, version: chosen.version }),
    onSuccess: refresh,
  });
  if (save.data) return <Done to={`/conversations/${id}`} label={`Open the conversation with ${save.data.conversation.personName}`} />;
  return (
    <form className="form proposal" aria-label="Update conversation" onSubmit={(e) => { e.preventDefault(); save.mutate(); }}>
      <label className="wide">Conversation
        <select id="as-conv" value={id} onChange={(e) => setId(Number(e.target.value))}>
          {open.map((c) => (
            <option key={c.id} value={c.id}>{c.personName} at {c.accountName}: {c.topic} ({c.status === 'CLOSED' ? 'closed' : `last touch ${formatDate(c.lastTouchOn)}`})</option>
          ))}
        </select>
      </label>
      <TouchFields touch={touch} setTouch={setTouch} prefix="as-upd" />
      <Problems error={save.error} />
      <div className="actions">
        <button type="submit" disabled={save.isPending || !touch.summary.trim()}>{save.isPending ? 'Saving…' : 'Add this update'}</button>
      </div>
    </form>
  );
}

function StartConversation({ answer }: { answer: AssistantAnswer }) {
  const refresh = useRefresh();
  const r = answer.reading;
  const [accountId, setAccountId] = useState<number | null>(answer.accounts[0]?.id ?? null);
  const [personName, setPersonName] = useState(r.fullName);
  const [topic, setTopic] = useState(r.topic);
  const [touch, setTouch] = useState(() => touchFrom(answer));
  // The contact found belongs to the first account; use it only while that account is chosen.
  const contactId = accountId === answer.accounts[0]?.id ? answer.contactId ?? null : null;
  const save = useMutation({
    mutationFn: () => api.post<ConversationDetail>('/conversations', {
      accountId, contactId, personName: contactId ? null : personName, topic, ...touchBody(touch), eventId: null,
    }),
    onSuccess: refresh,
  });
  if (save.data) return <Done to={`/conversations/${save.data.conversation.id}`} label="Open the new conversation" />;
  if (answer.accounts.length === 0) {
    return (
      <p className="muted small">
        {r.company ? `“${r.company}” is not one of our accounts yet.` : 'I could not tell which company this was.'} Add the
        account on the <Link to="/accounts">Accounts</Link> page, or save them as a connection for now.
      </p>
    );
  }
  return (
    <form className="form proposal" aria-label="Start conversation" onSubmit={(e) => { e.preventDefault(); save.mutate(); }}>
      <div className="grid">
        <label>Account
          <select id="as-account" value={accountId ?? ''} onChange={(e) => setAccountId(Number(e.target.value))}>
            {answer.accounts.map((a) => <option key={a.id} value={a.id}>{a.name} ({a.city})</option>)}
          </select>
        </label>
        <label>Person
          <input id="as-person" value={personName} maxLength={200} disabled={contactId !== null} onChange={(e) => setPersonName(e.target.value)} />
          {contactId !== null && <span className="muted small">Already a contact at this account</span>}
        </label>
        <label className="wide">Topic
          <input id="as-topic" value={topic} maxLength={200} onChange={(e) => setTopic(e.target.value)} />
        </label>
      </div>
      <TouchFields touch={touch} setTouch={setTouch} prefix="as-start" />
      <Problems error={save.error} />
      <div className="actions">
        <button type="submit" disabled={save.isPending || !accountId}>{save.isPending ? 'Saving…' : 'Start conversation'}</button>
      </div>
    </form>
  );
}

function ConnectionCard({ answer }: { answer: AssistantAnswer }) {
  const refresh = useRefresh();
  const r = answer.reading;
  const existing = answer.connections;
  const [id, setId] = useState<number | null>(existing[0]?.id ?? null);
  const chosen = existing.find((c) => c.id === id) ?? null;
  const account = answer.accounts[0] ?? null;
  const [person, setPerson] = useState({
    fullName: r.fullName, currentCompany: r.company, currentRole: r.title, city: r.city, linkedinUrl: r.linkedinUrl, email: '',
  });
  const [note, setNote] = useState(r.summary);
  const [strength, setStrength] = useState('ACQUAINTANCE');
  const [linkAccount, setLinkAccount] = useState(account !== null);

  const save = useMutation({
    mutationFn: () => {
      const dated = note.trim() ? `${r.occurredOn || today()}: ${note.trim()}` : '';
      if (chosen) {
        return api.put<Connection>(`/connections/${chosen.id}`, {
          fullName: chosen.fullName,
          currentCompany: person.currentCompany || chosen.currentCompany || null,
          currentRole: person.currentRole || chosen.currentRole || null,
          city: person.city || chosen.city || null,
          linkedinUrl: chosen.linkedinUrl || person.linkedinUrl || null,
          email: chosen.email ?? null,
          accountId: chosen.accountId ?? (linkAccount && account ? account.id : null),
          accountLink: chosen.accountLink ?? (linkAccount && account ? 'WORKS_THERE' : null),
          notes: [chosen.notes, dated].filter(Boolean).join('\n'),
          metAtEventId: chosen.metAtEventId ?? null,
          version: chosen.version,
        });
      }
      return api.post<Connection>('/connections', {
        person: {
          ...person, notes: dated, accountId: linkAccount && account ? account.id : null,
          accountLink: linkAccount && account ? 'WORKS_THERE' : null, metAtEventId: null,
        },
        knowing: { strength, howKnown: CHANNEL_LABELS[r.channel] ?? '', connectedOn: r.occurredOn || today() },
      });
    },
    onSuccess: refresh,
  });
  if (save.data) return <Done to={`/connections/${save.data.id}`} label={`Open ${save.data.fullName}`} />;
  const field = (key: keyof typeof person, label: string) => (
    <label>{label}
      <input id={`as-conn-${key}`} value={person[key]} onChange={(e) => setPerson({ ...person, [key]: e.target.value })} />
    </label>
  );
  return (
    <form className="form proposal" aria-label={chosen ? 'Update connection' : 'Add connection'} onSubmit={(e) => { e.preventDefault(); save.mutate(); }}>
      {existing.length > 0 && (
        <label className="wide">Connection
          <select id="as-conn" value={id ?? ''} onChange={(e) => setId(e.target.value ? Number(e.target.value) : null)}>
            {existing.map((c) => <option key={c.id} value={c.id}>{c.fullName}{c.currentCompany ? ` at ${c.currentCompany}` : ''}</option>)}
            <option value="">Someone new: add a connection</option>
          </select>
        </label>
      )}
      <div className="grid">
        {!chosen && field('fullName', 'Name')}
        {field('currentRole', 'Role')}
        {field('currentCompany', 'Company')}
        {field('city', 'City')}
        {!chosen && field('linkedinUrl', 'LinkedIn URL')}
        {!chosen && (
          <label>How well you know them
            <select id="as-conn-strength" value={strength} onChange={(e) => setStrength(e.target.value)}>
              <option value="CLOSE">Close</option>
              <option value="KNOW_WELL">Know well</option>
              <option value="ACQUAINTANCE">Acquaintance</option>
            </select>
          </label>
        )}
      </div>
      {account && !chosen?.accountId && (
        <label className="check">
          <input type="checkbox" checked={linkAccount} onChange={(e) => setLinkAccount(e.target.checked)} />
          Works at our account {account.name}
        </label>
      )}
      <label className="wide">{chosen ? 'Add to their notes' : 'Notes'}
        <textarea id="as-conn-notes" rows={2} value={note} onChange={(e) => setNote(e.target.value)} />
      </label>
      <Problems error={save.error} />
      <div className="actions">
        <button type="submit" disabled={save.isPending || (!chosen && !person.fullName.trim())}>
          {save.isPending ? 'Saving…' : chosen ? 'Update connection' : 'Add connection'}
        </button>
      </div>
    </form>
  );
}
