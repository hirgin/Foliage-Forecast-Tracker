import { describe, it, expect } from 'vitest';
import { latLngToCell, gridDisk, gridRing } from 'h3-js';
import { donorsFor, fillValue, MAX_RING } from './neighbourFill';

const at = (lat, lon) => latLngToCell(lat, lon, 6);

describe('donorsFor', () => {
  it('takes the immediate neighbours when there are any', () => {
    const centre = at(41.9, -93.5);
    const ring1 = gridDisk(centre, 1).filter((h) => h !== centre);
    const donors = donorsFor([centre], new Set(ring1));

    expect(donors.get(centre)).toHaveLength(ring1.length);
  });

  it('stops at the first ring that finds anything', () => {
    // A cell with one neighbour touching it and a crowd two steps away should
    // read as the neighbour it touches, not as an average of the crowd. This
    // is the difference between "the woods next door" and "somewhere in this
    // part of the state".
    const centre = at(41.9, -93.5);
    const near = gridDisk(centre, 1).filter((h) => h !== centre)[0];
    const far = gridDisk(centre, 2).filter((h) => !gridDisk(centre, 1).includes(h));

    const donors = donorsFor([centre], new Set([near, ...far]));
    expect(donors.get(centre)).toEqual([near]);
  });

  it('gives up rather than reaching across open country', () => {
    // Nothing within MAX_RING is roughly 12 km away. A cell that far from any
    // forest is in the middle of the plains, and colouring it from there would
    // be extrapolation rather than a local reading.
    const centre = at(41.9, -93.5);
    const distant = at(44.5, -72.7); // Vermont, half a continent off
    const donors = donorsFor([centre], new Set([distant]));

    expect(donors.has(centre)).toBe(false);
  });

  it('searches no further than MAX_RING', () => {
    const centre = at(41.9, -93.5);
    const justOutside = gridDisk(centre, MAX_RING + 1)
      .filter((h) => !gridDisk(centre, MAX_RING).includes(h));

    expect(donorsFor([centre], new Set(justOutside)).has(centre)).toBe(false);
    expect(donorsFor([centre], new Set(justOutside), MAX_RING + 1).has(centre)).toBe(true);
  });
});

describe('fillValue', () => {
  const byH3 = new Map([
    ['a', { progression: 20, stage: 'PATCHY' }],
    ['b', { progression: 60, stage: 'PARTIAL' }],
    ['c', { progression: null, stage: null }],
  ]);

  it('averages the donors that have a forecast', () => {
    expect(fillValue(['a', 'b'], byH3)).toBe(40);
  });

  it('skips donors with no forecast rather than counting them as zero', () => {
    // The failure this guards against drags whole regions green: a neighbour
    // that has not been scored yet knows nothing, and averaging it in as 0
    // would read as "no change" across everywhere still loading.
    expect(fillValue(['a', 'b', 'c'], byH3)).toBe(40);
  });

  it('returns nothing when no donor has a forecast', () => {
    expect(fillValue(['c'], byH3)).toBeNull();
    expect(fillValue([], byH3)).toBeNull();
  });

  it('is a mean and not a vote, so it lands between stages', () => {
    // Three quiet neighbours and one advanced one should not snap to the
    // majority; the point of averaging progression is a continuous result.
    const mixed = new Map([
      ['p', { progression: 10, stage: 'PATCHY' }],
      ['q', { progression: 10, stage: 'PATCHY' }],
      ['r', { progression: 10, stage: 'PATCHY' }],
      ['s', { progression: 90, stage: 'PAST_PEAK' }],
    ]);
    expect(fillValue(['p', 'q', 'r', 's'], mixed)).toBe(30);
  });
});
