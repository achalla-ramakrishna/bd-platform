import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router';
import { Proposals } from './AssistantPage';
import type { AssistantAnswer, Conversation } from '../lib/api';

const reading = {
  reply: 'Logged a call with Person A.', fullName: 'Person A', title: 'Head of Engineering', company: 'Example GCC',
  city: 'Hyderabad', linkedinUrl: '', topic: 'React team', channel: 'CALL', occurredOn: '2026-11-09',
  summary: 'Wants two React engineers from March.', outcome: 'REPLIED', nextStep: 'Send profiles', nextStepDue: '2026-11-13',
  newConnection: false,
};

const conversation: Conversation = {
  id: 7, accountId: 3, accountName: 'Example GCC', city: 'Hyderabad', websiteDomain: 'example-gcc.test', personName: 'Person A',
  topic: 'React team', ownerId: 1, ownerName: 'Engineer A', status: 'REPLIED', lastTouchOn: '2026-11-01', createdAt: '2026-10-01',
  version: 2,
};

function show(answer: AssistantAnswer) {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter><Proposals answer={answer} editable /></MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('assistant proposals', () => {
  afterEach(cleanup);

  it('opens on updating the matching conversation, prefilled from what was read', () => {
    show({ reading, conversations: [conversation], connections: [], accounts: [], suggestion: 'UPDATE_CONVERSATION' });
    expect(screen.getByRole('tab', { name: 'Update conversation', selected: true })).toBeTruthy();
    expect(screen.getByText('Logged a call with Person A.')).toBeTruthy();
    expect((document.getElementById('as-upd-summary') as HTMLTextAreaElement).value).toBe('Wants two React engineers from March.');
    expect((document.getElementById('as-upd-channel') as HTMLSelectElement).value).toBe('CALL');
  });

  it('offers to add a connection when neither the person nor the company is known', () => {
    show({ reading, conversations: [], connections: [], accounts: [], suggestion: 'ADD_CONNECTION' });
    expect(screen.queryByRole('tab', { name: 'Update conversation' })).toBeNull();
    expect(screen.getByRole('tab', { name: 'Add connection', selected: true })).toBeTruthy();
    expect((document.getElementById('as-conn-fullName') as HTMLInputElement).value).toBe('Person A');
  });
});
