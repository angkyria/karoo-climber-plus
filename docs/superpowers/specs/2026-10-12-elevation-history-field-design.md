# Elevation history field: design

- **Date:** 2026-10-12
- **Status:** design approved during brainstorming; spec awaiting review
- **Branch:** `elevation-history-field`

## 1. Intent

A graphical Karoo data field showing the elevation the rider has covered on the
current ride, updated live. Each stretch is colored by its grade using the
palette selected in Climber+ settings, the same grade coloring the climb profile
uses.

### Decided with the user

| Topic | Decision |
|---|---|
| What "historical" means | This ride so far, recorded live. Works without a route. |
| Coloring | Grade colors from the Climber+ palette (all 6 palettes). |
| Where it lives | A Karoo graphical data field, placed with Karoo's page editor. Not the overlay. |
| Horizontal span | The whole ride, refitting as the ride gets longer. |
| Extras | Current elevation, ascent so far, min/max labels, distance grid. |
| Coloring approach | Fixed 100 m grades, frozen once complete. Each pixel column shows the steepest chunk it covers. |
| Layout | "B, values on the graph": the graph spans the whole field, and values and labels float over it with a dark halo. |
| Smoothing | Centered ±25 m moving average before grades are computed. Added after a noise simulation and accepted. |
| Restarts | No persistence in v1. |

### Assumptions (stated during brainstorming, not objected to)

- Ships inside Climber+: same APK, extension id `karoo-climber`. Works whether
  or not the overlay is enabled.
- Palette comes from Climber+ settings. Units (m/ft, km/mi) come from the Karoo
  rider profile.
- Climber+'s own look (black card, IBM Plex Sans Condensed, unitless numbers
  with glyphs), regardless of the Karoo page theme.
- Demo mode drives the field for on-desk checks.
- The overlay's behavior does not change.

### Success criteria

