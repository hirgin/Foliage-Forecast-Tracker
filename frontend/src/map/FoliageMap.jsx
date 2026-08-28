import { useEffect, useMemo, useRef, useState } from 'react';
import DeckGL from '@deck.gl/react';
import { WebMercatorViewport } from '@deck.gl/core';
import { H3HexagonLayer, TileLayer } from '@deck.gl/geo-layers';
import { BitmapLayer } from '@deck.gl/layers';
import { cellToLatLng } from 'h3-js';
import { foliageColor, stageLabel } from './colors';

// Fallback only. The view is normally fitted to whatever cells are loaded, so
// adding a second state needs no code change here.
const FALLBACK_VIEW = { longitude: -72.65, latitude: 43.92, zoom: 7, pitch: 0, bearing: 0 };

// Esri's Dark Gray Canvas: keyless, and purpose-built as a muted backdrop for
// data overlays. CARTO's raster tiles now stamp "API KEY REQUIRED" across
// themselves. A raster tile layer also keeps the whole map inside deck.gl --
// react-map-gl was dropped here, since it pulled ~1 MB of MapLibre for a
// backdrop and its v8 API no longer loaded a style under a DeckGL parent.
// Note the {z}/{y}/{x} order: Esri puts row before column.
const BASEMAP_TILES =
  'https://services.arcgisonline.com/arcgis/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile/{z}/{y}/{x}';

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
  const [viewState, setViewState] = useState(null);
  const containerRef = useRef(null);
  const fitted = useRef(false);

  // Fit once, then hand control to the user. Hardcoding a zoom only ever looks
  // right at one window size.
  useEffect(() => {
    const el = containerRef.current;
    if (!el || !cells.length || fitted.current) return;

    const { clientWidth: width, clientHeight: height } = el;
    const bounds = boundsOf(cells);
    if (!width || !height || !bounds) return;

    const view = new WebMercatorViewport({ width, height }).fitBounds(bounds, {
      padding: { top: 40, bottom: 110, left: width > 720 ? 330 : 40, right: 40 },
    });
    setViewState({
      longitude: view.longitude,
      latitude: view.latitude,
      zoom: view.zoom,
      pitch: 0,
      bearing: 0,
    });
    fitted.current = true;
  }, [cells]);

  const layers = useMemo(
    () => [
      new TileLayer({
        id: 'basemap',
        data: BASEMAP_TILES,
        minZoom: 0,
        maxZoom: 19,
        tileSize: 256,
        // Knocked back over the page's dark ground so the basemap reads as
        // context rather than competing with the foliage ramp on top of it.
        opacity: 0.45,
        renderSubLayers: (props) => {
          const { boundingBox } = props.tile;
          return new BitmapLayer(props, {
            data: null,
            image: props.data,
            bounds: [boundingBox[0][0], boundingBox[0][1], boundingBox[1][0], boundingBox[1][1]],
          });
        },
      }),
      new H3HexagonLayer({
        id: 'foliage',
        data: cells,
        // Indexes arrive as hex strings: they are 64-bit and would lose
        // precision as JSON numbers.
        getHexagon: (d) => d.h3,
        getFillColor: foliageColor,
        getLineColor: (d) => (d.h3 === selected ? [255, 255, 255, 230] : [10, 12, 9, 120]),
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

  return (
    <div className="map" ref={containerRef}>
      <DeckGL
        viewState={viewState ?? FALLBACK_VIEW}
        onViewStateChange={({ viewState: v }) => setViewState(v)}
        controller={true}
        layers={layers}
        getCursor={({ isDragging }) => (isDragging ? 'grabbing' : hovered ? 'pointer' : 'grab')}
      />

      {hovered && hovered.h3 !== selected && (
        <div className="hovercard">
          <strong>{stageLabel(hovered.stage)}</strong>
          <span>{Math.round(hovered.progression)}% turned</span>
        </div>
      )}

      <div className="attribution">Basemap © Esri · Canopy: USFS/NLCD · Weather: Open-Meteo</div>
    </div>
  );
}
