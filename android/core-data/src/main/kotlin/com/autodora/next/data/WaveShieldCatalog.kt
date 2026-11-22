package com.autodora.next.data

import com.autodora.next.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Built-in categorized Tower of Saviors Wave Shield Chips (2013–2026 mechanics).
 * Used identically by DashboardScreen (Shield Tab) and OverlayTuningDrawer (In-Game 4-Tab Drawer).
 */
object WaveShieldCatalog {

    fun allChips(): List<WaveShieldChip> = listOf(
        // A. Combo Shields (连击盾)
        WaveShieldChip("min_combo", "≥ 7 Combo", ShieldCategory.COMBO, paramInt = 7),
        WaveShieldChip("min_first_elem_combo", "首消 ≥5 组属性珠", ShieldCategory.COMBO, paramInt = 5),
        WaveShieldChip("exact_combo", "指定 6 Combo", ShieldCategory.COMBO, paramInt = 6),
        WaveShieldChip("exact_first_combo", "首消 6 Combo", ShieldCategory.COMBO, paramInt = 6),
        WaveShieldChip("wave1_same_kind", "首消同种类 5C", ShieldCategory.COMBO, paramInt = 5),
        WaveShieldChip("wave1_distinct_kind", "首消不同种类 5C", ShieldCategory.COMBO, paramInt = 5),
        WaveShieldChip("zero_combo", "0 Combo 空转", ShieldCategory.COMBO),
        WaveShieldChip("odd_combo", "奇数 Combo", ShieldCategory.COMBO),
        WaveShieldChip("even_combo", "偶数 Combo", ShieldCategory.COMBO),
        WaveShieldChip("first_wave_only", "只计首批", ShieldCategory.COMBO),
        WaveShieldChip("no_cascade", "禁天降(仅首批)", ShieldCategory.COMBO),
        WaveShieldChip("same_combo_elements", "同属同 C", ShieldCategory.COMBO),
        WaveShieldChip("second_wave_combo", "次批叠珠 ≥ 1C", ShieldCategory.COMBO, paramInt = 1),
        WaveShieldChip("equal_pair_light_dark", "光暗同等 C", ShieldCategory.COMBO),
        WaveShieldChip("same_elem_combos", "同属 3 组连击", ShieldCategory.COMBO, paramInt = 3),

        // B. Count & Element Shields (数量与属性盾)
        WaveShieldChip("five_elements", "五属盾", ShieldCategory.COUNT_AND_ELEMENT),
        WaveShieldChip("five_elements_enchanted", "五属强化盾", ShieldCategory.COUNT_AND_ELEMENT),
        WaveShieldChip("three_elements", "水火木三属", ShieldCategory.COUNT_AND_ELEMENT),
        WaveShieldChip("first_wave_full_clear", "首消全版", ShieldCategory.COUNT_AND_ELEMENT),
        WaveShieldChip("digital_orb_count", "数字盾 18 颗", ShieldCategory.COUNT_AND_ELEMENT, paramInt = 18),
        WaveShieldChip("race_orb_shield", "种族符石首消", ShieldCategory.COUNT_AND_ELEMENT),
        WaveShieldChip("anti_first_wave_heart", "首消禁心(叠珠)", ShieldCategory.COUNT_AND_ELEMENT, paramElementMask = 1 shl Element.HEART.code),
        WaveShieldChip("forbid_all_heart", "全禁消心珠", ShieldCategory.COUNT_AND_ELEMENT, paramElementMask = 1 shl Element.HEART.code),

        // C. Shape & Puzzle Shields (形状与拼图盾)
        WaveShieldChip("strict_puzzle", "严格拼图盾", ShieldCategory.SHAPE_AND_PUZZLE),
        WaveShieldChip("loose_puzzle", "普通拼图盾", ShieldCategory.SHAPE_AND_PUZZLE),
        WaveShieldChip("enchanted_puzzle", "强化拼图盾", ShieldCategory.SHAPE_AND_PUZZLE),
        WaveShieldChip("cross_shield", "单十字盾", ShieldCategory.SHAPE_AND_PUZZLE, paramInt = 1),
        WaveShieldChip("cross_shield_2", "双十字盾", ShieldCategory.SHAPE_AND_PUZZLE, paramInt = 2),
        WaveShieldChip("l_shape", "L 形盾", ShieldCategory.SHAPE_AND_PUZZLE),
        WaveShieldChip("t_shape", "T 形盾", ShieldCategory.SHAPE_AND_PUZZLE),
        WaveShieldChip("square_2x2", "田字 2×2 盾", ShieldCategory.SHAPE_AND_PUZZLE),
        WaveShieldChip("all_columns_ameliorate", "全直排 ≥ 4 消", ShieldCategory.SHAPE_AND_PUZZLE),

        // D. Hazard, Terrain & Path (障碍、地形与路径)
        WaveShieldChip("traffic_light", "红绿灯(起终点)", ShieldCategory.HAZARD_AND_PATH),
        WaveShieldChip("zero_trail_burning", "轨迹燃烧 0 伤", ShieldCategory.HAZARD_AND_PATH, paramInt = 0),
        WaveShieldChip("trail_corrosion", "轨迹腐蚀自避", ShieldCategory.HAZARD_AND_PATH, paramInt = 15),
        WaveShieldChip("char_orb_terrain", "角色珠通行/停放", ShieldCategory.HAZARD_AND_PATH),
        WaveShieldChip("step_hurt_minimize", "一步一伤省步", ShieldCategory.HAZARD_AND_PATH),
        WaveShieldChip("fast_first_result", "达标即早停", ShieldCategory.HAZARD_AND_PATH),
        WaveShieldChip("weijian_burst", "违建长步爆发", ShieldCategory.HAZARD_AND_PATH)
    )

