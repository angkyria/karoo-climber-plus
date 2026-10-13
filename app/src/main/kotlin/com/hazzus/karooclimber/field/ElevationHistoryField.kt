package com.hazzus.karooclimber.field

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.widget.RemoteViews
import com.hazzus.karooclimber.ClimberExtension
import com.hazzus.karooclimber.R
import com.hazzus.karooclimber.data.ElevationHistory
import com.hazzus.karooclimber.data.ElevationHistoryRepo
import com.hazzus.karooclimber.data.HistorySnapshot
import com.hazzus.karooclimber.debug.DemoData
import com.hazzus.karooclimber.palette.GradePalettes
import com.hazzus.karooclimber.settings.Settings
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Graphical Karoo data field: the elevation ridden so far, colored by grade
 * (docs/superpowers/specs/2026-10-12-elevation-history-field-design.md).
 *
 * Karoo calls [startView] on a binder thread, once per visible instance. Each
 * instance runs a render loop on the extension's main scope, lays out and draws
 * off the main thread, and sends a bitmap through RemoteViews at most every
 * [FRAME_INTERVAL_MS]: karoo-ext silently drops frames < 900 ms apart.
 */
class ElevationHistoryField(
    private val scope: CoroutineScope,
    private val repo: ElevationHistoryRepo,
    private val settings: Flow<Settings>,
    private val imperial: StateFlow<Boolean>,
) : DataTypeImpl(ClimberExtension.EXTENSION_ID, TYPE_ID) {

    private val _runningViews = MutableStateFlow(0)

    /** Non-preview views on screen; counts as "field placed" for the recording gate. */
    val runningViews: StateFlow<Int> = _runningViews

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val (width, height) = config.viewSize
        Log.i(TAG, "startView ${width}x$height textSize=${config.textSize} preview=${config.preview}")
        // previews too: with Karoo's header left on, the bitmap drawn for the whole
        // field is squeezed into the strip under it
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        if (config.preview) {
            // page editor: a static sample ride, never counted as placed
            val job = scope.launch {
                val paletteId = settings.first().paletteId
                withContext(Dispatchers.Default) {
                    try {
                        val sample = previewSnapshot()
                        val frame = HistoryGraphLayout.frame(
                            width, height, sample, sample.climbed, GradePalettes.byId(paletteId), imperial.value,
                        )
                        val bitmap = HistoryGraphRenderer(context).render(frame, HistoryGraphLayout.bitmapScale(width, height))
                        emitter.updateView(remoteViews(context, bitmap))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "preview failed", e)
                    }
                }
            }
            emitter.setCancellable { job.cancel() }
            return
        }
        val job = scope.launch {
            _runningViews.value += 1
            try {
                renderLoop(context, width, height, emitter)
            } finally {
                _runningViews.value -= 1
            }
        }
        emitter.setCancellable {
            Log.i(TAG, "stopView ${width}x$height")
            job.cancel()
        }
    }

    private suspend fun renderLoop(context: Context, width: Int, height: Int, emitter: ViewEmitter) {
        val renderer = HistoryGraphRenderer(context)
        val pacer = FramePacer(FRAME_INTERVAL_MS)
        val scale = HistoryGraphLayout.bitmapScale(width, height)
        combine(repo.version, settings, imperial) { _, s, units -> s.paletteId to units }
            .conflate()
            .collect { (paletteId, units) ->
                delay(pacer.waitMs())
                val snapshot = repo.snapshot() // main thread: the history lives here
                val ascent = repo.ascent.value
                withContext(Dispatchers.Default) {
                    try {
                        val frame = HistoryGraphLayout.frame(
                            width, height, snapshot, ascent, GradePalettes.byId(paletteId), units,
                        )
                        if (pacer.isNew(frame)) {
                            emitter.updateView(remoteViews(context, renderer.render(frame, scale)))
                            pacer.sent(frame)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // the field shares the process with the overlay: never crash it
                        Log.e(TAG, "frame failed", e)
                    }
                }
            }
    }

    /** The demo route ridden end to end: what Karoo's page editor shows. */
    private fun previewSnapshot(): HistorySnapshot {
        val history = ElevationHistory()
        val profile = DemoData.route().profile ?: return history.snapshot()
        var d = 0.0
        while (d <= profile.totalDistance) {
            history.append(d, profile.elevationAt(d))
            d += PREVIEW_STEP_M
        }
        return history.snapshot()
    }

    private fun remoteViews(context: Context, bitmap: Bitmap) =
        RemoteViews(context.packageName, R.layout.field_elevation_history).apply {
            setImageViewBitmap(R.id.graph, bitmap)
        }

    companion object {
        /** Must match extension_info.xml's typeId. */
        const val TYPE_ID = "elevation-history"

        /** How Karoo names this field on ride pages (ActiveRideProfile elements). */
        val DATA_TYPE_ID: String = DataType.dataTypeId(ClimberExtension.EXTENSION_ID, TYPE_ID)

        const val FRAME_INTERVAL_MS = 5_000L
        private const val PREVIEW_STEP_M = 15.0
        private const val TAG = "ElevationHistoryField"
    }
}
