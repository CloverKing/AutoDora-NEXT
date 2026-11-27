package com.autodora.next.model

import kotlinx.serialization.Serializable

/**
 * Two-layer strategy architecture:
 *  1) TeamBasePreset: stays constant across a dungeon (e.g., 2-match team, column ameliorations, element weights)
 *  2) WaveShieldChip: temporary per-wave shield chips (manually toggled OR auto-filled by EnemySkillIntelligenceEngine)
 */
@Serializable
data class TeamBasePreset(
    val id: String,
    val name: String,
    val description: String = "",
    val baseConfig: RuleConfig = RuleConfig()
)

@Serializable
enum class ShieldCategory {
    COMBO,
    COUNT_AND_ELEMENT,
    SHAPE_AND_PUZZLE,
    HAZARD_AND_PATH,
    COMPOSITE
}

@Serializable
data class WaveShieldChip(
    val id: String,
    val label: String,
    val category: ShieldCategory,
    val autoDetected: Boolean = false,
    val paramInt: Int = 0,
    val paramElementMask: Int = 0
)

/**
 * Merges base TeamBasePreset + active temporary WaveShieldChips + board markers into a final immutable RuleConfig.
 * Enemy wave hard constraints automatically override conflicting soft preferences and boost search budget when needed.
 */
object RuleComposer {

    const val FIVE_ELEMENTS_MASK = (1 shl 0) or (1 shl 1) or (1 shl 2) or (1 shl 3) or (1 shl 4)
    const val THREE_ELEMENTS_MASK = (1 shl 0) or (1 shl 1) or (1 shl 2)

