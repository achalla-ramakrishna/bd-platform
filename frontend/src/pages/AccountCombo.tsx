import { useId, useRef, useState, type KeyboardEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api, type Account, type Page } from '../lib/api';

/** Accounts shown before typing narrows the list; clients always show. */
const OTHERS_SHOWN = 100;

/**
 * An editable account select: type to filter by name, parent company, city or website, pick with the mouse or
 * the arrow keys and Enter. Clients come first, then all other accounts.
 */
export function AccountCombo({ id, value, onChange, placeholder, autoFocus }: {
  id: string;
  value: number | null;
  onChange: (id: number | null, name: string | null) => void;
  placeholder?: string;
  autoFocus?: boolean;
}) {
  const all = useQuery({
    queryKey: ['accounts', 'combo'],
    queryFn: () => api.get<Page<Account>>('/accounts?size=1000&sort=name'),
    staleTime: 5 * 60 * 1000,
  });
  const [text, setText] = useState('');
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const listId = useId();
  const list = useRef<HTMLUListElement>(null);
  const items = all.data?.items ?? [];
  const selected = items.find((a) => a.id === value) ?? null;

  const q = text.trim().toLowerCase();
  const match = (a: Account) => !q || [a.name, a.parentCompany, a.city, a.websiteDomain]
    .some((s) => s?.toLowerCase().includes(q));
  const clients = items.filter((a) => a.relationship === 'CLIENT' && match(a));
  const othersAll = items.filter((a) => a.relationship !== 'CLIENT' && match(a));
  const others = othersAll.slice(0, OTHERS_SHOWN);
  const flat = [...clients, ...others];

  const choose = (a: Account) => {
    onChange(a.id, a.name);
    setText('');
    setOpen(false);
  };
  const scrollTo = (i: number) => {
    list.current?.querySelector(`[data-index="${i}"]`)?.scrollIntoView?.({ block: 'nearest' });
  };
  const onKey = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      setOpen(true);
      const next = flat.length === 0 ? 0 : (active + (e.key === 'ArrowDown' ? 1 : -1) + flat.length) % flat.length;
      setActive(next);
      scrollTo(next);
    } else if (e.key === 'Enter' && open) {
      e.preventDefault();
      if (flat[active]) choose(flat[active]);
    } else if (e.key === 'Escape') {
      setOpen(false);
      setText('');
    }
  };

  const option = (a: Account, i: number) => (
    <li key={a.id} id={`${listId}-${a.id}`} role="option" aria-selected={a.id === value} data-index={i}
      className={i === active ? 'active' : undefined}
      onMouseDown={(e) => { e.preventDefault(); choose(a); }} onMouseEnter={() => setActive(i)}>
      <span>{a.name}</span>
      <span className="muted small">{[a.city, a.parentCompany !== a.name ? a.parentCompany : null].filter(Boolean).join(' · ')}</span>
    </li>
  );

  return (
    <div className="combo">
      <input id={id} role="combobox" aria-expanded={open} aria-controls={listId} aria-autocomplete="list"
        aria-activedescendant={open && flat[active] ? `${listId}-${flat[active].id}` : undefined}
        autoComplete="off" autoFocus={autoFocus}
        value={open ? text : selected?.name ?? text}
        placeholder={selected ? selected.name : placeholder ?? (all.isPending ? 'Loading accounts…' : 'Type to search accounts')}
        onFocus={() => { setOpen(true); setText(''); setActive(0); }}
        onBlur={() => setOpen(false)}
        onChange={(e) => { setText(e.target.value); setOpen(true); setActive(0); }}
        onKeyDown={onKey} />
      {value !== null && !open && (
        <button type="button" className="combo-clear" aria-label="Clear account" onClick={() => onChange(null, null)}>×</button>
      )}
      {open && (
        <ul ref={list} id={listId} role="listbox" className="combo-list" aria-label="Accounts">
          {all.isPending && <li className="combo-note muted">Loading accounts…</li>}
          {clients.length > 0 && <li role="presentation" className="combo-group">Clients</li>}
          {clients.map((a, i) => option(a, i))}
          {others.length > 0 && <li role="presentation" className="combo-group">All accounts</li>}
          {others.map((a, i) => option(a, clients.length + i))}
          {othersAll.length > others.length && (
            <li role="presentation" className="combo-note muted small">{othersAll.length - others.length} more: type to narrow the list</li>
          )}
          {!all.isPending && flat.length === 0 && <li role="presentation" className="combo-note muted">No account matches “{text}”.</li>}
        </ul>
      )}
    </div>
  );
}