    /**
     * Normalize custom alias chip IDs (e.g. "cross_shield_2", "anti_first_wave_heart", "elem_shield_0") into
     * canonical RuleComposer chip IDs when building the final RuleConfig.
     */
    fun canonicalizeForComposer(chip: WaveShieldChip): WaveShieldChip = when {
        chip.id.startsWith("cross_shield_") -> chip.copy(id = "cross_shield")
        chip.id.startsWith("require_elem_") -> chip.copy(id = "require_elements")
        chip.id.startsWith("require_ench_") -> chip.copy(id = "require_enchanted_elements")
        chip.id.startsWith("anti_first_wave_") -> chip.copy(id = "anti_first_wave")
        chip.id.startsWith("forbid_all_") -> chip.copy(id = "forbid_all")
        chip.id.startsWith("clear_all_element_") -> chip.copy(id = "clear_all_element")
        else -> chip
    }

    val COMBO_EXCLUSIVE_IDS = setOf(
        "min_combo", "min_first_combo", "min_first_elem_combo",
        "exact_combo", "exact_first_combo", "wave1_same_kind", "wave1_distinct_kind",
        "zero_combo", "odd_combo", "even_combo"
    )
    val PUZZLE_EXCLUSIVE_IDS = setOf("puzzle_shield", "loose_puzzle", "strict_puzzle", "enchanted_puzzle")
    val CROSS_EXCLUSIVE_IDS = setOf("cross_shield", "cross_shield_2", "cross_shield_3")
    val STEP_EXCLUSIVE_IDS = setOf("step_limit", "step_range", "exact_steps")

    /**
     * Preset Puzzle Shield shape masks on a 6x5 (or wider) board centered around row 1..3, col 1..4.
     * Used when the user explicitly selects a Puzzle Shape template (0 = Auto-Detect board golden border).
     */
    fun puzzleShapeTemplateCells(shapeId: Int, cols: Int, rows: Int): Set<Int> {
        if (shapeId <= 0 || cols < 4 || rows < 4) return emptySet()
        val cr = rows / 2
        val cc = cols / 2
        val coords = when (shapeId) {
            1 -> listOf((cr - 1) to (cc - 1), cr to (cc - 1), (cr + 1) to (cc - 1), (cr + 1) to cc, (cr + 1) to (cc + 1)) // L 形 (5格)
            2 -> listOf((cr - 1) to (cc - 1), (cr - 1) to cc, (cr - 1) to (cc + 1), cr to cc, (cr + 1) to cc)             // T 形 (5格)
            3 -> listOf((cr - 1) to cc, cr to (cc - 1), cr to cc, cr to (cc + 1), (cr + 1) to cc)                         // 十字 (5格)
            4 -> listOf(cr to (cc - 1), cr to cc, (cr + 1) to (cc - 1), (cr + 1) to cc)                                   // 田字 2×2 (4格)
            5 -> listOf(cr to (cc - 1), cr to cc, cr to (cc + 1), (cr + 1) to (cc - 1), (cr + 1) to cc, (cr + 1) to (cc + 1)) // 3×2 矩形 (6格)
            6 -> listOf((cr - 1) to (cc - 1), cr to (cc - 1), cr to cc, cr to (cc + 1), (cr - 1) to (cc + 1))             // 凹字 (5格)
            else -> emptyList()
        }
        return coords.filter { (r, c) -> r in 0 until rows && c in 0 until cols }
            .map { (r, c) -> r * cols + c }
            .toSet()
    }
}

/**
 * Process-wide live reactive strategy state shared seamlessly between DashboardScreen and OverlayControllerService.
 * Holds active per-wave WaveShieldChips (from parameterized Shield Controls OR automatic Enemy Skill Recognition).
 */
object ActiveStrategyStore {
    private val _activeChips = MutableStateFlow<List<WaveShieldChip>>(emptyList())
    val activeChips: StateFlow<List<WaveShieldChip>> = _activeChips.asStateFlow()

    fun toggleChip(chip: WaveShieldChip) {
        val current = _activeChips.value.toMutableList()
        val existingIdx = current.indexOfFirst { it.id == chip.id }
        if (existingIdx >= 0) {
            current.removeAt(existingIdx)
        } else {
            current.add(chip)
        }
        _activeChips.value = current
    }

    fun upsertChip(chip: WaveShieldChip) {
        val current = _activeChips.value.toMutableList()
        val existingIdx = current.indexOfFirst { it.id == chip.id }
        if (existingIdx >= 0) {
            current[existingIdx] = chip
        } else {
            current.add(chip)
        }
        _activeChips.value = current
    }

    /**
     * Atomically replaces any chips whose IDs belong to [exclusiveIds] with [replacement] (or removes all if null).
     * Powers clean parameterized Shield Controllers (Combo mode, Puzzle mode, Per-Element matrix, etc.).
     */
    fun replaceGroup(exclusiveIds: Set<String>, replacement: WaveShieldChip?) {
        val current = _activeChips.value.filterNot { it.id in exclusiveIds }.toMutableList()
        if (replacement != null) {
            current.add(replacement)
        }
        _activeChips.value = current
    }

    fun replaceByPrefix(prefixes: List<String>, replacement: WaveShieldChip?) {
        val current = _activeChips.value.filterNot { chip -> prefixes.any { chip.id.startsWith(it) } }.toMutableList()
        if (replacement != null) {
            current.add(replacement)
        }
        _activeChips.value = current
    }

    fun removeChip(id: String) {
        _activeChips.value = _activeChips.value.filterNot { it.id == id }
    }

    fun clearAll() {
        _activeChips.value = emptyList()
    }
}
