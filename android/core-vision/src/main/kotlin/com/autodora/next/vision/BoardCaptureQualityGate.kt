package com.autodora.next.vision

import com.autodora.next.model.BoardGeometry
import com.autodora.next.model.Element
import com.autodora.next.model.HazardBits
import com.autodora.next.model.TileFlagBits
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class BoardQualityEvaluation(
    val isCleanStaticPlayable: Boolean,
    val rejectReason: String,
    val emptyHoleCount: Int,
    val comboGlowRatio: Float,
    val p90CenterV: Float
)

enum class StabilityDecision {
    WAIT_FIRST_FRAME,
    SETTLING,
    CAPTURE_DUAL_PAIR,
    ALREADY_CAPTURED_THIS_TURN,
    BOARD_CHANGED_RESET
}

/**
 * Temporal stability state machine ensuring idle training-sample capture collects at most ONE
 * genuine static dual-frame pair (`frame_a`, `frame_b`) per settled turn, rejecting cascades,
 * skyfall, and finger drag animations until the board settles again.
 */
class BoardTurnStabilityTracker(
    private val maxStableMad: Float = 11.5f,
    private val maxStableHamming: Int = 3,
    private val changeTriggerMad: Float = 16.0f,
    private val changeTriggerHamming: Int = 6,
    private val requiredStableTicks: Int = 2
) {
    private var prevHash: Long = 0L
    private var hasPrev: Boolean = false
    private var stableTicks: Int = 0
    private var capturedThisTurn: Boolean = false

    @Synchronized
    fun onStableFrameCheck(curHash: Long, madVsPrev: Float?): StabilityDecision {
        if (!hasPrev || madVsPrev == null) {
            prevHash = curHash
            hasPrev = true
            stableTicks = 1
            return StabilityDecision.WAIT_FIRST_FRAME
        }
        val dist = java.lang.Long.bitCount(curHash xor prevHash)
        prevHash = curHash

        if (madVsPrev >= changeTriggerMad || dist >= changeTriggerHamming) {
            capturedThisTurn = false
            stableTicks = 1
            return StabilityDecision.BOARD_CHANGED_RESET
        }

        if (madVsPrev <= maxStableMad && dist <= maxStableHamming) {
            if (capturedThisTurn) {
                return StabilityDecision.ALREADY_CAPTURED_THIS_TURN
            }
            stableTicks++
            return if (stableTicks >= requiredStableTicks) {
                capturedThisTurn = true
                StabilityDecision.CAPTURE_DUAL_PAIR
            } else {
                StabilityDecision.SETTLING
            }
        }

        // Intermediate jitter (e.g., bright lightning pulse): keep current turn lock state, re-settle before capture
        if (!capturedThisTurn) {
            stableTicks = 1
        }
        return if (capturedThisTurn) {
            StabilityDecision.ALREADY_CAPTURED_THIS_TURN
        } else {
            StabilityDecision.SETTLING
        }
    }

    @Synchronized
    fun reset() {
        prevHash = 0L
        hasPrev = false
        stableTicks = 0
        capturedThisTurn = false
    }
}

/**
 * Pure Kotlin quality gate separating clean, settled, playable battle boards from cascade/skyfall
 * animations, Combo!! explosions, dimmed end-of-turn screens, and non-battle UI (friend lists).
 */
object BoardCaptureQualityGate {

