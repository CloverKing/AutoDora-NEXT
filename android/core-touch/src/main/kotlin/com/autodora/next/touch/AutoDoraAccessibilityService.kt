package com.autodora.next.touch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.autodora.next.model.BoardGeometry
import com.autodora.next.model.CellCoord
import com.autodora.next.model.SolutionPath
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

enum class AccessibilityScreenshotFailure {
    NONE,
    API_UNSUPPORTED,
    SERVICE_DISCONNECTED,
    NO_PERMISSION,
    RATE_LIMITED,
    SECURE_WINDOW,
    INTERNAL_ERROR
}

data class AccessibilityScreenshotResult(
    val bitmap: Bitmap? = null,
    val failure: AccessibilityScreenshotFailure = AccessibilityScreenshotFailure.NONE
)

object AccessibilityServiceBridge {
    private val serviceRef = AtomicReference<AutoDoraAccessibilityService?>(null)
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    val isScreenshotSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    internal fun attach(service: AutoDoraAccessibilityService) {
        serviceRef.set(service)
        _isConnected.value = true
    }

    internal fun detach(service: AutoDoraAccessibilityService) {
        if (serviceRef.compareAndSet(service, null)) {
            _isConnected.value = false
        }
    }

    internal fun current(): AutoDoraAccessibilityService? = serviceRef.get()

    suspend fun takeScreenshotResult(): AccessibilityScreenshotResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return AccessibilityScreenshotResult(null, AccessibilityScreenshotFailure.API_UNSUPPORTED)
        }
        val svc = serviceRef.get()
            ?: return AccessibilityScreenshotResult(null, AccessibilityScreenshotFailure.SERVICE_DISCONNECTED)
        return svc.takeScreenshotResult()
    }
}

/**
 * No-Root AccessibilityService executing smooth continuous gesture strokes for Tower of Saviors,
 * and on Android 11+ (API 30+) also providing MediaProjection-free on-demand screenshots so
 * system screen recorders (e.g. HyperOS / Xiaomi 15) can run simultaneously without conflicts.
 */
class AutoDoraAccessibilityService : AccessibilityService() {

    private val cancelRequested = AtomicBoolean(false)
    private val screenshotExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AutoDoraA11yShot").apply { isDaemon = true }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityServiceBridge.attach(this)
        Log.i(TAG, "AutoDora AccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Gesture & on-demand screenshot service; no heavy event processing needed
    }

    override fun onInterrupt() {
        cancelRequested.set(true)
    }

