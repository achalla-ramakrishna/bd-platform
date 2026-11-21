import { useEffect, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  GTN_STAGES, GTN_STAGE_LABELS, WHY_LABELS, api,
  type GtnDashboard, type GtnTeamRow, type ProfilePage, type ProfileStats,
} from '../lib/api';
import { formatDate } from '../lib/format';
import { ErrorNote, useGoogleStatus } from './gtn';
import { today } from './ConversationsPage';

/** My GTN profile page (ADR-0019): the page outreach links open. Video first, brief, one clear ask. */
export function ProfileTab({ editable }: { editable: boolean }) {
  const queryClient = useQueryClient();
  const page = useQuery({ queryKey: ['gtn-profile'], queryFn: () => api.get<ProfilePage>('/network/profile') });
  const [form, setForm] = useState({ headline: '', intro: '', proof1: '', proof2: '', proof3: '', bookingUrl: '', whatsapp: '', interestedEnabled: true,
    bookingMode: 'LINK', slotMinutes: '20', dayStart: '10:00', dayEnd: '18:00' });
  const [saved, setSaved] = useState(false);
  const videoInput = useRef<HTMLInputElement>(null);
  const captionsInput = useRef<HTMLInputElement>(null);
  useEffect(() => {
    const p = page.data;
    if (!p) return;
    setForm({
      headline: p.headline, intro: p.intro ?? '', proof1: p.proofPoints[0] ?? '', proof2: p.proofPoints[1] ?? '', proof3: p.proofPoints[2] ?? '',
      bookingUrl: p.bookingUrl ?? '', whatsapp: p.whatsapp ? `+${p.whatsapp}` : '', interestedEnabled: p.interestedEnabled,
      bookingMode: p.bookingMode, slotMinutes: String(p.slotMinutes), dayStart: p.dayStart, dayEnd: p.dayEnd,
    });
  }, [page.data]);
  const done = (p: ProfilePage) => queryClient.setQueryData(['gtn-profile'], p);
  const save = useMutation({
    mutationFn: () => api.put<ProfilePage>('/network/profile', {
      headline: form.headline, intro: form.intro, proofPoints: [form.proof1, form.proof2, form.proof3], bookingUrl: form.bookingUrl,
      whatsapp: form.whatsapp, interestedEnabled: form.interestedEnabled, version: page.data?.version, bookingMode: form.bookingMode,
      slotMinutes: Number(form.slotMinutes), dayStart: form.dayStart, dayEnd: form.dayEnd,
    }),
    onSuccess: (p) => { done(p); setSaved(true); },
  });
  const video = useMutation({ mutationFn: (f: File) => api.putRaw<ProfilePage>('/network/profile/video', f), onSuccess: done });
  const removeVideo = useMutation({ mutationFn: () => api.del<ProfilePage>('/network/profile/video'), onSuccess: done });
  const captions = useMutation({
    mutationFn: async (f: File) => api.putRaw<ProfilePage>('/network/profile/captions', new Blob([await f.text()], { type: 'text/vtt' })),
    onSuccess: done,
  });
  const set = (k: keyof typeof form) => (e: { target: { value: string } }) => { setSaved(false); setForm({ ...form, [k]: e.target.value }); };
  if (page.isPending) return <p className="muted">Loading…</p>;
  const p = page.data;
  return (
    <>
      <GooglePanel editable={editable} />
      <p className="muted small">
        The page your outreach links open: put <code>{'{profile_link}'}</code> in a message and each person gets their own link. A visit, the
        video watched and every click show on their timeline; a visit from someone who hasn't replied puts "ask now" at the top of Today and
        emails you. LinkedIn doesn't say who viewed your profile, so this page does.
      </p>
      <div className="toolbar">
        <a className="button secondary" href="/api/v1/network/profile/preview" target="_blank" rel="noreferrer">Preview my page</a>
        {p && p.id === 0 && <span className="muted small">Not saved yet: people see the company default until you save.</span>}
      </div>
      <section className="form" aria-label="Intro video">
        <h2>Intro video</h2>
        <p className="muted small">Video first: a 60–90 second hello on camera, recorded on your phone, MP4 or WebM, up to 40 MB. Add captions
          (a .vtt file) so it works on mute.</p>
        {p?.videoBytes
          ? <video className="profile-video" controls preload="metadata" src={`/api/v1/network/profile/video?v=${p.version}`} aria-label="My intro video" />
          : <p className="muted">No video yet.</p>}
        {editable && (
          <div className="toolbar">
            <input ref={videoInput} type="file" accept="video/mp4,video/webm" hidden aria-label="Video file"
              onChange={(e) => { const f = e.target.files?.[0]; if (f) video.mutate(f); e.target.value = ''; }} />
            <button type="button" disabled={video.isPending} onClick={() => videoInput.current?.click()}>
              {video.isPending ? 'Uploading…' : p?.videoBytes ? 'Replace video' : 'Upload video'}</button>
            {p?.videoBytes && (
              <>
                <input ref={captionsInput} type="file" accept=".vtt,text/vtt" hidden aria-label="Captions file"
                  onChange={(e) => { const f = e.target.files?.[0]; if (f) captions.mutate(f); e.target.value = ''; }} />
                <button type="button" className="secondary" onClick={() => captionsInput.current?.click()}>
                  {p.hasCaptions ? 'Replace captions' : 'Add captions (.vtt)'}</button>
                <button type="button" className="link-plain" disabled={removeVideo.isPending}
                  onClick={() => window.confirm('Remove the video (and its captions)?') && removeVideo.mutate()}>Remove video</button>
                <span className="muted small">{Math.round((p.videoBytes / 1024 / 1024) * 10) / 10} MB{p.hasCaptions ? ' · captioned' : ''}</span>
              </>
            )}
          </div>
        )}
        <ErrorNote error={video.error ?? captions.error ?? removeVideo.error} />
      </section>
      <form className="form" aria-label="Profile page" onSubmit={(e) => { e.preventDefault(); save.mutate(); }}>
        <h2>Words and actions</h2>
        <label className="wide">Headline
          <input aria-label="Headline" value={form.headline} maxLength={200} onChange={set('headline')} disabled={!editable} />
        </label>
        <label className="wide">Intro
          <textarea aria-label="Intro" rows={3} maxLength={1500} value={form.intro} onChange={set('intro')} disabled={!editable}
            placeholder="Who we are and what we do, in two or three sentences." />
        </label>
        <div className="grid">
          <label>Proof point 1<input aria-label="Proof point 1" value={form.proof1} maxLength={300} onChange={set('proof1')} disabled={!editable} /></label>
          <label>Proof point 2<input aria-label="Proof point 2" value={form.proof2} maxLength={300} onChange={set('proof2')} disabled={!editable} /></label>
          <label>Proof point 3<input aria-label="Proof point 3" value={form.proof3} maxLength={300} onChange={set('proof3')} disabled={!editable} /></label>
        </div>
        <div className="grid">
          <label>Booking link
            <input aria-label="Booking link" value={form.bookingUrl} maxLength={400} onChange={set('bookingUrl')} disabled={!editable}
              placeholder="https://calendar.app.google/… (your booking page)" />
          </label>
          <label>Your WhatsApp
            <input aria-label="Your WhatsApp" value={form.whatsapp} maxLength={20} onChange={set('whatsapp')} disabled={!editable} placeholder="+91 98xxx xxxxx" />
          </label>
        </div>
        <div className="grid">
          <label>People book a call
            <select aria-label="People book a call" value={form.bookingMode} disabled={!editable} onChange={set('bookingMode')}>
              <option value="LINK">With my booking link</option>
              <option value="CALENDAR">From my free times (Google Calendar)</option>
            </select>
          </label>
          {form.bookingMode === 'CALENDAR' && <>
            <label>Call length
              <select aria-label="Call length" value={form.slotMinutes} disabled={!editable} onChange={set('slotMinutes')}>
                {[15, 20, 30, 45, 60].map((m) => <option key={m} value={m}>{m} minutes</option>)}
              </select>
            </label>
            <label>From<input type="time" aria-label="Bookable from" value={form.dayStart} disabled={!editable} onChange={set('dayStart')} /></label>
            <label>Until<input type="time" aria-label="Bookable until" value={form.dayEnd} disabled={!editable} onChange={set('dayEnd')} /></label>
          </>}
        </div>
        {form.bookingMode === 'CALENDAR' && <p className="muted small">Weekdays, the next 10 working days, at least two hours ahead. The call goes on your
          calendar with a Google Meet link and they get an invitation.</p>}
        <label className="inline-check"><input type="checkbox" checked={form.interestedEnabled} disabled={!editable}
          onChange={(e) => { setSaved(false); setForm({ ...form, interestedEnabled: e.target.checked }); }} /> Show "Interested, tell me more"</label>
        <ErrorNote error={save.error} />
        {editable && <div className="actions"><button type="submit" disabled={save.isPending}>Save page</button>
          {saved && <span className="muted small" role="status">Saved.</span>}</div>}
      </form>
    </>
  );
}

