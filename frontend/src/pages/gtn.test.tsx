import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router';
import { StageActions, sendLink } from './gtn';
import type { GtnTrack, GtnTrackPage } from '../lib/api';

const person = { fullName: 'Engineer A', linkedinUrl: 'https://www.linkedin.com/in/engineer-a', email: 'engineer.a@example-gcc.test', whatsapp: '919800000000' };

const track: GtnTrack = {
  id: 5, connectionId: 9, company: 'Example GCC', ownerId: 1, ownerName: 'Admin Person', stage: 'UNRESPONSIVE',
  stageSince: '2026-11-20T00:00:00Z', daysInStage: 1, whyReason: null, nextAction: 'Diagnose: why no reply?', nextActionOn: '2026-11-21',
  overdue: false, lastOutOn: '2026-11-13', lastChannel: 'LINKEDIN', nudgesSent: 0, beepIntervalDays: 30, version: 3, askNow: false,
  bestVideoPct: 0, replyCheck: false, ...person,
};

const page: GtnTrackPage = {
  detail: { track, touches: [], changes: [], commitments: [] },
  next: { track, task: 'DIAGNOSE', taskLabel: 'No reply: why?', overdueDays: 0, suggestedReason: 'INERTIA', suggestion: null },
  rules: { unresponsiveDays: 7, nudgeSpacingDays: 5, nudgeCap: 3, beepIntervalDays: 30 },
};

describe('GTN send links (ADR-0018)', () => {
  it('opens WhatsApp and Gmail with the message filled in, and LinkedIn at the profile', () => {
    expect(sendLink('WHATSAPP', person, '', 'Hi Engineer, 20 minutes?')).toBe('https://wa.me/919800000000?text=Hi%20Engineer%2C%2020%20minutes%3F');
    expect(sendLink('EMAIL', person, 'Catching up', 'Hi & hello')).toBe(
      'https://mail.google.com/mail/?view=cm&fs=1&to=engineer.a%40example-gcc.test&su=Catching%20up&body=Hi%20%26%20hello');
    expect(sendLink('LINKEDIN', person, '', 'x')).toBe('https://www.linkedin.com/in/engineer-a');
    expect(sendLink('WHATSAPP', { ...person, whatsapp: null }, '', 'x')).toBeNull();
  });
});

