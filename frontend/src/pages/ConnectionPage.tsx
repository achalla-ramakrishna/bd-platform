import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useParams } from 'react-router';
import { ACCOUNT_LINK_LABELS, ApiError, api, canEditPipeline, type Connection, type Me } from '../lib/api';
import { formatDate } from '../lib/format';
import { useReference } from './PipelinePage';
import { KnowingFields, LinkFields, PersonFields, StrengthPill } from './ConnectionsPage';
import { NewConversation, today } from './ConversationsPage';

/** One connection: who knows them, the account they lead to, and next moves (backlog item 4). */
export function ConnectionPage({ me }: { me: Me }) {
  const { id } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const editable = canEditPipeline(me);
  const conn = useQuery({ queryKey: ['connection', id], queryFn: () => api.get<Connection>(`/connections/${id}`) });
  const [editing, setEditing] = useState(false);
  const [knowingOpen, setKnowingOpen] = useState(false);
  const [talking, setTalking] = useState(false);
  const done = (c: Connection) => {
    queryClient.setQueryData(['connection', id], c);
    queryClient.invalidateQueries({ queryKey: ['connections'] });
  };

  if (conn.isPending) return <p className="muted">Loading…</p>;
  if (conn.error) return <div className="notice bad">{conn.error.message}</div>;
  const c = conn.data;
  const mine = c.knownBy.find((k) => k.userId === me.id);

  return (
    <>
      <p><Link to="/connections">← Connections</Link></p>
      <h1>{c.fullName}</h1>
      <p className="lead">
        {[c.currentRole, c.currentCompany, c.city].filter(Boolean).join(' · ') || 'No role or company yet'}
        {c.linkedinUrl && <> · <a href={c.linkedinUrl} target="_blank" rel="noreferrer">LinkedIn profile</a></>}
        {c.email && <> · {c.email}</>}
      </p>

      <div className="cards">
        <div className="card wide-card">
          <div className="label">Target account</div>
          <div className="value value-text">
            {c.accountId ? <><Link to={`/accounts/${c.accountId}`}>{c.accountName}</Link> <span className="sub">{ACCOUNT_LINK_LABELS[c.accountLink ?? '']}</span></>
              : <span className="muted">Not linked to an account yet</span>}
          </div>
        </div>
        <div className="card"><div className="label">First connected</div><div className="value value-text">{formatDate(c.firstConnectedOn)}</div></div>
        <div className="card"><div className="label">Contact record</div><div className="value value-text">
          {c.contactId ? <Link to={`/accounts/${c.accountId}`}>At the account</Link> : <span className="muted">None</span>}
        </div></div>
      </div>
      {c.notes && <p className="notes">{c.notes}</p>}

      <h2>Who knows them</h2>
      <ul className="knowers">
        {c.knownBy.map((k) => (
          <li key={k.userId}><strong>{k.userName}</strong> <StrengthPill strength={k.strength} />
            {k.howKnown && <> · {k.howKnown}</>} <span className="muted small">· since {formatDate(k.connectedOn)}</span></li>
        ))}
      </ul>

      {editable && (
        <ConnectionActions c={c} mine={!!mine} talking={talking} editing={editing} knowingOpen={knowingOpen} onTalk={() => setTalking(true)}
          onEdit={() => setEditing(true)} onKnowing={() => setKnowingOpen(true)} onChanged={done}
          onForgotten={() => { queryClient.invalidateQueries({ queryKey: ['connections'] }); if (c.knownBy.length <= 1) navigate('/connections'); else conn.refetch(); }} />
      )}
      {talking && c.accountId && (
        <NewConversation accountId={c.accountId} contactId={c.contactId} personName={c.contactId ? undefined : c.fullName}
          onDone={() => setTalking(false)} />
      )}
      {editing && <EditConnection c={c} onDone={(updated) => { if (updated) done(updated); setEditing(false); }} />}
      {knowingOpen && <MyKnowing c={c} me={me} onDone={(updated) => { if (updated) done(updated); setKnowingOpen(false); }} />}
    </>
  );
}

