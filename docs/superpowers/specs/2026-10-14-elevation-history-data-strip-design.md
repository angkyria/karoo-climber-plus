# Elevation history: data strip — design

- **Date:** 2026-10-14
- **Status:** design approved in chat; spec awaiting review
- **Branch:** `elevation-history-field`
- **Builds on:** `2026-10-12-elevation-history-field-design.md` (the field itself).
  That spec's device verification is still open; the plan finishes it first.

## 1. Intent

Let the rider put up to four live Karoo data values (power, heart rate,
cadence, grade, …) inside the Elevation history field, in a strip along its
bottom, so one field carries the ride's elevation and the numbers that go with
it.

### Decided with the user

| Topic | Decision |
|---|---|
| Where | A strip of up to 4 cells along the bottom of the field. The graph keeps its own values row (current elevation, ascent) on top. |
| Refresh | Every second, like Karoo's own fields, while the strip has at least one field. |
| What can be picked | Any Karoo data stream from the overlay's field list (all `ClimbField`s with a `dataTypeId`, Ki2 included). |
| Not pickable | The overlay's own climb-derived fields (`FieldFormat.DERIVED`: DIST TO TOP, ELEV TO TOP, CLIMB, GRADE, AVG GRADE, LENGTH). They only exist while the overlay is on. Karoo's own `K …` climb streams stay pickable. |
| Settings | A new "Elevation history fields" section with four slots, separate from the overlay's full-screen fields, using the same picker. |
| Empty slots | Each slot can be **None**. The strip shows only the filled slots, sharing the width; with all four None there is no strip. |
| Default | All four None: the field looks and costs exactly what it does today until the rider picks fields. |
| Look | The overlay's full-screen cell style: dark rounded tiles, small grey label, big white value right-aligned. |
| Technique | One bitmap per frame (approach A). The graph layout is reused for up to 5 s; each second only the strip values change. |

### Success criteria

1. With fields picked, the strip shows their live values, refreshed every
   second while the field is on screen, formatted exactly as the overlay's
   full-screen fields show them (same labels, same unitless numbers, same
   metric/imperial handling).
2. With no fields picked, the field is pixel-for-pixel what it is today and
   still sends at most one frame every 5 s.
3. The overlay's full-screen fields don't change (the value formatting moves
   to a shared helper as a pure refactor).
