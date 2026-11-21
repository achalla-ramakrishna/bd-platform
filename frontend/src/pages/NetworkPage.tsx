import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useSearchParams } from 'react-router';
import {
  GTN_STAGES, GTN_STAGE_LABELS, WHY_LABELS, api, canEditPipeline,
  type ConnectionPage, type GtnBoard, type GtnStage, type GtnTemplate, type GtnToday, type GtnTrack, type GtnTrackPage, type Me,
  type WhyReason,
} from '../lib/api';
import { formatDate } from '../lib/format';
import { ErrorNote, ImportConnections, StagePill, StartGtn, TodayItem } from './gtn';

type Tab = 'today' | 'board' | 'list' | 'templates';

/**
 * GTN, Go To Network (ADR-0017): work the people who already know us from a first message to a warm commitment,
 * diagnose silence, nudge, and keep warm people warm with a monthly beep.
 */
export function NetworkPage({ me }: { me: Me }) {
  const [params, setParams] = useSearchParams();
  const tab = (params.get('tab') as Tab) || 'today';
  const setTab = (t: Tab) => setParams(t === 'today' ? {} : { tab: t });
  const editable = canEditPipeline(me);
  const [starting, setStarting] = useState(false);
  const [importing, setImporting] = useState(false);
  return (
    <>
      <div className="title-row">
        <h1>GTN: Go To Network</h1>
        {editable && (
          <div className="actions">
            <button type="button" onClick={() => { setStarting(!starting); setImporting(false); }}>Start someone</button>
            <button type="button" className="secondary" onClick={() => { setImporting(!importing); setStarting(false); }}>Import CSV</button>
          </div>
        )}
      </div>
      <p className="lead">
        Work the people who already know us: a first message, a reply, the intro, a call, a warm commitment, then a light monthly beep.
        Silence gets a reason and the right nudge. Unshackle and ask.
      </p>
      {starting && <PickPerson onClose={() => setStarting(false)} />}
      {importing && <ImportConnections onDone={() => setImporting(false)} />}
      <div className="tabs" role="tablist">
        {([['today', 'Today'], ['board', 'Board'], ['list', 'List'], ['templates', 'Templates']] as [Tab, string][]).map(([k, label]) => (
          <button key={k} type="button" role="tab" aria-selected={tab === k} className={`tab${tab === k ? ' active' : ''}`} onClick={() => setTab(k)}>
            {label}
          </button>
        ))}
      </div>
      {tab === 'today' && <TodayTab />}
      {tab === 'board' && <BoardTab editable={editable} />}
      {tab === 'list' && <ListTab />}
      {tab === 'templates' && <TemplatesTab editable={editable} />}
    </>
  );
}

function TodayTab() {
  const [everyone, setEveryone] = useState(false);
  const today = useQuery({
    queryKey: ['gtn', 'today', everyone],
    queryFn: () => api.get<GtnToday>(`/network/today${everyone ? '?everyone=true' : ''}`),
  });
  const c = today.data?.counts;
  return (
    <>
      <div className="cards">
        <div className="card"><div className="label">Due today</div><div className="value">{c?.due ?? '—'}</div></div>
        <div className={`card${c?.overdue ? ' bad' : ''}`}><div className="label">Overdue</div><div className="value">{c?.overdue ?? '—'}</div></div>
        <div className="card"><div className="label">On GTN</div><div className="value">{c?.total ?? '—'}</div></div>
        <div className="card"><div className="label">Warm</div><div className="value">{c ? c.byStage.WARM_COMMITMENT + c.byStage.MONTHLY_BEEP : '—'}</div></div>
        <div className="card"><div className="label">Unresponsive</div><div className="value">{c?.byStage.UNRESPONSIVE ?? '—'}</div></div>
      </div>
      <div className="toolbar">
        <label className="inline-check"><input type="checkbox" checked={everyone} onChange={(e) => setEveryone(e.target.checked)} /> Everyone's, not only mine</label>
        {today.data && <span className="muted small">No reply in {today.data.rules.unresponsiveDays} days → Unresponsive · nudges every
          {' '}{today.data.rules.nudgeSpacingDays} days, at most {today.data.rules.nudgeCap} · beeps every {today.data.rules.beepIntervalDays} days</span>}
      </div>
      {today.isPending && <p className="muted">Loading…</p>}
      <ErrorNote error={today.error} />
      {today.data && today.data.items.length === 0 && (
        <p className="muted">Nothing due. Start someone new, or import your connections: no contact falls through the cracks.</p>
      )}
      <ol className="today-list">
        {today.data?.items.map((item) => <TodayItem key={item.track.id} item={item} />)}
      </ol>
    </>
  );
}

