# Elevation History Field Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a graphical Karoo data field to Climber+ showing the elevation ridden so far on the current ride, refitting to the whole ride and colored by grade with the Climber+ palette.

**Architecture:**
- **Recording.** `ElevationHistoryRepo` records Karoo's `DISTANCE`, `PRESSURE_ELEVATION_CORRECTION` and `ELEVATION_GAIN` streams (or the demo feed) into a pure `ElevationHistory` of 10 m points. It records only while `HistoryGate` allows it.
- **Layout.** `HistoryGraphLayout`, also pure, turns a `HistorySnapshot` into a `HistoryFrame`. The snapshot supplies ±25 m smoothing and frozen 100 m grades; the frame has one column per pixel, colored by the steepest chunk it covers.
- **Rendering.** `ElevationHistoryField` (a `DataTypeImpl`) paints each frame into an RGB_565 bitmap with `HistoryGraphRenderer`. It sends the bitmap through `RemoteViews` at most every 5 s.

**Tech Stack:** Kotlin 2.0.20, AGP 8.5.2, karoo-ext 1.1.9, kotlinx-coroutines 1.8.1, JUnit 4.13.2, Android Canvas/`RemoteViews`. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-10-12-elevation-history-field-design.md`. Read it alongside this plan; section numbers (§) below refer to it.

## Global Constraints

- **Java.** Every Gradle command runs after `export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`, from the repo root `/Users/angkyria/github/karoo-climber-plus`.
- **Unit tests** are JVM-only (`./gradlew testDebugUnitTest`).
  - Tested code must not call `android.*` (there's no Robolectric, and the stubs throw).
  - Fixtures are independent of `DemoData` (CLAUDE.md convention).
  - Test files live flat in `app/src/test/kotlin/com/hazzus/karooclimber/`, in package `com.hazzus.karooclimber`, with backtick test names.
  - To count tests after a run: `grep -ho 'tests="[0-9]*"' app/build/test-results/testDebugUnitTest/*.xml | grep -o '[0-9]*' | paste -sd+ - | bc`. The baseline is **46**.
- **Dependencies.** None added. No Compose or Glance in the field (Compose stays in `SettingsActivity` only). minSdk 26: the target device is a Karoo 2 on Android 8.1.
- **Ids.**
  - extension id `karoo-climber`
  - field typeId `elevation-history`
  - data type id `TYPE_EXT::karoo-climber::elevation-history`
  - display name "Elevation history"
  - description "Elevation ridden so far, colored by grade"
- **Constants, copied from the spec:**

  | Constant | Value |
  |---|---|
  | `BIN_M` | 10 |
  | `SMOOTH_HALF_WINDOW_M` | 25 |
  | `CHUNK_M` | 100 |
  | `LIVE_MIN_M` | 30 |
  | `RESET_DROP_M` | 50 |
  | `MIN_X_SPAN_M` | 1000 |
  | `MIN_Y_SPAN_M` | 20 |
  | `FRAME_INTERVAL_MS` | 5000 |
  | `BITMAP_BUDGET_BYTES` | 300 000 (RGB_565 bitmaps) |
  | `NO_GRADE_COLOR` | `0xFF4A4A4A` |

- **Overlay behavior must not change.** Task 2 is a pure refactor of `ClimbOverlayView`.
- **Look.** Numbers are unitless. Text uses IBM Plex Sans Condensed via `KarooFonts`. Units (m/ft, km/mi) come from the Karoo rider profile.
- **Threading.**
  - History and repo are mutated only on Main, in the extension's `Dispatchers.Main.immediate` scope.
  - `startView` arrives on a binder thread.
  - Layout and rendering run on `Dispatchers.Default`.
- **Never crash the process.** The field shares it with the overlay. Each frame catches `Exception`, logs it, and rethrows `CancellationException`.
- **Commits** follow the repo style: a present-tense summary such as "Adds …" or "Extracts …", then a blank line, then `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Add files by path; never use `git add -A` or `git add .`, because the untracked `.superpowers/` must stay out.
- **Device.** A Karoo 2 (`adb devices` shows `KAROO20ALC061504882`). It came with the official 0.1.6 release, signed `CN=hazzus`. This Mac can only build debug, so the first install needs that release uninstalled, and **only after the user says yes** (Task 1).

## Review Focus

Five inputs that the spec implies but doesn't spell out:

1. **Very long rides (400 km).** The history must keep one point per 10 m bin, and a frame must still hold one column per pixel with no gray gaps. Tested in Task 3 (`a 400 km ride keeps one point per 10 m bin`) and Task 4 (`a 400 km ride still yields one column per pixel`).
2. **History starting mid-ride** (extension restart, or the field added late). Grid labels must show absolute ride distance ("40, 45 … 60"), not distance since recording began. Tested in Task 4 (`a history that starts mid-ride labels absolute ride distance`).
3. **Degenerate `ViewConfig` sizes** (0×0, 1×1, tiny cells). Layout must not divide by zero and must still produce a drawable frame. Tested in Task 4 (`degenerate field sizes still give a drawable frame`).
4. **Below-sea-level elevations.** Labels and values keep their minus sign, and ranges still work. Tested in Task 4 (`below sea level elevations keep their sign`) and Task 2 (`elevation text truncates to whole meters or feet`, the `-12` case).
5. **GPS forward jumps** (tunnels, signal loss). The graph interpolates a straight line across the gap, with no `NaN` and no crash. Tested in Task 3 (`a forward jump interpolates straight across the gap`).

---

### Task 1: Device probe (throwaway): confirm the elevation, ascent, distance and profile data

Checks spec §2 and the §9 risks on the real Karoo 2 before any feature code is written. Nothing from this task's code is kept.

**Files:**
- Modify (temporarily, reverted in Step 10): `app/src/main/kotlin/com/hazzus/karooclimber/ClimberExtension.kt`
- Modify (kept): `docs/superpowers/specs/2026-10-12-elevation-history-field-design.md` (§9 results)

**Interfaces:**
- Consumes: nothing.
- Produces: findings recorded in spec §9. Tasks 3–5 rely on them: the field keys used in `ElevationHistoryRepo` (Task 5), `SMOOTH_HALF_WINDOW_M` (Task 3) and the noise σ in Task 4's noise test.

- [ ] **Step 1: Ask the user before uninstalling the release**

Ask the user, word for word, and wait for an explicit yes:

> To install a test build I have to uninstall Climber+ 0.1.6 (the official release) from the Karoo. That resets Climber+'s settings (palette, fields, positions) and its draw-over-apps permission. Updates from GitHub releases also stop applying until you reinstall the release from the releases page later. OK to uninstall?

If the answer is no: skip to Step 11. Record "probe skipped: no device install" in spec §9 and continue with Task 2. Before Task 6, ask again.

- [ ] **Step 2: Add the probe**

In `ClimberExtension.kt`, add these two imports next to the other `io.hammerhead.karooext.models` imports:

```kotlin
import io.hammerhead.karooext.models.ActiveRideProfile
import io.hammerhead.karooext.models.DataType
```

Then insert this block in `onCreate()` directly after `navigationRepo.start()`:

```kotlin
        // ---- TEMP elevation probe (plan Task 1), reverted before Task 2 ----
        listOf(
            DataType.Type.PRESSURE_ELEVATION_CORRECTION,
            DataType.Type.ELEVATION_GAIN,
            DataType.Type.DISTANCE,
        ).forEach { typeId ->
            consumerIds += karooSystem.addConsumer<OnStreamState>(
                OnStreamState.StartStreaming(typeId),
            ) { event ->
                val state = event.state
                val detail = (state as? StreamState.Streaming)?.dataPoint?.values ?: state
                Log.i("ElevProbe", "$typeId $detail")
            }
        }
        consumerIds += karooSystem.addConsumer<ActiveRideProfile> { event ->
            Log.i(
                "ElevProbe",
                "PROFILE ${event.profile.name}: " +
                    event.profile.pages.flatMap { page -> page.elements.map { it.dataTypeId } }.distinct(),
            )
        }
```

- [ ] **Step 3: Build**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`. The APK is at `app/build/outputs/apk/debug/karoo-climber-plus-debug.apk`.

- [ ] **Step 4: Uninstall the release and install the probe**

```bash
adb uninstall com.hazzus.karooclimber
adb install app/build/outputs/apk/debug/karoo-climber-plus-debug.apk
```
Expected: `Success` from both commands.

- [ ] **Step 5: Confirm Karoo binds the extension**

```bash
adb logcat -c
```

Ask the user to open the Ride screen on the Karoo. Then run:

```bash
sleep 20; adb logcat -d | grep -E "ClimberExtension: Karoo system connected|ElevProbe" | head -20
```
Expected: `ClimberExtension: Karoo system connected=true`, then `ElevProbe` lines, including one `PROFILE …` line.

If there's no output, run `adb reboot && adb wait-for-device && sleep 60`, ask the user to open the Ride screen again, and repeat this step.

- [ ] **Step 6: Capture a stationary sample**

Create a temp directory outside the repo with `PROBE=$(mktemp -d)`, then run `adb logcat -c`.

Ask the user:
> Please start a ride recording and leave the Karoo completely still for 2 minutes. Tell me when the 2 minutes are up.

When they confirm, run:

```bash
adb logcat -d -s ElevProbe:I > "$PROBE/still.log"; wc -l "$PROBE/still.log"
```
Expected: at least 200 lines.

- [ ] **Step 7: Capture a moving sample**

Run `adb logcat -c`, then ask the user:
> Now walk with the Karoo for about 3 minutes, including at least one flight of stairs or a short hill (3 m or more of climbing). Then end the ride and discard it. Tell me when you're done.

When they confirm, run:

```bash
adb logcat -d -s ElevProbe:I > "$PROBE/walk.log"; wc -l "$PROBE/walk.log"
```

- [ ] **Step 8: Analyze**

```bash
echo "--- field keys per stream"; grep -ho 'FIELD_[A-Z_]*_ID' "$PROBE"/*.log | sort | uniq -c
echo "--- profile ids"; grep PROFILE "$PROBE"/*.log | tail -2
grep -o 'FIELD_PRESSURE_ELEVATION_ID=[-0-9.E]*' "$PROBE/still.log" | cut -d= -f2 > "$PROBE/pe.txt"
python3 -I - "$PROBE/pe.txt" <<'EOF'
import statistics, sys
v = [float(x) for x in open(sys.argv[1])]
d = [b - a for a, b in zip(v, v[1:])]
print(f"{len(v)} samples, {min(v):.1f}..{max(v):.1f} m")
print(f"per-sample noise ~ {statistics.pstdev(d) / 2 ** 0.5:.2f} m (sd of steps / sqrt 2)")
EOF
echo "--- walk: distance, gain, elevation"
grep -o 'FIELD_DISTANCE_ID=[0-9.E]*' "$PROBE/walk.log" | sed -n '1p;$p'
grep -o 'FIELD_ELEVATION_GAIN_ID=[0-9.E]*' "$PROBE/walk.log" | sed -n '1p;$p'
grep -o 'FIELD_PRESSURE_ELEVATION_ID=[-0-9.E]*' "$PROBE/walk.log" | cut -d= -f2 | sort -n | sed -n '1p;$p'
```

What to look for:
- The PEC stream carries `FIELD_PRESSURE_ELEVATION_ID`, and its values are plausible for the local altitude. Ask the user what Karoo's own elevation field shows.
- The noise estimate, in meters.
- `FIELD_DISTANCE_ID` increases during the walk.
- `FIELD_ELEVATION_GAIN_ID` rises after the stairs or hill.
- The format of the `PROFILE` ids, e.g. `TYPE_SPEED_ID`. The ids use the full `TYPE_…_ID` form that `ElevationHistoryField.DATA_TYPE_ID` will match.

- [ ] **Step 9: Decide (stop if the spec doesn't hold)**

| Finding | Action |
|---|---|
| `FIELD_PRESSURE_ELEVATION_ID` present with plausible altitude | Proceed as written. |
| Altitude present under a different field key | Proceed. In Task 5 Step 3, replace `DataType.Field.PRESSURE_ELEVATION` with that key's constant, and note it in spec §2 in Step 11. |
| No altitude anywhere in the PEC stream | **STOP.** Report to the user. Spec §9's fallback (relative elevation from `ELEVATION_GAIN − ELEVATION_LOSS`) needs a spec update and approval before Task 2. |
| Distance or gain keys differ from `FIELD_DISTANCE_ID` / `FIELD_ELEVATION_GAIN_ID` | Note them, and use them in Task 5 Step 3 instead. |
| Noise estimate ≤ 0.8 m | Keep `SMOOTH_HALF_WINDOW_M = 25.0` and Task 4's noise σ of 0.8. |
| Noise estimate > 0.8 m | Use `SMOOTH_HALF_WINDOW_M = 50.0` in Task 3 and the measured σ in Task 4's noise test. Note both in spec §9. |

- [ ] **Step 10: Revert the probe**

```bash
git checkout -- app/src/main/kotlin/com/hazzus/karooclimber/ClimberExtension.kt
git status --short
```
Expected: only `?? .superpowers/` (and the spec, if you've already edited it).

- [ ] **Step 11: Record the results in the spec**

In spec §9, add this subsection after the risk table. Fill every bracket with what you actually measured:

```markdown
### Probe results (2026-10-12, Karoo 2, Android 8.1)

- `PRESSURE_ELEVATION_CORRECTION` fields: [keys seen]; altitude plausible: [yes/no, value vs. Karoo's elevation field]
- Stationary noise: [x.xx] m per sample → smoothing half-window [25/50] m
- `DISTANCE` key: [key]; `ELEVATION_GAIN` key: [key]; gain on the stairs: [from → to]
- `ActiveRideProfile` ids look like: [example]
- Decision: [proceed as written / changes made]
```

Commit:

```bash
git add docs/superpowers/specs/2026-10-12-elevation-history-field-design.md
git commit -q -F - <<'EOF'
Records elevation probe results in the spec

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 2: Extract the palette, units and font helpers shared by the overlay and the field

Spec §3. This is a pure refactor: the overlay must look and behave exactly as before.

**Files:**
- Modify: `app/src/main/kotlin/com/hazzus/karooclimber/palette/GradePalettes.kt`
- Create: `app/src/main/kotlin/com/hazzus/karooclimber/ui/Units.kt`
- Create: `app/src/main/kotlin/com/hazzus/karooclimber/ui/KarooFonts.kt`
- Modify: `app/src/main/kotlin/com/hazzus/karooclimber/overlay/ClimbOverlayView.kt`
- Test: `app/src/test/kotlin/com/hazzus/karooclimber/GradePaletteTest.kt`
- Test: `app/src/test/kotlin/com/hazzus/karooclimber/UnitsTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces:
  - `GradePalette.chunkColor(grade: Double): Int`, a member of the existing data class.
  - `GradePalettes.descentColor: Int`, now `0xFF507896.toInt()`.
  - In package `com.hazzus.karooclimber.ui`:
    - `const val METERS_PER_MILE = 1609.344`
    - `const val FEET_PER_METER = 3.28084`
    - `fun distanceText(meters: Double, imperial: Boolean): String`
    - `fun elevationText(meters: Double, imperial: Boolean): String`
    - `fun kmText(meters: Double, imperial: Boolean): String`
    - `fun distanceWithUnitText(meters: Double, imperial: Boolean): String`
    - `object KarooFonts { val plexCondensed: Typeface? }`

- [ ] **Step 1: Write the failing palette test**

Create `app/src/test/kotlin/com/hazzus/karooclimber/GradePaletteTest.kt`:

```kotlin
package com.hazzus.karooclimber

import com.hazzus.karooclimber.palette.GradePalettes
import org.junit.Assert.assertEquals
import org.junit.Test

class GradePaletteTest {

    private val karoo = GradePalettes.byId("karoo")
    private val turbo = GradePalettes.byId("turbo")

    @Test
    fun `downhill chunks use the descent color on palettes without negative bands`() {
        assertEquals(GradePalettes.descentColor, karoo.chunkColor(-3.0))
        assertEquals(karoo.colorFor(0.0), karoo.chunkColor(0.0))
        assertEquals(karoo.colorFor(9.0), karoo.chunkColor(9.0))
    }

    @Test
    fun `turbo colors downhill chunks with its own negative bands`() {
        assertEquals(0xFF5783E9.toInt(), turbo.chunkColor(-7.0))
        assertEquals(0xFF401C4C.toInt(), turbo.chunkColor(-20.0))
    }

    @Test
    fun `descent color keeps its previous rgb value`() {
        assertEquals(0xFF507896.toInt(), GradePalettes.descentColor)
    }
}
```

- [ ] **Step 2: Write the failing units test**

Create `app/src/test/kotlin/com/hazzus/karooclimber/UnitsTest.kt`:

```kotlin
package com.hazzus.karooclimber

import com.hazzus.karooclimber.ui.distanceText
import com.hazzus.karooclimber.ui.distanceWithUnitText
import com.hazzus.karooclimber.ui.elevationText
import com.hazzus.karooclimber.ui.kmText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

class UnitsTest {

    private val defaultLocale = Locale.getDefault()

    // "%.1f" follows the default locale, as on the device: pin it for stable expectations
    @Before
    fun pinLocale() = Locale.setDefault(Locale.US)

    @After
    fun restoreLocale() = Locale.setDefault(defaultLocale)

    @Test
    fun `distance text switches from meters to one-decimal km at 1 km`() {
        assertEquals("400", distanceText(400.0, imperial = false))
        assertEquals("999", distanceText(999.9, imperial = false))
        assertEquals("1.4", distanceText(1400.0, imperial = false))
    }

    @Test
    fun `imperial distance text switches from feet to miles at a tenth of a mile`() {
        assertEquals("328", distanceText(100.0, imperial = true))
        assertEquals("0.9", distanceText(1448.4, imperial = true))
    }

    @Test
    fun `elevation text truncates to whole meters or feet`() {
        assertEquals("412", elevationText(412.9, imperial = false))
        assertEquals("1354", elevationText(412.9, imperial = true))
        assertEquals("-12", elevationText(-12.4, imperial = false))
    }

    @Test
    fun `km text always has one decimal`() {
        assertEquals("0.7", kmText(700.0, imperial = false))
        assertEquals("0.4", kmText(700.0, imperial = true))
    }

    @Test
    fun `distance with unit text carries the unit label`() {
        assertEquals("400 m", distanceWithUnitText(400.0, imperial = false))
        assertEquals("2.0 km", distanceWithUnitText(2000.0, imperial = false))
        assertEquals("328 ft", distanceWithUnitText(100.0, imperial = true))
        assertEquals("1.2 mi", distanceWithUnitText(2000.0, imperial = true))
    }
}
```

- [ ] **Step 3: Run the tests to see them fail**

Run: `./gradlew testDebugUnitTest`
Expected: FAIL. Test compilation reports `Unresolved reference: chunkColor` and `Unresolved reference: ui`.

- [ ] **Step 4: Add `chunkColor` and make `descentColor` JVM-safe**

In `palette/GradePalettes.kt`:

1. Delete the line `import android.graphics.Color`.
2. In `data class GradePalette`, after `colorFor`, add:

```kotlin

    /**
     * Color of a grade chunk (climb profile, chunk strip, elevation history):
     * downhill chunks fall back to [GradePalettes.descentColor] when the palette
     * has no negative bands.
     */
    fun chunkColor(grade: Double): Int =
        if (grade < 0 && bands.last().first >= 0.0) GradePalettes.descentColor else colorFor(grade)
