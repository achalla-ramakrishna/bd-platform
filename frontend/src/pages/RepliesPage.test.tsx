import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router';
import { RepliesPage, type Reply, type ReplyInbox } from './RepliesPage';
import type { Me } from '../lib/api';

const me = { id: 1, email: 'admin@example-gcc.test', name: 'Admin Person', roles: ['BD_LEAD'], canImport: false } as unknown as Me;

const reply: Reply = {
  id: 4, personType: 'CONTACT', personId: 7, personName: 'Person One', personTitle: 'Head of Engineering', company: 'Example GCC',
  linkedinUrl: 'https://www.linkedin.com/in/example', email: null, conversationId: 3, trackId: null, ownerName: 'Admin Person',
  source: 'PASTED', channel: 'LINKEDIN', replyText: 'Busy until next quarter, ping me then', receivedOn: '2026-11-21', status: 'READY',
  kind: 'NOT_NOW', confidence: 'HIGH', summary: 'Busy until next quarter', revisitOn: '2027-02-21', nextStep: 'Check back in February',
  draftAnswer: 'Thanks, I will check back in February.', optedOut: false, version: 0,
};

const inbox: ReplyInbox = {
  waiting: [reply],
  recent: [{ ...reply, id: 5, personName: 'Person Two', status: 'DONE', kind: 'UNSUBSCRIBE', finalKind: 'UNSUBSCRIBE', optedOut: true }],
  last30Days: { read: 6, confirmed: 4, changed: 1, optedOut: 1, kinds: { NOT_NOW: 3, INTERESTED: 3 } },
  enabled: true,
};

describe('Replies (ADR-0024)', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('shows the kind and draft, says what Done will do, and sends the decision', async () => {
    const fetchMock = vi.fn(async (url: string) => new Response(JSON.stringify(url.endsWith('/replies') ? inbox : reply), { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={client}><MemoryRouter><RepliesPage me={me} /></MemoryRouter></QueryClientProvider>);
    expect(await screen.findByText('Busy until next quarter, ping me then')).toBeTruthy();
    expect(screen.getByText('25%')).toBeTruthy();
    expect(screen.getByText(/sets a reminder for the date below/)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Undo' })).toBeTruthy();
    expect((screen.getByLabelText('Your answer (draft)') as HTMLTextAreaElement).value).toBe('Thanks, I will check back in February.');

    fireEvent.change(screen.getByLabelText('Kind'), { target: { value: 'NOT_INTERESTED' } });
    expect(screen.getByText(/Closes the conversation/)).toBeTruthy();
    fireEvent.change(screen.getByLabelText('Kind'), { target: { value: 'NOT_NOW' } });
    fireEvent.click(screen.getByLabelText(/I sent this answer/));
    fireEvent.click(screen.getByRole('button', { name: 'Done' }));
    await waitFor(() => {
      const call = fetchMock.mock.calls.find(([u]) => String(u).endsWith('/replies/4/done')) as unknown[] | undefined;
      expect(call).toBeTruthy();
      expect(JSON.parse((call![1] as RequestInit).body as string)).toEqual({
        kind: 'NOT_NOW', answer: 'Thanks, I will check back in February.', answerSent: true, revisitOn: '2027-02-21', version: 0,
      });
    });
  });
});
