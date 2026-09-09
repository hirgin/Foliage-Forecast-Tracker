import { describe, it, expect } from 'vitest';
import {
  addDays, daysBetween, isDate, clampToSeason, horizonDate, isoToday, FORECAST_HORIZON_DAYS,
} from './season';

describe('addDays', () => {
  it('crosses a month boundary', () => {
    expect(addDays('2026-09-28', 5)).toBe('2026-10-03');
    expect(addDays('2026-10-03', -5)).toBe('2026-09-28');
  });

  it('crosses a year boundary', () => {
    expect(addDays('2026-12-30', 3)).toBe('2027-01-02');
  });
});

describe('daysBetween', () => {
  it('is signed', () => {
    expect(daysBetween('2026-09-28', '2026-10-03')).toBe(5);
    expect(daysBetween('2026-10-03', '2026-09-28')).toBe(-5);
  });

  it('is unaffected by daylight saving', () => {
    // US clocks change on 1 Nov 2026. Counting in local time would make this
    // 30.958 days and round to the wrong answer.
    expect(daysBetween('2026-10-25', '2026-11-25')).toBe(31);
  });
});

describe('isDate', () => {
  it('accepts a real date', () => {
    expect(isDate('2026-10-06')).toBe(true);
  });

  it('rejects a well-shaped impossibility', () => {
    // The reason this exists: a regex alone passes both of these.
    expect(isDate('2026-13-99')).toBe(false);
    expect(isDate('2026-02-30')).toBe(false);
  });

  it('rejects rubbish without throwing', () => {
    expect(isDate('')).toBe(false);
    expect(isDate(undefined)).toBe(false);
    expect(isDate('tomorrow')).toBe(false);
  });
});

describe('clampToSeason', () => {
  it('pins a date inside the season', () => {
    expect(clampToSeason('2026-08-01', '2026-09-01', '2026-11-15')).toBe('2026-09-01');
    expect(clampToSeason('2026-12-01', '2026-09-01', '2026-11-15')).toBe('2026-11-15');
    expect(clampToSeason('2026-10-06', '2026-09-01', '2026-11-15')).toBe('2026-10-06');
  });

  it('passes through when the season is not loaded yet', () => {
    expect(clampToSeason('2026-10-06', null, null)).toBe('2026-10-06');
  });
});

describe('isoToday', () => {
  it('is the local calendar date, not the UTC one', () => {
    // The bug this replaced: toISOString() is UTC, and every US timezone is
    // behind it, so an evening visitor was offered tomorrow.
    const now = new Date();
    const expected = [
      now.getFullYear(),
      String(now.getMonth() + 1).padStart(2, '0'),
      String(now.getDate()).padStart(2, '0'),
    ].join('-');
    expect(isoToday()).toBe(expected);
  });
});

describe('the forecast horizon', () => {
  it('is 16 days out, matching ADR-0005', () => {
    expect(FORECAST_HORIZON_DAYS).toBe(16);
    expect(daysBetween(isoToday(), horizonDate())).toBe(16);
  });
});