/** My Google connection: send from Gmail, find replies, book on my calendar (ADR-0020). */
function GooglePanel({ editable }: { editable: boolean }) {
  const queryClient = useQueryClient();
  const status = useGoogleStatus();
  const [outcome] = useState(() => new URLSearchParams(window.location.search).get('google'));
  const disconnect = useMutation({
    mutationFn: () => api.del<void>('/google'),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['google-status'] }),
  });
  const check = useMutation({
    mutationFn: () => api.post<{ found: number }>('/network/replies/check', {}),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['gtn'] }),
  });
  const s = status.data;
  if (!s) return null;
  return (
    <section className="form" aria-label="Gmail and Calendar">
      <h2>Gmail and Calendar</h2>
      {outcome && (outcome === 'connected'
        ? <div className="notice good" role="status">Google connected.</div>
        : <div className="notice bad" role="alert">{outcome}</div>)}
      {!s.available ? (
        <p className="muted small">Not set up for this app yet: an admin adds GOOGLE_TOKEN_KEY and the redirect address (see the deployment
          guide). Until then, use "Open in Gmail" and your booking link.</p>
      ) : s.connected ? (
        <>
          <p>Connected as <strong>{s.email}</strong> since {formatDate(s.connectedAt)}. GTN sends your emails from Gmail, spots replies in those
            threads (headers and preview only) and books calls on your calendar.</p>
          <div className="toolbar">
            <button type="button" className="secondary" disabled={check.isPending} onClick={() => check.mutate()}>
              {check.isPending ? 'Checking…' : 'Check for replies now'}</button>
            {check.data && <span className="muted small" role="status">{check.data.found ? `${check.data.found} new repl${check.data.found === 1 ? 'y' : 'ies'}: see Today.` : 'No new replies.'}</span>}
            {editable && <button type="button" className="link-plain" disabled={disconnect.isPending}
              onClick={() => window.confirm('Disconnect Google? GTN stops sending from Gmail, finding replies and booking on your calendar.') && disconnect.mutate()}>
              Disconnect</button>}
          </div>
          <ErrorNote error={check.error ?? disconnect.error} />
        </>
      ) : (
        <>
          {s.problem && <div className="notice warn">{s.problem}</div>}
          <p className="muted small">Connect your own Google account to send GTN emails from your Gmail, have replies found for you, and let people
            book your free times. GTN asks Google to send email, read the headers of the threads it started, and see free/busy and add events.</p>
          {editable && <a className="button" href="/api/v1/google/connect">Connect Google</a>}
        </>
      )}
    </section>
  );
}

