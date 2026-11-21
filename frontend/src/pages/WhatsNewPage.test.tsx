import { afterEach, describe, expect, it } from 'vitest';
import { act, cleanup, render, renderHook, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { WhatsNewPage, useUnseenCount } from './WhatsNewPage';
import { WHATS_NEW, type WhatsNewEntry } from '../whatsNew';

const entries: WhatsNewEntry[] = [
  { id: 'b', date: '2026-11-21', title: 'Second thing', summary: 'Newer', steps: ['Do this'], link: { to: '/network', label: 'Open it' } },
  { id: 'a', date: '2026-11-10', title: 'First thing', summary: 'Older', steps: [] },
];

describe("What's new", () => {
  afterEach(() => {
    cleanup();
    localStorage.clear();
  });

  it('marks unseen entries new, then remembers they were seen', () => {
    localStorage.setItem('whatsNew.seen', JSON.stringify(['a']));
    const { result } = renderHook(() => useUnseenCount(entries));
    expect(result.current).toBe(1);
    act(() => {
      render(<MemoryRouter><WhatsNewPage entries={entries} /></MemoryRouter>);
    });
    expect(screen.getAllByText('New')).toHaveLength(1);
    expect(screen.getByRole('link', { name: 'Open it →' }).getAttribute('href')).toBe('/network');
    expect(result.current).toBe(0);
  });

  it('has unique ids, newest first', () => {
    expect(new Set(WHATS_NEW.map((e) => e.id)).size).toBe(WHATS_NEW.length);
    const dates = WHATS_NEW.map((e) => e.date);
    expect([...dates].sort().reverse()).toEqual(dates);
  });
});
