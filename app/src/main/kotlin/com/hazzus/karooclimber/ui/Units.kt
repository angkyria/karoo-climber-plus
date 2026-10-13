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
