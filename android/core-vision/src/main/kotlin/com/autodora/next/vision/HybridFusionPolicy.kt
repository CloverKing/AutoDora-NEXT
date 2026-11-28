package com.autodora.next.vision

import com.autodora.next.model.BoardState
import com.autodora.next.model.BoardVisualHints
import com.autodora.next.model.CellState
import com.autodora.next.model.Element
import com.autodora.next.model.HazardBits
import com.autodora.next.model.RaceType
import com.autodora.next.model.TileFlagBits
import com.autodora.next.model.Variant
import kotlin.math.max

/**
 * Shared tensor schema constants aligned 1:1 with `ml/schema.py` & `AutoDoraOrbNetV1`.
 * Single output vector length = 47 per cell:
 *   [0]      is_rune (1)
 *   [1..9]   element softmax (9) -> Element.code 0..8
 *   [10..13] variant softmax (4) -> Variant.code 0..3
 *   [14..21] race softmax (8)    -> RaceType.code 0..7
 *   [22..38] hazards sigmoid (17)
 *   [39..46] tile_overlay sigmoid (8)
 */
object OrbNetSchema {
    const val SCHEMA_VERSION = 1
    const val INPUT_SIZE = 64
    const val OUTPUT_DIM = 47

    const val OFFSET_IS_RUNE = 0
    const val OFFSET_ELEMENT = 1
    const val NUM_ELEMENTS = 9
    const val OFFSET_VARIANT = 10
    const val NUM_VARIANTS = 4
    const val OFFSET_RACE = 14
    const val NUM_RACES = 8
    const val OFFSET_HAZARDS = 22
    const val NUM_HAZARDS = 17
    const val OFFSET_TILE_OVERLAY = 39
    const val NUM_TILE_OVERLAYS = 8

    val HAZARD_NAMES = arrayOf(
        "WEATHERED",
        "ELECTRIFIED",
        "FROZEN",
        "ICE_SEALED",
        "BLOOD_DRAIN_LOCKED",
        "PETRIFIED",
        "BOMB",
        "QUESTION",
        "BLACK_WHITE",
        "PURGATORY_FIRE",
        "EVIL_ENERGY",
        "THORN",
        "COAGULATED",
        "FOAM",
        "LUMINOUS",
        "MAGIC_ORB",
        "BLOOD_THORN"
    )

    val HAZARD_BITS_MAP = intArrayOf(
        HazardBits.WEATHERED,
        HazardBits.ELECTRIFIED,
        HazardBits.FROZEN,
        HazardBits.ICE_SEALED,
        HazardBits.BLOOD_DRAIN,
        HazardBits.PETRIFIED,
        HazardBits.BOMB,
        HazardBits.QUESTION,
        HazardBits.BLACK_WHITE,
        HazardBits.PURGATORY_FIRE,
        HazardBits.EVIL_ENERGY,
        HazardBits.THORN,
        HazardBits.COAGULATED,
        HazardBits.FOAM,
        HazardBits.LUMINOUS,
        HazardBits.MAGIC_ORB,
        HazardBits.BLOOD_THORN
    )

    val TILE_OVERLAY_NAMES = arrayOf(
        "PUZZLE",
        "STORM",
        "SHATTERED",
        "BURNING_TILE",
        "SCORCHING_TILE",
        "END_FORBIDDEN_BLOODBATH",
        "BLACK_HOLE",
        "TRAFFIC_LIGHT_MARKER"
    )

    // Mapped tile overlay flags safely fused per cell (PUZZLE & TRAFFIC_LIGHT remain CV geometric authority)
    val TILE_OVERLAY_BITS_MAP = intArrayOf(
        TileFlagBits.NONE,         // PUZZLE handled cleanly by Jordan-curve perimeter solver
        TileFlagBits.STORM,
        TileFlagBits.SHATTERED,
        TileFlagBits.BURNING_TILE,
        TileFlagBits.SCORCHING_TILE,
        TileFlagBits.END_FORBIDDEN,
        TileFlagBits.BLACK_HOLE,
        TileFlagBits.NONE          // TRAFFIC_LIGHT_MARKER handled by board-wide unique Start/End detector
    )