4. No dropped frames (karoo-ext's 900 ms guard), no `TransactionTooLargeException`,
   and render time under 50 ms per frame on the Karoo 2.
5. The strip's streams run only while an Elevation history view is on screen.
6. All new math is JVM-tested and `./gradlew testDebugUnitTest` passes.

### Non-goals

- Different strip fields per placed instance of the field (all instances share
  the one setting).
- The overlay's climb-derived fields in the strip.
- Unit labels (the strip stays unitless, like the rest of Climber+).
- More than four cells, or cells anywhere but the bottom.

## 2. Settings

- `Settings.historyFields: List<ClimbField?>`, always 4 entries, `null` = None.
  Default `listOf(null, null, null, null)`.
- Persisted under a new DataStore key `history_fields` as the four entry names
  joined by commas, empty for None (`"POWER_3S,,HEART_RATE,"`).
  - A missing key, a list that doesn't have exactly four entries, an unknown
    name or a `DERIVED` field all read as None for that slot (or all four, for
    a malformed list). Entry names are never renamed (existing `ClimbField` rule).
- Settings screen: section "Elevation history fields", rows "Field 1" … "Field 4".
  - The existing `FieldPickerDialog` gains a "None" entry at the top of the
    category list and an optional filter. The strip's picker hides `DERIVED`
    fields; the overlay's picker is unchanged (no None, no filter).

## 3. Values: shared formatting

- New `ui/FieldText.kt`, pure Kotlin:
  `fun fieldText(field: ClimbField, values: Map<String, Map<String, Double>>, imperial: Boolean): String`.
  - Moved verbatim from `ClimbOverlayView.sysFieldValue` and its helpers
    (`singleValue`, `formatDuration`, `formatClock`), including the two GEARS
    variants (Karoo's multi-field shifting stream; Ki2's front + rear streams via
    `extraDataTypeId`). `--` when the stream has no value.
  - `ClimbOverlayView.fieldValue` keeps the `DERIVED` cases and delegates every
    other field to `fieldText`.
- Labels: `ClimbField.cellLabel` (the short grid label the overlay already uses).

## 4. Layout

All in field pixels, pure and JVM-tested (`HistoryGraphLayout`).

- **When the strip shows:** at least one slot filled and field height ≥ 120 px.
- **Strip height:** 30% of the field height, clamped to 48–84 px, at the bottom
  of the field.
- **Cells:** the filled slots in slot order, equal widths across the field
  width minus the padding, with gaps of 1.5% of the width.
  - Label: `cellLabel`, top-right, in the overlay's sub-text color `#DCE8F5`,
    22% of the cell height, shrunk to fit the cell width.
  - Value: white, right-aligned near the bottom, 45% of the cell height,
    shrunk to fit the cell width.
  - Tile: `#1C1C1E`, 8 px corner radius (the overlay's `cellPaint` / `CELL_CORNER`).
- **Graph area:** the field minus the strip (and one gap). The 2026-10-12
  layout runs unchanged on that area: values row, min/max, distance labels and
  their drop order are decided by the area's size, not the field's.
- **Reference sizes** (tested):

  | Field | Strip | Graph area shows |
  |---|---|---|
  | 478×190, 4 fields | 57 px | values row, min/max, graph (no distance labels) |
  | 478×721, 4 fields | 84 px | everything |
  | 478×100, 4 fields | none (too short) | as today |
  | 478×190, no fields | none | as today |

- **Frame:** `HistoryFrame` gains the strip cells (rect, label, value text, text
  sizes). Frames with equal content still render identical pixels, so identical
  frames are still skipped.
- **Preview** (page editor): the strip shows the picked labels with `--`.

## 5. Refresh and rendering

- **Frame interval:** 1000 ms when the strip shows, 5000 ms otherwise
  (`FramePacer`'s interval becomes a per-frame input). 1000 ms stays clear of
  karoo-ext's 900 ms guard.
- **Inputs:** the render loop also watches the shared stream values
  (`sysValues`) and the strip setting.
- **Graph reuse:** the graph part (snapshot copy on Main + `HistoryGraphLayout`)
  is recomputed when the history changed **and** 5 s have passed since the last
  graph layout, or at once when the size, palette, units or strip fields
  change. In between, each frame reuses the last graph layout and only
  recomputes the strip cells. So the graph moves every 5 s as today, and only
  the numbers tick every second.
- **Without a strip** nothing changes: 5 s frames, and every frame lays out the
  graph, as today.
- **Rendering:** each frame is still one RGB_565 bitmap within the 300 KB budget
  (`bitmapScale`). Render time is measured on the Karoo 2; if it is over 50 ms,
  the graph part is cached as a bitmap and only the strip is drawn each second.
- **Errors:** unchanged (each frame catches `Exception`, logs, rethrows
  `CancellationException`).

## 6. Streams

- `ClimberExtension` already starts and stops `OnStreamState` consumers for a
  wanted set of data type ids (`reconcileSysStreams`) into the shared
  `sysValues` map. The wanted set becomes the union of:
  - the overlay's full-screen fields while `overlay.fieldsVisible` (as today)
  - the strip fields while `historyField.runningViews > 0`
  - including each field's `extraDataTypeId` (Ki2 gears).
- The union is computed by a pure function (JVM-tested). When the field's page
  goes off screen, Karoo stops the view, `runningViews` drops to 0 and the
  strip's consumers are removed.

## 7. Files

| File | Change |
|---|---|
| `ui/FieldText.kt` | new: `fieldText()`, moved from `ClimbOverlayView` |
| `overlay/ClimbOverlayView.kt` | delegates non-derived fields to `fieldText()`; no behavior change |
| `settings/Settings.kt` | `historyFields`; pure parse/format helpers for the stored list |
| `settings/SettingsRepo.kt` | `history_fields` key |
| `settings/SettingsActivity.kt` | new section; picker gains None and a filter |
| `field/HistoryGraphLayout.kt` | strip layout, graph area, frame gains cells |
| `field/HistoryGraphRenderer.kt` | draws the cells |
| `field/FramePacer.kt` | interval per frame |
| `field/ElevationHistoryField.kt` | watches `sysValues` and the strip setting; graph reuse; 1 s / 5 s pacing |
| `ClimberExtension.kt` | passes `sysValues`; wanted-streams union |

## 8. Testing

JVM (no Android classes, own fixtures):

- `FieldTextTest`: every `FieldFormat` against the values the overlay shows
  today, metric and imperial — speed, VAM, distance, elevation, integer,
  percent, number, number2, duration (incl. negative and millisecond input),
  clock (pinned time zone), temperature, pressure, both GEARS variants, missing
  values → `--`.
- Settings list: round trip with None slots; missing key, wrong count, unknown
  name and `DERIVED` names read as None.
- Strip layout: when it shows (fields, height); strip height clamps; 1–4 equal
  cells; the §4 reference sizes; graph area handed to the existing layout;
  degenerate sizes still give a drawable frame.
- Streams union: overlay only, strip only, both, Ki2 extra ids, nothing.
- Pacing: 1000 ms with a strip, 5000 ms without; graph reuse window.

On the Karoo 2:

1. Picker: the new settings section, None, no `DERIVED` fields.
2. The user's 478×190 slot and a full page: cells, labels, values.
3. Values tick every second while they change (frame log timestamps ≥ 1 s
   apart; longer gaps only when nothing visible changed); no
   `TransactionTooLargeException`; no `frame failed`.
4. Render time per frame (temporary log), under 50 ms.
5. Page switch: the strip's consumers stop (log), and restart on return.
6. Overlay full-screen fields unchanged (screenshot against `docs/img/full-view.png`).

## 9. Risks

| Risk | Fallback |
|---|---|
| A 300 KB bitmap every second is too much for binder or battery | Cap the 1 Hz frames at a smaller bitmap budget, or cache the graph bitmap (§5). Measured on the device. |
| Render time over 50 ms on the Karoo 2 | Cache the graph part as a bitmap; draw only the strip each second. |
| A stream's data point has no `SINGLE` key | `fieldText` already falls back to the first value, as the overlay does. |
