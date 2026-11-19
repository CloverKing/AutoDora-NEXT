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

        var highDifficultyShield = false

        for (chip in activeChips) {
            when (chip.id) {
                "exact_combo" -> cfg = cfg.copy(exactTotalCombo = chip.paramInt, avoidSkyfallRisk = true)
                "exact_first_combo" -> cfg = cfg.copy(exactFirstWaveCombo = chip.paramInt, avoidSkyfallRisk = true)
                "min_combo" -> cfg = cfg.copy(minTotalCombo = maxOf(cfg.minTotalCombo, chip.paramInt))
                "zero_combo" -> cfg = cfg.copy(exactTotalCombo = 0, maxSteps = minOf(cfg.maxSteps, 6), earlyStopOnGoal = true)
                "odd_combo" -> cfg = cfg.copy(comboParity = ComboParity.ODD, avoidSkyfallRisk = true)
                "even_combo" -> cfg = cfg.copy(comboParity = ComboParity.EVEN, avoidSkyfallRisk = true)
                "first_wave_only" -> cfg = cfg.copy(firstWaveOnlyScoring = true)
                "same_combo_elements" -> cfg = cfg.copy(sameComboAcrossEliminatedElements = true)

                "five_elements" -> cfg = cfg.copy(requiredTotalElementsMask = cfg.requiredTotalElementsMask or FIVE_ELEMENTS_MASK)
                "five_elements_enchanted" -> cfg = cfg.copy(
                    requiredTotalElementsMask = cfg.requiredTotalElementsMask or FIVE_ELEMENTS_MASK,
                    requiredEnchantedElementsMask = cfg.requiredEnchantedElementsMask or FIVE_ELEMENTS_MASK
                )
                "three_elements" -> cfg = cfg.copy(requiredTotalElementsMask = cfg.requiredTotalElementsMask or THREE_ELEMENTS_MASK)
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
                    val count = chip.paramInt.coerceIn(1, 3)
                    cfg = cfg.copy(crossShield = cfg.crossShield.copy(requiredCount = maxOf(cfg.crossShield.requiredCount, count)))
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

                "zero_trail_burning" -> cfg = cfg.copy(trailBurningEnabled = true, trailLength = chip.paramInt, maxTrailHurts = 0)
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
