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

export default function FoliageMap({ cells, selected, onSelect }) {
  const [hovered, setHovered] = useState(null);
  const containerRef = useRef(null);
  const mapRef = useRef(null);
  const overlayRef = useRef(null);
  const fitted = useRef(false);

  // Created once. Rebuilding the map on re-render would refetch every tile and
  // throw away the user's pan and zoom.
  useEffect(() => {
    if (!containerRef.current || mapRef.current) return undefined;

    const map = new maplibregl.Map({
      container: containerRef.current,
      style: STYLE_URL,
      center: [FALLBACK_VIEW.longitude, FALLBACK_VIEW.latitude],
      zoom: FALLBACK_VIEW.zoom,
      attributionControl: false,
    });
    const overlay = new MapboxOverlay({ interleaved: true, layers: [] });
    map.addControl(overlay);

    // Repaint after the style is in place; the layer list does not exist
    // before then.
    map.on('load', () => brightenLabels(map));

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
    overlayRef.current?.setProps({ layers });
  }, [layers]);

  // Fit once, then leave the view alone. A hardcoded zoom only ever looks
  // right at one window size.
  useEffect(() => {
    const map = mapRef.current;
    if (!map || !cells.length || fitted.current) return;

    const bounds = boundsOf(cells);
    if (!bounds) return;

    const width = containerRef.current?.clientWidth ?? 0;
    map.fitBounds(bounds, {
      padding: { top: 40, bottom: 110, left: width > 720 ? 330 : 40, right: 40 },
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
