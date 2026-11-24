package com.autodora.next.capture

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import com.autodora.next.data.CaptureMode
import com.autodora.next.touch.AccessibilityScreenshotFailure
import com.autodora.next.touch.AccessibilityServiceBridge

/**
 * Unified capture backend router shared by MainActivity and OverlayControllerService.
 *
 * - On Android 11+ (API 30+), defaults to [CaptureMode.ACCESSIBILITY] (`AccessibilityService.takeScreenshot`),
 *   which never occupies Android's exclusive MediaProjection slot — letting Xiaomi / HyperOS built-in
 *   Screen Recorder run simultaneously without conflict and eliminating repeated screen-cast consent prompts.
 * - On Android 10 (API 29), or whenever the user explicitly selects [CaptureMode.MEDIA_PROJECTION],
 *   routes to the classic [MediaProjectionForegroundService] / [ScreenCaptureSession] capturer.
 */
object CaptureRouter {

    fun isAccessibilityCaptureSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun effectiveMode(requested: CaptureMode): CaptureMode =
        if (requested == CaptureMode.ACCESSIBILITY && isAccessibilityCaptureSupported()) {
            CaptureMode.ACCESSIBILITY
        } else {
            CaptureMode.MEDIA_PROJECTION
        }

    @Volatile
    private var a11yCapturer: AccessibilityScreenCapturer? = null

    @Synchronized
    fun getAccessibilityCapturer(context: Context): AccessibilityScreenCapturer {
        val existing = a11yCapturer
        if (existing != null) return existing
        val appCtx = context.applicationContext
        val created = AccessibilityScreenCapturer(
            isReady = AccessibilityServiceBridge.isConnected,
            screenSizeProvider = { queryScreenSize(appCtx) },
            captureProvider = {
                val res = AccessibilityServiceBridge.takeScreenshotResult()
                val status = when (res.failure) {
                    AccessibilityScreenshotFailure.NONE -> AccessibilityCaptureStatus.SUCCESS
                    AccessibilityScreenshotFailure.NO_PERMISSION -> AccessibilityCaptureStatus.NO_PERMISSION
                    AccessibilityScreenshotFailure.RATE_LIMITED -> AccessibilityCaptureStatus.RATE_LIMITED
                    else -> AccessibilityCaptureStatus.TRANSIENT_FAILURE
                }
                AccessibilityCaptureOutcome(res.bitmap, status)
            }
        )
        a11yCapturer = created
        return created
    }

    fun resolveActiveCapturer(context: Context, requestedMode: CaptureMode): ScreenCapturer? {
        return when (effectiveMode(requestedMode)) {
            CaptureMode.ACCESSIBILITY -> getAccessibilityCapturer(context)
            CaptureMode.MEDIA_PROJECTION -> ScreenCaptureSession.capturer.value
        }
    }

    fun releaseMediaProjectionIfRunning(context: Context) {
        if (ScreenRecordingSession.isRecording) {
            ScreenRecordingSession.stopAndSaveAsync()
        }
        if (ScreenCaptureSession.capturer.value != null) {
            ScreenCaptureSession.clear()
            MediaProjectionForegroundService.stop(context.applicationContext)
        }
    }

    private fun queryScreenSize(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }
}
