package com.autodora.next.model

import kotlinx.serialization.Serializable

@Serializable
enum class ElementPriority(val code: Int) {
    RESERVE(-1),
    NORMAL(0),
    HIGH(1)
}

@Serializable
enum class MatchTiming(val code: Int) {
    ANY(0),
    MUST_FIRST_WAVE(1),
    FORBID_FIRST_WAVE_ALLOW_CASCADE(2),
    FORBID_ALL(3)
}

@Serializable
enum class TargetShape(val code: Int) {
    ANY(0),
    SPLIT_3(1),
    COUNT_2(2),
    COUNT_4(3),
    COUNT_5(4),
    COUNT_6(5),
    COUNT_7(6),
    COUNT_8(7),
    COUNT_9(8),
    ROW(9),
    COLUMN(10),
    CROSS(11),
    L_SHAPE(12),
    T_SHAPE(13),
    SQUARE_2X2(14),
    CLEAR_ALL(15)
}

@Serializable
enum class NotEnoughPolicy(val code: Int) {
    MATCH_ANYWAY(0),
    KEEP(1)
}

@Serializable
enum class FourPlusPolicy(val code: Int) {
    MAX_COMBO(0),
    MAX_ORBS(1),
    SINGLE_GROUP(2)
}

@Serializable
enum class StartPointPreset(val code: Int) {
    ANY(0),
    BOTTOM_LEFT(1),
    BOTTOM_RIGHT(2)
}

@Serializable
enum class ComboParity(val code: Int) {
    ANY(0),
    ODD(1),
    EVEN(2)
}

/**
 * Legacy AutoDora-inspired high-level global goal modes (全局求解目标模式).
 * Stored inside each Strategy Preset so players can express team goals cleanly.
 */
@Serializable
enum class StrategyGoalMode(val label: String, val shortLabel: String) {
    HIGH_COMBO("高连击", "高连击"),
    // Grouped parameterized goal modes (v2)
    N_COLOR_BALANCE("多色均衡", "多色均衡"),
    N_COLOR_ALL("多色全消", "多色全消"),
    SINGLE_COLOR_X_ORBS("单色多珠", "单色多珠"),
    MULTI_COLOR_X_ORBS("多色多珠", "多色多珠"),
    // Legacy aliases retained for seamless Room JSON migration
    THREE_COLORS("三色均衡", "三色"),
    FOUR_COLORS("四色均衡", "四色"),
    FIVE_COLORS("五色全消", "五色"),
    FIVE_ORBS_SINGLE("单色五珠", "单色5珠"),
    FIVE_ORBS_MULTI("多色五珠", "多色5珠"),
    SIX_ORBS("消六珠", "消6珠"),
    SEVEN_ORBS("消七珠", "消7珠"),
    EIGHT_ORBS("消八珠", "消8珠"),
    NINE_ORBS("消九珠", "消9珠");

    fun normalizedGroup(): StrategyGoalMode = when (this) {
        HIGH_COMBO -> HIGH_COMBO
        N_COLOR_BALANCE, THREE_COLORS, FOUR_COLORS -> N_COLOR_BALANCE
        N_COLOR_ALL, FIVE_COLORS -> N_COLOR_ALL
        SINGLE_COLOR_X_ORBS, FIVE_ORBS_SINGLE -> SINGLE_COLOR_X_ORBS
        MULTI_COLOR_X_ORBS, FIVE_ORBS_MULTI, SIX_ORBS, SEVEN_ORBS, EIGHT_ORBS, NINE_ORBS -> MULTI_COLOR_X_ORBS
    }

    fun defaultParamN(): Int = when (this) {
        THREE_COLORS -> 3
        FOUR_COLORS -> 4
        FIVE_COLORS, FIVE_ORBS_MULTI, SIX_ORBS, SEVEN_ORBS, EIGHT_ORBS, NINE_ORBS -> 5
        N_COLOR_ALL -> 5
        else -> 3
    }

    fun defaultParamX(): Int = when (this) {
        SIX_ORBS -> 6
        SEVEN_ORBS -> 7
        EIGHT_ORBS -> 8
        NINE_ORBS -> 9
        else -> 5
    }
}

