import { useQuery } from '@tanstack/react-query';
import { Link, useParams } from 'react-router';
import { api, canEditPipeline, type AccountDetail, type Me, type Opportunity } from '../lib/api';
import { formatDate, formatInr, formatRate, humanise } from '../lib/format';
import { relationshipPill } from './AccountsPage';
import { EndDate } from './DeploymentsPage';
import { OpportunityTable } from './PipelinePage';
import { ActivityLog } from './ActivityLog';
import { AddFromLinkedIn } from './AddFromLinkedIn';
import { useState } from 'react';

export function AccountDetailPage({ me }: { me: Me }) {
  const { id } = useParams();
  const detail = useQuery({ queryKey: ['account', id], queryFn: () => api.get<AccountDetail>(`/accounts/${id}`) });
  const [adding, setAdding] = useState(false);
  const opportunities = useQuery({
    queryKey: ['opportunities', 'account', id],
    queryFn: () => api.get<Opportunity[]>(`/opportunities?accountId=${id}`),
  });

  if (detail.isPending) return <p className="muted">Loading…</p>;
  if (detail.error) return <div className="notice bad">{detail.error.message}</div>;
  const { account: a, contacts, contracts, deployments, monthlyBillingInr } = detail.data;
  const active = deployments.filter((d) => d.status === 'ACTIVE');

  return (
    <>
      <p><Link to="/accounts">← Accounts</Link></p>
      <h1>{a.name} {relationshipPill(a.relationship)}</h1>
      <p className="lead">
        {a.parentCompany}{a.parentHqCountry ? ` (${a.parentHqCountry})` : ''} · {a.city} · {a.websiteDomain}
        {a.industry ? ` · ${a.industry}` : ''}
      </p>

      <div className="cards">
        <div className="card"><div className="label">Engineers deployed</div><div className="value">{a.activeEngineers}</div></div>
        {me.canSeeRestricted && (
          <div className="card"><div className="label">Monthly billing</div><div className="value">{formatInr(monthlyBillingInr)}</div></div>
        )}
        <div className="card"><div className="label">Contacts</div><div className="value">{contacts.length}</div></div>
        <div className="card"><div className="label">Owner</div><div className="value" style={{ fontSize: 15 }}>{a.ownerName}</div></div>
      </div>
      {a.techStack.length > 0 && <p className="muted">Tech stack: {a.techStack.join(', ')}</p>}

      <div className="title-row">
        <h2>Opportunities</h2>
        {canEditPipeline(me) && <Link className="button secondary" to={`/opportunities/new?accountId=${a.id}`}>New opportunity</Link>}
      </div>
      <OpportunityTable items={opportunities.data ?? []} />

      <h2>Activity</h2>
      <ActivityLog accountId={a.id} contacts={contacts} editable={canEditPipeline(me)} />

      <h2>Deployed team</h2>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Engineer</th><th>Role</th><th>Team</th><th className="num">Allocation</th>
              {me.canSeeRestricted && <th className="num">Bill rate</th>}
              <th>Ends</th><th>Status</th>
            </tr>
          </thead>
          <tbody>
            {deployments.length === 0 && <tr><td colSpan={7} className="muted">No deployments.</td></tr>}
            {deployments.map((d) => (
              <tr key={d.id}>
                <td>{d.engineerName}<span className="sub">{d.employeeCode} · {humanise(d.seniority)}</span></td>
                <td>{d.role}</td>
                <td>{d.teamOrProject ?? '—'}{d.sowRef && <span className="sub">{d.sowRef}</span>}</td>
                <td className="num">{d.allocationPercent}%</td>
                {me.canSeeRestricted && <td className="num">{formatRate(d.billRateInr, d.rateUnit)}</td>}
                <td><EndDate date={d.expectedEndDate} status={d.status} /></td>
                <td>{humanise(d.status)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {active.length > 0 && <p className="muted">{active.length} active deployments.</p>}

      <h2>Contracts</h2>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Reference</th><th>Type</th><th>Work</th>
              {me.canSeeRestricted && <th className="num">Value</th>}
              <th>Starts</th><th>Ends</th><th>Status</th><th>May name client</th>
            </tr>
          </thead>
          <tbody>
            {contracts.length === 0 && <tr><td colSpan={8} className="muted">No contracts.</td></tr>}
            {contracts.map((c) => (
              <tr key={c.id}>
                <td>{c.contractRef}{c.parentMsaRef && <span className="sub">under {c.parentMsaRef}</span>}</td>
                <td>{c.contractType}</td>
                <td>{humanise(c.workType)}<span className="sub">{humanise(c.pricingModel)}</span></td>
                {me.canSeeRestricted && <td className="num">{formatInr(c.totalValueInr)}</td>}
                <td>{formatDate(c.startDate)}</td>
                <td>{formatDate(c.endDate)}</td>
                <td>{humanise(c.status)}</td>
                <td>{c.clientPermissionToName ? 'Yes' : 'No'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="title-row">
        <h2>Contacts</h2>
        {canEditPipeline(me) && !adding && (
          <button type="button" className="secondary" style={{ marginTop: 22 }} onClick={() => setAdding(true)}>Add from LinkedIn</button>
        )}
      </div>
      {adding && <AddFromLinkedIn account={a} onDone={() => setAdding(false)} />}
      <div className="table-wrap">
        <table>
          <thead><tr><th>Name</th><th>Persona</th><th>Email</th><th>LinkedIn</th><th>Source</th></tr></thead>
          <tbody>
            {contacts.length === 0 && <tr><td colSpan={5} className="muted">No contacts.</td></tr>}
            {contacts.map((c) => (
              <tr key={c.id}>
                <td>{c.fullName}<span className="sub">{c.title}</span></td>
                <td>{humanise(c.persona)}</td>
                <td>{c.email ?? '—'}</td>
                <td>{c.linkedinUrl ? <a href={c.linkedinUrl} target="_blank" rel="noreferrer">Profile</a> : '—'}</td>
                <td>{c.source}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}
