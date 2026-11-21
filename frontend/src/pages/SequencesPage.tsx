import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router';
import { api, canEditPipeline, canManageNetwork, type Me } from '../lib/api';
import { formatDate } from '../lib/format';
import { ErrorNote } from './gtn';

/** Email sequences (ADR-0025): the agent writes, the owner approves once, the app sends within guardrails. */
export interface SequenceStep { position?: number; waitDays: number; subject: string; body: string }

export interface SequenceTemplate { id?: number; name: string; description?: string | null; active: boolean; steps: SequenceStep[]; version?: number }

export interface SequenceEmail { position: number; waitDays: number; subject: string; body: string; edited: boolean;
  status: 'PLANNED' | 'SENT' | 'SKIPPED'; sentAt?: string | null }

export type EnrolmentStatus = 'DRAFTING' | 'REVIEW' | 'FAILED' | 'ACTIVE' | 'PAUSED' | 'REPLIED' | 'STOPPED' | 'FINISHED';

export interface Enrolment {
  id: number; sequenceId: number; sequenceName: string; contactId: number; contactName: string; contactTitle?: string | null;
  accountId: number; accountName: string; ownerName: string; conversationId?: number | null; toEmail: string; status: EnrolmentStatus;
  nextPosition: number; nextSendOn?: string | null; waitingReason?: string | null; endedReason?: string | null; error?: string | null;
  sent: number; steps: number; createdAt: string; version: number; emails: SequenceEmail[];
}

export interface SequenceOverview { sending: boolean; writerEnabled: boolean; window: string; perSenderPerDay: number; mine: Enrolment[];
  templates: SequenceTemplate[] }

export const ENROLMENT_LABELS: Record<EnrolmentStatus, string> = {
  DRAFTING: 'Agent writing', REVIEW: 'Waiting for your review', FAILED: 'Write by hand', ACTIVE: 'Scheduled', PAUSED: 'Paused',
  REPLIED: 'They replied', STOPPED: 'Stopped', FINISHED: 'Finished',
};

const TONES: Partial<Record<EnrolmentStatus, string>> = { REVIEW: 'warn', FAILED: 'warn', ACTIVE: 'accent', REPLIED: 'good', STOPPED: 'bad' };

export function EnrolmentPill({ status }: { status: EnrolmentStatus }) {
  return <span className={`pill ${TONES[status] ?? ''}`}>{ENROLMENT_LABELS[status]}</span>;
}

const OPEN: EnrolmentStatus[] = ['DRAFTING', 'REVIEW', 'FAILED', 'ACTIVE', 'PAUSED'];

function useOverview(enabled = true) {
  return useQuery({
    queryKey: ['sequences'],
    queryFn: () => api.get<SequenceOverview>('/sequences'),
    enabled,
    refetchInterval: (q) => (q.state.data?.mine.some((e) => e.status === 'DRAFTING') ? 3000 : false),
  });
}

export function SequencesPage({ me }: { me: Me }) {
  const overview = useOverview(canEditPipeline(me));
  if (!canEditPipeline(me)) return <p className="muted">Email sequences are for people who work the pipeline.</p>;
  if (overview.isPending) return <p className="muted">Loading…</p>;
  if (overview.error) return <div className="notice bad">{overview.error.message}</div>;
  const o = overview.data;
  const open = o.mine.filter((e) => OPEN.includes(e.status));
  const ended = o.mine.filter((e) => !OPEN.includes(e.status));
  return (
    <>
      <h1>Email sequences</h1>
      <p className="lead">Start a sequence on a contact's conversation or from Prospecting. The agent writes each email for that person;
        you read and edit them, then approve once. The app sends them from your Gmail ({o.window}, at most {o.perSenderPerDay} a day),
        each with an unsubscribe link, and stops as soon as they reply, opt out or unsubscribe.</p>
      {o.sending
        ? <div className="notice good">Sending is on.</div>
        : <div className="notice warn">Sending is off until an Admin turns it on after the legal review (ADR-0025). You can write and approve
          sequences now; nothing is emailed until then.</div>}
      <h2>Mine</h2>
      {open.length === 0 && <p className="muted">No sequences running.</p>}
      <div className="queue">{open.map((e) => <EnrolmentCard key={e.id} e={e} />)}</div>
      {ended.length > 0 && (
        <>
          <h2>Ended in the last 30 days</h2>
          <div className="table-wrap">
            <table>
              <thead><tr><th>Person</th><th>Sequence</th><th>Sent</th><th>Ended</th></tr></thead>
              <tbody>{ended.map((e) => (
                <tr key={e.id}>
                  <td>{e.conversationId ? <Link to={`/conversations/${e.conversationId}`}>{e.contactName}</Link> : e.contactName}
                    <span className="sub">{e.accountName}</span></td>
                  <td>{e.sequenceName}</td>
                  <td>{e.sent} of {e.steps}</td>
                  <td><EnrolmentPill status={e.status} />{e.endedReason && <span className="sub">{e.endedReason}</span>}</td>
                </tr>
              ))}</tbody>
            </table>
          </div>
        </>
      )}
      <Templates templates={o.templates} editable={canManageNetwork(me)} />
    </>
  );
}