    fun computeBoardDHash(frame: VisionFrame, geometry: BoardGeometry): Long {
        val x0 = geometry.leftPx.roundToInt().coerceIn(0, frame.width - 2)
        val y0 = geometry.topPx.roundToInt().coerceIn(0, frame.height - 2)
        val x1 = geometry.rightPx.roundToInt().coerceIn(x0 + 1, frame.width - 1)
        val y1 = geometry.bottomPx.roundToInt().coerceIn(y0 + 1, frame.height - 1)
        val rw = (x1 - x0).coerceAtLeast(9)
        val rh = (y1 - y0).coerceAtLeast(8)
        val luma = IntArray(9 * 8)
        for (gy in 0 until 8) {
            val py = (y0 + ((gy + 0.5f) / 8f * rh)).toInt().coerceIn(0, frame.height - 1)
            for (gx in 0 until 9) {
                val px = (x0 + ((gx + 0.5f) / 9f * rw)).toInt().coerceIn(0, frame.width - 1)
                val argb = frame.getArgb(px, py)
                val r = (argb ushr 16) and 0xFF
                val g = (argb ushr 8) and 0xFF
                val b = argb and 0xFF
                luma[gy * 9 + gx] = (r * 30 + g * 59 + b * 11) / 100
            }
        }
        var hash = 0L
        var bit = 0
        for (y in 0 until 8) {
            val rowOff = y * 9
            for (x in 0 until 8) {
                if (luma[rowOff + x] > luma[rowOff + x + 1]) {
                    hash = hash or (1L shl bit)
                }
                bit++
            }
        }
        return hash
    }

    fun computeBoardGridMad(
        frameA: VisionFrame,
        frameB: VisionFrame,
        geometry: BoardGeometry
    ): Float {
        if (frameA.width != frameB.width || frameA.height != frameB.height) return 255f
        val x0 = geometry.leftPx.roundToInt().coerceIn(0, frameA.width - 2)
        val y0 = geometry.topPx.roundToInt().coerceIn(0, frameA.height - 2)
        val x1 = geometry.rightPx.roundToInt().coerceIn(x0 + 1, frameA.width - 1)
        val y1 = geometry.bottomPx.roundToInt().coerceIn(y0 + 1, frameA.height - 1)
        val cols = 12
        val rows = 10
        val w = (x1 - x0).coerceAtLeast(cols)
        val h = (y1 - y0).coerceAtLeast(rows)
        var diffSum = 0L
        var count = 0
        for (gy in 0 until rows) {
            val py = (y0 + ((gy + 0.5f) / rows) * h).toInt().coerceIn(0, frameA.height - 1)
            for (gx in 0 until cols) {
                val px = (x0 + ((gx + 0.5f) / cols) * w).toInt().coerceIn(0, frameA.width - 1)
                val a = frameA.getArgb(px, py)
                val b = frameB.getArgb(px, py)
                val dr = abs(((a ushr 16) and 0xFF) - ((b ushr 16) and 0xFF))
                val dg = abs(((a ushr 8) and 0xFF) - ((b ushr 8) and 0xFF))
                val db = abs((a and 0xFF) - (b and 0xFF))
                diffSum += (dr + dg + db)
                count += 3
            }
        }
        return if (count > 0) diffSum.toFloat() / count.toFloat() else 255f
    }

