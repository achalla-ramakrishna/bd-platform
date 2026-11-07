import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router';
import { api, type Deployment, type Me } from '../lib/api';
import { daysUntil, formatDate, formatRate, humanise } from '../lib/format';

/** End date with a warning pill inside 60 days (FR-GRO-02). */
export function EndDate({ date, status }: { date?: string; status: string }) {
  if (!date) return <span className="muted">Open-ended</span>;
  if (status === 'ENDED') return <>{formatDate(date)}</>;
  const days = daysUntil(date);
  const pill = days < 0
    ? <span className="pill bad">Overdue</span>
    : days <= 30
      ? <span className="pill bad">{days} days</span>
      : days <= 60
        ? <span className="pill warn">{days} days</span>
        : null;
  return <>{formatDate(date)} {pill}</>;
}

export function DeploymentsPage({ me }: { me: Me }) {
  const [view, setView] = useState<'ending' | 'active' | 'all'>('ending');
  const query = view === 'ending' ? '?endingWithinDays=60' : view === 'active' ? '?status=ACTIVE' : '';
  const deployments = useQuery({
    queryKey: ['deployments', view],
    queryFn: () => api.get<Deployment[]>(`/deployments${query}`),
  });

  return (
    <>
      <h1>Deployed teams</h1>
      <p className="lead">Who works where, and which assignments end soon so owners can extend them.</p>
      <div className="toolbar" role="group" aria-label="View">
        <button type="button" className={view === 'ending' ? '' : 'secondary'} onClick={() => setView('ending')}>Ending in 60 days</button>
        <button type="button" className={view === 'active' ? '' : 'secondary'} onClick={() => setView('active')}>All active</button>
        <button type="button" className={view === 'all' ? '' : 'secondary'} onClick={() => setView('all')}>Everything</button>
      </div>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Engineer</th><th>Client</th><th>Role</th><th className="num">Allocation</th>
              {me.canSeeRestricted && <th className="num">Bill rate</th>}
              <th>Started</th><th>Ends</th><th>Status</th>
            </tr>
          </thead>
          <tbody>
            {deployments.data?.length === 0 && (
              <tr><td colSpan={8} className="muted">
                {view === 'ending' ? 'Nothing ends in the next 60 days.' : 'No deployments yet.'}
              </td></tr>
            )}
            {deployments.data?.map((d) => (
              <tr key={d.id}>
                <td>{d.engineerName}<span className="sub">{d.employeeCode} · {humanise(d.seniority)}</span></td>
                <td><Link to={`/accounts/${d.accountId}`}>{d.accountName}</Link>{d.sowRef && <span className="sub">{d.sowRef}</span>}</td>
                <td>{d.role}{d.teamOrProject && <span className="sub">{d.teamOrProject}</span>}</td>
                <td className="num">{d.allocationPercent}%</td>
                {me.canSeeRestricted && <td className="num">{formatRate(d.billRateInr, d.rateUnit)}</td>}
                <td>{formatDate(d.startDate)}</td>
                <td><EndDate date={d.expectedEndDate} status={d.status} /></td>
                <td>{humanise(d.status)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}
