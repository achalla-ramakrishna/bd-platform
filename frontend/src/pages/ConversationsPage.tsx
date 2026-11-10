import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate } from 'react-router';
import {
  CHANNEL_LABELS, CONVERSATION_STATUS_LABELS, OUTCOME_LABELS, ApiError, api, canEditPipeline,
  type AccountDetail, type ConversationDetail, type ConversationPage, type ConversationStatus,
  type Conversation, type Me,
} from '../lib/api';
import { formatDate } from '../lib/format';
import { AccountPicker } from './AccountPicker';

export function today(offsetDays = 0) {
  const d = new Date();
  d.setDate(d.getDate() + offsetDays);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

export function StatusPill({ status }: { status: ConversationStatus }) {
  const cls = status === 'MEETING_BOOKED' || status === 'OPPORTUNITY' ? 'good'
    : status === 'REPLIED' ? 'accent' : status === 'GONE_QUIET' ? 'warn' : '';
  return <span className={`pill ${cls}`}>{CONVERSATION_STATUS_LABELS[status]}</span>;
}

/** The next step with its date, red when overdue (open conversations only). */
export function NextStep({ c }: { c: Conversation }) {
  if (!c.nextStep) return <span className="muted">No next step</span>;
  const open = c.status !== 'CLOSED' && c.status !== 'GONE_QUIET';
  const overdue = open && !!c.nextStepDue && c.nextStepDue < today();
  const dueToday = open && c.nextStepDue === today();
  return (
    <>
      {c.nextStep}
      <span className={`sub${overdue ? ' overdue' : ''}`}>
        {overdue ? 'Overdue: ' : dueToday ? 'Today: ' : 'Due '}{formatDate(c.nextStepDue)}
      </span>
    </>
  );
}

/** Conversations (backlog item 3): who we spoke to, what happened, and the next step. */
export function ConversationsPage({ me }: { me: Me }) {
  const [due, setDue] = useState('week');
  const [status, setStatus] = useState('OPEN');
  const [mine, setMine] = useState(false);
  const [q, setQ] = useState('');
  const [adding, setAdding] = useState(false);
  const params = new URLSearchParams();
  if (due) params.set('due', due);
  if (status) params.set('status', status);
  if (mine) params.set('mine', 'true');
  if (q) params.set('q', q);
  const list = useQuery({
    queryKey: ['conversations', params.toString()],
    queryFn: () => api.get<ConversationPage>(`/conversations?${params}`),
  });
  const s = list.data?.summary;
  const card = (label: string, value: number | undefined, onClick?: () => void, tone = '') => (
    <button type="button" className={`card card-button ${tone}`} onClick={onClick}>
      <div className="label">{label}</div><div className="value">{value ?? '—'}</div>
    </button>
  );

  return (
    <>
      <div className="title-row">
        <h1>Conversations</h1>
        {canEditPipeline(me) && !adding && <button type="button" onClick={() => setAdding(true)}>New conversation</button>}
      </div>
      <p className="lead">
        Who we have spoken to, what happened and what we do next. Conversations are the early sign of future business,
        so keep every one moving with a next step and a date.
      </p>
      {adding && <NewConversation onDone={() => setAdding(false)} />}

      <div className="cards">
        {card('Overdue next steps', s?.overdue, () => { setDue('overdue'); setStatus('OPEN'); }, s?.overdue ? 'bad' : '')}
        {card('Due this week', s?.dueThisWeek, () => { setDue('week'); setStatus('OPEN'); })}
        {card('Open conversations', s?.open, () => { setDue(''); setStatus('OPEN'); })}
        {card('Meetings booked', s?.meetingsBooked, () => { setDue(''); setStatus('MEETING_BOOKED'); })}
        {card('Became opportunities', s?.opportunities, () => { setDue(''); setStatus('OPPORTUNITY'); })}
        {card('Started in 30 days', s?.startedLast30Days, () => { setDue(''); setStatus(''); })}
      </div>

      <div className="toolbar">
        <input type="search" id="conv-search" placeholder="Search account, person or topic" aria-label="Search conversations"
          value={q} onChange={(e) => setQ(e.target.value)} />
        <select id="conv-due" aria-label="Next step due" value={due} onChange={(e) => setDue(e.target.value)}>
          <option value="overdue">Overdue</option>
          <option value="week">Due within 7 days</option>
          <option value="">Any next step</option>
        </select>
        <select id="conv-status" aria-label="Status" value={status} onChange={(e) => setStatus(e.target.value)}>
          <option value="OPEN">Open</option>
          <option value="">All statuses</option>
          {Object.entries(CONVERSATION_STATUS_LABELS).map(([code, label]) => <option key={code} value={code}>{label}</option>)}
        </select>
        <label className="inline-check">
          <input type="checkbox" id="conv-mine" checked={mine} onChange={(e) => setMine(e.target.checked)} /> Mine only
        </label>
        {list.data && <span className="muted">{list.data.items.length} shown</span>}
      </div>
      <ConversationTable items={list.data?.items ?? []} loading={list.isPending}
        empty={due ? 'Nothing due. Clear the "due" filter to see all conversations.' : 'No conversations yet.'} />
    </>
  );
}

export function ConversationTable({ items, loading, empty, hideAccount }: {
  items: Conversation[]; loading?: boolean; empty: string; hideAccount?: boolean;
}) {
  const navigate = useNavigate();
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr><th>Next step</th><th>Person</th>{!hideAccount && <th>Account</th>}<th>Topic</th><th>Status</th><th>Last touch</th><th>Owner</th></tr>
        </thead>
        <tbody>
          {loading && <tr><td colSpan={7} className="muted">Loading…</td></tr>}
          {!loading && items.length === 0 && <tr><td colSpan={7} className="muted">{empty}</td></tr>}
          {items.map((c) => (
            <tr key={c.id} className="clickable" onClick={() => navigate(`/conversations/${c.id}`)}>
              <td><NextStep c={c} /></td>
              <td><Link to={`/conversations/${c.id}`} onClick={(e) => e.stopPropagation()}>{c.personName}</Link>
                {c.personTitle && <span className="sub">{c.personTitle}</span>}</td>
              {!hideAccount && <td><Link to={`/accounts/${c.accountId}`} onClick={(e) => e.stopPropagation()}>{c.accountName}</Link></td>}
              <td>{c.topic}</td>
              <td><StatusPill status={c.status} /></td>
              <td>{formatDate(c.lastTouchOn)}{c.lastSummary && <span className="sub clamp">{c.lastSummary}</span>}</td>
              <td>{c.ownerName}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/**
 * Start a conversation with its first touch. With `accountId`, the account is fixed (account page);
 * `contactId`, `personName` and `topic` prefill the form (for example from a connection).
 */
export function NewConversation({ accountId, contactId, personName, topic, eventId, onDone }: {
  accountId?: number; contactId?: number | null; personName?: string; topic?: string; eventId?: number; onDone: () => void;
}) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const [chosenAccount, setChosenAccount] = useState<number | null>(accountId ?? null);
  const detail = useQuery({
    queryKey: ['account', String(chosenAccount)],
    queryFn: () => api.get<AccountDetail>(`/accounts/${chosenAccount}`),
    enabled: chosenAccount !== null,
  });
  const [form, setForm] = useState({
    contactId: contactId ? String(contactId) : personName ? 'other' : '', personName: personName ?? '', topic: topic ?? '', channel: eventId ? 'EVENT' : 'LINKEDIN_MESSAGE', occurredOn: today(), summary: '',
    outcome: 'NO_REPLY', nextStep: '', nextStepDue: today(3),
  });
  const save = useMutation({
    mutationFn: () => api.post<ConversationDetail>('/conversations', {
      accountId: chosenAccount, contactId: form.contactId && form.contactId !== 'other' ? Number(form.contactId) : null,
      personName: form.contactId === 'other' || !form.contactId ? form.personName : null, topic: form.topic,
      channel: form.channel, occurredOn: form.occurredOn, summary: form.summary, outcome: form.outcome,
      nextStep: form.nextStep || null, nextStepDue: form.nextStep ? form.nextStepDue || null : null, eventId: eventId ?? null,
    }),
    onSuccess: (d) => {
      queryClient.invalidateQueries({ queryKey: ['conversations'] });
      queryClient.invalidateQueries({ queryKey: ['activities'] });
      onDone();
      navigate(`/conversations/${d.conversation.id}`);
    },
  });
  const problems = save.error instanceof ApiError ? save.error.problems : [];
  const contacts = detail.data?.contacts ?? [];
  const typingName = form.contactId === 'other' || (contacts.length === 0 && chosenAccount !== null);

  return (
    <form className="form conversation-form" aria-label="New conversation" onSubmit={(e) => { e.preventDefault(); save.mutate(); }}>
      <h2>New conversation</h2>
      <div className="grid">
        {!accountId && (
          <label>Account
            <AccountPicker id="conv-account" value={chosenAccount} valueName={detail.data?.account.name}
              onChange={(id) => { setChosenAccount(id); setForm({ ...form, contactId: '' }); }} />
          </label>
        )}
        <label>Person
          <select id="conv-contact" value={form.contactId} disabled={chosenAccount === null}
            onChange={(e) => setForm({ ...form, contactId: e.target.value })}>
            <option value="">{contacts.length ? 'Choose a contact' : 'No contacts yet: type a name'}</option>
            {contacts.map((c) => <option key={c.id} value={c.id}>{c.fullName}{c.title ? ` (${c.title})` : ''}</option>)}
            {(contacts.length > 0 || form.contactId === 'other') && <option value="other">Someone not in contacts</option>}
          </select>
        </label>
        {typingName && (
          <label>Their name
            <input id="conv-person" value={form.personName} maxLength={200} onChange={(e) => setForm({ ...form, personName: e.target.value })} />
          </label>
        )}
        <label className="wide">Topic
          <input id="conv-topic" value={form.topic} maxLength={200} placeholder="e.g. React team for the payments rebuild"
            onChange={(e) => setForm({ ...form, topic: e.target.value })} />
        </label>
        <label>Channel
          <select id="conv-channel" value={form.channel} onChange={(e) => setForm({ ...form, channel: e.target.value })}>
            {Object.entries(CHANNEL_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
          </select>
        </label>
        <label>Date
          <input id="conv-date" type="date" value={form.occurredOn} max={today()} onChange={(e) => setForm({ ...form, occurredOn: e.target.value })} />
        </label>
        <label>Outcome
          <select id="conv-outcome" value={form.outcome} onChange={(e) => setForm({ ...form, outcome: e.target.value })}>
            {Object.entries(OUTCOME_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
          </select>
        </label>
      </div>
      <label className="wide">What was said
        <textarea id="conv-summary" rows={2} maxLength={2000} value={form.summary} onChange={(e) => setForm({ ...form, summary: e.target.value })} />
      </label>
      <div className="grid">
        <label className="wide">Next step
          <input id="conv-next" value={form.nextStep} maxLength={500} placeholder="e.g. Book a show and tell"
            onChange={(e) => setForm({ ...form, nextStep: e.target.value })} />
        </label>
        <label>By
          <input id="conv-next-due" type="date" value={form.nextStepDue} onChange={(e) => setForm({ ...form, nextStepDue: e.target.value })} />
        </label>
      </div>
      {save.error && (
        <div className="notice bad" role="alert">
          {problems.length > 0 ? <ul className="problems">{problems.map((p) => <li key={p}>{p}</li>)}</ul> : save.error.message}
        </div>
      )}
      <div className="actions">
        <button type="submit" disabled={save.isPending || chosenAccount === null}>{save.isPending ? 'Saving…' : 'Start conversation'}</button>
        <button type="button" className="secondary" onClick={onDone}>Cancel</button>
      </div>
    </form>
  );
}
