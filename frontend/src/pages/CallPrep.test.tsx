import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router';
import { CallPrepPanel, type CallPrep } from './CallPrep';

const prep: CallPrep = {
  id: 6, personType: 'CONTACT', personId: 7, personName: 'Person One', accountId: 3, accountName: 'Example GCC', conversationId: 4,
  ownerName: 'Admin Person', callOn: '2026-11-25', status: 'DONE', createdAt: '2026-11-22T10:00:00Z',
  prep: {
    goal: 'Understand the platform plans', about: 'Heads engineering.', needs: [{ point: 'Hiring Java engineers', source: 'Our records' }],
    recap: 'They replied and booked a call.', questions: ['What does success look like this year?'],
    concerns: [{ concern: 'Cost', answer: 'Flexible short engagements' }], nextStep: 'Propose a scoping session',
  },
  draft: { verdict: 'YES', name: 'Java team for the platform', type: 'STAFF_AUGMENTATION', need: 'Senior Java engineers.', roles: ['Java'] },
};

function show(editable: boolean, fetchMock: ReturnType<typeof vi.fn>) {
  vi.stubGlobal('fetch', fetchMock);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={client}><MemoryRouter><CallPrepPanel conversationId={4} editable={editable} /></MemoryRouter></QueryClientProvider>);
}

describe('Call prep (ADR-0026)', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('shows the brief and creates the edited opportunity', async () => {
    const fetchMock = vi.fn(async (_url: string, _init?: RequestInit) => new Response(JSON.stringify(prep), { status: 200 }));
    show(true, fetchMock);
    expect(await screen.findByText('Understand the platform plans')).toBeTruthy();
    expect(screen.getByText('What does success look like this year?')).toBeTruthy();
    expect(screen.getByText(/There is an opportunity/)).toBeTruthy();
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Java pod' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create opportunity' }));
    await waitFor(() => {
      const call = fetchMock.mock.calls.find(([u]) => String(u).endsWith('/call-prep/6/opportunity')) as unknown[] | undefined;
      expect(call).toBeTruthy();
      expect(JSON.parse((call![1] as RequestInit).body as string)).toEqual({ name: 'Java pod', type: 'STAFF_AUGMENTATION', need: 'Senior Java engineers.' });
    });
  });

  it('offers to prepare when there is none, and read-only people cannot', async () => {
    show(true, vi.fn(async () => new Response(null, { status: 204 })));
    expect(await screen.findByRole('button', { name: 'Prepare for the call' })).toBeTruthy();
    cleanup();
    show(false, vi.fn(async () => new Response(JSON.stringify(prep), { status: 200 })));
    await screen.findByText('Understand the platform plans');
    expect(screen.queryByRole('button', { name: 'Create opportunity' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Prepare again' })).toBeNull();
  });
});