    const val IDX_HAZ_FROZEN = 2
    const val IDX_HAZ_ICE_SEALED = 3
    const val IDX_HAZ_QUESTION = 7
    const val IDX_HAZ_BLACK_WHITE = 8
    const val IDX_OV_STORM = 1
    const val IDX_OV_SHATTERED = 2
    const val IDX_OV_BURNING = 3
    const val IDX_OV_SCORCHING = 4
    const val IDX_OV_BLOODBATH = 5
    const val IDX_OV_BLACK_HOLE = 6
}

data class CellModelPrediction(
    val isRune: Float,
    val elementProbs: FloatArray,
    val variantProbs: FloatArray,
    val raceProbs: FloatArray,
    val hazardProbs: FloatArray,
    val tileOverlayProbs: FloatArray
) {
    fun bestElement(): Pair<Element, Float> {
        var bestIdx = Element.UNKNOWN.code
        var bestP = -1f
        for (i in 0 until OrbNetSchema.NUM_ELEMENTS) {
            if (elementProbs[i] > bestP) {
                bestP = elementProbs[i]
                bestIdx = i
            }
        }
        return Element.fromCode(bestIdx) to bestP
    }

    fun bestMatchableElement(): Pair<Element, Float> {
        var bestIdx = 0
        var bestP = -1f
        for (i in 0 until 6) {
            if (elementProbs[i] > bestP) {
                bestP = elementProbs[i]
                bestIdx = i
            }
        }
        return Element.fromCode(bestIdx) to bestP
    }

    fun bestVariant(): Pair<Variant, Float> {
        var bestIdx = 0
        var bestP = -1f
        for (i in 0 until OrbNetSchema.NUM_VARIANTS) {
            if (variantProbs[i] > bestP) {
                bestP = variantProbs[i]
                bestIdx = i
            }
        }
        return Variant.fromCode(bestIdx) to bestP
    }

    fun bestRace(): Pair<RaceType, Float> {
        var bestIdx = 0
        var bestP = -1f
        for (i in 0 until OrbNetSchema.NUM_RACES) {
            if (raceProbs[i] > bestP) {
                bestP = raceProbs[i]
                bestIdx = i
            }
        }
        return RaceType.fromCode(bestIdx) to bestP
    }

    companion object {
        fun fromOutputRow(row: FloatArray, offset: Int = 0): CellModelPrediction {
            require(row.size >= offset + OrbNetSchema.OUTPUT_DIM) {
                "Expected >= ${offset + OrbNetSchema.OUTPUT_DIM} floats, got ${row.size}"
            }
            val el = FloatArray(OrbNetSchema.NUM_ELEMENTS) { i -> row[offset + OrbNetSchema.OFFSET_ELEMENT + i] }
            val va = FloatArray(OrbNetSchema.NUM_VARIANTS) { i -> row[offset + OrbNetSchema.OFFSET_VARIANT + i] }
            val rc = FloatArray(OrbNetSchema.NUM_RACES) { i -> row[offset + OrbNetSchema.OFFSET_RACE + i] }
            val hz = FloatArray(OrbNetSchema.NUM_HAZARDS) { i -> row[offset + OrbNetSchema.OFFSET_HAZARDS + i] }
            val ov = FloatArray(OrbNetSchema.NUM_TILE_OVERLAYS) { i -> row[offset + OrbNetSchema.OFFSET_TILE_OVERLAY + i] }
            return CellModelPrediction(
                isRune = row[offset + OrbNetSchema.OFFSET_IS_RUNE],
                elementProbs = el,
                variantProbs = va,
                raceProbs = rc,
                hazardProbs = hz,
                tileOverlayProbs = ov
            )
        }
    }
}

data class HybridFusionConfig(
    val minIsRuneProb: Float = 0.35f,
    val overrideLowConfElementProb: Float = 0.62f,
    val overrideNormalElementProb: Float = 0.88f,
    val recoverMonochromeElementProb: Float = 0.56f,
    val acceptHiddenQuestionProb: Float = 0.82f,
    val acceptRaceProb: Float = 0.78f,
    val acceptWeakenedProb: Float = 0.70f,
    val acceptEnchantedProb: Float = 0.78f,
    val hazardThresholds: FloatArray = FloatArray(OrbNetSchema.NUM_HAZARDS) { 0.72f },
    val overlayThresholds: FloatArray = FloatArray(OrbNetSchema.NUM_TILE_OVERLAYS) { 0.74f }
)

