/**
 * Derives peak.bin from the timeline shards already on disk.
 *
 * Not part of the build. The exporter writes this file from the database; this
 * reconstructs the same bytes from an export that is already sitting in
 * public/data, so the map's peak-date view can be built and checked without
 * spending an export to see it.
 *
 * Run from frontend/:  node derive-peak.mjs
 */
import fs from 'fs';
import path from 'path';
import { cellToParent } from 'h3-js';
import { stageOf } from './src/api/packed.js';

const DATA = path.join('public', 'data');
const NO_DATA = 255;
const MAGIC = 'FFPK';

const cells = JSON.parse(fs.readFileSync(path.join(DATA, 'cells.json'), 'utf8'));
const meta = JSON.parse(fs.readFileSync(path.join(DATA, 'meta.json'), 'utf8'));
console.log(`${cells.count} cells, season ${meta.seasonStart}..${meta.seasonEnd}`);

const isPeak = (b) => (b === 255 || b === 254 ? false : stageOf(b / 2) === 'PEAK');

const peak = new Uint8Array(cells.count).fill(NO_DATA);
let found = 0;

for (const file of fs.readdirSync(path.join(DATA, 'timeline'))) {
  const buf = fs.readFileSync(path.join(DATA, 'timeline', file));
  if (buf.toString('latin1', 0, 4) !== 'FFT1') continue;
  const n = buf.readUInt32LE(4);
  const dayCount = buf.readUInt32LE(8);
  const dataStart = 12 + 4 * n;

  for (let i = 0; i < n; i += 1) {
    const globalIndex = buf.readUInt32LE(12 + 4 * i);
    const base = dataStart + i * 3 * dayCount;
    for (let d = 0; d < dayCount; d += 1) {
      if (isPeak(buf[base + 3 * d])) {
        peak[globalIndex] = d;
        found += 1;
        break;
      }
    }
  }
}
console.log(`cells that reach peak: ${found} of ${cells.count}`);

function write(file, values) {
  const out = Buffer.alloc(8 + values.length);
  out.write(MAGIC, 0, 'latin1');
  out.writeUInt32LE(values.length, 4);
  Buffer.from(values).copy(out, 8);
  fs.writeFileSync(path.join(DATA, file), out);
  console.log(`${file}: ${values.length} cells, ${(out.length / 1024).toFixed(0)} KB`);
}

write('peak.bin', peak);

// The coarse levels average the children that have a peak, matching how the
// exporter aggregates and how the daily files already behave.
for (const res of [4, 5]) {
  const coarse = JSON.parse(fs.readFileSync(path.join(DATA, `cells-r${res}.json`), 'utf8'));
  const slot = new Map(coarse.h3.map((h, i) => [h, i]));
  const sums = new Float64Array(coarse.count);
  const counts = new Uint32Array(coarse.count);

  cells.h3.forEach((h3, i) => {
    if (peak[i] === NO_DATA) return;
    const at = slot.get(cellToParent(h3, res));
    if (at === undefined) return;
    sums[at] += peak[i];
    counts[at] += 1;
  });

  const out = new Uint8Array(coarse.count).fill(NO_DATA);
  for (let i = 0; i < coarse.count; i += 1) {
    if (counts[i]) out[i] = Math.round(sums[i] / counts[i]);
  }
  write(`peak-r${res}.bin`, out);
}
