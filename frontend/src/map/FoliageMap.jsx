import { useEffect, useMemo, useRef, useState } from 'react';
import maplibregl from 'maplibre-gl';
import { MapboxOverlay } from '@deck.gl/mapbox';
import { H3HexagonLayer } from '@deck.gl/geo-layers';
import { cellToLatLng } from 'h3-js';
import { NO_FOREST_RGB, NO_FOREST_ALPHA, progressionColor, stageForProgression } from './colors';
import { donorsFor, fillValue } from './neighbourFill';
import { bucketByAncestor, ancestorsInView, cellsInView } from './viewport';
import 'maplibre-gl/dist/maplibre-gl.css';
import { foliageColor, stageLabel } from './colors';

/**
 * Vector basemap, with the hexagons drawn *inside* its layer stack.
 *
 * The previous version stacked raster tiles: terrain below, hexagons, then a
 * second raster layer of labels on top. Labels were never sharp, and extra
 * tile resolution did not fix it -- raster text pushed through a WebGL texture
 * has no subpixel antialiasing and lands at non-integer scales, so fetching a
 * bigger image only produces a bigger blurry one.
 *
 * Vector tiles render glyphs natively at whatever the device pixel ratio is,
 * and arrive faster because a tile of label geometry is smaller than a PNG of
 * the same labels.
 *
 * The composition matters too, and it was backwards before: MapLibre is the
 * map and deck.gl is an overlay on it, not the reverse. Nesting a MapLibre
 * component inside DeckGL is why a style once silently failed to load.
 */
const STYLE_URL = 'https://tiles.openfreemap.org/styles/dark';

/**
 * Everything that is not the United States, as one polygon.
 *
 * A ring around the world with the country punched out of it as holes.
 * Rendered over the basemap, it hides Canada, Mexico, the Caribbean and the
 * oceans in a single fill, which is the only way to actually stop them being
 * drawn -- constraining the camera stops you *travelling* there but the
 * basemap still renders whatever is in frame, and this map is only ever about
 * the lower 48.
 *
 * Derived from the us-atlas national outline, which is built from the same
 * Census TIGER boundaries the grid bootstrap tiles states against, so the
 * coastline here and the coastline the hexagons stop at come from one source.
 * Trimmed to CONUS, rounded to four decimals -- about 11 m, far finer than a
 * 3 km hexagon -- and committed rather than fetched, because the border of the
 * United States does not change often enough to be worth a build step.
 */
import US_MASK from './us-mask.json';

/** Matches --bg in styles.css, so masked ground reads as page, not as sea. */
const MASK_FILL = '#0f0d0a';

/** Twice the signed area of a ring; positive when it winds counterclockwise. */
function signedArea(ring) {
  let total = 0;
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i, i += 1) {
    total += (ring[i][0] - ring[j][0]) * (ring[i][1] + ring[j][1]);
  }
  return -total;
}

/**
 * The country itself, as a polygon — the mask turned inside out.
 *
 * The mask's first ring is the world and the rest are the United States
 * punched out of it, so dropping the world ring and treating each remaining
 * ring as its own polygon gives the country. Derived rather than shipped
 * twice, so the two can never disagree.
 *
 * **Winding has to be corrected, not inherited.** In the mask those rings are
 * holes, so they wind clockwise; reused unchanged as exterior rings they
 * describe everything *except* the United States, and the `within` filter
 * built on them hid every label on the map rather than only the foreign ones.
 * Filling a polygon does not care about winding and testing a point against
 * one does, which is exactly the sort of difference that shows up as a blank
 * screen with no error.
 */
const US_OUTLINE = {
  type: 'MultiPolygon',
  coordinates: US_MASK.geometry.coordinates
    .slice(1)
    .map((ring) => [signedArea(ring) > 0 ? ring : [...ring].reverse()]),
};

/**
 * Hexagons are inserted beneath the first symbol layer, so all fifteen of the
 * style's label layers paint over them natively -- no second tile fetch, and
 * the style's own label collision handling still applies.
 */
const BEFORE_LAYER = 'water_name';

/**
 * The style ships muted grey labels, tuned for a bare dark map. Here they have
 * to stay readable over saturated foliage colours as well, so every text layer
 * is repainted white with a dark halo.
 *
 * The halo does the real work: white text alone disappears against a pale
 * near-peak hexagon. An outline keeps it legible over anything the ramp can
 * produce, dark ground included.
 */