```

3. Replace

```kotlin
    /** Fallback for flat/descending stretches in palettes without negative bands. */
    val descentColor: Int = Color.rgb(0x50, 0x78, 0x96)
```

with

```kotlin
    /**
     * Fallback for descending stretches in palettes without negative bands:
     * rgb(0x50, 0x78, 0x96), a plain literal so palette code runs in JVM tests.
     */
    val descentColor: Int = 0xFF507896.toInt()
```

- [ ] **Step 5: Create `ui/Units.kt`**

```kotlin
package com.hazzus.karooclimber.ui

// Unit conversion and unitless number formatting shared by the overlay and the
// elevation history field. Values carry no unit labels by design (native-Climber
// parity, see CLAUDE.md); `imperial` follows the Karoo rider profile.

const val METERS_PER_MILE = 1609.344
const val FEET_PER_METER = 3.28084

/** m under 1 km, km with one decimal above; ft under 0.1 mi, mi with one decimal above. */
fun distanceText(meters: Double, imperial: Boolean): String = if (imperial) {
    val miles = meters / METERS_PER_MILE
    if (miles < 0.1) "${(meters * FEET_PER_METER).toInt()}" else "%.1f".format(miles)
} else {
    if (meters < 1000) "${meters.toInt()}" else "%.1f".format(meters / 1000.0)
}

/** Whole meters (feet when imperial), truncated as the overlay always has. */
fun elevationText(meters: Double, imperial: Boolean): String =
    if (imperial) "${(meters * FEET_PER_METER).toInt()}" else "${meters.toInt()}"

/** Always km (mi), one decimal: 700 m -> "0.7". */
fun kmText(meters: Double, imperial: Boolean): String =
    "%.1f".format(if (imperial) meters / METERS_PER_MILE else meters / 1000.0)

/** With a unit label, for banners: "400 m", "1.4 km", "328 ft", "0.9 mi". */
fun distanceWithUnitText(meters: Double, imperial: Boolean): String = if (imperial) {
    val miles = meters / METERS_PER_MILE
    if (miles < 0.1) "${(meters * FEET_PER_METER).toInt()} ft" else "%.1f mi".format(miles)
} else {
    if (meters < 1000) "${meters.toInt()} m" else "%.1f km".format(meters / 1000.0)
}
```

- [ ] **Step 6: Create `ui/KarooFonts.kt`**

```kotlin
package com.hazzus.karooclimber.ui

import android.graphics.Typeface

/**
 * Karoo's native data-field font (IBM Plex Sans Condensed, shipped in
 * /system/fonts), used for all overlay and field text at its single Medium
 * weight. Null on other devices -> Paint falls back to the default typeface.
 */
object KarooFonts {
    val plexCondensed: Typeface? by lazy {
        runCatching { Typeface.createFromFile("/system/fonts/IBMPlexSansCondensed-Medium.otf") }.getOrNull()
    }
}
```

- [ ] **Step 7: Point `ClimbOverlayView` at the shared helpers**

In `overlay/ClimbOverlayView.kt`:

1. **Imports.** Delete `import android.graphics.Typeface`. Add after `import com.hazzus.karooclimber.settings.Settings`:

```kotlin
import com.hazzus.karooclimber.ui.KarooFonts
import com.hazzus.karooclimber.ui.distanceText
import com.hazzus.karooclimber.ui.distanceWithUnitText
import com.hazzus.karooclimber.ui.elevationText
import com.hazzus.karooclimber.ui.kmText
```

2. **Font.** Delete this block, including its comment:

```kotlin
    // Karoo's native data-field font (IBM Plex Sans Condensed, shipped in
    // /system/fonts), used for all overlay text at its single Medium weight.
    // Null on other devices -> Paint falls back to the default typeface.
    private val plexCondensed: Typeface? = runCatching {
        Typeface.createFromFile("/system/fonts/IBMPlexSansCondensed-Medium.otf")
    }.getOrNull()

```

   Then replace every `typeface = plexCondensed` (5 places) with `typeface = KarooFonts.plexCondensed`.

3. **Chunk strip color.** In `drawChunkStrip`, replace

```kotlin
            rowPaint.color = if (chunk.grade < 0 && palette.bands.last().first >= 0.0) {
                GradePalettes.descentColor
            } else {
                palette.colorFor(chunk.grade)
            }
```

   with

```kotlin
            rowPaint.color = palette.chunkColor(chunk.grade)
```

4. **Profile color.** In `drawProfile`, replace

```kotlin
            fillPaint.color = if (seg.grade < 0 && palette.bands.last().first >= 0.0) {
                GradePalettes.descentColor
            } else {
                palette.colorFor(seg.grade)
            }
```

   with

```kotlin
            fillPaint.color = palette.chunkColor(seg.grade)
```

5. **Unit functions.** Replace the whole units section, from the `// ---- units` comment through the end of `formatKm`, with the block below. This also drops `formatElevation`, which nothing calls.

```kotlin
    // ------------------------------------------------------------------ units
    // Shared with the elevation history field (ui/Units.kt), bound to this view's units.

    private fun formatDistance(meters: Double): String = distanceWithUnitText(meters, imperial)

    /** Unitless distance value (m under 1 km, km with one decimal above; mi imperial). */
    private fun formatValue(meters: Double): String = distanceText(meters, imperial)

    /** Unitless elevation value. */
    private fun formatElevationValue(meters: Double): String = elevationText(meters, imperial)

    /** Always km (or mi), one decimal: 700 m -> "0.7". */
    private fun formatKm(meters: Double): String = kmText(meters, imperial)
```

- [ ] **Step 8: Run the tests and build**

Run: `./gradlew testDebugUnitTest assembleDebug`
Expected: `BUILD SUCCESSFUL`, with **54** tests and 0 failures. Count them with the command in Global Constraints.

- [ ] **Step 9: Check on the device that the overlay is unchanged**

This step applies only if Task 1 installed a debug build. Run:

```bash
adb install -r app/build/outputs/apk/debug/karoo-climber-plus-debug.apk
adb shell appops set com.hazzus.karooclimber SYSTEM_ALERT_WINDOW allow
adb shell am start -n com.hazzus.karooclimber/.settings.SettingsActivity
```

Ask the user to turn on **Demo mode** and open a ride page. Wait until the drawer shows a climb, then:

```bash
adb shell input keyevent KEYCODE_WAKEUP; adb exec-out screencap -p > "$(mktemp -d)/overlay.png"
```

View the screenshot next to `docs/img/drawer-climbing.png`. The palette colors, fonts and number formats must match.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/kotlin/com/hazzus/karooclimber/palette/GradePalettes.kt \
  app/src/main/kotlin/com/hazzus/karooclimber/ui/Units.kt \
  app/src/main/kotlin/com/hazzus/karooclimber/ui/KarooFonts.kt \
  app/src/main/kotlin/com/hazzus/karooclimber/overlay/ClimbOverlayView.kt \
  app/src/test/kotlin/com/hazzus/karooclimber/GradePaletteTest.kt \
  app/src/test/kotlin/com/hazzus/karooclimber/UnitsTest.kt
git commit -q -F - <<'EOF'
Extracts palette chunk color, unit formatting and font helpers

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 3: `ElevationHistory` and `HistorySnapshot` (the pure recording model)

Spec §4.

