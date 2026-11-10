import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api, ApiError, accountSize, type Account } from '../lib/api';
import { formatDate } from '../lib/format';

const BANDS = ['<500', '500-2000', '2000-5000', '5000+'];

function num(v: string): number | null {
  const t = v.replace(/,/g, '').trim();
  return t === '' ? null : Number(t);
}

/** Year formed, size and open roles for an account, editable by the pipeline team (backlog item 1). */
export function AccountProfile({ account: a, editable }: { account: Account; editable: boolean }) {
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState(false);
  const initial = {
    yearEstablished: a.yearEstablished?.toString() ?? '',
    headcountBand: a.headcountBand ?? '',
    employeeCount: a.employeeCount?.toString() ?? '',
    openRoles: a.openRoles?.toString() ?? '',
    openRolesAsOf: a.openRolesAsOf ?? '',
    openRolesSource: a.openRolesSource ?? '',
  };
  const [form, setForm] = useState(initial);
  const save = useMutation({
    mutationFn: () => api.put(`/accounts/${a.id}/profile`, {
      yearEstablished: num(form.yearEstablished),
      // An exact count sets the band, so only send a band chosen without a count.
      headcountBand: form.employeeCount.trim() ? null : form.headcountBand || null,
      employeeCount: num(form.employeeCount),
      openRoles: num(form.openRoles),
      openRolesAsOf: form.openRolesAsOf || null,
      openRolesSource: form.openRolesSource.trim() || null,
    }),
    onSuccess: () => {
      setEditing(false);
      queryClient.invalidateQueries({ queryKey: ['account', String(a.id)] });
      queryClient.invalidateQueries({ queryKey: ['accounts'] });
    },
  });
  const problems = save.error instanceof ApiError ? save.error.problems : [];
  const source = a.openRolesSource;
  const sourceIsLink = !!source && /^https?:\/\//.test(source);

  return (
    <section className="account-profile" aria-label="Centre profile">
      <div className="cards">
        <div className="card"><div className="label">Year formed</div><div className="value">{a.yearEstablished ?? '—'}</div></div>
        <div className="card"><div className="label">Employees</div><div className="value">{accountSize(a)}</div></div>
        <div className="card">
          <div className="label">Open roles</div>
          <div className="value">{a.openRoles ?? '—'}</div>
          {(a.openRolesAsOf || source) && (
            <div className="muted small">
              {a.openRolesAsOf && `as of ${formatDate(a.openRolesAsOf)}`}
              {source && (sourceIsLink ? <> · <a href={source} target="_blank" rel="noreferrer">source</a></> : ` · ${source}`)}
            </div>
          )}
        </div>
      </div>
      {editable && !editing && (
        <p><button type="button" className="secondary" onClick={() => { setForm(initial); setEditing(true); }}>Edit profile</button></p>
      )}
      {editing && (
        <form className="form" aria-label="Edit centre profile" onSubmit={(e) => { e.preventDefault(); save.mutate(); }}>
          <div className="grid">
            <label>Year formed
              <input id="profile-year" inputMode="numeric" value={form.yearEstablished} placeholder="e.g. 2025"
                onChange={(e) => setForm({ ...form, yearEstablished: e.target.value })} />
            </label>
            <label>Employees (exact)
              <input id="profile-employees" inputMode="numeric" value={form.employeeCount} placeholder="e.g. 150"
                onChange={(e) => setForm({ ...form, employeeCount: e.target.value })} />
            </label>
            <label>Or size band
              <select id="profile-band" value={form.headcountBand} disabled={!!form.employeeCount.trim()}
                onChange={(e) => setForm({ ...form, headcountBand: e.target.value })}>
                <option value="">Not known</option>
                {BANDS.map((b) => <option key={b} value={b}>{b}</option>)}
              </select>
            </label>
            <label>Open roles
              <input id="profile-roles" inputMode="numeric" value={form.openRoles} placeholder="engineering roles"
                onChange={(e) => setForm({ ...form, openRoles: e.target.value })} />
            </label>
            <label>Counted on
              <input id="profile-asof" type="date" value={form.openRolesAsOf}
                onChange={(e) => setForm({ ...form, openRolesAsOf: e.target.value })} />
            </label>
            <label>Where counted
              <input id="profile-source" value={form.openRolesSource} maxLength={500} placeholder="careers page or job-board search link"
                onChange={(e) => setForm({ ...form, openRolesSource: e.target.value })} />
            </label>
          </div>
          {save.error && (
            <div className="notice bad" role="alert">
              {problems.length > 0 ? <ul className="problems">{problems.map((p) => <li key={p}>{p}</li>)}</ul> : save.error.message}
            </div>
          )}
          <div className="actions">
            <button type="submit" disabled={save.isPending}>{save.isPending ? 'Saving…' : 'Save profile'}</button>
            <button type="button" className="secondary" onClick={() => setEditing(false)}>Cancel</button>
          </div>
        </form>
      )}
    </section>
  );
}
