package com.hazzus.karooclimber.overlay

import com.hazzus.karooclimber.palette.GradePalette

/**
 * The chip looks like the ride app's navigation pill ("Rerouting"): one solid pill,
 * large text. On or ahead of a climb it takes the grade color; beside that pill
 * while navigating (a narrow slot) it says one thing.
 */
object ChipStyle {
    /** No climb ahead: solid dark, opaque like the navigation pill. */
    const val IDLE = 0xFF121212.toInt()
    const val TEXT_LIGHT = 0xFFFFFFFF.toInt()
    const val TEXT_DARK = 0xFF111111.toInt()

    /** [grade]: the grade underfoot on a climb, the climb's average before it; null: no climb. */
    fun background(grade: Double?, palette: GradePalette): Int = grade?.let(palette::chunkColor) ?: IDLE

    /** Dark text on light grade colors (yellow, light green), white on the rest. */
    fun textOn(background: Int): Int {
        val r = (background shr 16 and 0xFF) / 255.0
        val g = (background shr 8 and 0xFF) / 255.0
        val b = (background and 0xFF) / 255.0
        return if (0.299 * r + 0.587 * g + 0.114 * b > 0.6) TEXT_DARK else TEXT_LIGHT
    }

    /** On a climb its grade, before one the distance to it, otherwise the count done. */
    fun compactText(completed: Int, total: Int, toStart: String?, grade: String?): String =
        grade ?: toStart ?: "$completed/$total"
}