/**
 * Label sizes, because the basemap's own hierarchy is upside down here.
 *
 * OpenFreeMap ships `place_state` at size 10 and `place_city_large` at 14, so
 * Chicago is drawn half again as large as ILLINOIS. That is a reasonable call
 * for a general-purpose map, where you are usually looking for a city. It is
 * the wrong one for a map of the whole country whose subject is regional: the
 * states are the frame you read the forecast against, and they should be the
 * largest thing on it.
 *
 * Cities are then ordered among themselves by `rank`, which OpenMapTiles
 * derives from importance -- 1 is a capital or a major metropolis, 10 is a
 * market town. Ranking them by size rather than giving every city one size
 * means Chicago still reads as bigger than Peoria without either competing
 * with the state it sits in.
 */
const STATE_LABEL_SIZE = ['interpolate', ['linear'], ['zoom'], 3, 13, 5, 16, 8, 20, 12, 24];

/** Falls back to a middling rank, since not every place carries one. */
const CITY_RANK = ['coalesce', ['get', 'rank'], 8];
const CITY_LABEL_SIZE = ['interpolate', ['linear'], CITY_RANK, 1, 12.5, 4, 11, 8, 9.5, 12, 8.5];

const SMALLER_PLACES = {
  place_town: 9.5,
  place_village: 9,
  place_suburb: 8.5,
  place_other: 8.5,
};

const LABEL_COLOR = '#ffffff';
const LABEL_HALO = 'rgba(6, 8, 5, 0.92)';
const LABEL_HALO_WIDTH = 1.8;

/**
 * State outlines, made visible.
 *
 * The style draws them at hsl(0, 0%, 21%) -- nearly black, which is right for
 * a bare dark basemap and wrong here. They sit above the hexagons, so a
 * near-black line over a saturated orange cell reads as a smudge rather than a
 * border, and on empty ground it disappears into the background entirely.
 *
 * A light line with real transparency works over both: bright enough to follow
 * against colour, soft enough not to draw the eye away from the data, which is
 * what the map is actually for. Country borders get the same treatment a shade
 * stronger, since there are far fewer of them.
 */
const STATE_BORDER = 'rgba(226, 232, 220, 0.5)';
const COUNTRY_BORDER = 'rgba(232, 238, 226, 0.65)';

// Slightly wider than the style's own at the zooms people actually use. Below
// about zoom 4 the whole country is on screen and heavier lines would box in
// the hexagons rather than frame them.
const BORDER_WIDTH = ['interpolate', ['exponential', 1.3], ['zoom'], 3, 0.8, 5, 1.2, 8, 1.8, 12, 3, 22, 15];

function brightenBoundaries(map) {
  for (const [id, color] of [
    ['boundary_state', STATE_BORDER],
    ['boundary_country_z0-4', COUNTRY_BORDER],
    ['boundary_country_z5-', COUNTRY_BORDER],
  ]) {
    // Guarded: the basemap style is fetched at runtime and is free to rename
    // its layers, and a missing one should not take the map down with it.
    if (!map.getLayer(id)) continue;
    map.setPaintProperty(id, 'line-color', color);
    map.setPaintProperty(id, 'line-width', BORDER_WIDTH);
  }
}

function brightenLabels(map) {
  for (const layer of map.getStyle().layers) {
    // Only layers that actually draw text; symbol layers also cover icons and
    // road shields, which have their own colouring and should be left alone.
    if (layer.type !== 'symbol' || !layer.layout?.['text-field']) continue;
    map.setPaintProperty(layer.id, 'text-color', LABEL_COLOR);
    map.setPaintProperty(layer.id, 'text-halo-color', LABEL_HALO);
    map.setPaintProperty(layer.id, 'text-halo-width', LABEL_HALO_WIDTH);
  }
}


/**
 * How far the map lets you wander: the lower 48, with a margin.
 *
 * This is a forecast for the continental US and nothing is scored outside it,
 * so panning to the Pacific or zooming out to the globe only ever shows empty
 * ocean and a country the size of a postage stamp. Fencing the view keeps
 * every gesture landing somewhere that has data.
 *
 * Drawn to the actual coastlines rather than padded out. Since these bounds
 * set how far you may zoom out -- the floor is whatever fits them -- every
 * degree of slack is spent widening the view, and on a tall phone that slack
 * is paid vertically at several times the rate: padding to -128/51 pulled the
 * horizon down to Panama before the country fitted across.
 */
