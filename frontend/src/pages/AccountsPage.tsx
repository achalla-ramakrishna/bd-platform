import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router';
import { api, accountSize, canEditPipeline, type Account, type AccountFacets, type Me, type Page } from '../lib/api';
import { UploadResearch } from './UploadResearch';
import { CompanyLogo } from './CompanyLogo';
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
  const [city, setCity] = useState('');
  const [formed, setFormed] = useState('');
  const [sizeBand, setSizeBand] = useState('');
  const [industry, setIndustry] = useState('');
  const [tech, setTech] = useState('');
  const params = new URLSearchParams({ size: '1000', sort });
  if (q) params.set('q', q);
  if (relationship) params.set('relationship', relationship);
  if (researched) params.set('researched', researched);
  if (city) params.set('city', city);
  if (formed) params.set('formedSince', String(new Date().getFullYear() - Number(formed)));
  if (sizeBand) params.set('sizeBand', sizeBand);
  if (industry) params.set('industry', industry);
  if (tech) params.set('tech', tech);
  const accounts = useQuery({
    queryKey: ['accounts', params.toString()],
    queryFn: () => api.get<Page<Account>>(`/accounts?${params}`),
  });
  const facets = useQuery({ queryKey: ['account-facets'], queryFn: () => api.get<AccountFacets>('/accounts/facets') });
  const filtered = !!(q || relationship || researched || city || formed || sizeBand || industry || tech);
  const clear = () => {
    setQ(''); setRelationship(''); setResearched(''); setCity(''); setFormed(''); setSizeBand(''); setIndustry(''); setTech('');
  };

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
        <select id="account-city" aria-label="City" value={city} onChange={(e) => setCity(e.target.value)}>
          <option value="">All cities</option>
          {facets.data?.cities.map((c) => <option key={c} value={c}>{c}</option>)}
        </select>
        <select id="account-formed" aria-label="Year formed" value={formed} onChange={(e) => setFormed(e.target.value)}>
          <option value="">Formed any year</option>
          <option value="2">Formed in the last 2 years</option>
          <option value="5">Formed in the last 5 years</option>
        </select>
        <select id="account-size" aria-label="Size" value={sizeBand} onChange={(e) => setSizeBand(e.target.value)}>
          <option value="">Any size</option>
          <option value="<500">Micro: under 500</option>
          <option value="500-2000">500 to 2,000</option>
          <option value="2000-5000">2,000 to 5,000</option>
          <option value="5000+">5,000 and more</option>
        </select>
        <select id="account-industry" aria-label="Industry" value={industry} onChange={(e) => setIndustry(e.target.value)}>
          <option value="">All industries</option>
          {facets.data?.industries.map((i) => <option key={i} value={i}>{i}</option>)}
        </select>
        <select id="account-tech" aria-label="Tech stack" value={tech} onChange={(e) => setTech(e.target.value)}>
          <option value="">Any tech stack</option>
          <option value="Java">Uses Java</option>
          <option value="React">Uses React</option>
        </select>
        <select id="account-sort" aria-label="Sort" value={sort} onChange={(e) => setSort(e.target.value)}>
          <option value="fit">Best fit first</option>
          <option value="fit_asc">Lowest fit first</option>
          <option value="newest">Newest GCC first</option>
          <option value="smallest">Smallest first</option>
          <option value="open_roles">Most open roles</option>
          <option value="name">Name</option>
        </select>
        {filtered && <button type="button" className="secondary" onClick={clear}>Clear filters</button>}
        {accounts.data && <span className="muted">{accounts.data.total} accounts{accounts.data.total > accounts.data.items.length ? `, showing ${accounts.data.items.length}` : ''}</span>}
      </div>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th className="num" aria-sort={sort === 'fit' ? 'descending' : sort === 'fit_asc' ? 'ascending' : 'none'}>
                <button type="button" className="th-sort" onClick={() => setSort(sort === 'fit' ? 'fit_asc' : 'fit')}>
                  Fit{sort === 'fit' ? ' ↓' : sort === 'fit_asc' ? ' ↑' : ''}
                </button>
              </th>
              <th aria-sort={sort === 'name' ? 'ascending' : 'none'}>
                <button type="button" className="th-sort" onClick={() => setSort('name')}>Account{sort === 'name' ? ' ↑' : ''}</button>
              </th>
              <th>City</th>
              <th className="num" aria-sort={sort === 'newest' ? 'descending' : 'none'}>
                <button type="button" className="th-sort" onClick={() => setSort('newest')}>Formed{sort === 'newest' ? ' ↓' : ''}</button>
              </th>
              <th className="num" aria-sort={sort === 'smallest' ? 'ascending' : 'none'}>
                <button type="button" className="th-sort" onClick={() => setSort('smallest')}>Employees{sort === 'smallest' ? ' ↑' : ''}</button>
              </th>
              <th className="num" aria-sort={sort === 'open_roles' ? 'descending' : 'none'}>
                <button type="button" className="th-sort" onClick={() => setSort('open_roles')}>Open roles{sort === 'open_roles' ? ' ↓' : ''}</button>
              </th>
              <th>Industry</th>
              <th>Relationship</th>
              <th className="num">Engineers deployed</th>
              <th>Owner</th>
            </tr>
          </thead>
          <tbody>
            {accounts.data?.items.length === 0 && (
              <tr><td colSpan={10} className="muted">No accounts match{filtered ? ' these filters' : '. Upload accounts on the Uploads page'}.</td></tr>
            )}
            {accounts.data?.items.map((a) => (
              <tr key={a.id} className={a.researchedAt ? 'researched' : undefined}>
                <td className="num"><FitPill score={a.fitScore} reasons={a.fitReasons} /></td>
                <td>
                  <div className="account-cell">
                  <CompanyLogo domain={a.websiteDomain} name={a.parentCompany || a.name} />
                  <div>
                  <Link to={`/accounts/${a.id}`}>{a.name}</Link>
                  {a.researchedAt && (
                    <Link to={`/accounts/${a.id}#research`} className="pill research-pill" title={`Researched ${formatDate(a.researchedAt)}`}>Research ready</Link>
                  )}
                  <span className="sub">{a.parentCompany} · {a.websiteDomain}</span>
                  </div>
                  </div>
                </td>
                <td>{a.city}</td>
                <td className="num">{a.yearEstablished ?? '—'}</td>
                <td className="num">{accountSize(a)}</td>
                <td className="num" title={a.openRolesAsOf ? `Counted ${formatDate(a.openRolesAsOf)}` : undefined}>{a.openRoles ?? '—'}</td>
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
