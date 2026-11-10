import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useParams } from 'react-router';
import { ApiError, api, canEditPipeline, type Me } from '../lib/api';
import { formatDate } from '../lib/format';
import { AccountPicker } from './AccountPicker';
import { today } from './ConversationsPage';

type Status = 'IDEA' | 'ASKED' | 'AGREED' | 'BOOKED' | 'RECORDED' | 'PUBLISHED' | 'DECLINED';
type Format = 'BLOG' | 'PODCAST' | 'VIDEO' | 'LINKEDIN_ARTICLE';
interface Interview {
  id: number; leaderName: string; leaderTitle?: string | null; company?: string | null; accountId?: number | null;
  accountName?: string | null; topic: string; format: Format; status: Status; ownerId: number; ownerName: string;
  askedOn?: string | null; scheduledOn?: string | null; recordedOn?: string | null; publishedOn?: string | null;
  publishedUrl?: string | null; notes?: string | null; createdAt: string; version: number;
}
interface InterviewPage {
  items: Interview[];
  summary: { inProgress: number; bookedNext30Days: number; awaitingPublication: number; publishedThisQuarter: number };
}

const STATUS: Record<Status, string> = {
  IDEA: 'Idea', ASKED: 'Asked', AGREED: 'Agreed', BOOKED: 'Booked', RECORDED: 'Recorded', PUBLISHED: 'Published', DECLINED: 'Declined',
};
const FORMAT: Record<Format, string> = { BLOG: 'Blog', PODCAST: 'Podcast', VIDEO: 'Video', LINKEDIN_ARTICLE: 'LinkedIn article' };
const FLOW: Status[] = ['IDEA', 'ASKED', 'AGREED', 'BOOKED', 'RECORDED', 'PUBLISHED'];
const NEXT_LABEL: Partial<Record<Status, string>> = {
  ASKED: 'Mark asked', AGREED: 'They agreed', BOOKED: 'Book it', RECORDED: 'Mark recorded', PUBLISHED: 'Mark published',
};

function StatusPill({ s }: { s: Status }) {
  const cls = s === 'PUBLISHED' ? 'good' : s === 'BOOKED' || s === 'RECORDED' ? 'accent' : s === 'DECLINED' ? '' : 'warn';
  return <span className={`pill ${cls}`}>{STATUS[s]}</span>;
}

function stageDate(i: Interview) {
  if (i.status === 'PUBLISHED') return `Published ${formatDate(i.publishedOn)}`;
  if (i.status === 'RECORDED') return `Recorded ${formatDate(i.recordedOn)}`;
  if (i.status === 'BOOKED') return `Booked for ${formatDate(i.scheduledOn)}`;
  if (i.status === 'ASKED' || i.status === 'AGREED') return `Asked ${formatDate(i.askedOn)}`;
  return `Added ${formatDate(i.createdAt)}`;
}

const problemsOf = (e: unknown) => (e instanceof ApiError && e.problems.length ? e.problems : null);

