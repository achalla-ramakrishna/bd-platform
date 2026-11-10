import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useParams } from 'react-router';
import {
  EVENT_KIND_LABELS, EVENT_ROLE_LABELS, ApiError, api, canEditPipeline,
  type BdEvent, type BdEventDetail, type BdEventPage, type EventRole, type Me,
} from '../lib/api';
import { formatDate } from '../lib/format';
import { ConnectionTable, AddConnection } from './ConnectionsPage';
import { ConversationTable, NewConversation, today } from './ConversationsPage';
import { AccountPicker } from './AccountPicker';

export function RolePill({ role }: { role: EventRole }) {
  const cls = role === 'PRESENTING' || role === 'ORGANISING' || role === 'STALL' ? 'good' : role === 'WATCHING' ? '' : 'accent';
  return <span className={`pill ${cls}`}>{EVENT_ROLE_LABELS[role]}</span>;
}

function when(e: BdEvent) {
  return e.endsOn && e.endsOn !== e.startsOn ? `${formatDate(e.startsOn)} to ${formatDate(e.endsOn)}` : formatDate(e.startsOn);
}

const problemsOf = (e: unknown) => (e instanceof ApiError && e.problems.length ? e.problems : null);

/** Conferences and events (backlog item 6): the radar, our role, who goes, who we met. */
export function EventsPage({ me }: { me: Me }) {
  const [tab, setTab] = useState('upcoming');
  const [role, setRole] = useState('');
  const [kind, setKind] = useState('');
  const [mine, setMine] = useState(false);
  const [adding, setAdding] = useState(false);
  const navigate = useNavigate();
  const params = new URLSearchParams();
  if (tab) params.set('when', tab);
  if (role) params.set('role', role);
  if (kind) params.set('kind', kind);
  if (mine) params.set('mine', 'true');
  const list = useQuery({ queryKey: ['events', params.toString()], queryFn: () => api.get<BdEventPage>(`/events?${params}`) });
  const s = list.data?.summary;

  return (
    <>
      <div className="title-row">
        <h1>Conferences and events</h1>
        {canEditPipeline(me) && !adding && <button type="button" onClick={() => setAdding(true)}>Add event</button>}
      </div>
      <p className="lead">
        Webinars, roundtables, meetups and conferences where GCC leaders gather. Attend, present, organise, and record who
        we met so every event turns into connections and conversations.
      </p>
      {adding && <EventForm onDone={(e) => { setAdding(false); if (e) navigate(`/events/${e.event.id}`); }} />}

      <div className="cards">
        <div className="card"><div className="label">In the next 30 days</div><div className="value">{s?.next30Days ?? '—'}</div></div>
        <div className="card"><div className="label">Presenting, organising or stall</div><div className="value">{s?.presentingOrOrganising ?? '—'}</div></div>
        <div className="card"><div className="label">This quarter</div><div className="value">{s?.thisQuarter ?? '—'}</div></div>
        <div className="card"><div className="label">People met in 90 days</div><div className="value">{s?.peopleMetLast90Days ?? '—'}</div></div>
      </div>

      <div className="toolbar">
        <div className="segmented" role="tablist" aria-label="When">
          {[['upcoming', 'Upcoming'], ['past', 'Past'], ['', 'All']].map(([v, l]) => (
            <button key={v} type="button" role="tab" aria-selected={tab === v} className={tab === v ? 'on' : ''} onClick={() => setTab(v)}>{l}</button>
          ))}
        </div>
        <select id="event-role" aria-label="Our role" value={role} onChange={(e) => setRole(e.target.value)}>
          <option value="">Any role</option>
          {Object.entries(EVENT_ROLE_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
        </select>
        <select id="event-kind" aria-label="Kind" value={kind} onChange={(e) => setKind(e.target.value)}>
          <option value="">Any kind</option>
          {Object.entries(EVENT_KIND_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
        </select>
        <label className="inline-check"><input type="checkbox" id="event-mine" checked={mine} onChange={(e) => setMine(e.target.checked)} /> I'm going</label>
      </div>

      <div className="table-wrap">
        <table>
          <thead><tr><th>When</th><th>Event</th><th>Where</th><th>Our role</th><th>Going</th><th className="num">People met</th></tr></thead>
          <tbody>
            {list.isPending && <tr><td colSpan={6} className="muted">Loading…</td></tr>}
            {list.data?.items.length === 0 && <tr><td colSpan={6} className="muted">{tab === 'upcoming' ? 'Nothing on the radar yet. Add the next meetup or conference.' : 'No events.'}</td></tr>}
            {list.data?.items.map((e) => (
              <tr key={e.id} className="clickable" onClick={() => navigate(`/events/${e.id}`)}>
                <td>{when(e)}</td>
                <td><Link to={`/events/${e.id}`} onClick={(x) => x.stopPropagation()}>{e.name}</Link>
                  <span className="sub">{EVENT_KIND_LABELS[e.kind]}{e.organiser ? ` · ${e.organiser}` : ''}</span></td>
                <td>{[e.online ? 'Online' : null, e.city].filter(Boolean).join(' · ') || '—'}</td>
                <td><RolePill role={e.ourRole} /></td>
                <td>{e.attendees.map((a) => a.name).join(', ') || <span className="muted">—</span>}</td>
                <td className="num">{e.conversations + e.connections || '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}

function EventForm({ event, onDone }: { event?: BdEvent; onDone: (d?: BdEventDetail) => void }) {
  const queryClient = useQueryClient();
  const [f, setF] = useState({
    name: event?.name ?? '', kind: event?.kind ?? 'MEETUP', startsOn: event?.startsOn ?? today(7), endsOn: event?.endsOn ?? '',
    city: event?.city ?? 'Bengaluru', online: event?.online ?? false, url: event?.url ?? '', organiser: event?.organiser ?? '',
    ourRole: event?.ourRole ?? 'SIGNED_UP', notes: event?.notes ?? '', outcome: event?.outcome ?? '',
  });
  const save = useMutation({
    mutationFn: () => {
      const body = { ...f, endsOn: f.endsOn || null, version: event?.version };
      return event ? api.put<BdEventDetail>(`/events/${event.id}`, body) : api.post<BdEventDetail>('/events', body);
    },
    onSuccess: (d) => {
      queryClient.invalidateQueries({ queryKey: ['events'] });
      queryClient.setQueryData(['event', String(d.event.id)], d);
      onDone(d);
    },
  });
  const problems = problemsOf(save.error);
  const past = !!event && (event.endsOn ?? event.startsOn) < today();
  return (
    <form className="form conversation-form" aria-label={event ? 'Edit event' : 'Add event'} onSubmit={(e) => { e.preventDefault(); save.mutate(); }}>
      <h2>{event ? 'Edit event' : 'Add an event'}</h2>
      <div className="grid">
        <label className="wide">Name
          <input id="ev-name" value={f.name} maxLength={200} placeholder="e.g. GCC engineering leaders meetup" onChange={(e) => setF({ ...f, name: e.target.value })} />
        </label>
        <label>Kind
          <select id="ev-kind" value={f.kind} onChange={(e) => setF({ ...f, kind: e.target.value as typeof f.kind })}>
            {Object.entries(EVENT_KIND_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
          </select>
        </label>
        <label>Our role
          <select id="ev-role" value={f.ourRole} onChange={(e) => setF({ ...f, ourRole: e.target.value as typeof f.ourRole })}>
            {Object.entries(EVENT_ROLE_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
          </select>
        </label>
        <label>Starts
          <input id="ev-starts" type="date" value={f.startsOn} onChange={(e) => setF({ ...f, startsOn: e.target.value })} />
        </label>
        <label>Ends (if more than a day)
          <input id="ev-ends" type="date" value={f.endsOn} onChange={(e) => setF({ ...f, endsOn: e.target.value })} />
        </label>
        <label>City
          <input id="ev-city" value={f.city} maxLength={80} onChange={(e) => setF({ ...f, city: e.target.value })} />
        </label>
        <label className="inline-check" style={{ alignSelf: 'end' }}>
          <input type="checkbox" id="ev-online" checked={f.online} onChange={(e) => setF({ ...f, online: e.target.checked })} /> Online
        </label>
        <label>Organiser
          <input id="ev-organiser" value={f.organiser} maxLength={200} onChange={(e) => setF({ ...f, organiser: e.target.value })} />
        </label>
        <label className="wide">Link
          <input id="ev-url" value={f.url} maxLength={500} placeholder="https://…" onChange={(e) => setF({ ...f, url: e.target.value })} />
        </label>
      </div>
      <label className="wide">Notes (why go, who will be there, what we will say)
        <textarea id="ev-notes" rows={2} value={f.notes} onChange={(e) => setF({ ...f, notes: e.target.value })} />
      </label>
      {(past || event) && (
        <label className="wide">How it went
          <textarea id="ev-outcome" rows={2} value={f.outcome} placeholder="Who we met, what landed, what to do next"
            onChange={(e) => setF({ ...f, outcome: e.target.value })} />
        </label>
      )}
      {save.error && (
        <div className="notice bad" role="alert">
          {problems ? <ul className="problems">{problems.map((p) => <li key={p}>{p}</li>)}</ul> : save.error.message}
        </div>
      )}
      <div className="actions">
        <button type="submit" disabled={save.isPending}>{save.isPending ? 'Saving…' : 'Save event'}</button>
        <button type="button" className="secondary" onClick={() => onDone()}>Cancel</button>
      </div>
    </form>
  );
}

/** One event: details, who goes, and the people we met there. */
export function EventPage({ me }: { me: Me }) {
  const { id } = useParams();
  const queryClient = useQueryClient();
  const editable = canEditPipeline(me);
  const detail = useQuery({ queryKey: ['event', id], queryFn: () => api.get<BdEventDetail>(`/events/${id}`) });
  const [editing, setEditing] = useState(false);
  const [meeting, setMeeting] = useState<'conversation' | 'connection' | null>(null);
  const [account, setAccount] = useState<number | null>(null);
  const going = useMutation({
    mutationFn: (on: boolean) => on ? api.put<BdEventDetail>(`/events/${id}/attendees/me`, {}) : api.del<BdEventDetail>(`/events/${id}/attendees/me`),
    onSuccess: (d) => { queryClient.setQueryData(['event', id], d); queryClient.invalidateQueries({ queryKey: ['events'] }); },
  });
  if (detail.isPending) return <p className="muted">Loading…</p>;
  if (detail.error) return <div className="notice bad">{detail.error.message}</div>;
  const { event: e, conversations, connections } = detail.data;
  const amGoing = e.attendees.some((a) => a.userId === me.id);
  const isPast = (e.endsOn ?? e.startsOn) < today();

  return (
    <>
      <p><Link to="/events">← Conferences and events</Link></p>
      <h1>{e.name} <RolePill role={e.ourRole} /></h1>
      <p className="lead">
        {EVENT_KIND_LABELS[e.kind]} · {when(e)} · {[e.online ? 'Online' : null, e.city].filter(Boolean).join(' · ') || 'Place not set'}
        {e.organiser && <> · by {e.organiser}</>}
        {e.url && <> · <a href={e.url} target="_blank" rel="noreferrer">event page</a></>}
      </p>
      {e.notes && <p className="notes">{e.notes}</p>}
      {e.outcome && <><h2>How it went</h2><p className="notes">{e.outcome}</p></>}

      <div className="toolbar quick-actions">
        <span>{isPast ? 'Went' : 'Going'}: {e.attendees.map((a) => a.name).join(', ') || <span className="muted">nobody yet</span>}</span>
        <button type="button" className={amGoing ? 'secondary' : ''} disabled={going.isPending} onClick={() => going.mutate(!amGoing)}>
          {isPast ? (amGoing ? "I didn't go" : 'I went') : (amGoing ? "I'm not going" : "I'm going")}
        </button>
        {editable && <button type="button" className="secondary" onClick={() => setEditing(true)} disabled={editing}>Edit event</button>}
      </div>
      {editing && <EventForm event={e} onDone={() => setEditing(false)} />}

      <div className="title-row">
        <h2>People we met</h2>
        {editable && !meeting && (
          <div className="actions">
            <button type="button" className="secondary" onClick={() => setMeeting('conversation')}>Log a conversation</button>
            <button type="button" className="secondary" onClick={() => setMeeting('connection')}>Add a connection</button>
          </div>
        )}
      </div>
      {meeting === 'conversation' && (
        account === null ? (
          <div className="form conversation-form">
            <label>Which account were they from?
              <AccountPicker id="ev-account" value={account} onChange={(a) => setAccount(a)} />
            </label>
            <div className="actions"><button type="button" className="secondary" onClick={() => setMeeting(null)}>Cancel</button></div>
          </div>
        ) : (
          <NewConversation accountId={account} eventId={e.id} topic={`Met at ${e.name}`} onDone={() => { setMeeting(null); setAccount(null); }} />
        )
      )}
      {meeting === 'connection' && <AddConnection eventId={e.id} onDone={() => { setMeeting(null); detail.refetch(); }} />}

      <h3>Conversations started here</h3>
      <ConversationTable items={conversations} empty="No conversations from this event yet." />
      <h3>Connections first met here</h3>
      <ConnectionTable items={connections} empty="No connections from this event yet." />
    </>
  );
}
