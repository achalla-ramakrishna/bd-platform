const inr = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 });

/** ₹12,00,000 style, Indian digit grouping. Restricted values arrive as null and show as a dash. */
export function formatInr(value: number | null | undefined): string {
  return value === null || value === undefined ? '—' : inr.format(value);
}

const UNIT_LABELS: Record<string, string> = { MONTH: '/month', DAY: '/day', HOUR: '/hour' };

export function formatRate(value: number | null | undefined, unit?: string): string {
  if (value === null || value === undefined) return '—';
  return `${inr.format(value)}${unit ? UNIT_LABELS[unit] ?? '' : ''}`;
}

const dateFormat = new Intl.DateTimeFormat('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });

export function formatDate(iso?: string | null): string {
  if (!iso) return '—';
  const [y, m, d] = iso.substring(0, 10).split('-').map(Number);
  return dateFormat.format(new Date(y, m - 1, d));
}

/** Whole days from today until an ISO date; negative when it has passed. */
export function daysUntil(iso: string, today: Date = new Date()): number {
  const [y, m, d] = iso.substring(0, 10).split('-').map(Number);
  const target = Date.UTC(y, m - 1, d);
  const start = Date.UTC(today.getFullYear(), today.getMonth(), today.getDate());
  return Math.round((target - start) / 86_400_000);
}

/** "PAST_CLIENT" becomes "Past client". */
export function humanise(code?: string | null): string {
  if (!code) return '—';
  const words = code.toLowerCase().replace(/_/g, ' ');
  return words.charAt(0).toUpperCase() + words.slice(1);
}