    fun evaluateBoardCaptureQuality(
        frame: VisionFrame,
        geometry: BoardGeometry,
        recognition: RecognitionResult,
        hasHpBar: Boolean
    ): BoardQualityEvaluation {
        val board = recognition.board
        val total = board.cols * board.rows
        if (total < 9) {
            return BoardQualityEvaluation(false, "INVALID_GRID", total, 0f, 0f)
        }
        if (!hasHpBar && !geometry.isWeijianExpanded) {
            return BoardQualityEvaluation(false, "NO_HP_BAR", 0, 0f, 0f)
        }

        val cw = geometry.cellWidth
        val ch = geometry.cellHeight
        val centerVs = FloatArray(total)
        val centerOffsets = arrayOf(
            0f to 0f,
            0.22f to 0f,
            -0.22f to 0f,
            0f to 0.22f,
            0f to -0.22f
        )
        val cornerOffsets = arrayOf(
            -0.42f to -0.42f,
            0.42f to -0.42f,
            -0.42f to 0.42f,
            0.42f to 0.42f
        )

        var emptyHoleCount = 0
        var idx = 0
        for (r in 0 until board.rows) {
            for (c in 0 until board.cols) {
                val cx = geometry.cellCenterX(c)
                val cy = geometry.cellCenterY(r)

                var inVSum = 0f
                var peakV = 0f
                for ((dx, dy) in centerOffsets) {
                    val px = (cx + dx * cw).roundToInt().coerceIn(0, frame.width - 1)
                    val py = (cy + dy * ch).roundToInt().coerceIn(0, frame.height - 1)
                    val argb = frame.getArgb(px, py)
                    val v = max((argb ushr 16) and 0xFF, max((argb ushr 8) and 0xFF, argb and 0xFF)) / 255f
                    inVSum += v
                    if (v > peakV) peakV = v
                }
                val inMeanV = inVSum * 0.2f
                centerVs[idx] = inMeanV

                var cornerVSum = 0f
                for ((dx, dy) in cornerOffsets) {
                    val px = (cx + dx * cw).roundToInt().coerceIn(0, frame.width - 1)
                    val py = (cy + dy * ch).roundToInt().coerceIn(0, frame.height - 1)
                    val argb = frame.getArgb(px, py)
                    val v = max((argb ushr 16) and 0xFF, max((argb ushr 8) and 0xFF, argb and 0xFF)) / 255f
                    cornerVSum += v
                }
                val cornerMeanV = cornerVSum * 0.25f

                val cell = board.cells[idx]
                val flags = board.tileFlags.getOrElse(idx) { TileFlagBits.NONE }
                val isGenuineFlatTileHazard = (flags and (TileFlagBits.STORM or TileFlagBits.SHATTERED or TileFlagBits.BLACK_HOLE)) != 0 ||
                    cell.element == Element.HIDDEN ||
                    (cell.hazards and HazardBits.QUESTION) != 0

                // Empty checkerboard hole left by cleared orbs has flat low center brightness and almost no rim highlight
                if (!isGenuineFlatTileHazard &&
                    inMeanV <= 0.34f &&
                    peakV <= 0.42f &&
                    (inMeanV - cornerMeanV) <= 0.12f
                ) {
                    emptyHoleCount++
                }
                idx++
            }
        }

        // Measure bottom-right 2x3 corner where ToS renders huge white/cyan/gold "XX Combo!! +XXX%" explosions
        val glowRatio = measureBottomRightComboGlowRatio(frame, geometry)

        val sortedVs = centerVs.sortedArray()
        val p90Idx = ((total - 1) * 0.90f).roundToInt().coerceIn(0, total - 1)
        val p90CenterV = sortedVs[p90Idx]

        val blackWhiteCount = board.cells.count { (it.hazards and HazardBits.BLACK_WHITE) != 0 }
        val genuineDarkBoardCount = recognition.questionOrbsDetected +
            recognition.bloodDrainOrbsDetected +
            recognition.petrifiedOrbsDetected +
            blackWhiteCount
        val allowDimmedDarkMechanic = genuineDarkBoardCount >= max(15, total / 2)

        if (!allowDimmedDarkMechanic && p90CenterV < 0.74f) {
            return BoardQualityEvaluation(false, "DIMMED_END_OF_TURN", emptyHoleCount, glowRatio, p90CenterV)
        }
        if (emptyHoleCount >= 2 || (emptyHoleCount >= 1 && recognition.averageConfidence < 0.90f)) {
            return BoardQualityEvaluation(false, "MID_CLEAR_EMPTY_HOLES", emptyHoleCount, glowRatio, p90CenterV)
        }
        if (hasSystemToastBannerOcclusion(frame, geometry)) {
            return BoardQualityEvaluation(false, "SYSTEM_TOAST_OCCLUSION", emptyHoleCount, glowRatio, p90CenterV)
        }
        if (glowRatio >= 0.22f) {
            return BoardQualityEvaluation(false, "COMBO_EXPLOSION_GLOW", emptyHoleCount, glowRatio, p90CenterV)
        }

        val nonSpecialUnknowns = board.cells.indices.count { i ->
            val el = board.cells[i].element
            val flags = board.tileFlags.getOrElse(i) { TileFlagBits.NONE }
            val haz = board.cells[i].hazards
            val knownDark = (flags and (TileFlagBits.STORM or TileFlagBits.SHATTERED or TileFlagBits.END_FORBIDDEN or TileFlagBits.BLACK_HOLE)) != 0 ||
                (haz and (HazardBits.PETRIFIED or HazardBits.EVIL_ENERGY or HazardBits.BLOOD_DRAIN or HazardBits.QUESTION)) != 0
            !knownDark && (el == Element.UNKNOWN || el == Element.EMPTY)
        }
        if (!allowDimmedDarkMechanic && nonSpecialUnknowns >= 4) {
            return BoardQualityEvaluation(false, "HIGH_UNKNOWN", emptyHoleCount, glowRatio, p90CenterV)
        }

        return BoardQualityEvaluation(true, "OK", emptyHoleCount, glowRatio, p90CenterV)
    }