**Files:**
- Create: `app/src/main/kotlin/com/hazzus/karooclimber/data/ElevationHistory.kt`
- Test: `app/src/test/kotlin/com/hazzus/karooclimber/ElevationHistoryTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `class ElevationHistory(smoothHalfWindow: Double = HistorySnapshot.SMOOTH_HALF_WINDOW_M)`
    - `fun append(distance: Double, elevation: Double)`
    - `fun reset()`
    - `fun snapshot(): HistorySnapshot`
    - `val version: Long`
    - companion: `BIN_M = 10.0`, `RESET_DROP_M = 50.0`
  - `class HistorySnapshot(distances: DoubleArray, rawElevations: DoubleArray, current: Double?, smoothHalfWindow: Double = SMOOTH_HALF_WINDOW_M)`
    - `val distances: DoubleArray`, `val rawElevations: DoubleArray`, `val current: Double?`
    - `val size: Int`, `val chunkOrigin: Double`, `val newest: Double`
    - `val smoothed: DoubleArray`, `val grades: DoubleArray`
    - `val minElevation: Double`, `val maxElevation: Double`, `val climbed: Double`
    - `fun smoothedAt(distance: Double): Double`, which requires `size > 0`
    - companion: `SMOOTH_HALF_WINDOW_M = 25.0`, `CHUNK_M = 100.0`, `LIVE_MIN_M = 30.0`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/hazzus/karooclimber/ElevationHistoryTest.kt`:

```kotlin
package com.hazzus.karooclimber

import com.hazzus.karooclimber.data.ElevationHistory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class ElevationHistoryTest {

    /** Exact arithmetic: without smoothing, smoothed == bin means. */
    private fun raw() = ElevationHistory(smoothHalfWindow = 0.0)

    /** Flat at 0 m until 205 m, then 10 %; one sample every 10 m from 5 m. */
    private fun flatThenTenPercent(until: Double): ElevationHistory {
        val h = raw()
        var d = 5.0
        while (d <= until) {
            h.append(d, if (d <= 205.0) 0.0 else (d - 205.0) * 0.1)
            d += 10.0
        }
        return h
    }

    @Test
    fun `samples in one 10 m bin average into one point`() {
        val h = raw()
        h.append(1.0, 100.0)
        h.append(4.0, 102.0)
        h.append(7.0, 104.0)
        h.append(12.0, 110.0) // next bin, still open
        val s = h.snapshot()
        assertEquals(2, s.size)
        assertEquals(4.0, s.distances[0], 1e-9)
        assertEquals(102.0, s.rawElevations[0], 1e-9)
        assertEquals(12.0, s.distances[1], 1e-9)
        assertEquals(110.0, s.current!!, 1e-9)
    }

    @Test
    fun `samples that do not advance distance are ignored`() {
        val h = raw()
        h.append(5.0, 100.0)
        val version = h.version
        h.append(5.0, 130.0)
        h.append(3.0, 140.0)
        val s = h.snapshot()
        assertEquals(1, s.size)
        assertEquals(100.0, s.rawElevations[0], 1e-9)
        assertEquals(100.0, s.current!!, 1e-9)
        assertEquals(version, h.version)
    }

    @Test
    fun `backward jitter under 50 m is ignored and a bigger drop starts over`() {
        val h = raw()
        for (d in 0..1000 step 10) h.append(d.toDouble(), 200.0)
        h.append(960.0, 300.0) // 40 m back
        assertEquals(1000.0, h.snapshot().newest, 1e-9)
        h.append(940.0, 300.0) // 60 m back
        val s = h.snapshot()
        assertEquals(1, s.size)
        assertEquals(940.0, s.distances[0], 1e-9)
        assertEquals(300.0, s.current!!, 1e-9)
    }

    @Test
    fun `NaN samples are ignored`() {
        val h = raw()
        h.append(Double.NaN, 100.0)
        h.append(10.0, Double.NaN)
        assertEquals(0, h.snapshot().size)
        assertNull(h.snapshot().current)
    }

    @Test
    fun `smoothing averages the points within 25 m`() {
        val h = ElevationHistory()
        listOf(5.0 to 0.0, 15.0 to 10.0, 25.0 to 20.0, 35.0 to 30.0, 45.0 to 40.0)
            .forEach { (d, e) -> h.append(d, e) }
        assertArrayEquals(doubleArrayOf(10.0, 15.0, 20.0, 25.0, 30.0), h.snapshot().smoothed, 1e-9)
    }

    @Test
    fun `finished chunks are rise over run between their ends`() {
        val h = raw()
        var d = 5.0
        while (d <= 405.0) {
            h.append(d, 50.0 + 0.05 * (d - 5.0)) // 5 %
            d += 10.0
        }
        val g = h.snapshot().grades
        assertEquals(4, g.size)
        g.forEach { assertEquals(5.0, it, 1e-9) }
    }

    @Test
    fun `the newest chunk uses its own part once it is 30 m long`() {
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 10.0), flatThenTenPercent(until = 245.0).snapshot().grades, 1e-9)
    }

    @Test
    fun `a newest chunk under 30 m uses the trailing 100 m`() {
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 2.0), flatThenTenPercent(until = 225.0).snapshot().grades, 1e-9)
    }

    @Test
    fun `a ride under 30 m has no grade yet`() {
        val h = raw()
        h.append(5.0, 100.0)
        h.append(15.0, 101.0)
        val g = h.snapshot().grades
        assertEquals(1, g.size)
        assertTrue(g[0].isNaN())
    }

    @Test
    fun `finished chunks never change as the ride goes on`() {
        val h = ElevationHistory()
        val profile = { d: Double -> 300.0 + 40.0 * sin(d / 350.0) + 0.02 * d }
        var d = 0.0
        while (d <= 1000.0) {
            h.append(d, profile(d))
            d += 7.0
        }
        val early = h.snapshot()
        while (d <= 2000.0) {
            h.append(d, profile(d))
            d += 7.0
        }
        val late = h.snapshot()
        val settled = early.grades.indices.filter { early.chunkOrigin + (it + 1) * 100.0 <= early.newest - 50.0 }
        assertTrue(settled.size >= 8)
        settled.forEach { assertEquals("chunk $it", early.grades[it], late.grades[it], 0.0) }
    }

    @Test
    fun `min max current and climbed follow the smoothed points`() {
        val h = raw()
        listOf(5.0 to 100.0, 15.0 to 110.0, 25.0 to 105.0, 35.0 to 120.0).forEach { (d, e) -> h.append(d, e) }
        val s = h.snapshot()
        assertEquals(100.0, s.minElevation, 1e-9)
        assertEquals(120.0, s.maxElevation, 1e-9)
        assertEquals(120.0, s.current!!, 1e-9)
        assertEquals(25.0, s.climbed, 1e-9)
    }

    @Test
    fun `reset clears the ride and changes the version`() {
        val h = raw()
        h.append(5.0, 100.0)
        val version = h.version
        h.reset()
        assertEquals(0, h.snapshot().size)
        assertNull(h.snapshot().current)
        assertTrue(h.version != version)
    }

    @Test
    fun `a 400 km ride keeps one point per 10 m bin`() {
        val h = ElevationHistory()
        var d = 0.0
        while (d <= 400_000.0) {
            h.append(d, 500.0)
            d += 8.0
        }
        val s = h.snapshot()
        assertEquals(40_001, s.size)
        assertEquals(4000, s.grades.size)
    }

    @Test
    fun `a forward jump interpolates straight across the gap`() {
        val h = raw()
        h.append(5.0, 100.0)
        h.append(515.0, 151.0) // GPS jump: 510 m in one sample
        val g = h.snapshot().grades
        assertEquals(6, g.size)
        g.forEach { assertEquals(10.0, it, 1e-9) }
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `./gradlew testDebugUnitTest --tests "com.hazzus.karooclimber.ElevationHistoryTest"`
Expected: FAIL, with `Unresolved reference: ElevationHistory`.

- [ ] **Step 3: Implement**

Create `app/src/main/kotlin/com/hazzus/karooclimber/data/ElevationHistory.kt`:

```kotlin
package com.hazzus.karooclimber.data

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * Elevation recorded along the current ride, for the elevation history field.
 *
 * Samples are averaged into [BIN_M] bins of ride distance, one point per bin;
 * the newest bin stays open until a sample lands in a later one. Everything
 * derived (smoothing, 100 m grades, min/max) lives in [HistorySnapshot] and is
 * recomputed from these never-changing points, so a chunk whose inputs are all
 * final always yields the same grade: it is "frozen" by construction.
 *
 * Main thread only; [snapshot] hands an immutable copy to other threads.
 */
class ElevationHistory(
    /** Centered moving-average half-width (m); tests pass 0 for exact arithmetic. */
    private val smoothHalfWindow: Double = HistorySnapshot.SMOOTH_HALF_WINDOW_M,
) {
    private var distances = DoubleArray(INITIAL_CAPACITY)
    private var elevations = DoubleArray(INITIAL_CAPACITY)
    private var closed = 0

    private var openBin = Long.MIN_VALUE
    private var openSumDistance = 0.0
    private var openSumElevation = 0.0
    private var openSamples = 0

    private var lastDistance = Double.NaN
    private var current: Double? = null

    /** Changes on every accepted sample and on [reset]; render loops watch it. */
    var version: Long = 0
        private set

    fun append(distance: Double, elevation: Double) {
        if (distance.isNaN() || elevation.isNaN()) return
        val last = lastDistance
        if (!last.isNaN()) {
            if (distance <= last - RESET_DROP_M) {
                reset() // a new ride, or the demo loop wrapped
            } else if (distance <= last) {
                return // no movement or GPS jitter: it would only add barometric drift
            }
        }
        lastDistance = distance
        current = elevation
        val bin = floor(distance / BIN_M).toLong()
        if (bin != openBin) {
            closeOpenBin()
            openBin = bin
        }
        openSumDistance += distance
        openSumElevation += elevation
        openSamples++
        version++
    }

    fun reset() {
        closed = 0
        openBin = Long.MIN_VALUE
        openSumDistance = 0.0
        openSumElevation = 0.0
        openSamples = 0
        lastDistance = Double.NaN
        current = null
        version++
    }

    fun snapshot(): HistorySnapshot {
        val size = closed + if (openSamples > 0) 1 else 0
        val d = distances.copyOf(size)
        val e = elevations.copyOf(size)
        if (openSamples > 0) {
            d[size - 1] = openSumDistance / openSamples
            e[size - 1] = openSumElevation / openSamples
        }
        return HistorySnapshot(d, e, current, smoothHalfWindow)
    }

    private fun closeOpenBin() {
        if (openSamples == 0) return
        if (closed == distances.size) {
            distances = distances.copyOf(closed * 2)
            elevations = elevations.copyOf(closed * 2)
        }
        distances[closed] = openSumDistance / openSamples
        elevations[closed] = openSumElevation / openSamples
        closed++
        openSumDistance = 0.0
        openSumElevation = 0.0
        openSamples = 0
    }

    companion object {
        const val BIN_M = 10.0
        const val RESET_DROP_M = 50.0
        private const val INITIAL_CAPACITY = 1024
    }
}

/**
 * Immutable copy of the recorded ride: 10 m bin points ([distances] ascending,
 * [rawElevations] their mean samples; the last one may still be open) plus the
 * latest raw sample. Derived series are computed lazily, on whichever thread
 * first asks (the field's render loop, off the main thread).
 */
class HistorySnapshot(
    val distances: DoubleArray,
    val rawElevations: DoubleArray,
    /** Latest raw elevation sample; null before the first one. */
    val current: Double?,
    private val smoothHalfWindow: Double = SMOOTH_HALF_WINDOW_M,
) {
    val size: Int get() = distances.size

    /** Where chunk 0 starts: the first point (0 when empty). */
    val chunkOrigin: Double get() = if (size > 0) distances[0] else 0.0

    /** The newest point's distance (0 when empty). */
    val newest: Double get() = if (size > 0) distances[size - 1] else 0.0

    /** Centered moving average over the points within ±smoothHalfWindow of each point. */
    val smoothed: DoubleArray by lazy { movingAverage() }

    /**
     * Grade (%) of each [CHUNK_M] chunk from [chunkOrigin]. A chunk whose end is
     * recorded uses its two ends on the smoothed series; the chunk holding the
     * newest point uses its own part once that is [LIVE_MIN_M] long, else the
     * trailing [CHUNK_M] (clamped to the origin) if that is, else NaN.
     */
    val grades: DoubleArray by lazy { chunkGrades() }

    val minElevation: Double by lazy { smoothed.minOrNull() ?: 0.0 }
    val maxElevation: Double by lazy { smoothed.maxOrNull() ?: 0.0 }

    /** Sum of the rises between consecutive smoothed points (the demo-mode ascent). */
    val climbed: Double by lazy {
        var sum = 0.0
        for (i in 1 until size) sum += max(0.0, smoothed[i] - smoothed[i - 1])
        sum
    }

    /** Smoothed elevation at [distance], interpolated and clamped to the recorded range. Needs size > 0. */
    fun smoothedAt(distance: Double): Double {
        val s = smoothed
        if (distance <= distances[0]) return s[0]
        if (distance >= distances[size - 1]) return s[size - 1]
        var lo = 0
        var hi = size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (distances[mid] <= distance) lo = mid else hi = mid
        }
        val t = (distance - distances[lo]) / (distances[hi] - distances[lo])
        return s[lo] + t * (s[hi] - s[lo])
    }

    private fun movingAverage(): DoubleArray {
        val out = DoubleArray(size)
        var lo = 0
        var hi = 0
        var sum = 0.0
        for (i in 0 until size) {
            while (hi < size && distances[hi] <= distances[i] + smoothHalfWindow) sum += rawElevations[hi++]
            while (distances[lo] < distances[i] - smoothHalfWindow) sum -= rawElevations[lo++]
            out[i] = sum / (hi - lo)
        }
        return out
    }

    private fun chunkGrades(): DoubleArray {
        if (size < 2) return DoubleArray(0)
        val origin = distances[0]
        val count = ceil((newest - origin) / CHUNK_M).toInt()
        return DoubleArray(count) { k ->
            val a = origin + k * CHUNK_M
            val b = a + CHUNK_M
            when {
                b <= newest -> slope(a, b)
                newest - a >= LIVE_MIN_M -> slope(a, newest)
                else -> {
                    val from = max(origin, newest - CHUNK_M)
                    if (newest - from >= LIVE_MIN_M) slope(from, newest) else Double.NaN
                }
            }
        }
    }

    /** Grade in % between two distances on the smoothed series. */
    private fun slope(from: Double, to: Double): Double =
        (smoothedAt(to) - smoothedAt(from)) / (to - from) * 100.0

    companion object {
        const val SMOOTH_HALF_WINDOW_M = 25.0
        const val CHUNK_M = 100.0
        const val LIVE_MIN_M = 30.0
    }
}
```

If Task 1 chose a 50 m half-window, set `SMOOTH_HALF_WINDOW_M = 50.0`, and change the expectation in `smoothing averages the points within 25 m` to the 50 m window's averages. With 50 m, every point's window holds all five points, so all five expected values are `20.0`; rename the test to `smoothing averages the points within 50 m`.

- [ ] **Step 4: Run the tests to see them pass**

Run: `./gradlew testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, with **68** tests and 0 failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/hazzus/karooclimber/data/ElevationHistory.kt \
  app/src/test/kotlin/com/hazzus/karooclimber/ElevationHistoryTest.kt
