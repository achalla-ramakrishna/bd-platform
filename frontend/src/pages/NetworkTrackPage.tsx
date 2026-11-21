import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useParams } from 'react-router';
import {
  COMMITMENT_LABELS, GTN_CHANNEL_LABELS, GTN_STAGE_LABELS, WHY_HINTS, WHY_LABELS, api, canEditPipeline, canWorkTrack,
  type GtnTrackPage, type Me,
} from '../lib/api';
import { formatDate } from '../lib/format';
import { PersonPhoto } from './PersonPhoto';
import { ErrorNote, HandOverForm, SendStep, StageActions, StagePill } from './gtn';
import { ProfileCard } from './GtnProfileAndDashboard';

/** One person on GTN: where they are, what to do next with the message ready, and everything that happened. */
export function NetworkTrackPage({ me }: { me: Me }) {
  const { id } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [handing, setHanding] = useState(false);
  const page = useQuery({ queryKey: ['gtn', 'track', Number(id)], queryFn: () => api.get<GtnTrackPage>(`/network/tracks/${id}`) });
  const [editing, setEditing] = useState(false);
  const [next, setNext] = useState({ nextAction: '', nextActionOn: '', beepIntervalDays: 30 });
  const set = (p: GtnTrackPage) => queryClient.setQueryData(['gtn', 'track', Number(id)], p);
  const change = useMutation({
    mutationFn: () => api.patch<GtnTrackPage>(`/network/tracks/${id}`, { ...next, version: page.data!.detail.track.version }),
    onSuccess: (p) => {
      set(p);
      setEditing(false);
      queryClient.invalidateQueries({ queryKey: ['gtn'] });
    },
  });
  const stop = useMutation({
    mutationFn: () => api.del<void>(`/network/tracks/${id}`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['gtn'] });
      navigate('/network');
    },
  });

  if (page.isPending) return <p className="muted">Loading…</p>;
  if (page.error) return <div className="notice bad">{page.error.message}</div>;
  const { track: t, touches, changes, commitments } = page.data.detail;
  // The owner, or a manager, works the contact; everyone else reads (ADR-0021).
  const editable = canWorkTrack(me, t.ownerId);
  const n = page.data.next;
  const timeline = [
    ...touches.map((x) => ({ key: `t${x.id}`, at: x.occurredOn, sort: x.createdAt, text: x.summary,
      head: x.kind === 'VISIT' ? '← Profile page'
        : `${x.direction === 'IN' ? '← ' : '→ '}${GTN_CHANNEL_LABELS[x.channel]} · ${x.kind.toLowerCase()} · ${x.userName}` })),
    ...changes.map((c) => ({ key: `c${c.id}`, at: c.changedAt.slice(0, 10), sort: c.changedAt, text: c.note ?? '',
      head: `${c.fromStage && c.fromStage !== c.toStage ? `${GTN_STAGE_LABELS[c.fromStage]} → ` : ''}${GTN_STAGE_LABELS[c.toStage]} · ${c.changedBy}` })),
  ].sort((a, b) => b.sort.localeCompare(a.sort));

  return (
    <>
      <p><Link to="/network">← GTN</Link></p>
      <div className="person-head">
        <PersonPhoto c={{ id: t.connectionId, fullName: t.fullName, photoVersion: t.photoVersion }} size={64} />
        <div>
          <h1>{t.fullName} <StagePill stage={t.stage} /></h1>
          <p className="lead">{[t.role, t.company].filter(Boolean).join(' · ') || 'No role or company yet'}
            {' · '}<Link to={`/connections/${t.connectionId}`}>Connection</Link>
            {t.linkedinUrl && <> · <a href={t.linkedinUrl} target="_blank" rel="noreferrer">LinkedIn</a></>}
            {t.sourceTag && <> · <span className="pill">{t.sourceTag}</span></>}
            {' · '}owned by {t.ownerName}
            {editable && !handing && <> · <button type="button" className="link-plain" onClick={() => setHanding(true)}>Hand over</button></>}</p>
          {handing && <HandOverForm trackIds={[t.id]} version={t.version} onDone={() => { setHanding(false); void page.refetch(); }} />}
          {!editable && canEditPipeline(me) && <p className="muted small">This is {t.ownerName}'s contact. Ask them, or a manager, to hand it over to you.</p>}
        </div>
      </div>

      <div className="cards">
        <div className={`card wide-card${t.overdue ? ' bad' : ''}`}>
          <div className="label">Next action</div>
          <div className="value value-text">{t.nextAction} <span className="sub">{t.overdue ? 'overdue since ' : 'on '}{formatDate(t.nextActionOn)}</span></div>
        </div>
        <div className="card"><div className="label">In stage</div><div className="value">{t.daysInStage} d</div></div>
        {t.stage === 'UNRESPONSIVE' && <div className="card"><div className="label">Nudges</div><div className="value">{t.nudgesSent} of {page.data.rules.nudgeCap}</div></div>}
        {t.whyReason && <div className="card wide-card"><div className="label">Why?</div><div className="value value-text">{WHY_LABELS[t.whyReason]}
          <div className="muted small">{WHY_HINTS[t.whyReason]}</div></div></div>}
        {t.revisitReason && <div className="card wide-card"><div className="label">They said</div><div className="value value-text">{t.revisitReason}</div></div>}
        {t.callOn && t.stage === 'CALL_SCHEDULED' && <div className="card"><div className="label">Call</div><div className="value value-text">
          {t.callAt ? new Date(t.callAt).toLocaleString(undefined, { weekday: 'short', day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' })
            : formatDate(t.callOn)}</div></div>}
        <ProfileCard trackId={t.id} editable={editable} />
        {(t.stage === 'WARM_COMMITMENT' || t.stage === 'MONTHLY_BEEP') && (
          <div className="card"><div className="label">Beep every</div><div className="value">{t.beepIntervalDays} d</div></div>
        )}
      </div>

      {editable && (
        <section className="form gtn-next" aria-label="Next step">
          <h2>{n.taskLabel}</h2>
          {n.task === 'DIAGNOSE' && <p className="small">No reply in {page.data.rules.unresponsiveDays} days. Pick the reason; it decides the nudge.</p>}
          <SendStep page={page.data} onDone={set} />
          <StageActions page={page.data} onDone={set} />
        </section>
      )}

      {editable && (
        <div className="toolbar">
          {!editing && <button type="button" className="link-plain" onClick={() => {
            setNext({ nextAction: t.nextAction, nextActionOn: t.nextActionOn, beepIntervalDays: t.beepIntervalDays });
            setEditing(true);
          }}>Change the next action or beep interval</button>}
          <button type="button" className="link-plain" disabled={stop.isPending}
            onClick={() => window.confirm(`Stop working ${t.fullName} on GTN? Their timeline here is deleted; they stay in Connections.`) && stop.mutate()}>
            Stop GTN for {t.fullName}</button>
        </div>
      )}
      {editing && (
        <form className="inline-form" aria-label="Change the next action" onSubmit={(e) => { e.preventDefault(); change.mutate(); }}>
          <input aria-label="Next action" value={next.nextAction} maxLength={200} onChange={(e) => setNext({ ...next, nextAction: e.target.value })} />
          <input type="date" aria-label="Next action on" value={next.nextActionOn} onChange={(e) => setNext({ ...next, nextActionOn: e.target.value })} />
          <label>Beep every <input type="number" aria-label="Beep every (days)" min={7} max={180} value={next.beepIntervalDays}
            onChange={(e) => setNext({ ...next, beepIntervalDays: Number(e.target.value) })} /> days</label>
          <button type="submit" disabled={change.isPending}>Save</button>
          <button type="button" className="link-plain" onClick={() => setEditing(false)}>Cancel</button>
        </form>
      )}
      <ErrorNote error={change.error ?? stop.error} />

      {commitments.length > 0 && (
        <>
          <h2>Commitments</h2>
          <ul>{commitments.map((c) => <li key={c.id}><strong>{COMMITMENT_LABELS[c.kind]}</strong>: {c.description}
            <span className="muted small"> · {c.recordedBy}, {formatDate(c.recordedAt)}</span></li>)}</ul>
        </>
      )}

      <h2>Timeline</h2>
      <ol className="timeline">
        {timeline.map((x) => (
          <li key={x.key}>
            <div className="muted small">{formatDate(x.at)} · {x.head}</div>
            {x.text && <div>{x.text}</div>}
          </li>
        ))}
      </ol>
    </>
  );
}
