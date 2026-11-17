const gbp = new Intl.NumberFormat('en-GB', { style: 'currency', currency: 'GBP' });

export function money(amount: number | null | undefined, currency = 'GBP', wholePounds = false): string {
  if (amount == null) return '–';
  const f = currency === 'GBP' && !wholePounds ? gbp
    : new Intl.NumberFormat('en-GB', { style: 'currency', currency, maximumFractionDigits: wholePounds ? 0 : 2 });
  return f.format(amount);
}

/** 1240000 -> 1.24M, 4310000 -> 4.31M, 1872 -> 1,872. */
export function compact(n: number | null | undefined): string {
  if (n == null) return '–';
  if (Math.abs(n) >= 1_000_000) return `${(n / 1_000_000).toFixed(2)}M`;
  return n.toLocaleString('en-GB');
}

export function minutesLeft(due: string, now = Date.now()): number {
  return Math.round((new Date(due).getTime() - now) / 60000);
}

export function slaText(due: string, now = Date.now()): string {
  const m = minutesLeft(due, now);
  return m >= 0 ? `${m} min left` : `${-m} min overdue`;
}

export function time(iso: string): string {
  return new Date(iso).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', timeZone: 'Europe/London' });
}

export function date(iso: string | null): string {
  if (!iso) return '–';
  return new Date(iso).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'Europe/London' });
}

export function monthYear(iso: string | null): string {
  if (!iso) return '–';
  return new Date(iso).toLocaleDateString('en-GB', { month: 'short', year: 'numeric', timeZone: 'UTC' });
}

export function titleCase(s: string): string {
  return s.charAt(0) + s.slice(1).toLowerCase().replace(/_/g, '-');
}

export const CHANNEL_LABELS: Record<string, string> = { INSTANT: 'Instant', CARD: 'Card', TRANSFER: 'Transfer' };
export const ACTION_LABELS: Record<string, string> = { BLOCK: 'Block', HOLD: 'Hold', STEP_UP: 'Step-up', MONITOR: 'Monitor' };

/** Score pill tone: red at 90 and above, amber from 80, grey below (screen 2). */
export function scoreTone(score: number): string {
  return score >= 90 ? 'red' : score >= 80 ? 'amber' : 'grey';
}