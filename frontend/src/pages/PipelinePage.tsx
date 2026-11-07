import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router';
import { api, canEditPipeline, type Me, type Opportunity, type ReferenceData } from '../lib/api';
import { formatDate, formatInr, humanise } from '../lib/format';

export function useReference() {
  return useQuery({ queryKey: ['reference'], queryFn: () => api.get<ReferenceData>('/reference'), staleTime: Infinity });
}

export function typeLabel(type: string) {
  return type === 'STAFF_AUGMENTATION' ? 'Staff augmentation' : 'Project';
}

function sum(items: Opportunity[], pick: (o: Opportunity) => number | null | undefined) {
  return items.reduce((total, o) => total + (pick(o) ?? 0), 0);
}

/** Pipeline board and table (FR-OPP-07): open stages as columns, won and lost summarised. */
export function PipelinePage({ me }: { me: Me }) {
  const reference = useReference();
  const [view, setView] = useState<'board' | 'table'>('board');
  const [type, setType] = useState('');
  const [mine, setMine] = useState(false);
  const params = new URLSearchParams();
  if (type) params.set('type', type);
  if (mine) params.set('ownerId', String(me.id));
  const opportunities = useQuery({
    queryKey: ['opportunities', type, mine],
    queryFn: () => api.get<Opportunity[]>(`/opportunities?${params}`),
  });

  const all = opportunities.data ?? [];
  const open = all.filter((o) => o.stageOutcome === 'OPEN');
  const won = all.filter((o) => o.stageOutcome === 'WON');
  const lost = all.filter((o) => o.stageOutcome === 'LOST');
  const openStages = reference.data?.stages.filter((s) => s.outcome === 'OPEN') ?? [];

  return (
    <>
      <div className="title-row">
        <div>
          <h1>Pipeline</h1>
          <p className="lead">Staff augmentation and project opportunities, by stage.</p>
        </div>
        {canEditPipeline(me) && <Link className="button" to="/opportunities/new">New opportunity</Link>}
      </div>

      <div className="cards">
        <div className="card"><div className="label">Open opportunities</div><div className="value">{open.length}</div></div>
        <div className="card"><div className="label">Open value</div><div className="value">{formatInr(sum(open, (o) => o.valueInr))}</div></div>
        <div className="card"><div className="label">Weighted forecast</div><div className="value">{formatInr(sum(open, (o) => o.weightedValueInr))}</div></div>
        <div className="card"><div className="label">Won</div><div className="value">{won.length} <span className="muted small">{formatInr(sum(won, (o) => o.valueInr))}</span></div></div>
      </div>

      <div className="toolbar">
        <div role="group" aria-label="View" className="segmented">
          <button type="button" className={view === 'board' ? '' : 'secondary'} onClick={() => setView('board')}>Board</button>
          <button type="button" className={view === 'table' ? '' : 'secondary'} onClick={() => setView('table')}>Table</button>
        </div>
        <select id="pipeline-type" aria-label="Type" value={type} onChange={(e) => setType(e.target.value)}>
          <option value="">All types</option>
          <option value="STAFF_AUGMENTATION">Staff augmentation</option>
          <option value="PROJECT">Projects</option>
        </select>
        <label className="check">
          <input type="checkbox" id="pipeline-mine" checked={mine} onChange={(e) => setMine(e.target.checked)} /> Only mine
        </label>
      </div>

      {opportunities.error && <div className="notice bad">{opportunities.error.message}</div>}

      {view === 'board' ? (
        <div className="board">
          {openStages.map((stage) => {
            const items = open.filter((o) => o.stage === stage.code);
            return (
              <section key={stage.code} className="column" aria-label={stage.label}>
                <header>
                  <strong>{stage.label}</strong>
                  <span className="muted">{items.length} · {formatInr(sum(items, (o) => o.valueInr))}</span>
                </header>
                {items.length === 0 && <p className="muted small empty">None</p>}
                {items.map((o) => <OpportunityCard key={o.id} o={o} />)}
              </section>
            );
          })}
        </div>
      ) : (
        <OpportunityTable items={all} />
      )}

      {view === 'board' && (won.length > 0 || lost.length > 0) && (
        <>
          <h2>Closed</h2>
          <OpportunityTable items={[...won, ...lost]} />
        </>
      )}
      {all.length === 0 && opportunities.isSuccess && (
        <p className="muted">No opportunities yet. Create one from here or from an account page.</p>
      )}
    </>
  );
}

function OpportunityCard({ o }: { o: Opportunity }) {
  return (
    <Link to={`/opportunities/${o.id}`} className="opp-card">
      <span className="opp-name">{o.name}</span>
      <span className="muted small">{o.accountName}</span>
      <span className="opp-meta">
        <span className={`pill ${o.type === 'STAFF_AUGMENTATION' ? 'accent' : ''}`}>{o.type === 'STAFF_AUGMENTATION' ? 'Staff aug' : 'Project'}</span>
        <span className="num">{formatInr(o.valueInr)}</span>
      </span>
      <span className="muted small">
        {o.expectedCloseDate ? `Close ${formatDate(o.expectedCloseDate)}` : 'No close date'} · {o.ownerName}
      </span>
    </Link>
  );
}

export function OpportunityTable({ items }: { items: Opportunity[] }) {
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>Opportunity</th><th>Type</th><th>Stage</th><th className="num">Value</th>
            <th className="num">Probability</th><th className="num">Weighted</th><th>Close</th><th>Owner</th>
          </tr>
        </thead>
        <tbody>
          {items.length === 0 && <tr><td colSpan={8} className="muted">No opportunities.</td></tr>}
          {items.map((o) => (
            <tr key={o.id}>
              <td><Link to={`/opportunities/${o.id}`}>{o.name}</Link><span className="sub">{o.accountName}</span></td>
              <td>{typeLabel(o.type)}</td>
              <td>
                <span className={`pill ${o.stageOutcome === 'WON' ? 'good' : o.stageOutcome === 'LOST' ? 'bad' : ''}`}>{o.stageLabel}</span>
                {o.lossReasonCode && <span className="sub">{humanise(o.lossReasonCode)}</span>}
              </td>
              <td className="num">{formatInr(o.valueInr)}</td>
              <td className="num">{o.probability}%</td>
              <td className="num">{formatInr(o.weightedValueInr)}</td>
              <td>{formatDate(o.expectedCloseDate)}</td>
              <td>{o.ownerName}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
