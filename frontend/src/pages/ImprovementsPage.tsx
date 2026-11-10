import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useParams } from 'react-router';
import { ApiError, api } from '../lib/api';
import { formatDate } from '../lib/format';
import { Markdown } from '../lib/markdown';

type Status = 'SUGGESTED' | 'APPROVED' | 'IN_PROGRESS' | 'DONE' | 'DECLINED';

interface Improvement {
  id: number; title: string; details?: string | null; status: Status; rank?: number | null;
  suggestedBy: string; suggestedById: number; createdAt: string; decidedBy?: string | null; decidedAt?: string | null;
  decisionNote?: string | null; deliveredRef?: string | null; doneAt?: string | null; version: number; canEdit: boolean;
}
interface Board { items: Improvement[]; canApprove: boolean }

const LABELS: Record<Status, string> = {
  SUGGESTED: 'Suggested', APPROVED: 'Approved', IN_PROGRESS: 'In progress', DONE: 'Done', DECLINED: 'Declined',
};
const COLUMNS: { status: Status; hint: string }[] = [
  { status: 'SUGGESTED', hint: 'Ideas waiting for a decision' },
  { status: 'APPROVED', hint: 'The build queue, top first' },
  { status: 'IN_PROGRESS', hint: 'Being built' },
  { status: 'DONE', hint: 'Delivered' },
];
/** Allowed moves, matching the server. */
const MOVES: Record<Status, Status[]> = {
  SUGGESTED: ['APPROVED', 'DECLINED'],
  APPROVED: ['IN_PROGRESS', 'DONE', 'SUGGESTED', 'DECLINED'],
  IN_PROGRESS: ['DONE', 'APPROVED', 'DECLINED'],
  DONE: ['IN_PROGRESS'],
  DECLINED: ['SUGGESTED'],
};
const MOVE_LABELS: Record<Status, string> = {
  APPROVED: 'Approve', IN_PROGRESS: 'Start building', DONE: 'Mark done', SUGGESTED: 'Back to suggested', DECLINED: 'Decline',
};

const problemsOf = (e: unknown) => (e instanceof ApiError && e.problems.length ? e.problems.join(' ') : (e as Error | null)?.message);

/** Improvement areas (backlog item 5): suggest, approve, build, deliver. */
export function ImprovementsPage() {
  const queryClient = useQueryClient();
  const board = useQuery({ queryKey: ['improvements'], queryFn: () => api.get<Board>('/improvements') });
  const [adding, setAdding] = useState(false);
  const [showDeclined, setShowDeclined] = useState(false);
  const move = useMutation({
    mutationFn: ({ id, direction, version }: { id: number; direction: 'UP' | 'DOWN'; version: number }) =>
      api.post<Board>(`/improvements/${id}/move`, { direction, version }),
    onSuccess: (b) => queryClient.setQueryData(['improvements'], b),
  });
  const items = board.data?.items ?? [];
  const declined = items.filter((i) => i.status === 'DECLINED');

  return (
    <>
      <div className="title-row">
        <h1>Improvement areas</h1>
        {!adding && <button type="button" onClick={() => setAdding(true)}>Suggest an improvement</button>}
      </div>
      <p className="lead">
        Ideas for making this platform better. Anyone can suggest; leadership and admins approve and order them.
        The Approved column, top first, is the next work to build.
      </p>
      {adding && <Suggest onDone={() => setAdding(false)} />}
      {move.error && <div className="notice bad" role="alert">{problemsOf(move.error)}</div>}

      <div className="board">
        {COLUMNS.map((col) => {
          const list = items.filter((i) => i.status === col.status);
          return (
            <section key={col.status} className="board-column" aria-label={LABELS[col.status]}>
              <h2>{LABELS[col.status]} <span className="muted">{list.length}</span></h2>
              <p className="muted small">{col.hint}</p>
              {list.length === 0 && <p className="muted small">Nothing here.</p>}
              {list.map((i, n) => (
                <article key={i.id} className="board-card">
                  <Link to={`/improvements/${i.id}`} className="board-title">
                    {col.status === 'APPROVED' && <span className="rank">{n + 1}</span>}{i.title}
                  </Link>
                  <div className="muted small">
                    {i.status === 'DONE' ? `Done ${formatDate(i.doneAt)}` : `${i.suggestedBy} · ${formatDate(i.createdAt)}`}
                  </div>
                  {board.data?.canApprove && col.status === 'APPROVED' && (
                    <div className="rank-buttons">
                      <button type="button" className="secondary small-button" aria-label={`Move ${i.title} up`} disabled={n === 0 || move.isPending}
                        onClick={() => move.mutate({ id: i.id, direction: 'UP', version: i.version })}>↑</button>
                      <button type="button" className="secondary small-button" aria-label={`Move ${i.title} down`} disabled={n === list.length - 1 || move.isPending}
                        onClick={() => move.mutate({ id: i.id, direction: 'DOWN', version: i.version })}>↓</button>
                    </div>
                  )}
                </article>
              ))}
            </section>
          );
        })}
      </div>
      {declined.length > 0 && (
        <p><button type="button" className="link" onClick={() => setShowDeclined(!showDeclined)}>
          {showDeclined ? 'Hide' : 'Show'} declined ({declined.length})
        </button></p>
      )}
      {showDeclined && (
        <ul className="declined">
          {declined.map((i) => <li key={i.id}><Link to={`/improvements/${i.id}`}>{i.title}</Link> <span className="muted small">· {i.decisionNote}</span></li>)}
        </ul>
      )}
    </>
  );
}

