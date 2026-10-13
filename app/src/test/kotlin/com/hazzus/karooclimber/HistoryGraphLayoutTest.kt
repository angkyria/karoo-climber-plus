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
