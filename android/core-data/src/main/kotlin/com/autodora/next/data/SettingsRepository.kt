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

enum class EnemySkillScanMode(val label: String, val shortLabel: String) {
    HYBRID("图标快识 + 自动齿轮展开", "双模自动"),
    AUTO_GEAR_OCR("自动齿轮展开 OCR", "自动齿轮"),
    BATTLE_ICONS("仅战斗图标快识", "仅图标");

    companion object {
        fun fromOrdinalSafe(ord: Int?): EnemySkillScanMode =
            entries.getOrNull(ord ?: 0) ?: HYBRID
    }
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val language: AppLanguage = AppLanguage.SYSTEM,
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
    val showOrbBadgesOnPath: Boolean = true,    // Show compact recognized orb badges so users verify board accuracy at a glance
    val holdReplanEnabled: Boolean = false,     // Toggle mode: when ON, '行' executes Hold-and-Replan (按住重算)
    val holdReplanDelayMs: Long = 320L,
    val holdReplanNudge: Boolean = true,
    val invertDragX: Boolean = false,
    val invertDragY: Boolean = false,
    val autoEnemySkillEnabled: Boolean = false, // Master toggle: when ON, enemy skill recognition runs automatically per wave!
    val enemySkillScanMode: EnemySkillScanMode = EnemySkillScanMode.HYBRID,
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
        val STEP_DURATION_MS = longPreferencesKey("touch_step_ms")
        val HUMAN_MODE = booleanPreferencesKey("touch_human_mode")
        val MULTI_FRAME_COUNT = intPreferencesKey("vision_multi_frames")
        val SHOW_ORB_BADGES = booleanPreferencesKey("ux_show_orb_badges")

        val HOLD_REPLAN_ENABLED = booleanPreferencesKey("touch_hold_replan_enabled")
        val HOLD_REPLAN_DELAY_MS = longPreferencesKey("touch_hold_replan_delay_ms")
        val HOLD_REPLAN_NUDGE = booleanPreferencesKey("touch_hold_replan_nudge")
        val INVERT_DRAG_X = booleanPreferencesKey("touch_invert_drag_x")
        val INVERT_DRAG_Y = booleanPreferencesKey("touch_invert_drag_y")
        val AUTO_ENEMY_SKILL = booleanPreferencesKey("vision_auto_enemy_skill")
        val ENEMY_SKILL_SCAN_MODE = intPreferencesKey("vision_enemy_skill_scan_mode")
        val ACTIVE_PRESET_ID = stringPreferencesKey("strategy_active_preset_id")
    }

    private fun readFromPrefs(p: Preferences): AppSettings = AppSettings(
        themeMode = ThemeMode.fromOrdinalSafe(p[Keys.THEME_MODE]),
        language = AppLanguage.fromOrdinalSafe(p[Keys.APP_LANGUAGE]),
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
        stepDurationMs = p[Keys.STEP_DURATION_MS] ?: 68L,
        humanMode = p[Keys.HUMAN_MODE] ?: false,
        multiFrameCount = p[Keys.MULTI_FRAME_COUNT] ?: 2,
        showOrbBadgesOnPath = p[Keys.SHOW_ORB_BADGES] ?: true,
        holdReplanEnabled = p[Keys.HOLD_REPLAN_ENABLED] ?: false,
        holdReplanDelayMs = p[Keys.HOLD_REPLAN_DELAY_MS] ?: 320L,
        holdReplanNudge = p[Keys.HOLD_REPLAN_NUDGE] ?: true,
        invertDragX = p[Keys.INVERT_DRAG_X] ?: false,
        invertDragY = p[Keys.INVERT_DRAG_Y] ?: false,
        autoEnemySkillEnabled = p[Keys.AUTO_ENEMY_SKILL] ?: false,
        enemySkillScanMode = EnemySkillScanMode.fromOrdinalSafe(p[Keys.ENEMY_SKILL_SCAN_MODE]),
        activePresetId = p[Keys.ACTIVE_PRESET_ID] ?: "builtin_general"
    )

    val settingsFlow: Flow<AppSettings> = context.autoDoraDataStore.data.map { p -> readFromPrefs(p) }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.autoDoraDataStore.edit { p ->
            val current = readFromPrefs(p)
            val next = transform(current)
            p[Keys.THEME_MODE] = next.themeMode.ordinal
            p[Keys.APP_LANGUAGE] = next.language.ordinal
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
            p[Keys.STEP_DURATION_MS] = next.stepDurationMs.coerceIn(20L, 140L)
            p[Keys.HUMAN_MODE] = next.humanMode
            p[Keys.MULTI_FRAME_COUNT] = next.multiFrameCount.coerceIn(1, 3)
            p[Keys.SHOW_ORB_BADGES] = next.showOrbBadgesOnPath
            p[Keys.HOLD_REPLAN_ENABLED] = next.holdReplanEnabled
            p[Keys.HOLD_REPLAN_DELAY_MS] = next.holdReplanDelayMs.coerceIn(150L, 900L)
            p[Keys.HOLD_REPLAN_NUDGE] = next.holdReplanNudge
            p[Keys.INVERT_DRAG_X] = next.invertDragX
            p[Keys.INVERT_DRAG_Y] = next.invertDragY
            p[Keys.AUTO_ENEMY_SKILL] = next.autoEnemySkillEnabled
            p[Keys.ENEMY_SKILL_SCAN_MODE] = next.enemySkillScanMode.ordinal
            p[Keys.ACTIVE_PRESET_ID] = next.activePresetId
        }
    }
}
