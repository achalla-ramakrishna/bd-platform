import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router';
import { SequencesPage, type Enrolment, type SequenceOverview } from './SequencesPage';
import type { Me } from '../lib/api';

const me = { id: 1, email: 'admin@example-gcc.test', name: 'Admin Person', roles: ['ACCOUNT_EXECUTIVE'], canImport: false } as unknown as Me;

const enrolment: Enrolment = {
  id: 9, sequenceId: 1, sequenceName: 'Introduction, three emails', contactId: 7, contactName: 'Person One', accountId: 3,
  accountName: 'Example GCC', ownerName: 'Admin Person', conversationId: null, toEmail: 'person.one@example-gcc.test', status: 'REVIEW',
  nextPosition: 1, sent: 0, steps: 2, createdAt: '2026-11-21T10:00:00Z', version: 2,
  emails: [
    { position: 1, waitDays: 0, subject: 'A quick idea', body: 'Hello Person One, a fictional first email.', edited: false, status: 'PLANNED' },
    { position: 2, waitDays: 4, subject: 'Re: A quick idea', body: 'A fictional follow-up.', edited: false, status: 'PLANNED' },
  ],
};

const overview: SequenceOverview = {
  sending: false, writerEnabled: true, window: '09:00–18:00 IST, Monday to Friday', perSenderPerDay: 25, mine: [enrolment],
  templates: [{ id: 1, name: 'Introduction, three emails', active: true, steps: [{ waitDays: 0, subject: 'A quick idea for {company}', body: 'Introduce.' }] }],
};

describe('Email sequences (ADR-0025)', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('says sending is off, lets the owner edit each email and approve once', async () => {
    const fetchMock = vi.fn(async (url: string) => new Response(JSON.stringify(url.endsWith('/sequences') ? overview : enrolment), { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={client}><MemoryRouter><SequencesPage me={me} /></MemoryRouter></QueryClientProvider>);
    expect(await screen.findByText(/Sending is off until an Admin/)).toBeTruthy();
    // A non-lead does not edit the team's templates.
    expect(screen.queryByRole('button', { name: 'New sequence' })).toBeNull();

    fireEvent.change(screen.getAllByLabelText('Email')[0], { target: { value: 'Hello Person One, an edited fictional email.' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save email 1' }));
    fireEvent.click(screen.getByRole('button', { name: 'Approve and schedule' }));
    await waitFor(() => {
      const urls = fetchMock.mock.calls.map(([u]) => String(u));
      expect(urls.some((u) => u.endsWith('/sequences/9/emails/1'))).toBe(true);
      expect(urls.some((u) => u.endsWith('/sequences/9/approve?version=2'))).toBe(true);
    });
  });
});
