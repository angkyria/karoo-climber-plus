package com.hazzus.karooclimber.data

/** Where the elevation history's samples come from right now. */
enum class HistorySource { NONE, RIDE, DEMO }

/** Which Karoo streams the history needs: none, the elevation alone (empty field on screen), or a ride's. */
enum class HistoryStreams { NONE, ELEVATION, RIDE }

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

        /**
         * A ride records distance, elevation and ascent. Before the ride, a field on
         * screen still shows the current elevation, so it streams that alone; the
         * demo feed needs nothing from Karoo.
         */
        fun streamsFor(source: HistorySource, viewRunning: Boolean): HistoryStreams = when {
            source == HistorySource.RIDE -> HistoryStreams.RIDE
            source == HistorySource.NONE && viewRunning -> HistoryStreams.ELEVATION
            else -> HistoryStreams.NONE
        }
    }
}