function useBoard(filters: Record<string, string>) {
  const params = new URLSearchParams(Object.entries(filters).filter(([, v]) => v));
  return useQuery({ queryKey: ['gtn', 'tracks', params.toString()], queryFn: () => api.get<GtnBoard>(`/network/tracks?${params}`) });
}

function TrackCard({ t, draggable }: { t: GtnTrack; draggable?: boolean }) {
  return (
    <div className={`board-card${t.overdue ? ' overdue' : ''}`} draggable={draggable}
      onDragStart={(e) => {
        e.dataTransfer.setData('text/plain', JSON.stringify({ id: t.id, version: t.version, stage: t.stage }));
        e.dataTransfer.effectAllowed = 'move';
      }}>
      <Link to={`/network/${t.id}`} className="board-title">{t.fullName}</Link>
      <div className="muted small">{t.company ?? '—'}</div>
      <div className="small">{t.nextAction}</div>
      <div className={`small ${t.overdue ? 'bad-text' : 'muted'}`}>
        {t.overdue ? 'Overdue since ' : 'Next: '}{formatDate(t.nextActionOn)} · {t.daysInStage} day{t.daysInStage === 1 ? '' : 's'} in stage
      </div>
      <div className="muted small">Last touch {formatDate(t.lastInOn && (!t.lastOutOn || t.lastInOn > t.lastOutOn) ? t.lastInOn : t.lastOutOn)}
        {t.whyReason && <> · {WHY_LABELS[t.whyReason]}</>}</div>
    </div>
  );
}

function BoardTab({ editable }: { editable: boolean }) {
  const queryClient = useQueryClient();
  const [mine, setMine] = useState('true');
  const [q, setQ] = useState('');
  const board = useBoard({ mine, q });
  const [over, setOver] = useState<GtnStage | null>(null);
  const move = useMutation({
    mutationFn: (v: { id: number; version: number; stage: GtnStage }) => api.post<GtnTrackPage>(`/network/tracks/${v.id}/actions`,
      { action: 'MOVED', stage: v.stage, note: 'Moved on the board', version: v.version }),
    onSettled: () => queryClient.invalidateQueries({ queryKey: ['gtn'] }),
  });
  return (
    <>
      <div className="toolbar">
        <input type="search" aria-label="Search the board" placeholder="Search name, company or role" value={q} onChange={(e) => setQ(e.target.value)} />
        <label className="inline-check"><input type="checkbox" checked={mine === 'true'} onChange={(e) => setMine(e.target.checked ? 'true' : '')} /> Mine only</label>
        {editable && <span className="muted small">Drag a card to move it; the move is recorded.</span>}
      </div>
      <ErrorNote error={move.error} />
      <div className="gtn-board">
        {GTN_STAGES.map((s) => {
          const cards = (board.data?.tracks ?? []).filter((t) => t.stage === s);
          return (
            <section key={s} className={`board-column${over === s ? ' drop' : ''}`} aria-label={GTN_STAGE_LABELS[s]}
              onDragOver={(e) => { if (editable) { e.preventDefault(); setOver(s); } }}
              onDragLeave={() => setOver(null)}
              onDrop={(e) => {
                e.preventDefault();
                setOver(null);
                const v = JSON.parse(e.dataTransfer.getData('text/plain') || '{}') as { id?: number; version?: number; stage?: GtnStage };
                if (v.id && v.stage !== s) move.mutate({ id: v.id, version: v.version ?? 0, stage: s });
              }}>
              <h2>{GTN_STAGE_LABELS[s]}</h2>
              <p className="muted small">{cards.length}</p>
              {cards.map((t) => <TrackCard key={t.id} t={t} draggable={editable} />)}
            </section>
          );
        })}
      </div>
    </>
  );
}

