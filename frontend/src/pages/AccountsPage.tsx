import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router';
import { api, canEditPipeline, type Account, type Me, type Page } from '../lib/api';
import { UploadResearch } from './UploadResearch';
import { formatDate, humanise } from '../lib/format';

export function relationshipPill(rel: Account['relationship']) {
  const cls = rel === 'CLIENT' ? 'good' : rel === 'PROSPECT' ? 'accent' : '';
  return <span className={`pill ${cls}`}>{humanise(rel)}</span>;
}

export function FitPill({ score, reasons }: { score?: number | null; reasons: string[] }) {
  if (score === null || score === undefined) return <span className="muted">—</span>;
  const cls = score >= 60 ? 'good' : score >= 30 ? 'warn' : '';
  return <span className={`pill ${cls}`} title={reasons.join('\n')}>{score}</span>;
}

export function AccountsPage({ me }: { me: Me }) {
  const [q, setQ] = useState('');
  const [relationship, setRelationship] = useState('');
  const [sort, setSort] = useState('fit');
  const [researched, setResearched] = useState('');
  const params = new URLSearchParams({ size: '200', sort });
  if (q) params.set('q', q);
  if (relationship) params.set('relationship', relationship);
  if (researched) params.set('researched', researched);
  const accounts = useQuery({
    queryKey: ['accounts', q, relationship, sort, researched],
    queryFn: () => api.get<Page<Account>>(`/accounts?${params}`),
  });

  return (
    <>
      <div className="title-row">
        <h1>Accounts</h1>
        {canEditPipeline(me) && <UploadResearch />}
      </div>
      <p className="lead">GCCs and enterprise centres: clients, past clients and prospects.</p>
      <div className="toolbar">
        <input
          type="search"
          id="account-search"
          placeholder="Search name, parent or domain"
          aria-label="Search accounts"
          value={q}
          onChange={(e) => setQ(e.target.value)}
        />
        <select id="account-relationship" aria-label="Relationship" value={relationship} onChange={(e) => setRelationship(e.target.value)}>
          <option value="">All relationships</option>
          <option value="CLIENT">Clients</option>
          <option value="PROSPECT">Prospects</option>
          <option value="PAST_CLIENT">Past clients</option>
        </select>
        <select id="account-research" aria-label="Research" value={researched} onChange={(e) => setResearched(e.target.value)}>
          <option value="">With and without research</option>
          <option value="true">Research ready</option>
          <option value="false">No research yet</option>
        </select>
        <select id="account-sort" aria-label="Sort" value={sort} onChange={(e) => setSort(e.target.value)}>
          <option value="fit">Best fit first</option>
          <option value="name">Name</option>
        </select>
        {accounts.data && <span className="muted">{accounts.data.total} accounts{accounts.data.total > accounts.data.items.length ? `, showing ${accounts.data.items.length}` : ''}</span>}
      </div>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th className="num">Fit</th>
              <th>Account</th>
              <th>City</th>
              <th>Industry</th>
              <th>Relationship</th>
              <th className="num">Engineers deployed</th>
              <th>Owner</th>
            </tr>
          </thead>
          <tbody>
            {accounts.data?.items.length === 0 && (
              <tr><td colSpan={7} className="muted">No accounts match. Upload accounts on the Uploads page.</td></tr>
            )}
            {accounts.data?.items.map((a) => (
              <tr key={a.id} className={a.researchedAt ? 'researched' : undefined}>
                <td className="num"><FitPill score={a.fitScore} reasons={a.fitReasons} /></td>
                <td>
                  <Link to={`/accounts/${a.id}`}>{a.name}</Link>
                  {a.researchedAt && (
                    <Link to={`/accounts/${a.id}#research`} className="pill research-pill" title={`Researched ${formatDate(a.researchedAt)}`}>Research ready</Link>
                  )}
                  <span className="sub">{a.parentCompany} · {a.websiteDomain}</span>
                </td>
                <td>{a.city}</td>
                <td>{a.industry ?? '—'}</td>
                <td>{relationshipPill(a.relationship)}</td>
                <td className="num">{a.activeEngineers}</td>
                <td>{a.ownerName}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}
