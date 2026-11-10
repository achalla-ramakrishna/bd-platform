import { useEffect, useState } from 'react';
import { useLocation } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, api, type AccountResearch as Research, type ResearchFact } from '../lib/api';
import { formatDate } from '../lib/format';

function SourceLink({ source }: { source: string }) {
  if (/^https?:\/\//.test(source)) {
    let host = source;
    try { host = new URL(source).hostname.replace(/^www\./, ''); } catch { /* keep the raw text */ }
    return <a href={source} target="_blank" rel="noreferrer" className="source">{host}</a>;
  }
  return <span className="source muted">{source}</span>;
}

function Facts({ items }: { items: ResearchFact[] }) {
  if (items.length === 0) return <p className="muted">Nothing found.</p>;
  return <ul>{items.map((f, i) => <li key={i}>{f.statement} <SourceLink source={f.source} /></li>)}</ul>;
}

/**
 * AI research brief for one account (ADR-0010): what the centre does, buying signals with sources,
 * which CodeWalnut offers fit, who to contact and a first LinkedIn note for a person to edit and send.
 */
export function AccountResearch({ accountId, editable }: { accountId: number; editable: boolean }) {
  const queryClient = useQueryClient();
  const status = useQuery({ queryKey: ['assist-status'], queryFn: () => api.get<{ accountResearch: boolean }>('/assist/status') });
  const research = useQuery({
    queryKey: ['research', accountId],
    queryFn: () => api.get<Research | undefined>(`/accounts/${accountId}/research`),
    refetchInterval: (q) => (q.state.data?.status === 'RUNNING' ? 4000 : false),
  });
  const start = useMutation({
    mutationFn: () => api.post<Research>(`/accounts/${accountId}/research`, {}),
    onSuccess: (r) => queryClient.setQueryData(['research', accountId], r),
  });
  const [copied, setCopied] = useState(false);

  const r = research.data;
  const location = useLocation();
  // Opened from "Research ready" on the Accounts page: scroll to this panel once it has loaded.
  useEffect(() => {
    if (location.hash === '#research' && research.isSuccess) {
      document.getElementById('research')?.scrollIntoView({ block: 'start' });
    }
  }, [location.hash, research.isSuccess]);
  const enabled = status.data?.accountResearch ?? false;
  const running = r?.status === 'RUNNING' || start.isPending;
  const s = r?.summary;

  const button = editable && enabled && (
    <button type="button" className={r ? 'secondary' : ''} disabled={running} onClick={() => start.mutate()}>
      {running ? 'Researching…' : r ? 'Refresh research' : 'Research this account'}
    </button>
  );

  return (
    <section className="research" id="research" aria-label="Account research">
      <div className="title-row">
        <div>
          <h2>Research and how we can help</h2>
          {r?.status === 'DONE' && r.finishedAt && (
            <p className="muted small">Researched {formatDate(r.finishedAt)} by {r.requestedBy}, from public web sources and our records. Check before relying on it.</p>
          )}
        </div>
        <div className="actions">{button}</div>
      </div>

      {!r && !running && (
        <p className="muted">
          {enabled
            ? 'Claude searches the web for this centre (news, hiring, technology) and suggests where CodeWalnut fits, who to contact and a first message. Takes a minute or two.'
            : 'No research yet. Research written outside the app can be added with Upload research on the Accounts page, or an Admin can switch on in-app research by setting ANTHROPIC_API_KEY in Railway.'}
        </p>
      )}
      {running && <div className="notice warn" role="status">Researching the web and our records. This usually takes one to two minutes; you can leave this page and come back.</div>}
      {start.error && <div className="notice bad" role="alert">{start.error instanceof ApiError && start.error.problems.length ? start.error.problems.join(' ') : start.error.message}</div>}
      {r?.status === 'FAILED' && !running && <div className="notice bad" role="alert">{r.error ?? 'Research failed.'}</div>}
      {r?.status === 'DONE' && r.laterFailure && !running && (
        <div className="notice warn small" role="status">A later refresh failed, so this is the last good research. {r.laterFailure}</div>
      )}

      {s && r?.status === 'DONE' && (
        <div className="research-body">
          {s.fitRating && (
            <p className="small">
              <span className={`pill ${s.fitRating === 'STRONG' ? 'good' : s.fitRating === 'MEDIUM' ? 'warn' : ''}`}>
                Research fit: {s.fitRating.charAt(0) + s.fitRating.slice(1).toLowerCase()}
              </span>{' '}
              <span className="muted">Counts towards the fit score.</span>
            </p>
          )}
          <p className="overview">{s.centreOverview}</p>

          <div className="research-grid">
            <div>
              <h3>Buying signals</h3>
              <Facts items={s.buyingSignals} />
            </div>
            <div>
              <h3>Where we can help</h3>
              <ul>{s.recommendedOffers.map((o, i) => <li key={i}><strong>{o.offer}.</strong> {o.why}</li>)}</ul>
              {s.likelyNeeds.length > 0 && <p className="muted small">Likely needs: {s.likelyNeeds.join('; ')}</p>}
            </div>
            <div>
              <h3>Who to contact</h3>
              <ol>
                {s.whoToContact.map((t, i) => (
                  <li key={i}>
                    <strong>{t.persona}</strong>{t.knownContact ? <> ({t.knownContact})</> : <span className="muted"> (not in our contacts yet)</span>}. {t.why}
                  </li>
                ))}
              </ol>
            </div>
            <div>
              <h3>First LinkedIn note</h3>
              <blockquote className="message">{s.openingMessage}</blockquote>
              <div className="actions">
                <button type="button" className="secondary" onClick={() => {
                  navigator.clipboard.writeText(s.openingMessage).then(() => setCopied(true)).catch(() => setCopied(false));
                }}>{copied ? 'Copied' : 'Copy note'}</button>
                <span className="muted small">{s.openingMessage.length} / 300 characters. Edit before sending.</span>
              </div>
            </div>
          </div>

          <h3>Talking points for a first call</h3>
          <ul>{s.talkingPoints.map((t, i) => <li key={i}>{t}</li>)}</ul>

          <h3>About the centre</h3>
          <Facts items={s.facts} />

          {s.cautions.length > 0 && (
            <>
              <h3>Check before relying on it</h3>
              <ul className="muted">{s.cautions.map((c, i) => <li key={i}>{c}</li>)}</ul>
            </>
          )}

          {r.sources.length > 0 && (
            <details>
              <summary>{r.sources.length} web {r.sources.length === 1 ? 'source' : 'sources'} found</summary>
              <ul className="small">{r.sources.map((src) => <li key={src.url}><a href={src.url} target="_blank" rel="noreferrer">{src.title || src.url}</a></li>)}</ul>
            </details>
          )}
        </div>
      )}
    </section>
  );
}
