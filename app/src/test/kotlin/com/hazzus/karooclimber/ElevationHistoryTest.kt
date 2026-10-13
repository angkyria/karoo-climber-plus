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
