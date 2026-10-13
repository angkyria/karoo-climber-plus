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