    /**
     * Detects bright white/light-grey Android/HyperOS horizontal Toast pills (e.g. "已开始录屏...")
     * or wide white banner overlays occluding lower board rows (`y in [0.54..0.92]` of the board).
     */
    private fun hasSystemToastBannerOcclusion(frame: VisionFrame, geometry: BoardGeometry): Boolean {
        val boardW = (geometry.rightPx - geometry.leftPx).coerceAtLeast(1f)
        val boardH = (geometry.bottomPx - geometry.topPx).coerceAtLeast(1f)
        val x0 = (geometry.leftPx + boardW * 0.22f).roundToInt().coerceIn(0, frame.width - 2)
        val x1 = (geometry.leftPx + boardW * 0.78f).roundToInt().coerceIn(x0 + 1, frame.width - 1)
        val y0 = (geometry.topPx + boardH * 0.54f).roundToInt().coerceIn(0, frame.height - 2)
        val y1 = (geometry.topPx + boardH * 0.92f).roundToInt().coerceIn(y0 + 1, frame.height - 1)
        val stepX = max(2, (x1 - x0) / 32)
        val stepY = max(2, (y1 - y0) / 14)
        var bannerRows = 0
        var y = y0
        while (y <= y1) {
            var neutralWhitePx = 0
            var rowTotal = 0
            var x = x0
            while (x <= x1) {
                val argb = frame.getArgb(x, y)
                val r = (argb ushr 16) and 0xFF
                val g = (argb ushr 8) and 0xFF
                val b = argb and 0xFF
                val minC = min(r, min(g, b))
                val maxC = max(r, max(g, b))
                if (minC >= 212 && (maxC - minC) <= 22) {
                    neutralWhitePx++
                }
                rowTotal++
                x += stepX
            }
            if (rowTotal > 0 && neutralWhitePx.toFloat() / rowTotal >= 0.46f) {
                bannerRows++
                if (bannerRows >= 2) return true
            }
            y += stepY
        }
        return false
    }

    private fun measureBottomRightComboGlowRatio(frame: VisionFrame, geometry: BoardGeometry): Float {
        val boardW = (geometry.rightPx - geometry.leftPx).coerceAtLeast(1f)
        val x0 = (geometry.leftPx + boardW * 0.50f).roundToInt().coerceIn(0, frame.width - 2)
        val y0 = (geometry.bottomPx - geometry.cellHeight * 2.0f).roundToInt().coerceIn(0, frame.height - 2)
        val x1 = geometry.rightPx.roundToInt().coerceIn(x0 + 1, frame.width - 1)
        val y1 = geometry.bottomPx.roundToInt().coerceIn(y0 + 1, frame.height - 1)
        val stepX = max(2, (x1 - x0) / 36)
        val stepY = max(2, (y1 - y0) / 24)
        var glowPx = 0
        var totalPx = 0
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                val argb = frame.getArgb(x, y)
                val r = (argb ushr 16) and 0xFF
                val g = (argb ushr 8) and 0xFF
                val b = argb and 0xFF
                val minC = min(r, min(g, b))
                val maxC = max(r, max(g, b))
                val isOverexposedWhiteBloom = minC >= 195 && maxC >= 235
                val isComboGoldBurst = r >= 235 && g >= 210 && b >= 140
                if (isOverexposedWhiteBloom || isComboGoldBurst) {
                    glowPx++
                }
                totalPx++
                x += stepX
            }
            y += stepY
        }
        return if (totalPx > 0) glowPx.toFloat() / totalPx.toFloat() else 0f
    }
}