git commit -q -F - <<'EOF'
Adds the elevation history recording model

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 4: `HistoryGraphLayout` (the pure layout: steepest-chunk columns, ranges, grid, what fits, frame)

Spec §5 and the layout half of §6. Includes the noise and ramp checks from §8.

**Files:**
- Create: `app/src/main/kotlin/com/hazzus/karooclimber/field/HistoryGraphLayout.kt`
- Test: `app/src/test/kotlin/com/hazzus/karooclimber/HistoryGraphLayoutTest.kt`

**Interfaces:**
- Consumes:
  - From Task 3: `HistorySnapshot`, including `size`, `distances`, `chunkOrigin`, `newest`, `smoothed`, `grades`, `minElevation`, `maxElevation`, `current`, `smoothedAt()` and `CHUNK_M`.
  - From Task 2: `GradePalette.chunkColor()`, `METERS_PER_MILE` and `elevationText()`.
- Produces, in package `com.hazzus.karooclimber.field`:
  - `data class FieldFit(padding: Float, valuesRow: Float, minMax: Boolean, distanceLabels: Boolean)`
  - `data class Column(peak: Double, grade: Double)`
  - `data class HistoryFrame(width: Int, height: Int, fit: FieldFit, graphLeft: Int, graphTop: Int, graphBottom: Int, baselineY: Int, columnTops: List<Int>, columnColors: List<Int>, marker: HistoryFrame.Point?, markerRadius: Float, rules: List<HistoryFrame.Rule>, maxLabel: HistoryFrame.Label?, minLabel: HistoryFrame.Label?, labelTextSize: Float, currentText: String, ascentText: String)`
    - nested `Point(x: Int, y: Int)`, `Rule(x: Int, text: String)`, `Label(y: Int, text: String)`
  - `object HistoryGraphLayout`:
    - constants: `MIN_X_SPAN_M`, `MIN_Y_SPAN_M`, `BITMAP_BUDGET_BYTES`, `NO_GRADE_COLOR`
    - `fits(width: Int, height: Int): FieldFit`
    - `xRange(s): Pair<Double, Double>`, `yRange(s): Pair<Double, Double>`
    - `steepest(grades: DoubleArray, from: Int, to: Int): Double`
    - `columns(s, count: Int): List<Column>`
    - `gridStep(span: Double, unitMeters: Double): Double`
    - `gridRules(from: Double, to: Double, step: Double): List<Double>`
    - `gridLabel(meters: Double, step: Double, unitMeters: Double): String`
    - `bitmapScale(width: Int, height: Int): Float`
    - `frame(width: Int, height: Int, s: HistorySnapshot, ascent: Double?, palette: GradePalette, imperial: Boolean): HistoryFrame`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/hazzus/karooclimber/HistoryGraphLayoutTest.kt`:

```kotlin
package com.hazzus.karooclimber

import com.hazzus.karooclimber.data.ElevationHistory
import com.hazzus.karooclimber.data.HistorySnapshot
import com.hazzus.karooclimber.field.HistoryGraphLayout
import com.hazzus.karooclimber.palette.GradePalettes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale
import java.util.Random
import kotlin.math.sin

class HistoryGraphLayoutTest {

    private val defaultLocale = Locale.getDefault()

    @Before
    fun pinLocale() = Locale.setDefault(Locale.US)

    @After
    fun restoreLocale() = Locale.setDefault(defaultLocale)

    private val karoo = GradePalettes.byId("karoo")

    /** One sample every [step] m from 0 to [until]; no smoothing unless asked. */
    private fun ride(
        until: Double,
        step: Double = 10.0,
        smooth: Double = 0.0,
        profile: (Double) -> Double,
    ): HistorySnapshot = ElevationHistory(smoothHalfWindow = smooth).apply {
        var d = 0.0
        while (d <= until) {
            append(d, profile(d))
            d += step
        }
    }.snapshot()

    // ------------------------------------------------------------- coloring

    @Test
    fun `a column shows its steepest uphill chunk`() {
        assertEquals(9.8, HistoryGraphLayout.steepest(doubleArrayOf(3.1, 9.8, 4.0), 0, 2), 1e-9)
    }

    @Test
    fun `an all-downhill column shows its steepest descent`() {
        assertEquals(-6.0, HistoryGraphLayout.steepest(doubleArrayOf(-2.0, -6.0, -1.0), 0, 2), 1e-9)
    }

    @Test
    fun `flat counts as uphill and NaN chunks are skipped`() {
        assertEquals(0.0, HistoryGraphLayout.steepest(doubleArrayOf(-8.0, 0.0, Double.NaN), 0, 2), 1e-9)
        assertTrue(HistoryGraphLayout.steepest(doubleArrayOf(Double.NaN), 0, 0).isNaN())
    }

    @Test
    fun `columns pool their chunks and keep summits`() {
        // 10 % up to a 50 m summit at 500 m, then 10 % down
        val s = ride(until = 1000.0) { d -> if (d <= 500.0) d * 0.1 else (1000.0 - d) * 0.1 }
        val halves = HistoryGraphLayout.columns(s, 2)
        assertEquals(10.0, halves[0].grade, 1e-9)
        assertEquals(-10.0, halves[1].grade, 1e-9)
        val whole = HistoryGraphLayout.columns(s, 1).single()
        assertEquals(50.0, whole.peak, 1e-9) // the summit sits between the column's edges
        assertEquals(10.0, whole.grade, 1e-9) // uphill wins over the descent
    }

    @Test
    fun `barometer noise leaves flat roads in the lowest band`() {
        val random = Random(42)
        val s = ride(until = 150_000.0, step = 9.0, smooth = 25.0) { d ->
            200.0 + d * 0.005 + random.nextGaussian() * 0.8 // 0.5 % road, sigma 0.8 m
        }
        val cols = HistoryGraphLayout.columns(s, 440)
        val tinted = cols.count { it.grade >= 2.0 }
        assertTrue("$tinted of ${cols.size} columns tinted", tinted <= cols.size * 0.05)
    }

    @Test
    fun `smoothing keeps a short ramp steep`() {
        // 300 m at 12 % from 1000 m, flat on both sides; points every 10 m from 0
        val s = ride(until = 3000.0, smooth = 25.0) { d ->
            when {
                d < 1000.0 -> 0.0
                d < 1300.0 -> (d - 1000.0) * 0.12
                else -> 36.0
            }
        }
        val g = s.grades
        assertTrue("before ${g[9]}", g[9] <= 1.0)
        (10..12).forEach { assertTrue("ramp $it ${g[it]}", g[it] >= 11.0) }
        assertTrue("after ${g[13]}", g[13] <= 1.0)
    }

    // --------------------------------------------------------------- ranges

    @Test
    fun `the first kilometer fills in from the left`() {
        val s = ride(until = 400.0) { 100.0 }
        assertEquals(0.0 to 1000.0, HistoryGraphLayout.xRange(s))
        assertEquals(40, HistoryGraphLayout.columns(s, 100).size) // 10 m per column
    }

    @Test
    fun `after the first kilometer the graph refits to the whole ride`() {
        val s = ride(until = 25_000.0) { 100.0 }
        assertEquals(0.0 to 25_000.0, HistoryGraphLayout.xRange(s))
        assertEquals(400, HistoryGraphLayout.columns(s, 400).size)
    }

    @Test
    fun `a flat ride gets at least 20 m of height, centered`() {
        val s = ride(until = 2000.0) { d -> 100.0 + d * 0.002 } // 100 -> 104 m
        val (lo, hi) = HistoryGraphLayout.yRange(s)
        assertEquals(92.0, lo, 1e-9)
        assertEquals(112.0, hi, 1e-9)
    }

    // ----------------------------------------------------------------- grid

    @Test
    fun `grid steps keep at most 5 rules in km and miles`() {
        assertEquals(500.0, HistoryGraphLayout.gridStep(1_000.0, 1000.0), 1e-9)
        assertEquals(20_000.0, HistoryGraphLayout.gridStep(62_000.0, 1000.0), 1e-9)
        assertEquals(50_000.0, HistoryGraphLayout.gridStep(150_000.0, 1000.0), 1e-9)
        assertEquals(100_000.0, HistoryGraphLayout.gridStep(400_000.0, 1000.0), 1e-9)
        assertEquals(10 * 1609.344, HistoryGraphLayout.gridStep(62_000.0, 1609.344), 1e-6)
    }

    @Test
    fun `rules sit on whole multiples inside the range, even after a restart`() {
        assertEquals(
            listOf(40_000.0, 45_000.0, 50_000.0, 55_000.0, 60_000.0),
            HistoryGraphLayout.gridRules(37_000.0, 62_000.0, 5_000.0),
        )
        assertEquals(listOf(1000.0), HistoryGraphLayout.gridRules(0.0, 2000.0, 1000.0))
    }

    @Test
    fun `grid labels use one decimal only below a whole unit`() {
        assertEquals("1.5", HistoryGraphLayout.gridLabel(1500.0, 500.0, 1000.0))
        assertEquals("40", HistoryGraphLayout.gridLabel(40_000.0, 5000.0, 1000.0))
        assertEquals("10", HistoryGraphLayout.gridLabel(10 * 1609.344, 10 * 1609.344, 1609.344))
    }

    // ------------------------------------------------------------ what fits

    @Test
    fun `extras drop in order as the field shrinks`() {
        fun parts(w: Int, h: Int) = HistoryGraphLayout.fits(w, h).let {
            listOf(it.valuesRow > 0f, it.minMax, it.distanceLabels)
        }
        assertEquals(listOf(true, true, true), parts(480, 300))
        assertEquals(listOf(true, true, true), parts(480, 180))
        assertEquals(listOf(true, true, false), parts(240, 185))
        assertEquals(listOf(true, false, false), parts(480, 92))
        assertEquals(listOf(false, false, false), parts(480, 60))
    }

    @Test
    fun `big fields render below the bitmap budget`() {
        assertEquals(1f, HistoryGraphLayout.bitmapScale(480, 300), 0f)
        val scale = HistoryGraphLayout.bitmapScale(480, 720)
        assertTrue((480 * scale) * (720 * scale) * 2 <= HistoryGraphLayout.BITMAP_BUDGET_BYTES + 1)
        assertEquals(1f, HistoryGraphLayout.bitmapScale(0, 0), 0f)
    }

    // ---------------------------------------------------------------- frame

    @Test
    fun `the frame keeps the shape under the values row and above the bottom margin`() {
        val s = ride(until = 10_000.0) { d -> 200.0 + 100.0 * sin(d / 1500.0) }
        val f = HistoryGraphLayout.frame(480, 220, s, ascent = 312.0, palette = karoo, imperial = false)
        assertTrue(f.columnTops.isNotEmpty())
        assertEquals(f.columnTops.size, f.columnColors.size)
        assertTrue("shape reaches ${f.columnTops.min()}", f.columnTops.min() >= f.graphTop + f.fit.valuesRow)
        assertTrue(f.columnTops.max() < f.graphBottom)
        assertNotNull(f.marker)
        assertEquals("312", f.ascentText)
    }

    @Test
    fun `columns take the palette color of their grade`() {
        val s = ride(until = 2000.0) { d -> d * 0.09 } // 9 % all the way
        val f = HistoryGraphLayout.frame(480, 220, s, null, karoo, imperial = false)
        assertTrue(f.columnColors.all { it == karoo.chunkColor(9.0) })
        assertEquals("--", f.ascentText)
    }

    @Test
    fun `before two points the frame is the empty state`() {
        val h = ElevationHistory()
        h.append(10.0, 412.0)
        val f = HistoryGraphLayout.frame(480, 220, h.snapshot(), null, karoo, imperial = false)
        assertTrue(f.columnTops.isEmpty())
        assertNull(f.marker)
        assertEquals("412", f.currentText)
        assertEquals("--", f.ascentText)
    }

    @Test
    fun `min label is dropped when it would overlap max`() {
        val s = ride(until = 3000.0) { 100.0 } // flat: min == max
        val f = HistoryGraphLayout.frame(480, 220, s, null, karoo, imperial = false)
        assertNotNull(f.maxLabel)
        assertNull(f.minLabel)
    }

    // --------------------------------------------- review focus (plan header)

