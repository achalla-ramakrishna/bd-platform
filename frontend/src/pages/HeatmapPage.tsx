import { useMemo, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router';
import { api } from '../lib/api';
import { formatDate } from '../lib/format';

interface Item { kind: string; text: string; link: string }
interface Day {
  date: string; total: number; touches: number; meetings: number; conversationsStarted: number; connections: number;
  events: number; interviews: number; items: Item[]; moreItems: number;
}
interface Week { weekStart: string; total: number }
interface Heatmap {
  from: string; to: string; days: Day[]; weeks: Week[];
  summary: { total: number; thisWeek: number; lastWeek: number; activeDaysLast30: number; bestWeek: Week | null };
  people: { id: number; name: string }[];
}

/** Fixed thresholds, so the grid darkens as the team does more over the months (not relative to the busiest day). */
export function level(total: number) {
  return total === 0 ? 0 : total === 1 ? 1 : total <= 3 ? 2 : total <= 6 ? 3 : 4;
}
const LEVEL_TEXT = ['No activity', '1 a day', '2 to 3 a day', '4 to 6 a day', '7 or more a day'];

const CELL = 13;
const GAP = 3;
const STEP = CELL + GAP;
const LEFT = 30;
const TOP = 18;
const DAY_LABELS = ['Mon', '', 'Wed', '', 'Fri', '', ''];
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

function parse(iso: string) {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(y, m - 1, d);
}

function breakdown(d: Day) {
  const parts: string[] = [];
  if (d.touches) parts.push(`${d.touches} touch${d.touches === 1 ? '' : 'es'}`);
  if (d.meetings) parts.push(`${d.meetings} meeting${d.meetings === 1 ? '' : 's'}`);
  if (d.conversationsStarted) parts.push(`${d.conversationsStarted} conversation${d.conversationsStarted === 1 ? '' : 's'} started`);
  if (d.connections) parts.push(`${d.connections} new connection${d.connections === 1 ? '' : 's'}`);
  if (d.events) parts.push(`${d.events} event${d.events === 1 ? '' : 's'}`);
  if (d.interviews) parts.push(`${d.interviews} interview step${d.interviews === 1 ? '' : 's'}`);
  return parts.join(' · ');
}

/** The BD activity heatmap (backlog item 7): from pale to dark green as the network work picks up. */
export function HeatmapPage() {
  const [weeks, setWeeks] = useState('26');
  const [person, setPerson] = useState('');
  const [asTable, setAsTable] = useState(false);
  const data = useQuery({
    queryKey: ['heatmap', weeks, person],
    queryFn: () => api.get<Heatmap>(`/heatmap?weeks=${weeks}${person ? `&userId=${person}` : ''}`),
  });
  const h = data.data;
  const s = h?.summary;
  const trend = s ? s.thisWeek - s.lastWeek : 0;

  return (
    <>
      <div className="title-row"><h1>Activity heatmap</h1></div>
      <p className="lead">
        One square per day, darker when we did more: every touch and conversation update, every new connection, every
        event someone went to, and every leader interview recorded or published. The aim is to go from pale to dark green.
      </p>
      <div className="toolbar">
        <select id="heat-person" aria-label="Whose activity" value={person} onChange={(e) => setPerson(e.target.value)}>
          <option value="">The whole team</option>
          {h?.people.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
        </select>
        <select id="heat-weeks" aria-label="Period" value={weeks} onChange={(e) => setWeeks(e.target.value)}>
          <option value="12">Last 12 weeks</option>
          <option value="26">Last 26 weeks</option>
          <option value="52">Last 52 weeks</option>
        </select>
        <button type="button" className="secondary" onClick={() => setAsTable(!asTable)}>{asTable ? 'Show the grid' : 'Show as a table'}</button>
      </div>

      <div className="cards">
        <div className="card"><div className="label">This week</div><div className="value">{s?.thisWeek ?? '—'}</div>
          {s && <div className="muted small">{trend === 0 ? 'same as' : trend > 0 ? `${trend} more than` : `${-trend} fewer than`} last week ({s.lastWeek})</div>}</div>
        <div className="card"><div className="label">Active days, last 30</div><div className="value">{s?.activeDaysLast30 ?? '—'}</div></div>
        <div className="card"><div className="label">Best week</div><div className="value">{s?.bestWeek?.total ?? '—'}</div>
          {s?.bestWeek && <div className="muted small">from {formatDate(s.bestWeek.weekStart)}</div>}</div>
        <div className="card"><div className="label">In this period</div><div className="value">{s?.total ?? '—'}</div></div>
      </div>

      {data.isPending && <p className="muted">Loading…</p>}
      {data.error && <div className="notice bad">{data.error.message}</div>}
      {h && (asTable ? <HeatTable h={h} /> : <HeatGrid h={h} />)}
    </>
  );
}

function HeatGrid({ h }: { h: Heatmap }) {
  const [selected, setSelected] = useState<number | null>(h.days.length - 1);
  const [hover, setHover] = useState<{ i: number; x: number; y: number } | null>(null);
  const wrap = useRef<HTMLDivElement>(null);
  const cells = useMemo(() => h.days.map((d, i) => {
    const date = parse(d.date);
    const row = (date.getDay() + 6) % 7; // Monday first
    const col = Math.floor((i + ((parse(h.days[0].date).getDay() + 6) % 7)) / 7);
    return { d, i, row, col, date };
  }), [h.days]);
  const columns = cells.length ? Math.max(...cells.map((c) => c.col)) + 1 : 0;
  const monthLabels = cells.filter((c) => c.date.getDate() <= 7 && c.row === 0)
    .map((c) => ({ col: c.col, text: MONTHS[c.date.getMonth()] }));
  const width = LEFT + columns * STEP;
  const height = TOP + 7 * STEP;
  const move = (delta: number) => setSelected((s) => Math.max(0, Math.min(h.days.length - 1, (s ?? h.days.length - 1) + delta)));
  const sel = selected === null ? null : h.days[selected];
  const tip = hover ? h.days[hover.i] : null;

  return (
    <div className="heat">
      <div className="heat-scroll" ref={wrap}>
        <svg width={width} height={height} role="grid" aria-label="Activity per day. Use the arrow keys to move between days."
          tabIndex={0} className="heat-svg"
          onKeyDown={(e) => {
            const step = { ArrowLeft: -7, ArrowRight: 7, ArrowUp: -1, ArrowDown: 1 }[e.key];
            if (step) { e.preventDefault(); move(step); }
          }}>
          {monthLabels.map((m) => <text key={`${m.col}-${m.text}`} x={LEFT + m.col * STEP} y={11} className="heat-label">{m.text}</text>)}
          {DAY_LABELS.map((l, r) => l && <text key={l} x={0} y={TOP + r * STEP + CELL - 2} className="heat-label">{l}</text>)}
          {cells.map((c) => (
            <rect key={c.d.date} x={LEFT + c.col * STEP} y={TOP + c.row * STEP} width={CELL} height={CELL} rx={3}
              className={`heat-cell l${level(c.d.total)}${selected === c.i ? ' selected' : ''}`}
              onPointerMove={(e) => {
                const box = wrap.current?.getBoundingClientRect();
                if (box) setHover({ i: c.i, x: e.clientX - box.left + (wrap.current?.scrollLeft ?? 0), y: e.clientY - box.top });
              }}
              onPointerLeave={() => setHover(null)}
              onClick={() => setSelected(c.i)}>
              <title>{`${formatDate(c.d.date)}: ${c.d.total}`}</title>
            </rect>
          ))}
        </svg>
        {tip && hover && (
          <div className="heat-tip" style={{ left: hover.x + 12, top: hover.y + 12 }} role="presentation">
            <strong>{tip.total}</strong> <span className="muted">on {formatDate(tip.date)}</span>
            {tip.total > 0 && <div className="small">{breakdown(tip)}</div>}
          </div>
        )}
      </div>
      <div className="heat-legend" aria-label="Legend">
        <span className="muted small">Less</span>
        {[0, 1, 2, 3, 4].map((l) => (
          <span key={l} className="heat-key" title={LEVEL_TEXT[l]}>
            <svg width={CELL} height={CELL} aria-hidden="true"><rect width={CELL} height={CELL} rx={3} className={`heat-cell l${l}`} /></svg>
          </span>
        ))}
        <span className="muted small">More</span>
        <span className="muted small legend-note">Faintest green is 1 a day; strongest is 7 or more.</span>
      </div>

      {sel && (
        <section className="heat-day" aria-live="polite">
          <h2>{formatDate(sel.date)} <span className="muted">· {sel.total === 0 ? 'nothing recorded' : breakdown(sel)}</span></h2>
          {sel.items.length > 0 && (
            <ul>
              {sel.items.map((it, n) => <li key={n}><Link to={it.link}>{it.text}</Link></li>)}
              {sel.moreItems > 0 && <li className="muted">and {sel.moreItems} more</li>}
            </ul>
          )}
        </section>
      )}
    </div>
  );
}

function HeatTable({ h }: { h: Heatmap }) {
  const byWeek = [...h.weeks].reverse();
  return (
    <div className="table-wrap">
      <table>
        <thead><tr><th>Week starting</th><th className="num">Total</th><th>Busiest day</th></tr></thead>
        <tbody>
          {byWeek.map((w) => {
            const days = h.days.filter((d) => d.date >= w.weekStart && parse(d.date).getTime() < parse(w.weekStart).getTime() + 7 * 864e5);
            const best = days.reduce<Day | null>((b, d) => (d.total > (b?.total ?? 0) ? d : b), null);
            return (
              <tr key={w.weekStart}>
                <td>{formatDate(w.weekStart)}</td>
                <td className="num">{w.total}</td>
                <td>{best ? `${formatDate(best.date)}: ${breakdown(best)}` : <span className="muted">—</span>}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
