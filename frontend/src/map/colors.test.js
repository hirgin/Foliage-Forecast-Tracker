import { describe, it, expect } from 'vitest';
import {
  STAGES,
  stageColor,
  stageLabel,
  progressionColor,
  foliageColor,
  canopyColor,
} from './colors';

const PEAK = [204, 62, 44];
const NEAR_PEAK = [224, 124, 42];

describe('progressionColor', () => {
  it('sits on the stage anchor at the bottom of its band', () => {
    // NEAR_PEAK spans 55-75, so 55 is exactly the anchor.
    expect(progressionColor(55, 'NEAR_PEAK')).toEqual(NEAR_PEAK);
  });

  it('reaches the next stage anchor at the top of its band', () => {
    // 75 is the boundary: NEAR_PEAK has blended fully into PEAK.
    expect(progressionColor(75, 'NEAR_PEAK')).toEqual(PEAK);
  });

  it('is continuous across a stage boundary', () => {
    // The top of one band and the bottom of the next must agree, or the map
    // shows banding artefacts where none exist in the data.
    expect(progressionColor(75, 'NEAR_PEAK')).toEqual(progressionColor(75, 'PEAK'));
    expect(progressionColor(55, 'PARTIAL')).toEqual(progressionColor(55, 'NEAR_PEAK'));
    expect(progressionColor(30, 'PATCHY')).toEqual(progressionColor(30, 'PARTIAL'));
  });

  it('distinguishes two cells inside the same stage', () => {
    // THE REGRESSION. Northern Vermont scores 85.4 and the south 79.2 --
    // both PEAK. Flat-filling the stage rendered the whole state one red and
    // hid a real north-to-south march. These must not be equal.
    const south = progressionColor(79.2, 'PEAK');
    const north = progressionColor(85.4, 'PEAK');
    expect(north).not.toEqual(south);
  });

  it('advances monotonically through a band', () => {
    const samples = [75, 79, 83, 87, 90].map((p) => progressionColor(p, 'PEAK'));
    for (let i = 1; i < samples.length; i++) {
      expect(samples[i]).not.toEqual(samples[i - 1]);
    }
  });

  it('deepens rather than jumping in the final stage', () => {
    // PAST_PEAK has no successor to blend toward, so it darkens instead.
    const start = progressionColor(90, 'PAST_PEAK');
    const end = progressionColor(100, 'PAST_PEAK');
    expect(end.every((c, i) => c <= start[i])).toBe(true);
    expect(end).not.toEqual(start);
  });

  it('clamps progression outside the band rather than extrapolating', () => {
    // Out-of-band values must not produce colours outside the ramp.
    expect(progressionColor(-20, 'PEAK')).toEqual(progressionColor(75, 'PEAK'));
    expect(progressionColor(999, 'PEAK')).toEqual(progressionColor(90, 'PEAK'));
  });

  it('returns a neutral grey for an unknown stage', () => {
    expect(progressionColor(50, 'NOT_A_STAGE')).toEqual([70, 66, 60]);
  });

  it('always returns three integer channels in range', () => {
    for (const stage of STAGES) {
      for (let p = 0; p <= 100; p += 3) {
        const c = progressionColor(p, stage.key);
        expect(c).toHaveLength(3);
        c.forEach((ch) => {
          expect(Number.isInteger(ch)).toBe(true);
          expect(ch).toBeGreaterThanOrEqual(0);
          expect(ch).toBeLessThanOrEqual(255);
        });
      }
    }
  });
});

describe('foliageColor', () => {
  it('carries confidence in the alpha channel', () => {
    const certain = foliageColor({ progression: 80, stage: 'PEAK', confidence: 1 });
    const vague = foliageColor({ progression: 80, stage: 'PEAK', confidence: 0.4 });
    expect(certain[3]).toBeGreaterThan(vague[3]);
    // The three colour channels must be identical: confidence is not allowed
    // to masquerade as a different stage.
    expect(certain.slice(0, 3)).toEqual(vague.slice(0, 3));
  });

  it('keeps low-confidence cells legible', () => {
    // A climatological October is a weak claim, not an invisible one.
    const [, , , alpha] = foliageColor({ progression: 80, stage: 'PEAK', confidence: 0 });
    expect(alpha).toBeGreaterThan(100);
  });

  it('treats missing confidence as certain', () => {
    const a = foliageColor({ progression: 80, stage: 'PEAK' });
    const b = foliageColor({ progression: 80, stage: 'PEAK', confidence: 1 });
    expect(a).toEqual(b);
  });
});