    @Test
    fun `a 400 km ride still yields one column per pixel`() {
        val s = ride(until = 400_000.0, step = 8.0) { d -> 500.0 + 300.0 * sin(d / 20_000.0) }
        val f = HistoryGraphLayout.frame(480, 220, s, 4000.0, karoo, imperial = false)
        assertEquals(451, f.columnTops.size)
        assertTrue(f.columnColors.none { it == HistoryGraphLayout.NO_GRADE_COLOR })
    }

    @Test
    fun `a history that starts mid-ride labels absolute ride distance`() {
        val h = ElevationHistory(smoothHalfWindow = 0.0)
        var d = 37_000.0
        while (d <= 62_000.0) {
            h.append(d, 300.0)
            d += 10.0
        }
        val f = HistoryGraphLayout.frame(480, 220, h.snapshot(), null, karoo, imperial = false)
        assertEquals(listOf("40", "45", "50", "55", "60"), f.rules.map { it.text })
    }

    @Test
    fun `degenerate field sizes still give a drawable frame`() {
        val s = ride(until = 5000.0) { d -> d * 0.05 }
        listOf(0 to 0, 1 to 1, 60 to 30, 120 to 60).forEach { (w, h) ->
            val f = HistoryGraphLayout.frame(w, h, s, 10.0, karoo, imperial = false)
            assertTrue("$w x $h", f.columnTops.isNotEmpty())
            assertTrue("$w x $h", f.graphBottom > f.graphTop)
        }
    }

    @Test
    fun `below sea level elevations keep their sign`() {
        val s = ride(until = 3000.0) { d -> -30.0 + d * 0.01 } // -30 -> 0 m
        val f = HistoryGraphLayout.frame(480, 220, s, null, karoo, imperial = false)
        assertEquals("0", f.maxLabel!!.text)
        assertEquals("-30", f.minLabel!!.text)
        assertEquals("0", f.currentText)
    }
}
```

If Task 1 measured noise above 0.8 m, use that σ in `barometer noise leaves flat roads in the lowest band`, and pass `smooth = 50.0` in that test and in `smoothing keeps a short ramp steep`. With 50 m smoothing the ramp chunks still read at least 10%. Change that test's `>= 11.0` to `>= 10.0` and its `<= 1.0` bounds to `<= 2.0`, and note the change in spec §8.

- [ ] **Step 2: Run the tests to see them fail**

Run: `./gradlew testDebugUnitTest --tests "com.hazzus.karooclimber.HistoryGraphLayoutTest"`
Expected: FAIL, with `Unresolved reference: field`.

- [ ] **Step 3: Implement**

Create `app/src/main/kotlin/com/hazzus/karooclimber/field/HistoryGraphLayout.kt`:

```kotlin
package com.hazzus.karooclimber.field

import com.hazzus.karooclimber.data.HistorySnapshot
import com.hazzus.karooclimber.palette.GradePalette
import com.hazzus.karooclimber.ui.METERS_PER_MILE
import com.hazzus.karooclimber.ui.elevationText
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Which optional parts fit a field of a given pixel size (spec §5). */
data class FieldFit(
    val padding: Float,
    /** Height of the values row over the top of the graph; 0 when hidden. */
    val valuesRow: Float,
    val minMax: Boolean,
    val distanceLabels: Boolean,
)

/** Peak elevation and coloring grade of one pixel column; grade NaN = no color yet. */
data class Column(val peak: Double, val grade: Double)

/**
 * Everything one frame of the field draws, in field pixels (layout B: values and
 * labels over a full-field graph). Equal frames render identical pixels, which
 * is how the field skips sending a frame that wouldn't change anything.
 */
data class HistoryFrame(
    val width: Int,
    val height: Int,
    val fit: FieldFit,
    val graphLeft: Int,
    val graphTop: Int,
    val graphBottom: Int,
    /** Empty-state line: the edge of the plot's bottom margin. */
    val baselineY: Int,
    /** Top y of pixel column i, which spans [graphLeft + i, graphLeft + i + 1). */
    val columnTops: List<Int>,
    val columnColors: List<Int>,
    /** The newest point; null while there is nothing to draw. */
    val marker: Point?,
    val markerRadius: Float,
    val rules: List<Rule>,
    val maxLabel: Label?,
    val minLabel: Label?,
    val labelTextSize: Float,
    val currentText: String,
    val ascentText: String,
) {
    data class Point(val x: Int, val y: Int)
    data class Rule(val x: Int, val text: String)
    data class Label(val y: Int, val text: String)
}

/**
 * Pure layout math for the elevation history field: ranges, steepest-chunk
 * columns, grid and what fits. No Android types, so it all runs in JVM tests.
 */
object HistoryGraphLayout {
    /** The graph never spans less, so the first kilometer fills in from the left. */
    const val MIN_X_SPAN_M = 1000.0

    /** A flat ride stays flat instead of noise blown up into hills. */
    const val MIN_Y_SPAN_M = 20.0

    /** RemoteViews bitmap budget; bigger fields render scaled down. */
    const val BITMAP_BUDGET_BYTES = 300_000

    /** Columns without a grade yet. */
    val NO_GRADE_COLOR: Int = 0xFF4A4A4A.toInt()

    private const val BOTTOM_MARGIN = 0.10
    private const val TOP_HEADROOM = 0.06
    private const val MAX_RULES = 5
    private val GRID_STEPS = doubleArrayOf(0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0)

    fun fits(width: Int, height: Int): FieldFit {
        val padding = max(4f, width * 0.02f)
        val valuesRow = if (height >= 80) (height * 0.24f).coerceIn(26f, 64f) else 0f
        return FieldFit(
            padding = padding,
            valuesRow = valuesRow,
            minMax = height - 2 * padding - valuesRow >= 70f,
            distanceLabels = height >= 160 && width >= 300,
        )
    }

    /** From the first point to the newest, never narrower than [MIN_X_SPAN_M]. */
    fun xRange(s: HistorySnapshot): Pair<Double, Double> =
        s.chunkOrigin to max(s.newest, s.chunkOrigin + MIN_X_SPAN_M)

    /** Smoothed min..max, widened symmetrically to at least [MIN_Y_SPAN_M]. */
    fun yRange(s: HistorySnapshot): Pair<Double, Double> {
        val lo = s.minElevation
        val hi = s.maxElevation
        if (hi - lo >= MIN_Y_SPAN_M) return lo to hi
        val mid = (lo + hi) / 2
        return mid - MIN_Y_SPAN_M / 2 to mid + MIN_Y_SPAN_M / 2
    }

    /** Steepest uphill grade in [from]..[to] if any chunk is >= 0, else the steepest downhill; NaN skipped. */
    fun steepest(grades: DoubleArray, from: Int, to: Int): Double {
        var up = Double.NEGATIVE_INFINITY
        var down = Double.POSITIVE_INFINITY
        for (k in from..to) {
            val g = grades[k]
            if (g.isNaN()) continue
            if (g > up) up = g
            if (g < down) down = g
        }
        return when {
            up == Double.NEGATIVE_INFINITY -> Double.NaN
            up >= 0.0 -> up
            else -> down
        }
    }

    /** One entry per pixel column, left to right; columns past the newest point stay empty. */
    fun columns(s: HistorySnapshot, count: Int): List<Column> {
        if (s.size < 2 || count <= 0) return emptyList()
        val (x0, x1) = xRange(s)
        val per = (x1 - x0) / count
        val grades = s.grades
        val smoothed = s.smoothed
        val out = ArrayList<Column>(count)
        var p = 0
        for (i in 0 until count) {
            val a = x0 + per * i
            if (a >= s.newest) break
            val b = min(x0 + per * (i + 1), s.newest)
            var peak = max(s.smoothedAt(a), s.smoothedAt(b))
            while (p < s.size && s.distances[p] < a) p++
            var q = p
            while (q < s.size && s.distances[q] <= b) {
                peak = max(peak, smoothed[q])
                q++
            }
            val first = floor((a - x0) / HistorySnapshot.CHUNK_M).toInt().coerceAtLeast(0)
            val last = floor((b - x0 - 1e-6) / HistorySnapshot.CHUNK_M).toInt().coerceAtMost(grades.size - 1)
            out += Column(peak, steepest(grades, first, last))
        }
        return out
    }

    /** Rule spacing (m): the smallest nice step giving at most [MAX_RULES] rules, else 100 units. */
    fun gridStep(span: Double, unitMeters: Double): Double {
        for (units in GRID_STEPS) {
            if (span / (units * unitMeters) <= MAX_RULES) return units * unitMeters
        }
        return 100 * unitMeters
    }

    /** Whole multiples of [step] strictly inside ([from], [to]): absolute ride distance. */
    fun gridRules(from: Double, to: Double, step: Double): List<Double> {
        val rules = ArrayList<Double>()
        var k = floor(from / step).toLong() + 1
        while (k * step < to) {
            rules += k * step
            k++
        }
        return rules
    }

    /** Unitless km/mi label: one decimal only when the step is under a whole unit. */
    fun gridLabel(meters: Double, step: Double, unitMeters: Double): String {
        val value = meters / unitMeters
        return if (step < unitMeters) "%.1f".format(value) else value.roundToInt().toString()
    }

    /** Bitmap px per field px keeping an RGB_565 bitmap within [BITMAP_BUDGET_BYTES]. */
    fun bitmapScale(width: Int, height: Int): Float {
        val bytes = width.toLong() * height * 2
        return if (bytes <= BITMAP_BUDGET_BYTES) 1f else sqrt(BITMAP_BUDGET_BYTES.toDouble() / bytes).toFloat()
    }

    fun frame(
        width: Int,
        height: Int,
        s: HistorySnapshot,
        ascent: Double?,
        palette: GradePalette,
        imperial: Boolean,
    ): HistoryFrame {
        val w = max(width, 1)
        val h = max(height, 1)
        val fit = fits(w, h)
        val left = fit.padding.roundToInt()
        val top = fit.padding.roundToInt()
        val bottom = max(top + 1, (h - fit.padding).roundToInt())
        val graphHeight = (bottom - top).toDouble()
        val markerRadius = (graphHeight * 0.055).toFloat().coerceIn(3.5f, 9f)
        // one column per pixel, leaving room for the marker at the right edge
        val columnCount = max(1, (w - 2 * fit.padding - markerRadius).toInt())
        val plotTop = top + fit.valuesRow + graphHeight * TOP_HEADROOM
        val plotBottom = bottom - graphHeight * BOTTOM_MARGIN
        val labelTextSize = (h * 0.07f).coerceIn(11f, 17f)
        val empty = HistoryFrame(
            width = w,
            height = h,
            fit = fit,
            graphLeft = left,
            graphTop = top,
            graphBottom = bottom,
            baselineY = plotBottom.roundToInt(),
            columnTops = emptyList(),
            columnColors = emptyList(),
            marker = null,
            markerRadius = markerRadius,
            rules = emptyList(),
            maxLabel = null,
            minLabel = null,
            labelTextSize = labelTextSize,
            currentText = s.current?.let { elevationText(it, imperial) } ?: "--",
            ascentText = ascent?.let { elevationText(it, imperial) } ?: "--",
        )
        if (s.size < 2) return empty

        val (x0, x1) = xRange(s)
        val (y0, y1) = yRange(s)
        fun xOf(distance: Double): Int = (left + (distance - x0) / (x1 - x0) * columnCount).roundToInt()
        fun yOf(elevation: Double): Int =
            (plotBottom - (elevation - y0) / (y1 - y0) * (plotBottom - plotTop)).roundToInt()

        val columns = columns(s, columnCount)
        val unit = if (imperial) METERS_PER_MILE else 1000.0
        val step = gridStep(x1 - x0, unit)
        val maxY = yOf(s.maxElevation)
        val minY = yOf(s.minElevation)
        return empty.copy(
            columnTops = columns.map { yOf(it.peak) },
            columnColors = columns.map { if (it.grade.isNaN()) NO_GRADE_COLOR else palette.chunkColor(it.grade) },
            marker = HistoryFrame.Point(xOf(s.newest), yOf(s.smoothed[s.size - 1])),
            rules = gridRules(x0, x1, step).map { HistoryFrame.Rule(xOf(it), gridLabel(it, step, unit)) },
            maxLabel = if (fit.minMax) HistoryFrame.Label(maxY, elevationText(s.maxElevation, imperial)) else null,
            minLabel = if (fit.minMax && minY - maxY > labelTextSize * 1.2f) {
                HistoryFrame.Label(minY, elevationText(s.minElevation, imperial))
            } else {
                null
            },
        )
    }
}
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `./gradlew testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, with **90** tests and 0 failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/hazzus/karooclimber/field/HistoryGraphLayout.kt \
  app/src/test/kotlin/com/hazzus/karooclimber/HistoryGraphLayoutTest.kt
git commit -q -F - <<'EOF'
Adds the elevation history graph layout

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 5: Recording gate and `ElevationHistoryRepo`

Spec §7.1 and §7.4.

**Files:**
- Create: `app/src/main/kotlin/com/hazzus/karooclimber/data/HistoryGate.kt`
- Create: `app/src/main/kotlin/com/hazzus/karooclimber/data/ElevationHistoryRepo.kt`
- Test: `app/src/test/kotlin/com/hazzus/karooclimber/HistoryGateTest.kt`

**Interfaces:**
- Consumes: from Task 3, `ElevationHistory` (`append`, `reset`, `snapshot`, `version`) and `HistorySnapshot.climbed`.
- Produces, in package `com.hazzus.karooclimber.data`:
  - `enum class HistorySource { NONE, RIDE, DEMO }`
  - `class HistoryGate`
    - `fun switchTo(source: HistorySource): Boolean` (true means reset the history first)
    - companion: `fun sourceFor(fieldPlaced: Boolean, rideActive: Boolean, demoMode: Boolean): HistorySource`
  - `class ElevationHistoryRepo(karooSystem: KarooSystemService)`
    - `val version: StateFlow<Long>`
    - `val ascent: StateFlow<Double?>`
    - `fun snapshot(): HistorySnapshot`
    - `fun startStreams()`, `fun stopStreams()`
    - `fun appendDemo(distance: Double, elevation: Double)`
    - `fun reset()`

- [ ] **Step 1: Write the failing gate test**

Create `app/src/test/kotlin/com/hazzus/karooclimber/HistoryGateTest.kt`:

```kotlin
package com.hazzus.karooclimber

