package com.autodora.next.touch

import com.autodora.next.model.BoardGeometry
import com.autodora.next.model.CellCoord
import com.autodora.next.model.SolutionPath
import kotlinx.coroutines.flow.StateFlow

enum class HoldReplanTrigger {
    HOLD_IN_PLACE,        // 按住不动触发 (部分风化‧转 / 烟雾)
    NUDGE_ONE_CELL_HOLD   // 按住微移1格触发并继续按住不松手 (问号‧转等需起手移动才翻转显形的关卡)
}

data class SwipeConfig(
    val stepDurationMs: Long = 68L,        // Milliseconds per orthogonal cell move (20..140ms; 24ms=2秒极速, 38ms=3秒极速)
    val diagonalSpeedFactor: Float = 0.85f, // Slightly brisker across 4-cell diagonal intersection
    val startHoldMs: Long = 55L,           // Grab hold at starting orb before moving
    val endHoldMs: Long = 45L,             // Settle hold at destination orb before lifting finger
    val humanMode: Boolean = false,        // Subtle humanized micro-jitter & speed variance
    val maxStepsPerSegment: Int = 14,      // Safe StrokeDescription chaining chunk size
    val invertX: Boolean = false,          // 颠倒操控: 左右镜像划屏
    val invertY: Boolean = false           // 颠倒操控: 上下镜像划屏
) {
    companion object {
        const val TURBO_2S_STEP_MS = 24L
        const val FAST_3S_STEP_MS = 38L
        const val NORMAL_STEP_MS = 68L
        const val SAFE_STEP_MS = 95L
    }
}

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

    /**
     * No-Root Hold-and-Replan (问号‧转 / 风化‧转 / 烟雾):
     * Presses and holds [startCell] (optionally nudging to [nudgeTarget]) via continuous willContinue=true
     * micro-heartbeat strokes without lifting the finger, invokes [onBoardRevealed] after [revealDelayMs],
     * and seamlessly continues swiping the freshly solved path from the held finger!
     */
    suspend fun executeHoldAndReplan(
        startCell: CellCoord,
        geometry: BoardGeometry,
        trigger: HoldReplanTrigger = HoldReplanTrigger.HOLD_IN_PLACE,
        nudgeTarget: CellCoord? = null,
        revealDelayMs: Long = 360L,
        config: SwipeConfig = SwipeConfig(),
        onBoardRevealed: suspend (heldCell: CellCoord) -> SolutionPath?
    ): TouchExecutionResult

    fun emergencyStop()
}
