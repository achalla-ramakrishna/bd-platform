import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AccountCombo } from './AccountCombo';

const accounts = [
  { id: 1, name: 'Alpha Prospect', parentCompany: 'Alpha Corp', city: 'Pune', websiteDomain: 'alpha-gcc.test', relationship: 'PROSPECT' },
  { id: 2, name: 'Beta Client', parentCompany: 'Beta Corp', city: 'Bengaluru', websiteDomain: 'beta-gcc.test', relationship: 'CLIENT' },
  { id: 3, name: 'Gamma Prospect', parentCompany: 'Gamma Corp', city: 'Chennai', websiteDomain: 'gamma-gcc.test', relationship: 'PROSPECT' },
];

function show(onChange = vi.fn()) {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ items: accounts, page: 0, size: 1000, total: 3 }))));
  render(
    <QueryClientProvider client={new QueryClient()}>
      <AccountCombo id="acc" value={null} onChange={onChange} />
    </QueryClientProvider>,
  );
  return onChange;
}

describe('AccountCombo', () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  it('lists clients first, then all accounts', async () => {
    show();
    fireEvent.focus(screen.getByRole('combobox'));
    const options = await screen.findAllByRole('option');
    expect(options.map((o) => o.textContent)).toEqual([
      expect.stringContaining('Beta Client'), expect.stringContaining('Alpha Prospect'), expect.stringContaining('Gamma Prospect'),
    ]);
    expect(screen.getByText('Clients')).toBeTruthy();
    expect(screen.getByText('All accounts')).toBeTruthy();
  });

  it('filters as you type and picks with the keyboard', async () => {
    const onChange = show();
    const box = screen.getByRole('combobox');
    fireEvent.focus(box);
    await screen.findAllByRole('option');
    fireEvent.change(box, { target: { value: 'chennai' } });
    expect(screen.getAllByRole('option')).toHaveLength(1);
    fireEvent.keyDown(box, { key: 'Enter' });
    expect(onChange).toHaveBeenCalledWith(3, 'Gamma Prospect');
  });
});
