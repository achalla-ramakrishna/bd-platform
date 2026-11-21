import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router';
import { ProspectingPage, linkedinLink, type ProspectQueue } from './ProspectingPage';
import type { Me } from '../lib/api';

const me = { id: 1, email: 'admin@example-gcc.test', name: 'Admin Person', roles: ['BD_LEAD'], canImport: false } as unknown as Me;

const queue: ProspectQueue = {
  day: '2026-11-21', size: 20, researchEnabled: true, researchLeftToday: 12,
  last30Days: { sent: 4, skipped: 1, expired: 0, edited: 2, skipReasons: { NOT_NOW: 1 } },
  items: [
    { id: 11, personType: 'CONTACT', personId: 7, name: 'Person One', title: 'Head of Engineering', company: 'Example GCC',
      linkedinUrl: null, accountId: 3, accountName: 'Example GCC', score: 82, headline: 'Heads the platform', whyNow: 'Hiring 30 engineers',
      draftNote: 'Saw the platform build. Open to a chat?', status: 'QUEUED' },
    { id: 12, personType: 'CONNECTION', personId: 9, name: 'Friend Two', score: 70, draftNote: 'Hi', sentNote: 'Hi there',
      status: 'SENT', trackId: 5 },
  ],
};

describe('Prospecting queue (ADR-0023)', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('opens the profile, or a LinkedIn search by name and company', () => {
    expect(linkedinLink({ linkedinUrl: 'https://www.linkedin.com/in/example', name: 'A', company: 'B' })).toBe('https://www.linkedin.com/in/example');
    expect(linkedinLink({ linkedinUrl: null, name: 'Person One', company: 'Example GCC' }))
      .toBe('https://www.linkedin.com/search/results/people/?keywords=Person%20One%20Example%20GCC');
  });

  it('shows today\'s people with their draft, and sends the edited note', async () => {
    const overview = { sending: false, writerEnabled: true, window: '09:00–18:00 IST', perSenderPerDay: 25, mine: [], templates: [] };
    const fetchMock = vi.fn(async (url: string) => new Response(JSON.stringify(url.endsWith('/queue') ? queue
      : url.includes('/sequences/contact/') ? [] : url.endsWith('/sequences') ? overview : queue.items[0]), { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={client}><MemoryRouter><ProspectingPage me={me} /></MemoryRouter></QueryClientProvider>);
    const note = await screen.findByLabelText(/Note \(/) as HTMLTextAreaElement;
    expect(note.value).toBe('Saw the platform build. Open to a chat?');
    expect(screen.getByText('50% notes edited')).toBeTruthy();
    expect(screen.getByText(/Sent \(edited\)/)).toBeTruthy();
    fireEvent.change(note, { target: { value: 'Coffee next week?' } });
    fireEvent.click(screen.getByRole('button', { name: 'I sent it' }));
    await waitFor(() => {
      const call = fetchMock.mock.calls.find(([u]) => String(u).endsWith('/prospecting/queue/11/sent')) as unknown[] | undefined;
      expect(call).toBeTruthy();
      expect(JSON.parse((call![1] as RequestInit).body as string)).toEqual({ note: 'Coffee next week?' });
    });
  });
});
