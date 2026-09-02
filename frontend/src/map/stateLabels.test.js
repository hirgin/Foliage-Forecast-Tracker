import { describe, it, expect } from 'vitest';
import { stateLabelField, labelAt, FULL_NAME_ZOOM, STATE_ABBR } from './stateLabels';

/**
 * State labels have to sit inside the state they name. At the national view
 * they did not: the anchors were correct all along, but "CONNECTICUT" is six
 * times wider than Connecticut, so a correctly centred label still spilled
 * into New York and the sea.
 */
describe('state labels', () => {
  it('abbreviates the crowded states at the default national view', () => {
    // Zoom 3.5 is roughly what the map opens at. Every one of these is
    // narrower than its own name here.
    for (const state of Object.keys(FULL_NAME_ZOOM)) {
      expect(labelAt(state, 3.5)).toBe(STATE_ABBR[state]);
    }
  });

  it('leaves states that already fit completely alone', () => {
    // The change must not touch the rest of the map. Most states have room
    // for their name at every zoom the label is drawn at, and abbreviating
    // them would make the map worse to read, not better.
    expect(labelAt('Texas', 3.5)).toBe('Texas');
    expect(labelAt('California', 3.5)).toBe('California');
    expect(labelAt('Minnesota', 3.5)).toBe('Minnesota');
    expect(labelAt('Pennsylvania', 3.5)).toBe('Pennsylvania');
  });

  it('gives each state its full name once there is room', () => {
    expect(labelAt('Vermont', 6)).toBe('Vermont');
    expect(labelAt('Vermont', 5.9)).toBe('VT');
    expect(labelAt('Rhode Island', 8)).toBe('Rhode Island');
    expect(labelAt('Rhode Island', 7.9)).toBe('RI');
  });

  it('makes smaller states wait longer than larger ones', () => {
    // The thresholds are per state because "too small" is a property of the
    // state, not of the map. A single global cutoff would spill Rhode Island
    // or needlessly abbreviate Maryland.
    expect(FULL_NAME_ZOOM['Rhode Island']).toBeGreaterThan(FULL_NAME_ZOOM.Connecticut);
    expect(FULL_NAME_ZOOM.Connecticut).toBeGreaterThan(FULL_NAME_ZOOM.Maryland);
  });

  it('has an abbreviation for every state it abbreviates', () => {
    // A missing entry would render the literal word "undefined" on the map.
    for (const state of Object.keys(FULL_NAME_ZOOM)) {
      expect(STATE_ABBR[state]).toMatch(/^[A-Z]{2}$/);
    }
  });

  describe('the MapLibre expression', () => {
    // Evaluating the real expression, rather than trusting labelAt to be a
    // faithful mirror of it. The expression is what the map draws; the mirror
    // is only a convenience for reasoning.
    const evaluate = (expr, name, zoom) => {
      if (!Array.isArray(expr)) return expr;
      const [op] = expr;
      if (op === 'coalesce') return name;
      if (op === 'get') return name;
      if (op === 'zoom') return zoom;
      if (op === 'step') {
        const [, , fallback, ...rest] = expr;
        let chosen = fallback;
        for (let i = 0; i < rest.length; i += 2) {
          if (zoom >= rest[i]) chosen = rest[i + 1];
        }
        return evaluate(chosen, name, zoom);
      }
      if (op === 'match') {
        const [, , ...rest] = expr;
        const otherwise = rest[rest.length - 1];
        for (let i = 0; i < rest.length - 1; i += 2) {
          if (rest[i] === name) return evaluate(rest[i + 1], name, zoom);
        }
        return evaluate(otherwise, name, zoom);
      }
      throw new Error(`unhandled op ${op}`);
    };

    it('agrees with labelAt at every threshold boundary', () => {
      const expr = stateLabelField();
      const zooms = [3, 3.5, 4, 5, 5.9, 6, 6.5, 7, 7.5, 8, 10];
      const states = [...Object.keys(FULL_NAME_ZOOM), 'Texas', 'Ohio'];
      for (const state of states) {
        for (const zoom of zooms) {
          expect(evaluate(expr, state, zoom)).toBe(labelAt(state, zoom));
        }
      }
    });
  });
});