/** Leader interviews (backlog item 8): give GCC leaders a stage before asking them for anything. */
export function InterviewsPage({ me }: { me: Me }) {
  const navigate = useNavigate();
  const [status, setStatus] = useState('OPEN');
  const [mine, setMine] = useState(false);
  const [adding, setAdding] = useState(false);
  const params = new URLSearchParams();
  if (status) params.set('status', status);
  if (mine) params.set('mine', 'true');
  const list = useQuery({ queryKey: ['interviews', params.toString()], queryFn: () => api.get<InterviewPage>(`/interviews?${params}`) });
  const s = list.data?.summary;
  return (
    <>
      <div className="title-row">
        <h1>Leader interviews</h1>
        {canEditPipeline(me) && !adding && <button type="button" onClick={() => setAdding(true)}>Add an interview</button>}
      </div>
      <p className="lead">
        GCC engineering leaders we interview for our blog, podcast or LinkedIn. We reach out to feature them, not to sell,
        and they remember who gave them a stage.
      </p>
      {adding && <InterviewForm onDone={(i) => { setAdding(false); if (i) navigate(`/interviews/${i.id}`); }} />}
      <div className="cards">
        <div className="card"><div className="label">In progress</div><div className="value">{s?.inProgress ?? '—'}</div></div>
        <div className="card"><div className="label">Booked, next 30 days</div><div className="value">{s?.bookedNext30Days ?? '—'}</div></div>
        <div className="card"><div className="label">Recorded, not published</div><div className="value">{s?.awaitingPublication ?? '—'}</div></div>
        <div className="card"><div className="label">Published this quarter</div><div className="value">{s?.publishedThisQuarter ?? '—'}</div></div>
      </div>
      <div className="toolbar">
        <select id="int-status" aria-label="Status" value={status} onChange={(e) => setStatus(e.target.value)}>
          <option value="OPEN">Not yet published</option>
          <option value="">All</option>
          {Object.entries(STATUS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
        </select>
        <label className="inline-check"><input type="checkbox" id="int-mine" checked={mine} onChange={(e) => setMine(e.target.checked)} /> Mine only</label>
      </div>
      <div className="table-wrap">
        <table>
          <thead><tr><th>Leader</th><th>Topic</th><th>Format</th><th>Status</th><th>When</th><th>Owner</th></tr></thead>
          <tbody>
            {list.isPending && <tr><td colSpan={6} className="muted">Loading…</td></tr>}
            {list.data?.items.length === 0 && <tr><td colSpan={6} className="muted">No interviews yet. Add a leader you would like to feature.</td></tr>}
            {list.data?.items.map((i) => (
              <tr key={i.id} className="clickable" onClick={() => navigate(`/interviews/${i.id}`)}>
                <td><Link to={`/interviews/${i.id}`} onClick={(e) => e.stopPropagation()}>{i.leaderName}</Link>
                  <span className="sub">{[i.leaderTitle, i.accountName ?? i.company].filter(Boolean).join(' · ')}</span></td>
                <td>{i.topic}</td>
                <td>{FORMAT[i.format]}</td>
                <td><StatusPill s={i.status} /></td>
                <td>{stageDate(i)}</td>
                <td>{i.ownerName}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}

function InterviewForm({ interview, onDone }: { interview?: Interview; onDone: (i?: Interview) => void }) {
  const queryClient = useQueryClient();
  const [f, setF] = useState({
    leaderName: interview?.leaderName ?? '', leaderTitle: interview?.leaderTitle ?? '', company: interview?.company ?? '',
    topic: interview?.topic ?? '', format: interview?.format ?? 'PODCAST', notes: interview?.notes ?? '',
  });
  const [account, setAccount] = useState<{ id: number | null; name: string | null }>({ id: interview?.accountId ?? null, name: interview?.accountName ?? null });
  const save = useMutation({
    mutationFn: () => {
      const body = { ...f, accountId: account.id, ownerId: interview?.ownerId ?? null, version: interview?.version };
      return interview ? api.put<Interview>(`/interviews/${interview.id}`, body) : api.post<Interview>('/interviews', body);
    },
    onSuccess: (i) => { queryClient.invalidateQueries({ queryKey: ['interviews'] }); queryClient.setQueryData(['interview', String(i.id)], i); onDone(i); },
  });
  const problems = problemsOf(save.error);
  const field = (key: 'leaderName' | 'leaderTitle' | 'company', label: string) => (
    <label>{label}<input id={`int-${key}`} value={f[key]} maxLength={200} onChange={(e) => setF({ ...f, [key]: e.target.value })} /></label>
  );
  return (
    <form className="form conversation-form" aria-label={interview ? 'Edit interview' : 'Add an interview'} onSubmit={(e) => { e.preventDefault(); save.mutate(); }}>
      <h2>{interview ? 'Edit interview' : 'Add an interview'}</h2>
      <div className="grid">
        {field('leaderName', 'Leader')}
        {field('leaderTitle', 'Title')}
        {field('company', 'Company')}
        <label>Format
          <select id="int-format" value={f.format} onChange={(e) => setF({ ...f, format: e.target.value as Format })}>
            {Object.entries(FORMAT).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
          </select>
        </label>
        <label className="wide">Topic
          <input id="int-topic" value={f.topic} maxLength={300} placeholder="e.g. How our GCC made every engineer AI-native"
            onChange={(e) => setF({ ...f, topic: e.target.value })} />
        </label>
        <label>Account (optional)
          <AccountPicker id="int-account" value={account.id} valueName={account.name} onChange={(id, name) => setAccount({ id, name })} />
        </label>
      </div>
      <label className="wide">Notes (questions, how we know them, where it will be published)
        <textarea id="int-notes" rows={3} value={f.notes} onChange={(e) => setF({ ...f, notes: e.target.value })} />
      </label>
      {save.error && (
        <div className="notice bad" role="alert">
          {problems ? <ul className="problems">{problems.map((p) => <li key={p}>{p}</li>)}</ul> : save.error.message}
        </div>
      )}
      <div className="actions">
        <button type="submit" disabled={save.isPending}>Save</button>
        <button type="button" className="secondary" onClick={() => onDone()}>Cancel</button>
      </div>
    </form>
  );
}

/** One interview with its stage steps. */
export function InterviewPage({ me }: { me: Me }) {
  const { id } = useParams();
  const queryClient = useQueryClient();
  const editable = canEditPipeline(me);
  const item = useQuery({ queryKey: ['interview', id], queryFn: () => api.get<Interview>(`/interviews/${id}`) });
  const [editing, setEditing] = useState(false);
  const [moving, setMoving] = useState<Status | null>(null);
  if (item.isPending) return <p className="muted">Loading…</p>;
  if (item.error) return <div className="notice bad">{item.error.message}</div>;
  const i = item.data;
  const at = FLOW.indexOf(i.status);
  const next = at >= 0 && at < FLOW.length - 1 ? FLOW[at + 1] : null;
  const back = at > 0 ? FLOW[at - 1] : null;
  return (
    <>
      <p><Link to="/interviews">← Leader interviews</Link></p>
      <h1>{i.leaderName} <StatusPill s={i.status} /></h1>
      <p className="lead">
        {[i.leaderTitle, i.company].filter(Boolean).join(' · ')}
        {i.accountId && <> · <Link to={`/accounts/${i.accountId}`}>{i.accountName}</Link></>}
        {' · '}{FORMAT[i.format]} · owned by {i.ownerName}
      </p>
      <h2 className="topic">{i.topic}</h2>
      <ol className="stage-steps" aria-label="Stages">
        {FLOW.map((st, n) => (
          <li key={st} className={i.status === 'DECLINED' ? '' : n < at ? 'done' : n === at ? 'current' : ''}>
            {STATUS[st]}
            <span className="sub">{st === 'ASKED' ? formatDate(i.askedOn) : st === 'BOOKED' ? formatDate(i.scheduledOn)
              : st === 'RECORDED' ? formatDate(i.recordedOn) : st === 'PUBLISHED' ? formatDate(i.publishedOn) : ''}</span>
          </li>
        ))}
      </ol>
      {i.publishedUrl && <p>Published at <a href={i.publishedUrl} target="_blank" rel="noreferrer">{i.publishedUrl}</a></p>}
      {i.notes && <p className="notes">{i.notes}</p>}

      {editable && (
        <div className="toolbar quick-actions">
          {next && <button type="button" onClick={() => setMoving(next)}>{NEXT_LABEL[next]}</button>}
          {i.status === 'DECLINED' && <button type="button" onClick={() => setMoving('IDEA')}>Back to idea</button>}
          {at >= 0 && at < FLOW.indexOf('RECORDED') && <button type="button" className="secondary" onClick={() => setMoving('DECLINED')}>They declined</button>}
          {back && <button type="button" className="secondary" onClick={() => setMoving(back)}>Back to {STATUS[back].toLowerCase()}</button>}
          <button type="button" className="secondary" onClick={() => setEditing(true)} disabled={editing}>Edit</button>
        </div>
      )}
      {moving && <MoveForm i={i} to={moving} onDone={(u) => { if (u) { queryClient.setQueryData(['interview', id], u); queryClient.invalidateQueries({ queryKey: ['interviews'] }); } setMoving(null); }} />}
      {editing && <InterviewForm interview={i} onDone={() => setEditing(false)} />}
    </>
  );
}

function MoveForm({ i, to, onDone }: { i: Interview; to: Status; onDone: (i?: Interview) => void }) {
  const needsDate = to === 'ASKED' || to === 'BOOKED' || to === 'RECORDED' || to === 'PUBLISHED';
  const [on, setOn] = useState(to === 'BOOKED' ? (i.scheduledOn ?? today(7)) : today());
  const [url, setUrl] = useState(i.publishedUrl ?? '');
  const save = useMutation({
    mutationFn: () => api.post<Interview>(`/interviews/${i.id}/move`, { status: to, on: needsDate ? on : null, publishedUrl: url, version: i.version }),
    onSuccess: (u) => onDone(u),
  });
  const problems = problemsOf(save.error);
  const label = to === 'DECLINED' ? 'They declined' : to === 'IDEA' ? 'Back to idea' : NEXT_LABEL[to] ?? `Back to ${STATUS[to].toLowerCase()}`;
  return (
    <form className="form conversation-form" aria-label={label} onSubmit={(e) => { e.preventDefault(); save.mutate(); }}>
      <h2>{label}</h2>
      <div className="grid">
        {needsDate && (
          <label>{to === 'BOOKED' ? 'Booked for' : 'On'}
            <input id="int-on" type="date" value={on} max={to === 'BOOKED' ? undefined : today()} onChange={(e) => setOn(e.target.value)} />
          </label>
        )}
        {to === 'PUBLISHED' && (
          <label className="wide">Where it is published
            <input id="int-url" value={url} maxLength={500} placeholder="https://…" onChange={(e) => setUrl(e.target.value)} />
          </label>
        )}
      </div>
      {save.error && <div className="notice bad" role="alert">{problems ? problems.join(' ') : save.error.message}</div>}
      <div className="actions">
        <button type="submit" disabled={save.isPending}>{label}</button>
        <button type="button" className="secondary" onClick={() => onDone()}>Cancel</button>
      </div>
    </form>
  );
}