function Suggest({ onDone }: { onDone: () => void }) {
  const queryClient = useQueryClient();
  const [form, setForm] = useState({ title: '', details: '' });
  const save = useMutation({
    mutationFn: () => api.post<Improvement>('/improvements', form),
    onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['improvements'] }); onDone(); },
  });
  return (
    <form className="form conversation-form" aria-label="Suggest an improvement" onSubmit={(e) => { e.preventDefault(); save.mutate(); }}>
      <h2>Suggest an improvement</h2>
      <label className="wide">Title
        <input id="imp-title" value={form.title} maxLength={200} placeholder="e.g. Show open roles per account on the Accounts page"
          onChange={(e) => setForm({ ...form, title: e.target.value })} />
      </label>
      <label className="wide">What and why (Markdown welcome)
        <textarea id="imp-details" rows={6} maxLength={10000} value={form.details}
          placeholder={'What should change, who it helps, and how we will know it works.\n- bullet points are fine'}
          onChange={(e) => setForm({ ...form, details: e.target.value })} />
      </label>
      {save.error && <div className="notice bad" role="alert">{problemsOf(save.error)}</div>}
      <div className="actions">
        <button type="submit" disabled={save.isPending}>Suggest</button>
        <button type="button" className="secondary" onClick={onDone}>Cancel</button>
      </div>
    </form>
  );
}

