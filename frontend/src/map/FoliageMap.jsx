import { useEffect, useMemo, useRef, useState } from 'react';
import maplibregl from 'maplibre-gl';
import { MapboxOverlay } from '@deck.gl/mapbox';
import { H3HexagonLayer } from '@deck.gl/geo-layers';
import { cellToLatLng } from 'h3-js';
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

const FALLBACK_VIEW = { longitude: -72.65, latitude: 43.92, zoom: 7 };

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
function fenceZoomOut(map) {
  // Briefly clear the old floor, or a floor higher than the fitting zoom makes
  // cameraForBounds return the floor itself and the fence never loosens.
  map.setMinZoom(0);
  const camera = map.cameraForBounds(US_BOUNDS);
  if (camera?.zoom == null) return;
  map.setMinZoom(camera.zoom);
  if (map.getZoom() < camera.zoom) map.setZoom(camera.zoom);
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
  map.addLayer({
    id: 'outside-us',
    type: 'fill',
    source: 'outside-us',
    paint: { 'fill-color': MASK_FILL, 'fill-opacity': 1 },
  });
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
function clampCentre(map) {
  const [[west, south], [east, north]] = US_BOUNDS;
  const centre = map.getCenter();
  const view = map.getBounds();

  // How much the clamp has to allow for depends on how much is on screen,
  // which is the part a plain bounding-box clamp gets wrong. Holding only the
  // centre inside the box lets you drag the centre onto the west coast while
  // zoomed out, putting the Atlantic seaboard off-screen and filling half the
  // map with masked ocean -- technically inside the fence, useless to look at.
  const halfW = (view.getEast() - view.getWest()) / 2;
  const halfH = (view.getNorth() - view.getSouth()) / 2;

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
    map.setCenter([lng, lat]);
  }
}

/** Bounding box of the loaded cells, from their H3 indexes. */
function boundsOf(cells) {
  if (!cells.length) return null;
  let minLon = 180, minLat = 90, maxLon = -180, maxLat = -90;
  for (const c of cells) {
    const [lat, lon] = cellToLatLng(c.h3);
    if (lon < minLon) minLon = lon;
    if (lon > maxLon) maxLon = lon;
    if (lat < minLat) minLat = lat;
    if (lat > maxLat) maxLat = lat;
  }
  return [[minLon, minLat], [maxLon, maxLat]];
}

export default function FoliageMap({ cells, selected, onSelect, focus, onZoom }) {
  const [hovered, setHovered] = useState(null);
  // Whether the basemap style is in place. The hexagons are interleaved into
  // it, so they cannot be added before it exists.
  const [styleReady, setStyleReady] = useState(false);
  const containerRef = useRef(null);
  const mapRef = useRef(null);
  const overlayRef = useRef(null);
  const fitted = useRef(false);
  // Held in a ref so the map is not rebuilt when the callback identity changes.
  const onZoomRef = useRef(onZoom);
  onZoomRef.current = onZoom;

  // Created once. Rebuilding the map on re-render would refetch every tile and
  // throw away the user's pan and zoom.
  useEffect(() => {
    if (!containerRef.current || mapRef.current) return undefined;

    const map = new maplibregl.Map({
      container: containerRef.current,
      style: STYLE_URL,
      center: [FALLBACK_VIEW.longitude, FALLBACK_VIEW.latitude],
      zoom: FALLBACK_VIEW.zoom,
      // No repeated copies of the world either side. Nothing is scored
      // outside the US, so the copies are empty basemap.
      renderWorldCopies: false,
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
      setStyleReady(true);
    });
    // A phone rotating, or a desktop pane being dragged wider, changes how
    // much ground a zoom level covers -- so the floor has to move with it.
    map.on('resize', () => fenceZoomOut(map));
    map.on('move', () => clampCentre(map));
    // Already loaded is possible if the style came from cache between
    // constructing the map and attaching this listener.
    if (map.isStyleLoaded()) setStyleReady(true);

    // Report zoom so the page can swap to the coarser export when hexagons
    // would be smaller than a pixel. On moveend rather than on every frame:
    // this decides which file to fetch, and doing it mid-gesture would thrash.
    const report = () => onZoomRef.current?.(map.getZoom());
    map.on('moveend', report);
    map.on('load', report);

    mapRef.current = map;
    overlayRef.current = overlay;

    return () => {
      map.remove();
      mapRef.current = null;
      overlayRef.current = null;
    };
  }, []);

  const layers = useMemo(
    () => [
      new H3HexagonLayer({
        id: 'foliage',
        data: cells,
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
          getFillColor: [cells],
          getLineColor: [selected],
          getLineWidth: [selected],
        },
      }),
    ],
    [cells, selected, onSelect],
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

  // Fit once, then leave the view alone. A hardcoded zoom only ever looks
  // right at one window size.
  useEffect(() => {
    const map = mapRef.current;
    if (!map || !cells.length || fitted.current) return;

    const bounds = boundsOf(cells);
    if (!bounds) return;

    // Pad for wherever the chrome actually is. On a wide screen the panel sits
    // down the left; on a phone it is a sheet across the top, so reserving 330
    // pixels on the left there both wasted the width the map needs most and
    // left the country to drift under the panel.
    const width = containerRef.current?.clientWidth ?? 0;
    const wide = width > 720;
    map.fitBounds(bounds, {
      padding: wide
        ? { top: 40, bottom: 110, left: 330, right: 40 }
        : { top: 150, bottom: 100, left: 12, right: 12 },
      duration: 0,
    });
    fitted.current = true;
  }, [cells]);

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
