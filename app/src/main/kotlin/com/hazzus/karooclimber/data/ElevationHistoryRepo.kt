package com.hazzus.karooclimber.data

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Feeds [ElevationHistory] from Karoo's DISTANCE, PRESSURE_ELEVATION_CORRECTION
 * and ELEVATION_GAIN streams while [setStreams] asks for a ride's streams, or
 * from the demo feed. Main thread only; [snapshot] hands an immutable copy to
 * the field's render loops.
 */
class ElevationHistoryRepo(private val karooSystem: KarooSystemService) {

    private val history = ElevationHistory()
    private var streams = HistoryStreams.NONE
    private var elevationConsumer: String? = null
    private val rideConsumers = mutableListOf<String>()
    private var elevation: Double? = null

    /** The history holds a sample; until then the field shows [liveElevation]. */
    private var recorded = false

    private val _version = MutableStateFlow(0L)

    /** Changes whenever the history, the ascent or an empty field's elevation changes; render loops watch it. */
    val version: StateFlow<Long> = _version

    private val _ascent = MutableStateFlow<Double?>(null)

    /** Ascent so far (m): Karoo's own ELEVATION_GAIN, or the recorded climb in demo mode. */
    val ascent: StateFlow<Double?> = _ascent

    private val _liveElevation = MutableStateFlow<Double?>(null)

    /** Latest barometric elevation (m) while any stream runs; what a field without samples shows. */
    val liveElevation: StateFlow<Double?> = _liveElevation

    fun snapshot(): HistorySnapshot = history.snapshot()

    /** Starts and stops the Karoo consumers to match [next] (spec §7.1). */
    fun setStreams(next: HistoryStreams) {
        if (next == streams) return
        streams = next
        if (next == HistoryStreams.NONE) {
            elevationConsumer?.let { karooSystem.removeConsumer(it) }
            elevationConsumer = null
            elevation = null
            _liveElevation.value = null
        } else if (elevationConsumer == null) {
            // data points carry several fields: read by key, SINGLE as the fallback
            elevationConsumer = stream(DataType.Type.PRESSURE_ELEVATION_CORRECTION) { values ->
                val value = values[DataType.Field.PRESSURE_ELEVATION] ?: values[DataType.Field.SINGLE]
                elevation = value
                _liveElevation.value = value
                if (!recorded) changed() // an empty field shows it
            }
        }
        if (next == HistoryStreams.RIDE) {
            if (rideConsumers.isEmpty()) {
                rideConsumers += stream(DataType.Type.ELEVATION_GAIN) { values ->
                    _ascent.value = values[DataType.Field.ELEVATION_GAIN] ?: values[DataType.Field.SINGLE]
                    changed()
                }
                rideConsumers += stream(DataType.Type.DISTANCE) { values ->
                    val distance = values[DataType.Field.DISTANCE] ?: values[DataType.Field.SINGLE]
                    val elev = elevation
                    if (distance != null && elev != null) append(distance, elev)
                }
            }
        } else {
            rideConsumers.forEach { karooSystem.removeConsumer(it) }
            rideConsumers.clear()
        }
    }

    /** Demo feed: one synthetic sample; the ascent follows the recorded shape. */
    fun appendDemo(distance: Double, elevation: Double) {
        history.append(distance, elevation)
        recorded = true
        _ascent.value = history.snapshot().climbed
        changed()
    }

    fun reset() {
        history.reset()
        recorded = false
        _ascent.value = null
        changed()
    }

    private fun append(distance: Double, elevation: Double) {
        val before = history.version
        history.append(distance, elevation)
        if (history.version != before) {
            recorded = true
            changed()
        }
    }

    private fun changed() {
        _version.value += 1
    }

    private fun stream(typeId: String, onValues: (Map<String, Double>) -> Unit): String =
        karooSystem.addConsumer<OnStreamState>(OnStreamState.StartStreaming(typeId)) { event ->
            (event.state as? StreamState.Streaming)?.dataPoint?.values?.let(onValues)
        }
}
