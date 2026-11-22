import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router';
import { WalnutPage, latestPlanIndex } from './WalnutPage';
import type { WalnutMessage } from '../lib/api';

const old: WalnutMessage = { id: 1, author: 'WALNUT', kind: 'BRIEF', body: '### Your BD plan for an old day', actions: [], createdAt: '2026-11-12T03:00:00Z' };
const chat: WalnutMessage = { id: 2, author: 'PERSON', kind: 'CHAT', body: 'An old question', actions: [], createdAt: '2026-11-12T05:00:00Z' };
const today: WalnutMessage = { id: 3, author: 'WALNUT', kind: 'BRIEF', body: '### Your BD plan for today', actions: [], createdAt: new Date().toISOString() };

describe('Walnut (ADR-0015)', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it('finds the newest plan', () => {
    expect(latestPlanIndex([old, chat, today])).toBe(2);
    expect(latestPlanIndex([chat])).toBe(-1);
  });

  it('shows the newest plan first and folds earlier plans and chats away', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => new Response(JSON.stringify(
      url.endsWith('/walnut/status') ? { available: true, chatEnabled: true, worksWith: ['Admin Person'] }
        : url.endsWith('/walnut/messages') ? [old, chat, today]
          : { emailBrief: true, email: 'admin@example-gcc.test', emailAvailable: false }), { status: 200 })));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={client}><MemoryRouter><WalnutPage /></MemoryRouter></QueryClientProvider>);
    const plan = await screen.findByText('Your BD plan for today');
    const earlier = screen.getByText(/Earlier plans and chats \(2\)/);
    // Today's plan comes before the earlier ones on the page, and nothing says it is out of date.
    expect(plan.compareDocumentPosition(earlier) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.queryByText(/This plan is from/)).toBeNull();
  });

  it('says when the newest plan is not today', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => new Response(JSON.stringify(
      url.endsWith('/walnut/status') ? { available: true, chatEnabled: true, worksWith: ['Admin Person'] }
        : url.endsWith('/walnut/messages') ? [old] : { emailBrief: false, email: 'admin@example-gcc.test', emailAvailable: false }), { status: 200 })));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={client}><MemoryRouter><WalnutPage /></MemoryRouter></QueryClientProvider>);
    expect(await screen.findByText(/This plan is from/)).toBeTruthy();
  });
});
