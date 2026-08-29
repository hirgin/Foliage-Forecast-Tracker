/**
 * Place search over the exported index.
 *
 * The payload is parallel arrays rather than objects, for the same reason the
 * daily forecasts are packed: repeating five keys per entry roughly triples
 * the file. Searching walks the arrays directly instead of materialising
 * thousands of objects on every keystroke.
 */

/**
 * Ranking weight per kind. A town is usually what someone means when they type
 * a name, but a named mountain outranks a hamlet — and both outrank the
 * thousand-odd minor summits the mountain code also captures.
 */
const KIND_RANK = { TOWN: 5, PARK: 4, MOUNTAIN: 3, NOTCH: 2, FOREST: 1 };

export const KIND_LABEL = {
  TOWN: 'Town',
  PARK: 'Park',
  MOUNTAIN: 'Mountain',
  NOTCH: 'Notch',
  FOREST: 'Forest',
};

const normalise = (s) => s.toLowerCase().replace(/[^a-z0-9 ]/g, '');

/**
 * Ranked matches for a query.
 *
 * Scoring, highest first:
 *   - an exact name match
 *   - a match starting at the beginning of the name
 *   - a match starting at any word boundary ("mansfield" finds Mount Mansfield)
 *   - anything else containing the query
 *
 * Population only breaks ties within a band. Sorting by it first would bury
 * Stowe under every larger place that happens to contain the same letters,
 * and small towns are the whole point of this dataset.
 */
export function searchPlaces(places, query, limit = 8) {
  if (!places || !query) return [];
  const q = normalise(query.trim());
  if (q.length < 2) return [];

  const results = [];

  for (let i = 0; i < places.count; i++) {
    const name = normalise(places.name[i]);
    const at = name.indexOf(q);
    if (at < 0) continue;

    let score;
    if (name === q) score = 4000;
    else if (at === 0) score = 3000;
    else if (name[at - 1] === ' ') score = 2000;
    else score = 1000;

    score += (KIND_RANK[places.kind[i]] ?? 0) * 100;
    // Compressed so a city of a million cannot outweigh a better name match.
    score += Math.min(90, Math.log10(Math.max(1, places.population[i])) * 15);
    // Shorter names are more likely to be the thing meant: "Stowe" over
    // "Stowe Hollow" for the query "stowe".
    score -= Math.min(50, name.length);

    results.push({
      index: i,
      name: places.name[i],
      state: places.state[i],
      kind: places.kind[i],
      population: places.population[i],
      cell: places.cell[i],
      // True when the forecast comes from a nearby forested cell rather than
      // the place's own ground -- a city centre with too few trees to score.
      nearby: places.nearby?.[i] ?? false,
      lat: places.lat[i],
      lon: places.lon[i],
      score,
    });
  }

  results.sort((a, b) => b.score - a.score || a.name.localeCompare(b.name));
  return results.slice(0, limit);
}

/** "Stowe, VT · Town" — the subtitle under a result. */
export function describePlace(place) {
  const kind = KIND_LABEL[place.kind] ?? place.kind;
  // For a place whose own ground has too few trees to score, the note
  // replaces the kind rather than following it. Both together did not fit the
  // panel and truncated to "MA - Town - ne...", which says less than either
  // alone -- and that the forecast is from the woods nearby matters more than
  // that the place is a town.
  if (place.nearby) return place.state ? `${place.state} · nearest woods` : 'nearest woods';
  return place.state ? `${place.state} · ${kind}` : kind;
}