function ListTab() {
  const [f, setF] = useState({ q: '', stage: '', reason: '', source: '', mine: '', overdue: '' });
  const board = useBoard(f);
  const sources = useQuery({ queryKey: ['connection-sources'], queryFn: () => api.get<string[]>('/connections/sources') });
  const [sort, setSort] = useState<'next' | 'name' | 'days'>('next');
  const rows = [...(board.data?.tracks ?? [])].sort((a, b) => sort === 'name' ? a.fullName.localeCompare(b.fullName)
    : sort === 'days' ? b.daysInStage - a.daysInStage : a.nextActionOn.localeCompare(b.nextActionOn));
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value });
  return (
    <>
      <div className="toolbar">
        <input type="search" aria-label="Search" placeholder="Search name, company or role" value={f.q} onChange={set('q')} />
        <select aria-label="Stage" value={f.stage} onChange={set('stage')}>
          <option value="">Any stage</option>
          {GTN_STAGES.map((s) => <option key={s} value={s}>{GTN_STAGE_LABELS[s]}</option>)}
        </select>
        <select aria-label="Why? reason" value={f.reason} onChange={set('reason')}>
          <option value="">Any reason</option>
          {(Object.keys(WHY_LABELS) as WhyReason[]).map((r) => <option key={r} value={r}>{WHY_LABELS[r]}</option>)}
        </select>
        <select aria-label="Source" value={f.source} onChange={set('source')}>
          <option value="">Any source</option>
          {sources.data?.map((s) => <option key={s} value={s}>{s}</option>)}
        </select>
        <label className="inline-check"><input type="checkbox" checked={!!f.mine} onChange={(e) => setF({ ...f, mine: e.target.checked ? 'true' : '' })} /> Mine</label>
        <label className="inline-check"><input type="checkbox" checked={!!f.overdue} onChange={(e) => setF({ ...f, overdue: e.target.checked ? 'true' : '' })} /> Overdue</label>
        <select aria-label="Sort" value={sort} onChange={(e) => setSort(e.target.value as typeof sort)}>
          <option value="next">Next action first</option>
          <option value="days">Longest in stage</option>
          <option value="name">Name</option>
        </select>
        <span className="muted">{rows.length} shown</span>
      </div>
      <table className="table">
        <thead><tr><th>Name</th><th>Company</th><th>Stage</th><th>Next action</th><th>Due</th><th>In stage</th><th>Source</th><th>Owner</th></tr></thead>
        <tbody>
          {rows.map((t) => (
            <tr key={t.id} className={t.overdue ? 'overdue' : ''}>
              <td><Link to={`/network/${t.id}`}>{t.fullName}</Link></td>
              <td>{t.company ?? '—'}</td>
              <td><StagePill stage={t.stage} />{t.whyReason && <div className="muted small">{WHY_LABELS[t.whyReason]}</div>}</td>
              <td>{t.nextAction}</td>
              <td className={t.overdue ? 'bad-text' : ''}>{formatDate(t.nextActionOn)}</td>
              <td>{t.daysInStage} d</td>
              <td>{t.sourceTag ?? '—'}</td>
              <td>{t.ownerName}</td>
            </tr>
          ))}
          {board.data && rows.length === 0 && <tr><td colSpan={8} className="muted">Nobody matches.</td></tr>}
        </tbody>
      </table>
    </>
  );
}

const blankTemplate = { name: '', stage: 'INITIATE' as GtnStage, reason: '', channel: 'ANY', subject: '', body: '', active: true };

