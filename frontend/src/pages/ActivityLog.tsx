import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { CHANNEL_LABELS, OUTCOME_LABELS, ApiError, api, type Activity, type Contact } from '../lib/api';
import { formatDate } from '../lib/format';

function today() {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

/**
 * Activity log for one account (FR-ENG-01). LinkedIn steps are done on LinkedIn by people and logged
 * here; the app never sends anything to LinkedIn (BR-BD-03).
 */
export function ActivityLog({ accountId, contacts, editable }: { accountId: number; contacts: Contact[]; editable: boolean }) {
  const queryClient = useQueryClient();
  const activities = useQuery({
    queryKey: ['activities', accountId],
    queryFn: () => api.get<Activity[]>(`/activities?accountId=${accountId}`),
  });
  const blank = { contactId: '', channel: 'LINKEDIN_CONNECTION', occurredOn: today(), summary: '', outcome: 'NO_REPLY' };
  const [form, setForm] = useState(blank);
  const log = useMutation({
    mutationFn: () => api.post<Activity>('/activities', {
      accountId, contactId: form.contactId ? Number(form.contactId) : null, channel: form.channel,
      occurredOn: form.occurredOn, summary: form.summary, outcome: form.outcome,
    }),
    onSuccess: () => {
      setForm(blank);
      queryClient.invalidateQueries({ queryKey: ['activities', accountId] });
    },
  });
  const problems = log.error instanceof ApiError ? log.error.problems : [];

  return (
    <>
      {editable && (
        <form className="form activity-form" aria-label="Log an activity" onSubmit={(e) => { e.preventDefault(); log.mutate(); }}>
          <div className="grid">
            <label>Who
              <select id="act-contact" value={form.contactId} onChange={(e) => setForm({ ...form, contactId: e.target.value })}>
                <option value="">The account in general</option>
                {contacts.map((c) => <option key={c.id} value={c.id}>{c.fullName}</option>)}
              </select>
            </label>
            <label>Channel
              <select id="act-channel" value={form.channel} onChange={(e) => setForm({ ...form, channel: e.target.value })}>
                {Object.entries(CHANNEL_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
              </select>
            </label>
            <label>Date
              <input id="act-date" type="date" value={form.occurredOn} max={today()} onChange={(e) => setForm({ ...form, occurredOn: e.target.value })} />
            </label>
            <label>Outcome
              <select id="act-outcome" value={form.outcome} onChange={(e) => setForm({ ...form, outcome: e.target.value })}>
                {Object.entries(OUTCOME_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
              </select>
            </label>
          </div>
          <label className="wide">What happened
            <textarea id="act-summary" rows={2} maxLength={2000} value={form.summary} onChange={(e) => setForm({ ...form, summary: e.target.value })}
              placeholder="For example: Sent a connection request mentioning our work for a fintech GCC" />
          </label>
          {log.error && (
            <div className="notice bad" role="alert">
              {problems.length > 0 ? <ul className="problems">{problems.map((p) => <li key={p}>{p}</li>)}</ul> : log.error.message}
            </div>
          )}
          <div className="actions"><button type="submit" disabled={log.isPending}>{log.isPending ? 'Logging…' : 'Log activity'}</button></div>
        </form>
      )}
      <div className="table-wrap">
        <table>
          <thead><tr><th>Date</th><th>Channel</th><th>Who</th><th>What happened</th><th>Outcome</th><th>By</th></tr></thead>
          <tbody>
            {activities.data?.length === 0 && <tr><td colSpan={6} className="muted">No activity logged yet.</td></tr>}
            {activities.data?.map((a) => (
              <tr key={a.id}>
                <td>{formatDate(a.occurredOn)}</td>
                <td>{CHANNEL_LABELS[a.channel] ?? a.channel}</td>
                <td>{a.contactName ?? <span className="muted">Account</span>}</td>
                <td>{a.summary}</td>
                <td>{OUTCOME_LABELS[a.outcome] ?? a.outcome}</td>
                <td>{a.userName}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}
