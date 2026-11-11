import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate } from 'react-router';
import {
  ACCOUNT_LINK_LABELS, STRENGTH_LABELS, ApiError, api, canEditPipeline,
  type Connection, type ConnectionPage, type Me, type Strength,
} from '../lib/api';
import { formatDate } from '../lib/format';
import { AccountPicker } from './AccountPicker';
import { today } from './ConversationsPage';
import { PersonPhoto } from './PersonPhoto';

export function StrengthPill({ strength }: { strength: Strength }) {
  const cls = strength === 'CLOSE' ? 'good' : strength === 'KNOW_WELL' ? 'accent' : '';
  return <span className={`pill ${cls}`}>{STRENGTH_LABELS[strength]}</span>;
}

export function KnownBy({ c }: { c: Connection }) {
  return (
    <span className="known-by">
      {c.knownBy.map((k) => (
        <span key={k.userId} title={k.howKnown ?? undefined}>{k.userName} <StrengthPill strength={k.strength} /></span>
      ))}
    </span>
  );
}

/** Connections (backlog item 4): go to network, not go to market. */
export function ConnectionsPage({ me }: { me: Me }) {
  const [view, setView] = useState<'cards' | 'table'>(() => {
    try {
      return localStorage.getItem('connections.view') === 'table' ? 'table' : 'cards';
    } catch {
      return 'cards';
    }
  });
  const chooseView = (v: 'cards' | 'table') => {
    setView(v);
    try {
      localStorage.setItem('connections.view', v);
    } catch {
      // Storage may be blocked; the choice then lasts for this visit only.
    }
  };
  const [q, setQ] = useState('');
  const [mine, setMine] = useState(false);
  const [linked, setLinked] = useState('');
  const [strength, setStrength] = useState('');
  const [adding, setAdding] = useState(false);
  const params = new URLSearchParams();
  if (q) params.set('q', q);
  if (mine) params.set('mine', 'true');
  if (linked) params.set('linked', linked);
  if (strength) params.set('strength', strength);
  const list = useQuery({ queryKey: ['connections', params.toString()], queryFn: () => api.get<ConnectionPage>(`/connections?${params}`) });
  const s = list.data?.summary;
  const weeks = s?.weekly ?? [];
  const thisWeek = weeks.at(-1)?.added ?? 0;
  const lastWeek = weeks.at(-2)?.added ?? 0;

  const emptyText = q || linked || strength || mine ? 'No connections match.' : 'No connections yet. Add the people you know.';
  return (
    <>
      <div className="title-row">
        <h1>Connections</h1>
        <div className="actions">
          {canEditPipeline(me) && !adding && <button type="button" onClick={() => setAdding(true)}>Add connection</button>}
          {canEditPipeline(me) && !adding && <Link className="button secondary" to="/assistant">Log with AI</Link>}
        </div>
      </div>
      <p className="lead">
        People we know personally. Go to network, not go to market: grow this list, give these people value, and link
        them to the target accounts they work at or can open a door to.
      </p>
      {adding && <AddConnection onDone={() => setAdding(false)} />}

      <div className="cards">
        <div className="card"><div className="label">Connections</div><div className="value">{s?.total ?? '—'}</div></div>
        <div className="card"><div className="label">Known by me</div><div className="value">{s?.knownByMe ?? '—'}</div></div>
        <div className="card"><div className="label">Warm paths into accounts</div><div className="value">{s?.linkedToAccounts ?? '—'}</div></div>
        <div className="card"><div className="label">Added in 30 days</div><div className="value">{s?.addedLast30Days ?? '—'}</div></div>
        <div className="card"><div className="label">Added this week</div><div className="value">{thisWeek}</div>
          <div className="muted small">last week {lastWeek}</div></div>
      </div>

      <div className="toolbar">
        <input type="search" id="conn-search" placeholder="Search name, company, role or account" aria-label="Search connections"
          value={q} onChange={(e) => setQ(e.target.value)} />
        <select id="conn-linked" aria-label="Linked to an account" value={linked} onChange={(e) => setLinked(e.target.value)}>
          <option value="">Linked or not</option>
          <option value="true">Linked to a target account</option>
          <option value="false">Not linked yet</option>
        </select>
        <select id="conn-strength" aria-label="How well" value={strength} onChange={(e) => setStrength(e.target.value)}>
          <option value="">Any closeness</option>
          {Object.entries(STRENGTH_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
        </select>
        <label className="inline-check"><input type="checkbox" id="conn-mine" checked={mine} onChange={(e) => setMine(e.target.checked)} /> Known by me</label>
        {list.data && <span className="muted">{list.data.items.length} shown</span>}
        <div className="segmented view-toggle" role="group" aria-label="Show as">
          <button type="button" className={view === 'cards' ? 'on' : ''} aria-pressed={view === 'cards'} onClick={() => chooseView('cards')}>Cards</button>
          <button type="button" className={view === 'table' ? 'on' : ''} aria-pressed={view === 'table'} onClick={() => chooseView('table')}>Table</button>
        </div>
      </div>
      {view === 'cards'
        ? <ConnectionCards items={list.data?.items ?? []} loading={list.isPending} empty={emptyText} />
        : <ConnectionTable items={list.data?.items ?? []} loading={list.isPending} empty={emptyText} />}
    </>
  );
}

/** Connections as a grid of people cards: photo, name, role, company, who knows them, the account they lead to. */
export function ConnectionCards({ items, loading, empty }: { items: Connection[]; loading?: boolean; empty: string }) {
  const navigate = useNavigate();
  if (loading) return <p className="muted">Loading…</p>;
  if (items.length === 0) return <p className="muted">{empty}</p>;
  return (
    <ul className="person-cards">
      {items.map((c) => (
        <li key={c.id} className="person-card" onClick={() => navigate(`/connections/${c.id}`)}>
          <div className="banner" aria-hidden="true" />
          <PersonPhoto c={c} size={88} />
          <Link className="name" to={`/connections/${c.id}`} onClick={(e) => e.stopPropagation()}>{c.fullName}</Link>
          <p className="role">{c.currentRole || <span className="muted">No role yet</span>}</p>
          <p className="company muted">{[c.currentCompany, c.city].filter(Boolean).join(' · ') || '—'}</p>
          <div className="meta">
            {c.accountId ? (
              <p>
                <Link to={`/accounts/${c.accountId}`} onClick={(e) => e.stopPropagation()}>{c.accountName}</Link>
                {c.accountLink && <span className="muted"> · {ACCOUNT_LINK_LABELS[c.accountLink]}</span>}
              </p>
            ) : <p className="muted">Not linked to an account</p>}
            <KnownBy c={c} />
            <p className="muted small">Connected {formatDate(c.firstConnectedOn)}</p>
          </div>
          {c.linkedinUrl && (
            <a className="profile-link small" href={c.linkedinUrl} target="_blank" rel="noreferrer" onClick={(e) => e.stopPropagation()}>
              Profile ↗
            </a>
          )}
        </li>
      ))}
    </ul>
  );
}

export function ConnectionTable({ items, loading, empty, hideAccount }: { items: Connection[]; loading?: boolean; empty: string; hideAccount?: boolean }) {
  const navigate = useNavigate();
  return (
    <div className="table-wrap">
      <table>
        <thead><tr><th>Person</th><th>Company</th>{!hideAccount && <th>Target account</th>}<th>Known by</th><th>Connected</th></tr></thead>
        <tbody>
          {loading && <tr><td colSpan={5} className="muted">Loading…</td></tr>}
          {!loading && items.length === 0 && <tr><td colSpan={5} className="muted">{empty}</td></tr>}
          {items.map((c) => (
            <tr key={c.id} className="clickable" onClick={() => navigate(`/connections/${c.id}`)}>
              <td><span className="with-photo"><PersonPhoto c={c} size={32} /><span>
                <Link to={`/connections/${c.id}`} onClick={(e) => e.stopPropagation()}>{c.fullName}</Link>
                {c.currentRole && <span className="sub">{c.currentRole}</span>}</span></span></td>
              <td>{c.currentCompany ?? '—'}{hideAccount && c.accountLink && <span className="sub">{ACCOUNT_LINK_LABELS[c.accountLink]}</span>}</td>
              {!hideAccount && (
                <td>{c.accountId
                  ? <><Link to={`/accounts/${c.accountId}`} onClick={(e) => e.stopPropagation()}>{c.accountName}</Link>
                      <span className="sub">{ACCOUNT_LINK_LABELS[c.accountLink ?? ''] ?? ''}</span></>
                  : <span className="muted">—</span>}</td>
              )}
              <td><KnownBy c={c} /></td>
              <td>{formatDate(c.firstConnectedOn)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

const blankPerson = { fullName: '', currentCompany: '', currentRole: '', city: '', linkedinUrl: '', email: '', notes: '' };

export function PersonFields({ form, setForm }: { form: typeof blankPerson; setForm: (f: typeof blankPerson) => void }) {
  const field = (key: keyof typeof blankPerson, label: string, extra: object = {}) => (
    <label>{label}
      <input id={`conn-${key}`} value={form[key]} onChange={(e) => setForm({ ...form, [key]: e.target.value })} {...extra} />
    </label>
  );
  return (
    <div className="grid">
      {field('fullName', 'Name', { maxLength: 200 })}
      {field('currentCompany', 'Company', { maxLength: 200 })}
      {field('currentRole', 'Role', { maxLength: 200, placeholder: 'e.g. Engineering site lead' })}
      {field('city', 'City', { maxLength: 80 })}
      {field('linkedinUrl', 'LinkedIn profile', { maxLength: 400, placeholder: 'https://www.linkedin.com/in/…' })}
      {field('email', 'Email', { maxLength: 254, type: 'email' })}
    </div>
  );
}

export function LinkFields({ account, setAccount }: {
  account: { id: number | null; name: string | null; link: string }; setAccount: (a: { id: number | null; name: string | null; link: string }) => void;
}) {
  return (
    <div className="grid">
      <label>Target account (optional)
        <AccountPicker id="conn-account" value={account.id} valueName={account.name}
          onChange={(id, name) => setAccount({ ...account, id, name })} />
      </label>
      <label>How they connect to it
        <select id="conn-link" value={account.link} disabled={account.id === null} onChange={(e) => setAccount({ ...account, link: e.target.value })}>
          {Object.entries(ACCOUNT_LINK_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
        </select>
      </label>
    </div>
  );
}

export function KnowingFields({ knowing, setKnowing }: {
  knowing: { strength: string; howKnown: string; connectedOn: string }; setKnowing: (k: { strength: string; howKnown: string; connectedOn: string }) => void;
}) {
  return (
    <div className="grid">
      <label>How well you know them
        <select id="conn-strength-me" value={knowing.strength} onChange={(e) => setKnowing({ ...knowing, strength: e.target.value })}>
          {Object.entries(STRENGTH_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
        </select>
      </label>
      <label className="wide">How you know them
        <input id="conn-how" value={knowing.howKnown} maxLength={400} placeholder="e.g. My manager at a previous company"
          onChange={(e) => setKnowing({ ...knowing, howKnown: e.target.value })} />
      </label>
      <label>Connected on
        <input id="conn-on" type="date" value={knowing.connectedOn} max={today()} onChange={(e) => setKnowing({ ...knowing, connectedOn: e.target.value })} />
      </label>
    </div>
  );
}

export function AddConnection({ eventId, onDone }: { eventId?: number; onDone: () => void }) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const [form, setForm] = useState(blankPerson);
  const [account, setAccount] = useState<{ id: number | null; name: string | null; link: string }>({ id: null, name: null, link: 'WORKS_THERE' });
  const [knowing, setKnowing] = useState({ strength: 'KNOW_WELL', howKnown: '', connectedOn: today() });
  const save = useMutation({
    mutationFn: () => api.post<Connection>('/connections', {
      person: { ...form, accountId: account.id, accountLink: account.id ? account.link : null, metAtEventId: eventId ?? null },
      knowing: { ...knowing, connectedOn: knowing.connectedOn || null },
    }),
    onSuccess: (c) => {
      queryClient.invalidateQueries({ queryKey: ['connections'] });
      queryClient.invalidateQueries({ queryKey: ['event'] });
      onDone();
      if (!eventId) navigate(`/connections/${c.id}`);
    },
  });
  const problems = save.error instanceof ApiError ? save.error.problems : [];
  return (
    <form className="form conversation-form" aria-label="Add connection" onSubmit={(e) => { e.preventDefault(); save.mutate(); }}>
      <h2>Add a connection</h2>
      <p className="muted small">Type it in yourself; the app reads nothing from LinkedIn.</p>
      <PersonFields form={form} setForm={setForm} />
      <KnowingFields knowing={knowing} setKnowing={setKnowing} />
      <LinkFields account={account} setAccount={setAccount} />
      <label className="wide">Notes
        <textarea id="conn-notes" rows={2} value={form.notes} onChange={(e) => setForm({ ...form, notes: e.target.value })} />
      </label>
      {save.error && (
        <div className="notice bad" role="alert">
          {problems.length > 0 ? <ul className="problems">{problems.map((p) => <li key={p}>{p}</li>)}</ul> : save.error.message}
        </div>
      )}
      <div className="actions">
        <button type="submit" disabled={save.isPending}>{save.isPending ? 'Saving…' : 'Add connection'}</button>
        <button type="button" className="secondary" onClick={onDone}>Cancel</button>
      </div>
    </form>
  );
}
