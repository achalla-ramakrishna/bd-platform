import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api, type Account, type Page } from '../lib/api';

/** Type two or more letters to find an account; shows the chosen one with a "change" link. */
export function AccountPicker({ id, value, valueName, onChange, placeholder }: {
  id: string; value: number | null; valueName?: string | null; onChange: (id: number | null, name: string | null) => void;
  placeholder?: string;
}) {
  const [query, setQuery] = useState('');
  const [chosenName, setChosenName] = useState<string | null>(valueName ?? null);
  const accounts = useQuery({
    queryKey: ['accounts', 'picker', query],
    queryFn: () => api.get<Page<Account>>(`/accounts?size=20&sort=name&q=${encodeURIComponent(query)}`),
    enabled: value === null && query.trim().length >= 2,
  });
  if (value !== null) {
    return (
      <span className="chosen">{chosenName ?? valueName ?? '…'}{' '}
        <button type="button" className="link" onClick={() => { setChosenName(null); onChange(null, null); }}>change</button>
      </span>
    );
  }
  return (
    <>
      <input id={id} value={query} placeholder={placeholder ?? 'Type 2+ letters of the account'} autoComplete="off"
        onChange={(e) => setQuery(e.target.value)} />
      {accounts.data && accounts.data.items.length > 0 && (
        <ul className="picker">
          {accounts.data.items.map((a) => (
            <li key={a.id}>
              <button type="button" onClick={() => { setChosenName(a.name); onChange(a.id, a.name); }}>
                {a.name} <span className="muted">· {a.city}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
      {accounts.data && accounts.data.items.length === 0 && <span className="muted small">No account matches.</span>}
    </>
  );
}
