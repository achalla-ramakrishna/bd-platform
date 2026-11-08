import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, api, canEditPipeline, type GccSearch, type Me } from '../lib/api';
import { formatDate } from '../lib/format';

const CITIES = ['Bengaluru', 'Hyderabad', 'Pune', 'Chennai', 'Delhi-NCR', 'Mumbai', 'Kochi', 'Coimbatore', 'Ahmedabad', 'Thiruvananthapuram'];
const INDUSTRIES = ['Banking and financial services', 'Insurance', 'Retail', 'Consumer goods', 'Automotive', 'Aerospace',
  'Semiconductors', 'Software', 'Healthcare and life sciences', 'Pharmaceuticals', 'Energy', 'Industrial technology',
  'Telecom', 'Logistics', 'Travel and hospitality', 'Media and entertainment'];

/**
 * Find GCCs (ADR-0011): Claude searches the web for centres in a city and industry that are not yet
 * accounts. People review each company and its source, then add the ones they want as prospects.
 */
export function FindGccsPage({ me }: { me: Me }) {
  const queryClient = useQueryClient();
  const status = useQuery({ queryKey: ['assist-status'], queryFn: () => api.get<{ findGccs: boolean }>('/assist/status') });
  const history = useQuery({ queryKey: ['discovery'], queryFn: () => api.get<GccSearch[]>('/discovery') });
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [city, setCity] = useState('Bengaluru');
  const [industry, setIndustry] = useState('');

  const currentId = selectedId ?? history.data?.[0]?.id ?? null;
  const current = useQuery({
    queryKey: ['discovery', currentId],
    queryFn: () => api.get<GccSearch>(`/discovery/${currentId}`),
    enabled: currentId !== null,
    refetchInterval: (q) => (q.state.data?.status === 'RUNNING' ? 4000 : false),
  });
  useEffect(() => {
    if (current.data?.status !== 'RUNNING') queryClient.invalidateQueries({ queryKey: ['discovery'], exact: true });
  }, [current.data?.status, queryClient]);

  const start = useMutation({
    mutationFn: () => api.post<GccSearch>('/discovery', { city, industry }),
    onSuccess: (s) => {
      setSelectedId(s.id);
      queryClient.setQueryData(['discovery', s.id], s);
      queryClient.invalidateQueries({ queryKey: ['discovery'], exact: true });
    },
  });
  const enabled = status.data?.findGccs ?? false;
  const editable = canEditPipeline(me);

  return (
    <>
      <h1>Find GCCs</h1>
      <p className="lead">
        Claude searches the web for global capability centres in a city and industry that are not in our accounts yet,
        with a source for each. Pick the ones worth pursuing and add them as prospects. Each search takes a minute or
        two and costs a few dozen rupees.
      </p>

      {!enabled && status.isSuccess && (
        <div className="notice warn">Find GCCs is switched off. An Admin can turn it on by setting ANTHROPIC_API_KEY in Railway.</div>
      )}

      {editable && enabled && (
        <form className="form find-form" onSubmit={(e) => { e.preventDefault(); start.mutate(); }} aria-label="Search for GCCs">
          <div className="grid">
            <label>City
              <input id="find-city" list="cities" required value={city} onChange={(e) => setCity(e.target.value)} />
              <datalist id="cities">{CITIES.map((c) => <option key={c} value={c} />)}</datalist>
            </label>
            <label>Industry (optional)
              <input id="find-industry" list="industries" value={industry} onChange={(e) => setIndustry(e.target.value)} placeholder="Any industry" />
              <datalist id="industries">{INDUSTRIES.map((c) => <option key={c} value={c} />)}</datalist>
            </label>
          </div>
          <div className="actions">
            <button type="submit" disabled={start.isPending || current.data?.status === 'RUNNING'}>
              {start.isPending ? 'Starting…' : 'Find GCCs'}
            </button>
            <span className="muted small">Searching city by city and industry by industry covers the market over time.</span>
          </div>
          {start.error && <div className="notice bad" role="alert">{start.error instanceof ApiError && start.error.problems.length ? start.error.problems.join(' ') : start.error.message}</div>}
        </form>
      )}

      {current.data && <SearchResult search={current.data} editable={editable} />}

      {(history.data?.length ?? 0) > 0 && (
        <>
          <h2>Past searches</h2>
          <div className="table-wrap">
            <table>
              <thead><tr><th>When</th><th>City</th><th>Industry</th><th>Result</th><th>By</th></tr></thead>
              <tbody>
                {history.data!.map((h) => (
                  <tr key={h.id} className={h.id === currentId ? 'selected' : ''}>
                    <td><button type="button" className="link-plain" onClick={() => setSelectedId(h.id)}>{formatDate(h.requestedAt)}</button></td>
                    <td>{h.city}</td>
                    <td>{h.industry ?? <span className="muted">Any</span>}</td>
                    <td>
                      {h.status === 'DONE'
                        ? <>{h.candidates.length} found, {h.candidates.filter((c) => !c.alreadyInApp).length} new</>
                        : h.status === 'RUNNING' ? <span className="pill warn">Searching</span> : <span className="pill bad">Failed</span>}
                    </td>
                    <td>{h.requestedBy}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </>
  );
}

function SearchResult({ search, editable }: { search: GccSearch; editable: boolean }) {
  const queryClient = useQueryClient();
  const addable = search.candidates.filter((c) => !c.alreadyInApp && c.validDomain);
  const [chosen, setChosen] = useState<Set<string>>(new Set());
  const add = useMutation({
    mutationFn: () => api.post<{ created: number; skipped: number; problems: string[] }>(`/discovery/${search.id}/add`, { domains: [...chosen] }),
    onSuccess: () => {
      setChosen(new Set());
      queryClient.invalidateQueries({ queryKey: ['discovery'] });
      queryClient.invalidateQueries({ queryKey: ['accounts'] });
    },
  });
  const toggle = (d: string) => setChosen((s) => { const n = new Set(s); if (n.has(d)) n.delete(d); else n.add(d); return n; });

  if (search.status === 'RUNNING') {
    return <div className="notice warn" role="status">Searching for GCCs in {search.city}{search.industry ? ` (${search.industry})` : ''}. This usually takes one to two minutes.</div>;
  }
  if (search.status === 'FAILED') {
    return <div className="notice bad" role="alert">{search.error ?? 'The search failed.'}</div>;
  }
  return (
    <section aria-label="Search results">
      <div className="title-row">
        <h2>{search.candidates.length} GCCs in {search.city}{search.industry ? `, ${search.industry}` : ''}</h2>
        {editable && addable.length > 0 && (
          <div className="actions" style={{ marginTop: 22 }}>
            <button type="button" className="secondary" onClick={() => setChosen(chosen.size === addable.length ? new Set() : new Set(addable.map((c) => c.domain)))}>
              {chosen.size === addable.length ? 'Clear selection' : `Select all ${addable.length} new`}
            </button>
            <button type="button" disabled={chosen.size === 0 || add.isPending} onClick={() => add.mutate()}>
              {add.isPending ? 'Adding…' : `Add ${chosen.size || ''} as prospects`}
            </button>
          </div>
        )}
      </div>
      {add.data && (
        <div className={`notice ${add.data.problems.length ? 'warn' : 'good'}`} role="status">
          Added {add.data.created} as prospects owned by you{add.data.skipped ? `; ${add.data.skipped} were already accounts` : ''}.
          {add.data.problems.map((p) => <div key={p}>{p}</div>)}
        </div>
      )}
      {search.candidates.length === 0 && <p className="muted">No new GCCs found. Try another industry or city.</p>}
      {search.candidates.length > 0 && (
        <div className="table-wrap">
          <table>
            <thead><tr><th aria-label="Add"></th><th>Company</th><th>What the centre does</th><th>Industry</th><th>Source</th></tr></thead>
            <tbody>
              {search.candidates.map((v) => {
                const c = v.candidate;
                return (
                  <tr key={`${v.domain}-${c.company}`}>
                    <td>
                      {v.alreadyInApp ? <span className="pill good">In accounts</span>
                        : !v.validDomain ? <span className="pill bad" title="No valid website domain">Check</span>
                          : editable && <input type="checkbox" aria-label={`Add ${c.company}`} checked={chosen.has(v.domain)} onChange={() => toggle(v.domain)} />}
                    </td>
                    <td>{c.company}<span className="sub">{c.parentCompany}{c.hqCountry ? ` (${c.hqCountry})` : ''} · {v.domain || c.websiteDomain}</span></td>
                    <td>{c.description}</td>
                    <td>{c.industry}</td>
                    <td><a href={c.source} target="_blank" rel="noreferrer">{(() => { try { return new URL(c.source).hostname.replace(/^www\./, ''); } catch { return 'source'; } })()}</a></td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      <p className="muted small">Found from public web sources. Open the source before adding; anything can be wrong or out of date.</p>
    </section>
  );
}