/** One improvement: details, decision history and the moves the user may make. */
export function ImprovementPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const item = useQuery({ queryKey: ['improvement', id], queryFn: () => api.get<Improvement>(`/improvements/${id}`) });
  const board = useQuery({ queryKey: ['improvements'], queryFn: () => api.get<Board>('/improvements') });
  const [editing, setEditing] = useState(false);
  const [deciding, setDeciding] = useState<Status | null>(null);
  const done = (i: Improvement) => {
    queryClient.setQueryData(['improvement', id], i);
    queryClient.invalidateQueries({ queryKey: ['improvements'] });
  };
  if (item.isPending) return <p className="muted">Loading…</p>;
  if (item.error) return <div className="notice bad">{item.error.message}</div>;
  const i = item.data;
  const canApprove = board.data?.canApprove ?? false;

  return (
    <>
      <p><Link to="/improvements">← Improvement areas</Link></p>
      <h1>{i.title} <span className={`pill ${i.status === 'DONE' ? 'good' : i.status === 'DECLINED' ? '' : 'accent'}`}>{LABELS[i.status]}</span></h1>
      <p className="lead">
        Suggested by {i.suggestedBy} on {formatDate(i.createdAt)}
        {i.decidedBy && <> · last decision by {i.decidedBy} on {formatDate(i.decidedAt)}</>}
        {i.status === 'APPROVED' && i.rank != null && <> · in the build queue</>}
      </p>
      {i.decisionNote && <div className="notice small">{i.status === 'DECLINED' ? 'Declined: ' : 'Note: '}{i.decisionNote}</div>}
      {i.deliveredRef && (
        <p>Delivered by: {/^https?:\/\//.test(i.deliveredRef) ? <a href={i.deliveredRef} target="_blank" rel="noreferrer">{i.deliveredRef}</a> : i.deliveredRef}</p>
      )}

      {editing ? <EditImprovement i={i} onDone={(u) => { if (u) done(u); setEditing(false); }} /> : (
        <article className="strategy">{i.details ? <Markdown text={i.details} /> : <p className="muted">No details yet.</p>}</article>
      )}

      <div className="toolbar quick-actions">
        {i.canEdit && !editing && <button type="button" className="secondary" onClick={() => setEditing(true)}>Edit</button>}
        {canApprove && MOVES[i.status].map((to) => (
          <button key={to} type="button" className={to === 'APPROVED' || to === 'DONE' ? '' : 'secondary'} onClick={() => setDeciding(to)}>
            {MOVE_LABELS[to]}
          </button>
        ))}
      </div>
      {deciding && <Decide i={i} to={deciding} onDone={(u) => { if (u) done(u); setDeciding(null); if (u && u.status === 'DECLINED') navigate('/improvements'); }} />}
    </>
  );
}

function EditImprovement({ i, onDone }: { i: Improvement; onDone: (i?: Improvement) => void }) {
  const [form, setForm] = useState({ title: i.title, details: i.details ?? '' });
  const save = useMutation({ mutationFn: () => api.put<Improvement>(`/improvements/${i.id}`, { ...form, version: i.version }), onSuccess: (u) => onDone(u) });
  return (
    <form className="form conversation-form" aria-label="Edit improvement" onSubmit={(e) => { e.preventDefault(); save.mutate(); }}>
      <label className="wide">Title
        <input id="imp-edit-title" value={form.title} maxLength={200} onChange={(e) => setForm({ ...form, title: e.target.value })} />
      </label>
      <label className="wide">What and why
        <textarea id="imp-edit-details" rows={8} maxLength={10000} value={form.details} onChange={(e) => setForm({ ...form, details: e.target.value })} />
      </label>
      {save.error && <div className="notice bad" role="alert">{problemsOf(save.error)}</div>}
      <div className="actions">
        <button type="submit" disabled={save.isPending}>Save</button>
        <button type="button" className="secondary" onClick={() => onDone()}>Cancel</button>
      </div>
    </form>
  );
}

function Decide({ i, to, onDone }: { i: Improvement; to: Status; onDone: (i?: Improvement) => void }) {
  const [note, setNote] = useState('');
  const [ref, setRef] = useState(i.deliveredRef ?? '');
  const save = useMutation({
    mutationFn: () => api.post<Improvement>(`/improvements/${i.id}/decision`, { status: to, note, deliveredRef: ref, version: i.version }),
    onSuccess: (u) => onDone(u),
  });
  return (
    <form className="form conversation-form" aria-label={MOVE_LABELS[to]} onSubmit={(e) => { e.preventDefault(); save.mutate(); }}>
      <h2>{MOVE_LABELS[to]}</h2>
      <label className="wide">{to === 'DECLINED' ? 'Why (the person who suggested it will see this)' : 'Note (optional)'}
        <input id="imp-note" value={note} maxLength={1000} onChange={(e) => setNote(e.target.value)} />
      </label>
      {to === 'DONE' && (
        <label className="wide">What delivered it (link to the change or release, optional)
          <input id="imp-ref" value={ref} maxLength={500} placeholder="https://github.com/…/commit/…" onChange={(e) => setRef(e.target.value)} />
        </label>
      )}
      {save.error && <div className="notice bad" role="alert">{problemsOf(save.error)}</div>}
      <div className="actions">
        <button type="submit" disabled={save.isPending}>{MOVE_LABELS[to]}</button>
        <button type="button" className="secondary" onClick={() => onDone()}>Cancel</button>
      </div>
    </form>
  );
}
