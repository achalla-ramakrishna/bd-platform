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
  overdue: false, lastOutOn: '2026-11-13', lastChannel: 'LINKEDIN', nudgesSent: 0, beepIntervalDays: 30, version: 3, ...person,
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