1. On a real ride the field shows the whole ride so far. Current elevation,
   ascent (matching Karoo's own Ascent field), min/max and grade colors are
   correct.
2. Flat roads stay in the palette's lowest band under realistic barometer
   noise (test in §8).
3. No cost when the field isn't on any page of the active ride profile. When it
   is: three ~1 Hz streams during the ride, and at most one frame every 5 s while
   the field is visible.
4. No crashes, no `TransactionTooLargeException`, and no frames dropped by
   karoo-ext's 900 ms guard (§2).
5. All the math is unit-tested on the JVM, and `./gradlew testDebugUnitTest`
   passes.

### Non-goals (v1)

- Keeping the history across extension restarts.
- Showing the route ahead, or anything route-based, in this field.
- Overlay changes beyond the shared-helper extractions in §3.
- Settings UI changes, per-field-instance options, a light-theme variant.
- Glance/Compose rendering for the field.

## 2. Platform facts (checked in the karoo-ext 1.1.9 API and bytecode)

- **Graphical fields.** `DataTypeImpl.startView(context, ViewConfig, ViewEmitter)`
  plus `ViewEmitter.updateView(RemoteViews)`. `UpdateGraphicConfig(showHeader, formatDataTypeId)`
  controls Karoo's own field header. Fields are returned from `KarooExtension.types`
  and declared in `extension_info.xml` as `<DataType typeId displayName description graphical icon>`.
  Karoo OS parses that XML itself, so the jar can't confirm the attribute
  names. They come from karoo-ext's sample and match the fields of the library's
  `DataType` model; §9 checks them on the device.
- **`startView` threading.** The binder stub calls it directly on a binder
  thread.
- **Frame drops.** `ViewEmitter.updateView` silently drops a frame sent less
  than 900 ms after the previous one; it only logs `ignoring updateView, too soon`.
- **`ViewConfig`** carries `viewSize` (px), `gridSize`, `textSize`, `alignment`,
  `boundariesEnabled` and `preview`. `preview` is true in Karoo's page editor.
- **Elevation.** There is no plain elevation stream. Current barometric
  altitude comes from `DataType.Type.PRESSURE_ELEVATION_CORRECTION`, field
  `DataType.Field.PRESSURE_ELEVATION`; this still needs a device check (§9).
  Ride distance is `DataType.Type.DISTANCE`, field `DataType.Field.DISTANCE`.
  Ascent is `DataType.Type.ELEVATION_GAIN`, field `DataType.Field.ELEVATION_GAIN`.
  Data points carry several fields, so values are read by key, with
  `DataType.Field.SINGLE` as the fallback (same as `ProgressRepo`).
- **Field placement.** `ActiveRideProfile.profile.pages[].elements[].dataTypeId`
  lists every field on every page of the active ride profile.
- **Our id.** `DataType.dataTypeId("karoo-climber", "elevation-history")`
  = `TYPE_EXT::karoo-climber::elevation-history`.

## 3. Architecture

```
Karoo streams                          recording gate (§7.1)
 DISTANCE ──────────┐
 PRESSURE_ELEVATION ├─► ElevationHistoryRepo ──► ElevationHistory (pure)
 ELEVATION_GAIN ────┘           ▲                      │ snapshot copy (Main)
 RideState, ActiveRideProfile ──┘                      ▼
            ElevationHistoryField ──► HistoryGraphLayout (pure) ──► HistoryGraphRenderer
            (one loop per visible      ranges, columns,              Bitmap ─► RemoteViews ─► Karoo
             field instance)           grid, what fits
```

| File | Status | Responsibility |
|---|---|---|
| `data/ElevationHistory.kt` | new | Pure model: 10 m points, ±25 m smoothing, 100 m chunk grades, min/max/current, demo ascent. JVM-tested. |
| `data/ElevationHistoryRepo.kt` | new | Starts and stops the three stream consumers according to the gate, feeds the history on Main, and exposes `version`, `ascent` and `snapshot()`. Same shape as `ProgressRepo`. |
| `field/ElevationHistoryField.kt` | new | `DataTypeImpl("karoo-climber", "elevation-history")`: per-`startView` render loop, throttling, `RemoteViews`. |
| `field/HistoryGraphLayout.kt` | new | Pure layout math: x/y ranges, per-column peak and grade, grid steps, which extras fit. No Android imports. JVM-tested. |
| `field/HistoryGraphRenderer.kt` | new | Canvas drawing of layout B into a `Bitmap`. |
| `ui/Units.kt` | new, extracted | Unitless m/ft and km/mi formatting, shared by the overlay and the field. |
| `ui/KarooFonts.kt` | new, extracted | IBM Plex Sans Condensed loader from `/system/fonts`. |
| `palette/GradePalettes.kt` | changed | Adds `GradePalette.chunkColor(grade)`, the descent-fallback rule that is currently duplicated in two places in `ClimbOverlayView`. `descentColor` becomes the literal `0xFF507896` (same color as `Color.rgb(0x50, 0x78, 0x96)`), so palette code runs in JVM tests. |
| `overlay/ClimbOverlayView.kt` | changed | Uses the three extractions. No behavior change. |
| `ClimberExtension.kt` | changed | Creates the repo and the field, overrides `types`, consumes `ActiveRideProfile`, wires the gate and the demo feed, resets the history when a ride ends. |
| `res/xml/extension_info.xml` | changed | Adds `<DataType typeId="elevation-history" graphical="true" …/>` and updates the "no datatypes" comment. |
| `res/layout/field_elevation_history.xml` | new | A single `ImageView` (`@+id/graph`, `match_parent`, `scaleType="fitXY"`). |
| `res/drawable/ic_elevation.xml` | new | The `ic_climber` mountain cropped to its visible box, in white. Used for the current-elevation value and as the picker icon. |
| `res/values/strings.xml` | changed | Field name "Elevation history" and its description. |

**Threading.** The history and the repo are only changed on Main: Karoo
callbacks plus the `Dispatchers.Main.immediate` extension scope. Each field
loop runs in the extension scope. It copies a snapshot on Main, then does layout
and rendering on `Dispatchers.Default`. Each loop owns its own renderer
(`Paint` and `Drawable` instances aren't shared across threads).

## 4. Recording model: `ElevationHistory`

| Constant | Value | Meaning |
|---|---|---|
| `BIN_M` | 10 | Sample bin width |
| `SMOOTH_HALF_WINDOW_M` | 25 | Half-width of the centered moving average |
| `CHUNK_M` | 100 | Grade chunk length |
| `LIVE_MIN_M` | 30 | Minimum recorded length before the newest chunk gets its own grade |
| `RESET_DROP_M` | 50 | Distance drop treated as a new ride |

1. **`append(distance, elevation)`**
   - A distance at least `RESET_DROP_M` below the last sample's distance
     calls `reset()`, then the sample is recorded normally.
   - Any other distance that isn't greater than the last sample's distance
     (no movement, or small backward jitter) is ignored. A café stop therefore
     adds no barometric drift.
   - Otherwise the sample goes into bin `floor(distance / BIN_M)`. A bin's
     point is the mean distance and mean elevation of its samples. The newest
     bin stays open (provisional) until a sample lands in a later bin.
   - `current` becomes this sample's raw elevation, and `version` increments.
2. **Smoothing.** A point's smoothed elevation is the mean of all points within
   ±25 m of it, by distance. Values within 25 m of the newest point are
   provisional and recomputed on each append. Earlier values are final.
3. **Chunks** start at the first point's distance `d0`: chunk *k* covers
   [d0 + 100k, d0 + 100k + 100). Its grade is rise over run, in %, between the
   chunk's ends on the smoothed series (linear interpolation). A chunk is
   **frozen** (computed once and never changed again) as soon as every smoothed
   value it depends on is final, i.e. once the newest point is more than 25 m
   past its end.
4. **Provisional chunks.** Chunks that aren't frozen yet are recomputed on every
   append.
   - A chunk whose end has already been recorded uses its two ends.
   - The chunk containing the newest point uses [chunk start, newest] if that's
     at least 30 m long.
   - Failing that, it uses the trailing 100 m (clamped to `d0`) if that's at
     least 30 m long.
   - Otherwise it has no grade (`NaN`, drawn neutral gray).
5. **Summary values.** Min and max are taken over the smoothed points.
   `climbed` is the sum of positive steps between consecutive smoothed points;
   it's only used as the ascent in demo mode.
6. **`snapshot()`** returns immutable copies of the point distances, smoothed
   elevations, chunk grades, `d0`, `current`, `min`, `max` and `climbed`.
   That's about 20k points for 200 km, under 0.5 MB.

## 5. Layout and coloring: `HistoryGraphLayout` (pure)

- **Horizontal range.** From `d0` to max(newest, `d0` + 1000 m). During the
  first kilometer the graph fills in from the left; after that it refits to the
  whole ride.
- **Columns.** One per graph pixel. Column *i* covers [xᵢ, xᵢ₊₁), clipped to
  the newest point. Columns past the newest point stay empty.
  - **Grade.** Of the chunks overlapping the column: the steepest uphill if any
    is ≥ 0, otherwise the steepest downhill. `NaN` chunks are ignored; if all
    are `NaN`, the column is `NaN`. Examples: [3.1, 9.8, 4.0] → 9.8 and
    [−2, −6, −1] → −6.
  - **Peak.** The highest smoothed elevation inside the column, including the
    interpolated values at both column edges.
- **Vertical range.** [min, max] of the smoothed points, widened symmetrically
  to at least 20 m. The bottom 10% of the graph is left as a margin, so even
  the lowest column shows its color. The top of the range sits below the values
  row, plus 6% headroom, so the shape never runs under the numbers.
- **Grid.** The rule step is the smallest of 0.5, 1, 2, 5, 10, 20 and 50 km
  (mi when imperial) that gives at most 5 rules across the range, otherwise 100.
  Rules sit at whole multiples of the step that fall strictly inside the range.
- **Marker.** At the newest point. The horizontal mapping keeps the marker's
  radius inside the right edge.
- **What fits (`fits(w, h)`, field pixels).**
  - Padding is max(4, 2% of the width).
  - **Values row:** shown when h ≥ 80. Its height is 24% of h, clamped to
    26–64 px.
  - **Distance labels:** shown when h ≥ 160 and w ≥ 300.
  - **Min/max:** shown when h − 2·padding − (values row height) ≥ 70.
  - The resulting drop order is distance labels, then min/max, then the values
    row. The graph always stays.
  - Reference cases (tested):

    | Size | Shows |
    |---|---|
    | 480×300 | everything |
    | 480×180 | everything |
    | 240×185 | values, min/max, graph |
    | 480×92 | values, graph |

## 6. Rendering: `HistoryGraphRenderer` (layout B)

Agreed on the mockup during brainstorming.

- **Background and columns.** The bitmap is filled black. Each graph pixel gets
  a 1-px column running from the column's peak down to the graph bottom, colored
  `palette.chunkColor(grade)`; `NaN` columns are `0xFF4A4A4A`.
- **Grid rules** are drawn first, behind the colors: white at 25% alpha,
  1.5 px wide.
- **Marker.** A white dot with a black center, as in the overlay. Radius is
  5.5% of the graph height, clamped to 3.5–9 px.
- **Values row**, drawn over the top of the graph:
  - Current elevation plus `ic_elevation` on the left; ascent plus
    `ic_elev_gain` on the right. White, with a dark halo (black at 95%, blur
    15% of the text size).
  - Text size is the smaller of 0.8 × row height and the size at which both
    values, their glyphs and a 12%-of-width gap fit across the field.
  - Glyph height is 0.54 × text size, sitting on the baseline (the overlay
    convention).
  - `--` when a value is unknown.
- **Min/max labels.** Small text (7% of the field height, 11–17 px) at the left
  edge, at the y of max and of min. Color `#DCE8F5`, 4 px halo. The min label
  is left out when it would overlap the max label.
- **Distance labels.** Same small size, `#9AA4AE`, halo, placed at the bottom of
  the graph just right of each rule. One decimal when the step is under
  1 km/mi, integers otherwise.
- **Font.** IBM Plex Sans Condensed Medium via `KarooFonts`, falling back to the
  default typeface.
- **Units.** Elevations in m/ft and distances in km/mi, unitless, via
  `ui/Units.kt`.
- **Empty state** (fewer than 2 points): `--` values and a faint baseline.
- **Preview** (`ViewConfig.preview`): the full `DemoData` route, drawn once as a
  static sample.

## 7. Lifecycle

### 7.1 Recording gate (`ClimberExtension` + `ElevationHistoryRepo`)

- **`fieldPlaced`** is true when the active ride profile has an element with our
  data type id, or when at least one field view is running. The second
  condition is a safety net.
- **Real data.** When `rideActive && fieldPlaced && !demoMode` holds, the repo
  starts the `DISTANCE`, `PRESSURE_ELEVATION_CORRECTION` and `ELEVATION_GAIN`
  consumers, and removes them when it stops holding. This uses the same
  combine → `distinctUntilChanged` pattern the extension already uses.
  - On each `DISTANCE` value, if an elevation is known,
    `history.append(distance, elevation)` runs.
  - `ELEVATION_GAIN` supplies the ascent.
- **Demo data.** When `demoMode && fieldPlaced` holds, each demo tick appends
  `(demoProgress, DemoData.route().profile.elevationAt(demoProgress))`, and
  the ascent is `climbed`. When the demo loop wraps back to 0, the reset rule in
  §4 clears the history.
- **Resets** happen when:
  - a ride ends (Recording/Paused → Idle, next to the existing `engine.reset()`)
  - distance drops by 50 m or more
  - the source switches between demo and real data

### 7.2 Field view (per `startView`)

- **Preview.** If `config.preview` is set, render the sample once, call
  `updateView` and stop. Preview views don't count as running views, so the
  page editor never starts recording.
- **Setup.** Otherwise:
  - send `emitter.onNext(UpdateGraphicConfig(showHeader = false))`
  - register as visible (a counter on Main)
  - launch the loop, whose inputs are the history `version`, the ascent, the
    palette (from settings) and the units
- **Frame pacing.**
  - The first frame is sent immediately.
  - After that, at most one frame every 5 s (`FRAME_INTERVAL_MS = 5000`),
    well clear of karoo-ext's 900 ms guard. Changes that arrive sooner are
    folded into the next frame.
  - A frame is skipped when its signature (size, palette, units, rounded
    values, column y/color arrays) matches the last frame sent.
- **Each frame.**
  1. Snapshot on Main.
  2. Layout and render on Default.
  3. `RemoteViews(packageName, R.layout.field_elevation_history)` with
     `setImageViewBitmap(R.id.graph, bitmap)`.
  4. `emitter.updateView(...)`.
- **Bitmap.** `RGB_565`, since the card is opaque.
  - If w × h × 2 bytes exceeds 300 KB, render at scale √(300 KB / bytes) and let
    the `ImageView` stretch it.
  - Each frame gets a new bitmap. Frames are at least 5 s apart, and the
    previous bitmap may still be in transit to Karoo.
- **Teardown.** `emitter.setCancellable { job.cancel(); unregister }`.
- **Errors.** Each frame runs inside `runCatching` and logs with `Log.e`. The
  field shares its process with the overlay, so a crash would take both down.

### 7.3 Edge cases

- **No elevation yet, or no sensor.** Values show `--`. The graph keeps what it
  has, or shows the empty-state baseline if it has nothing.
- **Indoor ride.** A flat line, thanks to the 20 m minimum height.
- **Paused.** The gate stays open, but distance doesn't advance, so nothing is
  recorded.
- **Field on several pages or at several sizes.** Each view has its own loop;
  they share one history.
- **Process restart mid-ride.** The history starts again from the current
  distance, and the graph shows only the recorded range. This is the known v1
  limitation.

### 7.4 Battery

- Streams run only while the gate is open: three consumers at about 1 Hz.
- Rendering happens only while a view is running, at most every 5 s, and is
  skipped when nothing visible changed.
- Each `RemoteViews` bitmap is at most 300 KB. Layout and rendering stay off the
  main thread.

## 8. Testing

JVM unit tests use no Android classes. They use their own fixtures, not
`DemoData`, following the `ClimbEngineTest` convention.

**`ElevationHistoryTest`**
- Samples in one 10 m bin average into a single point, and a sample that
  doesn't advance distance is ignored.
- Backward jitter under 50 m is ignored; a drop of 50 m or more resets.
- ±25 m smoothing gives the expected values on a known sequence.
- A frozen chunk's grade never changes as more samples arrive.
- Newest-chunk rules: own part ≥ 30 m, trailing 100 m, `NaN`.
- min / max / current / climbed.

**`HistoryGraphLayoutTest`**
- Column grade: uphill wins ([3.1, 9.8, 4.0] → 9.8); all downhill gives the
  steepest descent ([−2, −6, −1] → −6); `NaN` is ignored.
- Column peak includes the interpolated edges and summits between points.
- Horizontal range: under 1 km the graph doesn't stretch; after that it refits.
- Vertical range: at least 20 m, centered.
- Grid steps in km and mi; never more than 5 rules.
- `fits()` gives the four reference cases in §5.
- **Noise check.** A flat road (true grade 0.5%) with seeded Gaussian noise of
  σ = 0.8 m per 1 Hz sample at 9 m/s, over 150 km and 440 columns: at least 95%
  of columns stay under 2% (the Karoo palette's lowest band). In the
  brainstorming simulation this was 0.7% tinted with smoothing, and 24% without.
- **Ramp check.** A clean 300 m ramp at 12%, aligned to chunk boundaries: the
  three ramp chunks read at least 11%, and the flat chunks either side read at
  most 1%.

**`GradePaletteTest`**
- `chunkColor` uses `descentColor` for negative grades on palettes without
  negative bands, and Turbo uses its own negative bands.

## 9. On-device verification and risks

**Checks** (no emulator path, see CLAUDE.md):
1. The field appears in Karoo's page-editor picker under Climber+, with its
   name, icon and preview sample. This confirms the `extension_info.xml`
   attributes.
2. Demo mode with the field placed on a ride page: the graph grows and refits.
   Take screenshots with `adb exec-out screencap -p`.
3. logcat:
   - `startView` and its cancellation when switching pages
   - no `ignoring updateView, too soon`
   - no `TransactionTooLargeException`
   - the logged `ViewConfig` (`viewSize`, `textSize`) for common field sizes
4. A real ride:
   - `PRESSURE_ELEVATION` streams and means altitude
   - its noise level (checks the 0.8 m assumption)
   - ascent matches Karoo's Ascent field
   - `ActiveRideProfile` lists the field
5. Karoo 2: frame render time, and the overlay stays smooth.

| Risk | Fallback |
|---|---|
| `PRESSURE_ELEVATION` is missing or isn't altitude | Relative elevation from `ELEVATION_GAIN − ELEVATION_LOSS`. Shape and colors stay right; min/max and current become relative. Decided after the device check. |
| `ActiveRideProfile` doesn't reflect field placement | The "a view is running" safety net still starts recording, but only from the first time the field's page is shown. |
| Karoo keeps `startView` running while the page is off-screen | Frames are still at most one per 5 s and skipped when unchanged. |
| Real noise is above 0.8 m | Widen `SMOOTH_HALF_WINDOW_M`. |

## 10. Documentation

- **README:** a section on the elevation history field (what it shows, adding it
  in Karoo's page editor), and a line under "What's different". A screenshot
  follows after the device check.
- **CLAUDE.md:** architecture bullets for `field/` and the history classes,
  plus three gotchas:
  - `startView` runs on a binder thread
  - karoo-ext's 900 ms `updateView` guard
  - the `RGB_565` / 300 KB bitmap budget