import com.hazzus.karooclimber.data.HistoryGate
import com.hazzus.karooclimber.data.HistorySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryGateTest {

    @Test
    fun `nothing records while the field is not placed`() {
        assertEquals(HistorySource.NONE, HistoryGate.sourceFor(fieldPlaced = false, rideActive = true, demoMode = false))
        assertEquals(HistorySource.NONE, HistoryGate.sourceFor(fieldPlaced = false, rideActive = true, demoMode = true))
    }

    @Test
    fun `demo mode wins over a running ride`() {
        assertEquals(HistorySource.DEMO, HistoryGate.sourceFor(fieldPlaced = true, rideActive = true, demoMode = true))
        assertEquals(HistorySource.DEMO, HistoryGate.sourceFor(fieldPlaced = true, rideActive = false, demoMode = true))
    }

    @Test
    fun `a placed field records a running ride only`() {
        assertEquals(HistorySource.RIDE, HistoryGate.sourceFor(fieldPlaced = true, rideActive = true, demoMode = false))
        assertEquals(HistorySource.NONE, HistoryGate.sourceFor(fieldPlaced = true, rideActive = false, demoMode = false))
    }

    @Test
    fun `switching sources clears the history but pausing does not`() {
        val gate = HistoryGate()
        assertFalse(gate.switchTo(HistorySource.RIDE))
        assertFalse(gate.switchTo(HistorySource.NONE))
        assertFalse(gate.switchTo(HistorySource.RIDE))
        assertTrue(gate.switchTo(HistorySource.DEMO))
        assertFalse(gate.switchTo(HistorySource.DEMO))
        assertFalse(gate.switchTo(HistorySource.NONE))
        assertTrue(gate.switchTo(HistorySource.RIDE))
    }
}
```

- [ ] **Step 2: Run the test to see it fail**

Run: `./gradlew testDebugUnitTest --tests "com.hazzus.karooclimber.HistoryGateTest"`
Expected: FAIL, with `Unresolved reference: HistoryGate`.

- [ ] **Step 3: Implement the gate and the repo**

Create `app/src/main/kotlin/com/hazzus/karooclimber/data/HistoryGate.kt`:

```kotlin
package com.hazzus.karooclimber.data

/** Where the elevation history's samples come from right now. */
enum class HistorySource { NONE, RIDE, DEMO }

/**
 * Recording gate for the elevation history (spec §7.1): record only while the
 * field is placed (the demo feed in demo mode, a running ride otherwise), and
 * never continue one source's history with the other's samples.
 */
class HistoryGate {
    private var lastRecorded = HistorySource.NONE

    /** Returns true when the history must be cleared before recording from [source]. */
    fun switchTo(source: HistorySource): Boolean {
        if (source == HistorySource.NONE) return false
        val clear = lastRecorded != HistorySource.NONE && lastRecorded != source
        lastRecorded = source
        return clear
    }

    companion object {
        fun sourceFor(fieldPlaced: Boolean, rideActive: Boolean, demoMode: Boolean): HistorySource = when {
            !fieldPlaced -> HistorySource.NONE
            demoMode -> HistorySource.DEMO
            rideActive -> HistorySource.RIDE
            else -> HistorySource.NONE
        }
    }
}
```

Create `app/src/main/kotlin/com/hazzus/karooclimber/data/ElevationHistoryRepo.kt`. If Task 1 found different field keys, use them in place of the `DataType.Field.*` constants below.

```kotlin
package com.hazzus.karooclimber.data

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Feeds [ElevationHistory] from Karoo's DISTANCE, PRESSURE_ELEVATION_CORRECTION
 * and ELEVATION_GAIN streams between [startStreams] and [stopStreams], or from
 * the demo feed. Main thread only; [snapshot] hands an immutable copy to the
 * field's render loops.
 */
class ElevationHistoryRepo(private val karooSystem: KarooSystemService) {

    private val history = ElevationHistory()
    private val consumerIds = mutableListOf<String>()
    private var elevation: Double? = null

    private val _version = MutableStateFlow(0L)

    /** Changes whenever the history or the ascent changes; render loops watch it. */
    val version: StateFlow<Long> = _version

    private val _ascent = MutableStateFlow<Double?>(null)

    /** Ascent so far (m): Karoo's own ELEVATION_GAIN, or the recorded climb in demo mode. */
    val ascent: StateFlow<Double?> = _ascent

    fun snapshot(): HistorySnapshot = history.snapshot()

    fun startStreams() {
        if (consumerIds.isNotEmpty()) return
        // data points carry several fields: read by key, SINGLE as the fallback
        consumerIds += stream(DataType.Type.PRESSURE_ELEVATION_CORRECTION) { values ->
            elevation = values[DataType.Field.PRESSURE_ELEVATION] ?: values[DataType.Field.SINGLE]
        }
        consumerIds += stream(DataType.Type.ELEVATION_GAIN) { values ->
            _ascent.value = values[DataType.Field.ELEVATION_GAIN] ?: values[DataType.Field.SINGLE]
            changed()
        }
        consumerIds += stream(DataType.Type.DISTANCE) { values ->
            val distance = values[DataType.Field.DISTANCE] ?: values[DataType.Field.SINGLE]
            val elev = elevation
            if (distance != null && elev != null) append(distance, elev)
        }
    }

    fun stopStreams() {
        consumerIds.forEach { karooSystem.removeConsumer(it) }
        consumerIds.clear()
        elevation = null
    }

    /** Demo feed: one synthetic sample; the ascent follows the recorded shape. */
    fun appendDemo(distance: Double, elevation: Double) {
        history.append(distance, elevation)
        _ascent.value = history.snapshot().climbed
        changed()
    }

    fun reset() {
        history.reset()
        _ascent.value = null
        changed()
    }

    private fun append(distance: Double, elevation: Double) {
        val before = history.version
        history.append(distance, elevation)
        if (history.version != before) changed()
    }

    private fun changed() {
        _version.value += 1
    }

    private fun stream(typeId: String, onValues: (Map<String, Double>) -> Unit): String =
        karooSystem.addConsumer<OnStreamState>(OnStreamState.StartStreaming(typeId)) { event ->
            (event.state as? StreamState.Streaming)?.dataPoint?.values?.let(onValues)
        }
}
```

- [ ] **Step 4: Run the tests and build**

Run: `./gradlew testDebugUnitTest assembleDebug`
Expected: `BUILD SUCCESSFUL`, with **94** tests and 0 failures. The repo isn't wired up yet; Task 6 does that.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/hazzus/karooclimber/data/HistoryGate.kt \
  app/src/main/kotlin/com/hazzus/karooclimber/data/ElevationHistoryRepo.kt \
  app/src/test/kotlin/com/hazzus/karooclimber/HistoryGateTest.kt
git commit -q -F - <<'EOF'
Adds the elevation history recording gate and repo

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 6: The field (pacing, renderer, `DataTypeImpl`, resources, registration and extension wiring)

Spec §6, §7.1 (wiring) and §7.2.

**Files:**
- Create: `app/src/main/kotlin/com/hazzus/karooclimber/field/FramePacer.kt`
- Create: `app/src/main/kotlin/com/hazzus/karooclimber/field/HistoryGraphRenderer.kt`
- Create: `app/src/main/kotlin/com/hazzus/karooclimber/field/ElevationHistoryField.kt`
- Create: `app/src/main/res/layout/field_elevation_history.xml`
- Create: `app/src/main/res/drawable/ic_elevation.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/xml/extension_info.xml`
- Modify: `app/src/main/kotlin/com/hazzus/karooclimber/ClimberExtension.kt`
- Test: `app/src/test/kotlin/com/hazzus/karooclimber/FramePacerTest.kt`

**Interfaces:**
- Consumes:
  - From Task 4: `HistoryGraphLayout.frame()`, `HistoryGraphLayout.bitmapScale()`, and `HistoryFrame` with its nested types.
  - From Task 5: `ElevationHistoryRepo` (`version`, `ascent`, `snapshot()`, `startStreams()`, `stopStreams()`, `appendDemo()`, `reset()`), `HistoryGate` and `HistorySource`.
  - From Task 3: `ElevationHistory` and `HistorySnapshot.climbed`.
  - From Task 2: `KarooFonts`.
- Produces:
  - `class FramePacer(intervalMs: Long, now: () -> Long = System::currentTimeMillis)` with `waitMs(): Long`, `isNew(frame: Any): Boolean` and `sent(frame: Any)`.
  - `class HistoryGraphRenderer(context: Context)` with `render(frame: HistoryFrame, scale: Float): Bitmap`.
  - `class ElevationHistoryField(scope: CoroutineScope, repo: ElevationHistoryRepo, settings: Flow<Settings>, imperial: StateFlow<Boolean>) : DataTypeImpl`
    - `val runningViews: StateFlow<Int>`
    - companion: `TYPE_ID = "elevation-history"`, `DATA_TYPE_ID`, `FRAME_INTERVAL_MS = 5_000L`
  - `ClimberExtension.EXTENSION_ID = "karoo-climber"` and `ClimberExtension.types`.

- [ ] **Step 1: Write the failing pacer test**

Create `app/src/test/kotlin/com/hazzus/karooclimber/FramePacerTest.kt`:

```kotlin
package com.hazzus.karooclimber

import com.hazzus.karooclimber.field.FramePacer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePacerTest {

    private var clock = 0L
    private val pacer = FramePacer(intervalMs = 5_000, now = { clock })

    @Test
    fun `the first frame goes out immediately`() {
        assertEquals(0L, pacer.waitMs())
    }

    @Test
    fun `later frames wait out the interval`() {
        clock = 1_000
        pacer.sent("a")
        clock = 3_000
        assertEquals(3_000L, pacer.waitMs())
        clock = 6_000
        assertEquals(0L, pacer.waitMs())
        clock = 9_000
        assertEquals(0L, pacer.waitMs())
    }

    @Test
    fun `an identical frame is not new`() {
        pacer.sent(listOf(1, 2))
        assertFalse(pacer.isNew(listOf(1, 2)))
        assertTrue(pacer.isNew(listOf(1, 3)))
    }
}
```

- [ ] **Step 2: Run the test to see it fail**

Run: `./gradlew testDebugUnitTest --tests "com.hazzus.karooclimber.FramePacerTest"`
Expected: FAIL, with `Unresolved reference: FramePacer`.

- [ ] **Step 3: Implement `FramePacer`**

Create `app/src/main/kotlin/com/hazzus/karooclimber/field/FramePacer.kt`:

```kotlin
package com.hazzus.karooclimber.field

/**
 * Frame pacing for a graphical field. karoo-ext silently drops an updateView
 * sent < 900 ms after the previous one, and every frame is an IPC carrying a
 * bitmap, so frames go out at most every [intervalMs] and only when they differ
 * from the last one sent.
 */
class FramePacer(
    private val intervalMs: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var lastSentAt: Long? = null
    private var lastFrame: Any? = null

    /** Milliseconds to wait before the next frame may go out; 0 for the first one. */
    fun waitMs(): Long = lastSentAt?.let { (it + intervalMs - now()).coerceAtLeast(0L) } ?: 0L

    /** False when [frame] equals the last frame sent: identical pixels, skip the IPC. */
    fun isNew(frame: Any): Boolean = frame != lastFrame

    fun sent(frame: Any) {
        lastSentAt = now()
        lastFrame = frame
    }
}
```

Run: `./gradlew testDebugUnitTest --tests "com.hazzus.karooclimber.FramePacerTest"`
Expected: PASS (3 tests).

- [ ] **Step 4: Add the resources**

Create `app/src/main/res/drawable/ic_elevation.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- ic_climber's mountain cropped to its visible box (x 1-23, y 6-18), white:
     sized like the other value glyphs, bottom on the text baseline. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="22dp"
    android:height="12dp"
    android:viewportWidth="22"
    android:viewportHeight="12">
    <group
        android:translateX="-1"
        android:translateY="-6">
        <path
            android:fillColor="#FFFFFFFF"
            android:pathData="M14,6l-4.22,5.63 1.25,1.67L14,9.33 19,16h-8.46l-4.01,-5.37L1,18h22L14,6zM5,16l1.52,-2.03L8.04,16H5z" />
    </group>
</vector>
```

Create `app/src/main/res/layout/field_elevation_history.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Elevation history field: one bitmap drawn by HistoryGraphRenderer, stretched
     to the field when it was rendered below field size (IPC budget). -->
<ImageView xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/graph"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:contentDescription="@string/elevation_history_name"
    android:scaleType="fitXY" />
```

In `app/src/main/res/values/strings.xml`, add before `</resources>`:

```xml
    <string name="elevation_history_name">Elevation history</string>
    <string name="elevation_history_description">Elevation ridden so far, colored by grade</string>
```

Replace the whole of `app/src/main/res/xml/extension_info.xml` with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Overlay extension plus one graphical data field (elevation history); no
     scanning, no map layer. typeId must match ElevationHistoryField.TYPE_ID. -->
<ExtensionInfo
    displayName="@string/extension_name"
    icon="@drawable/ic_climber"
    id="karoo-climber">

    <DataType
        description="@string/elevation_history_description"
        displayName="@string/elevation_history_name"
        graphical="true"
        icon="@drawable/ic_elevation"
        typeId="elevation-history" />
</ExtensionInfo>
```

