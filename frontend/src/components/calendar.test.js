import { describe, it, expect } from 'vitest';
import {
  startOfMonth, addMonths, monthGrid, pickRange, previewRange,
  within, nightsIn, openingMonth, stepDay, WEEKDAYS,
} from './calendar';

describe('monthGrid', () => {
  it('lays a month out in whole weeks starting Sunday', () => {
    const g = monthGrid('2026-10-14');
    expect(g.weeks.every((w) => w.length === 7)).toBe(true);
    expect(WEEKDAYS).toHaveLength(7);
    // 1 Oct 2026 is a Thursday, so the first week carries four nulls.
    expect(g.weeks[0].slice(0, 4)).toEqual([null, null, null, null]);
    expect(g.weeks[0][4]).toBe('2026-10-01');
  });

  it('holds every day of the month exactly once', () => {
    const days = monthGrid('2026-10-01').weeks.flat().filter(Boolean);
    expect(days).toHaveLength(31);
    expect(new Set(days).size).toBe(31);
    expect(days[30]).toBe('2026-10-31');
  });

  it('gets February right, including a leap year', () => {
    expect(monthGrid('2026-02-01').weeks.flat().filter(Boolean)).toHaveLength(28);
    expect(monthGrid('2028-02-01').weeks.flat().filter(Boolean)).toHaveLength(29);
  });

  it('pads only with nulls, never a neighbouring month', () => {
    // A grid holding dates it will not let you pick has to be explained; one
    // that holds blanks does not.
    const g = monthGrid('2026-09-01');
    const stray = g.weeks.flat().filter((d) => d && !d.startsWith('2026-09'));
    expect(stray).toEqual([]);
  });
});

describe('addMonths', () => {
  it('steps within a year', () => {
    expect(addMonths('2026-09-14', 1)).toBe('2026-10-01');
    expect(addMonths('2026-10-01', -1)).toBe('2026-09-01');
  });

  it('crosses a year boundary in both directions', () => {
    expect(addMonths('2026-12-01', 1)).toBe('2027-01-01');
    expect(addMonths('2027-01-01', -1)).toBe('2026-12-01');
  });

  it('does not land on a day that does not exist', () => {
    // The reason this returns the first of the month rather than keeping the
    // day: stepping back a month from 31 October has no 31st to land on.
    expect(addMonths('2026-10-31', -1)).toBe('2026-09-01');
  });
});

describe('pickRange', () => {
  it('opens a range on the first click', () => {
    expect(pickRange(null, '2026-10-05'))
      .toEqual({ from: '2026-10-05', to: '2026-10-05', picking: true });
  });

  it('closes it on the second', () => {
    const open = pickRange(null, '2026-10-05');
    expect(pickRange(open, '2026-10-09'))
      .toEqual({ from: '2026-10-05', to: '2026-10-09', picking: false });
  });

  it('starts again when the second click is earlier than the first', () => {
    // Changing your mind about where the trip starts, not asking for a stay
    // that runs backwards -- which the trip model would drop anyway.
    const open = pickRange(null, '2026-10-05');
    expect(pickRange(open, '2026-10-01'))
      .toEqual({ from: '2026-10-01', to: '2026-10-01', picking: true });
  });

  it('begins a fresh range once one is complete', () => {
    const done = { from: '2026-10-05', to: '2026-10-09', picking: false };
    expect(pickRange(done, '2026-10-20'))
      .toEqual({ from: '2026-10-20', to: '2026-10-20', picking: true });
  });

  it('allows a stay of a single day', () => {
    const open = pickRange(null, '2026-10-05');
    expect(pickRange(open, '2026-10-05'))
      .toEqual({ from: '2026-10-05', to: '2026-10-05', picking: false });
  });
});

describe('previewRange', () => {
  it('follows the pointer while the range is open', () => {
    const open = { from: '2026-10-05', to: '2026-10-05', picking: true };
    expect(previewRange(open, '2026-10-08')).toEqual({ from: '2026-10-05', to: '2026-10-08' });
  });

  it('ignores a pointer before the open end', () => {
    const open = { from: '2026-10-05', to: '2026-10-05', picking: true };
    expect(previewRange(open, '2026-10-01')).toEqual({ from: '2026-10-05', to: '2026-10-05' });
  });

  it('leaves a settled range alone', () => {
    const done = { from: '2026-10-05', to: '2026-10-09', picking: false };
    expect(previewRange(done, '2026-10-20')).toEqual({ from: '2026-10-05', to: '2026-10-09' });
  });
});

describe('within', () => {
  it('covers both ends of the range', () => {
    expect(within('2026-10-05', '2026-10-05', '2026-10-09')).toBe(true);
    expect(within('2026-10-09', '2026-10-05', '2026-10-09')).toBe(true);
    expect(within('2026-10-07', '2026-10-05', '2026-10-09')).toBe(true);
  });

  it('excludes anything outside it, and survives a half-set range', () => {
    expect(within('2026-10-04', '2026-10-05', '2026-10-09')).toBe(false);
    expect(within('2026-10-05', null, '2026-10-09')).toBe(false);
    expect(within(null, '2026-10-05', '2026-10-09')).toBe(false);
  });
});

describe('nightsIn', () => {
  it('counts nights, not days', () => {
    expect(nightsIn('2026-10-05', '2026-10-05')).toBe(0);
    expect(nightsIn('2026-10-05', '2026-10-09')).toBe(4);
  });
});

describe('openingMonth', () => {
  const min = '2026-09-01';
  const max = '2026-12-15';

  it('opens where the trip already is', () => {
    expect(openingMonth('2026-10-20', min, max)).toBe('2026-10-01');
  });

  it('falls back to the season start when there is nothing to open on', () => {
    expect(openingMonth(null, min, max)).toBe('2026-09-01');
    expect(openingMonth('2025-01-01', min, max)).toBe('2026-09-01');
  });
});

describe('stepDay', () => {
  const min = '2026-09-01';
  const max = '2026-12-15';

  it('moves a day at a time', () => {
    expect(stepDay('2026-10-05', 1, min, max)).toBe('2026-10-06');
    expect(stepDay('2026-10-05', -7, min, max)).toBe('2026-09-28');
  });

  it('stops at the edges of the season rather than leaving it', () => {
    expect(stepDay(min, -1, min, max)).toBe(min);
    expect(stepDay(max, 1, min, max)).toBe(max);
  });
});