describe('stage metadata', () => {
  it('names every stage the backend can emit', () => {
    const expected = ['NO_CHANGE', 'PATCHY', 'PARTIAL', 'NEAR_PEAK', 'PEAK', 'PAST_PEAK'];
    expect(STAGES.map((s) => s.key)).toEqual(expected);
    expected.forEach((k) => expect(stageLabel(k)).not.toBe('Unknown'));
  });

  it('falls back rather than throwing on an unknown stage', () => {
    expect(stageLabel('WAT')).toBe('Unknown');
    expect(stageColor('WAT')).toEqual([70, 66, 60]);
  });
});

describe('canopyColor', () => {
  it('is a monotonic ramp with density', () => {
    const light = canopyColor(10);
    const dense = canopyColor(90);
    expect(dense[1]).toBeGreaterThan(light[1]); // greener
  });

  it('distinguishes unsampled from bare ground', () => {
    // Zero canopy is a real measurement; null means off-raster. Rendering
    // them the same would paint lakes as bare forest.
    expect(canopyColor(null)).not.toEqual(canopyColor(0));
  });
});

describe('telling peak from past peak', () => {
  // Relative luminance, the brightness a viewer perceives. Colour-blind or
  // not, this is the channel that still works when hue does not.
  const luminance = ([r, g, b]) => 0.2126 * r + 0.7152 * g + 0.0722 * b;

  it('separates them by brightness, not only by hue', () => {
    // The bug this guards: peak red and past-peak brown had luminances of
    // 90.9 and 88.5, so they differed only in colourfulness. On a small
    // hexagon at 45-70% alpha over a dark basemap, that reads as one colour.
    const peak = stageColor('PEAK');
    const past = stageColor('PAST_PEAK');
    expect(Math.abs(luminance(peak) - luminance(past))).toBeGreaterThan(8);
  });

  it('keeps past peak the duller of the two', () => {
    // Semantics as well as legibility: past peak is leaves going down, and it
    // should never look more vivid than peak itself.
    const chroma = ([r, g, b]) => Math.max(r, g, b) - Math.min(r, g, b);
    expect(chroma(stageColor('PAST_PEAK'))).toBeLessThan(chroma(stageColor('PEAK')));
  });

  it('still reads as a continuous ramp out of peak', () => {
    // Separating them must not reintroduce a hard step, which would hide the
    // progression within the peak band that the interpolation exists for.
    const samples = [75, 80, 85, 90].map((p) => progressionColor(p, 'PEAK'));
    samples.slice(1).forEach((c, i) => {
      const previous = samples[i];
      const jump = Math.max(...[0, 1, 2].map((k) => Math.abs(c[k] - previous[k])));
      expect(jump).toBeLessThan(60);
    });
  });

  it('darkens steadily through the peak band', () => {
    // With peak now lasting a week, where a cell sits inside the band is
    // information. It should get visibly closer to past peak, not jump.
    const early = luminance(progressionColor(76, 'PEAK'));
    const late = luminance(progressionColor(89, 'PEAK'));
    expect(late).toBeLessThan(early);
  });
});

describe('cells with no forecast yet', () => {
  it('is drawn distinctly from a scored cell', () => {
    // The bug this guards: a cell awaiting data was drawn at [70, 66, 60] and
    // 45% alpha over a near-black basemap, which is indistinguishable from
    // empty ground. Most of the country looks like this until the backfill
    // finishes, so a waiting grid read as a broken one.
    const pending = foliageColor({ progression: null, stage: null, confidence: 0 });
    const scored = foliageColor({ progression: 80, stage: 'PEAK', confidence: 1 });
    expect(pending).not.toEqual(scored);
    expect(pending[3]).toBeGreaterThan(100);
  });

  it('is not drawn as a score of zero', () => {
    // "Not computed yet" and "no colour change yet" are different claims and
    // must not share a colour.
    const pending = foliageColor({ progression: null, stage: null, confidence: 0 });
    const noChange = foliageColor({ progression: 0, stage: 'NO_CHANGE', confidence: 1 });
    expect(pending.slice(0, 3)).not.toEqual(noChange.slice(0, 3));
  });

  it('stays subordinate to the stage colours', () => {
    // It covers most of the map right now, so it must not shout over the
    // cells that actually carry a forecast.
    const chroma = ([r, g, b]) => Math.max(r, g, b) - Math.min(r, g, b);
    const pending = foliageColor({ progression: null, stage: null, confidence: 0 });
    expect(chroma(pending)).toBeLessThan(chroma(stageColor('PEAK')));
  });

  it('does not look like ground with no data', () => {
    // The bug this guards: past peak was darkened to separate it from red and
    // lost its colour doing so, landing at chroma 37 beside the "not
    // forecast" grey's 6. Over a dark basemap a whole state past peak then
    // read as missing rather than turning.
    const chroma = ([r, g, b]) => Math.max(r, g, b) - Math.min(r, g, b);
    const past = chroma(stageColor('PAST_PEAK'));
    const pending = chroma(foliageColor({ progression: null, stage: null, confidence: 0 }));
    expect(past).toBeGreaterThan(pending * 4);
  });
});