function TemplatesTab({ editable }: { editable: boolean }) {
  const queryClient = useQueryClient();
  const list = useQuery({ queryKey: ['gtn-templates', 'all'], queryFn: () => api.get<GtnTemplate[]>('/network/templates?all=true') });
  const [editing, setEditing] = useState<GtnTemplate | 'new' | null>(null);
  const [form, setForm] = useState(blankTemplate);
  const save = useMutation({
    mutationFn: () => {
      const body = { ...form, reason: form.stage === 'UNRESPONSIVE' ? form.reason || null : null };
      return editing === 'new' ? api.post<GtnTemplate>('/network/templates', body)
        : api.put<GtnTemplate>(`/network/templates/${(editing as GtnTemplate).id}`, { ...body, version: (editing as GtnTemplate).version });
    },
    onSuccess: () => {
      setEditing(null);
      queryClient.invalidateQueries({ queryKey: ['gtn-templates'] });
    },
  });
  const remove = useMutation({
    mutationFn: (id: number) => api.del<void>(`/network/templates/${id}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['gtn-templates'] }),
  });
  const open = (t: GtnTemplate | 'new') => {
    setEditing(t);
    setForm(t === 'new' ? blankTemplate : { name: t.name, stage: t.stage, reason: t.reason ?? '', channel: t.channel, subject: t.subject ?? '', body: t.body, active: t.active });
  };
  return (
    <>
      <p className="muted small">Merge fields: {'{first_name}'} {'{full_name}'} {'{company}'} {'{how_we_know}'} {'{last_topic}'} {'{revisit_note}'} {'{my_name}'}.
        Nudges have one template per Why? reason. You always see and can edit the message before sending.</p>
      {editable && !editing && <button type="button" onClick={() => open('new')}>New template</button>}
      {editing && (
        <form className="form" aria-label="Template" onSubmit={(e) => { e.preventDefault(); save.mutate(); }}>
          <div className="grid">
            <label>Name<input aria-label="Name" value={form.name} maxLength={120} onChange={(e) => setForm({ ...form, name: e.target.value })} /></label>
            <label>Stage
              <select aria-label="Template stage" value={form.stage} onChange={(e) => setForm({ ...form, stage: e.target.value as GtnStage })}>
                {GTN_STAGES.map((s) => <option key={s} value={s}>{GTN_STAGE_LABELS[s]}</option>)}
              </select>
            </label>
            {form.stage === 'UNRESPONSIVE' && (
              <label>Why? reason
                <select aria-label="Template reason" value={form.reason} onChange={(e) => setForm({ ...form, reason: e.target.value })}>
                  <option value="">Choose…</option>
                  {(Object.keys(WHY_LABELS) as WhyReason[]).map((r) => <option key={r} value={r}>{WHY_LABELS[r]}</option>)}
                </select>
              </label>
            )}
            <label>Channel
              <select aria-label="Template channel" value={form.channel} onChange={(e) => setForm({ ...form, channel: e.target.value })}>
                <option value="ANY">Any</option><option value="LINKEDIN">LinkedIn</option><option value="WHATSAPP">WhatsApp</option><option value="EMAIL">Email</option>
              </select>
            </label>
            <label>Email subject<input aria-label="Email subject" value={form.subject} maxLength={200} onChange={(e) => setForm({ ...form, subject: e.target.value })} /></label>
          </div>
          <label className="wide">Message<textarea aria-label="Template message" rows={4} maxLength={2000} value={form.body} onChange={(e) => setForm({ ...form, body: e.target.value })} /></label>
          <label className="inline-check"><input type="checkbox" checked={form.active} onChange={(e) => setForm({ ...form, active: e.target.checked })} /> In use</label>
          <ErrorNote error={save.error} />
          <div className="actions">
            <button type="submit" disabled={save.isPending}>Save template</button>
            <button type="button" className="link-plain" onClick={() => setEditing(null)}>Cancel</button>
          </div>
        </form>
      )}
      <table className="table">
        <thead><tr><th>Template</th><th>Stage</th><th>Channel</th><th>Message</th><th /></tr></thead>
        <tbody>
          {list.data?.map((t) => (
            <tr key={t.id} className={t.active ? '' : 'muted'}>
              <td>{t.name}{!t.active && ' (not in use)'}</td>
              <td>{GTN_STAGE_LABELS[t.stage]}{t.reason && <div className="muted small">{WHY_LABELS[t.reason]}</div>}</td>
              <td>{t.channel === 'ANY' ? 'Any' : t.channel.charAt(0) + t.channel.slice(1).toLowerCase()}</td>
              <td className="small">{t.body}</td>
              <td>{editable && <>
                <button type="button" className="link-plain" onClick={() => open(t)}>Edit</button>{' '}
                <button type="button" className="link-plain" onClick={() => window.confirm(`Delete "${t.name}"?`) && remove.mutate(t.id)}>Delete</button>
              </>}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </>
  );
}

/** Start someone: search Connections for people not on GTN yet, then write the first message. */
function PickPerson({ onClose }: { onClose: () => void }) {
  const navigate = useNavigate();
  const [q, setQ] = useState('');
  const [chosen, setChosen] = useState<ConnectionPage['items'][number] | null>(null);
  const people = useQuery({
    queryKey: ['connections', 'untracked', q],
    queryFn: () => api.get<ConnectionPage>(`/connections?tracked=false&q=${encodeURIComponent(q)}`),
  });
  if (chosen) {
    return <StartGtn c={chosen} onStarted={(p) => navigate(`/network/${p.detail.track.id}`)} onCancel={() => setChosen(null)} />;
  }
  return (
    <section className="form" aria-label="Start someone on GTN">
      <h2>Who do you want to reach?</h2>
      <input type="search" aria-label="Find someone in Connections" placeholder="Name, company or source" value={q} onChange={(e) => setQ(e.target.value)} autoFocus />
      <ul className="pick-list">
        {people.data?.items.slice(0, 12).map((c) => (
          <li key={c.id}>
            <button type="button" className="link-plain" onClick={() => setChosen(c)}>{c.fullName}</button>
            <span className="muted small"> {[c.currentRole, c.currentCompany, c.sourceTag].filter(Boolean).join(' · ')}</span>
          </li>
        ))}
        {people.data && people.data.items.length === 0 && <li className="muted">Nobody found who isn't already on GTN. Add them in Connections or import a CSV.</li>}
      </ul>
      <button type="button" className="link-plain" onClick={onClose}>Close</button>
    </section>
  );
}
