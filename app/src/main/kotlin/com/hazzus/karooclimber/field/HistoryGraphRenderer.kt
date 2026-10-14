package com.hazzus.karooclimber.field

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
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

    // nearest-neighbour sampling keeps the color bands crisp on every Android version
    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = false }
    private val shapePath = Path()
    private val shapeMatrix = Matrix()
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
        drawShape(canvas, f, bottom)
        if (marker != null) {
            val mx = marker.x.toFloat()
            val my = marker.y.toFloat()
            canvas.drawCircle(mx, my, f.markerRadius, markerPaint)
            canvas.drawCircle(mx, my, f.markerRadius * 0.45f, markerCenterPaint)
        }
        drawLabels(canvas, f)
        drawValues(canvas, f)
    }

    /**
     * The ridden shape: one antialiased outline through the column tops, filled
     * from a strip holding one grade color per pixel column. Separate 1 px bars
     * would step on every slope, and the steps grow when a big field's bitmap is
     * stretched back to field size.
     */
    private fun drawShape(canvas: Canvas, f: HistoryFrame, bottom: Float) {
        val count = f.columnTops.size
        if (count == 0) return
        val left = f.graphLeft.toFloat()
        val right = left + count
        shapePath.rewind()
        shapePath.moveTo(left, bottom)
        shapePath.lineTo(left, f.columnTops[0])
        for (i in 0 until count) shapePath.lineTo(left + i + 0.5f, f.columnTops[i])
        shapePath.lineTo(right, f.columnTops[count - 1])
        shapePath.lineTo(right, bottom)
        shapePath.close()

        val strip = Bitmap.createBitmap(IntArray(count) { f.columnColors[it] }, count, 1, Bitmap.Config.ARGB_8888)
        shapeMatrix.setTranslate(left, 0f)
        shapePaint.shader = BitmapShader(strip, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(shapeMatrix)
        }
        canvas.drawPath(shapePath, shapePaint)
        shapePaint.shader = null
        strip.recycle()
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
        f.hint?.let { hint ->
            labelPaint.color = DISTANCE_LABEL_COLOR
            labelPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(hint.text, f.width / 2f, hint.y + baselineOffset, labelPaint)
            labelPaint.textAlign = Paint.Align.LEFT
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
