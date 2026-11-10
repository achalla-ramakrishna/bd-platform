import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router';
import {
  CHANNEL_LABELS, CONVERSATION_STATUS_LABELS, OUTCOME_LABELS, ApiError, api, canEditPipeline,
  type ConversationDetail, type Me, type Opportunity,
} from '../lib/api';
import { formatDate } from '../lib/format';
import { NextStep, StatusPill, today } from './ConversationsPage';

/** One conversation: its updates, the next step, and quick changes (backlog item 3). */
export function ConversationPage({ me }: { me: Me }) {
  const { id } = useParams();
  const queryClient = useQueryClient();
  const editable = canEditPipeline(me);
  const detail = useQuery({ queryKey: ['conversation', id], queryFn: () => api.get<ConversationDetail>(`/conversations/${id}`) });
  const accountId = detail.data?.conversation.accountId;
  const opportunities = useQuery({
    queryKey: ['opportunities', 'account', String(accountId)],
    queryFn: () => api.get<Opportunity[]>(`/opportunities?accountId=${accountId}`),
    enabled: editable && accountId !== undefined,
  });
  const [form, setForm] = useState({
    channel: 'LINKEDIN_MESSAGE', occurredOn: today(), summary: '', outcome: 'REPLIED', status: '', nextStep: '', nextStepDue: today(3),
  });
  const refresh = (d: ConversationDetail) => {
    queryClient.setQueryData(['conversation', id], d);
    queryClient.invalidateQueries({ queryKey: ['conversations'] });
    queryClient.invalidateQueries({ queryKey: ['activities'] });
  };
  const update = useMutation({
    mutationFn: () => api.post<ConversationDetail>(`/conversations/${id}/updates`, {
      ...form, status: form.status || null, nextStep: form.nextStep || null,
      nextStepDue: form.nextStep ? form.nextStepDue || null : null, version: detail.data!.conversation.version,
    }),
    onSuccess: (d) => {
      refresh(d);
      setForm({ ...form, summary: '', status: '', nextStep: '', nextStepDue: today(3) });
    },
  });
  const change = useMutation({
    mutationFn: (body: object) => api.patch<ConversationDetail>(`/conversations/${id}`, { ...body, version: detail.data!.conversation.version }),
    onSuccess: refresh,
  });

  if (detail.isPending) return <p className="muted">Loading…</p>;
  if (detail.error) return <div className="notice bad">{detail.error.message}</div>;
  const { conversation: c, updates } = detail.data;
  const problems = (e: unknown) => (e instanceof ApiError && e.problems.length ? e.problems : null);
  const updateProblems = problems(update.error);
  const changeProblems = problems(change.error);
  const keep = { nextStep: c.nextStep ?? null, nextStepDue: c.nextStepDue ?? null };

  return (
    <>
      <p><Link to="/conversations">← Conversations</Link></p>
      <h1>{c.personName} <span className="muted" style={{ fontWeight: 400 }}>at</span> <Link to={`/accounts/${c.accountId}`}>{c.accountName}</Link> <StatusPill status={c.status} /></h1>
      <p className="lead">{c.topic}{c.personTitle ? ` · ${c.personTitle}` : ''} · {c.city} · owned by {c.ownerName} · started {formatDate(c.createdAt)}
        {c.eventId && <> at <Link to={`/events/${c.eventId}`}>{c.eventName}</Link></>}</p>

      <div className="cards">
        <div className="card wide-card"><div className="label">Next step</div><div className="value value-text"><NextStep c={c} /></div></div>
        <div className="card"><div className="label">Last touch</div><div className="value value-text">{formatDate(c.lastTouchOn)}</div></div>
        <div className="card"><div className="label">Opportunity</div><div className="value value-text">
          {c.opportunityId ? <Link to={`/opportunities/${c.opportunityId}`}>{c.opportunityName}</Link> : <span className="muted">None yet</span>}
        </div></div>
      </div>

      {editable && (
        <div className="toolbar quick-actions">
          {c.status !== 'GONE_QUIET' && <button type="button" className="secondary" disabled={change.isPending}
            onClick={() => change.mutate({ status: 'GONE_QUIET', ...keep })}>Mark gone quiet</button>}
          {c.status !== 'CLOSED' && <button type="button" className="secondary" disabled={change.isPending}
            onClick={() => change.mutate({ status: 'CLOSED', nextStep: null, nextStepDue: null })}>Close</button>}
          {(c.status === 'CLOSED' || c.status === 'GONE_QUIET') && <button type="button" className="secondary" disabled={change.isPending}
            onClick={() => change.mutate({ status: 'STARTED', ...keep })}>Reopen</button>}
          {!c.opportunityId && (
            <>
              <select id="conv-link-opp" aria-label="Link an opportunity" value="" disabled={change.isPending}
                onChange={(e) => e.target.value && change.mutate({ opportunityId: Number(e.target.value), ...keep })}>
                <option value="">Link an opportunity…</option>
                {opportunities.data?.map((o) => <option key={o.id} value={o.id}>{o.name}</option>)}
              </select>
              <Link className="button secondary" to={`/opportunities/new?accountId=${c.accountId}`}>New opportunity</Link>
            </>
          )}
        </div>
      )}
      {change.error && <div className="notice bad" role="alert">{changeProblems ? changeProblems.join(' ') : change.error.message}</div>}

      {editable && (
        <form className="form conversation-form" aria-label="Add an update" onSubmit={(e) => { e.preventDefault(); update.mutate(); }}>
          <h2>Add an update</h2>
          <div className="grid">
            <label>Channel
              <select id="upd-channel" value={form.channel} onChange={(e) => setForm({ ...form, channel: e.target.value })}>
                {Object.entries(CHANNEL_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
              </select>
            </label>
            <label>Date
              <input id="upd-date" type="date" value={form.occurredOn} max={today()} onChange={(e) => setForm({ ...form, occurredOn: e.target.value })} />
            </label>
            <label>Outcome
              <select id="upd-outcome" value={form.outcome} onChange={(e) => setForm({ ...form, outcome: e.target.value })}>
                {Object.entries(OUTCOME_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
              </select>
            </label>
            <label>Status
              <select id="upd-status" value={form.status} onChange={(e) => setForm({ ...form, status: e.target.value })}>
                <option value="">From the outcome</option>
                {Object.entries(CONVERSATION_STATUS_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
              </select>
            </label>
          </div>
          <label className="wide">What happened
            <textarea id="upd-summary" rows={2} maxLength={2000} value={form.summary} placeholder="e.g. Replied: back on Friday, let's meet"
              onChange={(e) => setForm({ ...form, summary: e.target.value })} />
          </label>
          <div className="grid">
            <label className="wide">Next step
              <input id="upd-next" value={form.nextStep} maxLength={500} placeholder="Leave empty to clear the next step"
                onChange={(e) => setForm({ ...form, nextStep: e.target.value })} />
            </label>
            <label>By
              <input id="upd-next-due" type="date" value={form.nextStepDue} onChange={(e) => setForm({ ...form, nextStepDue: e.target.value })} />
            </label>
          </div>
          {update.error && (
            <div className="notice bad" role="alert">
              {updateProblems ? <ul className="problems">{updateProblems.map((p) => <li key={p}>{p}</li>)}</ul> : update.error.message}
            </div>
          )}
          <div className="actions"><button type="submit" disabled={update.isPending}>{update.isPending ? 'Saving…' : 'Save update'}</button></div>
        </form>
      )}

      <h2>Updates</h2>
      <ol className="timeline">
        {updates.map((u) => (
          <li key={u.id}>
            <div className="when">{formatDate(u.occurredOn)} · {CHANNEL_LABELS[u.channel] ?? u.channel} · {OUTCOME_LABELS[u.outcome] ?? u.outcome}</div>
            <div>{u.summary}</div>
            <div className="muted small">by {u.userName}</div>
          </li>
        ))}
      </ol>
    </>
  );
}
