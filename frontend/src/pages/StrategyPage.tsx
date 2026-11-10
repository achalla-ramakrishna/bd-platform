import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError } from '../lib/api';
import { formatDate } from '../lib/format';
import { Markdown } from '../lib/markdown';

interface Version { id: number; body: string; note: string | null; editedBy: string; editedAt: string }
interface VersionSummary { id: number; note: string | null; editedBy: string; editedAt: string }
interface Strategy { current: Version | null; canEdit: boolean }

/** The BD strategy: what we are trying to do, in one place (backlog item 2). */
export function StrategyPage() {
  const queryClient = useQueryClient();
  const strategy = useQuery({ queryKey: ['strategy'], queryFn: () => api.get<Strategy>('/strategy') });
  const [editing, setEditing] = useState<{ body: string; note: string; base: number | null } | null>(null);
  const [showHistory, setShowHistory] = useState(false);
  const [viewing, setViewing] = useState<number | null>(null);
  const versions = useQuery({
    queryKey: ['strategy', 'versions'],
    queryFn: () => api.get<VersionSummary[]>('/strategy/versions'),
    enabled: showHistory,
  });
  const old = useQuery({
    queryKey: ['strategy', 'version', viewing],
    queryFn: () => api.get<Version>(`/strategy/versions/${viewing}`),
    enabled: viewing !== null,
  });
  const save = useMutation({
    mutationFn: () => api.put<Version>('/strategy', { body: editing!.body, note: editing!.note, baseVersionId: editing!.base }),
    onSuccess: () => {
      setEditing(null);
      queryClient.invalidateQueries({ queryKey: ['strategy'] });
    },
  });

  if (strategy.isPending) return <p className="muted">Loading…</p>;
  if (strategy.error) return <div className="notice bad">{strategy.error.message}</div>;
  const { current, canEdit } = strategy.data;
  const problems = save.error instanceof ApiError ? save.error.problems : [];
  const startEdit = (body: string) => { save.reset(); setEditing({ body, note: '', base: current?.id ?? null }); };

  return (
    <>
      <div className="title-row">
        <h1>Strategy</h1>
        <div className="actions">
          <button type="button" className="secondary" onClick={() => { setShowHistory(!showHistory); setViewing(null); }}>
            {showHistory ? 'Hide history' : 'History'}
          </button>
          {canEdit && !editing && <button type="button" onClick={() => startEdit(current?.body ?? '')}>Edit</button>}
        </div>
      </div>
      <p className="lead">
        What we are trying to do and how, so nobody has to look outside the app.
        {current && <> Last changed {formatDate(current.editedAt)} by {current.editedBy}.</>}
      </p>

      {editing ? (
        <form className="form strategy-edit" aria-label="Edit strategy" onSubmit={(e) => { e.preventDefault(); save.mutate(); }}>
          <div className="strategy-columns">
            <label>Text (Markdown: # heading, - bullet, 1. numbered, **bold**)
              <textarea id="strategy-body" rows={28} value={editing.body} maxLength={50000}
                onChange={(e) => setEditing({ ...editing, body: e.target.value })} />
            </label>
            <div>
              <div className="label-like">Preview</div>
              <div className="strategy-preview"><Markdown text={editing.body} /></div>
            </div>
          </div>
          <label className="wide">What changed (optional)
            <input id="strategy-note" maxLength={300} value={editing.note} placeholder="e.g. Added banking focus"
              onChange={(e) => setEditing({ ...editing, note: e.target.value })} />
          </label>
          {save.error && (
            <div className="notice bad" role="alert">
              {problems.length > 0 ? <ul className="problems">{problems.map((p) => <li key={p}>{p}</li>)}</ul> : save.error.message}
            </div>
          )}
          <div className="actions">
            <button type="submit" disabled={save.isPending}>{save.isPending ? 'Saving…' : 'Save new version'}</button>
            <button type="button" className="secondary" onClick={() => setEditing(null)}>Cancel</button>
          </div>
        </form>
      ) : current ? (
        <article className="strategy"><Markdown text={current.body} /></article>
      ) : (
        <p className="muted">No strategy written yet.{canEdit ? ' Click Edit to write it.' : ''}</p>
      )}

      {showHistory && (
        <section aria-label="Strategy history">
          <h2>History</h2>
          <div className="table-wrap">
            <table>
              <thead><tr><th>Saved</th><th>By</th><th>What changed</th><th></th></tr></thead>
              <tbody>
                {versions.data?.map((v, i) => (
                  <tr key={v.id}>
                    <td>{formatDate(v.editedAt)}{i === 0 && <span className="pill good" style={{ marginLeft: 8 }}>Current</span>}</td>
                    <td>{v.editedBy}</td>
                    <td>{v.note ?? '—'}</td>
                    <td><button type="button" className="secondary" onClick={() => setViewing(v.id)}>View</button></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {viewing !== null && old.data && (
            <div className="strategy-old">
              <div className="title-row">
                <h3>Version saved {formatDate(old.data.editedAt)} by {old.data.editedBy}</h3>
                <div className="actions">
                  {canEdit && <button type="button" className="secondary" onClick={() => { startEdit(old.data.body); setShowHistory(false); }}>Edit from this version</button>}
                  <button type="button" className="secondary" onClick={() => setViewing(null)}>Close</button>
                </div>
              </div>
              <article className="strategy"><Markdown text={old.data.body} /></article>
            </div>
          )}
        </section>
      )}
    </>
  );
}
