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
