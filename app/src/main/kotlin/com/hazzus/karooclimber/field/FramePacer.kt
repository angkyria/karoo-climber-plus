package com.hazzus.karooclimber.field

/**
 * Frame pacing for a graphical field. karoo-ext silently drops an updateView
 * sent < 900 ms after the previous one, and every frame is an IPC carrying a
 * bitmap, so frames go out at most every [intervalMs] and only when they differ
 * from the last one sent.
 */
class FramePacer(
    private val intervalMs: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var lastSentAt: Long? = null
    private var lastFrame: Any? = null

    /** Milliseconds to wait before the next frame may go out; 0 for the first one. */
    fun waitMs(): Long = lastSentAt?.let { (it + intervalMs - now()).coerceAtLeast(0L) } ?: 0L

    /** False when [frame] equals the last frame sent: identical pixels, skip the IPC. */
    fun isNew(frame: Any): Boolean = frame != lastFrame

    fun sent(frame: Any) {
        lastSentAt = now()
        lastFrame = frame
    }
}
