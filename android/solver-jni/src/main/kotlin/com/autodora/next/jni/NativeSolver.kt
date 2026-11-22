package com.autodora.next.jni

import com.autodora.next.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class NativeCancelToken internal constructor(private var ptr: Long) : AutoCloseable {
    fun cancel() {
        if (ptr != 0L) NativeSolver.cancelInternal(ptr)
    }

    internal fun rawPtr(): Long = ptr

    override fun close() {
        if (ptr != 0L) {
            NativeSolver.destroyTokenInternal(ptr)
            ptr = 0L
        }
    }
}

object NativeSolver {
    init {
        System.loadLibrary("autodora_jni")
    }

    fun createCancelToken(): NativeCancelToken = NativeCancelToken(nativeCreateCancelToken())

    internal fun cancelInternal(ptr: Long) = nativeCancel(ptr)
    internal fun destroyTokenInternal(ptr: Long) = nativeDestroyCancelToken(ptr)

    suspend fun solve(
        board: BoardState,
        rules: RuleConfig,
        cancelToken: NativeCancelToken? = null
    ): SolveOutput = withContext(Dispatchers.Default) {
        val n = board.cols * board.rows
        val elements = IntArray(n) { board.cells[it].element.code }
        val variants = IntArray(n) { board.cells[it].variant.code }
        val hazards = IntArray(n) { board.cells[it].hazards }
        val tileFlags = IntArray(n) { board.tileFlags[it] }
        val checkpoints = board.orderedCheckpoints.toIntArray()

        val ruleInts = IntArray(99)
        ruleInts[0] = if (rules.allowDiagonalMove) 1 else 0
        ruleInts[1] = rules.maxSteps
        ruleInts[2] = rules.exactSteps
        ruleInts[3] = rules.startPreset.code
        ruleInts[4] = if (rules.allowHitWeatheredToClear) 1 else 0
        ruleInts[5] = if (rules.useCharacterOrb) 1 else 0
        ruleInts[6] = rules.characterOrbCol
        ruleInts[7] = rules.characterOrbElement.code
        ruleInts[8] = if (rules.allowDiagonalMatch) 1 else 0
        ruleInts[9] = if (rules.forceSplitThree) 1 else 0
        ruleInts[10] = rules.notEnoughPolicy.code
        ruleInts[11] = rules.fourPlusPolicy.code
        ruleInts[12] = if (rules.preferEnchanted) 1 else 0
        ruleInts[13] = if (rules.preferRace) 1 else 0
        ruleInts[14] = rules.minTotalCombo
        ruleInts[15] = rules.maxTotalCombo
        ruleInts[16] = rules.exactTotalCombo
        ruleInts[17] = rules.minFirstWaveCombo
        ruleInts[18] = rules.exactFirstWaveCombo
        ruleInts[19] = if (rules.firstWaveOnlyScoring) 1 else 0
        ruleInts[20] = rules.comboParity.code
        ruleInts[21] = if (rules.comboParityFirstWaveOnly) 1 else 0
        ruleInts[22] = if (rules.sameComboAcrossEliminatedElements) 1 else 0
        ruleInts[23] = if (rules.avoidSkyfallRisk) 1 else 0
        ruleInts[24] = rules.exactFirstWaveTotalOrbs
        ruleInts[25] = if (rules.requireFirstWaveFullClear) 1 else 0
        ruleInts[26] = rules.requiredFirstWaveElementsMask
        ruleInts[27] = rules.requiredTotalElementsMask
        ruleInts[28] = rules.requiredEnchantedElementsMask
        ruleInts[29] = rules.minDistinctElements
        ruleInts[30] = if (rules.puzzle1.enabled) 1 else 0
        ruleInts[31] = if (rules.puzzle1.strictNoExternalTouch) 1 else 0
        ruleInts[32] = rules.puzzle1.requiredElement
        ruleInts[33] = if (rules.puzzle1.requireEnchanted) 1 else 0
        ruleInts[34] = if (rules.puzzle2.enabled) 1 else 0
        ruleInts[35] = if (rules.puzzle2.strictNoExternalTouch) 1 else 0
        ruleInts[36] = rules.puzzle2.requiredElement
        ruleInts[37] = if (rules.puzzle2.requireEnchanted) 1 else 0
        ruleInts[38] = rules.crossShield.requiredCount
        ruleInts[39] = if (rules.crossShield.strict5Orbs) 1 else 0
        ruleInts[40] = rules.crossShield.requiredElement
        ruleInts[41] = if (rules.crossShield.requireDistinctColors) 1 else 0
        ruleInts[42] = rules.shapeShield.requireLCount
        ruleInts[43] = rules.shapeShield.requireTCount
        ruleInts[44] = rules.shapeShield.requireSquare2x2Count
        ruleInts[45] = if (rules.shapeShield.strictShape) 1 else 0
        ruleInts[46] = if (rules.trailBurningEnabled) 1 else 0
        ruleInts[47] = rules.trailLength
        ruleInts[48] = rules.maxTrailHurts
        ruleInts[49] = rules.maxBurningTileHurts
        ruleInts[50] = if (rules.mustClearElectrified) 1 else 0
        ruleInts[51] = if (rules.mustClearBloodDrain) 1 else 0
        ruleInts[52] = if (rules.mustClearBomb) 1 else 0
        ruleInts[53] = if (rules.prioritizeFrozen) 1 else 0
        ruleInts[54] = if (rules.prioritizePetrifiedClear) 1 else 0
        ruleInts[55] = rules.beamWidth
        ruleInts[56] = rules.maxTimeMs
        ruleInts[57] = rules.numThreads
        ruleInts[58] = rules.topK
        ruleInts[59] = if (rules.earlyStopOnGoal) 1 else 0
        for (e in 0 until 6) {
            ruleInts[60 + e] = rules.compatibleLinkMask.getOrElse(e) { 1 shl e }
        }
        ruleInts[66] = rules.fixedStartCell
        ruleInts[67] = if (rules.requireCharOrbPass) 1 else 0
        ruleInts[68] = if (rules.requireCharOrbEnd) 1 else 0
        ruleInts[69] = rules.minSecondWaveCombo
        ruleInts[70] = rules.requiredSecondWaveElementsMask
        ruleInts[71] = if (rules.requireEqualPairCombos) 1 else 0
        ruleInts[72] = rules.equalPairElemA
        ruleInts[73] = rules.equalPairElemB
        ruleInts[74] = if (rules.equalPairMustBeNonZero) 1 else 0
        ruleInts[75] = rules.requiredSameElementCombos
        ruleInts[76] = rules.sameElementComboTarget
        ruleInts[77] = rules.requiredRaceElementsMask
        ruleInts[78] = if (rules.requireAnyRaceOrb) 1 else 0
        ruleInts[79] = rules.maximizeElementOrbIndex
        ruleInts[80] = if (rules.trailCorrosionEnabled) 1 else 0
        ruleInts[81] = rules.requiredUniqueCellsVisited
        ruleInts[82] = rules.maxMiasmaHits
        ruleInts[83] = if (rules.mustClearFoam) 1 else 0
        ruleInts[84] = if (rules.mustClearLockedHurt) 1 else 0
        ruleInts[85] = if (rules.prioritizeLockedAtkDown) 1 else 0
        ruleInts[86] = if (rules.avoidCorroded) 1 else 0
        ruleInts[87] = if (rules.prioritizeLuminous) 1 else 0
        ruleInts[88] = rules.crossShield.requiredElementsMask
        ruleInts[89] = if (rules.requireAnyEnchantedOrb) 1 else 0
        ruleInts[90] = if (rules.requireCharOrbFirstWave) 1 else 0
        ruleInts[91] = if (rules.forbidWeakenedOrbs) 1 else 0
        ruleInts[92] = if (rules.requireLuminousOrbClear) 1 else 0
        ruleInts[93] = rules.minSteps
        ruleInts[94] = rules.minFirstWaveElementalCombo
        ruleInts[95] = if (rules.disableCascades) 1 else 0
        ruleInts[96] = rules.wave1KindMode.code
        ruleInts[97] = rules.equalComboElementsMask
        ruleInts[98] = rules.unmatchableElementsMask

        val ruleDoubles = doubleArrayOf(rules.stepPenaltyWeight, rules.diagonalPenaltyWeight)

        val elemRuleInts = IntArray(6 * 10)
        val elemWeights = DoubleArray(6) { 1.0 }
        for (e in 0 until 6) {
            val er = rules.elementRules.getOrElse(e) { ElementRule() }
            val b = e * 10
            elemRuleInts[b + 0] = er.minMatchCount
            elemRuleInts[b + 1] = er.targetShape.code
            elemRuleInts[b + 2] = er.priority.code
            elemRuleInts[b + 3] = er.timing.code
            elemRuleInts[b + 4] = if (er.requireEnchanted) 1 else 0
            elemRuleInts[b + 5] = if (er.requireRace) 1 else 0
            elemRuleInts[b + 6] = if (er.clearAllFirstWave) 1 else 0
            elemRuleInts[b + 7] = er.exactFirstWaveCombos
            elemRuleInts[b + 8] = er.exactFirstWaveOrbs
            elemRuleInts[b + 9] = er.minFirstWaveOrbs
            elemWeights[e] = er.customWeight
        }

        val colRules = IntArray(8 * 4)
        for (c in 0 until 8) {
            val cr = rules.columnRules.getOrElse(c) { ColumnAmeliorationRule() }
            val b = c * 4
            colRules[b + 0] = if (cr.enabled) 1 else 0
            colRules[b + 1] = cr.minOrbs
            colRules[b + 2] = if (cr.hardRequired) 1 else 0
            colRules[b + 3] = cr.requiredElement
        }

        val rowRules = IntArray(8 * 3)
        for (r in 0 until 8) {
            val rr = rules.rowRules.getOrElse(r) { RowBonusRule() }
            val b = r * 3
            rowRules[b + 0] = if (rr.enabled) 1 else 0
            rowRules[b + 1] = if (rr.hardRequired) 1 else 0
            rowRules[b + 2] = rr.requiredElement
        }

        val jsonStr = nativeSolve(
            board.cols, board.rows,
            elements, variants, hazards, tileFlags, checkpoints,
            ruleInts, ruleDoubles, elemRuleInts, elemWeights, colRules, rowRules,
            cancelToken?.rawPtr() ?: 0L
        )
        parseSolveOutput(jsonStr)
    }