@Serializable
data class ElementRule(
    val minMatchCount: Int = 3,
    val targetShape: TargetShape = TargetShape.ANY,
    val priority: ElementPriority = ElementPriority.NORMAL,
    val timing: MatchTiming = MatchTiming.ANY,
    val customWeight: Double = 1.0,
    val requireEnchanted: Boolean = false,
    val requireRace: Boolean = false,
    val clearAllFirstWave: Boolean = false,
    val exactFirstWaveCombos: Int = -1,
    val exactFirstWaveOrbs: Int = -1,
    val minFirstWaveOrbs: Int = 0
)

@Serializable
data class ColumnAmeliorationRule(
    val enabled: Boolean = false,
    val minOrbs: Int = 4,
    val hardRequired: Boolean = false,
    val requiredElement: Int = -1
)

@Serializable
data class RowBonusRule(
    val enabled: Boolean = false,
    val hardRequired: Boolean = false,
    val requiredElement: Int = -1
)

@Serializable
data class PuzzleShieldRule(
    val enabled: Boolean = false,
    val strictNoExternalTouch: Boolean = true,
    val requiredElement: Int = -1,
    val requireEnchanted: Boolean = false
)

@Serializable
data class CrossShieldRule(
    val requiredCount: Int = 0,
    val strict5Orbs: Boolean = true,
    val requiredElement: Int = -1,
    val requireDistinctColors: Boolean = false,
    val requiredElementsMask: Int = 0
)

@Serializable
data class ShapeShieldRule(
    val requireLCount: Int = 0,
    val requireTCount: Int = 0,
    val requireSquare2x2Count: Int = 0,
    val strictShape: Boolean = false
)

