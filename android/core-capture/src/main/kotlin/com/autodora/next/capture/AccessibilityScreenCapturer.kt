package com.autodora.next.capture

import android.graphics.Bitmap
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class AccessibilityCaptureStatus {
    SUCCESS,
    NO_PERMISSION,
    RATE_LIMITED,
    TRANSIENT_FAILURE
}

data class AccessibilityCaptureOutcome(
    val bitmap: Bitmap? = null,
    val status: AccessibilityCaptureStatus = AccessibilityCaptureStatus.TRANSIENT_FAILURE
)

/**
 * Android 11+ (API 30+) [ScreenCapturer] backed by `AccessibilityService.takeScreenshot()`.
 *
 * Why this exists:
 * - Android 14–16 / Xiaomi HyperOS strictly enforce a single exclusive system-wide MediaProjection slot,
 *   so holding MediaProjection blocks Xiaomi's built-in Screen Recorder (and vice versa).
 * - `AccessibilityService.takeScreenshot()` fetches live compositor frames directly without occupying
 *   MediaProjection, requires zero extra consent dialogs, never freezes after idle, and coexists cleanly
 *   with system screen recording.
 *
 * Pacing & Coexistence guarantees:
 * - Enforces a serialized cooldown (`minIntervalMs`, default 370ms, safely above AOSP's 333ms limit)
 *   with one automatic retry on [AccessibilityCaptureStatus.RATE_LIMITED].
 * - Caps multi-frame bursts to at most 2 frames (~370ms apart — optimal half-cycle phase separation for
 *   enchanted-orb sparkle & vortex animation without adding >0.4s latency).
 * - Background watcher frames use [captureBackgroundFrame] with `tryLock` + `backgroundMinIntervalMs`
 *   so background polling never contends with foreground solve or saturates SurfaceFlinger during recording.
 */
class AccessibilityScreenCapturer(
    override val isReady: StateFlow<Boolean>,
    private val screenSizeProvider: () -> Pair<Int, Int>,
    private val captureProvider: suspend () -> AccessibilityCaptureOutcome,
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    private val backgroundMinIntervalMs: Long = DEFAULT_BACKGROUND_INTERVAL_MS,
    private val retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val sleepFn: suspend (Long) -> Unit = { delay(it) }
) : ScreenCapturer {

    private val captureMutex = Mutex()
    @Volatile
    private var lastCaptureTimestampMs: Long = 0L
    @Volatile
    private var permissionErrorFlag: Boolean = false
    @Volatile
    private var cachedWidth: Int = 0
    @Volatile
    private var cachedHeight: Int = 0

    override val screenWidth: Int
        get() = if (cachedWidth > 0) cachedWidth else screenSizeProvider().first

    override val screenHeight: Int
        get() = if (cachedHeight > 0) cachedHeight else screenSizeProvider().second

    override val minFrameIntervalMs: Long
        get() = minIntervalMs

    override val isAccessibilityBased: Boolean
        get() = true

    override val lastPermissionError: Boolean
        get() = permissionErrorFlag

    override val lastFrameAgeMs: Long
        get() = 0L // Every capture fetches a fresh live compositor frame directly

    override suspend fun captureSingleFrame(): Bitmap? {
        if (!isReady.value) return null
        return captureMutex.withLock {
            captureLocked()
        }
    }

    override suspend fun captureBackgroundFrame(): Bitmap? {
        if (!isReady.value) return null
        if (!captureMutex.tryLock()) return null
        return try {
            val now = clockMs()
            if (lastCaptureTimestampMs > 0L && (now - lastCaptureTimestampMs) < backgroundMinIntervalMs) {
                null
            } else {
                captureLocked(allowRetry = false)
            }
        } finally {
            captureMutex.unlock()
        }
    }

    override suspend fun captureBandBitmap(yStart: Int, yEnd: Int): BandBitmap? {
        val full = captureSingleFrame() ?: return null
        val safeTop = yStart.coerceIn(0, (full.height - 1).coerceAtLeast(0))
        val safeBot = yEnd.coerceIn(safeTop + 1, full.height)
        val bandH = safeBot - safeTop
        if (safeTop == 0 && bandH == full.height) {
            return BandBitmap(full, full.width, full.height, 0)
        }
        return try {
            val cropped = Bitmap.createBitmap(full, 0, safeTop, full.width, bandH)
            BandBitmap(cropped, full.width, full.height, safeTop)
        } finally {
            if (!full.isRecycled) full.recycle()
        }
    }

    override suspend fun captureMultiFrames(count: Int, intervalMs: Long): List<Bitmap> {
        if (!isReady.value) return emptyList()
        // Cap burst to at most 2 frames spaced by >= minIntervalMs (370ms):
        // captures half-cycle animated shimmer / vortex rotation cleanly without excessive wait.
        val effectiveCount = count.coerceIn(1, MAX_ACCESSIBILITY_BURST_FRAMES)
        val effectiveInterval = maxOf(intervalMs, minIntervalMs)
        val result = ArrayList<Bitmap>(effectiveCount)
        for (i in 0 until effectiveCount) {
            if (i > 0) {
                val elapsed = clockMs() - lastCaptureTimestampMs
                val remain = effectiveInterval - elapsed
                if (remain > 0L) sleepFn(remain)
            }
            val bmp = captureSingleFrame()
            if (bmp != null) {
                result.add(bmp)
            } else if (permissionErrorFlag) {
                break
            }
        }
        return result
    }

    override fun clearCachedFrame() {
        // Live on-demand capture never holds a cached stale frame.
        permissionErrorFlag = false
    }

    override fun release() {
        // Nothing owned persistently; AccessibilityService lifecycle is managed by Android OS.
    }

    private suspend fun captureLocked(allowRetry: Boolean = true): Bitmap? {
        val now = clockMs()
        if (lastCaptureTimestampMs > 0L) {
            val waitMs = minIntervalMs - (now - lastCaptureTimestampMs)
            if (waitMs > 0L) {
                sleepFn(waitMs)
            }
        }
        var outcome = captureProvider()
        lastCaptureTimestampMs = clockMs()

        if (outcome.status == AccessibilityCaptureStatus.RATE_LIMITED && allowRetry) {
            sleepFn(retryDelayMs)
            outcome = captureProvider()
            lastCaptureTimestampMs = clockMs()
        }

        permissionErrorFlag = outcome.status == AccessibilityCaptureStatus.NO_PERMISSION
        val bmp = outcome.bitmap
        if (bmp != null) {
            if (bmp.width > 0) cachedWidth = bmp.width
            if (bmp.height > 0) cachedHeight = bmp.height
        }
        return bmp
    }

    companion object {
        const val DEFAULT_MIN_INTERVAL_MS = 370L
        const val DEFAULT_BACKGROUND_INTERVAL_MS = 850L
        const val DEFAULT_RETRY_DELAY_MS = 220L
        const val MAX_ACCESSIBILITY_BURST_FRAMES = 2
    }
}
