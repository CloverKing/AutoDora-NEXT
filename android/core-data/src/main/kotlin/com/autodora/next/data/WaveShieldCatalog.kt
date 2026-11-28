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
        WaveShieldChip("max_combo", "≤ 6 Combo", ShieldCategory.COMBO, paramInt = 6),
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
        WaveShieldChip("keep_element_at_least_one", "留底心珠≥1(加护)", ShieldCategory.COUNT_AND_ELEMENT, paramElementMask = 1 shl Element.HEART.code),

        // C. Shape & Puzzle Shields (形状与拼图盾)
        WaveShieldChip("strict_puzzle", "严格拼图盾", ShieldCategory.SHAPE_AND_PUZZLE),
        WaveShieldChip("loose_puzzle", "普通拼图盾", ShieldCategory.SHAPE_AND_PUZZLE),
        WaveShieldChip("enchanted_puzzle", "强化拼图盾", ShieldCategory.SHAPE_AND_PUZZLE),
        WaveShieldChip("cross_shield", "单十字盾", ShieldCategory.SHAPE_AND_PUZZLE, paramInt = 1),
        WaveShieldChip("cross_shield_2", "双十字盾", ShieldCategory.SHAPE_AND_PUZZLE, paramInt = 2),
        WaveShieldChip("forbid_cross", "避消十字(十之诅咒)", ShieldCategory.SHAPE_AND_PUZZLE),
        WaveShieldChip("l_shape", "L 形盾", ShieldCategory.SHAPE_AND_PUZZLE),
        WaveShieldChip("t_shape", "T 形盾", ShieldCategory.SHAPE_AND_PUZZLE),
        WaveShieldChip("square_2x2", "田字 2×2 盾", ShieldCategory.SHAPE_AND_PUZZLE),
        WaveShieldChip("square_3x3", "正方 3×3 盾", ShieldCategory.SHAPE_AND_PUZZLE),
        WaveShieldChip("all_columns_ameliorate", "全直排 ≥ 4 消", ShieldCategory.SHAPE_AND_PUZZLE),

        // D. Hazard, Terrain & Path (障碍、地形与路径)
        WaveShieldChip("traffic_light", "起结有序", ShieldCategory.HAZARD_AND_PATH),
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
        "min_combo", "max_combo", "min_first_combo", "min_first_elem_combo",
        "exact_combo", "exact_first_combo", "wave1_same_kind", "wave1_distinct_kind",
        "zero_combo", "odd_combo", "even_combo"
    )
    val PUZZLE_EXCLUSIVE_IDS = setOf("puzzle_shield", "loose_puzzle", "strict_puzzle", "enchanted_puzzle")
    val CROSS_EXCLUSIVE_IDS = setOf("cross_shield", "cross_shield_2", "cross_shield_3", "forbid_cross")
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
 * Greyed-out enemy skill currently bypassed by the player's team leaders/skills (ToS 2025.41+).
 * Preserved verbatim in Room knowledge DB and surfaced as a muted recoverable pill in the overlay.
 */
data class BypassedEnemySkill(
    val titleZhTw: String,
    val chips: List<WaveShieldChip>
)

/**
 * Process-wide live reactive strategy state shared seamlessly between DashboardScreen and OverlayControllerService.
 * Holds active per-wave WaveShieldChips (from parameterized Shield Controls OR automatic Enemy Skill Recognition),
 * with strict 4-tier arbitration: User Manual > Popup OCR > Enemy Icon > Board Markers.
 */
object ActiveStrategyStore {
    private val _activeChips = MutableStateFlow<List<WaveShieldChip>>(emptyList())
    val activeChips: StateFlow<List<WaveShieldChip>> = _activeChips.asStateFlow()

    private val _bypassedGreySkills = MutableStateFlow<List<BypassedEnemySkill>>(emptyList())
    val bypassedGreySkills: StateFlow<List<BypassedEnemySkill>> = _bypassedGreySkills.asStateFlow()

    private val _activeVisualHints = MutableStateFlow(BoardVisualHints())
    val activeVisualHints: StateFlow<BoardVisualHints> = _activeVisualHints.asStateFlow()

