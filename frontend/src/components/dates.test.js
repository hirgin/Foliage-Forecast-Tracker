import { describe, it, expect } from 'vitest';
import { formatDay, nextFrame, playFrom } from './TimeSlider';

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

describe('playback', () => {
  // A 76-day season is indexes 0..75, so `total` is 75.
  const TOTAL = 75;

  describe('nextFrame', () => {
    it('advances a day at a time', () => {
      expect(nextFrame(0, TOTAL)).toBe(1);
      expect(nextFrame(40, TOTAL)).toBe(41);
    });

    it('advances onto the final day', () => {
      // The last day must be shown, not skipped: peak colour is the point.
      expect(nextFrame(TOTAL - 1, TOTAL)).toBe(TOTAL);
    });

    it('reports the end rather than running past it', () => {
      expect(nextFrame(TOTAL, TOTAL)).toBeNull();
    });
  });

  describe('playFrom', () => {
    it('rewinds once the season has played through', () => {
      // The bug this guards: pressing play on the last day advanced to
      // total + 1, which is out of range, so playback stopped on its first
      // tick. The season could only be watched once per page load and the
      // button looked broken.
      expect(playFrom(TOTAL, TOTAL)).toBe(0);
    });

    it('carries on from where playback was paused', () => {
      expect(playFrom(30, TOTAL)).toBe(30);
      expect(playFrom(0, TOTAL)).toBe(0);
    });

    it('rewinds from a date before the season starts', () => {
      // Equally outside the range the slider can step through.
      expect(playFrom(-5, TOTAL)).toBe(0);
    });

    it('rewinds from beyond the end too', () => {
      expect(playFrom(TOTAL + 3, TOTAL)).toBe(0);
    });

    it('replays the whole season, not just its last day', () => {
      // End to end: from a finished season, stepping from playFrom must reach
      // the end again rather than stopping immediately.
      let i = playFrom(TOTAL, TOTAL);
      let steps = 0;
      while (i !== null) {
        i = nextFrame(i, TOTAL);
        steps += 1;
      }
      expect(steps).toBe(TOTAL + 1);
    });
  });
});
