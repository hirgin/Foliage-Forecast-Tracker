import { describe, it, expect } from 'vitest';
import { formatDay } from './TimeSlider';

describe('formatDay', () => {
  it('formats a date as day and short month', () => {
    expect(formatDay('2026-10-08')).toBe('8 Oct');
    expect(formatDay('2026-09-01')).toBe('1 Sep');
    expect(formatDay('2026-11-15')).toBe('15 Nov');
  });

  it('parses as UTC, not local time', () => {
    // The bug this guards: `new Date('2026-10-08')` is midnight UTC, but
    // reading it with local getters west of Greenwich yields 7 Oct. Every
    // date on the slider would be off by one, and only for some users.
    expect(formatDay('2026-01-01')).toBe('1 Jan');
    expect(formatDay('2026-12-31')).toBe('31 Dec');
  });

  it('handles month boundaries in both directions', () => {
    expect(formatDay('2026-09-30')).toBe('30 Sep');
    expect(formatDay('2026-10-01')).toBe('1 Oct');
  });

  it('handles a leap day', () => {
    expect(formatDay('2024-02-29')).toBe('29 Feb');
  });

  it('does not zero-pad the day', () => {
    // "08 Oct" would look wrong next to "15 Oct" in the slider readout.
    expect(formatDay('2026-10-08')).toBe('8 Oct');
    expect(formatDay('2026-10-08').startsWith('0')).toBe(false);
  });

  it('covers every month name', () => {
    const months = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
    months.forEach((name, i) => {
      const mm = String(i + 1).padStart(2, '0');
      expect(formatDay(`2026-${mm}-15`)).toBe(`15 ${name}`);
    });
  });
});
