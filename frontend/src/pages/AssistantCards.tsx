import { useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import {
  EVENT_KIND_LABELS, EVENT_ROLE_LABELS, api,
  type AssistantAnswer, type BdEvent, type BdEventDetail, type EventKind, type EventRole,
} from '../lib/api';
import { formatDate } from '../lib/format';
import { Done, Problems, useRefresh } from './AssistantPage';
import { FLOW, FORMAT, STATUS, type Format, type Status } from './InterviewsPage';
import { today } from './ConversationsPage';

/*
 * Log with AI cards for conferences and leader interviews (ADR-0013). Like the conversation and connection
 * cards, they are prefilled from what Claude read, editable, and save only through the usual endpoints.
 */

const GOING: EventRole[] = ['SIGNED_UP', 'ATTENDING', 'PRESENTING', 'STALL', 'ORGANISING'];

/** Add a conference, or update the one we already have with that name; optionally mark me as going. */
export function EventCard({ answer }: { answer: AssistantAnswer }) {
  const refresh = useRefresh();
  const e = answer.reading.event!;
  const existing = answer.events ?? [];
  const [id, setId] = useState<number | null>(existing[0]?.id ?? null);
  const chosen = existing.find((x) => x.id === id) ?? null;
  const fromReading = () => ({
    name: e.name, kind: e.kind, startsOn: e.startsOn, endsOn: e.endsOn, city: e.city, online: e.online, url: e.url,
    organiser: e.organiser, ourRole: e.ourRole, notes: e.notes,
  });
  // For an event we have, keep what is stored and take what was read where it says something.
  const merged = (x: BdEvent) => ({
    name: x.name, kind: e.kind || x.kind, startsOn: e.startsOn || x.startsOn, endsOn: e.endsOn || x.endsOn || '',
    city: e.city || x.city || '', online: e.online || x.online, url: x.url || e.url, organiser: x.organiser || e.organiser,
    ourRole: e.ourRole === 'WATCHING' ? x.ourRole : e.ourRole, notes: [x.notes, e.notes].filter(Boolean).join('\n'),
  });
  const [form, setForm] = useState(() => (chosen ? merged(chosen) : fromReading()));
  const [going, setGoing] = useState(GOING.includes(e.ourRole));
  const choose = (value: string) => {
    const next = value ? existing.find((x) => x.id === Number(value)) ?? null : null;
    setId(next?.id ?? null);
    setForm(next ? merged(next) : fromReading());
  };

  const save = useMutation({
    mutationFn: async () => {
      const body = {
        ...form, endsOn: form.endsOn || null, city: form.city || null, url: form.url || null, organiser: form.organiser || null,
        notes: form.notes || null, outcome: chosen?.outcome ?? null, version: chosen?.version ?? null,
      };
      const d = chosen ? await api.put<BdEventDetail>(`/events/${chosen.id}`, body) : await api.post<BdEventDetail>('/events', body);
      if (going) await api.put<BdEventDetail>(`/events/${d.event.id}/attendees/me`, {});
      return d.event;
    },
    onSuccess: refresh,
  });
  if (save.data) return <Done to={`/events/${save.data.id}`} label={`Open ${save.data.name}`} />;

  const field = (key: 'name' | 'city' | 'url' | 'organiser', label: string) => (
    <label>{label}
      <input id={`as-ev-${key}`} value={form[key]} onChange={(x) => setForm({ ...form, [key]: x.target.value })} />
    </label>
  );
  return (
    <form className="form proposal" aria-label={chosen ? 'Update conference' : 'Add conference'} onSubmit={(x) => { x.preventDefault(); save.mutate(); }}>
      {existing.length > 0 && (
        <label className="wide">Conference
          <select id="as-ev" value={id ?? ''} onChange={(x) => choose(x.target.value)}>
            {existing.map((x) => <option key={x.id} value={x.id}>{x.name} ({formatDate(x.startsOn)})</option>)}
            <option value="">A different event: add it</option>
          </select>
        </label>
      )}
      <div className="grid">
        {field('name', 'Name')}
        <label>Kind
          <select id="as-ev-kind" value={form.kind} onChange={(x) => setForm({ ...form, kind: x.target.value as EventKind })}>
            {Object.entries(EVENT_KIND_LABELS).map(([code, label]) => <option key={code} value={code}>{label}</option>)}
          </select>
        </label>
        <label>Our role
          <select id="as-ev-role" value={form.ourRole} onChange={(x) => setForm({ ...form, ourRole: x.target.value as EventRole })}>
            {Object.entries(EVENT_ROLE_LABELS).map(([code, label]) => <option key={code} value={code}>{label}</option>)}
          </select>
        </label>
        <label>Starts
          <input id="as-ev-starts" type="date" value={form.startsOn} onChange={(x) => setForm({ ...form, startsOn: x.target.value })} />
        </label>
        <label>Ends (if more than a day)
          <input id="as-ev-ends" type="date" value={form.endsOn} min={form.startsOn} onChange={(x) => setForm({ ...form, endsOn: x.target.value })} />
        </label>
        {field('city', 'City')}
        {field('url', 'Event page')}
        {field('organiser', 'Organiser')}
        <label className="wide">Notes
          <textarea id="as-ev-notes" rows={2} value={form.notes} onChange={(x) => setForm({ ...form, notes: x.target.value })} />
        </label>
      </div>
      <div className="checks">
        <label className="check">
          <input type="checkbox" checked={form.online} onChange={(x) => setForm({ ...form, online: x.target.checked })} /> Online
        </label>
        <label className="check">
          <input type="checkbox" checked={going} onChange={(x) => setGoing(x.target.checked)} /> I'm going
        </label>
      </div>
      <Problems error={save.error} />
      <div className="actions">
        <button type="submit" disabled={save.isPending || !form.name.trim() || !form.startsOn}>
          {save.isPending ? 'Saving…' : chosen ? 'Update conference' : 'Add conference'}
        </button>
      </div>
    </form>
  );
}

/**
 * The moves from one interview stage to another, one step at a time as the interview rules require:
 * via IDEA when it was declined; DECLINED directly.
 */
export function stepsTo(from: Status, to: Status): Status[] {
  if (from === to) return [];
  if (to === 'DECLINED') return ['DECLINED'];
  const steps: Status[] = [];
  let at = from;
  if (at === 'DECLINED') {
    steps.push('IDEA');
    at = 'IDEA';
  }
  let i = FLOW.indexOf(at);
  const j = FLOW.indexOf(to);
  while (i !== j) {
    i += i < j ? 1 : -1;
    steps.push(FLOW[i]!);
  }
  return steps;
}

type Saved = { id: number; status: Status; version: number; leaderName: string };

/** Add a leader interview, or move the one we have with that leader to the stage the material shows. */
export function InterviewCard({ answer }: { answer: AssistantAnswer }) {
  const refresh = useRefresh();
  const v = answer.reading.interview!;
  const existing = answer.interviews ?? [];
  const [id, setId] = useState<number | null>(existing[0]?.id ?? null);
  const chosen = existing.find((x) => x.id === id) ?? null;
  const [form, setForm] = useState({
    leaderName: v.leaderName, leaderTitle: v.leaderTitle, company: v.company, topic: v.topic, format: v.format as Format, notes: v.notes,
  });
  const [status, setStatus] = useState<Status>(v.status as Status);
  const [on, setOn] = useState(v.on || (v.status === 'BOOKED' ? '' : today()));
  const [url, setUrl] = useState(v.publishedUrl);
  const [stepProblem, setStepProblem] = useState<string | null>(null);

  const save = useMutation({
    mutationFn: async (): Promise<Saved> => {
      setStepProblem(null);
      let current: Saved = chosen
        ? { id: chosen.id, status: chosen.status as Status, version: chosen.version, leaderName: chosen.leaderName }
        : await api.post<Saved>('/interviews', { ...form, accountId: null, ownerId: null, notes: form.notes || null });
      for (const step of stepsTo(current.status, status)) {
        const last = step === status;
        try {
          current = await api.post<Saved>(`/interviews/${current.id}/move`, {
            status: step, version: current.version, publishedUrl: step === 'PUBLISHED' ? url : null,
            on: last ? on || null : step === 'BOOKED' ? on || today() : null,
          });
        } catch (e) {
          // The interview exists; say how far it got.
          setStepProblem(`It is saved as ${STATUS[current.status]}; moving it to ${STATUS[step]} failed: ${e instanceof Error ? e.message : e}`);
          break;
        }
      }
      return current;
    },
    onSuccess: refresh,
  });
  if (save.data) {
    return (
      <>
        <Done to={`/interviews/${save.data.id}`} label={`Open the interview with ${save.data.leaderName}`} />
        {stepProblem && <div className="notice warn">{stepProblem}</div>}
      </>
    );
  }

  const needsDate = status === 'BOOKED';
  const dateLabel: Partial<Record<Status, string>> = { ASKED: 'Asked on', BOOKED: 'Booked for', RECORDED: 'Recorded on', PUBLISHED: 'Published on' };
  const field = (key: 'leaderName' | 'leaderTitle' | 'company' | 'topic', label: string) => (
    <label className={key === 'topic' ? 'wide' : undefined}>{label}
      <input id={`as-iv-${key}`} value={form[key]} onChange={(x) => setForm({ ...form, [key]: x.target.value })} />
    </label>
  );
  return (
    <form className="form proposal" aria-label={chosen ? 'Update interview' : 'Add interview'} onSubmit={(x) => { x.preventDefault(); save.mutate(); }}>
      {existing.length > 0 && (
        <label className="wide">Interview
          <select id="as-iv" value={id ?? ''} onChange={(x) => setId(x.target.value ? Number(x.target.value) : null)}>
            {existing.map((x) => (
              <option key={x.id} value={x.id}>{x.leaderName}: {x.topic} ({STATUS[x.status as Status] ?? x.status})</option>
            ))}
            <option value="">A new interview: add it</option>
          </select>
        </label>
      )}
      <div className="grid">
        {!chosen && field('leaderName', 'Leader')}
        {!chosen && field('leaderTitle', 'Title')}
        {!chosen && field('company', 'Company')}
        {!chosen && (
          <label>Format
            <select id="as-iv-format" value={form.format} onChange={(x) => setForm({ ...form, format: x.target.value as Format })}>
              {Object.entries(FORMAT).map(([code, label]) => <option key={code} value={code}>{label}</option>)}
            </select>
          </label>
        )}
        {!chosen && field('topic', 'Topic')}
        <label>{chosen ? `Move from ${STATUS[chosen.status as Status] ?? chosen.status} to` : 'Stage'}
          <select id="as-iv-status" value={status} onChange={(x) => setStatus(x.target.value as Status)}>
            {Object.entries(STATUS).map(([code, label]) => <option key={code} value={code}>{label}</option>)}
          </select>
        </label>
        {dateLabel[status] && (
          <label>{dateLabel[status]}
            <input id="as-iv-on" type="date" value={on} max={needsDate ? undefined : today()} onChange={(x) => setOn(x.target.value)} />
          </label>
        )}
        {status === 'PUBLISHED' && (
          <label>Published at
            <input id="as-iv-url" value={url} placeholder="https://" onChange={(x) => setUrl(x.target.value)} />
          </label>
        )}
        {!chosen && (
          <label className="wide">Notes
            <textarea id="as-iv-notes" rows={2} value={form.notes} onChange={(x) => setForm({ ...form, notes: x.target.value })} />
          </label>
        )}
      </div>
      <Problems error={save.error} />
      <div className="actions">
        <button type="submit" disabled={save.isPending || (!chosen && !form.leaderName.trim()) || (needsDate && !on) || (status === 'PUBLISHED' && !url.trim())}>
          {save.isPending ? 'Saving…' : chosen ? 'Update interview' : 'Add interview'}
        </button>
      </div>
    </form>
  );
}
