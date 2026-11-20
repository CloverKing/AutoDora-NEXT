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
import com.autodora.next.model.RuleConfig
import com.autodora.next.model.StrategyGoalMode
import com.autodora.next.model.TargetShape
import com.autodora.next.model.TeamBasePreset
import com.autodora.next.model.WaveShieldChip
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
        StrategyGoalMode.HIGH_COMBO -> ctx.getString(R.string.goal_group_high_combo)
        StrategyGoalMode.N_COLOR_BALANCE -> ctx.getString(R.string.goal_group_n_balance)
        StrategyGoalMode.N_COLOR_ALL -> ctx.getString(R.string.goal_group_n_all)
        StrategyGoalMode.SINGLE_COLOR_X_ORBS -> ctx.getString(R.string.goal_group_single_x)
        StrategyGoalMode.MULTI_COLOR_X_ORBS -> ctx.getString(R.string.goal_group_multi_x)
        StrategyGoalMode.THREE_COLORS -> ctx.getString(R.string.goal_3_colors)
        StrategyGoalMode.FOUR_COLORS -> ctx.getString(R.string.goal_4_colors)
        StrategyGoalMode.FIVE_COLORS -> ctx.getString(R.string.goal_5_colors)
        StrategyGoalMode.FIVE_ORBS_SINGLE -> ctx.getString(R.string.goal_5_single)
        StrategyGoalMode.FIVE_ORBS_MULTI -> ctx.getString(R.string.goal_5_multi)
        StrategyGoalMode.SIX_ORBS -> ctx.getString(R.string.goal_6_orbs)
        StrategyGoalMode.SEVEN_ORBS -> ctx.getString(R.string.goal_7_orbs)
        StrategyGoalMode.EIGHT_ORBS -> ctx.getString(R.string.goal_8_orbs)
        StrategyGoalMode.NINE_ORBS -> ctx.getString(R.string.goal_9_orbs)
    }

    fun goalSummary(ctx: Context, cfg: RuleConfig): String {
        val g = cfg.goalMode.normalizedGroup()
        val n = if (cfg.goalMode == g) cfg.goalParamN else cfg.goalMode.defaultParamN()
        val x = if (cfg.goalMode == g) cfg.goalParamX else cfg.goalMode.defaultParamX()
        return when (g) {
            StrategyGoalMode.HIGH_COMBO -> ctx.getString(R.string.goal_group_high_combo)
            StrategyGoalMode.N_COLOR_BALANCE -> ctx.getString(R.string.goal_summary_n_balance, n.coerceIn(2, 5))
            StrategyGoalMode.N_COLOR_ALL -> ctx.getString(R.string.goal_summary_n_all, n.coerceIn(2, 6))
            StrategyGoalMode.SINGLE_COLOR_X_ORBS -> {
                val base = ctx.getString(R.string.goal_summary_single_x, x.coerceIn(4, 9))
                if (cfg.goalTargetElement in 0..5) "$base·${elementLabel(ctx, cfg.goalTargetElement)}" else base
            }
            StrategyGoalMode.MULTI_COLOR_X_ORBS -> ctx.getString(R.string.goal_summary_multi_x, n.coerceIn(2, 5), x.coerceIn(4, 9))
            else -> goalModeLabel(ctx, cfg.goalMode)
        }
    }

    /**
     * Dynamically formats any active [WaveShieldChip] into the CURRENT UI language so switching
     * languages or importing auto-detected shields never leaves mismatched-language pill labels.
     */
    fun formatWaveChipLabel(ctx: Context, chip: WaveShieldChip): String {
        val elemNames = (0..5).filter { (chip.paramElementMask and (1 shl it)) != 0 }
            .joinToString("") { elementLabel(ctx, it) }
        return when (chip.id) {
            "min_combo" -> "${ctx.getString(R.string.combo_type_min_total)} ≥${chip.paramInt}"
            "min_first_combo" -> "${ctx.getString(R.string.combo_type_min_first)} ≥${chip.paramInt}"
            "exact_combo" -> "${ctx.getString(R.string.combo_type_exact_total)} ${chip.paramInt}"
            "exact_first_combo" -> "${ctx.getString(R.string.combo_type_exact_first)} ${chip.paramInt}"
            "zero_combo" -> ctx.getString(R.string.combo_type_zero)
            "odd_combo" -> {
                val scope = ctx.getString(if (chip.paramInt == 1) R.string.combo_parity_scope_total else R.string.combo_parity_scope_first)
                "${ctx.getString(R.string.combo_type_odd)}（$scope）"
            }
            "even_combo" -> {
                val scope = ctx.getString(if (chip.paramInt == 1) R.string.combo_parity_scope_total else R.string.combo_parity_scope_first)
                "${ctx.getString(R.string.combo_type_even)}（$scope）"
            }
            "same_combo_elements" -> ctx.getString(R.string.combo_extra_same_c)
            "second_wave_combo" -> ctx.getString(R.string.combo_extra_second_wave, chip.paramInt.coerceAtLeast(1))
            "same_elem_combos" -> {
                val n = chip.paramInt.coerceAtLeast(2)
                val base = ctx.getString(R.string.combo_extra_same_elem, n)
                if (elemNames.isNotEmpty()) "$base·$elemNames" else base
            }
            "equal_pair_light_dark" -> {
                val a = if (chip.paramElementMask != 0) (chip.paramElementMask and 0x0F).coerceIn(0, 5) else 3
                val b = if (chip.paramElementMask != 0) ((chip.paramElementMask ushr 4) and 0x0F).coerceIn(0, 5) else 4
                ctx.getString(R.string.combo_extra_equal_pair, elementLabel(ctx, a), elementLabel(ctx, b))
            }
            "min_distinct_elements" -> ctx.getString(R.string.elem_min_distinct_fmt, chip.paramInt.coerceIn(2, 6))
            "require_elements" -> {
                val reqMask = chip.paramElementMask and 0x3F
                val perColorEnch = (chip.paramElementMask ushr 8) and 0x3F
                val effEnch = if (perColorEnch != 0) perColorEnch else if (chip.paramInt == 1) reqMask else 0
                val formatted = (0..5).filter { (reqMask and (1 shl it)) != 0 }.joinToString("") { idx ->
                    val name = elementLabel(ctx, idx)
                    if ((effEnch and (1 shl idx)) != 0) "$name+" else name
                }
                formatted.ifEmpty { chip.label }
            }
            "elem_burst_shield" -> {
                val targetOrbs = chip.paramInt.coerceIn(3, 20)
                val targetElems = elemNames.ifEmpty { elementLabel(ctx, 1) }
                ctx.getString(R.string.elem_burst_fmt, targetElems, targetOrbs)
            }
            "five_elements" -> (0..4).joinToString("") { elementLabel(ctx, it) }
            "five_elements_enchanted" -> (0..4).joinToString("") { elementLabel(ctx, it) } + "·" + ctx.getString(R.string.ov_puzzle_ench)
            "three_elements" -> (0..2).joinToString("") { elementLabel(ctx, it) }
            "anti_first_wave" -> if (elemNames.isNotEmpty()) "${ctx.getString(R.string.elem_limit_anti)}:$elemNames" else ctx.getString(R.string.elem_limit_anti)
            "forbid_all" -> if (elemNames.isNotEmpty()) "${ctx.getString(R.string.elem_limit_forbid)}:$elemNames" else ctx.getString(R.string.elem_limit_forbid)
            "clear_all_element" -> if (elemNames.isNotEmpty()) "${ctx.getString(R.string.elem_limit_clear_all)}:$elemNames" else ctx.getString(R.string.elem_limit_clear_all)
            "digital_orb_count" -> ctx.getString(R.string.shield_digital_orbs_fmt, chip.paramInt.coerceIn(1, 48))
            "first_wave_full_clear" -> ctx.getString(R.string.shield_full_clear)
            "any_enchanted_orb_shield" -> ctx.getString(R.string.shield_any_enchanted_orb)
            "race_orb_shield" -> ctx.getString(R.string.shield_race_orb)
            "char_orb_first_wave" -> ctx.getString(R.string.shield_char_orb_first)
            "forbid_weakened_orb" -> ctx.getString(R.string.shield_forbid_weakened)
            "luminous_orb_shield" -> ctx.getString(R.string.shield_luminous_orb)
            "puzzle_shield" -> {
                val strict = (chip.paramInt and 1) != 0
                val ench = (chip.paramInt and 2) != 0
                val elemEnc = (chip.paramInt ushr 2) and 0x0F
                val shapeId = (chip.paramInt ushr 8) and 0x0F
                val parts = buildList {
                    add(ctx.getString(if (strict) R.string.puzzle_mode_strict else R.string.puzzle_mode_normal))
                    if (ench) add(ctx.getString(R.string.ov_puzzle_ench))
                    if (elemEnc in 1..6) add(elementLabel(ctx, elemEnc - 1))
                    if (shapeId > 0) add(puzzleShapeLabel(ctx, shapeId))
                }
                parts.joinToString("·")
            }
            "strict_puzzle" -> ctx.getString(R.string.puzzle_mode_strict)
            "loose_puzzle" -> ctx.getString(R.string.puzzle_mode_normal)
            "enchanted_puzzle" -> "${ctx.getString(R.string.puzzle_mode_strict)}·${ctx.getString(R.string.ov_puzzle_ench)}"
            "cross_shield", "cross_shield_2", "cross_shield_3" -> {
                val cnt = (chip.paramInt and 0x0F).coerceIn(1, 3)
                val distinct = (chip.paramInt and 0x20) != 0
                val base = when (cnt) {
                    1 -> ctx.getString(R.string.cross_1)
                    2 -> ctx.getString(R.string.cross_2)
                    else -> ctx.getString(R.string.cross_3)
                }
                when {
                    elemNames.isNotEmpty() -> "$base·$elemNames"
                    distinct -> "$base(≠)"
                    else -> base
                }
            }
            "l_shape" -> if (elemNames.isNotEmpty()) "${ctx.getString(R.string.shield_l_shape)}·$elemNames" else ctx.getString(R.string.shield_l_shape)
            "t_shape" -> if (elemNames.isNotEmpty()) "${ctx.getString(R.string.shield_t_shape)}·$elemNames" else ctx.getString(R.string.shield_t_shape)
            "square_2x2" -> ctx.getString(R.string.shield_sq_2x2)
            "all_columns_ameliorate" -> ctx.getString(R.string.shield_all_cols)
            "zero_trail_burning" -> {
                val tLen = chip.paramInt and 0xFF
                val maxH = (chip.paramInt ushr 8) and 0xFF
                val lenStr = ctx.getString(if (tLen == 6) R.string.ov_burn_len_6 else R.string.ov_burn_len_max)
                "${ctx.getString(R.string.burn_path_title)}·$lenStr·≤$maxH"
            }
            "step_limit" -> ctx.getString(R.string.shield_step_limit_fmt, chip.paramInt.coerceAtLeast(1))
            "trail_corrosion" -> ctx.getString(R.string.shield_trail_corrosion)
            "step_hurt_minimize" -> ctx.getString(R.string.shield_step_hurt)
            "char_orb_terrain" -> ctx.getString(R.string.shield_char_terrain)
            "weijian_burst" -> ctx.getString(R.string.shield_weijian_burst)
            else -> chip.label
        }
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