describe('GTN stage actions (ADR-0017)', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('asks why someone is quiet, highlights inertia, and sends the diagnosis with the version', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify(page), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    vi.stubGlobal('fetch', fetchMock);
    const done = vi.fn();
    render(
      <QueryClientProvider client={new QueryClient()}>
        <MemoryRouter><StageActions page={page} onDone={done} /></MemoryRouter>
      </QueryClientProvider>,
    );
    expect(screen.getByRole('button', { name: 'Inertia to respond (likely)' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Not seen on LinkedIn' }));
    await waitFor(() => expect(done).toHaveBeenCalled());
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toContain('/network/tracks/5/actions');
    expect(JSON.parse(init.body as string)).toEqual({ action: 'DIAGNOSED', reason: 'NOT_SEEN', version: 3 });

    // "Not the right time" asks for a revisit date and the reason before parking.
    fireEvent.click(screen.getByRole('button', { name: 'Now is not the right time' }));
    fireEvent.change(screen.getByLabelText('Why not now'), { target: { value: 'Busy quarter' } });
    fireEvent.click(screen.getByRole('button', { name: 'Park in monthly beep' }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    const second = JSON.parse((fetchMock.mock.calls[1] as unknown as [string, RequestInit])[1].body as string);
    expect(second.action).toBe('DIAGNOSED');
    expect(second.reason).toBe('NOT_RIGHT_TIME');
    expect(second.revisitReason).toBe('Busy quarter');
  });
});

describe('GTN dashboard (M10)', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('shows the response rate, the funnel and the profile page numbers', async () => {
    const { DashboardTab } = await import('./GtnProfileAndDashboard');
    const body = {
      from: '2026-10-23', to: '2026-11-21', total: 4,
      byStage: { INITIATE: 1, RESPONDED: 1, UNRESPONSIVE: 1, INTRO_MADE: 0, CALL_SCHEDULED: 0, WARM_COMMITMENT: 1, MONTHLY_BEEP: 0 },
      cohort: { started: 4, responded: 2, intro: 1, call: 1, warm: 1 }, commitments: 1,
      reasons: [{ reason: 'INERTIA', total: 2, replied: 1, parked: 0, stillQuiet: 1 }],
      nudges: 3, nudgedThenReplied: 1, beepsSent: 0, overdue: 2, missedBeeps: 0,
      profile: { links: 3, visitors: 2, watched75: 1, ctaClicks: 1, interested: 1 }, sources: [{ source: 'Alumni', started: 4, responded: 2, warm: 1 }],
    };
    vi.stubGlobal('fetch', vi.fn(async (url: string) => new Response(JSON.stringify(url.includes('sources') ? ['Alumni'] : body),
      { status: 200, headers: { 'Content-Type': 'application/json' } })));
    render(<QueryClientProvider client={new QueryClient()}><MemoryRouter><DashboardTab /></MemoryRouter></QueryClientProvider>);
    const started = await screen.findByText('2 of 4 started');
    expect(started.parentElement!.textContent).toContain('Response rate50%'); // 2 of 4 responded
    expect(screen.getByText('Inertia to respond')).toBeTruthy();
    expect(screen.getByRole('region', { name: 'Profile page' }).textContent).toContain('Watched 75%+ of the video1');
  });
});

describe('GTN with Gmail (ADR-0020)', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  function respond(map: Record<string, unknown>) {
    const fetchMock = vi.fn(async (url: string) => {
      const key = Object.keys(map).find((k) => url.includes(k)) ?? '';
      return new Response(JSON.stringify(map[key] ?? {}), { status: 200, headers: { 'Content-Type': 'application/json' } });
    });
    vi.stubGlobal('fetch', fetchMock);
    return fetchMock;
  }

  it('sends from Gmail when Google is connected and the channel is email', async () => {
    const { Composer } = await import('./gtn');
    respond({
      '/google/status': { available: true, connected: true, email: 'admin@example-gcc.test', canSend: true, canRead: true, canCalendar: true },
      '/network/templates': [],
    });
    const sent = vi.fn();
    const plain = vi.fn();
    render(
      <QueryClientProvider client={new QueryClient()}>
        <MemoryRouter>
          <Composer person={person} stage="INITIATE" suggestion={{ channel: 'EMAIL', subject: 'Catching up', body: 'Hi Engineer', templateId: 1 }}
            onSent={plain} onSendEmail={sent} />
        </MemoryRouter>
      </QueryClientProvider>,
    );
    fireEvent.click(await screen.findByRole('button', { name: 'Send from Gmail (admin@example-gcc.test)' }));
    expect(sent).toHaveBeenCalledWith({ subject: 'Catching up', body: 'Hi Engineer', templateId: 1 });
    expect(plain).not.toHaveBeenCalled();
    // On WhatsApp it is still "I sent it".
    fireEvent.click(screen.getByRole('button', { name: 'WhatsApp' }));
    expect(screen.getByRole('button', { name: 'I sent it' })).toBeTruthy();
  });

  it('asks what a reply found in Gmail meant', async () => {
    const replied = { ...track, stage: 'RESPONDED' as const, replyCheck: true, lastTopic: 'Replied by email: Next quarter is better' };
    const fetchMock = respond({ '/actions': { ...page, detail: { ...page.detail, track: { ...replied, replyCheck: false } } } });
    const done = vi.fn();
    render(
      <QueryClientProvider client={new QueryClient()}>
        <MemoryRouter><StageActions page={{ ...page, detail: { ...page.detail, track: replied } }} onDone={done} /></MemoryRouter>
      </QueryClientProvider>,
    );
    expect(screen.getByRole('status').textContent).toContain('Next quarter is better');
    fireEvent.click(screen.getByRole('button', { name: 'Not the right time' }));
    fireEvent.change(screen.getByLabelText('Why not now'), { target: { value: 'Next quarter' } });
    fireEvent.click(screen.getByRole('button', { name: 'Park in monthly beep' }));
    await waitFor(() => expect(done).toHaveBeenCalled());
    const body = JSON.parse((fetchMock.mock.calls[0] as unknown as [string, RequestInit])[1].body as string);
    expect(body).toMatchObject({ action: 'CONFIRM_REPLY', outcome: 'NOT_RIGHT_TIME', revisitReason: 'Next quarter', version: 3 });
  });
});
