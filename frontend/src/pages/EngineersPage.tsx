import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api, type Engineer } from '../lib/api';
import { formatDate, humanise } from '../lib/format';

function availabilityPill(e: Engineer) {
  const cls = e.availability === 'BENCH' ? 'warn' : e.availability === 'DEPLOYED' ? 'good' : '';
  return <span className={`pill ${cls}`}>{humanise(e.availability)}</span>;
}

export function EngineersPage() {
  const [availability, setAvailability] = useState('');
  const [seniority, setSeniority] = useState('');
  const params = new URLSearchParams();
  if (availability) params.set('availability', availability);
  if (seniority) params.set('seniority', seniority);
  const engineers = useQuery({
    queryKey: ['engineers', availability, seniority],
    queryFn: () => api.get<Engineer[]>(`/engineers?${params}`),
  });
  const bench = engineers.data?.filter((e) => e.availability === 'BENCH').length ?? 0;

  return (
    <>
      <h1>Engineers</h1>
      <p className="lead">
        Everyone, with current allocation. Junior and mid-level engineers work for one client at a time; senior engineers
        and above may split across two.
      </p>
      <div className="toolbar">
        <select id="eng-availability" aria-label="Availability" value={availability} onChange={(e) => setAvailability(e.target.value)}>
          <option value="">Any availability</option>
          <option value="BENCH">Bench</option>
          <option value="DEPLOYED">Deployed</option>
          <option value="NOTICE">Notice</option>
          <option value="LEAVE">Leave</option>
        </select>
        <select id="eng-seniority" aria-label="Seniority" value={seniority} onChange={(e) => setSeniority(e.target.value)}>
          <option value="">Any seniority</option>
          {['JUNIOR', 'MID', 'SENIOR', 'LEAD', 'PRINCIPAL'].map((s) => <option key={s} value={s}>{humanise(s)}</option>)}
        </select>
        {engineers.data && <span className="muted">{engineers.data.length} engineers, {bench} on the bench</span>}
      </div>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Engineer</th><th>Role</th><th>Seniority</th><th>Skills</th><th>Availability</th>
              <th className="num">Allocated today</th><th>Location</th>
            </tr>
          </thead>
          <tbody>
            {engineers.data?.length === 0 && <tr><td colSpan={7} className="muted">No engineers match.</td></tr>}
            {engineers.data?.map((e) => (
              <tr key={e.id}>
                <td>{e.fullName}<span className="sub">{e.employeeCode}</span></td>
                <td>{e.primaryRole}</td>
                <td>{humanise(e.seniority)}</td>
                <td>{e.skills.join(', ')}</td>
                <td>
                  {availabilityPill(e)}
                  {e.availableFrom && <span className="sub">from {formatDate(e.availableFrom)}</span>}
                </td>
                <td className="num">{e.currentAllocationPercent}%</td>
                <td>{e.location ?? '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}