export function EnrolmentCard({ e }: { e: Enrolment }) {
  const queryClient = useQueryClient();
  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ['sequences'] });
  };
  const act = useMutation({
    mutationFn: (what: 'approve' | 'pause' | 'resume' | 'stop') =>
      api.post<Enrolment>(`/sequences/${e.id}/${what}${what === 'approve' ? `?version=${e.version}` : ''}`, {}),
    onSuccess: refresh,
  });
  const editable = ['REVIEW', 'FAILED', 'PAUSED'].includes(e.status);
  return (
    <section className="queue-card" aria-label={`Sequence for ${e.contactName}`}>
      <h3>{e.conversationId ? <Link to={`/conversations/${e.conversationId}`}>{e.contactName}</Link> : e.contactName}{' '}
        <EnrolmentPill status={e.status} /></h3>
      <div className="muted small">{[e.contactTitle, e.accountName].filter(Boolean).join(' · ')} · {e.toEmail} · {e.sequenceName} ·
        {' '}{e.sent} of {e.steps} sent{e.status === 'ACTIVE' && e.nextSendOn ? ` · next on ${formatDate(e.nextSendOn)}` : ''}</div>
      {e.status === 'DRAFTING' && <p className="muted" role="status">The agent is writing the emails…</p>}
      {e.error && <div className="notice warn">{e.error}</div>}
      {e.waitingReason && <p className="small">Waiting: {e.waitingReason}</p>}
      {e.emails.map((m) => <EmailEditor key={m.position} enrolment={e} email={m} editable={editable && m.status === 'PLANNED'} />)}
      <div className="actions">
        {(e.status === 'REVIEW' || e.status === 'FAILED') && (
          <button type="button" disabled={act.isPending} onClick={() => act.mutate('approve')}>Approve and schedule</button>
        )}
        {e.status === 'ACTIVE' && <button type="button" className="secondary" disabled={act.isPending} onClick={() => act.mutate('pause')}>Pause</button>}
        {e.status === 'PAUSED' && <button type="button" disabled={act.isPending} onClick={() => act.mutate('resume')}>Resume</button>}
        <button type="button" className="secondary" disabled={act.isPending} onClick={() => act.mutate('stop')}>Stop</button>
      </div>
      <ErrorNote error={act.error} />
    </section>
  );
}

function EmailEditor({ enrolment, email, editable }: { enrolment: Enrolment; email: SequenceEmail; editable: boolean }) {
  const queryClient = useQueryClient();
  const [subject, setSubject] = useState(email.subject);
  const [body, setBody] = useState(email.body);
  const dirty = subject !== email.subject || body !== email.body;
  const save = useMutation({
    mutationFn: () => api.put<Enrolment>(`/sequences/${enrolment.id}/emails/${email.position}`, { subject, body }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['sequences'] }),
  });
  const when = email.position === 1 ? 'First email' : `${email.waitDays} days later`;
  return (
    <details className="sequence-email" open={editable && email.position === 1}>
      <summary>
        <strong>Email {email.position}</strong> <span className="muted small">{when} · {email.status === 'SENT' && email.sentAt
          ? `sent ${formatDate(email.sentAt)}` : email.status.toLowerCase()}{email.edited ? ' · edited' : ''}</span> · {email.subject}
      </summary>
      {editable ? (
        <>
          <label className="small" htmlFor={`s-${enrolment.id}-${email.position}`}>Subject</label>
          <input id={`s-${enrolment.id}-${email.position}`} value={subject} onChange={(ev) => setSubject(ev.target.value)} />
          <label className="small" htmlFor={`b-${enrolment.id}-${email.position}`}>Email</label>
          <textarea id={`b-${enrolment.id}-${email.position}`} rows={7} value={body} onChange={(ev) => setBody(ev.target.value)} />
          <p className="muted small">An unsubscribe line is added at the end of every email.</p>
          {dirty && <button type="button" className="secondary" disabled={save.isPending} onClick={() => save.mutate()}>Save email {email.position}</button>}
          <ErrorNote error={save.error} />
        </>
      ) : <p className="draft-note" style={{ whiteSpace: 'pre-wrap' }}>{email.body}</p>}
    </details>
  );
}

function Templates({ templates, editable }: { templates: SequenceTemplate[]; editable: boolean }) {
  const [editing, setEditing] = useState<SequenceTemplate | null>(null);
  return (
    <>
      <div className="title-row">
        <h2>Sequences the team uses</h2>
        {editable && !editing && (
          <button type="button" className="secondary" style={{ marginTop: 22 }}
            onClick={() => setEditing({ name: '', active: true, steps: [{ waitDays: 0, subject: '', body: '' }] })}>New sequence</button>
        )}
      </div>
      {editing && <TemplateEditor t={editing} onDone={() => setEditing(null)} />}
      <div className="table-wrap">
        <table>
          <thead><tr><th>Sequence</th><th>Emails</th><th /></tr></thead>
          <tbody>{templates.map((t) => (
            <tr key={t.id}>
              <td>{t.name}{!t.active && <span className="pill">off</span>}<span className="sub">{t.description}</span></td>
              <td>{t.steps.map((s, i) => <div key={i} className="small">{i === 0 ? 'Day 0' : `+${s.waitDays} days`}: {s.subject}</div>)}</td>
              <td>{editable && <button type="button" className="link-plain" onClick={() => setEditing(t)}>Edit</button>}</td>
            </tr>
          ))}</tbody>
        </table>
      </div>
    </>
  );
}

