package com.autodora.next.touch

import com.autodora.next.model.BoardGeometry
import com.autodora.next.model.SolutionPath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AccessibilityTouchExecutor : TouchExecutor {
    override val engineName: String = "AccessibilityService (No-Root)"
    override val isAvailable: StateFlow<Boolean> = AccessibilityServiceBridge.isConnected

    override suspend fun executePath(
        solution: SolutionPath,
        geometry: BoardGeometry,
        config: SwipeConfig
    ): TouchExecutionResult {
        val service = AccessibilityServiceBridge.current()
            ?: return TouchExecutionResult.Failed("无障碍服务未连接，请在系统设置中启用 AutoDora-NEXT 无障碍服务")
        val segments = PathInterpolator.buildSegments(solution, geometry, config)
        return service.dispatchChainedSegments(segments)
    }

    override fun emergencyStop() {
        AccessibilityServiceBridge.current()?.requestEmergencyStop()
    }
}

/**
 * Reserved Phase 4 Shizuku touch executor slot (implements identical TouchExecutor contract).
 */
class ShizukuTouchExecutor : TouchExecutor {
    override val engineName: String = "Shizuku (Phase 4 Reserved)"
    override val isAvailable: StateFlow<Boolean> = MutableStateFlow(false)

    override suspend fun executePath(
        solution: SolutionPath,
        geometry: BoardGeometry,
        config: SwipeConfig
    ): TouchExecutionResult {
        return TouchExecutionResult.Failed("Shizuku 触控引擎将在 Phase 4 接入，请先使用无障碍引擎")
    }

    override fun emergencyStop() = Unit
}
