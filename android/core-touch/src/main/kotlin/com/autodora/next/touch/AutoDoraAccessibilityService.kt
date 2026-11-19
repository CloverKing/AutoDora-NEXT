package com.autodora.next.touch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

object AccessibilityServiceBridge {
    private val serviceRef = AtomicReference<AutoDoraAccessibilityService?>(null)
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

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
}

/**
 * No-Root AccessibilityService executing smooth continuous gesture strokes for Tower of Saviors.
 */
class AutoDoraAccessibilityService : AccessibilityService() {

    private val cancelRequested = AtomicBoolean(false)

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityServiceBridge.attach(this)
        Log.i(TAG, "AutoDora AccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Gesture-only service; no heavy event processing needed
    }

    override fun onInterrupt() {
        cancelRequested.set(true)
    }

    override fun onDestroy() {
        cancelRequested.set(true)
        AccessibilityServiceBridge.detach(this)
        super.onDestroy()
    }

    fun requestEmergencyStop() {
        cancelRequested.set(true)
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
                    deferred.complete(false)
                }
            },
            null
        )
        if (!submitted) {
            deferred.complete(false)
        }
        return deferred.await()
    }

    companion object {
        private const val TAG = "AutoDoraA11y"
    }
}