    private val _dismissedAutoPolicyCodes = MutableStateFlow<Set<String>>(emptySet())
    val dismissedAutoPolicyCodes: StateFlow<Set<String>> = _dismissedAutoPolicyCodes.asStateFlow()

    private val dismissedAutoChipIds = LinkedHashSet<String>()
    private val dismissedVisualHintKeys = LinkedHashSet<String>()

    @Synchronized
    fun toggleChip(chip: WaveShieldChip) {
        val current = _activeChips.value.toMutableList()
        val existingIdx = current.indexOfFirst { it.id == chip.id }
        if (existingIdx >= 0) {
            if (current[existingIdx].autoDetected) dismissedAutoChipIds.add(chip.id)
            current.removeAt(existingIdx)
        } else {
            if (!chip.autoDetected) dismissedAutoChipIds.remove(chip.id)
            current.add(chip)
        }
        _activeChips.value = current
    }

    @Synchronized
    fun upsertChip(chip: WaveShieldChip) {
        if (!chip.autoDetected) dismissedAutoChipIds.remove(chip.id)
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
    @Synchronized
    fun replaceGroup(exclusiveIds: Set<String>, replacement: WaveShieldChip?) {
        val current = _activeChips.value.filterNot { it.id in exclusiveIds }.toMutableList()
        if (replacement != null) {
            if (!replacement.autoDetected) dismissedAutoChipIds.remove(replacement.id)
            current.add(replacement)
        }
        _activeChips.value = current
    }

    @Synchronized
    fun replaceByPrefix(prefixes: List<String>, replacement: WaveShieldChip?) {
        val current = _activeChips.value.filterNot { chip -> prefixes.any { chip.id.startsWith(it) } }.toMutableList()
        if (replacement != null) {
            if (!replacement.autoDetected) dismissedAutoChipIds.remove(replacement.id)
            current.add(replacement)
        }
        _activeChips.value = current
    }

    @Synchronized
    fun removeChip(id: String) {
        val existing = _activeChips.value.firstOrNull { it.id == id }
        if (existing?.autoDetected == true) {
            dismissedAutoChipIds.add(id)
            // Also block canonical alias family so background EnemyIconNetV1 won't re-add it this wave
            dismissedAutoChipIds.addAll(exclusiveGroupFor(id))
        }
        _activeChips.value = _activeChips.value.filterNot { it.id == id }
    }

    /**
     * Removes a single element bit from a multi-element chip (e.g. when user resets Dark's
     * `anti_first_wave` or `forbid_all` cell in the 6x5 Per-Element Matrix), or deletes the
     * whole chip if that was the last element in its mask!
     */
    @Synchronized
    fun removeElementFromChip(chipId: String, elementCode: Int) {
        val bit = 1 shl elementCode
        val current = _activeChips.value.toMutableList()
        val idx = current.indexOfFirst { it.id == chipId }
        if (idx < 0) return
        val prev = current[idx]
        val nextMask = (prev.paramElementMask and 0x3F) and bit.inv()
        if (nextMask == 0) {
            if (prev.autoDetected) dismissedAutoChipIds.add(chipId)
            current.removeAt(idx)
        } else {
            val highBits = prev.paramElementMask and 0x3F.inv()
            current[idx] = prev.copy(paramElementMask = highBits or nextMask, autoDetected = false)
        }
        _activeChips.value = current
    }

    @Synchronized
    fun dismissVisualHint(hintKey: String) {
        dismissedVisualHintKeys.add(hintKey)
        _activeVisualHints.value = maskDismissedHints(_activeVisualHints.value)
    }

    @Synchronized
    fun dismissAutoPolicyCode(code: String) {
        _dismissedAutoPolicyCodes.value = _dismissedAutoPolicyCodes.value + code
    }

    @Synchronized
    fun resetDismissedAutoPolicies() {
        _dismissedAutoPolicyCodes.value = emptySet()
    }

    private fun maskDismissedHints(h: BoardVisualHints): BoardVisualHints = h.copy(
        hasMonochrome = h.hasMonochrome && "monochrome" !in dismissedVisualHintKeys,
        hasDisguise = h.hasDisguise && "disguise" !in dismissedVisualHintKeys,
        hasBurning = h.hasBurning && "burning" !in dismissedVisualHintKeys,
        hasScorching = h.hasScorching && "scorching" !in dismissedVisualHintKeys,
        hasElectrified = h.hasElectrified && "electrified" !in dismissedVisualHintKeys,
        hasSticky = h.hasSticky && "sticky" !in dismissedVisualHintKeys,
        hasPurgatoryFire = h.hasPurgatoryFire && "purgatory" !in dismissedVisualHintKeys,
        hasHiddenOrbs = h.hasHiddenOrbs && "hidden" !in dismissedVisualHintKeys,
        hasFrozenOrPetrified = h.hasFrozenOrPetrified && "frozen_petrified" !in dismissedVisualHintKeys,
        hasWeatheredOrBomb = h.hasWeatheredOrBomb && "weathered_bomb" !in dismissedVisualHintKeys
    )

    /**
     * Applies [incoming] enemy-skill chips using strict 4-tier arbitration:
     *  - Never resurrects an auto chip ID manually dismissed (`×`) during the same wave.
     *  - Never overwrites a user-configured manual chip (`autoDetected == false`) of the same ID or exclusive group.
     *  - Replaces stale auto chips in the same exclusive group/ID cleanly.
     * Returns the number of auto chips newly added or updated.
     */
    @Synchronized
    fun applyAutoDetectedChips(
        incoming: List<WaveShieldChip>,
        bypassed: List<BypassedEnemySkill> = emptyList()
    ): Int {
        _bypassedGreySkills.value = bypassed
        if (incoming.isEmpty()) return 0

        val current = _activeChips.value.toMutableList()
        var appliedCount = 0

        for (rawChip in incoming) {
            val chip = rawChip.copy(autoDetected = true)
            if (chip.id in dismissedAutoChipIds) continue

            val groupIds = exclusiveGroupFor(chip.id)
            val hasManualConflict = current.any { existing ->
                !existing.autoDetected && (existing.id == chip.id || existing.id in groupIds)
            }
            if (hasManualConflict) continue

            val sameIdx = current.indexOfFirst { it.id == chip.id }
            if (sameIdx >= 0) {
                if (current[sameIdx] != chip) {
                    current[sameIdx] = chip
                    appliedCount++
                }
            } else {
                current.removeAll { it.autoDetected && it.id in groupIds }
                current.add(chip)
                appliedCount++
            }
        }
        _activeChips.value = current
        return appliedCount
    }

    /**
     * Allows user to manually override and activate a previously bypassed grey-text enemy skill in one tap.
     */
    @Synchronized
    fun restoreBypassedSkill(skill: BypassedEnemySkill): Int {
        _bypassedGreySkills.value = _bypassedGreySkills.value.filterNot { it == skill }
        var restored = 0
        for (c in skill.chips) {
            val manualChip = c.copy(autoDetected = false)
            dismissedAutoChipIds.remove(manualChip.id)
            val groupIds = exclusiveGroupFor(manualChip.id)
            val current = _activeChips.value.filterNot { it.id in groupIds }.toMutableList()
            current.add(manualChip)
            _activeChips.value = current
            restored++
        }
        return restored
    }

    /**
     * Collaborative Stage 2B `EnemyIconNetV1` ONNX fusion (`v0.9.0`):
     *  - Runs continuously & at solve-capture time EVEN WHEN Stage Scripts or Popup OCR are active!
     *  - Supplements newly appeared shields / phase shifts / visual hints not yet in [_activeChips].
     *  - NEVER downgrades richer parameterized OCR/Script shields of the same family (e.g. preserves
     *    `火强化严格拼图盾` or `水火双十字盾` instead of overwriting with generic icon defaults).
     *  - Respects user's per-wave `×` dismissals and manual overrides.
     */
    @Synchronized
    fun mergeCollaborativeIconDetections(
        iconChips: List<WaveShieldChip>,
        iconHints: BoardVisualHints
    ): Int {
        var added = 0
        if (iconChips.isNotEmpty()) {
            val current = _activeChips.value.toMutableList()
            for (rawChip in iconChips) {
                val chip = rawChip.copy(autoDetected = true)
                if (chip.id in dismissedAutoChipIds) continue
                val familyIds = iconConflictFamilyFor(chip.id)
                val alreadyCovered = current.any { existing ->
                    existing.id == chip.id || existing.id in familyIds
                }
                if (!alreadyCovered) {
                    current.add(chip)
                    added++
                }
            }
            if (added > 0) {
                _activeChips.value = current
            }
        }
        if (iconHints.hasAny()) {
            val merged = maskDismissedHints(_activeVisualHints.value.mergeWith(iconHints))
            if (merged != _activeVisualHints.value) {
                _activeVisualHints.value = merged
            }
        }
        return added
    }

    /**
     * `S2 -> S1` Board-to-Strategy Closed-Loop Feedback (`v0.9.0` Tier-4 Arbitration):
     * Propagates unmistakable board-resident states (`BLACK_WHITE` monochrome disguise, Starting Buoy,
     * Traffic Light Start+End, Puzzle polygon, Electrified/Frozen/Burning/Scorching/Sticky/Bloodbath/Shattered)
     * back into [_activeVisualHints] and [_activeChips] even when upper enemy skill icons are occluded or disabled.
     *
     * Strictly respects Tier 1..3 precedence (never overwrites user/OCR/Icon chips and honors wave dismissals).
     */
    @Synchronized
    fun mergeBoardDerivedSignals(board: BoardState): Int {
        if (board.cells.isEmpty()) return 0

        var bwCount = 0
        var elecCount = 0
        var frozPetCount = 0
        var weathBombCount = 0
        var purgCount = 0
        var hiddenCount = 0
        var burnCount = 0
        var scorchCount = 0
        var stickyCount = 0
        var bbCount = 0
        var bhCount = 0
        var shatCount = 0
        var puzzle1Count = 0
        var hasTlStart = false
        var hasTlEnd = false

        for (i in board.cells.indices) {
            val h = board.cells[i].hazards
            val f = board.tileFlags.getOrElse(i) { TileFlagBits.NONE }
            if ((h and HazardBits.BLACK_WHITE) != 0) bwCount++
            if ((h and HazardBits.ELECTRIFIED) != 0) elecCount++
            if ((h and (HazardBits.FROZEN or HazardBits.ICE_SEALED or HazardBits.PETRIFIED)) != 0) frozPetCount++
            if ((h and (HazardBits.WEATHERED or HazardBits.BOMB)) != 0) weathBombCount++
            if ((h and HazardBits.PURGATORY_FIRE) != 0) purgCount++
            if ((h and HazardBits.QUESTION) != 0 || board.cells[i].element == Element.HIDDEN) hiddenCount++

            if ((f and TileFlagBits.BURNING_TILE) != 0) burnCount++
            if ((f and TileFlagBits.SCORCHING_TILE) != 0) scorchCount++
            if ((f and TileFlagBits.STICKY_TILE) != 0) stickyCount++
            if ((f and TileFlagBits.END_FORBIDDEN) != 0) bbCount++
            if ((f and TileFlagBits.BLACK_HOLE) != 0) bhCount++
            if ((f and TileFlagBits.SHATTERED) != 0) shatCount++
            if ((f and TileFlagBits.PUZZLE_1) != 0) puzzle1Count++
            if ((f and TileFlagBits.START_ALLOWED) != 0) hasTlStart = true
            if ((f and TileFlagBits.END_REQUIRED) != 0) hasTlEnd = true
        }

        val boardHints = BoardVisualHints(
            hasMonochrome = bwCount >= maxOf(8, (board.cells.size * 0.35f).toInt()),
            hasBurning = burnCount > 0,
            hasScorching = scorchCount > 0,
            hasElectrified = elecCount > 0,
            hasSticky = stickyCount > 0,
            hasPurgatoryFire = purgCount > 0,
            hasHiddenOrbs = hiddenCount >= 4,
            hasFrozenOrPetrified = frozPetCount > 0,
            hasWeatheredOrBomb = weathBombCount > 0,
            hasBloodbath = bbCount > 0,
            hasBlackHole = bhCount > 0,
            hasShattered = shatCount > 0
        )

        val derivedChips = ArrayList<WaveShieldChip>(3)
        if (board.startingBuoyCol in 0 until board.cols) {
            derivedChips.add(
                WaveShieldChip(
                    id = "starting_buoy_shield",
                    label = "起动浮标(盘面)",
                    category = ShieldCategory.COUNT_AND_ELEMENT,
                    autoDetected = true
                )
            )
        }
        if (hasTlStart && hasTlEnd) {
            derivedChips.add(
                WaveShieldChip(
                    id = "traffic_light",
                    label = "起结有序(盘面)",
                    category = ShieldCategory.HAZARD_AND_PATH,
                    autoDetected = true
                )
            )
        }
        if (puzzle1Count >= 3) {
            derivedChips.add(
                WaveShieldChip(
                    id = "puzzle_shield",
                    label = "拼图盾(盘面)",
                    category = ShieldCategory.SHAPE_AND_PUZZLE,
                    autoDetected = true,
                    paramInt = 1
                )
            )
        }

        return mergeCollaborativeIconDetections(derivedChips, boardHints)
    }

    private fun iconConflictFamilyFor(chipId: String): Set<String> = when (chipId) {
        in WaveShieldCatalog.PUZZLE_EXCLUSIVE_IDS -> WaveShieldCatalog.PUZZLE_EXCLUSIVE_IDS
        in WaveShieldCatalog.CROSS_EXCLUSIVE_IDS -> WaveShieldCatalog.CROSS_EXCLUSIVE_IDS
        in WaveShieldCatalog.COMBO_EXCLUSIVE_IDS -> WaveShieldCatalog.COMBO_EXCLUSIVE_IDS
        in WaveShieldCatalog.STEP_EXCLUSIVE_IDS -> WaveShieldCatalog.STEP_EXCLUSIVE_IDS
        "five_elements" -> setOf("five_elements", "five_elements_enchanted", "require_elements")
        "any_enchanted_orb_shield" -> setOf("any_enchanted_orb_shield", "five_elements_enchanted", "require_enchanted_elements", "require_elements")
        else -> setOf(chipId)
    }

    @Synchronized
    fun setActiveVisualHints(hints: BoardVisualHints) {
        _activeVisualHints.value = maskDismissedHints(hints)
    }

    /**
     * Clears ONLY auto-detected per-wave chips on wave transition, preserving user's manual chips
     * and resetting the wave-local dismissal guard. Returns the count of cleared auto chips.
     */
    @Synchronized
    fun clearAutoDetectedChips(): Int {
        val before = _activeChips.value
        val remaining = before.filterNot { it.autoDetected }
        val removed = before.size - remaining.size
        _activeChips.value = remaining
        _bypassedGreySkills.value = emptyList()
        dismissedAutoChipIds.clear()
        dismissedVisualHintKeys.clear()
        _dismissedAutoPolicyCodes.value = emptySet()
        _activeVisualHints.value = BoardVisualHints()
        return removed
    }

    @Synchronized
    fun clearAll() {
        _activeChips.value = emptyList()
        _bypassedGreySkills.value = emptyList()
        dismissedAutoChipIds.clear()
        dismissedVisualHintKeys.clear()
        _dismissedAutoPolicyCodes.value = emptySet()
        _activeVisualHints.value = BoardVisualHints()
    }

    private fun exclusiveGroupFor(chipId: String): Set<String> = when {
        chipId in WaveShieldCatalog.COMBO_EXCLUSIVE_IDS -> WaveShieldCatalog.COMBO_EXCLUSIVE_IDS
        chipId in WaveShieldCatalog.PUZZLE_EXCLUSIVE_IDS -> WaveShieldCatalog.PUZZLE_EXCLUSIVE_IDS
        chipId in WaveShieldCatalog.CROSS_EXCLUSIVE_IDS -> WaveShieldCatalog.CROSS_EXCLUSIVE_IDS
        chipId in WaveShieldCatalog.STEP_EXCLUSIVE_IDS -> WaveShieldCatalog.STEP_EXCLUSIVE_IDS
        else -> setOf(chipId)
    }
}