@Serializable
data class RuleConfig(
    val goalMode: StrategyGoalMode = StrategyGoalMode.HIGH_COMBO,
    val goalParamN: Int = 3,                  // N colors (2..6) for N_COLOR_BALANCE / N_COLOR_ALL / MULTI_COLOR_X_ORBS
    val goalParamX: Int = 5,                  // X orbs (4..9) for SINGLE_COLOR_X_ORBS / MULTI_COLOR_X_ORBS
    val goalTargetElement: Int = -1,          // -1 = auto/high-priority, 0..5 = specific color for SINGLE_COLOR_X_ORBS
    val goalElementsMask: Int = 0,            // Optional specific element bitmask for N_COLOR_ALL / MULTI_COLOR_X_ORBS
    val useSystemSteps: Boolean = true,       // true = inherit System Settings maxSteps; false = Strategy overrides System
    val useSystemBeam: Boolean = true,        // true = inherit System Settings beamWidth; false = Strategy overrides System
    val allowDiagonalMove: Boolean = true,
    val maxSteps: Int = 30,
    val exactSteps: Int = -1,
    val stepPenaltyWeight: Double = 0.08,
    val diagonalPenaltyWeight: Double = 0.45,
    val startPreset: StartPointPreset = StartPointPreset.ANY,
    val fixedStartCell: Int = -1,
    val allowHitWeatheredToClear: Boolean = false,

    val useCharacterOrb: Boolean = false,
    val characterOrbCol: Int = 0,
    val characterOrbElement: Element = Element.FIRE,
    val requireCharOrbPass: Boolean = false,
    val requireCharOrbEnd: Boolean = false,

    val allowDiagonalMatch: Boolean = false,
    val compatibleLinkMask: List<Int> = List(6) { 1 shl it },
    val forceSplitThree: Boolean = false,
    val notEnoughPolicy: NotEnoughPolicy = NotEnoughPolicy.MATCH_ANYWAY,
    val fourPlusPolicy: FourPlusPolicy = FourPlusPolicy.MAX_COMBO,
    val preferEnchanted: Boolean = true,
    val preferRace: Boolean = false,

    val elementRules: List<ElementRule> = List(6) { ElementRule() },
    val columnRules: List<ColumnAmeliorationRule> = List(8) { ColumnAmeliorationRule() },
    val rowRules: List<RowBonusRule> = List(8) { RowBonusRule() },

    val minTotalCombo: Int = 0,
    val maxTotalCombo: Int = -1,
    val exactTotalCombo: Int = -1,
    val minFirstWaveCombo: Int = 0,
    val exactFirstWaveCombo: Int = -1,
    val firstWaveOnlyScoring: Boolean = false,
    val comboParity: ComboParity = ComboParity.ANY,
    val comboParityFirstWaveOnly: Boolean = true,
    val sameComboAcrossEliminatedElements: Boolean = false,
    val avoidSkyfallRisk: Boolean = true,
    val minSecondWaveCombo: Int = 0,
    val requiredSecondWaveElementsMask: Int = 0,
    val requireEqualPairCombos: Boolean = false,
    val equalPairElemA: Int = 3,
    val equalPairElemB: Int = 4,
    val equalPairMustBeNonZero: Boolean = true,
    val requiredSameElementCombos: Int = 0,
    val sameElementComboTarget: Int = -1,

    val exactFirstWaveTotalOrbs: Int = -1,
    val requireFirstWaveFullClear: Boolean = false,
    val requiredFirstWaveElementsMask: Int = 0,
    val requiredTotalElementsMask: Int = 0,
    val requiredEnchantedElementsMask: Int = 0,
    val requireAnyEnchantedOrb: Boolean = false,
    val requiredRaceElementsMask: Int = 0,
    val requireAnyRaceOrb: Boolean = false,
    val requireCharOrbFirstWave: Boolean = false,
    val forbidWeakenedOrbs: Boolean = false,
    val requireLuminousOrbClear: Boolean = false,
    val minDistinctElements: Int = 0,
    val maximizeElementOrbIndex: Int = -1,

    val puzzle1: PuzzleShieldRule = PuzzleShieldRule(),
    val puzzle2: PuzzleShieldRule = PuzzleShieldRule(),
    val crossShield: CrossShieldRule = CrossShieldRule(),
    val shapeShield: ShapeShieldRule = ShapeShieldRule(),

    val trailBurningEnabled: Boolean = false,
    val trailLength: Int = 0,
    val maxTrailHurts: Int = 0,
    val maxBurningTileHurts: Int = 0,
    val trailCorrosionEnabled: Boolean = false,
    val requiredUniqueCellsVisited: Int = 0,
    val maxMiasmaHits: Int = 0,
    val mustClearElectrified: Boolean = true,
    val mustClearBloodDrain: Boolean = true,
    val mustClearBomb: Boolean = true,
    val mustClearFoam: Boolean = true,
    val mustClearLockedHurt: Boolean = true,
    val prioritizeLockedAtkDown: Boolean = true,
    val avoidCorroded: Boolean = true,
    val prioritizeLuminous: Boolean = true,
    val prioritizeFrozen: Boolean = true,
    val prioritizePetrifiedClear: Boolean = true,

    val beamWidth: Int = 180,
    val maxTimeMs: Int = 500,
    val numThreads: Int = 0,
    val topK: Int = 5,
    val earlyStopOnGoal: Boolean = false
)

@Serializable
data class MatchSummary(
    val element: Element,
    val orbCount: Int,
    val wave: Int,
    val hasEnchanted: Boolean
)

@Serializable
data class SolutionPath(
    val start: CellCoord,
    val usedCharacterOrb: Boolean,
    val characterOrbCol: Int,
    val moves: List<Direction>,
    val waypoints: List<CellCoord>,
    val stepCount: Int,
    val firstWaveCombo: Int,
    val totalCombo: Int,
    val firstWaveOrbs: Int,
    val totalOrbs: Int,
    val allHardConstraintsMet: Boolean,
    val satisfiedFlags: Int,
    val failedFlags: Int,
    val score: Double,
    val tag: String,
    val groups: List<MatchSummary>
)

@Serializable
data class SolveOutput(
    val solutions: List<SolutionPath>,
    val statesExpanded: Long,
    val statesEvaluated: Long,
    val elapsedMs: Double,
    val interrupted: Boolean,
    val timedOut: Boolean
)
