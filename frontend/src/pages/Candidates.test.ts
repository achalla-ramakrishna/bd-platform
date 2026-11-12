import { describe, expect, it } from 'vitest';
import { nextMoves } from './Candidates';

describe('candidate moves', () => {
  it('offers the next stage, a shortcut from shared to selected, and closing', () => {
    expect(nextMoves('PROPOSED')).toEqual(['SHARED', 'REJECTED', 'WITHDRAWN']);
    expect(nextMoves('SHARED')).toEqual(['INTERVIEW', 'SELECTED', 'REJECTED', 'WITHDRAWN']);
    expect(nextMoves('ONBOARDING')).toEqual(['STARTED', 'REJECTED', 'WITHDRAWN']);
    expect(nextMoves('STARTED')).toEqual([]);
    expect(nextMoves('REJECTED')).toEqual(['PROPOSED']);
  });
});