/** The person's link on their track page, and what their page reported. */
export function ProfileCard({ trackId, editable }: { trackId: number; editable: boolean }) {
  const queryClient = useQueryClient();
  const stats = useQuery({ queryKey: ['gtn', 'profile-stats', trackId], queryFn: () => api.get<ProfileStats>(`/network/tracks/${trackId}/profile`) });
  const [copied, setCopied] = useState(false);
  const make = useMutation({
    mutationFn: () => api.post<ProfileStats>(`/network/tracks/${trackId}/profile`, {}),
    onSuccess: (s) => queryClient.setQueryData(['gtn', 'profile-stats', trackId], s),
  });
  const s = stats.data;
  if (!s) return null;
  const copy = async (url: string) => {
    try {
      await navigator.clipboard.writeText(url);
      setCopied(true);
    } catch {
      setCopied(false);
    }
  };
  return (
    <div className="card wide-card">
      <div className="label">Profile page</div>
      <div className="value value-text">
        {s.url ? <>
          <a className="profile-link-url" href={s.url} target="_blank" rel="noreferrer">{s.url.replace(/^https?:\/\//, '')}</a>{' '}
          <button type="button" className="link-plain" onClick={() => void copy(s.url!)}>{copied ? 'Copied' : 'Copy link'}</button>
          <div className="sub">
            {s.visits ? `Opened ${s.visits} time${s.visits === 1 ? '' : 's'}, last ${formatDate(s.lastVisitAt)}` : 'Not opened yet'}
            {s.bestVideoPct > 0 && ` · watched ${s.bestVideoPct}% of the video`}
            {s.ctaClicks > 0 && ` · ${s.ctaClicks} click${s.ctaClicks === 1 ? '' : 's'}`}
            {s.interestedAt && ' · clicked Interested'}
          </div>
        </> : <>
          <span className="muted">No link yet. </span>
          {editable && <button type="button" className="link-plain" disabled={make.isPending} onClick={() => make.mutate()}>Make their link</button>}
        </>}
      </div>
    </div>
  );
}

const pct = (n: number, of: number) => (of ? `${Math.round((n * 100) / of)}%` : '—');

/** Per owner (ADR-0021). */
export function TeamTable({ rows }: { rows: GtnTeamRow[] }) {
  if (rows.length === 0) return <p className="muted">Nobody is working GTN contacts yet.</p>;
  return (
    <div className="table-scroll">
      <table className="table">
        <thead><tr><th>Owner</th><th className="num">On GTN</th><th className="num">Due</th><th className="num">Overdue</th>
          <th className="num">Started</th><th className="num">Responded</th><th className="num">Warm</th><th className="num">Commitments</th>
          <th className="num">Beeps on time</th></tr></thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.userId}>
              <td>{r.name}{!r.active && <span className="muted small"> (inactive)</span>}</td>
              <td className="num">{r.onGtn}</td><td className="num">{r.due}</td>
              <td className={`num${r.overdue ? ' bad-text' : ''}`}>{r.overdue}</td>
              <td className="num">{r.started}</td><td className="num">{r.responded} <span className="muted small">{pct(r.responded, r.started)}</span></td>
              <td className="num">{r.warm}</td><td className="num">{r.commitments}</td>
              <td className="num">{r.beepsSent ? `${r.beepsOnTime} of ${r.beepsSent}` : '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** GTN dashboard (M10): funnel, conversion, Why? outcomes, what slips, and the profile page. */
export function DashboardTab() {
  const [f, setF] = useState({ from: today(-29), to: today(), mine: false, source: '' });
  const sources = useQuery({ queryKey: ['connection-sources'], queryFn: () => api.get<string[]>('/connections/sources') });
  const params = new URLSearchParams({ from: f.from, to: f.to });
  if (f.mine) params.set('mine', 'true');
  if (f.source) params.set('source', f.source);
  const d = useQuery({ queryKey: ['gtn', 'dashboard', params.toString()], queryFn: () => api.get<GtnDashboard>(`/network/dashboard?${params}`) });
  const data = d.data;
  const c = data?.cohort;
  const steps = c ? [
    ['Started', c.started, c.started], ['Responded', c.responded, c.started], ['Intro made', c.intro, c.started],
    ['Call', c.call, c.started], ['Warm commitment', c.warm, c.started],
  ] as [string, number, number][] : [];
  const maxStage = data ? Math.max(1, ...GTN_STAGES.map((s) => data.byStage[s])) : 1;
  return (
    <>
      <div className="toolbar">
        <label>From <input type="date" aria-label="From" value={f.from} max={f.to} onChange={(e) => setF({ ...f, from: e.target.value })} /></label>
        <label>To <input type="date" aria-label="To" value={f.to} min={f.from} onChange={(e) => setF({ ...f, to: e.target.value })} /></label>
        <select aria-label="Source" value={f.source} onChange={(e) => setF({ ...f, source: e.target.value })}>
          <option value="">Any source</option>
          {sources.data?.map((s) => <option key={s} value={s}>{s}</option>)}
        </select>
        <label className="inline-check"><input type="checkbox" checked={f.mine} onChange={(e) => setF({ ...f, mine: e.target.checked })} /> Mine only</label>
      </div>
      <ErrorNote error={d.error} />
      {data && c && (
        <>
          <div className="cards">
            <div className="card"><div className="label">Response rate</div><div className="value">{pct(c.responded, c.started)}</div>
              <div className="muted small">{c.responded} of {c.started} started</div></div>
            <div className="card"><div className="label">Reached a call</div><div className="value">{pct(c.call, c.responded)}</div>
              <div className="muted small">of those who responded</div></div>
            <div className="card"><div className="label">New warm commitments</div><div className="value">{data.commitments}</div></div>
            <div className="card"><div className="label">Re-engaged after a nudge</div><div className="value">{data.nudgedThenReplied}</div>
              <div className="muted small">{data.nudges} nudge{data.nudges === 1 ? '' : 's'} sent</div></div>
            <div className={`card${data.overdue ? ' bad' : ''}`}><div className="label">Overdue actions</div><div className="value">{data.overdue}</div></div>
            <div className="card"><div className="label">Beeps on time</div><div className="value">{pct(data.beepsOnTime, data.beepsSent)}</div>
              <div className="muted small">within 3 days of their date</div></div>
            <div className={`card${data.missedBeeps ? ' bad' : ''}`}><div className="label">Missed beeps</div><div className="value">{data.missedBeeps}</div>
              <div className="muted small">{data.beepsSent} beep{data.beepsSent === 1 ? '' : 's'} sent</div></div>
          </div>

          <div className="dash-grid">
            <section className="dash-panel" aria-label="People started in the period">
              <h2>People started {formatDate(data.from)} – {formatDate(data.to)}</h2>
              <table className="table">
                <tbody>
                  {steps.map(([label, n, of]) => (
                    <tr key={label}><th scope="row">{label}</th><td className="num">{n}</td><td className="num muted">{label === 'Started' ? '' : pct(n, of)}</td>
                      <td className="bar-cell"><span className="bar" style={{ width: of ? `${(n * 100) / of}%` : 0 }} /></td></tr>
                  ))}
                </tbody>
              </table>
            </section>
            <section className="dash-panel" aria-label="Where everyone is now">
              <h2>Where everyone is now ({data.total})</h2>
              <table className="table">
                <tbody>
                  {GTN_STAGES.map((s) => (
                    <tr key={s}><th scope="row">{GTN_STAGE_LABELS[s]}</th><td className="num">{data.byStage[s]}</td>
                      <td className="bar-cell"><span className="bar" style={{ width: `${(data.byStage[s] * 100) / maxStage}%` }} /></td></tr>
                  ))}
                </tbody>
              </table>
            </section>
            <section className="dash-panel" aria-label="Why people went quiet">
              <h2>Why people went quiet, and what happened</h2>
              {data.reasons.length === 0 ? <p className="muted">Nobody diagnosed yet.</p> : (
                <table className="table">
                  <thead><tr><th>Reason</th><th className="num">People</th><th className="num">Nudges</th><th className="num">Replied</th><th className="num">Parked</th><th className="num">Still quiet</th></tr></thead>
                  <tbody>
                    {data.reasons.map((r) => (
                      <tr key={r.reason}><td>{WHY_LABELS[r.reason]}</td><td className="num">{r.total}</td><td className="num">{r.nudges}</td><td className="num">{r.replied}</td>
                        <td className="num">{r.parked}</td><td className="num">{r.stillQuiet}</td></tr>
                    ))}
                  </tbody>
                </table>
              )}
            </section>
            <section className="dash-panel" aria-label="Profile page">
              <h2>Profile page</h2>
              <table className="table">
                <tbody>
                  <tr><th scope="row">New links</th><td className="num">{data.profile.links}</td></tr>
                  <tr><th scope="row">People who opened it</th><td className="num">{data.profile.visitors}</td></tr>
                  <tr><th scope="row">Watched 75%+ of the video</th><td className="num">{data.profile.watched75}</td></tr>
                  <tr><th scope="row">Clicked book or WhatsApp</th><td className="num">{data.profile.ctaClicks}</td></tr>
                  <tr><th scope="row">Clicked Interested</th><td className="num">{data.profile.interested}</td></tr>
                </tbody>
              </table>
            </section>
            {data.team.length > 0 && (
              <section className="dash-panel wide-panel" aria-label="By owner">
                <h2>By owner</h2>
                <TeamTable rows={data.team} />
              </section>
            )}
            {data.sources.length > 0 && (
              <section className="dash-panel" aria-label="By source">
                <h2>By source</h2>
                <table className="table">
                  <thead><tr><th>Source</th><th className="num">Started</th><th className="num">Responded</th><th className="num">Warm</th></tr></thead>
                  <tbody>
                    {data.sources.map((s) => (
                      <tr key={s.source || '-'}><td>{s.source || 'No source'}</td><td className="num">{s.started}</td>
                        <td className="num">{s.responded} <span className="muted small">{pct(s.responded, s.started)}</span></td>
                        <td className="num">{s.warm}</td></tr>
                    ))}
                  </tbody>
                </table>
              </section>
            )}
          </div>
        </>
      )}
    </>
  );
}
