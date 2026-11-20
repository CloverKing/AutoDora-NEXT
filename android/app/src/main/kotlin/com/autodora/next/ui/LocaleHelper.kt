package com.autodora.next.ui

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import com.autodora.next.R
import com.autodora.next.data.AppLanguage
import com.autodora.next.data.EnemySkillScanMode
import com.autodora.next.data.ThemeMode
import com.autodora.next.model.ElementPriority
import com.autodora.next.model.MatchTiming
import com.autodora.next.model.StrategyGoalMode
import com.autodora.next.model.TargetShape
import com.autodora.next.model.TeamBasePreset
import com.autodora.next.touch.SwipeSpeedPreset
import java.util.Locale

/**
 * Live zero-restart locale switcher backed by Android XML string resources
 * (`values/strings.xml`, `values-zh-rTW/strings.xml`, `values-en/strings.xml`).
 * Works identically for both Jetpack Compose Activity and OverlayControllerService!
 */
object LocaleHelper {

    fun localizedContext(base: Context, language: AppLanguage): Context {
        val locale = when (language) {
            AppLanguage.SYSTEM -> return base
            AppLanguage.ZH_CN -> Locale.SIMPLIFIED_CHINESE
            AppLanguage.ZH_TW -> Locale.TRADITIONAL_CHINESE
            AppLanguage.EN -> Locale.ENGLISH
        }
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocales(LocaleList(locale))
        return base.createConfigurationContext(cfg)
    }

    fun elementLabel(ctx: Context, elemIndex: Int): String = when (elemIndex) {
        0 -> ctx.getString(R.string.elem_water)
        1 -> ctx.getString(R.string.elem_fire)
        2 -> ctx.getString(R.string.elem_wood)
        3 -> ctx.getString(R.string.elem_light)
        4 -> ctx.getString(R.string.elem_dark)
        5 -> ctx.getString(R.string.elem_heart)
        else -> "?"
    }

    fun themeLabel(ctx: Context, mode: ThemeMode): String = when (mode) {
        ThemeMode.SYSTEM -> ctx.getString(R.string.theme_system)
        ThemeMode.LIGHT -> ctx.getString(R.string.theme_light)
        ThemeMode.DARK -> ctx.getString(R.string.theme_dark)
    }

    fun languageLabel(ctx: Context, lang: AppLanguage): String = when (lang) {
        AppLanguage.SYSTEM -> ctx.getString(R.string.lang_system)
        AppLanguage.ZH_CN -> ctx.getString(R.string.lang_zh_cn)
        AppLanguage.ZH_TW -> ctx.getString(R.string.lang_zh_tw)
        AppLanguage.EN -> ctx.getString(R.string.lang_en)
    }

    fun speedLabel(ctx: Context, preset: SwipeSpeedPreset): String = when (preset) {
        SwipeSpeedPreset.TURBO_2S -> ctx.getString(R.string.speed_turbo)
        SwipeSpeedPreset.FAST_3S -> ctx.getString(R.string.speed_fast)
        SwipeSpeedPreset.STANDARD -> ctx.getString(R.string.speed_standard)
        SwipeSpeedPreset.STEADY -> ctx.getString(R.string.speed_steady)
    }

    fun scanModeLabel(ctx: Context, mode: EnemySkillScanMode): String = when (mode) {
        EnemySkillScanMode.HYBRID -> ctx.getString(R.string.scan_mode_hybrid)
        EnemySkillScanMode.AUTO_GEAR_OCR -> ctx.getString(R.string.scan_mode_gear)
        EnemySkillScanMode.BATTLE_ICONS -> ctx.getString(R.string.scan_mode_icons)
    }

    fun scanModeDesc(ctx: Context, mode: EnemySkillScanMode): String = when (mode) {
        EnemySkillScanMode.HYBRID -> ctx.getString(R.string.scan_mode_hybrid_desc)
        EnemySkillScanMode.AUTO_GEAR_OCR -> ctx.getString(R.string.scan_mode_gear_desc)
        EnemySkillScanMode.BATTLE_ICONS -> ctx.getString(R.string.scan_mode_icons_desc)
    }

    fun goalModeLabel(ctx: Context, mode: StrategyGoalMode): String = when (mode) {
        StrategyGoalMode.HIGH_COMBO -> ctx.getString(R.string.goal_high_combo)
        StrategyGoalMode.THREE_COLORS -> ctx.getString(R.string.goal_3_colors)
        StrategyGoalMode.FOUR_COLORS -> ctx.getString(R.string.goal_4_colors)
        StrategyGoalMode.FIVE_COLORS -> ctx.getString(R.string.goal_5_colors)
        StrategyGoalMode.FIVE_ORBS_SINGLE -> ctx.getString(R.string.goal_5_single)
        StrategyGoalMode.FIVE_ORBS_MULTI -> ctx.getString(R.string.goal_5_multi)
        StrategyGoalMode.SIX_ORBS -> ctx.getString(R.string.goal_6_orbs)
        StrategyGoalMode.EIGHT_ORBS -> ctx.getString(R.string.goal_8_orbs)
    }

