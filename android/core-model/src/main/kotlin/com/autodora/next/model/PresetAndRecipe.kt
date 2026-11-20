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

        // Apply Strategy Goal Mode (inherited & upgraded from legacy AutoDora 'mode' selector)
        when (cfg.goalMode) {
            StrategyGoalMode.HIGH_COMBO -> Unit
            StrategyGoalMode.THREE_COLORS -> {
                cfg = cfg.copy(minDistinctElements = maxOf(cfg.minDistinctElements, 3))
            }
            StrategyGoalMode.FOUR_COLORS -> {
                cfg = cfg.copy(minDistinctElements = maxOf(cfg.minDistinctElements, 4))
            }
            StrategyGoalMode.FIVE_COLORS -> {
                cfg = cfg.copy(
                    requiredTotalElementsMask = cfg.requiredTotalElementsMask or FIVE_ELEMENTS_MASK,
                    minDistinctElements = maxOf(cfg.minDistinctElements, 5)
                )
            }
            StrategyGoalMode.FIVE_ORBS_SINGLE -> {
                val targetIdx = elemRules.indexOfFirst { it.priority == ElementPriority.HIGH }.takeIf { it >= 0 } ?: 0
                if (elemRules[targetIdx].targetShape == TargetShape.ANY) {
                    elemRules[targetIdx] = elemRules[targetIdx].copy(targetShape = TargetShape.COUNT_5)
                }
            }
            StrategyGoalMode.FIVE_ORBS_MULTI -> {
                for (e in 0 until 5) {
                    if (elemRules[e].targetShape == TargetShape.ANY && elemRules[e].priority != ElementPriority.RESERVE) {
                        elemRules[e] = elemRules[e].copy(targetShape = TargetShape.COUNT_5)
                    }
                }
            }
            StrategyGoalMode.SIX_ORBS -> {
                for (e in 0 until 5) {
                    if (elemRules[e].targetShape == TargetShape.ANY && elemRules[e].priority != ElementPriority.RESERVE) {
                        elemRules[e] = elemRules[e].copy(targetShape = TargetShape.COUNT_6)
                    }
                }
            }
            StrategyGoalMode.EIGHT_ORBS -> {
                for (e in 0 until 5) {
                    if (elemRules[e].targetShape == TargetShape.ANY && elemRules[e].priority != ElementPriority.RESERVE) {
                        elemRules[e] = elemRules[e].copy(targetShape = TargetShape.COUNT_8)
                    }
                }
            }
        }

        var highDifficultyShield = false

        for (chip in activeChips) {
            when (chip.id) {
                "exact_combo" -> cfg = cfg.copy(exactTotalCombo = chip.paramInt, avoidSkyfallRisk = true)
                "exact_first_combo" -> cfg = cfg.copy(exactFirstWaveCombo = chip.paramInt, avoidSkyfallRisk = true)
                "min_combo" -> cfg = cfg.copy(minTotalCombo = maxOf(cfg.minTotalCombo, chip.paramInt))
                "min_first_combo" -> cfg = cfg.copy(minFirstWaveCombo = maxOf(cfg.minFirstWaveCombo, chip.paramInt))
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
                "same_combo_elements" -> cfg = cfg.copy(sameComboAcrossEliminatedElements = true)

                "min_distinct_elements" -> cfg = cfg.copy(minDistinctElements = maxOf(cfg.minDistinctElements, chip.paramInt.coerceIn(1, 6)))
                "five_elements" -> cfg = cfg.copy(requiredTotalElementsMask = cfg.requiredTotalElementsMask or FIVE_ELEMENTS_MASK)
                "five_elements_enchanted" -> cfg = cfg.copy(
                    requiredTotalElementsMask = cfg.requiredTotalElementsMask or FIVE_ELEMENTS_MASK,
                    requiredEnchantedElementsMask = cfg.requiredEnchantedElementsMask or FIVE_ELEMENTS_MASK
                )
                "three_elements" -> cfg = cfg.copy(requiredTotalElementsMask = cfg.requiredTotalElementsMask or THREE_ELEMENTS_MASK)
                "require_elements" -> {
                    val requireEnch = chip.paramInt == 1
                    cfg = cfg.copy(
                        requiredFirstWaveElementsMask = cfg.requiredFirstWaveElementsMask or chip.paramElementMask,
                        requiredTotalElementsMask = cfg.requiredTotalElementsMask or chip.paramElementMask,
                        requiredEnchantedElementsMask = if (requireEnch) {
                            cfg.requiredEnchantedElementsMask or chip.paramElementMask
                        } else {
                            cfg.requiredEnchantedElementsMask
                        }
                    )
                }
                "require_enchanted_elements" -> cfg = cfg.copy(
                    requiredFirstWaveElementsMask = cfg.requiredFirstWaveElementsMask or chip.paramElementMask,
                    requiredTotalElementsMask = cfg.requiredTotalElementsMask or chip.paramElementMask,
                    requiredEnchantedElementsMask = cfg.requiredEnchantedElementsMask or chip.paramElementMask
                )
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
                    cfg = cfg.copy(
                        crossShield = cfg.crossShield.copy(
                            requiredCount = maxOf(cfg.crossShield.requiredCount, count),
                            requireDistinctColors = cfg.crossShield.requireDistinctColors || distinctColors
                        )
                    )
                    highDifficultyShield = true
                }
                "l_shape" -> cfg = cfg.copy(shapeShield = cfg.shapeShield.copy(requireLCount = maxOf(cfg.shapeShield.requireLCount, 1)))
                "t_shape" -> cfg = cfg.copy(shapeShield = cfg.shapeShield.copy(requireTCount = maxOf(cfg.shapeShield.requireTCount, 1)))
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
                    val elemA = if (chip.paramElementMask != 0) (chip.paramElementMask and 0x0F).coerceIn(0, 5) else Element.LIGHT.code
                    val elemB = if (chip.paramElementMask != 0) ((chip.paramElementMask ushr 4) and 0x0F).coerceIn(0, 5) else Element.DARK.code
                    cfg = cfg.copy(
                        requireEqualPairCombos = true,
                        equalPairElemA = elemA,
                        equalPairElemB = elemB,
                        equalPairMustBeNonZero = true
                    )
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
                "race_orb_shield" -> {
                    cfg = cfg.copy(
                        requiredRaceElementsMask = cfg.requiredRaceElementsMask or chip.paramElementMask,
                        requireAnyRaceOrb = chip.paramElementMask == 0
                    )
                    highDifficultyShield = true
                }
                "maximize_element_orbs" -> cfg = cfg.copy(maximizeElementOrbIndex = chip.paramInt)
                "char_orb_terrain" -> {
                    cfg = cfg.copy(requireCharOrbPass = true, requireCharOrbEnd = true)
                    highDifficultyShield = true
                }
                "step_limit" -> cfg = cfg.copy(maxSteps = minOf(cfg.maxSteps, chip.paramInt.coerceAtLeast(1)))
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
        }

        // Cooperative auto-tuning for hard composite shields
        if (highDifficultyShield) {
            cfg = cfg.copy(
                allowDiagonalMove = true,
                beamWidth = maxOf(cfg.beamWidth, 260),
                maxTimeMs = maxOf(cfg.maxTimeMs, 800)
            )
        }

        return cfg.copy(elementRules = elemRules, columnRules = colRules)
    }
}