const US_BOUNDS = [[-125.5, 24.0], [-66.5, 49.5]];

/**
 * Where the map opens.
 *
 * The country, because that is what the site is about. It used to be Stowe,
 * Vermont at zoom 7 -- correct when Vermont was the only state with data, and
 * quietly wrong from the day the second one landed. Every visitor arrived
 * zoomed into one hillside in New England and had to find the rest of the
 * United States for themselves.
 *
 * Set on the constructor rather than corrected after load, so there is no
 * first frame showing somewhere else.
 */
const OPENING_VIEW = US_BOUNDS;

/**
 * The basemap style with everything this map needs already in it.
 *
 * All of this used to be applied in the map's `load` handler, which is too
 * late by one frame -- and one frame is enough to see. The style loads, the
 * map paints the entire world in the basemap's own grey, and only then does
 * the mask go on and the rest of the planet vanish. Every visit opened with a
 * world map that flickered out.
 *
 * Fetching the style ourselves and handing MapLibre an object rather than a
 * URL closes the gap: the mask, the label filtering and the colours are all
 * present in the very first paint, so nothing outside the United States is
 * ever drawn at all. The style is fetched either way; this only decides who
 * fetches it.
 */
async function prepareStyle() {
  const style = await fetch(STYLE_URL).then((r) => r.json());

  const borders = {
    boundary_state: STATE_BORDER,
    'boundary_country_z0-4': COUNTRY_BORDER,
    'boundary_country_z5-': COUNTRY_BORDER,
  };

  for (const layer of style.layers) {
    if (borders[layer.id]) {
      layer.paint = { ...layer.paint, 'line-color': borders[layer.id], 'line-width': BORDER_WIDTH };
    }
    // Road shields have their own colouring and are left alone, as before.
    if (layer.type === 'symbol' && layer.layout?.['text-field']) {
      layer.paint = {
        ...layer.paint,
        'text-color': LABEL_COLOR,
        'text-halo-color': LABEL_HALO,
        'text-halo-width': LABEL_HALO_WIDTH,
      };
    }
    if (!layer.id.startsWith('place_')) continue;
    if (layer.id.startsWith('place_country')) {
      layer.layout = { ...layer.layout, visibility: 'none' };
      continue;
    }

    // Put the hierarchy the right way up: states largest, then cities ordered
    // among themselves by importance, then everything below them.
    if (layer.id === 'place_state') {
      layer.layout = { ...layer.layout, 'text-size': STATE_LABEL_SIZE };
    } else if (layer.id === 'place_city' || layer.id === 'place_city_large') {
      layer.layout = { ...layer.layout, 'text-size': CITY_LABEL_SIZE };
    } else if (SMALLER_PLACES[layer.id]) {
      layer.layout = { ...layer.layout, 'text-size': SMALLER_PLACES[layer.id] };
    }
    layer.filter = layer.filter
      ? ['all', layer.filter, ['within', US_OUTLINE]]
      : ['within', US_OUTLINE];
  }

  style.sources = { ...style.sources, 'outside-us': { type: 'geojson', data: US_MASK } };
  const firstPlaceLabel = style.layers.findIndex((l) => l.id.startsWith('place_'));
  style.layers.splice(firstPlaceLabel < 0 ? style.layers.length : firstPlaceLabel, 0, {
    id: 'outside-us',
    type: 'fill',
    source: 'outside-us',
    paint: { 'fill-color': MASK_FILL, 'fill-opacity': 1 },
  });

  return style;
}

/**
 * Stops the zoom out once the whole country is on screen.
 *
 * Paired with [US_BOUNDS]: bounds alone stop you panning away but not zooming
 * out, and at zoom 2 the US sits in the middle of a world map.
 *
 * This cannot be a constant, which is the mistake it replaces. A fixed floor
 * of 3 fits the country on a desktop pane and strands a phone halfway: a
 * 375 px viewport covers far less ground at the same zoom, so the eastern half
 * filled the screen and there was no way to pull back and see the rest. The
 * floor has to be whatever zoom fits [US_BOUNDS] in *this* viewport, which is
 * what cameraForBounds computes -- so it is recomputed whenever the map is
 * resized, including on a phone rotating.
 */
