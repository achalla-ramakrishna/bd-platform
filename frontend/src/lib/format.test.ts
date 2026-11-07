import { describe, expect, it } from 'vitest';
import { daysUntil, formatInr, formatRate, humanise } from './format';

describe('format', () => {
  it('uses Indian digit grouping for INR', () => {
    expect(formatInr(1200000)).toBe('₹12,00,000');
  });

  it('shows a dash for restricted values', () => {
    expect(formatInr(null)).toBe('—');
    expect(formatRate(undefined, 'MONTH')).toBe('—');
  });

  it('adds the rate unit', () => {
    expect(formatRate(4000, 'DAY')).toBe('₹4,000/day');
  });

  it('counts days to a date', () => {
    expect(daysUntil('2027-03-31', new Date(2027, 2, 1))).toBe(30);
    expect(daysUntil('2027-02-28', new Date(2027, 2, 1))).toBe(-1);
  });

  it('humanises codes', () => {
    expect(humanise('PAST_CLIENT')).toBe('Past client');
  });
});