    fun compose(
        base: TeamBasePreset,
        activeChips: List<WaveShieldChip>,
        board: BoardState? = null
    ): RuleConfig {
        var cfg = base.baseConfig
        val elemRules = cfg.elementRules.toMutableList()
        val colRules = cfg.columnRules.toMutableList()

        // Apply Grouped & Parameterized Global Matching Goal (v2: N色均衡 / N色全消 / 单色X珠 / N色X珠)
        val goalGroup = cfg.goalMode.normalizedGroup()
        val effN = if (cfg.goalMode == goalGroup) cfg.goalParamN else cfg.goalMode.defaultParamN()
        val effX = if (cfg.goalMode == goalGroup) cfg.goalParamX else cfg.goalMode.defaultParamX()
        fun shapeForX(x: Int): TargetShape = when (x.coerceIn(4, 9)) {
            4 -> TargetShape.COUNT_4
            5 -> TargetShape.COUNT_5
            6 -> TargetShape.COUNT_6
            7 -> TargetShape.COUNT_7
            8 -> TargetShape.COUNT_8
            else -> TargetShape.COUNT_9
        }
        when (goalGroup) {
            StrategyGoalMode.HIGH_COMBO -> Unit
            StrategyGoalMode.N_COLOR_BALANCE -> {
                val n = effN.coerceIn(2, 5)
                cfg = cfg.copy(minDistinctElements = maxOf(cfg.minDistinctElements, n))
                if (cfg.goalElementsMask != 0) {
                    val m = cfg.goalElementsMask and 0x3F
                    for (e in 0 until 6) {
                        if ((m and (1 shl e)) != 0 && elemRules[e].priority == ElementPriority.NORMAL) {
                            elemRules[e] = elemRules[e].copy(priority = ElementPriority.HIGH)
                        }
                    }
                }
            }
            StrategyGoalMode.N_COLOR_ALL -> {
                val n = effN.coerceIn(2, 6)
                val mask = if (cfg.goalElementsMask != 0) {
                    cfg.goalElementsMask and 0x3F
                } else {
                    (0 until n).fold(0) { acc, i -> acc or (1 shl i) }
                }
                cfg = cfg.copy(
                    requiredTotalElementsMask = cfg.requiredTotalElementsMask or mask,
                    minDistinctElements = maxOf(cfg.minDistinctElements, Integer.bitCount(mask).coerceAtLeast(n))
                )
                for (e in 0 until 6) {
                    if ((mask and (1 shl e)) != 0) {
                        elemRules[e] = elemRules[e].copy(
                            priority = ElementPriority.HIGH,
                            clearAllFirstWave = true
                        )
                    }
                }
            }
            StrategyGoalMode.SINGLE_COLOR_X_ORBS -> {
                val targetIdx = if (cfg.goalTargetElement in 0..5) {
                    cfg.goalTargetElement
                } else {
                    elemRules.indexOfFirst { it.priority == ElementPriority.HIGH }.takeIf { it >= 0 } ?: 0
                }
                if (elemRules[targetIdx].targetShape == TargetShape.ANY) {
                    elemRules[targetIdx] = elemRules[targetIdx].copy(
                        targetShape = shapeForX(effX),
                        priority = ElementPriority.HIGH
                    )
                }
            }
            StrategyGoalMode.MULTI_COLOR_X_ORBS -> {
                val n = effN.coerceIn(2, 5)
                val shape = shapeForX(effX)
                val mask = cfg.goalElementsMask and 0x3F
                var applied = 0
                if (mask != 0) {
                    for (e in 0 until 6) {
                        if ((mask and (1 shl e)) != 0 && elemRules[e].priority != ElementPriority.RESERVE) {
                            if (elemRules[e].targetShape == TargetShape.ANY) {
                                elemRules[e] = elemRules[e].copy(targetShape = shape)
                            }
                            applied++
                        }
                    }
                } else {
                    for (e in 0 until 5) {
                        if (applied >= n) break
                        if (elemRules[e].priority != ElementPriority.RESERVE) {
                            if (elemRules[e].targetShape == TargetShape.ANY) {
                                elemRules[e] = elemRules[e].copy(targetShape = shape)
                            }
                            applied++
                        }
                    }
                }
                if (applied >= 2) {
                    cfg = cfg.copy(minDistinctElements = maxOf(cfg.minDistinctElements, minOf(applied, n)))
                }
            }
            else -> Unit
        }

        var highDifficultyShield = false

        for (chip in activeChips) {
            when (chip.id) {
                "exact_combo" -> cfg = cfg.copy(exactTotalCombo = chip.paramInt, avoidSkyfallRisk = true)
                "exact_first_combo" -> cfg = cfg.copy(exactFirstWaveCombo = chip.paramInt, avoidSkyfallRisk = true)
                "min_combo" -> cfg = cfg.copy(minTotalCombo = maxOf(cfg.minTotalCombo, chip.paramInt))
                "min_first_combo" -> cfg = cfg.copy(minFirstWaveCombo = maxOf(cfg.minFirstWaveCombo, chip.paramInt))
                "min_first_elem_combo" -> cfg = cfg.copy(
                    minFirstWaveElementalCombo = maxOf(cfg.minFirstWaveElementalCombo, chip.paramInt.coerceIn(1, 12))
                )
                "wave1_same_kind" -> {
                    cfg = cfg.copy(
                        exactFirstWaveCombo = chip.paramInt.coerceIn(1, 10),
                        wave1KindMode = Wave1KindMode.SAME_KIND_HEART_WILD,
                        avoidSkyfallRisk = true
                    )
                    highDifficultyShield = true
                }
                "wave1_distinct_kind" -> {
                    cfg = cfg.copy(
                        exactFirstWaveCombo = chip.paramInt.coerceIn(1, 10),
                        wave1KindMode = Wave1KindMode.DISTINCT_KIND_HEART_WILD,
                        avoidSkyfallRisk = true
                    )
                    highDifficultyShield = true
                }
                "zero_combo" -> cfg = cfg.copy(exactTotalCombo = 0, maxSteps = minOf(cfg.maxSteps, 6), earlyStopOnGoal = true)
                "odd_combo" -> cfg = cfg.copy(
                    comboParity = ComboParity.ODD,
                    comboParityFirstWaveOnly = (chip.paramInt != 1),
                    avoidSkyfallRisk = true
                )
                "even_combo" -> cfg = cfg.copy(
                    comboParity = ComboParity.EVEN,
                    comboParityFirstWaveOnly = (chip.paramInt != 1),
                    avoidSkyfallRisk = true
                )
                "first_wave_only" -> cfg = cfg.copy(firstWaveOnlyScoring = true)
                "no_cascade" -> cfg = cfg.copy(disableCascades = true, firstWaveOnlyScoring = true)
                "same_combo_elements" -> cfg = cfg.copy(sameComboAcrossEliminatedElements = true)

                "min_distinct_elements" -> cfg = cfg.copy(minDistinctElements = maxOf(cfg.minDistinctElements, chip.paramInt.coerceIn(1, 6)))
                "five_elements" -> cfg = cfg.copy(requiredTotalElementsMask = cfg.requiredTotalElementsMask or FIVE_ELEMENTS_MASK)
                "five_elements_enchanted" -> cfg = cfg.copy(
                    requiredTotalElementsMask = cfg.requiredTotalElementsMask or FIVE_ELEMENTS_MASK,
                    requiredEnchantedElementsMask = cfg.requiredEnchantedElementsMask or FIVE_ELEMENTS_MASK
                )
                "three_elements" -> cfg = cfg.copy(requiredTotalElementsMask = cfg.requiredTotalElementsMask or THREE_ELEMENTS_MASK)
                "require_elements" -> {
                    // Low 6 bits: required elements mask; bits 8..13: independent per-color enchanted mask.
                    // Official Skills #4-#7 (二/三/四/五屬盾) allow cascade/skyfall elimination by default;
                    // set paramInt bit 1 (value 2) when explicit Wave-1-only elimination is required.
                    // Legacy compat: if bits 8..13 are 0 and (paramInt and 1) != 0, treat all required elements as enchanted.
                    val reqMask = chip.paramElementMask and 0x3F
                    val perColorEnch = (chip.paramElementMask ushr 8) and 0x3F
                    val effEnch = if (perColorEnch != 0) perColorEnch else if ((chip.paramInt and 1) != 0) reqMask else 0
                    val firstWaveStrict = (chip.paramInt and 2) != 0
                    cfg = cfg.copy(
                        requiredFirstWaveElementsMask = if (firstWaveStrict) (cfg.requiredFirstWaveElementsMask or reqMask) else cfg.requiredFirstWaveElementsMask,
                        requiredTotalElementsMask = cfg.requiredTotalElementsMask or reqMask,
                        requiredEnchantedElementsMask = cfg.requiredEnchantedElementsMask or effEnch
                    )
                }
                "require_enchanted_elements" -> cfg = cfg.copy(
                    requiredTotalElementsMask = cfg.requiredTotalElementsMask or chip.paramElementMask,
                    requiredEnchantedElementsMask = cfg.requiredEnchantedElementsMask or chip.paramElementMask
                )
                "unmatchable_elements" -> cfg = cfg.copy(
                    unmatchableElementsMask = cfg.unmatchableElementsMask or (chip.paramElementMask and 0x3F)
                )
                "elem_burst_shield" -> {
                    // Cumulative / First-Wave N-Orb Attribute Shield (e.g. 火5 / 水15 / 心5 累积符石盾):
                    // Require eliminating the attribute, prioritize HIGH, maximize its orb count, and when
                    // the live board currently has >= paramInt orbs of that color, enforce minFirstWaveOrbs!
                    val mask = chip.paramElementMask and 0x3F
                    if (mask != 0) {
                        cfg = cfg.copy(
                            requiredFirstWaveElementsMask = cfg.requiredFirstWaveElementsMask or mask,
                            requiredTotalElementsMask = cfg.requiredTotalElementsMask or mask
                        )
                        val targetCount = chip.paramInt.coerceIn(3, 15)
                        for (e in 0 until 6) {
                            if ((mask and (1 shl e)) != 0) {
                                val availOnBoard = board?.cells?.count { it.element.code == e } ?: 0
                                val hardMin = if (board == null || availOnBoard >= targetCount) targetCount.coerceAtMost(9) else 0
                                elemRules[e] = elemRules[e].copy(
                                    priority = ElementPriority.HIGH,
                                    minFirstWaveOrbs = maxOf(elemRules[e].minFirstWaveOrbs, hardMin)
                                )
                                if (cfg.maximizeElementOrbIndex < 0) {
                                    cfg = cfg.copy(maximizeElementOrbIndex = e)
                                }
                            }
                        }
                        highDifficultyShield = true
                    }
                }
                "first_wave_full_clear" -> {
                    cfg = cfg.copy(requireFirstWaveFullClear = true)
                    highDifficultyShield = true
                }
                "digital_orb_count" -> {
                    cfg = cfg.copy(exactFirstWaveTotalOrbs = chip.paramInt)
                    highDifficultyShield = true
                }
                "anti_first_wave" -> {
                    for (e in 0 until 6) {
                        if ((chip.paramElementMask and (1 shl e)) != 0) {
                            elemRules[e] = elemRules[e].copy(timing = MatchTiming.FORBID_FIRST_WAVE_ALLOW_CASCADE)
                        }
                    }
                }
                "forbid_all" -> {
                    for (e in 0 until 6) {
                        if ((chip.paramElementMask and (1 shl e)) != 0) {
                            elemRules[e] = elemRules[e].copy(timing = MatchTiming.FORBID_ALL)
                        }
                    }
                }
                "clear_all_element" -> {
                    for (e in 0 until 6) {
                        if ((chip.paramElementMask and (1 shl e)) != 0) {
                            elemRules[e] = elemRules[e].copy(clearAllFirstWave = true)
                        }
                    }
                }

                "puzzle_shield" -> {
                    // Parameterized Puzzle Shield:
                    //  bit 0: strictNoExternalTouch (1=strict, 0=loose)
                    //  bit 1: requireEnchanted (1=enchanted, 0=any)
                    //  bits 2..5: requiredElement + 1 (0 => -1 any, 1..6 => 0..5 Water..Heart)
                    val strict = (chip.paramInt and 1) != 0
                    val ench = (chip.paramInt and 2) != 0
                    val elemEncoded = (chip.paramInt ushr 2) and 0x0F
                    val reqElem = if (elemEncoded in 1..6) elemEncoded - 1 else -1
                    cfg = cfg.copy(
                        puzzle1 = cfg.puzzle1.copy(
                            enabled = true,
                            strictNoExternalTouch = strict,
                            requiredElement = reqElem,
                            requireEnchanted = ench
                        )
                    )
                    highDifficultyShield = true
                }
                "strict_puzzle" -> {
                    cfg = cfg.copy(puzzle1 = cfg.puzzle1.copy(enabled = true, strictNoExternalTouch = true))
                    highDifficultyShield = true
                }
                "loose_puzzle" -> {
                    cfg = cfg.copy(puzzle1 = cfg.puzzle1.copy(enabled = true, strictNoExternalTouch = false))
                    highDifficultyShield = true
                }
                "enchanted_puzzle" -> {
                    cfg = cfg.copy(puzzle1 = cfg.puzzle1.copy(enabled = true, strictNoExternalTouch = true, requireEnchanted = true))
                    highDifficultyShield = true
                }
                "cross_shield" -> {
                    val count = (chip.paramInt and 0x0F).coerceIn(1, 3)
                    val distinctColors = (chip.paramInt and 0x20) != 0
                    val elemMask = chip.paramElementMask and 0x3F
                    cfg = cfg.copy(
                        crossShield = cfg.crossShield.copy(
                            requiredCount = maxOf(cfg.crossShield.requiredCount, count),
                            requireDistinctColors = cfg.crossShield.requireDistinctColors || distinctColors || Integer.bitCount(elemMask) >= 2,
                            requiredElementsMask = cfg.crossShield.requiredElementsMask or elemMask
                        )
                    )
                    highDifficultyShield = true
                }
                "l_shape" -> {
                    // Official Skill #16 (L字限盾): strict exact 5 orbs!
                    val cnt = if (chip.paramInt > 0) chip.paramInt.coerceIn(1, 3) else 1
                    cfg = cfg.copy(shapeShield = cfg.shapeShield.copy(requireLCount = maxOf(cfg.shapeShield.requireLCount, cnt), strictShape = true))
                    val em = chip.paramElementMask and 0x3F
                    if (em != 0) {
                        cfg = cfg.copy(requiredFirstWaveElementsMask = cfg.requiredFirstWaveElementsMask or em)
                        for (e in 0 until 6) if ((em and (1 shl e)) != 0) elemRules[e] = elemRules[e].copy(priority = ElementPriority.HIGH)
                    }
                    highDifficultyShield = true
                }
                "t_shape" -> {
                    // Official Skill #15 (T字限盾): strict exact 5 orbs!
                    val cnt = if (chip.paramInt > 0) chip.paramInt.coerceIn(1, 3) else 1
                    cfg = cfg.copy(shapeShield = cfg.shapeShield.copy(requireTCount = maxOf(cfg.shapeShield.requireTCount, cnt), strictShape = true))
                    val em = chip.paramElementMask and 0x3F
                    if (em != 0) {
                        cfg = cfg.copy(requiredFirstWaveElementsMask = cfg.requiredFirstWaveElementsMask or em)
                        for (e in 0 until 6) if ((em and (1 shl e)) != 0) elemRules[e] = elemRules[e].copy(priority = ElementPriority.HIGH)
                    }
                    highDifficultyShield = true
                }
                "square_2x2" -> cfg = cfg.copy(shapeShield = cfg.shapeShield.copy(requireSquare2x2Count = maxOf(cfg.shapeShield.requireSquare2x2Count, 1)))
                "all_columns_ameliorate" -> {
                    for (c in 0 until 6) {
                        colRules[c] = colRules[c].copy(enabled = true, minOrbs = 4)
                    }
                }

                "zero_trail_burning" -> {
                    // Pack: low 8 bits = trailLength (0=Max, 6=6格), high bits = maxTrailHurts (0..10)
                    val tLen = chip.paramInt and 0xFF
                    val maxHurts = (chip.paramInt ushr 8) and 0xFF
                    cfg = cfg.copy(trailBurningEnabled = true, trailLength = tLen, maxTrailHurts = maxHurts)
                }
                "trail_corrosion" -> cfg = cfg.copy(
                    trailCorrosionEnabled = true,
                    requiredUniqueCellsVisited = maxOf(cfg.requiredUniqueCellsVisited, chip.paramInt)
                )
                "second_wave_combo" -> {
                    cfg = cfg.copy(
                        minSecondWaveCombo = maxOf(cfg.minSecondWaveCombo, chip.paramInt.coerceAtLeast(1)),
                        requiredSecondWaveElementsMask = cfg.requiredSecondWaveElementsMask or chip.paramElementMask
                    )
                    highDifficultyShield = true
                }
                "equal_pair_light_dark" -> {
                    // Official Skill #9 (連擊相等盾): supports generalized 2..6 colors when bit 8 (0x100) is set,
                    // while remaining 100% backward-compatible with legacy 2-element nibble packing (a | (b<<4)).
                    if ((chip.paramElementMask and 0x100) != 0) {
                        val mask = chip.paramElementMask and 0x3F
                        val picked = (0..5).filter { (mask and (1 shl it)) != 0 }
                        val elemA = picked.getOrElse(0) { Element.LIGHT.code }
                        val elemB = picked.getOrElse(1) { Element.DARK.code }
                        cfg = cfg.copy(
                            requireEqualPairCombos = true,
                            equalPairElemA = elemA,
                            equalPairElemB = elemB,
                            equalComboElementsMask = mask,
                            equalPairMustBeNonZero = true
                        )
                    } else {
                        val elemA = if (chip.paramElementMask != 0) (chip.paramElementMask and 0x0F).coerceIn(0, 5) else Element.LIGHT.code
                        val elemB = if (chip.paramElementMask != 0) ((chip.paramElementMask ushr 4) and 0x0F).coerceIn(0, 5) else Element.DARK.code
                        cfg = cfg.copy(
                            requireEqualPairCombos = true,
                            equalPairElemA = elemA,
                            equalPairElemB = elemB,
                            equalComboElementsMask = (1 shl elemA) or (1 shl elemB),
                            equalPairMustBeNonZero = true
                        )
                    }
                    highDifficultyShield = true
                }
                "same_elem_combos" -> {
                    val targetElem = if (chip.paramElementMask != 0) {
                        (0 until 6).firstOrNull { (chip.paramElementMask and (1 shl it)) != 0 } ?: -1
                    } else -1
                    cfg = cfg.copy(
                        requiredSameElementCombos = maxOf(cfg.requiredSameElementCombos, chip.paramInt.coerceAtLeast(2)),
                        sameElementComboTarget = targetElem
                    )
                    highDifficultyShield = true
                }
                "any_enchanted_orb_shield" -> {
                    cfg = cfg.copy(requireAnyEnchantedOrb = true)
                    highDifficultyShield = true
                }
                "race_orb_shield" -> {
                    val raceTypesMask = chip.paramInt and 0x7F
                    cfg = cfg.copy(
                        requiredRaceElementsMask = cfg.requiredRaceElementsMask or chip.paramElementMask,
                        requiredRaceTypesMask = cfg.requiredRaceTypesMask or raceTypesMask,
                        requireAnyRaceOrb = chip.paramElementMask == 0 && raceTypesMask == 0
                    )
                    highDifficultyShield = true
                }
                "char_orb_first_wave" -> {
                    cfg = cfg.copy(requireCharOrbFirstWave = true)
                    highDifficultyShield = true
                }
                "starting_buoy_shield" -> {
                    cfg = cfg.copy(
                        autoDetectStartingBuoy = true,
                        requireCharOrbFirstWave = true
                    )
                    highDifficultyShield = true
                }
                "forbid_weakened_orb" -> {
                    cfg = cfg.copy(forbidWeakenedOrbs = true)
                }
                "luminous_orb_shield" -> {
                    cfg = cfg.copy(requireLuminousOrbClear = true, prioritizeLuminous = true)
                }
                "maximize_element_orbs" -> cfg = cfg.copy(maximizeElementOrbIndex = chip.paramInt)
                "traffic_light" -> {
                    cfg = cfg.copy(startPreset = StartPointPreset.ANY)
                    highDifficultyShield = true
                }
                "char_orb_terrain" -> {
                    cfg = cfg.copy(requireCharOrbPass = true, requireCharOrbEnd = true)
                    highDifficultyShield = true
                }
                "step_limit" -> cfg = cfg.copy(maxSteps = minOf(cfg.maxSteps, chip.paramInt.coerceAtLeast(1)))
                "step_range" -> {
                    // Official Skill #60 (步數盾): low byte = minSteps, high byte = maxSteps (e.g. 25..30)
                    val lo = (chip.paramInt and 0xFF).coerceIn(1, 200)
                    val hi = ((chip.paramInt ushr 8) and 0xFF).coerceIn(lo, 200)
                    cfg = cfg.copy(minSteps = maxOf(cfg.minSteps, lo), maxSteps = hi, useSystemSteps = false)
                    highDifficultyShield = true
                }
                "exact_steps" -> cfg = cfg.copy(exactSteps = chip.paramInt, maxSteps = maxOf(cfg.maxSteps, chip.paramInt))
                "step_hurt_minimize" -> cfg = cfg.copy(stepPenaltyWeight = 5.0)
                "fast_first_result" -> cfg = cfg.copy(earlyStopOnGoal = true)
                "weijian_burst" -> cfg = cfg.copy(maxSteps = maxOf(cfg.maxSteps, 60), beamWidth = maxOf(cfg.beamWidth, 220))
            }
        }

        // Auto-enable puzzle rules if board already has auto-detected/marked puzzle tiles
        if (board != null) {
            val hasP1 = board.tileFlags.any { (it and TileFlagBits.PUZZLE_1) != 0 }
            val hasP2 = board.tileFlags.any { (it and TileFlagBits.PUZZLE_2) != 0 }
            if (hasP1 && !cfg.puzzle1.enabled) {
                cfg = cfg.copy(puzzle1 = cfg.puzzle1.copy(enabled = true))
                highDifficultyShield = true
            }
            if (hasP2 && !cfg.puzzle2.enabled) {
                cfg = cfg.copy(puzzle2 = cfg.puzzle2.copy(enabled = true))
                highDifficultyShield = true
            }
            val hasTrafficStart = board.tileFlags.any { (it and TileFlagBits.START_ALLOWED) != 0 }
            val hasTrafficEnd = board.tileFlags.any { (it and TileFlagBits.END_REQUIRED) != 0 }
            if (hasTrafficStart || hasTrafficEnd) {
                if (hasTrafficStart) {
                    cfg = cfg.copy(startPreset = StartPointPreset.ANY)
                }
                highDifficultyShield = true
            }
            // Official ToS 【起動浮標】 (No. 5893 / 7403) & 【消除起動浮標角色符石盾】 (No. 5894):
            // When a portrait Starting Buoy is detected (or painted in Board Editor) and no board-cell start vortex
            // overrides it, automatically start from that portrait's Character Runestone with its real attribute!
            val hasBuoyChip = activeChips.any { it.id == "starting_buoy_shield" }
            if (!hasTrafficStart &&
                board.startingBuoyCol in 0 until board.cols &&
                (cfg.autoDetectStartingBuoy || hasBuoyChip)
            ) {
                val resolvedEl = if (board.startingBuoyElement.isMatchable) {
                    board.startingBuoyElement
                } else {
                    cfg.characterOrbElement
                }
                // Auto-enforce Wave-1 elimination of the buoyed Character Runestone (breaks No. 5894 shield & adds
                // free chase attack on No. 5893) whenever that element is not forbidden and >= 3 orbs exist after pull!
                val buoyIdx = board.index(0, board.startingBuoyCol)
                val otherSameCount = board.cells.indices.count { i ->
                    i != buoyIdx && board.cells[i].element == resolvedEl
                }
                val elRule = elemRules.getOrNull(resolvedEl.code)
                val elForbidden = elRule != null &&
                    (elRule.timing == MatchTiming.FORBID_FIRST_WAVE_ALLOW_CASCADE || elRule.timing == MatchTiming.FORBID_ALL)
                val autoRequireClear = !elForbidden && otherSameCount >= 2
                cfg = cfg.copy(
                    useCharacterOrb = true,
                    characterOrbCol = board.startingBuoyCol,
                    characterOrbElement = resolvedEl,
                    startPreset = StartPointPreset.ANY,
                    requireCharOrbFirstWave = cfg.requireCharOrbFirstWave || hasBuoyChip || autoRequireClear
                )
                highDifficultyShield = true
            } else if (cfg.useCharacterOrb &&
                cfg.characterOrbAutoElement &&
                board.startingBuoyElement.isMatchable &&
                board.startingBuoyCol == cfg.characterOrbCol
            ) {
                cfg = cfg.copy(characterOrbElement = board.startingBuoyElement)
            }
        }

        // Cooperative auto-tuning for hard composite shields:
        // Strictly preserve user's allowDiagonalMove choice (never force 8-way diagonals when disabled!).
        if (highDifficultyShield) {
            cfg = cfg.copy(
                beamWidth = maxOf(cfg.beamWidth, 260),
                maxTimeMs = maxOf(cfg.maxTimeMs, 800)
            )
        }

        return cfg.copy(elementRules = elemRules, columnRules = colRules)
    }
}