/**
 * How much further out than an exact fit you may pull, in zoom levels.
 *
 * A floor set to the exact fit is uncomfortable: the country touches all four
 * edges and the panel covers the west coast, so the whole of it is never
 * actually visible at once. 0.6 is about half as much ground again, which
 * clears the interface while keeping the country the subject of the frame.
 *
 * Fitting around the interface was tried instead of this and is worse. It is
 * more correct in principle -- pad by the panel, fit to what is left -- but on
 * a narrow window the panel is 40% of the width, so the fit solves for a
 * strip and the country comes out small and marooned. It also meant giving the
 * map a padding, which put getBounds and getCenter into different frames of
 * reference and sent clampCentre into infinite recursion.
 *
 * Headroom costs nothing to give: everything outside the country is masked, so
 * pulling back further reveals background rather than Canada.
 */
const ZOOM_HEADROOM = 0.6;

/**
 * The same allowance on a phone, where it mostly is not needed.
 *
 * On a wide screen the headroom is what clears the sidebar, because the fit
 * knows nothing about it. On a phone [chromePadding] has already fitted the
 * country to the space below the sheet, so the full allowance is spent
 * shrinking a map that is small to begin with -- 0.6 of a level is a third off
 * every dimension. Just enough to keep it off the edges.
 *
 * Measured rather than guessed: at 0.15 the view spanned 53.5 degrees against
 * a country 59 wide, so Washington and Maine were clipped off either side.
 * 0.4 spans 62 and leaves a margin.
 */
const NARROW_ZOOM_HEADROOM = 0.4;

/**
 * Width below which the panel stops being a sidebar and becomes a sheet across
 * the top of the screen. Mirrors the breakpoint in styles.css.
 */
const NARROW_PX = 620;

/**
 * How much of the map the interface is covering, on a phone.
 *
 * Only on a phone. On a wide screen the panel is a 262 px sidebar with the
 * country beside it, and padding for it there costs more than it gives: it is
 * 40% of a narrow window, so the fit solves for a strip and the country comes
 * out small and marooned. But on a phone the panel is a sheet across the whole
 * top, taking over half the height, and the map centres the country in the
 * full canvas -- which puts it squarely underneath.
 *
 * Measured from the panel rather than assumed, because it grows and shrinks
 * with its own content and collapses when the toggle is pressed.
 */
function chromePadding(map) {
  const canvas = map.getCanvas();
  const width = canvas.clientWidth;
  const height = canvas.clientHeight;
  if (width > NARROW_PX) return { top: 0, right: 0, bottom: 0, left: 0 };

  const panel = document.querySelector('.panel');
  const gap = 12;
  const top = panel ? Math.min(panel.getBoundingClientRect().bottom + gap, height * 0.62) : 0;

  // The slider sits at the bottom on every size, and the attribution under it.
  return { top: Math.max(top, 0), right: 0, bottom: 96, left: 0 };
}

function fenceZoomOut(map) {
  // Set on the map rather than passed to the fit, so that *every* camera move
  // works in the same frame -- the fit, the clamp, and flying to a search
  // result all agree on where the middle of the map is.
  map.setPadding(chromePadding(map));

  // Briefly clear the old floor, or a floor higher than the fitting zoom makes
  // cameraForBounds return the floor itself and the fence never loosens.
  map.setMinZoom(0);
  // No padding argument: cameraForBounds already honours the map's own, and
  // passing it here as well applies it twice.
  const camera = map.cameraForBounds(US_BOUNDS);
  if (camera?.zoom == null) return;
  const narrow = map.getCanvas().clientWidth <= NARROW_PX;
  const floor = camera.zoom - (narrow ? NARROW_ZOOM_HEADROOM : ZOOM_HEADROOM);
  map.setMinZoom(floor);
  if (map.getZoom() < floor) map.setZoom(floor);
}

/**
 * Covers every country that is not this one.
 *
 * Added on top of the basemap's own layers and therefore above its labels too,
 * which is the point: hiding the label layers alone would leave the landmass
 * of Ontario and Chihuahua drawn in, and filtering the basemap's fills per
 * country is not something the vector tiles support.
 *
 * deck.gl's hexagons are added afterwards and so sit above this. The mask is
 * scenery; it must never cover data.
 */