    private fun parseSolveOutput(jsonStr: String): SolveOutput {
        val root = JSONObject(jsonStr)
        val arr = root.getJSONArray("solutions")
        val sols = buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val startRow = obj.getInt("startRow")
                val startCol = obj.getInt("startCol")
                val movesArr = obj.getJSONArray("moves")
                val moves = List(movesArr.length()) { idx -> Direction.fromCode(movesArr.getInt(idx)) }
                val waypoints = buildList {
                    var r = startRow
                    var c = startCol
                    add(CellCoord(r, c))
                    for (m in moves) {
                        r += m.dr
                        c += m.dc
                        add(CellCoord(r, c))
                    }
                }
                val groupsArr = obj.getJSONArray("groups")
                val groups = List(groupsArr.length()) { idx ->
                    val g = groupsArr.getJSONObject(idx)
                    MatchSummary(
                        element = Element.fromCode(g.getInt("element")),
                        orbCount = g.getInt("orbCount"),
                        wave = g.getInt("wave"),
                        hasEnchanted = g.getBoolean("hasEnchanted")
                    )
                }
                add(
                    SolutionPath(
                        start = CellCoord(startRow, startCol),
                        usedCharacterOrb = obj.getBoolean("usedCharacterOrb"),
                        characterOrbCol = obj.getInt("characterOrbCol"),
                        moves = moves,
                        waypoints = waypoints,
                        stepCount = obj.getInt("stepCount"),
                        firstWaveCombo = obj.getInt("firstWaveCombo"),
                        totalCombo = obj.getInt("totalCombo"),
                        firstWaveOrbs = obj.getInt("firstWaveOrbs"),
                        totalOrbs = obj.getInt("totalOrbs"),
                        allHardConstraintsMet = obj.getBoolean("allHardConstraintsMet"),
                        satisfiedFlags = obj.getInt("satisfiedFlags"),
                        failedFlags = obj.getInt("failedFlags"),
                        score = obj.getDouble("score"),
                        tag = obj.getString("tag"),
                        groups = groups
                    )
                )
            }
        }
        return SolveOutput(
            solutions = sols,
            statesExpanded = root.getLong("statesExpanded"),
            statesEvaluated = root.getLong("statesEvaluated"),
            elapsedMs = root.getDouble("elapsedMs"),
            interrupted = root.getBoolean("interrupted"),
            timedOut = root.getBoolean("timedOut")
        )
    }

    private external fun nativeCreateCancelToken(): Long
    private external fun nativeCancel(tokenPtr: Long)
    private external fun nativeDestroyCancelToken(tokenPtr: Long)
    private external fun nativeSolve(
        cols: Int,
        rows: Int,
        elements: IntArray,
        variants: IntArray,
        hazards: IntArray,
        tileFlags: IntArray,
        checkpoints: IntArray,
        ruleInts: IntArray,
        ruleDoubles: DoubleArray,
        elementRuleInts: IntArray,
        elementWeights: DoubleArray,
        colRules: IntArray,
        rowRules: IntArray,
        cancelTokenPtr: Long
    ): String
}