    /**
     * Resolves localized name & description for built-in strategies (so even previously seeded Room DB rows
     * immediately update from old "队" names to "策略" AND translate cleanly across 简体 / 繁體 / English!).
     */
    fun displayPresetName(ctx: Context, preset: TeamBasePreset, isBuiltIn: Boolean): String {
        if (!isBuiltIn) return preset.name
        return when (preset.id) {
            "builtin_general" -> ctx.getString(R.string.preset_general_name)
            "builtin_two_match" -> ctx.getString(R.string.preset_two_match_name)
            "builtin_column_ameliorate" -> ctx.getString(R.string.preset_col_name)
            "builtin_char_orb" -> ctx.getString(R.string.preset_char_name)
            "builtin_weijian_burst" -> ctx.getString(R.string.preset_weijian_name)
            else -> preset.name
        }
    }

    fun displayPresetDesc(ctx: Context, preset: TeamBasePreset, isBuiltIn: Boolean): String {
        if (!isBuiltIn) return preset.description
        return when (preset.id) {
            "builtin_general" -> ctx.getString(R.string.preset_general_desc)
            "builtin_two_match" -> ctx.getString(R.string.preset_two_match_desc)
            "builtin_column_ameliorate" -> ctx.getString(R.string.preset_col_desc)
            "builtin_char_orb" -> ctx.getString(R.string.preset_char_desc)
            "builtin_weijian_burst" -> ctx.getString(R.string.preset_weijian_desc)
            else -> preset.description
        }
    }

    fun targetShapeLabel(ctx: Context, shape: TargetShape): String = when (shape) {
        TargetShape.ANY -> ctx.getString(R.string.shape_default)
        TargetShape.COUNT_2 -> ctx.getString(R.string.shape_2)
        TargetShape.COUNT_4 -> ctx.getString(R.string.shape_4)
        TargetShape.COUNT_5 -> ctx.getString(R.string.shape_5)
        TargetShape.ROW -> ctx.getString(R.string.shape_row)
        TargetShape.COUNT_6 -> ctx.getString(R.string.shape_6)
        TargetShape.COUNT_7 -> ctx.getString(R.string.shape_7)
        TargetShape.COUNT_8 -> ctx.getString(R.string.shape_8)
        TargetShape.COUNT_9 -> ctx.getString(R.string.shape_9)
        TargetShape.CLEAR_ALL -> ctx.getString(R.string.shape_all)
        else -> ctx.getString(R.string.shape_default)
    }

    fun elementPriorityLabel(ctx: Context, prio: ElementPriority): String = when (prio) {
        ElementPriority.NORMAL -> ctx.getString(R.string.prio_normal)
        ElementPriority.HIGH -> ctx.getString(R.string.prio_high)
        ElementPriority.RESERVE -> ctx.getString(R.string.prio_reserve)
    }

    fun matchTimingLabel(ctx: Context, timing: MatchTiming): String = when (timing) {
        MatchTiming.ANY -> ctx.getString(R.string.timing_any)
        MatchTiming.MUST_FIRST_WAVE -> ctx.getString(R.string.timing_first)
        MatchTiming.FORBID_FIRST_WAVE_ALLOW_CASCADE -> ctx.getString(R.string.timing_stack)
        MatchTiming.FORBID_ALL -> ctx.getString(R.string.timing_forbid)
    }

    fun puzzleShapeLabel(ctx: Context, shapeId: Int): String = when (shapeId) {
        1 -> ctx.getString(R.string.puzzle_shape_l)
        2 -> ctx.getString(R.string.puzzle_shape_t)
        3 -> ctx.getString(R.string.puzzle_shape_cross)
        4 -> ctx.getString(R.string.puzzle_shape_sq)
        5 -> ctx.getString(R.string.puzzle_shape_rect)
        6 -> ctx.getString(R.string.puzzle_shape_u)
        else -> ctx.getString(R.string.puzzle_shape_auto)
    }

    fun elementBadgeArgb(elemIndex: Int): Int = when (elemIndex) {
        0 -> 0xFF1E88E5.toInt() // Water
        1 -> 0xFFE53935.toInt() // Fire
        2 -> 0xFF43A047.toInt() // Wood
        3 -> 0xFFF9A825.toInt() // Light
        4 -> 0xFF8E24AA.toInt() // Dark
        5 -> 0xFFEC407A.toInt() // Heart
        else -> 0xFF607D8B.toInt()
    }
}
