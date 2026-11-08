import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiError, api, type Account, type ProfileSuggestion } from '../lib/api';
import { useReference } from './PipelinePage';

interface Form {
  fullName: string;
  title: string;
  persona: string;
  linkedinUrl: string;
  email: string;
  phone: string;
  city: string;
  source: string;
  consentBasis: string;
  notes: string;
}

const blank = (city: string): Form => ({
  fullName: '', title: '', persona: '', linkedinUrl: '', email: '', phone: '', city,
  source: 'LinkedIn', consentBasis: 'BUSINESS_CONTACT', notes: '',
});

function loosely(a: string) {
  return a.toLowerCase().replace(/[^a-z0-9]/g, '');
}

/**
 * Add a contact from a LinkedIn profile the user is looking at. They paste the address and the
 * profile text or a screenshot; Claude suggests the fields; the user checks them and saves. Nothing is
 * read from or sent to LinkedIn by the app (ADR-0005, ADR-0009).
 */
export function AddFromLinkedIn({ account, onDone }: { account: Account; onDone: () => void }) {
  const queryClient = useQueryClient();
  const reference = useReference();
  const status = useQuery({ queryKey: ['assist-status'], queryFn: () => api.get<{ profileReading: boolean }>('/assist/status') });
  const personas = reference.data?.picklists.PERSONA ?? [];
  const [profileText, setProfileText] = useState('');
  const [image, setImage] = useState<File | null>(null);
  const [form, setForm] = useState<Form>(blank(account.city));
  const [hint, setHint] = useState<ProfileSuggestion | null>(null);
  const set = (patch: Partial<Form>) => setForm((f) => ({ ...f, ...patch }));

  const read = useMutation({
    mutationFn: () => {
      const data = new FormData();
      if (form.linkedinUrl) data.append('linkedinUrl', form.linkedinUrl);
      if (profileText.trim()) data.append('text', profileText);
      if (image) data.append('image', image);
      return api.postForm<ProfileSuggestion>('/assist/read-profile', data);
    },
    onSuccess: (s) => {
      setHint(s);
      setForm((f) => ({
        ...f,
        fullName: s.fullName || f.fullName,
        title: s.title || f.title,
        persona: s.persona || f.persona,
        city: s.city || f.city,
        notes: s.notes || f.notes,
      }));
    },
  });
  const save = useMutation({
    mutationFn: () => api.post<{ id: number }>('/contacts', { accountId: account.id, ...form }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['account'] });
      queryClient.invalidateQueries({ queryKey: ['contacts'] });
      onDone();
    },
  });

  const companyMismatch = hint?.company
    && !loosely(account.name).includes(loosely(hint.company).slice(0, 5))
    && !loosely(account.parentCompany).includes(loosely(hint.company).slice(0, 5));
  const problems = save.error instanceof ApiError ? save.error.problems : [];

  return (
    <section className="form linkedin-add" aria-label="Add from LinkedIn">
      <h3>Add a contact from LinkedIn</h3>
      <ol className="muted small steps-inline">
        <li>Open their profile on LinkedIn and copy the address bar.</li>
        <li>{status.data?.profileReading
          ? 'Select and copy the top of the profile (name, headline, location), or take a screenshot, and paste it below.'
          : 'Fill in the fields from the profile.'}</li>
        <li>Check the fields and save.</li>
      </ol>

      <div className="grid">
        <label className="span-2">LinkedIn profile address
          <input id="li-url" value={form.linkedinUrl} onChange={(e) => set({ linkedinUrl: e.target.value })}
            placeholder="https://www.linkedin.com/in/..." />
        </label>
      </div>

      {status.data?.profileReading && (
        <>
          <label className="wide">Profile text or screenshot (paste here)
            <textarea id="li-text" rows={4} value={profileText} onChange={(e) => setProfileText(e.target.value)}
              onPaste={(e) => {
                const file = Array.from(e.clipboardData.files).find((f) => f.type.startsWith('image/'));
                if (file) { e.preventDefault(); setImage(file); }
              }}
              placeholder="Paste the copied profile text, or press Ctrl+V with a screenshot on the clipboard" />
          </label>
          <div className="actions">
            <input id="li-image" type="file" accept="image/png,image/jpeg,image/webp,image/gif" aria-label="Screenshot"
              onChange={(e) => setImage(e.target.files?.[0] ?? null)} />
            {image && <span className="muted small">Screenshot: {image.name || 'pasted image'}</span>}
            <button type="button" className="secondary" disabled={read.isPending || (!profileText.trim() && !image)}
              onClick={() => read.mutate()}>
              {read.isPending ? 'Reading…' : 'Read profile'}
            </button>
          </div>
          {read.error && <div className="notice bad" role="alert">{read.error instanceof ApiError && read.error.problems.length ? read.error.problems.join(' ') : read.error.message}</div>}
          {hint && (
            <div className={`notice ${companyMismatch ? 'warn' : 'good'}`} role="status">
              Filled from the profile. Check before saving.
              {hint.personaReason && <> Persona: {hint.personaReason}</>}
              {companyMismatch && <> The profile says they work at <strong>{hint.company}</strong>, not {account.name}. Check you are on the right account.</>}
            </div>
          )}
        </>
      )}

      <div className="grid">
        <label>Full name<input id="li-name" value={form.fullName} onChange={(e) => set({ fullName: e.target.value })} /></label>
        <label>Title<input id="li-title" value={form.title} onChange={(e) => set({ title: e.target.value })} /></label>
        <label>Persona
          <select id="li-persona" value={form.persona} onChange={(e) => set({ persona: e.target.value })}>
            <option value="">Choose a persona</option>
            {personas.map((p) => <option key={p.code} value={p.code}>{p.label}</option>)}
          </select>
        </label>
        <label>City<input id="li-city" value={form.city} onChange={(e) => set({ city: e.target.value })} /></label>
        <label>Business email<input id="li-email" type="email" value={form.email} onChange={(e) => set({ email: e.target.value })} /></label>
        <label>Phone<input id="li-phone" value={form.phone} onChange={(e) => set({ phone: e.target.value })} /></label>
        <label>Source<input id="li-source" value={form.source} onChange={(e) => set({ source: e.target.value })} /></label>
        <label>Consent basis
          <select id="li-consent" value={form.consentBasis} onChange={(e) => set({ consentBasis: e.target.value })}>
            <option value="BUSINESS_CONTACT">Business contact</option>
            <option value="EXISTING_RELATIONSHIP">Existing relationship</option>
            <option value="CONSENTED">Consented</option>
          </select>
        </label>
      </div>
      <label className="wide">Notes
        <textarea id="li-notes" rows={2} value={form.notes} onChange={(e) => set({ notes: e.target.value })}
          placeholder="For example: mutual connections, who could introduce us" />
      </label>

      {save.error && (
        <div className="notice bad" role="alert">
          {problems.length ? <ul className="problems">{problems.map((p) => <li key={p}>{p}</li>)}</ul> : save.error.message}
        </div>
      )}
      <div className="actions">
        <button type="button" disabled={save.isPending} onClick={() => save.mutate()}>{save.isPending ? 'Saving…' : 'Save contact'}</button>
        <button type="button" className="secondary" onClick={onDone}>Cancel</button>
      </div>
    </section>
  );
}
