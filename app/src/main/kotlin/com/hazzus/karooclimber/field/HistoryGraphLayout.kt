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
    /**
     * Top y of pixel column i, which spans [graphLeft + i, graphLeft + i + 1), in
     * quarter pixels: the renderer joins them into one antialiased outline, so a
     * steady slope draws as a straight line instead of whole-pixel steps.
     */
    val columnTops: List<Float>,
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
    /** Centered in the plot while an empty field waits for the ride to start. */
    val hint: Label? = null,
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

    /** Before the ride an empty field says why it's empty, instead of reading as broken. */
    const val WAITING_HINT = "STARTS WHEN YOU RIDE"

    private const val BOTTOM_MARGIN = 0.10
    private const val TOP_HEADROOM = 0.06
    private const val MAX_RULES = 5

    /** Column tops per pixel: fine enough for a smooth outline, coarse enough that frames still repeat. */
    private const val SUBPIXELS = 4
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

    /**
     * [liveElevation] stands in for the current elevation until the history has a
     * sample; [waitingForRide] adds [WAITING_HINT] to a field with nothing to draw.
     */
    fun frame(
        width: Int,
        height: Int,
        s: HistorySnapshot,
        ascent: Double?,
        palette: GradePalette,
        imperial: Boolean,
        liveElevation: Double? = null,
        waitingForRide: Boolean = false,
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
            currentText = (s.current ?: liveElevation)?.let { elevationText(it, imperial) } ?: "--",
            ascentText = ascent?.let { elevationText(it, imperial) } ?: "--",
            hint = if (waitingForRide && s.size < 2) {
                HistoryFrame.Label(((plotTop + plotBottom) / 2).roundToInt(), WAITING_HINT)
            } else {
                null
            },
        )
        if (s.size < 2) return empty

        val (x0, x1) = xRange(s)
        val (y0, y1) = yRange(s)
        fun xOf(distance: Double): Int = (left + (distance - x0) / (x1 - x0) * columnCount).roundToInt()
        fun yAt(elevation: Double): Double = plotBottom - (elevation - y0) / (y1 - y0) * (plotBottom - plotTop)
        fun yOf(elevation: Double): Int = yAt(elevation).roundToInt()

        val columns = columns(s, columnCount)
        val unit = if (imperial) METERS_PER_MILE else 1000.0
        val step = gridStep(x1 - x0, unit)
        val maxY = yOf(s.maxElevation)
        val minY = yOf(s.minElevation)
        return empty.copy(
            columnTops = columns.map { (yAt(it.peak) * SUBPIXELS).roundToInt() / SUBPIXELS.toFloat() },
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
