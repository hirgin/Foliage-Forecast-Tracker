# Published peak foliage windows

Reference targets for calibrating the model, gathered 4 September 2026. Kept
here because the model has twice been fitted against remembered figures that
turned out not to match any published source — ADR-0008 records a Vermont
target of 5–12 October that none of these agree with.

## By state or region

| Region | Published peak |
|---|---|
| Northern Vermont | 24 Sep – 10 Oct |
| New Hampshire, inland | 28 Sep – 9 Oct |
| Maine, inland | 1 – 17 Oct |
| Northern Michigan | 1 – 17 Oct |
| Northern Minnesota | 1 – 17 Oct |
| Wisconsin | 5 – 14 Oct |
| Iowa, northern Illinois, northern Missouri | 5 – 21 Oct |
| Ohio, Pennsylvania | 5 – 21 Oct |
| West Virginia, eastern Kentucky | 5 – 21 Oct |
| New York | 28 Sep – 28 Oct, by elevation and distance from the coast |
| Maryland, New Jersey, inland | 12 – 28 Oct |
| Virginia, North Carolina, inland | 12 – 28 Oct |
| Tennessee | 12 – 28 Oct |
| Georgia, Alabama, Mississippi | 19 Oct – 4 Nov |
| Louisiana, Florida | 2 – 11 Nov |

Within Vermont, tourism sources place the north in the last week of September
into the first week of October, the centre early-to-mid October, and the south
mid-to-late October. Maine runs north late September, central and western
mountains the first week of October, coastal and southern mid-to-late October.

## What this says about the model

Two things, measured against the medians this model produced on 4 September
under `S_PEAK` 100 with a floor of 1.25:

**Northern New England peaking in late September is correct**, not an error.
Vermont at 27 September sits inside the published 24 September – 10 October,
and Vermont Tourism describes exactly that. The 5–12 October figure in
ADR-0008 is not supported by any source found here.

**The north–south gradient is too steep.** Against published midpoints:

| | Model | Published mid | Error |
|---|---|---|---|
| Vermont | 27 Sep | 2 Oct | −5 |
| Michigan | 8 Oct | 9 Oct | −1 |
| Kentucky | 20 Oct | 13 Oct | +7 |
| North Carolina | 24 Oct | 20 Oct | +4 |
| Georgia | 31 Oct | 27 Oct | +4 |
| Louisiana | 16 Nov | 6 Nov | +10 |

The model spans 50 days from Vermont to Louisiana where these sources span
about 35. The north is a little early and the south drifts progressively
later, which is the photoperiod floor carrying southern autumns too slowly
rather than anything wrong with `S_PEAK`.

Refitting the floor against this table is the outstanding work. It was not
done on 4 September because the database quota was exhausted and the cluster
refused connections.

## Sources

- [Farmers' Almanac, fall foliage dates by state](https://www.farmersalmanac.com/fall-leaves-foliage-dates-map)
- [Vermont Tourism, best time to see foliage](https://vermontvacation.com/best-time-to-see-foliage-in-vermont/)
- [Boston.com, 2025 New England foliage maps and dates](https://www.boston.com/culture/new-england-travel/2025/09/18/2025-fall-foliage-map-forecast-boston-new-england/)
- [NPR, leaf-peeping predictions](https://www.npr.org/2025/09/22/nx-s1-5550033/fall-leaves-peak-map-2025)
