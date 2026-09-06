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

## Consensus peak dates, gathered 6 September 2026

The windows above are 9-17 days wide, and that turned out to matter more than
their accuracy. **A window that wide is satisfied by landing at its early
edge**, which is what this model did at every reference town, for its whole
life, while scoring as a good fit. The error was invisible to the metric.

So these are peak *dates* -- the consensus of five published forecasts, taken
as a single number per place, which is a target a fit can actually be wrong
about.

| Place | Consensus peak |
|---|---|
| Fort Kent ME | 2 Oct |
| Duluth MN | 7 Oct |
| Stowe VT | 9 Oct |
| Marquette MI | 9 Oct |
| Wausau WI | 9 Oct |
| Concord NH | 10 Oct |
| Bangor ME | 12 Oct |
| Elkins WV | 15 Oct |
| Columbus OH | 20 Oct |
| Lexington KY | 22 Oct |
| Asheville NC | 22 Oct |
| Roanoke VA | 25 Oct |
| Knoxville TN | 28 Oct |
| Atlanta GA | 3 Nov |
| Birmingham AL | 5 Nov |
| Jackson MS | 5 Nov |
| Baton Rouge LA | 20 Nov |
| Ocala FL | 25 Nov |

Northern New England is the load-bearing row. Five sources put it in **early
to mid October**, not late September:

- Old Farmer's Almanac names **8 October** as the optimal viewing date for much
  of New England.
- Explore Fall: *"Northern Vermont, New Hampshire, and northwestern Maine
  experience peak in early October."*
- Boston Globe (4 September 2026), from Explore Fall data: Vermont, New
  Hampshire and Maine all reach the Peak frame at **12 October**, past peak on
  the 19th.
- Yankee / newengland.com place Full Peak between their 5 October and 16
  October slider stops.
- Farmers' Almanac gives northern Vermont 24 September - 10 October.

## What this changed

Measured against those dates, the model as published was **5 days early across
the north on average, and 9 days early at Stowe and Bangor**. That is the
error a user reported as "New England is peaking weeks early", and no display
or band change could have reached it -- being early is set by how fast cooling
accumulates, not by where the stages sit.

`T_BASE_C` is what sets that pace, and at 20 C nearly every autumn day falls
below the base, so the model was closer to counting days than counting cold.
Lowering it to 18 puts the north on its consensus dates:

| | Shipped (20) | T_BASE 18 | Consensus |
|---|---|---|---|
| Stowe VT | 30 Sep | 10 Oct | 9 Oct |
| Bangor ME | 3 Oct | 12 Oct | 12 Oct |
| Concord NH | 3 Oct | 12 Oct | 10 Oct |
| Fort Kent ME | 25 Sep | 30 Sep | 2 Oct |
| Columbus OH | 16 Oct | 20 Oct | 20 Oct |
| Atlanta GA | 28 Oct | 4 Nov | 3 Nov |

**The cost was predicted and did not appear.** The arithmetic said a later
peak sits in a steeper part of the season, so neighbouring hexagons would
separate by fewer days -- sensitivity 4.1 to 2.7 days per degree, Vermont's
spread falling from ~14 days to ~9. Measured on rescored states, it went the
other way:

| State | Spread before | Spread after | Median peak before | after |
|---|---|---|---|---|
| Vermont | 14 d | **16 d** | 27 Sep | 8 Oct |
| Maine | 6 d | **8 d** | 27 Sep | 8 Oct |
| Colorado | 16 d | 21 d | 18 Sep | 20 Sep |
| Georgia | 4 d | 5 d | 2 Nov | 10 Nov |
| Louisiana | 2 d | 4 d | 5 Nov | 23 Nov |
| Michigan | 8 d | 5 d | 8 Oct | 11 Oct |

(Colorado, Georgia, Louisiana and Michigan were measured at T_BASE 19;
Vermont and Maine at 18.)

The proxy captured a later peak landing in a steeper season and missed the
larger opposite effect: with a lower base fewer days contribute at all, so
cells sitting near the threshold separate further. Five of six states held or
improved. Michigan is the exception at 8 to 5 days and is worth a look.

**What this leaves.** The south now runs late -- Georgia's median at 10
November against a window closing on the 4th, Louisiana's at 23 November
against the 11th. That is the photoperiod floor's job and is a separate
change with its own validation. Fitting one constant to serve both ends is
what produced 19 and left Maine two weeks early.

## Sources for the consensus dates

- [Old Farmer's Almanac 2026 fall foliage map](https://www.almanac.com/fall-foliage-map)
- [Explore Fall foliage map](https://www.explorefall.com/fall-foliage-map)
- [Boston Globe, 4 September 2026](https://www.bostonglobe.com/2026/09/04/metro/new-england-vermont-fall-foliage-forecast/)
- [Yankee / New England peak foliage map](https://newengland.com/foliage/foliage/peak-fall-foliage-map/)
- [SmokyMountains.com 2026 prediction map](https://smokymountains.com/fall-foliage-map)
- [AccuWeather 2026 fall foliage forecast](https://www.accuweather.com/en/weather-forecasts/fall-foliage-forecast-2026-where-to-expect-the-best-color-across-the-us/1924680)
