package com.autodora.next.model

/**
 * Exact Kotlin mirror of C++20 `autodora::ShieldStatusBits` (`solver-core/include/autodora/evaluator.hpp`).
 * Enables per-shield pass/fail diagnostics for every active wave shield on each candidate solution.
 */
object ShieldStatusBits {
    const val NONE                = 0
    const val COMBO_SHIELD        = 1 shl 0
    const val COMBO_PARITY        = 1 shl 1
    const val SAME_COMBO_ELEMENTS = 1 shl 2
    const val DIGITAL_ORB_COUNT   = 1 shl 3
    const val FULL_CLEAR          = 1 shl 4
    const val ELEMENT_SHIELD      = 1 shl 5
    const val ENCHANTED_SHIELD    = 1 shl 6
    const val FORBID_FIRST_WAVE   = 1 shl 7
    const val FORBID_ALL          = 1 shl 8
    const val CLEAR_ALL_ELEMENT   = 1 shl 9
    const val PUZZLE_1            = 1 shl 10
    const val PUZZLE_2            = 1 shl 11
    const val CROSS_SHIELD        = 1 shl 12
    const val SHAPE_SHIELD        = 1 shl 13
    const val COLUMN_AMELIORATE   = 1 shl 14
    const val ROW_BONUS           = 1 shl 15
    const val TRAFFIC_LIGHT       = 1 shl 16
    const val STEP_LIMIT          = 1 shl 17
    const val HAZARDS_CLEAR       = 1 shl 18
    const val BURNING_LIMIT       = 1 shl 19
    const val SECOND_WAVE_SHIELD  = 1 shl 20
    const val EQUAL_PAIR_COMBO    = 1 shl 21
    const val SAME_ELEM_MULTI_C   = 1 shl 22
    const val RACE_ORB_SHIELD     = 1 shl 23
    const val CHAR_ORB_TERRAIN    = 1 shl 24
    const val TRAIL_CELLS_GOAL    = 1 shl 25
    const val MIASMA_LIMIT        = 1 shl 26
    const val WAVE1_KIND_SHIELD   = 1 shl 27
}

enum class ShieldVerdictStatus {
    PASSED,
    FAILED,
    UNDETECTED
}

data class ShieldVerdict(
    val chipId: String,
    val chip: WaveShieldChip?,
    val status: ShieldVerdictStatus,
    val detailTag: String = ""
)

/**
 * Evaluates every active wave shield chip (plus auto-detected board shields such as Puzzle / Traffic Light)
 * individually against a solved [SolutionPath] so the player can immediately see which shields are satisfied
 * (`✓`), which failed (`✗`), and which board markers were not visually detected (`?`).
 */
object ShieldVerdictEvaluator {