/**
 * Confidence-gated fusion between CvBoardRecognizer and LiteRT Multi-Head CNN:
 *  - Preserves CV board-level geometric detectors (Starting Buoy, Jordan-curve Puzzle polygons,
 *    Traffic Light Start/End, and Storm full-column tornado tips).
 *  - Uses CNN to recover elements under Monochrome/Weakened/darkened states, identify all 7 Races,
 *    detect C-class mechanics (Coagulated, Blood-Thorn, Magic Orb, Foam, Luminous, Weathered, Scorching,
 *    Black Hole), and boost A-class hazard accuracy across all 6 element hues.
 */
object HybridFusionPolicy {

    fun fuse(
        cvResult: RecognitionResult,
        predictions: List<CellModelPrediction>,
        totalElapsedMs: Double = cvResult.elapsedMs,
        config: HybridFusionConfig = HybridFusionConfig(),
        visualHints: BoardVisualHints = BoardVisualHints()
    ): RecognitionResult {
        val board = cvResult.board
        val total = board.cols * board.rows
        if (predictions.size != total) return cvResult

        val outCells = ArrayList<CellState>(total)
        val outFlags = board.tileFlags.toMutableList()
        val lowConf = ArrayList<Int>()
        var confSum = 0f
        var questionCount = 0
        var raceCount = 0

        for (idx in 0 until total) {
            val cvCell = board.cells[idx]
            val cvFlag = outFlags[idx]
            val pred = predictions[idx]

            val isStormCv = (cvFlag and TileFlagBits.STORM) != 0
            val isShatteredCv = (cvFlag and TileFlagBits.SHATTERED) != 0

            // Skip CNN fusion when CV already locked Storm/Shattered or CNN says non-board pixel
            if (isStormCv || isShatteredCv || pred.isRune < config.minIsRuneProb) {
                outCells.add(cvCell)
                confSum += cvCell.confidence
                if (!isStormCv && !isShatteredCv && (cvCell.element == Element.HIDDEN || (cvCell.hazards and HazardBits.QUESTION) != 0)) {
                    questionCount++
                }
                if (cvCell.variant == Variant.RACE || cvCell.race != RaceType.NONE) raceCount++
                if (!isStormCv && !isShatteredCv && cvCell.confidence < CvBoardRecognizer.LOW_CONFIDENCE_THRESHOLD) {
                    lowConf.add(idx)
                }
                continue
            }

            // 1) Fuse per-cell tile overlays (Scorching, Black Hole, Bloodbath, Burning, Shattered, Storm)
            //    modulated by 18-dim BoardVisualHints (SkillPriorVisionFilter)
            var fusedFlags = cvFlag
            for (oi in 0 until OrbNetSchema.NUM_TILE_OVERLAYS) {
                val bit = OrbNetSchema.TILE_OVERLAY_BITS_MAP[oi]
                if (bit == TileFlagBits.NONE) continue
                val baseTh = config.overlayThresholds[oi].coerceIn(0.40f, 1.05f)
                val priorDelta = when (oi) {
                    OrbNetSchema.IDX_OV_BURNING -> if (visualHints.hasBurning) -0.10f else 0f
                    OrbNetSchema.IDX_OV_SCORCHING -> if (visualHints.hasScorching) -0.10f else 0f
                    OrbNetSchema.IDX_OV_BLOODBATH -> if (visualHints.hasBloodbath) -0.10f else 0f
                    OrbNetSchema.IDX_OV_BLACK_HOLE -> if (visualHints.hasBlackHole) -0.10f else 0f
                    OrbNetSchema.IDX_OV_SHATTERED -> if (visualHints.hasShattered) -0.10f else 0f
                    else -> 0f
                }
                val th = if (baseTh > 1.0f) baseTh else (baseTh + priorDelta).coerceIn(0.42f, 0.99f)
                if (pred.tileOverlayProbs[oi] >= th) {
                    fusedFlags = fusedFlags or bit
                }
            }
            if (visualHints.hasBarrier && (fusedFlags and TileFlagBits.SHATTERED) == 0 && (fusedFlags and TileFlagBits.STORM) == 0) {
                // Preserve CV barrier detections if any
            }
            val nowShattered = (fusedFlags and TileFlagBits.SHATTERED) != 0
            if (nowShattered) {
                outFlags[idx] = (fusedFlags and TileFlagBits.STORM.inv())
                val sCell = CellState(
                    element = Element.EMPTY,
                    variant = Variant.NORMAL,
                    hazards = HazardBits.NONE,
                    confidence = max(cvCell.confidence, pred.tileOverlayProbs[OrbNetSchema.IDX_OV_SHATTERED]),
                    race = RaceType.NONE
                )
                outCells.add(sCell)
                confSum += sCell.confidence
                continue
            }
            outFlags[idx] = fusedFlags

            // 2) Fuse hazards (preserving CV detections + adding high-confidence CNN hazards + SkillPriorVisionFilter)
            var fusedHazards = cvCell.hazards
            for (hi in 0 until OrbNetSchema.NUM_HAZARDS) {
                val bit = OrbNetSchema.HAZARD_BITS_MAP[hi]
                if (bit == HazardBits.NONE) continue
                val baseTh = config.hazardThresholds[hi].coerceIn(0.40f, 1.05f)
                val priorDelta = when (bit) {
                    HazardBits.ELECTRIFIED -> if (visualHints.hasElectrified) -0.12f else 0f
                    HazardBits.PURGATORY_FIRE -> if (visualHints.hasPurgatoryFire) -0.12f else 0f
                    HazardBits.FROZEN, HazardBits.ICE_SEALED, HazardBits.PETRIFIED ->
                        if (visualHints.hasFrozenOrPetrified) -0.10f else 0f
                    HazardBits.WEATHERED, HazardBits.BOMB ->
                        if (visualHints.hasWeatheredOrBomb) -0.10f else 0f
                    else -> 0f
                }
                val th = if (baseTh > 1.0f) baseTh else (baseTh + priorDelta).coerceIn(0.45f, 0.99f)
                if (pred.hazardProbs[hi] >= th) {
                    fusedHazards = fusedHazards or bit
                }
            }
            fusedHazards = SkillPriorVisionFilter.filterCellHazards(fusedHazards, pred, visualHints)

            // 3) Fuse element
            val (cnnEl, cnnElProb) = pred.bestElement()
            val (cnnMatchEl, cnnMatchProb) = pred.bestMatchableElement()
            val isMono = (fusedHazards and HazardBits.BLACK_WHITE) != 0 || visualHints.hasMonochrome
            val isCvQuestionOrUnknown = !cvCell.element.isMatchable ||
                (cvCell.hazards and (HazardBits.QUESTION or HazardBits.BLACK_WHITE)) != 0

            val cvMonoProbs = cvResult.monochromeCellProbs?.getOrNull(idx)
            val hasCvMonoProbs = cvMonoProbs != null && cvMonoProbs.size >= 6 && cvMonoProbs.any { it > 0f }

            var fusedElement = cvCell.element
            var fusedConf = cvCell.confidence

            when {
                isMono && hasCvMonoProbs && cnnMatchProb >= 0.35f -> {
                    var bestE = 0
                    var bestJoint = -1f
                    for (e in 0 until 6) {
                        val jp = 0.50f * pred.elementProbs[e] + 0.50f * cvMonoProbs!![e]
                        if (jp > bestJoint) {
                            bestJoint = jp
                            bestE = e
                        }
                    }
                    fusedElement = Element.fromCode(bestE)
                    fusedConf = max(cvCell.confidence, max(cnnMatchProb, bestJoint))
                    fusedHazards = (fusedHazards and HazardBits.QUESTION.inv()) or HazardBits.BLACK_WHITE
                }
                isMono && cnnMatchProb >= config.recoverMonochromeElementProb -> {
                    fusedElement = cnnMatchEl
                    fusedConf = max(cvCell.confidence, cnnMatchProb)
                    fusedHazards = fusedHazards and HazardBits.QUESTION.inv()
                }
                isCvQuestionOrUnknown && cnnEl.isMatchable && cnnElProb >= config.overrideLowConfElementProb -> {
                    fusedElement = cnnEl
                    fusedConf = cnnElProb
                    fusedHazards = fusedHazards and HazardBits.QUESTION.inv()
                }
                cvCell.confidence < 0.70f && cnnEl.isMatchable && cnnElProb >= config.overrideLowConfElementProb -> {
                    fusedElement = cnnEl
                    fusedConf = cnnElProb
                }
                cvCell.element.isMatchable &&
                    cnnEl == Element.HIDDEN &&
                    cnnElProb >= config.acceptHiddenQuestionProb &&
                    (fusedHazards and (HazardBits.ELECTRIFIED or HazardBits.FROZEN or HazardBits.ICE_SEALED or HazardBits.BOMB or HazardBits.PURGATORY_FIRE or HazardBits.EVIL_ENERGY)) == 0 -> {
                    fusedElement = Element.HIDDEN
                    fusedHazards = fusedHazards or HazardBits.QUESTION
                    fusedConf = 0.30f
                }
                cvCell.element.isMatchable &&
                    cnnEl.isMatchable &&
                    cnnEl != cvCell.element &&
                    cvCell.confidence < 0.90f &&
                    cnnElProb >= config.overrideNormalElementProb -> {
                    fusedElement = cnnEl
                    fusedConf = cnnElProb
                }
                cnnEl == cvCell.element && cnnEl.isMatchable -> {
                    fusedConf = max(cvCell.confidence, cnnElProb)
                }
            }

            // If cell was confirmed dark Bloodbath/Question cleanly, sync confidence
            val isDarkTerrain = (fusedFlags and (TileFlagBits.END_FORBIDDEN or TileFlagBits.BLACK_HOLE)) != 0
            if (isDarkTerrain && fusedElement == Element.HIDDEN) {
                fusedHazards = fusedHazards and HazardBits.QUESTION.inv()
                fusedConf = max(fusedConf, 0.80f)
            }

            // 4) Fuse exact 7-class Race & Variant (Weakened / Enchanted / Race)
            val (cnnRace, cnnRaceProb) = pred.bestRace()
            val fusedRace = if (cnnRace != RaceType.NONE && cnnRaceProb >= config.acceptRaceProb) {
                cnnRace
            } else {
                cvCell.race
            }

            val pWeakened = pred.variantProbs[Variant.WEAKENED.code]
            val pEnchanted = pred.variantProbs[Variant.ENCHANTED.code]
            val flashHazardMask = HazardBits.ELECTRIFIED or HazardBits.PURGATORY_FIRE or
                HazardBits.FROZEN or HazardBits.ICE_SEALED or HazardBits.PETRIFIED
            val fusedVariant = when {
                cvCell.variant == Variant.WEAKENED || pWeakened >= config.acceptWeakenedProb -> Variant.WEAKENED
                cvCell.variant == Variant.ENCHANTED -> Variant.ENCHANTED
                pEnchanted >= config.acceptEnchantedProb && (fusedHazards and flashHazardMask) == 0 -> Variant.ENCHANTED
                fusedRace != RaceType.NONE || cvCell.variant == Variant.RACE -> Variant.RACE
                else -> Variant.NORMAL
            }

            val fusedCell = CellState(
                element = fusedElement,
                variant = fusedVariant,
                hazards = fusedHazards,
                confidence = fusedConf.coerceIn(0f, 1f),
                race = fusedRace
            )
            outCells.add(fusedCell)

            confSum += fusedCell.confidence
            if (!isDarkTerrain && (fusedCell.element == Element.HIDDEN || (fusedCell.hazards and HazardBits.QUESTION) != 0)) {
                questionCount++
            }
            if (fusedCell.variant == Variant.RACE || fusedCell.race != RaceType.NONE) {
                raceCount++
            }
            if (!isDarkTerrain && fusedCell.confidence < CvBoardRecognizer.LOW_CONFIDENCE_THRESHOLD) {
                lowConf.add(idx)
            }
        }

        val stormCount = outFlags.count { (it and TileFlagBits.STORM) != 0 }
        val fusedBoard = board.copy(cells = outCells, tileFlags = outFlags)
        return cvResult.copy(
            board = fusedBoard,
            averageConfidence = if (total > 0) confSum / total else 0f,
            lowConfidenceIndices = lowConf,
            engineName = "Hybrid-LiteRT+CV-v1",
            elapsedMs = totalElapsedMs,
            questionOrbsDetected = questionCount,
            raceBadgesDetected = raceCount,
            stormCellsDetected = stormCount,
            petrifiedOrbsDetected = fusedBoard.cells.count { (it.hazards and HazardBits.PETRIFIED) != 0 },
            bloodDrainOrbsDetected = fusedBoard.cells.count {
                (it.hazards and (HazardBits.BLOOD_DRAIN or HazardBits.LOCKED_HURT)) != 0
            },
            thornOrbsDetected = fusedBoard.cells.count { (it.hazards and HazardBits.THORN) != 0 },
            electrifiedOrbsDetected = fusedBoard.cells.count { (it.hazards and HazardBits.ELECTRIFIED) != 0 },
            frozenOrbsDetected = fusedBoard.cells.count {
                (it.hazards and (HazardBits.FROZEN or HazardBits.ICE_SEALED)) != 0
            },
            bombOrbsDetected = fusedBoard.cells.count { (it.hazards and HazardBits.BOMB) != 0 },
            evilEnergyOrbsDetected = fusedBoard.cells.count { (it.hazards and HazardBits.EVIL_ENERGY) != 0 },
            purgatoryFireOrbsDetected = fusedBoard.cells.count { (it.hazards and HazardBits.PURGATORY_FIRE) != 0 },
            burningTilesDetected = outFlags.count {
                (it and (TileFlagBits.BURNING_TILE or TileFlagBits.SCORCHING_TILE)) != 0
            },
            bloodbathTilesDetected = outFlags.count { (it and TileFlagBits.END_FORBIDDEN) != 0 },
            shatteredTilesDetected = outFlags.count { (it and TileFlagBits.SHATTERED) != 0 },
            blackWhiteOrbsDetected = fusedBoard.cells.count { (it.hazards and HazardBits.BLACK_WHITE) != 0 }
        )
    }
}

