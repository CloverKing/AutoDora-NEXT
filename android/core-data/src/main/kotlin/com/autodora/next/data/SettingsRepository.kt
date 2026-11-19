package com.autodora.next.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.autodora.next.capture.BoardCalibration
import com.autodora.next.model.RuleConfig
import com.autodora.next.touch.SwipeConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.autoDoraDataStore: DataStore<Preferences> by preferencesDataStore(name = "autodora_prefs")

data class AppSettings(
    val calibration: BoardCalibration = BoardCalibration(),
    val maxSteps: Int = 28,
    val beamWidth: Int = 180,
    val allowDiagonalMove: Boolean = true,
    val testModeOnlyDraw: Boolean = true,       // Default safe Test Mode: draw preview first, tap Execute to swipe!
    val countdownSeconds: Int = 0,              // Optional 0..3s countdown before swipe
    val showEmergencyStop: Boolean = true,
    val stepDurationMs: Long = 68L,
    val humanMode: Boolean = false,
    val multiFrameCount: Int = 2,
    val showOrbBadgesOnPath: Boolean = true
) {
    fun toRuleConfig(): RuleConfig = RuleConfig(
        allowDiagonalMove = allowDiagonalMove,
        maxSteps = maxSteps,
        beamWidth = beamWidth,
        maxTimeMs = 550,
        topK = 5
    )

    fun toSwipeConfig(): SwipeConfig = SwipeConfig(
        stepDurationMs = stepDurationMs,
        humanMode = humanMode
    )
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val LEFT_RATIO = floatPreferencesKey("calib_left_ratio")
        val RIGHT_RATIO = floatPreferencesKey("calib_right_ratio")
        val BOTTOM_RATIO = floatPreferencesKey("calib_bottom_ratio")
        val HEIGHT_SCALE = floatPreferencesKey("calib_height_scale")
        val AUTO_WEIJIAN = booleanPreferencesKey("calib_auto_weijian")
        val AUTO_ARBITRARY = booleanPreferencesKey("calib_auto_arbitrary")

        val MAX_STEPS = intPreferencesKey("solver_max_steps")
        val BEAM_WIDTH = intPreferencesKey("solver_beam_width")
        val ALLOW_DIAGONAL = booleanPreferencesKey("solver_allow_diagonal")

        val TEST_MODE_ONLY_DRAW = booleanPreferencesKey("ux_test_mode_draw")
        val COUNTDOWN_SECONDS = intPreferencesKey("ux_countdown_sec")
        val SHOW_EMERGENCY_STOP = booleanPreferencesKey("ux_show_emergency")
        val STEP_DURATION_MS = longPreferencesKey("touch_step_ms")
        val HUMAN_MODE = booleanPreferencesKey("touch_human_mode")
        val MULTI_FRAME_COUNT = intPreferencesKey("vision_multi_frames")
        val SHOW_ORB_BADGES = booleanPreferencesKey("ux_show_orb_badges")
    }

    val settingsFlow: Flow<AppSettings> = context.autoDoraDataStore.data.map { p ->
        AppSettings(
            calibration = BoardCalibration(
                leftRatio = p[Keys.LEFT_RATIO] ?: 0.008f,
                rightRatio = p[Keys.RIGHT_RATIO] ?: 0.992f,
                bottomRatio = p[Keys.BOTTOM_RATIO] ?: 0.968f,
                heightScale = p[Keys.HEIGHT_SCALE] ?: 1.0f,
                autoDetectWeijian = p[Keys.AUTO_WEIJIAN] ?: true,
                autoDetectArbitraryGrid = p[Keys.AUTO_ARBITRARY] ?: false
            ),
            maxSteps = p[Keys.MAX_STEPS] ?: 28,
            beamWidth = p[Keys.BEAM_WIDTH] ?: 180,
            allowDiagonalMove = p[Keys.ALLOW_DIAGONAL] ?: true,
            testModeOnlyDraw = p[Keys.TEST_MODE_ONLY_DRAW] ?: true,
            countdownSeconds = p[Keys.COUNTDOWN_SECONDS] ?: 0,
            showEmergencyStop = p[Keys.SHOW_EMERGENCY_STOP] ?: true,
            stepDurationMs = p[Keys.STEP_DURATION_MS] ?: 68L,
            humanMode = p[Keys.HUMAN_MODE] ?: false,
            multiFrameCount = p[Keys.MULTI_FRAME_COUNT] ?: 2,
            showOrbBadgesOnPath = p[Keys.SHOW_ORB_BADGES] ?: true
        )
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.autoDoraDataStore.edit { p ->
            val current = AppSettings(
                calibration = BoardCalibration(
                    leftRatio = p[Keys.LEFT_RATIO] ?: 0.008f,
                    rightRatio = p[Keys.RIGHT_RATIO] ?: 0.992f,
                    bottomRatio = p[Keys.BOTTOM_RATIO] ?: 0.968f,
                    heightScale = p[Keys.HEIGHT_SCALE] ?: 1.0f,
                    autoDetectWeijian = p[Keys.AUTO_WEIJIAN] ?: true,
                    autoDetectArbitraryGrid = p[Keys.AUTO_ARBITRARY] ?: false
                ),
                maxSteps = p[Keys.MAX_STEPS] ?: 28,
                beamWidth = p[Keys.BEAM_WIDTH] ?: 180,
                allowDiagonalMove = p[Keys.ALLOW_DIAGONAL] ?: true,
                testModeOnlyDraw = p[Keys.TEST_MODE_ONLY_DRAW] ?: true,
                countdownSeconds = p[Keys.COUNTDOWN_SECONDS] ?: 0,
                showEmergencyStop = p[Keys.SHOW_EMERGENCY_STOP] ?: true,
                stepDurationMs = p[Keys.STEP_DURATION_MS] ?: 68L,
                humanMode = p[Keys.HUMAN_MODE] ?: false,
                multiFrameCount = p[Keys.MULTI_FRAME_COUNT] ?: 2,
                showOrbBadgesOnPath = p[Keys.SHOW_ORB_BADGES] ?: true
            )
            val next = transform(current)
            p[Keys.LEFT_RATIO] = next.calibration.leftRatio.coerceIn(0f, 0.35f)
            p[Keys.RIGHT_RATIO] = next.calibration.rightRatio.coerceIn(0.65f, 1f)
            p[Keys.BOTTOM_RATIO] = next.calibration.bottomRatio.coerceIn(0.45f, 1f)
            p[Keys.HEIGHT_SCALE] = next.calibration.heightScale.coerceIn(0.75f, 1.25f)
            p[Keys.AUTO_WEIJIAN] = next.calibration.autoDetectWeijian
            p[Keys.AUTO_ARBITRARY] = next.calibration.autoDetectArbitraryGrid
            p[Keys.MAX_STEPS] = next.maxSteps.coerceIn(8, 100)
            p[Keys.BEAM_WIDTH] = next.beamWidth.coerceIn(32, 800)
            p[Keys.ALLOW_DIAGONAL] = next.allowDiagonalMove
            p[Keys.TEST_MODE_ONLY_DRAW] = next.testModeOnlyDraw
            p[Keys.COUNTDOWN_SECONDS] = next.countdownSeconds.coerceIn(0, 3)
            p[Keys.SHOW_EMERGENCY_STOP] = next.showEmergencyStop
            p[Keys.STEP_DURATION_MS] = next.stepDurationMs.coerceIn(35L, 140L)
            p[Keys.HUMAN_MODE] = next.humanMode
            p[Keys.MULTI_FRAME_COUNT] = next.multiFrameCount.coerceIn(1, 3)
            p[Keys.SHOW_ORB_BADGES] = next.showOrbBadgesOnPath
        }
    }
}
