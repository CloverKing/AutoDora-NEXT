package com.autodora.next.touch

import com.autodora.next.model.BoardGeometry
import com.autodora.next.model.SolutionPath
import kotlinx.coroutines.flow.StateFlow

data class SwipeConfig(
    val stepDurationMs: Long = 68L,        // Milliseconds per orthogonal cell move (35..140ms)
    val diagonalSpeedFactor: Float = 0.85f, // Slightly brisker across 4-cell diagonal intersection
    val startHoldMs: Long = 55L,           // Grab hold at starting orb before moving
    val endHoldMs: Long = 45L,             // Settle hold at destination orb before lifting finger
    val humanMode: Boolean = false,        // Subtle humanized micro-jitter & speed variance
    val maxStepsPerSegment: Int = 14       // Safe StrokeDescription chaining chunk size
)

sealed class TouchExecutionResult {
    data class Success(val segmentsDispatched: Int, val elapsedMs: Long) : TouchExecutionResult()
    data object Cancelled : TouchExecutionResult()
    data class Failed(val reason: String) : TouchExecutionResult()
}

/**
 * Abstract no-root touch execution engine:
 *  - Default: AccessibilityTouchExecutor (Android AccessibilityService dispatchGesture + willContinue)
 *  - Future:  ShizukuTouchExecutor (wireless ADB privileged input injection)
 */
interface TouchExecutor {
    val engineName: String
    val isAvailable: StateFlow<Boolean>

    suspend fun executePath(
        solution: SolutionPath,
        geometry: BoardGeometry,
        config: SwipeConfig = SwipeConfig()
    ): TouchExecutionResult

    fun emergencyStop()
}