    override fun onDestroy() {
        cancelRequested.set(true)
        AccessibilityServiceBridge.detach(this)
        try {
            screenshotExecutor.shutdownNow()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    /**
     * Captures a single live compositor frame via Android 11+ [takeScreenshot] without occupying
     * the system's exclusive MediaProjection slot. Returns a CPU-readable ARGB_8888 Bitmap.
     */
    suspend fun takeScreenshotResult(timeoutMs: Long = 1800L): AccessibilityScreenshotResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return AccessibilityScreenshotResult(null, AccessibilityScreenshotFailure.API_UNSUPPORTED)
        }
        val deferred = CompletableDeferred<AccessibilityScreenshotResult>()
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                screenshotExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val hb = screenshot.hardwareBuffer
                        val cs = screenshot.colorSpace
                        val swBitmap = try {
                            val hw = Bitmap.wrapHardwareBuffer(hb, cs)
                            try {
                                hw?.copy(Bitmap.Config.ARGB_8888, false)
                            } finally {
                                hw?.recycle()
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "Failed converting HardwareBuffer screenshot", t)
                            null
                        } finally {
                            try {
                                hb.close()
                            } catch (_: Exception) {
                            }
                        }
                        if (swBitmap != null) {
                            deferred.complete(AccessibilityScreenshotResult(swBitmap, AccessibilityScreenshotFailure.NONE))
                        } else {
                            deferred.complete(AccessibilityScreenshotResult(null, AccessibilityScreenshotFailure.INTERNAL_ERROR))
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        val reason = when (errorCode) {
                            ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> AccessibilityScreenshotFailure.NO_PERMISSION
                            ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> AccessibilityScreenshotFailure.RATE_LIMITED
                            ERROR_TAKE_SCREENSHOT_INVALID_WINDOW -> AccessibilityScreenshotFailure.SECURE_WINDOW
                            else -> AccessibilityScreenshotFailure.INTERNAL_ERROR
                        }
                        if (reason != AccessibilityScreenshotFailure.RATE_LIMITED) {
                            Log.w(TAG, "takeScreenshot failed: errorCode=$errorCode ($reason)")
                        }
                        deferred.complete(AccessibilityScreenshotResult(null, reason))
                    }
                }
            )
        } catch (se: SecurityException) {
            Log.w(TAG, "takeScreenshot SecurityException (canTakeScreenshot not yet granted)", se)
            return AccessibilityScreenshotResult(null, AccessibilityScreenshotFailure.NO_PERMISSION)
        } catch (t: Throwable) {
            Log.w(TAG, "takeScreenshot exception", t)
            return AccessibilityScreenshotResult(null, AccessibilityScreenshotFailure.INTERNAL_ERROR)
        }
        return withTimeoutOrNull(timeoutMs) { deferred.await() }
            ?: AccessibilityScreenshotResult(null, AccessibilityScreenshotFailure.INTERNAL_ERROR)
    }

    fun requestEmergencyStop() {
        cancelRequested.set(true)
    }

    suspend fun dispatchTap(x: Float, y: Float, holdMs: Long = 36L): Boolean {
        val sx = x.coerceAtLeast(1f)
        val sy = y.coerceAtLeast(1f)
        val p = Path().apply {
            moveTo(sx, sy)
            lineTo(sx + 0.5f, sy + 0.5f)
        }
        val stroke = GestureDescription.StrokeDescription(p, 0L, holdMs.coerceIn(16L, 2000L))
        return dispatchSingleStroke(stroke, false, ScreenPoint(sx, sy))
    }

    suspend fun dispatchSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 260L): Boolean {
        val sx1 = x1.coerceAtLeast(1f)
        val sy1 = y1.coerceAtLeast(1f)
        val sx2 = x2.coerceAtLeast(1f)
        val sy2 = y2.coerceAtLeast(1f)
        val p = Path().apply {
            moveTo(sx1, sy1)
            lineTo(sx2, sy2)
        }
        val stroke = GestureDescription.StrokeDescription(p, 0L, durationMs.coerceIn(60L, 1200L))
        return dispatchSingleStroke(stroke, false, ScreenPoint(sx2, sy2))
    }

    suspend fun dispatchChainedSegments(segments: List<StrokeSegment>): TouchExecutionResult {
        if (segments.isEmpty()) return TouchExecutionResult.Failed("Empty stroke segments")
        cancelRequested.set(false)
        val tStart = System.currentTimeMillis()
        var activeStroke: GestureDescription.StrokeDescription? = null
        var dispatched = 0

        for (seg in segments) {
            if (cancelRequested.get()) {
                // Clean finger lift if a continued stroke is currently held down!
                if (activeStroke != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val lastPt = seg.points.first()
                    val liftPath = Path().apply {
                        moveTo(lastPt.x, lastPt.y)
                        lineTo(lastPt.x + 0.5f, lastPt.y)
                    }
                    val liftStroke = activeStroke.continueStroke(liftPath, 0L, 12L, false)
                    dispatchSingleStroke(liftStroke, false, lastPt)
                }
                return TouchExecutionResult.Cancelled
            }

            val path = Path().apply {
                val p0 = seg.points.first()
                moveTo(p0.x, p0.y)
                for (i in 1 until seg.points.size) {
                    lineTo(seg.points[i].x, seg.points[i].y)
                }
            }

            val stroke = if (activeStroke == null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    GestureDescription.StrokeDescription(path, 0L, seg.durationMs, seg.willContinue)
                } else {
                    GestureDescription.StrokeDescription(path, 0L, seg.durationMs)
                }
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    activeStroke.continueStroke(path, 0L, seg.durationMs, seg.willContinue)
                } else {
                    GestureDescription.StrokeDescription(path, 0L, seg.durationMs)
                }
            }

            val ok = dispatchSingleStroke(stroke, seg.willContinue, seg.points.last())
            if (!ok) {
                return if (cancelRequested.get()) {
                    TouchExecutionResult.Cancelled
                } else {
                    TouchExecutionResult.Failed("Gesture segment $dispatched rejected or cancelled by system")
                }
            }

            dispatched++
            activeStroke = if (seg.willContinue) stroke else null
        }

        return TouchExecutionResult.Success(dispatched, System.currentTimeMillis() - tStart)
    }

    /**
     * No-Root Hold-and-Replan (问号‧转 / 风化‧转 / 烟雾):
     * Keeps the virtual finger continuously pressed via chained willContinue=true micro-heartbeat strokes
     * while capturing & re-solving the newly transformed board, then seamlessly continues swiping!
     */
    suspend fun dispatchHoldAndReplan(
        startCell: CellCoord,
        geometry: BoardGeometry,
        trigger: HoldReplanTrigger,
        nudgeTarget: CellCoord?,
        revealDelayMs: Long,
        maxHoldWaitMs: Long = 2400L,
        config: SwipeConfig,
        onBoardRevealed: suspend (heldCell: CellCoord) -> SolutionPath?
    ): TouchExecutionResult = coroutineScope {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return@coroutineScope TouchExecutionResult.Failed("按住重算模式需要 Android 8.0+ 连续手势支持")
        }
        cancelRequested.set(false)
        val tStart = System.currentTimeMillis()
        var dispatched = 0

        val startPt = ScreenPoint(geometry.cellCenterX(startCell.col), geometry.cellCenterY(startCell.row))
        val effectiveNudgeCell = if (trigger == HoldReplanTrigger.NUDGE_ONE_CELL_HOLD && nudgeTarget != null) {
            nudgeTarget
        } else {
            startCell
        }
        val heldPt = ScreenPoint(
            geometry.cellCenterX(effectiveNudgeCell.col),
            geometry.cellCenterY(effectiveNudgeCell.row)
        )

        // 1. Snappy press-down (and optional 1-cell nudge) with willContinue=true
        val initPath = Path().apply {
            moveTo(startPt.x, startPt.y)
            if (effectiveNudgeCell != startCell) {
                lineTo(heldPt.x, heldPt.y)
            } else {
                lineTo(startPt.x + 0.5f, startPt.y + 0.5f)
            }
        }
        val initDuration = if (effectiveNudgeCell != startCell) {
            config.stepDurationMs.coerceIn(32L, 48L)
        } else {
            45L
        }
        var activeStroke = GestureDescription.StrokeDescription(initPath, 0L, initDuration, true)
        var curFingerPt = if (effectiveNudgeCell != startCell) heldPt else ScreenPoint(startPt.x + 0.5f, startPt.y + 0.5f)
        if (!dispatchSingleStroke(activeStroke, true, curFingerPt)) {
            return@coroutineScope if (cancelRequested.get()) TouchExecutionResult.Cancelled
            else TouchExecutionResult.Failed("按住起手被系统拒绝")
        }
        dispatched++

        // 2. Launch parallel capture + early-ready reveal poll + fixedStartCell C++20 solve off Main thread!
        val replanDeferred = async(Dispatchers.Default) {
            if (revealDelayMs > 0L) {
                delay(revealDelayMs.coerceIn(20L, 1200L))
            }
            onBoardRevealed(effectiveNudgeCell)
        }

        // 3. Keep finger pressed seamlessly via calm micro-heartbeat strokes (supports up to 3.5s post-reveal solve!)
        var toggle = false
        var heartbeatIdx = 0
        val effectiveWaitCapMs = maxHoldWaitMs.coerceIn(1800L, 6500L)
        val maxWaitDeadline = System.currentTimeMillis() + revealDelayMs.coerceAtLeast(0L) + effectiveWaitCapMs
        while (!replanDeferred.isCompleted && !cancelRequested.get() && System.currentTimeMillis() < maxWaitDeadline) {
            val nextPt = if (toggle) {
                ScreenPoint(heldPt.x + 2.0f, heldPt.y)
            } else {
                ScreenPoint(heldPt.x - 2.0f, heldPt.y)
            }
            toggle = !toggle
            val holdPath = Path().apply {
                moveTo(curFingerPt.x, curFingerPt.y)
                lineTo(nextPt.x, nextPt.y)
            }
            // First hold covers initial flip latency (140ms); quick 85ms beats early; gentle 110ms beats during deep 1..3.5s solves
            val hbMs = when {
                heartbeatIdx == 0 -> 140L
                heartbeatIdx <= 5 -> 85L
                else -> 110L
            }
            heartbeatIdx++
            val nextStroke = activeStroke.continueStroke(holdPath, 0L, hbMs, true)
            if (!dispatchSingleStroke(nextStroke, true, nextPt)) {
                Log.w(TAG, "Hold heartbeat #$heartbeatIdx failed at ${System.currentTimeMillis() - tStart}ms")
                replanDeferred.cancel()
                // Dispatch a quick synthetic lift at current finger position so Unity never leaves the orb stuck down!
                runCatching {
                    val safeLift = Path().apply {
                        moveTo(curFingerPt.x, curFingerPt.y)
                        lineTo(curFingerPt.x + 1.0f, curFingerPt.y)
                    }
                    dispatchSingleStroke(GestureDescription.StrokeDescription(safeLift, 0L, 14L, false), false, curFingerPt)
                }
                return@coroutineScope if (cancelRequested.get()) TouchExecutionResult.Cancelled
                else TouchExecutionResult.Failed("按住保活中断 (hb=$heartbeatIdx)")
            }
            activeStroke = nextStroke
            curFingerPt = nextPt
            dispatched++
        }

        val solution = if (replanDeferred.isCompleted && !cancelRequested.get()) {
            try {
                replanDeferred.await()
            } catch (_: Exception) {
                null
            }
        } else {
            replanDeferred.cancel()
            null
        }

        if (solution == null || cancelRequested.get()) {
            // Always perform clean finger lift so touch NEVER sticks down!
            val liftPath = Path().apply {
                moveTo(curFingerPt.x, curFingerPt.y)
                lineTo(curFingerPt.x + 0.5f, curFingerPt.y)
            }
            val liftStroke = activeStroke.continueStroke(liftPath, 0L, 14L, false)
            dispatchSingleStroke(liftStroke, false, curFingerPt)
            return@coroutineScope if (cancelRequested.get()) TouchExecutionResult.Cancelled
            else TouchExecutionResult.Failed("重算未返回有效路线，已安全抬手")
        }

        // 4. Seamlessly continue swiping the freshly solved path without lifting the finger!
        val contSegments = PathInterpolator.buildContinuationSegments(solution, geometry, config, curFingerPt)
        if (contSegments.isEmpty()) {
            val liftPath = Path().apply {
                moveTo(curFingerPt.x, curFingerPt.y)
                lineTo(curFingerPt.x + 0.5f, curFingerPt.y)
            }
            val liftStroke = activeStroke.continueStroke(liftPath, 0L, 14L, false)
            dispatchSingleStroke(liftStroke, false, curFingerPt)
            return@coroutineScope TouchExecutionResult.Success(dispatched, System.currentTimeMillis() - tStart)
        }

        for (seg in contSegments) {
            if (cancelRequested.get()) {
                val liftPath = Path().apply {
                    moveTo(curFingerPt.x, curFingerPt.y)
                    lineTo(curFingerPt.x + 0.5f, curFingerPt.y)
                }
                val liftStroke = activeStroke.continueStroke(liftPath, 0L, 12L, false)
                dispatchSingleStroke(liftStroke, false, curFingerPt)
                return@coroutineScope TouchExecutionResult.Cancelled
            }
            val segPath = Path().apply {
                val p0 = seg.points.first()
                moveTo(p0.x, p0.y)
                for (i in 1 until seg.points.size) {
                    lineTo(seg.points[i].x, seg.points[i].y)
                }
            }
            val nextStroke = activeStroke.continueStroke(segPath, 0L, seg.durationMs, seg.willContinue)
            if (!dispatchSingleStroke(nextStroke, seg.willContinue, seg.points.last())) {
                return@coroutineScope if (cancelRequested.get()) TouchExecutionResult.Cancelled
                else TouchExecutionResult.Failed("按住重算续滑失败")
            }
            activeStroke = nextStroke
            curFingerPt = seg.points.last()
            dispatched++
        }

        TouchExecutionResult.Success(dispatched, System.currentTimeMillis() - tStart)
    }

    private suspend fun dispatchSingleStroke(
        stroke: GestureDescription.StrokeDescription,
        willContinue: Boolean,
        endPoint: ScreenPoint
    ): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val submitted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    // If emergency stop fired while this continued stroke was in-flight (or coroutine cancelled),
                    // immediately lift the finger right here inside callback so touch never sticks down!
                    if (willContinue && cancelRequested.get() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val liftPath = Path().apply {
                            moveTo(endPoint.x, endPoint.y)
                            lineTo(endPoint.x + 0.5f, endPoint.y)
                        }
                        val liftStroke = stroke.continueStroke(liftPath, 0L, 12L, false)
                        dispatchGesture(GestureDescription.Builder().addStroke(liftStroke).build(), null, null)
                        deferred.complete(false)
                    } else {
                        deferred.complete(true)
                    }
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "dispatchGesture onCancelled (willContinue=$willContinue, cancelReq=${cancelRequested.get()})")
                    deferred.complete(false)
                }
            },
            null
        )
        if (!submitted) {
            Log.w(TAG, "dispatchGesture returned submitted=false (willContinue=$willContinue)")
            deferred.complete(false)
        }
        return deferred.await()
    }

    companion object {
        private const val TAG = "AutoDoraA11y"
    }
}