function maskEverythingElse(map) {
  if (map.getSource('outside-us')) return;
  map.addSource('outside-us', { type: 'geojson', data: US_MASK });

  // Under the labels, not over them.
  //
  // Added plainly, the mask lands on top of every layer including the basemap's
  // symbols, and then it clips any label whose text overhangs the coastline --
  // San Diego, Houston, half of Florida -- because the text is drawn from the
  // dot outwards and the water beside it is painted over. Zoomed out, a label
  // spans about five degrees, so no amount of margin around the coast fixes
  // it; the mask simply has to sit underneath.
  //
  // The cost is that foreign labels now draw over the masked ground. Nothing
  // in the vector tiles carries a country on a city, so they cannot be
  // filtered out: the `place` layer has name, class, rank and capital, and no
  // country code at all. Between a US city whose name is half painted over and
  // a Canadian one floating on empty ground, the first is a bug and the second
  // is a blank map with a word on it.
  // Specifically below the *place names*, which is not the same as below the
  // first symbol layer. This style puts `water_name` at index 8, so anchoring
  // to the first symbol dropped the mask underneath every road, railway and
  // boundary, and Quebec kept its motorways. The place labels are the last
  // seven layers in the style; going in front of the first of them puts the
  // mask above all the geometry and below all the names.
  const firstPlaceLabel = map.getStyle().layers.find((l) => l.id.startsWith('place_'));
  map.addLayer(
    {
      id: 'outside-us',
      type: 'fill',
      source: 'outside-us',
      paint: { 'fill-color': MASK_FILL, 'fill-opacity': 1 },
    },
    firstPlaceLabel?.id,
  );

  // Then drop every place name that is not in the country.
  //
  // The vector tiles carry no country on a city, so this cannot be filtered on
  // an attribute -- but it can be filtered on geometry. `within` tests each
  // label's point against a polygon, which is the country test the tiles do
  // not provide, and it is applied on top of each layer's own filter rather
  // than replacing it so the style's zoom and rank rules still hold.
  //
  // Without this the mask trades one problem for another: labels sit above it
  // by necessity, so hiding Canada's landmass while leaving its names strews
  // NUNAVUT, MONTERREY and HAVANA across blank ground.
  for (const layer of map.getStyle().layers) {
    if (!layer.id.startsWith('place_')) continue;

    // Country names go entirely rather than by geometry. The only one that
    // would survive the filter is "UNITED STATES", set across a map that shows
    // nothing else.
    if (layer.id.startsWith('place_country')) {
      map.setLayoutProperty(layer.id, 'visibility', 'none');
      continue;
    }

    const existing = map.getFilter(layer.id);
    map.setFilter(
      layer.id,
      existing ? ['all', existing, ['within', US_OUTLINE]] : ['within', US_OUTLINE],
    );
  }
}

/**
 * Keeps the middle of the screen over the country.
 *
 * This is deliberately *not* maplibre's own `maxBounds`, which was tried first
 * and is the wrong shape of constraint. `maxBounds` holds the whole viewport
 * inside the box, so on a tall phone -- 375 by 812 -- keeping a 29 degree tall
 * box on screen forces a zoom at which the 64 degree wide country cannot fit,
 * and no zoom floor can undo that. It fenced the map by making it useless.
 *
 * Clamping the centre separates the two concerns: [fenceZoomOut] decides how
 * far out you may zoom, this decides where you may go, and neither dictates
 * the other. Zoomed out on a phone the country sits in frame with ocean either
 * side, which is correct -- that is what the country looks like on a phone.
 */
/**
 * Guards against clampCentre re-entering itself.
 *
 * setCenter fires `move`, which is what calls this, so the correction calls
 * itself. That was survivable while the two agreed on where the centre is;
 * once the map carries padding they stop agreeing -- getBounds describes the
 * whole canvas and getCenter describes the middle of the padded box -- so each
 * correction computes a slightly different target and the recursion never
 * bottoms out. It stack-overflowed on the first pan.
 *
 * One correction per gesture is all that was ever wanted.
 */
let clamping = false;

/**
 * The part of the map the interface is not sitting on, in degrees.
 *
 * Deliberately not getBounds, which describes the whole canvas. Once the map
 * carries padding, getCenter reports the middle of the *padded* box, and
 * measuring the span from one while clamping the centre of the other leaves
 * the two disagreeing -- every correction lands slightly off, and the next
 * `move` corrects it again for ever.
 */
function visibleSpan(map) {
  const canvas = map.getCanvas();
  const pad = map.getPadding();
  const sw = map.unproject([pad.left, canvas.clientHeight - pad.bottom]);
  const ne = map.unproject([canvas.clientWidth - pad.right, pad.top]);
  return { width: ne.lng - sw.lng, height: ne.lat - sw.lat };
}