/**
 * Enemy-skill prior filter resolving visually ambiguous runestone effects (e.g. Electrified vs
 * Purgatory Fire / Frozen / Ice-Sealed across Dark, Heart, and Water orbs) using the current
 * wave's [BoardVisualHints] and enforcing ToS physical mutual-exclusion groups.
 */
object SkillPriorVisionFilter {

    private const val IDX_HAZ_ELECTRIFIED = 1
    private const val IDX_HAZ_PURGATORY = 9

    fun filterCellHazards(
        rawHazards: Int,
        pred: CellModelPrediction,
        hints: BoardVisualHints
    ): Int {
        var h = rawHazards

        // 1. Physical mutual exclusion: Frozen vs Ice-Sealed
        if ((h and HazardBits.FROZEN) != 0 && (h and HazardBits.ICE_SEALED) != 0) {
            if (pred.hazardProbs[OrbNetSchema.IDX_HAZ_ICE_SEALED] > pred.hazardProbs[OrbNetSchema.IDX_HAZ_FROZEN]) {
                h = h and HazardBits.FROZEN.inv()
            } else {
                h = h and HazardBits.ICE_SEALED.inv()
            }
        }

        // 2. Physical mutual exclusion: Electrified vs Purgatory Fire
        if ((h and HazardBits.ELECTRIFIED) != 0 && (h and HazardBits.PURGATORY_FIRE) != 0) {
            val preferPurg = hints.hasPurgatoryFire && !hints.hasElectrified ||
                (!hints.hasElectrified && pred.hazardProbs[IDX_HAZ_PURGATORY] > pred.hazardProbs[IDX_HAZ_ELECTRIFIED])
            h = if (preferPurg) {
                h and HazardBits.ELECTRIFIED.inv()
            } else {
                h and HazardBits.PURGATORY_FIRE.inv()
            }
        }

        // 3. Cross-talk suppression when explicit enemy-skill visual hints are active:
        if (hints.hasElectrified) {
            if (!hints.hasPurgatoryFire && (h and HazardBits.PURGATORY_FIRE) != 0 &&
                pred.hazardProbs[IDX_HAZ_PURGATORY] < 0.95f
            ) {
                h = (h and HazardBits.PURGATORY_FIRE.inv()) or HazardBits.ELECTRIFIED
            }
            if (!hints.hasFrozenOrPetrified) {
                if ((h and HazardBits.FROZEN) != 0 && pred.hazardProbs[OrbNetSchema.IDX_HAZ_FROZEN] < 0.95f) {
                    h = h and HazardBits.FROZEN.inv()
                }
                if ((h and HazardBits.ICE_SEALED) != 0 && pred.hazardProbs[OrbNetSchema.IDX_HAZ_ICE_SEALED] < 0.95f) {
                    h = h and HazardBits.ICE_SEALED.inv()
                }
            }
        } else if (hints.hasPurgatoryFire && !hints.hasElectrified) {
            if ((h and HazardBits.ELECTRIFIED) != 0 && pred.hazardProbs[IDX_HAZ_ELECTRIFIED] < 0.95f) {
                h = (h and HazardBits.ELECTRIFIED.inv()) or HazardBits.PURGATORY_FIRE
            }
        }

        return h
    }
}
