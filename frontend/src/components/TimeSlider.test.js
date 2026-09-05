import { describe, it, expect } from 'vitest';
import { horizonCollides, HORIZON_CLEARANCE_PCT } from './TimeSlider';

/**
 * The "forecast ends" marker sits at today plus sixteen days, so it drifts
 * across the track as the season runs and lands on a month label a few days
 * in every month. When it does, the wording is dropped and the dashed line
 * stays.
 */
describe('horizon marker crowding', () => {
  // Season 1 Sep to 15 Dec is 105 days. Month ticks fall where those dates do.
  const pctOf = (dayIndex) => (dayIndex / 105) * 100;
  const monthPcts = [pctOf(30), pctOf(61), pctOf(91)]; // 1 Oct, 1 Nov, 1 Dec

  it('hides the wording when it would run into a month label', () => {
    // 4 September: the horizon is 20 September, day 19, which lands 10.5% from
    // the October tick. That is what read as "forecast endsOct" on the map,
    // and a 10% clearance was not enough to catch it.
    const horizon = pctOf(19);
    expect(Math.abs(monthPcts[0] - horizon)).toBeLessThan(HORIZON_CLEARANCE_PCT);
    expect(horizonCollides(horizon, monthPcts)).toBe(true);
  });

  it('keeps the wording when there is room', () => {
    // Mid-month, the horizon sits clear of both neighbouring ticks.
    const horizon = pctOf(45);
    expect(horizonCollides(horizon, monthPcts)).toBe(false);
  });

  it('clears both labels, not only its own width', () => {
    // "forecast ends" is about 75 px and a month label another 20, so the gap
    // has to cover the pair. A clearance sized to one of them lets them touch.
    expect(HORIZON_CLEARANCE_PCT).toBeGreaterThan(11);
  });

  it('is quiet when there is no horizon at all', () => {
    expect(horizonCollides(null, monthPcts)).toBe(false);
  });
});
