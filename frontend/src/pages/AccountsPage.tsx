import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router';
import { api, type Account, type Page } from '../lib/api';
import { humanise } from '../lib/format';

export function relationshipPill(rel: Account['relationship']) {
  const cls = rel === 'CLIENT' ? 'good' : rel === 'PROSPECT' ? 'accent' : '';
  return <span className={`pill ${cls}`}>{humanise(rel)}</span>;
}

export function AccountsPage() {
  const [q, setQ] = useState('');
  const [relationship, setRelationship] = useState('');
  const params = new URLSearchParams({ size: '200' });
  if (q) params.set('q', q);
  if (relationship) params.set('relationship', relationship);
  const accounts = useQuery({
    queryKey: ['accounts', q, relationship],
    queryFn: () => api.get<Page<Account>>(`/accounts?${params}`),
  });

  return (
    <>
      <h1>Accounts</h1>
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
        {accounts.data && <span className="muted">{accounts.data.total} accounts</span>}
      </div>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
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
              <tr><td colSpan={6} className="muted">No accounts match. Upload accounts on the Uploads page.</td></tr>
            )}
            {accounts.data?.items.map((a) => (
              <tr key={a.id}>
                <td>
                  <Link to={`/accounts/${a.id}`}>{a.name}</Link>
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