- [ ] **Step 5: Implement the renderer**

Create `app/src/main/kotlin/com/hazzus/karooclimber/field/HistoryGraphRenderer.kt`:

```kotlin
package com.hazzus.karooclimber.field

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import com.hazzus.karooclimber.R
import com.hazzus.karooclimber.ui.KarooFonts
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Paints a [HistoryFrame] (layout B: values and labels over a full-field graph)
 * into an opaque RGB_565 bitmap. Owns its Paints and (mutated) Drawables: one
 * renderer per render loop, never shared across threads.
 */
class HistoryGraphRenderer(context: Context) {

    private val elevationIcon: Drawable? = context.getDrawable(R.drawable.ic_elevation)?.mutate()
    private val gainIcon: Drawable? = context.getDrawable(R.drawable.ic_elev_gain)?.mutate()

    private val columnPaint = Paint()
    private val gridPaint = Paint().apply {
        color = GRID_COLOR
        strokeWidth = 1.5f
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val markerCenterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = KarooFonts.plexCondensed
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = KarooFonts.plexCondensed
        textAlign = Paint.Align.LEFT
    }

    /** Renders [frame] at [scale] bitmap px per field px (< 1 keeps big fields in budget). */
    fun render(frame: HistoryFrame, scale: Float): Bitmap {
        val bitmap = Bitmap.createBitmap(
            (frame.width * scale).roundToInt().coerceAtLeast(1),
            (frame.height * scale).roundToInt().coerceAtLeast(1),
            Bitmap.Config.RGB_565,
        )
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        draw(canvas, frame)
        return bitmap
    }

    private fun draw(canvas: Canvas, f: HistoryFrame) {
        canvas.drawColor(Color.BLACK)
        val top = f.graphTop.toFloat()
        val bottom = f.graphBottom.toFloat()
        val marker = f.marker
        if (marker == null) {
            // empty state: a faint baseline where the shape will grow
            val y = f.baselineY.toFloat()
            canvas.drawLine(f.graphLeft.toFloat(), y, (f.width - f.graphLeft).toFloat(), y, gridPaint)
        }
        // distance grid first, so the colors paint over it
        for (rule in f.rules) {
            val x = rule.x + 0.5f
            canvas.drawLine(x, top, x, bottom, gridPaint)
        }
        // one grade-colored column per pixel
        for (i in f.columnTops.indices) {
            columnPaint.color = f.columnColors[i]
            val x = (f.graphLeft + i).toFloat()
            canvas.drawRect(x, f.columnTops[i].toFloat(), x + 1f, bottom, columnPaint)
        }
        if (marker != null) {
            val mx = marker.x.toFloat()
            val my = marker.y.toFloat()
            canvas.drawCircle(mx, my, f.markerRadius, markerPaint)
            canvas.drawCircle(mx, my, f.markerRadius * 0.45f, markerCenterPaint)
        }
        drawLabels(canvas, f)
        drawValues(canvas, f)
    }

    private fun drawLabels(canvas: Canvas, f: HistoryFrame) {
        labelPaint.textSize = f.labelTextSize
        labelPaint.setShadowLayer(LABEL_HALO_PX, 0f, 0f, HALO_COLOR)
        val baselineOffset = f.labelTextSize * 0.35f
        val x = f.graphLeft + 4f
        labelPaint.color = MIN_MAX_COLOR
        f.maxLabel?.let { canvas.drawText(it.text, x, it.y + baselineOffset, labelPaint) }
        f.minLabel?.let { canvas.drawText(it.text, x, it.y + baselineOffset, labelPaint) }
        if (f.fit.distanceLabels) {
            labelPaint.color = DISTANCE_LABEL_COLOR
            for (rule in f.rules) canvas.drawText(rule.text, rule.x + 3f, f.graphBottom - 4f, labelPaint)
        }
    }

    /** Current elevation (left) and ascent (right) over the top of the graph, with glyphs. */
    private fun drawValues(canvas: Canvas, f: HistoryFrame) {
        val row = f.fit.valuesRow
        if (row <= 0f) return
        val pad = f.fit.padding
        valuePaint.textSize = 100f
        val perUnit = (valuePaint.measureText(f.currentText) + valuePaint.measureText(f.ascentText)) / 100f +
            2 * GAP + ICON * (aspect(elevationIcon) + aspect(gainIcon))
        val size = min(row * 0.8f, (f.width - 2 * pad - f.width * 0.12f) / perUnit)
        valuePaint.textSize = size
        valuePaint.setShadowLayer(size * 0.15f, 0f, 0f, HALO_COLOR)
        val baseline = pad + (row + size * 0.72f) / 2f
        var x = pad
        canvas.drawText(f.currentText, x, baseline, valuePaint)
        x += valuePaint.measureText(f.currentText) + size * GAP
        drawIcon(canvas, elevationIcon, x, baseline, size * ICON)
        x = f.width - pad - (valuePaint.measureText(f.ascentText) + size * GAP + size * ICON * aspect(gainIcon))
        canvas.drawText(f.ascentText, x, baseline, valuePaint)
        x += valuePaint.measureText(f.ascentText) + size * GAP
        drawIcon(canvas, gainIcon, x, baseline, size * ICON)
    }

    private fun aspect(icon: Drawable?): Float =
        if (icon == null) 0f else icon.intrinsicWidth.toFloat() / icon.intrinsicHeight

    /** Draws [icon] [size] tall with its bottom on the text [baseline] (overlay convention). */
    private fun drawIcon(canvas: Canvas, icon: Drawable?, x: Float, baseline: Float, size: Float) {
        icon ?: return
        val w = size * aspect(icon)
        icon.setBounds(x.toInt(), (baseline - size).toInt(), (x + w).toInt(), baseline.toInt())
        icon.draw(canvas)
    }

    private companion object {
        const val GAP = 0.25f
        const val ICON = 0.54f
        const val LABEL_HALO_PX = 4f
        const val GRID_COLOR = 0x40FFFFFF
        val HALO_COLOR = 0xF2000000.toInt()
        val MIN_MAX_COLOR = 0xFFDCE8F5.toInt()
        val DISTANCE_LABEL_COLOR = 0xFF9AA4AE.toInt()
    }
}
```

- [ ] **Step 6: Implement the field**

Create `app/src/main/kotlin/com/hazzus/karooclimber/field/ElevationHistoryField.kt`:

```kotlin
package com.hazzus.karooclimber.field

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.widget.RemoteViews
import com.hazzus.karooclimber.ClimberExtension
import com.hazzus.karooclimber.R
import com.hazzus.karooclimber.data.ElevationHistory
import com.hazzus.karooclimber.data.ElevationHistoryRepo
import com.hazzus.karooclimber.data.HistorySnapshot
import com.hazzus.karooclimber.debug.DemoData
import com.hazzus.karooclimber.palette.GradePalettes
import com.hazzus.karooclimber.settings.Settings
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Graphical Karoo data field: the elevation ridden so far, colored by grade
 * (docs/superpowers/specs/2026-10-12-elevation-history-field-design.md).
 *
 * Karoo calls [startView] on a binder thread, once per visible instance. Each
 * instance runs a render loop on the extension's main scope, lays out and draws
 * off the main thread, and sends a bitmap through RemoteViews at most every
 * [FRAME_INTERVAL_MS]: karoo-ext silently drops frames < 900 ms apart.
 */
class ElevationHistoryField(
    private val scope: CoroutineScope,
    private val repo: ElevationHistoryRepo,
    private val settings: Flow<Settings>,
    private val imperial: StateFlow<Boolean>,
) : DataTypeImpl(ClimberExtension.EXTENSION_ID, TYPE_ID) {

    private val _runningViews = MutableStateFlow(0)

    /** Non-preview views on screen; counts as "field placed" for the recording gate. */
    val runningViews: StateFlow<Int> = _runningViews

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val (width, height) = config.viewSize
        Log.i(TAG, "startView ${width}x$height textSize=${config.textSize} preview=${config.preview}")
        if (config.preview) {
            // page editor: a static sample ride, never counted as placed
            val job = scope.launch {
                val paletteId = settings.first().paletteId
                withContext(Dispatchers.Default) {
                    try {
                        val sample = previewSnapshot()
                        val frame = HistoryGraphLayout.frame(
                            width, height, sample, sample.climbed, GradePalettes.byId(paletteId), imperial.value,
                        )
                        val bitmap = HistoryGraphRenderer(context).render(frame, HistoryGraphLayout.bitmapScale(width, height))
                        emitter.updateView(remoteViews(context, bitmap))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "preview failed", e)
                    }
                }
            }
            emitter.setCancellable { job.cancel() }
            return
        }
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        val job = scope.launch {
            _runningViews.value += 1
            try {
                renderLoop(context, width, height, emitter)
            } finally {
                _runningViews.value -= 1
            }
        }
        emitter.setCancellable {
            Log.i(TAG, "stopView ${width}x$height")
            job.cancel()
        }
    }

    private suspend fun renderLoop(context: Context, width: Int, height: Int, emitter: ViewEmitter) {
        val renderer = HistoryGraphRenderer(context)
        val pacer = FramePacer(FRAME_INTERVAL_MS)
        val scale = HistoryGraphLayout.bitmapScale(width, height)
        combine(repo.version, settings, imperial) { _, s, units -> s.paletteId to units }
            .conflate()
            .collect { (paletteId, units) ->
                delay(pacer.waitMs())
                val snapshot = repo.snapshot() // main thread: the history lives here
                val ascent = repo.ascent.value
                withContext(Dispatchers.Default) {
                    try {
                        val frame = HistoryGraphLayout.frame(
                            width, height, snapshot, ascent, GradePalettes.byId(paletteId), units,
                        )
                        if (pacer.isNew(frame)) {
                            emitter.updateView(remoteViews(context, renderer.render(frame, scale)))
                            pacer.sent(frame)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // the field shares the process with the overlay: never crash it
                        Log.e(TAG, "frame failed", e)
                    }
                }
            }
    }

    /** The demo route ridden end to end: what Karoo's page editor shows. */
    private fun previewSnapshot(): HistorySnapshot {
        val history = ElevationHistory()
        val profile = DemoData.route().profile ?: return history.snapshot()
        var d = 0.0
        while (d <= profile.totalDistance) {
            history.append(d, profile.elevationAt(d))
            d += PREVIEW_STEP_M
        }
        return history.snapshot()
    }

    private fun remoteViews(context: Context, bitmap: Bitmap) =
        RemoteViews(context.packageName, R.layout.field_elevation_history).apply {
            setImageViewBitmap(R.id.graph, bitmap)
        }

    companion object {
        /** Must match extension_info.xml's typeId. */
        const val TYPE_ID = "elevation-history"

        /** How Karoo names this field on ride pages (ActiveRideProfile elements). */
        val DATA_TYPE_ID: String = DataType.dataTypeId(ClimberExtension.EXTENSION_ID, TYPE_ID)

        const val FRAME_INTERVAL_MS = 5_000L
        private const val PREVIEW_STEP_M = 15.0
        private const val TAG = "ElevationHistoryField"
    }
}
```

- [ ] **Step 7: Wire `ClimberExtension`**

Apply these edits to `app/src/main/kotlin/com/hazzus/karooclimber/ClimberExtension.kt`.

1. **Imports.** Add these lines, keeping the import list alphabetical:

```kotlin
import com.hazzus.karooclimber.data.ElevationHistoryRepo
import com.hazzus.karooclimber.data.HistoryGate
import com.hazzus.karooclimber.data.HistorySource
import com.hazzus.karooclimber.field.ElevationHistoryField
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.models.ActiveRideProfile
```

2. **Class header.** Replace

```kotlin
/**
 * Extension service bound by Karoo OS. Hosts the KarooSystemService connection,
 * the data layer and the overlay controller.
 */
class ClimberExtension : KarooExtension("karoo-climber", BuildConfig.VERSION_NAME) {
```

   with

```kotlin
/**
 * Extension service bound by Karoo OS. Hosts the KarooSystemService connection,
 * the data layer, the overlay controller and the elevation history field.
 */
class ClimberExtension : KarooExtension(EXTENSION_ID, BuildConfig.VERSION_NAME) {
```

3. **Members.** Replace

```kotlin
    private lateinit var settingsRepo: SettingsRepo
    private lateinit var overlay: OverlayController
    private val engine = ClimbEngine()
```

   with

```kotlin
    private lateinit var settingsRepo: SettingsRepo
    private lateinit var historyRepo: ElevationHistoryRepo
    private lateinit var historyField: ElevationHistoryField
    private lateinit var overlay: OverlayController
    private val engine = ClimbEngine()
    private val historyGate = HistoryGate()
```

4. **Field state.** Replace

```kotlin
    private val consumerIds = mutableListOf<String>()
```

   with

```kotlin
    private val consumerIds = mutableListOf<String>()

    /** The elevation history field sits on a page of the active ride profile. */
    private val fieldInProfile = MutableStateFlow(false)
    private val historySource = MutableStateFlow(HistorySource.NONE)
    private val demoProfile by lazy { DemoData.route().profile }
```

5. **Data types.** Directly before `override fun onCreate() {`, add:

```kotlin
    override val types: List<DataTypeImpl> by lazy { listOf(historyField) }

```

6. **Construction.** Replace

```kotlin
        settingsRepo = SettingsRepo(applicationContext)
        overlay = OverlayController(this)
```

   with

```kotlin
        settingsRepo = SettingsRepo(applicationContext)
        historyRepo = ElevationHistoryRepo(karooSystem)
        historyField = ElevationHistoryField(scope, historyRepo, settingsRepo.settings, imperial)
        overlay = OverlayController(this)
```