function clampCentre(map) {
  if (clamping) return;
  const [[west, south], [east, north]] = US_BOUNDS;
  const centre = map.getCenter();
  const span = visibleSpan(map);

  // How much the clamp has to allow for depends on how much is on screen,
  // which is the part a plain bounding-box clamp gets wrong. Holding only the
  // centre inside the box lets you drag the centre onto the west coast while
  // zoomed out, putting the Atlantic seaboard off-screen and filling half the
  // map with masked ocean -- technically inside the fence, useless to look at.
  const halfW = span.width / 2;
  const halfH = span.height / 2;

  // When the viewport is wider than the country there is no choice to make:
  // any pan only moves the country off-centre, so pin that axis. Otherwise
  // keep the visible edges inside the bounds.
  const lng = halfW >= (east - west) / 2
    ? (west + east) / 2
    : Math.min(Math.max(centre.lng, west + halfW), east - halfW);
  const lat = halfH >= (north - south) / 2
    ? (south + north) / 2
    : Math.min(Math.max(centre.lat, south + halfH), north - halfH);

  // Only when it actually moved, and not for sub-pixel differences, or
  // setCenter re-fires move and the two fight each other every frame.
  if (Math.abs(lng - centre.lng) > 1e-6 || Math.abs(lat - centre.lat) > 1e-6) {
    clamping = true;
    try {
      map.setCenter([lng, lat]);
    } finally {
      clamping = false;
    }
  }
}


