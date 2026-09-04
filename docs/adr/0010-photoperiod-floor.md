# ADR-0010: A floor under daily progress, so southern autumns finish

**Status:** accepted — refitted nationally, model version bumped

## Context

[ADR-0008](0008-cooling-degree-day-senescence.md) made photoperiod a gate and
temperature the pace: nothing accumulates until days shorten past 13 hours, and
after that every day contributes in proportion to how far below 20 °C it sits.
That model cannot finish an autumn where the weather does not cooperate.

On the Gulf coast the daily mean sits at or above 20 °C well into November, so a
stand there accumulated nothing for weeks. The consequences were all visible on
the map at once:

- **2,366 Florida cells and 2,058 in Alabama never reached peak at all.** They
  froze part-way through autumn — 60 or 70% turned — and held that colour to the
  end of the season, so a mid-December map showed orange across the south.
- **11,372 cells peaked after 25 November**, and Louisiana's tail ran to
  15 December, which is not a date anything in the contiguous states is turning.
- Georgia and Arkansas ran roughly two weeks late at their medians.

A test asserted the first of these as *correct*: "a place too warm to cool never
reaches peak", described as what stops the model claiming a season in a
subtropical autumn. It was the defect, written down as an invariant.

Those forests do turn. They turn on shortening days.

## Decision

**Past the gate, a stand makes minimum daily progress on daylight alone.**

```
cooling += max(T_BASE_C - mean, PHOTOPERIOD_FLOOR)
```

This finishes the thought the gate started. Senescence is triggered by
photoperiod and *paced* by temperature; the gate said the first half and the
model then let temperature do all the work. A cold day is unchanged, because its
own cooling already exceeds the floor.

**A floor, not an added term.** The obvious alternative — adding something
proportional to how far daylight has fallen below the threshold — was fitted and
rejected. Northern days shorten further and faster, so a daylight-proportional
term lands hardest where it is least needed; the fit had to raise `S_PEAK` to
compensate, and Maine and Vermont went late. Under a floor the north is
essentially untouched.

**Fitted jointly, because the parameters interact.** Floor, `S_PEAK` and the oak
multiplier were swept together against eleven places from Fort Kent to Baton
Rouge. Fitting them one at a time gives a different and worse answer for each.

| | Before | After |
|---|---|---|
| `PHOTOPERIOD_FLOOR` | — | **2.1** |
| `S_PEAK` | 100 | **225** |
| Oak multiplier | 1.6 | **1.0** |
| Error over eleven places | 12.2 days | **4.8 days** |

## Consequences

**The oak multiplier was standing in for latitude.** The joint fit puts oak at
exactly 1.0, and holding it higher only makes oak country late again. Litchfield
is oak country and also three degrees south of Stowe, and a model with no way to
say "further south turns later" had only the species term available to explain
the gap. This retroactively explains why 1.6 was simultaneously too early in New
England and too late in the Ozarks — a single constant was carrying two
different effects. Oak keeps its group, because it still names the forest in the
explanation and a later fit against per-species observations may find the real
oak effect now that latitude is no longer confounded with it. Aspen–birch at
0.61 is unaffected: it was measured against places at the same latitude.

**Measured nationally, after rescoring all 141,274 cells:**

| | Before | After |
|---|---|---|
| Cells peaking after 25 November | 11,372 | **1,069** |
| Latest peak anywhere | 15 December | **26 November** |
| Louisiana median | 1 December | 12 November |
| Arkansas median | 14 November | 5 November |

Cells that never peak rose, and that is the evergreen decision in
[ADR-0009](0009-forest-type-as-a-model-term.md) rather than this one: Georgia's
2,737 matches its 2,761 conifer cells. They are drawn faded.

**The deep south now runs early rather than late** — Louisiana's median at
12 November against a published window nearer 27 November, Florida at 14
November against early December. That is a real residual and a smaller one than
the lateness it replaces, and the southern windows are less firmly sourced than
the New England ones ADR-0008 fitted against. Texas keeps 1,022 cells peaking
after 25 November, which is the largest remaining pocket.

## The mistake worth recording

The first fit targeted **progression 82**, the middle of the peak band. The rest
of the system reports a peak date when a cell *enters* the band at 75, which at
this shape is 80.8% of the way in — so every prediction was late by that gap.
Offline it looked like a good fit; scored end to end it put Louisiana's median
on 29 October against a target of 27 November, a month out.

The offline fit and the thing it is fitting have to agree on what a peak date
means. ADR-0008 already recorded a version of this — its own fit came out a day
optimistic for leaving out the lapse-rate downscale — and the lesson did not
carry. It is written here more plainly: **fit against the number the system
reports, not the number the model computes.**