7. **Ride end.** Replace

```kotlin
            // ride ended: forget completed climbs so re-riding the route starts at 0/N
            if (!active && rideActive.value) engine.reset()
            rideActive.value = active
```

   with

```kotlin
            // ride ended: forget completed climbs so re-riding the route starts at 0/N,
            // and start the next ride's elevation history from scratch
            if (!active && rideActive.value) {
                engine.reset()
                historyRepo.reset()
            }
            rideActive.value = active
```

8. **Field placement.** Directly after the `UserProfile` consumer block, which ends with `UserProfile.PreferredUnit.UnitType.IMPERIAL` and `}`, add:

```kotlin
        consumerIds += karooSystem.addConsumer<ActiveRideProfile> { event ->
            fieldInProfile.value = event.profile.pages.any { page ->
                page.elements.any { it.dataTypeId == ElevationHistoryField.DATA_TYPE_ID }
            }
        }
```

9. **Gate and demo feed.** At the end of `onCreate()`, after the closing `}` of the demo-mode `scope.launch` block and before `onCreate`'s own closing `}`, add:

```kotlin

        // Elevation history (spec §7.1): record while the field is placed, from the
        // demo feed in demo mode or the ride's streams otherwise; switching between
        // the two never continues the other's history.
        scope.launch {
            val placed = combine(fieldInProfile, historyField.runningViews) { inProfile, views ->
                inProfile || views > 0
            }
            combine(placed, rideActive, settingsRepo.settings.map { it.demoMode }) { isPlaced, riding, demo ->
                HistoryGate.sourceFor(isPlaced, riding, demo)
            }
                .distinctUntilChanged()
                .collect { source ->
                    if (historyGate.switchTo(source)) historyRepo.reset()
                    if (source == HistorySource.RIDE) historyRepo.startStreams() else historyRepo.stopStreams()
                    historySource.value = source
                }
        }

        // Demo mode feeds the history from the canned route; the loop wrapping back
        // to 0 m starts it over (ElevationHistory's distance-drop rule).
        scope.launch {
            demoProgress.collect { at ->
                val profile = demoProfile
                if (historySource.value == HistorySource.DEMO && profile != null) {
                    historyRepo.appendDemo(at, profile.elevationAt(at))
                }
            }
        }
```

10. **Teardown.** In `onDestroy()`, replace

```kotlin
        progressRepo.stop()
        scope.cancel()
```

    with

```kotlin
        progressRepo.stop()
        historyRepo.stopStreams()
        scope.cancel()
```

11. **Companion.** Replace

```kotlin
    companion object {
        private const val TAG = "ClimberExtension"
```

    with

```kotlin
    companion object {
        /** Must match extension_info.xml's id. */
        const val EXTENSION_ID = "karoo-climber"
        private const val TAG = "ClimberExtension"
```

- [ ] **Step 8: Run all tests and build**

Run: `./gradlew testDebugUnitTest assembleDebug`
Expected: `BUILD SUCCESSFUL`, with **97** tests and 0 failures.

- [ ] **Step 9: Smoke test on the Karoo in demo mode**

If Task 1 didn't install a debug build, ask the user the Task 1 Step 1 question first.

```bash
adb install -r app/build/outputs/apk/debug/karoo-climber-plus-debug.apk
adb shell appops set com.hazzus.karooclimber SYSTEM_ALERT_WINDOW allow
adb logcat -c
```

Ask the user:
> On the Karoo, add the **Elevation history** field (Climber+ category) to a ride page, full width and about a third of the screen high. Does the picker show the name, icon and a preview graph? Then in the Climber+ app turn on Demo mode, turn the overlay off (so it doesn't cover the field), and open that ride page.

Wait 90 seconds, then:

```bash
adb shell input keyevent KEYCODE_WAKEUP; S=$(mktemp -d); adb exec-out screencap -p > "$S/field-1.png"; sleep 30; adb exec-out screencap -p > "$S/field-2.png"; echo "$S"
adb logcat -d | grep -E "ElevationHistoryField|FATAL EXCEPTION" | head -20
```

View both screenshots. Expected:
- `field-2` shows a longer grade-colored shape than `field-1`.
- Values sit top left and top right, the min/max labels and grid are visible, and the marker is at the right end.
- The log shows `startView 480x…` and no `FATAL EXCEPTION`.

If the field shows Karoo's own "no data / searching" overlay instead of the graph, Karoo wants a stream value for graphical fields. Add this to `ElevationHistoryField` and repeat this step:

```kotlin
    // Karoo shows graphical fields only once their stream produces a value
    override fun startStream(emitter: Emitter<StreamState>) {
        val job = scope.launch {
            repo.version.collect {
                val current = repo.snapshot().current ?: return@collect
                emitter.onNext(StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to current))))
            }
        }
        emitter.setCancellable { job.cancel() }
    }
```

It needs these imports: `io.hammerhead.karooext.internal.Emitter`, `io.hammerhead.karooext.models.DataPoint` and `io.hammerhead.karooext.models.StreamState`.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/kotlin/com/hazzus/karooclimber/field/FramePacer.kt \
  app/src/main/kotlin/com/hazzus/karooclimber/field/HistoryGraphRenderer.kt \
  app/src/main/kotlin/com/hazzus/karooclimber/field/ElevationHistoryField.kt \
  app/src/main/kotlin/com/hazzus/karooclimber/ClimberExtension.kt \
  app/src/main/res/layout/field_elevation_history.xml \
  app/src/main/res/drawable/ic_elevation.xml \
  app/src/main/res/values/strings.xml \
  app/src/main/res/xml/extension_info.xml \
  app/src/test/kotlin/com/hazzus/karooclimber/FramePacerTest.kt
git commit -q -F - <<'EOF'
Adds the elevation history data field

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

---

### Task 7: Device verification and documentation

Spec §9 (checks) and §10 (docs).

**Files:**
- Modify: `README.md`
- Modify: `CLAUDE.md`
- Create: `docs/img/elevation-history.png` (a device screenshot)
- Modify: `docs/superpowers/specs/2026-10-12-elevation-history-field-design.md` (§9 verification results)

**Interfaces:**
- Consumes: the installed field from Task 6.
- Produces: verified behavior, docs and a screenshot.

- [ ] **Step 1: Check pacing, payload size and page switching (demo mode)**

With the Task 6 setup still running (demo mode, field on a page), run `adb logcat -c`.

Ask the user to:
1. Stay on the field's page for 2 minutes.
2. Switch to another ride page and back twice.
3. Change the field to full-screen size, then return to the ride.

Then run:

```bash
adb logcat -d | grep -cE "ignoring updateView, too soon"
adb logcat -d | grep -E "TransactionTooLarge|FATAL EXCEPTION|frame failed"
adb logcat -d | grep -E "ElevationHistoryField: (startView|stopView)"
```

Expected:
- The count is `0`.
- No `TransactionTooLarge`, `FATAL` or `frame failed` lines.
- Each page switch shows a `stopView` / `startView` pair, which confirms Karoo stops off-screen views.
- The full-screen size logs a larger `startView` and renders without errors.

- [ ] **Step 2: Measure render time on the Karoo 2 (temporary log)**

In `ElevationHistoryField.renderLoop`, temporarily replace

```kotlin
                            emitter.updateView(remoteViews(context, renderer.render(frame, scale)))
```

with

```kotlin
                            val t0 = System.nanoTime()
                            val bitmap = renderer.render(frame, scale)
                            Log.i(TAG, "render ${(System.nanoTime() - t0) / 1_000_000} ms")
                            emitter.updateView(remoteViews(context, bitmap))
```

Then build, install and read the timings:

```bash
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/karoo-climber-plus-debug.apk
sleep 60; adb logcat -d | grep "ElevationHistoryField: render" | tail -5
git checkout -- app/src/main/kotlin/com/hazzus/karooclimber/field/ElevationHistoryField.kt
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/karoo-climber-plus-debug.apk
```

Expected: under 50 ms per frame. If it's slower, report it to the user before going on.

- [ ] **Step 3: Take the README screenshot**

With demo mode running for at least 3 minutes, so most of the 5 km demo route is drawn:

```bash
adb shell input keyevent KEYCODE_WAKEUP; adb exec-out screencap -p > docs/img/elevation-history.png
```

View the image. If the shape covers less than half the field, retake it after another minute.

- [ ] **Step 4: Update the README**

1. **Screenshot table.** Replace the header, separator and image rows with:

```markdown
| Chip (climbing) | Drawer (approaching) | Drawer (climbing) | Climbs list | Full screen | Elevation history |
|---|---|---|---|---|---|
| ![](docs/img/chip-climb.png) | ![](docs/img/drawer-approach.png) | ![](docs/img/drawer-climbing.png) | ![](docs/img/climb-list.png) | ![](docs/img/full-view.png) | ![](docs/img/elevation-history.png) |
```

2. **Features.** At the end of the `## Features` list, directly before `### Gestures`, add:

```markdown
- **Elevation history data field**: a graphical Karoo data field showing the
  elevation you've ridden so far on this ride, refitting to the whole ride as
  it grows and colored by grade with the selected palette. Each pixel column
  shows the steepest 100 m chunk it covers, so short ramps stay visible on long
  rides. Current elevation and ascent sit on top, with min/max labels and a
  distance grid when the field is big enough. Works with or without a route
```

3. **Configuration.** At the end of the `## Configuration` list, add:

```markdown
- **Elevation history field**: in Karoo's page editor, add *Elevation history*
  from the Climber+ category to any ride page. It uses the palette chosen here
  and records only while it's on a page of the active ride profile
```

4. **How it works.** At the end of the `## How it works` list, before `### Limitations`, add:

```markdown
- The elevation history records the `DISTANCE` and barometric elevation
  (`PRESSURE_ELEVATION_CORRECTION`) streams while the field is placed, smooths
  them over ±25 m and grades fixed 100 m chunks. It's drawn into a bitmap and
  sent through karoo-ext's `RemoteViews` at most every 5 s.
```

5. **Limitations.** At the end of the `### Limitations` list, add:

```markdown
- The elevation history starts over if the extension restarts mid-ride.
```

- [ ] **Step 5: Update CLAUDE.md**

1. **Architecture.** In `## Architecture`, after the `**\`palette/GradePalettes\`**` bullet, add:

```markdown
- **Elevation history field** (`field/`, `data/ElevationHistory*`, `data/HistoryGate`):
  a graphical karoo-ext data field (`ElevationHistoryField`, typeId
  `elevation-history`, declared in `extension_info.xml`, registered via
  `ClimberExtension.types`) showing the ride's elevation so far, colored by grade.
  - Recording: `ElevationHistoryRepo` records `DISTANCE` + `PRESSURE_ELEVATION_CORRECTION`
    (+ `ELEVATION_GAIN`) only while `HistoryGate` says so: the field is on a page
    of the active ride profile (`ActiveRideProfile`) or a view is running; in demo
    mode the demo ticker feeds it instead.
  - Model: `ElevationHistory` keeps 10 m bin points; `HistorySnapshot` derives the
    ±25 m smoothing, frozen 100 m grades and min/max lazily.
  - Drawing: `HistoryGraphLayout` (pure) builds a `HistoryFrame` (steepest chunk per
    pixel column), and `HistoryGraphRenderer` paints it.
- **`ui/`**: unit formatting (`Units.kt`) and the IBM Plex loader (`KarooFonts`),
  shared by the overlay and the field.
```

2. **Gotchas.** At the end of `## Conventions / gotchas`, add:

```markdown
- karoo-ext calls `DataTypeImpl.startView` on a binder thread, and
  `ViewEmitter.updateView` silently drops a frame sent < 900 ms after the last
  one. The field paces itself (`FramePacer`: every 5 s, skips identical frames).
- Field bitmaps travel through binder: RGB_565, ≤ 300 KB
  (`HistoryGraphLayout.bitmapScale`). Bigger fields render scaled down and the
  `ImageView` stretches them.
- Palette and layout code must stay JVM-safe (no `android.graphics.Color` calls),
  because the field's layout tests use `GradePalettes` directly.
```

- [ ] **Step 6: Record the verification results in the spec**

In spec §9, append under the probe results (fill every bracket):

```markdown
### Verification (2026-10-12, Karoo 2)

- Picker: name/icon/preview [ok / notes]
- Demo mode: graph grows and refits [ok / notes]; `too soon` drops: [count]; TransactionTooLarge: [none / notes]
- Page switch stops off-screen views: [yes / no]
- Render time: [n] ms per frame
- Real ride: [pending / results]
```

- [ ] **Step 7: Final check and commit**

Run: `./gradlew testDebugUnitTest assembleDebug`
Expected: `BUILD SUCCESSFUL`, with **97** tests and 0 failures.

```bash
git add README.md CLAUDE.md docs/img/elevation-history.png docs/superpowers/specs/2026-10-12-elevation-history-field-design.md
git commit -q -F - <<'EOF'
Documents the elevation history field

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
```

- [ ] **Step 8: Hand over the real-ride check**

Tell the user:
1. **The real-ride check is theirs.** Ride at least 20 minutes, including a climb, with the field on a page, then reconnect the Karoo. On return, capture `adb logcat -d | grep -E "ElevationHistoryField|FATAL"`, take a screenshot, and compare the field's ascent with Karoo's Ascent field. Flat sections should stay in the palette's lowest colors. Record the results in spec §9's "Real ride" line and commit.
2. **Getting back to the official release.** Run `adb uninstall com.hazzus.karooclimber`, then install `karoo-climber-plus.apk` from the GitHub releases page. The debug build and the release have different signatures.