export default function FoliageMap({ cells, bareCells = [], resolution = 6, selected, onSelect, focus, onZoom }) {
  const [hovered, setHovered] = useState(null);
  // The visible bounds, refreshed on moveend. Null until the map first settles.
  const [view, setView] = useState(null);
  // Whether the basemap style is in place. The hexagons are interleaved into
  // it, so they cannot be added before it exists.
  const [styleReady, setStyleReady] = useState(false);
  const containerRef = useRef(null);
  const mapRef = useRef(null);
  const overlayRef = useRef(null);
  // Read by the donor memo, which must see the current cells without taking a
  // dependency on them; see the note there.
  const cellsRef = useRef(cells);
  cellsRef.current = cells;

  // Held in a ref so the map is not rebuilt when the callback identity changes.
  const onZoomRef = useRef(onZoom);
  onZoomRef.current = onZoom;

  // Created once. Rebuilding the map on re-render would refetch every tile and
  // throw away the user's pan and zoom.
  useEffect(() => {
    if (!containerRef.current || mapRef.current) return undefined;

    // Built synchronously from a style we already hold, so the first paint is
    // already masked. If preparing it fails -- the style host is third-party --
    // the URL is handed over instead and the load handler below applies the
    // same changes a frame late, which is the old behaviour rather than a
    // broken map.
    const build = (style) => {
    const map = new maplibregl.Map({
      container: containerRef.current,
      style,
      bounds: OPENING_VIEW,
      // No repeated copies of the world either side. Nothing is scored
      // outside the US, so the copies are empty basemap.
      renderWorldCopies: false,
      // Required for interleaved deck.gl: the hexagons render into this map's
      // own WebGL context, and without multisampling their edges alias and
      // everything drawn alongside them -- labels included -- comes out softer
      // than the basemap alone would be.
      antialias: true,
      attributionControl: false,
    });
    const overlay = new MapboxOverlay({ interleaved: true, layers: [] });
    map.addControl(overlay);

    // Repaint after the style is in place; the layer list does not exist
    // before then.
    map.on('load', () => {
      brightenLabels(map);
      brightenBoundaries(map);
      maskEverythingElse(map);
      fenceZoomOut(map);
      // Again now that fenceZoomOut has set the padding, so the country is
      // framed in the space the interface leaves rather than the whole canvas.
      map.fitBounds(US_BOUNDS, { duration: 0 });
      setStyleReady(true);
    });
    // A phone rotating, or a desktop pane being dragged wider, changes how
    // much ground a zoom level covers -- so the floor has to move with it.
    map.on('resize', () => fenceZoomOut(map));
    map.on('moveend', () => clampCentre(map));

    // The panel is measured, so the fence has to be recomputed whenever it
    // changes size -- and it changes constantly: it starts as one line saying
    // the forecast is loading, grows a legend of six stages, and collapses
    // entirely when the toggle is pressed. Measuring it once on load would
    // frame the country against a panel that no longer exists.
    let panelWatcher;
    const panel = document.querySelector('.panel');
    if (panel && typeof ResizeObserver !== 'undefined') {
      panelWatcher = new ResizeObserver(() => fenceZoomOut(map));
      panelWatcher.observe(panel);
    }
    // Already loaded is possible if the style came from cache between
    // constructing the map and attaching this listener.
    if (map.isStyleLoaded()) setStyleReady(true);

    // Report zoom so the page can swap to the coarser export when hexagons
    // would be smaller than a pixel. On moveend rather than on every frame:
    // this decides which file to fetch, and doing it mid-gesture would thrash.
    const report = () => {
      onZoomRef.current?.(map.getZoom());
      // What is on screen, so the layers can be given that and not the country.
      const b = map.getBounds();
      setView({
        west: b.getWest(), south: b.getSouth(), east: b.getEast(), north: b.getNorth(),
      });
    };
    map.on('moveend', report);
    map.on('load', report);

    mapRef.current = map;
    overlayRef.current = overlay;
    return () => panelWatcher?.disconnect();
    };

    let disposePanelWatcher;
    let cancelled = false;
    prepareStyle()
      .catch(() => STYLE_URL)
      .then((style) => {
        if (cancelled) return;
        disposePanelWatcher = build(style);
      });

    return () => {
      cancelled = true;
      disposePanelWatcher?.();
      mapRef.current?.remove();
      mapRef.current = null;
      overlayRef.current = null;
    };
  }, []);

  // A scored cell's colour is a measurement; a filled one is a reading of the
  // country around it. Drawn a shade fainter so the two are distinguishable at
  // a glance without turning the middle of the map back into an absence.
  const FILLED_ALPHA = 150;

  /** The date's scored cells, by index. Rebuilt per date; the values change. */
  const scoredByH3 = useMemo(() => new Map(cells.map((c) => [c.h3, c])), [cells]);

  // Only res 6 needs this. The coarse levels are a few thousand hexagons for
  // the whole country, which is less work to draw than to filter.
  const windowed = resolution === 6 && view != null;

  // Bucketed once per dataset, not per move: this is the single pass over
  // everything, and it is what makes each pan cheap afterwards.
  const cellBuckets = useMemo(
    () => (windowed ? bucketByAncestor(cells.map((c) => c.h3)) : null),
    // The *set* of cells is the export's index and does not change with the
    // date, so this survives the time slider; see the donor memo below.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [windowed, cells.length],
  );
  const bareBuckets = useMemo(
    () => (windowed ? bucketByAncestor(bareCells) : null),
    [windowed, bareCells],
  );

  const visible = useMemo(() => {
    if (!windowed) return { cells, bare: bareCells };
    const ancestors = ancestorsInView(view);
    const onScreen = new Set(cellsInView(cellBuckets, ancestors));
    return {
      cells: cells.filter((c) => onScreen.has(c.h3)),
      bare: cellsInView(bareBuckets, ancestors),
    };
  }, [windowed, view, cells, bareCells, cellBuckets, bareBuckets]);

  // Which neighbours each treeless cell borrows from.
  //
  // Keyed on how many cells there are rather than on the cells themselves,
  // which is deliberate. The *set* of scored hexagons is the export's index
  // and does not change while the page is open -- only the values in them do,
  // once per move of the time slider. Depending on the array would rebuild
  // 122,000 neighbour searches every time the date changed, which is the
  // expensive half of this and none of it would differ.
  const scoredCount = cells.length;

  /** Every scored hexagon in the country, for the neighbour search to hit. */
  const scoredH3 = useMemo(
    () => new Set(cellsRef.current.map((c) => c.h3)),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [scoredCount],
  );

  // Searched for the hexagons on screen, and remembered.
  //
  // Doing the whole country up front cost 1.2 seconds on the main thread, for
  // 122,000 cells of which a viewport shows a couple of thousand. Restricted
  // to what is visible it is a few milliseconds, and the cache means panning
  // back over ground already covered costs nothing at all.
  const donorCache = useRef(new Map());
  useEffect(() => { donorCache.current = new Map(); }, [scoredH3]);

  const donors = donorCache.current;
  if (windowed) {
    const missing = visible.bare.filter((h3) => !donors.has(h3));
    if (missing.length > 0) {
      for (const [h3, found] of donorsFor(missing, scoredH3)) donors.set(h3, found);
      // Remember the misses too, or every pan re-searches the cells that have
      // no forest anywhere near them -- which is most of Kansas.
      for (const h3 of missing) if (!donors.has(h3)) donors.set(h3, []);
    }
  }

  const layers = useMemo(
    () => [
      // Ground with no forest, drawn first so the forecast always sits on top
      // of it. Flat, unpickable and unlabelled: it is there to close the holes
      // in the grid, not to be read.
      //
      // Only at res 6. The coarse levels aggregate whatever forest a cell
      // contains, so a 22 km hexagon over farmland already exists wherever any
      // of its children is forest, and the holes this fills are a res 6
      // phenomenon.
      visible.bare.length > 0 && resolution === 6
        ? new H3HexagonLayer({
          id: 'no-forest',
          data: visible.bare,
          beforeId: BEFORE_LAYER,
          getHexagon: (d) => d,
          // Coloured from the country around it rather than left neutral. See
          // neighbourFill: this is a regional reading, not a claim about trees
          // on ground that has none, so it is drawn fainter than a scored cell
          // and named separately in the legend.
          getFillColor: (d) => {
            const value = fillValue(donors.get(d) ?? [], scoredByH3);
            if (value == null) return [...NO_FOREST_RGB, NO_FOREST_ALPHA];
            return [...progressionColor(value, stageForProgression(value)), FILLED_ALPHA];
          },
          updateTriggers: { getFillColor: [visible, cells] },
          stroked: false,
          filled: true,
          extruded: false,
          pickable: false,
        })
        : null,
      new H3HexagonLayer({
        id: 'foliage',
        data: visible.cells,
        // Under the style's label layers rather than over them.
        beforeId: BEFORE_LAYER,
        // Indexes arrive as hex strings: they are 64-bit and would lose
        // precision as JSON numbers.
        getHexagon: (d) => d.h3,
        getFillColor: foliageColor,
        getLineColor: (d) => (d.h3 === selected ? [255, 255, 255, 230] : [10, 12, 9, 90]),
        getLineWidth: (d) => (d.h3 === selected ? 3 : 1),
        lineWidthUnits: 'pixels',
        lineWidthMinPixels: 0.5,
        stroked: true,
        filled: true,
        extruded: false,
        pickable: true,
        onHover: ({ object }) => setHovered(object ?? null),
        onClick: ({ object }) => onSelect?.(object?.h3 ?? null),
        updateTriggers: {
          getFillColor: [visible],
          getLineColor: [selected],
          getLineWidth: [selected],
        },
      }),
    // The bare layer is conditional, so drop the null when it is off rather
    // than handing deck.gl a hole in the list.
    ].filter(Boolean),
    [visible, resolution, selected, onSelect, donors, scoredByH3],
  );

  useEffect(() => {
    // Held back until the style exists.
    //
    // The layer is interleaved beneath a style layer by name, so adding it
    // before the style has loaded leaves it with no insertion point and it
    // draws no fill -- the map came up with hexagon outlines and no colour,
    // and only a reload fixed it, because then the style came from cache and
    // won the race against the data.
    if (!styleReady) return;
    overlayRef.current?.setProps({ layers });
  }, [layers, styleReady]);

  // Centre on a chosen place. Keyed on the nonce rather than the coordinates
  // so picking the same place twice still recentres.
  useEffect(() => {
    const map = mapRef.current;
    if (!map || !focus) return;
    map.flyTo({
      center: [focus.lon, focus.lat],
      zoom: Math.max(map.getZoom(), 9),
      duration: 900,
    });
  }, [focus?.nonce]); // eslint-disable-line react-hooks/exhaustive-deps

  // There is deliberately no fit-to-the-loaded-cells here any more.
  //
  // It existed to frame whatever data had arrived, back when that was one
  // state. With the country loaded it fits to very nearly the country anyway,
  // so all it did was wait for the first payload and then jerk the view a few
  // degrees -- and while states are still loading it framed the *loaded* part,
  // which is a map of the backfill's progress rather than a map of America.
  // The opening view is the country, and it stays there until someone moves it.

  return (
    <div className="map">
      <div className="map__canvas" ref={containerRef} />

      {hovered && hovered.h3 !== selected && (
        <div className="hovercard">
          <strong>{stageLabel(hovered.stage)}</strong>
          <span>{Math.round(hovered.progression)}% turned</span>
        </div>
      )}

      <div className="attribution">
        © OpenFreeMap · © OpenMapTiles · © OpenStreetMap · Canopy: USFS/NLCD · Weather: Open-Meteo
      </div>
    </div>
  );
}
