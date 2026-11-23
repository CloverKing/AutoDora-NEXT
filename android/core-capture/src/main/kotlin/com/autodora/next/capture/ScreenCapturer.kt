package com.autodora.next.capture

import android.graphics.Bitmap
import kotlinx.coroutines.flow.StateFlow

/**
 * Lightweight horizontal band capture of the screen (rows [yOffset .. yOffset + bitmap.height)),
 * avoiding full-screen copies & rowStride re-crop allocations during latency-critical Hold-and-Replan polling.
 */
data class BandBitmap(
    val bitmap: Bitmap,
    val fullWidth: Int,
    val fullHeight: Int,
    val yOffset: Int
)

interface ScreenCapturer {
    val isReady: StateFlow<Boolean>
    val screenWidth: Int
    val screenHeight: Int

    suspend fun captureSingleFrame(): Bitmap?
    suspend fun captureBandBitmap(yStart: Int, yEnd: Int): BandBitmap? {
        val full = captureSingleFrame() ?: return null
        return BandBitmap(full, full.width, full.height, 0)
    }
    suspend fun captureMultiFrames(count: Int = 3, intervalMs: Long = 55L): List<Bitmap>
    /** Age in milliseconds since the compositor last delivered a fresh frame (0 when unknown/mock). */
    val lastFrameAgeMs: Long get() = 0L
    /** Discards any previously buffered frame so the next capture waits for a fresh post-hide compositor frame. */
    fun clearCachedFrame() {}
    /**
     * Attempts to unstick a stale/blank VirtualDisplay ImageReader by swapping in a fresh Surface
     * without requiring a new MediaProjection consent token. Returns true if a live frame arrived.
     */
    suspend fun tryRecoverSurface(): Boolean = false
    fun release()
}
