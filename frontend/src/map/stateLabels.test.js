import { describe, it, expect } from 'vitest';
import {
  stateLabelField,
  labelAt,
  fullNameZoom,
  stateWidthPx,
  nameWidthPx,
  FULL_NAME_ZOOM,
  STATE_ABBR,
  STATE_SPAN_DEG,
  NATIONAL_CODE_ZOOM,
} from './stateLabels';

/**
 * State labels have to sit inside the state they name. At the national view
 * they did not: the anchors were correct all along, but "CONNECTICUT" is far
 * wider than Connecticut, so a correctly centred label still spilled into New
 * York and the sea.
 */
describe('state labels', () => {
  const CONUS = Object.keys(STATE_ABBR);

  it('covers every contiguous state', () => {
    // 48 states. A missing entry renders the literal word "undefined" on the
    // map, and a missing span silently defaults the state to the national
    // threshold rather than failing.
    expect(CONUS).toHaveLength(48);
    for (const state of CONUS) {
      expect(STATE_ABBR[state]).toMatch(/^[A-Z]{2}$/);
      expect(STATE_SPAN_DEG[state]).toBeGreaterThan(0);
    }
  });

  it('shows a code for every state at the national view', () => {
    // The point of the design. A national view that spells out Texas while
    // Rhode Island cannot fit any name at all reads as broken rather than as
    // adaptive, so below the floor nothing is spelled out.
    for (const state of CONUS) {
      expect(labelAt(state, 3.7)).toBe(STATE_ABBR[state]);
    }
  });

  it('spells every state out once it is wide enough', () => {
    // The other half of the claim: codes are a small-scale accommodation, not
    // the permanent labelling.
    for (const state of CONUS) {
      expect(labelAt(state, FULL_NAME_ZOOM[state])).toBe(state);
    }
  });

  it('never spells a name out before it fits', () => {
    // The regression that started all this. At its own threshold the name
    // must actually fit inside the state's usable width.
    for (const state of CONUS) {
      const zoom = FULL_NAME_ZOOM[state];
      const usable = stateWidthPx(state, zoom) * 0.55;
      expect(usable).toBeGreaterThanOrEqual(nameWidthPx(state) - 0.5);
    }
  });

  it('makes narrower states wait longer than wider ones', () => {
    // Derived from each state's own width rather than picked by eye, so this
    // ordering should fall out rather than need maintaining.
    expect(fullNameZoom('Rhode Island')).toBeGreaterThan(fullNameZoom('Connecticut'));
    expect(fullNameZoom('Connecticut')).toBeGreaterThan(fullNameZoom('Pennsylvania'));
    expect(fullNameZoom('Texas')).toBe(NATIONAL_CODE_ZOOM);
  });

  it('holds the smallest states to the largest thresholds', () => {
    // Rhode Island and Delaware are about 7 px wide nationally. Nothing can
    // put their names inside them there, and the threshold has to say so.
    expect(stateWidthPx('Rhode Island', 3.7)).toBeLessThan(10);
    expect(stateWidthPx('Delaware', 3.7)).toBeLessThan(10);
    expect(fullNameZoom('Rhode Island')).toBeGreaterThan(8);
  });

  describe('the MapLibre expression', () => {
    // Evaluating the real expression rather than trusting labelAt to mirror
    // it. The expression is what the map draws; the mirror is a convenience.
    const evaluate = (expr, name, zoom) => {
      if (!Array.isArray(expr)) return expr;
      const [op] = expr;
      if (op === 'coalesce' || op === 'get') return name;
      if (op === 'zoom') return zoom;
      if (op === 'step') {
        const [, , fallback, ...rest] = expr;
        let chosen = fallback;
        for (let i = 0; i < rest.length; i += 2) if (zoom >= rest[i]) chosen = rest[i + 1];
        return evaluate(chosen, name, zoom);
      }
      if (op === 'match') {
        const [, , ...rest] = expr;
        for (let i = 0; i < rest.length - 1; i += 2) {
          if (rest[i] === name) return evaluate(rest[i + 1], name, zoom);
        }
        return evaluate(rest[rest.length - 1], name, zoom);
      }
      throw new Error(`unhandled op ${op}`);
    };

    it('agrees with labelAt for every state at every threshold boundary', () => {
      const expr = stateLabelField();
      const boundaries = [...new Set(Object.values(FULL_NAME_ZOOM))].flatMap((z) => [z - 0.01, z]);
      const zooms = [3, 3.7, 4.49, ...boundaries, 12];
      for (const state of CONUS) {
        for (const zoom of zooms) {
          expect(evaluate(expr, state, zoom)).toBe(labelAt(state, zoom));
        }
      }
    });

    it('uses a single zoom-based subexpression', () => {
      // MapLibre rejects more than one, and the rejection takes the whole map
      // down rather than degrading. Nesting steps is the obvious way to write
      // this and does exactly that.
      const expr = stateLabelField();
      const countZoomSteps = (node) => {
        if (!Array.isArray(node)) return 0;
        const isZoomStep = node[0] === 'step' && Array.isArray(node[1]) && node[1][0] === 'zoom';
        return (isZoomStep ? 1 : 0) + node.slice(1).reduce((a, c) => a + countZoomSteps(c), 0);
      };
      expect(countZoomSteps(expr)).toBe(1);
    });
  });
});