function EditConnection({ c, onDone }: { c: Connection; onDone: (c?: Connection) => void }) {
  const [form, setForm] = useState({
    fullName: c.fullName, currentCompany: c.currentCompany ?? '', currentRole: c.currentRole ?? '', city: c.city ?? '',
    linkedinUrl: c.linkedinUrl ?? '', email: c.email ?? '', notes: c.notes ?? '',
  });
  const [account, setAccount] = useState<{ id: number | null; name: string | null; link: string }>({
    id: c.accountId ?? null, name: c.accountName ?? null, link: c.accountLink ?? 'WORKS_THERE',
  });
  const save = useMutation({
    mutationFn: () => api.put<Connection>(`/connections/${c.id}`, {
      ...form, accountId: account.id, accountLink: account.id ? account.link : null, version: c.version,
    }),
    onSuccess: (updated) => onDone(updated),
  });
  const problems = save.error instanceof ApiError ? save.error.problems : [];
  return (
    <form className="form conversation-form" aria-label="Edit connection" onSubmit={(e) => { e.preventDefault(); save.mutate(); }}>
      <h2>Edit details</h2>
      <PersonFields form={form} setForm={setForm} />
      <LinkFields account={account} setAccount={setAccount} />
      <label className="wide">Notes
        <textarea id="conn-edit-notes" rows={2} value={form.notes} onChange={(e) => setForm({ ...form, notes: e.target.value })} />
      </label>
      {save.error && (
        <div className="notice bad" role="alert">
          {problems.length > 0 ? <ul className="problems">{problems.map((p) => <li key={p}>{p}</li>)}</ul> : save.error.message}
        </div>
      )}
      <div className="actions">
        <button type="submit" disabled={save.isPending}>Save</button>
        <button type="button" className="secondary" onClick={() => onDone()}>Cancel</button>
      </div>
    </form>
  );
}

const errorText = (e: unknown) => (e instanceof ApiError && e.problems.length ? e.problems.join(' ') : (e as Error).message);

function ConnectionActions({ c, mine, talking, editing, knowingOpen, onTalk, onEdit, onKnowing, onChanged, onForgotten }: {
  c: Connection; mine: boolean; talking: boolean; editing: boolean; knowingOpen: boolean; onTalk: () => void; onEdit: () => void;
  onKnowing: () => void; onChanged: (c: Connection) => void; onForgotten: () => void;
}) {
  const reference = useReference();
  const personas = reference.data?.picklists.PERSONA ?? [];
  const [persona, setPersona] = useState('');
  const forget = useMutation({ mutationFn: () => api.del(`/connections/${c.id}/knowers/me`), onSuccess: onForgotten });
  const contact = useMutation({ mutationFn: () => api.post<Connection>(`/connections/${c.id}/contact`, { persona }), onSuccess: onChanged });
  return (
    <>
      <div className="toolbar quick-actions">
        {c.accountId
          ? <button type="button" onClick={onTalk} disabled={talking}>Start conversation</button>
          : <span className="muted small">Link a target account to start a conversation.</span>}
        {c.accountLink === 'WORKS_THERE' && !c.contactId && (
          <>
            <select id="conn-persona" aria-label="Persona for the contact" value={persona} onChange={(e) => setPersona(e.target.value)}>
              <option value="">Persona…</option>
              {personas.map((p) => <option key={p.code} value={p.code}>{p.label}</option>)}
            </select>
            <button type="button" className="secondary" disabled={!persona || contact.isPending} onClick={() => contact.mutate()}>Make a contact</button>
          </>
        )}
        <button type="button" className="secondary" onClick={onEdit} disabled={editing}>Edit details</button>
        <button type="button" className="secondary" onClick={onKnowing} disabled={knowingOpen}>
          {mine ? 'Change how I know them' : 'I know them too'}
        </button>
        {mine && (
          <button type="button" className="secondary" disabled={forget.isPending}
            onClick={() => { if (confirm(c.knownBy.length <= 1 ? 'You are the only one who knows them, so the connection will be removed. Continue?' : 'Remove yourself from the people who know them?')) forget.mutate(); }}>
            I don't know them
          </button>
        )}
      </div>
      {(contact.error || forget.error) && <div className="notice bad" role="alert">{errorText(contact.error ?? forget.error)}</div>}
    </>
  );
}

function MyKnowing({ c, me, onDone }: { c: Connection; me: Me; onDone: (c?: Connection) => void }) {
  const mine = c.knownBy.find((k) => k.userId === me.id);
  const [knowing, setKnowing] = useState<{ strength: string; howKnown: string; connectedOn: string }>({
    strength: mine?.strength ?? 'KNOW_WELL', howKnown: mine?.howKnown ?? '', connectedOn: mine?.connectedOn ?? today(),
  });
  const save = useMutation({ mutationFn: () => api.put<Connection>(`/connections/${c.id}/knowers/me`, knowing), onSuccess: (u) => onDone(u) });
  return (
    <form className="form conversation-form" aria-label="How I know them" onSubmit={(e) => { e.preventDefault(); save.mutate(); }}>
      <h2>How I know them</h2>
      <KnowingFields knowing={knowing} setKnowing={setKnowing} />
      {save.error && <div className="notice bad" role="alert">{errorText(save.error)}</div>}
      <div className="actions">
        <button type="submit" disabled={save.isPending}>Save</button>
        <button type="button" className="secondary" onClick={() => onDone()}>Cancel</button>
      </div>
    </form>
  );
}
