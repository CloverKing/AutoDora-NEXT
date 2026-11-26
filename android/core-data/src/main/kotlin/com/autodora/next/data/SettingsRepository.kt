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

enum class ThemeMode(val label: String) {
    SYSTEM("随系统"),
    LIGHT("明亮"),
    DARK("黑暗");

    companion object {
        fun fromOrdinalSafe(ord: Int?): ThemeMode =
            entries.getOrNull(ord ?: 0) ?: SYSTEM
    }
}

enum class AppLanguage(val tag: String, val nativeLabel: String) {
    SYSTEM("", "跟随系统"),
    ZH_CN("zh-CN", "简体中文"),
    ZH_TW("zh-TW", "繁體中文"),
    EN("en", "English");

    companion object {
        fun fromOrdinalSafe(ord: Int?): AppLanguage =
            entries.getOrNull(ord ?: 0) ?: SYSTEM
    }
}

enum class CaptureMode {
    /** Android 11+ AccessibilityService.takeScreenshot(): coexists cleanly with Xiaomi/HyperOS system screen recording. */
    ACCESSIBILITY,
    /** Classic MediaProjection VirtualDisplay: faster burst polling, but occupies Android's exclusive screen-recording slot. */
    MEDIA_PROJECTION;

    companion object {
        fun fromOrdinalSafe(ord: Int?): CaptureMode =
            entries.getOrNull(ord ?: 0) ?: ACCESSIBILITY
    }
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val captureMode: CaptureMode = CaptureMode.ACCESSIBILITY,
    val floatingBarVertical: Boolean = false,
    val floatingBarDockRight: Boolean = false,
    val floatingBarPosXRatio: Float = -1f,       // <0 = auto edge snap; 0..1 = remembered left (or right if dockRight) ratio
    val floatingBarPosYRatio: Float = -1f,       // <0 = default top margin; 0..1 = remembered top Y ratio
    val calibration: BoardCalibration = BoardCalibration(),
    val maxSteps: Int = 28,
    val beamWidth: Int = 180,
    val allowDiagonalMove: Boolean = true,
    val testModeOnlyDraw: Boolean = true,       // Default safe Test Mode: draw preview first, tap Execute to swipe!
    val countdownSeconds: Int = 0,              // Optional 0..3s countdown before swipe
    val showEmergencyStop: Boolean = true,
    val showFloatingRecordButton: Boolean = true,
    val stepDurationMs: Long = 68L,
    val humanMode: Boolean = false,
    val multiFrameCount: Int = 2,
    val useAiRecognizer: Boolean = true,        // Phase 4 LiteRT + CV Hybrid recognition (falls back to pure CV if off)
    val autoEnemySkillOcrEnabled: Boolean = true, // Pure-offline PP-OCRv5 ONNX passive enemy skill popup recognition
    val trainingDataCollectionEnabled: Boolean = true, // Unlimited dual-track on-device capture (boards + enemy popups)
    val showOrbBadgesOnPath: Boolean = true,    // Show compact recognized orb badges so users verify board accuracy at a glance
    val holdReplanEnabled: Boolean = false,     // Toggle mode: when ON, '行' executes Hold-and-Replan (按住重算)
    val holdReplanDelayMs: Long = 560L,         // Max wait ceiling for '?' flip reveal animation (exits early once revealed!)
    val holdReplanSolveTimeMs: Long = 400L,     // Post-reveal C++20 solve time budget (150..3500ms, default 400ms / max 3.5s)
    val holdReplanNudge: Boolean = true,
    val invertDragX: Boolean = false,
    val invertDragY: Boolean = false,
    val activePresetId: String = "builtin_general"
) {
    fun toRuleConfig(): RuleConfig {
        val scaledTimeMs = (550 + (maxSteps - 40).coerceAtLeast(0) * 8).coerceIn(550, 2200)
        return RuleConfig(
            allowDiagonalMove = allowDiagonalMove,
            maxSteps = maxSteps.coerceIn(8, 300),
            beamWidth = beamWidth,
            maxTimeMs = scaledTimeMs,
            topK = 5
        )
    }

    fun toSwipeConfig(): SwipeConfig = SwipeConfig(
        stepDurationMs = stepDurationMs,
        humanMode = humanMode,
        invertX = invertDragX,
        invertY = invertDragY
    )
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val THEME_MODE = intPreferencesKey("ui_theme_mode")
        val APP_LANGUAGE = intPreferencesKey("ui_app_language")
        val CAPTURE_MODE = intPreferencesKey("capture_mode")
        val FLOATING_BAR_VERTICAL = booleanPreferencesKey("ux_floating_bar_vertical")
        val FLOATING_BAR_DOCK_RIGHT = booleanPreferencesKey("ux_floating_bar_dock_right")
        val FLOATING_BAR_POS_X_RATIO = floatPreferencesKey("ux_floating_bar_pos_x_ratio")
        val FLOATING_BAR_POS_Y_RATIO = floatPreferencesKey("ux_floating_bar_pos_y_ratio")
        val LEFT_RATIO = floatPreferencesKey("calib_left_ratio")
        val RIGHT_RATIO = floatPreferencesKey("calib_right_ratio")
        val BOTTOM_RATIO = floatPreferencesKey("calib_bottom_ratio_v2")
        val HEIGHT_SCALE = floatPreferencesKey("calib_height_scale")
        val AUTO_WEIJIAN = booleanPreferencesKey("calib_auto_weijian")
        val AUTO_ARBITRARY = booleanPreferencesKey("calib_auto_arbitrary")

        val MAX_STEPS = intPreferencesKey("solver_max_steps")
        val BEAM_WIDTH = intPreferencesKey("solver_beam_width")
        val ALLOW_DIAGONAL = booleanPreferencesKey("solver_allow_diagonal")

        val TEST_MODE_ONLY_DRAW = booleanPreferencesKey("ux_test_mode_draw")
        val COUNTDOWN_SECONDS = intPreferencesKey("ux_countdown_sec")
        val SHOW_EMERGENCY_STOP = booleanPreferencesKey("ux_show_emergency")
        val SHOW_FLOATING_RECORD_BUTTON = booleanPreferencesKey("ux_show_floating_record_btn")
        val STEP_DURATION_MS = longPreferencesKey("touch_step_ms")
        val HUMAN_MODE = booleanPreferencesKey("touch_human_mode")
        val MULTI_FRAME_COUNT = intPreferencesKey("vision_multi_frames")
        val USE_AI_RECOGNIZER = booleanPreferencesKey("vision_use_ai_recognizer")
        val AUTO_ENEMY_SKILL_OCR_ENABLED = booleanPreferencesKey("vision_auto_enemy_skill_ocr")
        val TRAINING_DATA_COLLECTION_ENABLED = booleanPreferencesKey("ml_training_data_collection_enabled")
        val SHOW_ORB_BADGES = booleanPreferencesKey("ux_show_orb_badges")

        val HOLD_REPLAN_ENABLED = booleanPreferencesKey("touch_hold_replan_enabled")
        val HOLD_REPLAN_DELAY_MS = longPreferencesKey("touch_hold_replan_delay_ms")
        val HOLD_REPLAN_SOLVE_TIME_MS = longPreferencesKey("touch_hold_replan_solve_ms")
        val HOLD_REPLAN_NUDGE = booleanPreferencesKey("touch_hold_replan_nudge")
        val INVERT_DRAG_X = booleanPreferencesKey("touch_invert_drag_x")
        val INVERT_DRAG_Y = booleanPreferencesKey("touch_invert_drag_y")
        val ACTIVE_PRESET_ID = stringPreferencesKey("strategy_active_preset_id")
    }

    private fun readFromPrefs(p: Preferences): AppSettings = AppSettings(
        themeMode = ThemeMode.fromOrdinalSafe(p[Keys.THEME_MODE]),
        language = AppLanguage.fromOrdinalSafe(p[Keys.APP_LANGUAGE]),
        captureMode = CaptureMode.fromOrdinalSafe(p[Keys.CAPTURE_MODE]),
        floatingBarVertical = p[Keys.FLOATING_BAR_VERTICAL] ?: false,
        floatingBarDockRight = p[Keys.FLOATING_BAR_DOCK_RIGHT] ?: false,
        floatingBarPosXRatio = p[Keys.FLOATING_BAR_POS_X_RATIO] ?: -1f,
        floatingBarPosYRatio = p[Keys.FLOATING_BAR_POS_Y_RATIO] ?: -1f,
        calibration = BoardCalibration(
            leftRatio = p[Keys.LEFT_RATIO] ?: 0.0f,
            rightRatio = p[Keys.RIGHT_RATIO] ?: 1.0f,
            bottomRatio = p[Keys.BOTTOM_RATIO] ?: BoardCalibration.AUTO_BOTTOM_RATIO,
            heightScale = p[Keys.HEIGHT_SCALE] ?: 1.0f,
            autoDetectWeijian = p[Keys.AUTO_WEIJIAN] ?: true,
            autoDetectArbitraryGrid = p[Keys.AUTO_ARBITRARY] ?: false
        ),
        maxSteps = (p[Keys.MAX_STEPS] ?: 28).coerceIn(8, 300),
        beamWidth = p[Keys.BEAM_WIDTH] ?: 180,
        allowDiagonalMove = p[Keys.ALLOW_DIAGONAL] ?: true,
        testModeOnlyDraw = p[Keys.TEST_MODE_ONLY_DRAW] ?: true,
        countdownSeconds = p[Keys.COUNTDOWN_SECONDS] ?: 0,
        showEmergencyStop = p[Keys.SHOW_EMERGENCY_STOP] ?: true,
        showFloatingRecordButton = p[Keys.SHOW_FLOATING_RECORD_BUTTON] ?: true,
        stepDurationMs = p[Keys.STEP_DURATION_MS] ?: 68L,
        humanMode = p[Keys.HUMAN_MODE] ?: false,
        multiFrameCount = p[Keys.MULTI_FRAME_COUNT] ?: 2,
        useAiRecognizer = p[Keys.USE_AI_RECOGNIZER] ?: true,
        autoEnemySkillOcrEnabled = p[Keys.AUTO_ENEMY_SKILL_OCR_ENABLED] ?: true,
        trainingDataCollectionEnabled = p[Keys.TRAINING_DATA_COLLECTION_ENABLED] ?: true,
        showOrbBadgesOnPath = p[Keys.SHOW_ORB_BADGES] ?: true,
        holdReplanEnabled = p[Keys.HOLD_REPLAN_ENABLED] ?: false,
        holdReplanDelayMs = (p[Keys.HOLD_REPLAN_DELAY_MS] ?: 560L).coerceIn(240L, 900L),
        holdReplanSolveTimeMs = (p[Keys.HOLD_REPLAN_SOLVE_TIME_MS] ?: 400L).coerceIn(150L, 3500L),
        holdReplanNudge = p[Keys.HOLD_REPLAN_NUDGE] ?: true,
        invertDragX = p[Keys.INVERT_DRAG_X] ?: false,
        invertDragY = p[Keys.INVERT_DRAG_Y] ?: false,
        activePresetId = p[Keys.ACTIVE_PRESET_ID] ?: "builtin_general"
    )

    val settingsFlow: Flow<AppSettings> = context.autoDoraDataStore.data.map { p -> readFromPrefs(p) }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.autoDoraDataStore.edit { p ->
            val current = readFromPrefs(p)
            val next = transform(current)
            p[Keys.THEME_MODE] = next.themeMode.ordinal
            p[Keys.APP_LANGUAGE] = next.language.ordinal
            p[Keys.CAPTURE_MODE] = next.captureMode.ordinal
            p[Keys.FLOATING_BAR_VERTICAL] = next.floatingBarVertical
            p[Keys.FLOATING_BAR_DOCK_RIGHT] = next.floatingBarDockRight
            p[Keys.FLOATING_BAR_POS_X_RATIO] = if (next.floatingBarPosXRatio < 0f) -1f else next.floatingBarPosXRatio.coerceIn(0f, 1f)
            p[Keys.FLOATING_BAR_POS_Y_RATIO] = if (next.floatingBarPosYRatio < 0f) -1f else next.floatingBarPosYRatio.coerceIn(0f, 0.96f)
            p[Keys.LEFT_RATIO] = next.calibration.leftRatio.coerceIn(0f, 0.35f)
            p[Keys.RIGHT_RATIO] = next.calibration.rightRatio.coerceIn(0.65f, 1f)
            p[Keys.BOTTOM_RATIO] = if (next.calibration.bottomRatio < 0f) {
                BoardCalibration.AUTO_BOTTOM_RATIO
            } else {
                next.calibration.bottomRatio.coerceIn(0.45f, 1f)
            }
            p[Keys.HEIGHT_SCALE] = next.calibration.heightScale.coerceIn(0.75f, 1.25f)
            p[Keys.AUTO_WEIJIAN] = next.calibration.autoDetectWeijian
            p[Keys.AUTO_ARBITRARY] = next.calibration.autoDetectArbitraryGrid
            p[Keys.MAX_STEPS] = next.maxSteps.coerceIn(8, 300)
            p[Keys.BEAM_WIDTH] = next.beamWidth.coerceIn(32, 800)
            p[Keys.ALLOW_DIAGONAL] = next.allowDiagonalMove
            p[Keys.TEST_MODE_ONLY_DRAW] = next.testModeOnlyDraw
            p[Keys.COUNTDOWN_SECONDS] = next.countdownSeconds.coerceIn(0, 3)
            p[Keys.SHOW_EMERGENCY_STOP] = next.showEmergencyStop
            p[Keys.SHOW_FLOATING_RECORD_BUTTON] = next.showFloatingRecordButton
            p[Keys.STEP_DURATION_MS] = next.stepDurationMs.coerceIn(20L, 140L)
            p[Keys.HUMAN_MODE] = next.humanMode
            p[Keys.MULTI_FRAME_COUNT] = next.multiFrameCount.coerceIn(1, 3)
            p[Keys.USE_AI_RECOGNIZER] = next.useAiRecognizer
            p[Keys.AUTO_ENEMY_SKILL_OCR_ENABLED] = next.autoEnemySkillOcrEnabled
            p[Keys.TRAINING_DATA_COLLECTION_ENABLED] = next.trainingDataCollectionEnabled
            p[Keys.SHOW_ORB_BADGES] = next.showOrbBadgesOnPath
            p[Keys.HOLD_REPLAN_ENABLED] = next.holdReplanEnabled
            p[Keys.HOLD_REPLAN_DELAY_MS] = next.holdReplanDelayMs.coerceIn(240L, 900L)
            p[Keys.HOLD_REPLAN_SOLVE_TIME_MS] = next.holdReplanSolveTimeMs.coerceIn(150L, 3500L)
            p[Keys.HOLD_REPLAN_NUDGE] = next.holdReplanNudge
            p[Keys.INVERT_DRAG_X] = next.invertDragX
            p[Keys.INVERT_DRAG_Y] = next.invertDragY
            p[Keys.ACTIVE_PRESET_ID] = next.activePresetId
        }
    }
}
