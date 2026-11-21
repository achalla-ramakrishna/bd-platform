import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ProspectBriefPanel, type ProspectView } from './ProspectBrief';

const done: ProspectView = {
  id: 3, personType: 'CONTACT', personId: 7, personName: 'Person One', accountId: 1, accountName: 'Example GCC', status: 'DONE',
  brief: {
    headline: 'Head of the payments platform', role: 'Payments engineering', summary: 'Leads the platform build.',
    whyNow: [{ statement: 'Hiring 30 engineers', source: 'https://jobs.example.test/1' }, { statement: 'Met at an event', source: 'Our records' }],
    angle: { offer: 'AI pods', why: 'A fast build-out' }, talkingPoints: ['Their migration'],
    connectionNote: 'Saw the payments build. Open to a chat?', cautions: ['Check the team size'],
    roleFit: 5, roleFitReason: 'Heads the platform', timing: 4, timingReason: 'Hiring push',
  },
  sources: [{ url: 'https://jobs.example.test/1', title: 'Jobs' }], roleFit: 5, timing: 4, access: 2,
  accessReason: 'No one in the team knows them yet; their company is a client', score: 78, error: null,
  requestedBy: 'Admin Person', requestedAt: '2026-11-21T10:00:00Z',
};

function respond(routes: Record<string, unknown>) {
  return vi.fn(async (url: string) => {
    const key = Object.keys(routes).find((k) => url.endsWith(k));
    const body = key === undefined ? undefined : routes[key];
    return body === undefined ? new Response(null, { status: 204 }) : new Response(JSON.stringify(body), { status: 200 });
  });
}

function show(editable = true) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={client}><ProspectBriefPanel type="CONTACT" id={7} editable={editable} /></QueryClientProvider>);
}

describe('Research brief (ADR-0022)', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('shows the score, sourced reasons and the note to copy, and offers to research again', async () => {
    vi.stubGlobal('fetch', respond({ '/prospects/status': { enabled: true, remainingToday: 10 }, '/prospects/contact/7': done }));
    show();
    expect(await screen.findByText('Head of the payments platform')).toBeTruthy();
    expect(screen.getByTitle(/Agent score/).textContent).toBe('78');
    expect(screen.getByText('source').getAttribute('href')).toBe('https://jobs.example.test/1');
    expect(screen.getByText('(Our records)')).toBeTruthy();
    expect(screen.getByText('Saw the payments build. Open to a chat?')).toBeTruthy();
    expect(screen.getByText(/their company is a client/)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Research again' })).toBeTruthy();
  });

  it('offers research when there is no brief, and starts it', async () => {
    const fetchMock = respond({ '/prospects/status': { enabled: true, remainingToday: 10 }, '/research': { ...done, status: 'RUNNING', brief: null } });
    vi.stubGlobal('fetch', fetchMock);
    show();
    fireEvent.click(await screen.findByRole('button', { name: 'Research this person' }));
    expect(await screen.findByText(/Researching/)).toBeTruthy();
    await waitFor(() => expect(fetchMock.mock.calls.some(([u]) => String(u).endsWith('/prospects/contact/7/research'))).toBe(true));
  });

  it('hides the button from people who cannot edit the pipeline', async () => {
    vi.stubGlobal('fetch', respond({ '/prospects/status': { enabled: true, remainingToday: 10 }, '/prospects/contact/7': done }));
    show(false);
    await screen.findByText('Head of the payments platform');
    expect(screen.queryByRole('button', { name: 'Research again' })).toBeNull();
  });
});