    fun evaluate(
        activeChips: List<WaveShieldChip>,
        board: BoardState,
        solution: SolutionPath
    ): List<ShieldVerdict> {
        val out = ArrayList<ShieldVerdict>(activeChips.size + 2)
        val seenIds = HashSet<String>(activeChips.size + 2)

        val firstWaveElemMask = solution.groups
            .filter { it.wave == 1 && it.element.isMatchable }
            .fold(0) { acc, g -> acc or (1 shl g.element.code) }
        val totalElemMask = solution.groups
            .filter { it.element.isMatchable }
            .fold(0) { acc, g -> acc or (1 shl g.element.code) }
        val totalEnchMask = solution.groups
            .filter { it.hasEnchanted && it.element.isMatchable }
            .fold(0) { acc, g -> acc or (1 shl g.element.code) }
        val firstWaveEnchMask = solution.groups
            .filter { it.wave == 1 && it.hasEnchanted && it.element.isMatchable }
            .fold(0) { acc, g -> acc or (1 shl g.element.code) }

        val hasPuzzle1OnBoard = board.tileFlags.any { (it and TileFlagBits.PUZZLE_1) != 0 }
        val hasTlStartOnBoard = board.tileFlags.any { (it and TileFlagBits.START_ALLOWED) != 0 }
        val hasTlEndOnBoard = board.tileFlags.any { (it and TileFlagBits.END_REQUIRED) != 0 }

        fun evalTrafficLight(chip: WaveShieldChip?, explicitChip: Boolean): ShieldVerdict {
            val startIdx = solution.start.row * board.cols + solution.start.col
            val endCell = solution.waypoints.lastOrNull() ?: solution.start
            val endIdx = endCell.row * board.cols + endCell.col
            val startOk = !hasTlStartOnBoard ||
                (startIdx in board.tileFlags.indices && (board.tileFlags[startIdx] and TileFlagBits.START_ALLOWED) != 0)
            val endOk = !hasTlEndOnBoard ||
                (endIdx in board.tileFlags.indices && (board.tileFlags[endIdx] and TileFlagBits.END_REQUIRED) != 0)

            return when {
                !hasTlStartOnBoard && !hasTlEndOnBoard ->
                    ShieldVerdict("traffic_light", chip, ShieldVerdictStatus.UNDETECTED, "missing_both")
                hasTlStartOnBoard && hasTlEndOnBoard ->
                    ShieldVerdict(
                        "traffic_light",
                        chip,
                        if (startOk && endOk) ShieldVerdictStatus.PASSED else ShieldVerdictStatus.FAILED,
                        if (startOk && endOk) "both" else if (!startOk) "miss_start" else "miss_end"
                    )
                hasTlStartOnBoard && !hasTlEndOnBoard ->
                    ShieldVerdict(
                        "traffic_light",
                        chip,
                        when {
                            !startOk -> ShieldVerdictStatus.FAILED
                            explicitChip -> ShieldVerdictStatus.UNDETECTED
                            else -> ShieldVerdictStatus.PASSED
                        },
                        if (explicitChip) "missing_end" else "start_only"
                    )
                else -> // !hasTlStartOnBoard && hasTlEndOnBoard
                    ShieldVerdict(
                        "traffic_light",
                        chip,
                        when {
                            !endOk -> ShieldVerdictStatus.FAILED
                            explicitChip -> ShieldVerdictStatus.UNDETECTED
                            else -> ShieldVerdictStatus.PASSED
                        },
                        if (explicitChip) "missing_start" else "end_only"
                    )
            }
        }

        fun evalPuzzle(chipId: String, chip: WaveShieldChip?): ShieldVerdict {
            if (!hasPuzzle1OnBoard) {
                return ShieldVerdict(chipId, chip, ShieldVerdictStatus.UNDETECTED, "missing_frame")
            }
            val passed = (solution.failedFlags and ShieldStatusBits.PUZZLE_1) == 0 &&
                (solution.satisfiedFlags and ShieldStatusBits.PUZZLE_1) != 0
            return ShieldVerdict(chipId, chip, if (passed) ShieldVerdictStatus.PASSED else ShieldVerdictStatus.FAILED)
        }

        fun bitOk(bit: Int): ShieldVerdictStatus =
            if ((solution.failedFlags and bit) == 0) ShieldVerdictStatus.PASSED else ShieldVerdictStatus.FAILED

        for (chip in activeChips) {
            seenIds.add(chip.id)
            val verdict = when {
                chip.id == "traffic_light" -> evalTrafficLight(chip, explicitChip = true)
                chip.id in setOf("puzzle_shield", "strict_puzzle", "loose_puzzle", "enchanted_puzzle") ->
                    evalPuzzle(chip.id, chip)
                chip.id == "min_combo" -> ShieldVerdict(
                    chip.id, chip,
                    if (solution.totalCombo >= chip.paramInt) ShieldVerdictStatus.PASSED else ShieldVerdictStatus.FAILED
                )
                chip.id == "min_first_combo" -> ShieldVerdict(
                    chip.id, chip,
                    if (solution.firstWaveCombo >= chip.paramInt) ShieldVerdictStatus.PASSED else ShieldVerdictStatus.FAILED
                )
                chip.id == "min_first_elem_combo" -> {
                    val elemW1 = solution.groups.count { it.wave == 1 && it.element.code in 0..4 }
                    ShieldVerdict(
                        chip.id, chip,
                        if (elemW1 >= chip.paramInt) ShieldVerdictStatus.PASSED else ShieldVerdictStatus.FAILED
                    )
                }
                chip.id == "wave1_same_kind" || chip.id == "wave1_distinct_kind" -> {
                    val ok = solution.firstWaveCombo == chip.paramInt &&
                        (solution.failedFlags and ShieldStatusBits.WAVE1_KIND_SHIELD) == 0
                    ShieldVerdict(chip.id, chip, if (ok) ShieldVerdictStatus.PASSED else ShieldVerdictStatus.FAILED)
                }
                chip.id == "exact_combo" -> ShieldVerdict(
                    chip.id, chip,
                    if (solution.totalCombo == chip.paramInt) ShieldVerdictStatus.PASSED else ShieldVerdictStatus.FAILED
                )
                chip.id == "exact_first_combo" -> ShieldVerdict(
                    chip.id, chip,
                    if (solution.firstWaveCombo == chip.paramInt) ShieldVerdictStatus.PASSED else ShieldVerdictStatus.FAILED
                )
                chip.id == "zero_combo" -> ShieldVerdict(
                    chip.id, chip,
                    if (solution.totalCombo == 0 && solution.stepCount >= 1) ShieldVerdictStatus.PASSED else ShieldVerdictStatus.FAILED
                )
                chip.id == "odd_combo" || chip.id == "even_combo" ->
                    ShieldVerdict(chip.id, chip, bitOk(ShieldStatusBits.COMBO_PARITY))
                chip.id == "same_combo_elements" ->
                    ShieldVerdict(chip.id, chip, bitOk(ShieldStatusBits.SAME_COMBO_ELEMENTS))
                chip.id == "second_wave_combo" ->
                    ShieldVerdict(chip.id, chip, bitOk(ShieldStatusBits.SECOND_WAVE_SHIELD))
                chip.id == "equal_pair_light_dark" ->
                    ShieldVerdict(chip.id, chip, bitOk(ShieldStatusBits.EQUAL_PAIR_COMBO))
                chip.id == "same_elem_combos" ->
                    ShieldVerdict(chip.id, chip, bitOk(ShieldStatusBits.SAME_ELEM_MULTI_C))
                chip.id == "min_distinct_elements" -> {
                    val distinct = Integer.bitCount(totalElemMask and 0x3F)
                    ShieldVerdict(
                        chip.id, chip,
                        if (distinct >= chip.paramInt.coerceIn(1, 6)) ShieldVerdictStatus.PASSED else ShieldVerdictStatus.FAILED
                    )
                }
                chip.id == "five_elements" -> ShieldVerdict(
                    chip.id, chip,
                    if ((totalElemMask and RuleComposer.FIVE_ELEMENTS_MASK) == RuleComposer.FIVE_ELEMENTS_MASK)
                        ShieldVerdictStatus.PASSED else ShieldVerdictStatus.FAILED
                )
                chip.id == "five_elements_enchanted" -> ShieldVerdict(
                    chip.id, chip,
                    if ((totalEnchMask and RuleComposer.FIVE_ELEMENTS_MASK) == RuleComposer.FIVE_ELEMENTS_MASK)
                        ShieldVerdictStatus.PASSED else ShieldVerdictStatus.FAILED
                )
                chip.id == "three_elements" -> ShieldVerdict(
                    chip.id, chip,
                    if ((totalElemMask and RuleComposer.THREE_ELEMENTS_MASK) == RuleComposer.THREE_ELEMENTS_MASK)
                        ShieldVerdictStatus.PASSED else ShieldVerdictStatus.FAILED
                )
                chip.id == "require_elements" || chip.id.startsWith("require_elem_") -> {
                    val reqMask = chip.paramElementMask and 0x3F
                    val perEnch = (chip.paramElementMask ushr 8) and 0x3F
                    val effEnch = if (perEnch != 0) perEnch else if ((chip.paramInt and 1) != 0) reqMask else 0
                    val firstWaveStrict = (chip.paramInt and 2) != 0
                    val targetElemMask = if (firstWaveStrict) firstWaveElemMask else totalElemMask
                    val elemOk = (targetElemMask and reqMask) == reqMask
                    val enchOk = (totalEnchMask and effEnch) == effEnch
                    ShieldVerdict(chip.id, chip, if (elemOk && enchOk) ShieldVerdictStatus.PASSED else ShieldVerdictStatus.FAILED)
                }
                chip.id == "require_enchanted_elements" || chip.id.startsWith("require_ench_") -> {
                    val reqMask = chip.paramElementMask and 0x3F
                    val ok = (totalElemMask and reqMask) == reqMask && (totalEnchMask and reqMask) == reqMask
                    ShieldVerdict(chip.id, chip, if (ok) ShieldVerdictStatus.PASSED else ShieldVerdictStatus.FAILED)
                }
                chip.id == "elem_burst_shield" -> {
                    val reqMask = chip.paramElementMask and 0x3F
                    val ok = (firstWaveElemMask and reqMask) == reqMask &&
                        (solution.failedFlags and ShieldStatusBits.DIGITAL_ORB_COUNT) == 0
                    ShieldVerdict(chip.id, chip, if (ok) ShieldVerdictStatus.PASSED else ShieldVerdictStatus.FAILED)
                }
                chip.id == "anti_first_wave" || chip.id.startsWith("anti_first_wave_") -> {
                    val mask = chip.paramElementMask and 0x3F
                    val ok = (firstWaveElemMask and mask) == 0 &&
                        (solution.failedFlags and ShieldStatusBits.FORBID_FIRST_WAVE) == 0
                    ShieldVerdict(chip.id, chip, if (ok) ShieldVerdictStatus.PASSED else ShieldVerdictStatus.FAILED)
                }
                chip.id == "forbid_all" || chip.id.startsWith("forbid_all_") -> {
                    val mask = chip.paramElementMask and 0x3F
                    val ok = (totalElemMask and mask) == 0 &&
                        (solution.failedFlags and ShieldStatusBits.FORBID_ALL) == 0
                    ShieldVerdict(chip.id, chip, if (ok) ShieldVerdictStatus.PASSED else ShieldVerdictStatus.FAILED)
                }
                chip.id == "clear_all_element" || chip.id.startsWith("clear_all_element_") ->
                    ShieldVerdict(chip.id, chip, bitOk(ShieldStatusBits.CLEAR_ALL_ELEMENT))
                chip.id == "digital_orb_count" -> ShieldVerdict(
                    chip.id, chip,
                    if (solution.firstWaveOrbs == chip.paramInt) ShieldVerdictStatus.PASSED else ShieldVerdictStatus.FAILED
                )
                chip.id == "first_wave_full_clear" ->
                    ShieldVerdict(chip.id, chip, bitOk(ShieldStatusBits.FULL_CLEAR))
                chip.id == "any_enchanted_orb_shield" -> ShieldVerdict(
                    chip.id, chip,
                    if (firstWaveEnchMask != 0) ShieldVerdictStatus.PASSED else ShieldVerdictStatus.FAILED
                )
                chip.id == "race_orb_shield" ->
                    ShieldVerdict(chip.id, chip, bitOk(ShieldStatusBits.RACE_ORB_SHIELD))
                chip.id == "char_orb_first_wave" || chip.id == "char_orb_terrain" ->
                    ShieldVerdict(chip.id, chip, bitOk(ShieldStatusBits.CHAR_ORB_TERRAIN))
                chip.id == "forbid_weakened_orb" || chip.id == "luminous_orb_shield" ->
                    ShieldVerdict(chip.id, chip, bitOk(ShieldStatusBits.HAZARDS_CLEAR))
                chip.id == "cross_shield" || chip.id.startsWith("cross_shield_") ->
                    ShieldVerdict(chip.id, chip, bitOk(ShieldStatusBits.CROSS_SHIELD))
                chip.id == "l_shape" || chip.id == "t_shape" || chip.id == "square_2x2" ->
                    ShieldVerdict(chip.id, chip, bitOk(ShieldStatusBits.SHAPE_SHIELD))
                chip.id == "all_columns_ameliorate" ->
                    ShieldVerdict(chip.id, chip, bitOk(ShieldStatusBits.COLUMN_AMELIORATE))
                chip.id == "zero_trail_burning" ->
                    ShieldVerdict(chip.id, chip, bitOk(ShieldStatusBits.BURNING_LIMIT))
                chip.id == "trail_corrosion" ->
                    ShieldVerdict(chip.id, chip, bitOk(ShieldStatusBits.TRAIL_CELLS_GOAL))
                chip.id == "step_limit" -> ShieldVerdict(
                    chip.id, chip,
                    if (solution.stepCount <= chip.paramInt.coerceAtLeast(1)) ShieldVerdictStatus.PASSED else ShieldVerdictStatus.FAILED
                )
                chip.id == "step_range" -> {
                    val lo = (chip.paramInt and 0xFF).coerceIn(1, 200)
                    val hi = ((chip.paramInt ushr 8) and 0xFF).coerceIn(lo, 200)
                    ShieldVerdict(
                        chip.id, chip,
                        if (solution.stepCount in lo..hi) ShieldVerdictStatus.PASSED else ShieldVerdictStatus.FAILED
                    )
                }
                chip.id == "exact_steps" -> ShieldVerdict(
                    chip.id, chip,
                    if (solution.stepCount == chip.paramInt) ShieldVerdictStatus.PASSED else ShieldVerdictStatus.FAILED
                )
                // Soft/behavior/physics modifier chips (no pass/fail hard gate)
                chip.id in setOf("first_wave_only", "no_cascade", "unmatchable_elements", "step_hurt_minimize", "fast_first_result", "weijian_burst", "maximize_element_orbs") -> null
                else -> null
            }
            if (verdict != null) out.add(verdict)
        }

        // Auto-detected board shields (when player didn't manually toggle the chip, still report verdict!)
        if ("traffic_light" !in seenIds && (hasTlStartOnBoard || hasTlEndOnBoard)) {
            out.add(evalTrafficLight(null, explicitChip = false))
        }
        val hasAnyPuzzleChip = seenIds.any { it in setOf("puzzle_shield", "strict_puzzle", "loose_puzzle", "enchanted_puzzle") }
        if (!hasAnyPuzzleChip && hasPuzzle1OnBoard) {
            out.add(evalPuzzle("puzzle_shield", null))
        }

        return out
    }
}
