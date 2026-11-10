import { describe, expect, it } from 'vitest';
import { errorMessage } from './api';

describe('errorMessage', () => {
  it('uses the server detail when there is one', () => {
    expect(errorMessage(422, { detail: 'Type what happened.' })).toBe('Type what happened.');
  });

  it('is never empty, even for a bare 500 or a proxy page', () => {
    expect(errorMessage(500, { timestamp: 'x', status: 500, error: '' })).toContain('HTTP 500');
    expect(errorMessage(504, undefined)).toContain('HTTP 504');
    expect(errorMessage(413, undefined)).toContain('too large');
  });
});