function TemplateEditor({ t, onDone }: { t: SequenceTemplate; onDone: () => void }) {
  const queryClient = useQueryClient();
  const [form, setForm] = useState<SequenceTemplate>(t);
  const save = useMutation({
    mutationFn: () => (form.id ? api.put<SequenceTemplate>(`/sequences/templates/${form.id}`, form) : api.post<SequenceTemplate>('/sequences/templates', form)),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['sequences'] });
      onDone();
    },
  });
  const step = (i: number, patch: Partial<SequenceStep>) => setForm({ ...form, steps: form.steps.map((s, j) => (j === i ? { ...s, ...patch } : s)) });
  return (
    <section className="queue-card" aria-label="Edit sequence">
      <label className="small" htmlFor="seq-name">Name</label>
      <input id="seq-name" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
      <label className="small" htmlFor="seq-desc">Description</label>
      <input id="seq-desc" value={form.description ?? ''} onChange={(e) => setForm({ ...form, description: e.target.value })} />
      <label className="small"><input type="checkbox" checked={form.active} onChange={(e) => setForm({ ...form, active: e.target.checked })} /> In use</label>
      <p className="muted small">Each email: a subject ({'{company}'} and {'{firstName}'} are filled in) and what the email should do. The agent writes
        the real email for each person from this.</p>
      {form.steps.map((s, i) => (
        <div key={i} className="sequence-email">
          <strong>Email {i + 1}</strong>
          {i > 0 && (
            <label className="small"> wait <input type="number" min={3} max={60} style={{ width: 70 }} value={s.waitDays}
              onChange={(e) => step(i, { waitDays: Number(e.target.value) })} /> days</label>
          )}
          <input aria-label={`Email ${i + 1} subject`} placeholder="Subject" value={s.subject} onChange={(e) => step(i, { subject: e.target.value })} />
          <textarea aria-label={`Email ${i + 1} instructions`} rows={3} placeholder="What this email should do" value={s.body}
            onChange={(e) => step(i, { body: e.target.value })} />
          {i > 0 && <button type="button" className="link-plain" onClick={() => setForm({ ...form, steps: form.steps.filter((_, j) => j !== i) })}>Remove</button>}
        </div>
      ))}
      <div className="actions">
        {form.steps.length < 4 && (
          <button type="button" className="secondary" onClick={() => setForm({ ...form, steps: [...form.steps, { waitDays: 4, subject: '', body: '' }] })}>
            Add an email</button>
        )}
        <button type="button" disabled={save.isPending} onClick={() => save.mutate()}>Save sequence</button>
        <button type="button" className="secondary" onClick={onDone}>Cancel</button>
      </div>
      <ErrorNote error={save.error} />
    </section>
  );
}

/** "Email sequence" for one contact: shows the one running, or starts one. */
export function StartSequence({ contactId }: { contactId: number }) {
  const queryClient = useQueryClient();
  const overview = useOverview();
  const existing = useQuery({
    queryKey: ['sequences', 'contact', contactId],
    queryFn: () => api.get<Enrolment[]>(`/sequences/contact/${contactId}`),
    refetchInterval: (q) => (q.state.data?.some((e) => e.status === 'DRAFTING') ? 3000 : false),
  });
  const [sequenceId, setSequenceId] = useState('');
  const start = useMutation({
    mutationFn: () => api.post<Enrolment>('/sequences', { contactId, sequenceId: Number(sequenceId) }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['sequences'] }),
  });
  const running = existing.data?.find((e) => OPEN.includes(e.status));
  const templates = overview.data?.templates.filter((t) => t.active) ?? [];
  if (running) {
    return (
      <p className="small">Email sequence: <EnrolmentPill status={running.status} /> {running.sent} of {running.steps} sent ·{' '}
        <Link to="/sequences">{running.status === 'REVIEW' || running.status === 'FAILED' ? 'Review and approve' : 'Open'}</Link></p>
    );
  }
  return (
    <div className="actions">
      <select aria-label="Email sequence" value={sequenceId} onChange={(e) => setSequenceId(e.target.value)}>
        <option value="">Email sequence…</option>
        {templates.map((t) => <option key={t.id} value={t.id}>{t.name} ({t.steps.length} emails)</option>)}
      </select>
      <button type="button" className="secondary" disabled={!sequenceId || start.isPending} onClick={() => start.mutate()}>Start</button>
      {start.data && <span className="small" role="status">The agent is writing it. <Link to="/sequences">Review it</Link>.</span>}
      <ErrorNote error={start.error} />
    </div>
  );
}
