package com.autodora.next.capture

import android.graphics.Bitmap
import android.graphics.Color
import com.autodora.next.model.BoardGeometry
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Normalized 6x5 baseline calibration (ratios of full physical screen width/height).
 * When [bottomRatio] < 0 (AUTO_BOTTOM_RATIO), automatically computes Tower of Saviors'
 * exact centered 9:16 game canvas bottom for any 16:9 / 19.5:9 / 20:9 / 21:9 screen
 * (verified against real Xiaomi 15 1200x2670 battle screenshot: top=1410, bottom=2410, cell=200px).
 */
data class BoardCalibration(
    val leftRatio: Float = 0.0f,
    val rightRatio: Float = 1.0f,
    val bottomRatio: Float = AUTO_BOTTOM_RATIO,
    val heightScale: Float = 1.0f, // 1.0 = square cells (height = 5/6 * width)
    val autoDetectWeijian: Boolean = true,
    val autoDetectArbitraryGrid: Boolean = false
) {
    fun effectiveBottomRatio(screenWidth: Int, screenHeight: Int): Float {
        if (bottomRatio in 0.45f..1.0f) return bottomRatio
        return computeAutoBottomRatio(screenWidth, screenHeight)
    }

    fun toBase6x5Geometry(screenWidth: Int, screenHeight: Int): BoardGeometry {
        val left = leftRatio.coerceIn(0f, 0.35f) * screenWidth
        val right = rightRatio.coerceIn(0.65f, 1f) * screenWidth
        val boardW = (right - left).coerceAtLeast(60f)
        val cellW = boardW / 6f
        val cellH = cellW * heightScale
        val effBottomRatio = effectiveBottomRatio(screenWidth, screenHeight)
        val bottom = (effBottomRatio * screenHeight).coerceIn(cellH * 5f, screenHeight.toFloat())
        val top = (bottom - cellH * 5f).coerceAtLeast(0f)
        return BoardGeometry(
            leftPx = left,
            topPx = top,
            rightPx = right,
            bottomPx = bottom,
            cols = 6,
            rows = 5,
            isWeijianExpanded = false
        )
    }

    companion object {
        const val AUTO_BOTTOM_RATIO = -1f

        /**
         * Tower of Saviors renders a portrait 9:16 viewport centered vertically on tall screens
         * (with symmetric top/bottom decorative borders). On 1200x2670 -> 2410/2670 = 0.9026.
         */
        fun computeAutoBottomRatio(screenWidth: Int, screenHeight: Int): Float {
            if (screenWidth <= 0 || screenHeight <= 0) return 0.9026f
            val gameH = screenWidth * (16f / 9f)
            val letterbox = ((screenHeight - gameH) * 0.5f).coerceAtLeast(0f)
            val bottomPx = (screenHeight - letterbox + gameH * 0.004f).coerceAtMost(screenHeight.toFloat())
            return (bottomPx / screenHeight).coerceIn(0.60f, 1.0f)
        }
    }
}

/**
 * Pure pixel buffer abstraction so AutoGridDetector runs identically on Android Bitmap AND JVM unit tests!
 */
interface PixelFrame {
    val width: Int
    val height: Int
    fun getArgb(x: Int, y: Int): Int
}

class BitmapPixelFrame(private val bitmap: Bitmap) : PixelFrame {
    override val width: Int = bitmap.width
    override val height: Int = bitmap.height
    private val pixels = IntArray(width * height).also {
        bitmap.getPixels(it, 0, width, 0, 0, width, height)
    }

    override fun getArgb(x: Int, y: Int): Int {
        val cx = x.coerceIn(0, width - 1)
        val cy = y.coerceIn(0, height - 1)
        return pixels[cy * width + cx]
    }
}

/**
 * Two-stage Dynamic Board Geometry & Grid-Size Auto-Detector:
 *  - Stage 1 (< 3ms): Uses calibrated 6x5 baseline and probes rows -1..-3 directly ABOVE the base
 *    board to instantaneously detect Tower of Saviors "违建" (6x6 / 6x7 / 6x8 expanded boards!).
 *  - Stage 2 (general fallback): Horizontal & vertical edge-gradient autocorrelation to estimate
 *    cellSize and scan arbitrary rectangular grids (4x4 ~ 8x8, e.g., 7x6 / 5x4 / 6x8).
 */
object AutoGridDetector {

    fun detect(bitmap: Bitmap, calibration: BoardCalibration): BoardGeometry {
        return detect(BitmapPixelFrame(bitmap), calibration)
    }

    fun detect(frame: PixelFrame, calibration: BoardCalibration): BoardGeometry {
        val base = calibration.toBase6x5Geometry(frame.width, frame.height)

        if (calibration.autoDetectArbitraryGrid) {
            val arbitrary = detectArbitraryGrid(frame)
            if (arbitrary != null) return arbitrary
        }

        if (!calibration.autoDetectWeijian) {
            return base
        }

        // Stage 1: Fast upward probe for 6x6 / 6x7 / 6x8 "违建" (extra 1..3 rows above base 6x5)
        // FIRST: Check for the horizontal ToS HP Bar Barrier directly above base.topPx!
        // If the HP bar sits directly above row 0, what lies above it are the 6 team character portraits —
        // NEVER expand into portraits as false 6x6 Weijian!
        if (hasHpBarBarrier(frame, base.leftPx, base.rightPx, base.topPx, base.cellHeight)) {
            return base
        }

        val baseVitality = rowOrbVitality(frame, base.leftPx, base.topPx, base.cellWidth, base.cellHeight, base.cols)
        if (baseVitality < 0.25f) {
            // Base board may not be calibrated yet or not in battle; try HP-bar snap then arbitrary fallback
            return autoCalibrateFromRealScreen(frame) ?: detectArbitraryGrid(frame) ?: base
        }

        var extraRows = 0
        for (k in 1..3) {
            val probeTop = base.topPx - k * base.cellHeight
            if (probeTop < 0f) break
            // Stop immediately if a horizontal HP bar separates the previous row from this candidate row
            if (k > 1 && hasHpBarBarrier(frame, base.leftPx, base.rightPx, probeTop + base.cellHeight, base.cellHeight)) {
                break
            }
            val vitality = rowOrbVitality(frame, base.leftPx, probeTop, base.cellWidth, base.cellHeight, base.cols)
            // Real ToS weijian orb rows have circular orb saturation/radial contrast similar to base rows
            // (and much higher circular uniformity than team character portraits or HP bar)
            val threshold = max(0.34f, baseVitality * 0.65f)
            if (vitality >= threshold) {
                extraRows = k
            } else {
                break
            }
        }

        return if (extraRows > 0) {
            BoardGeometry(
                leftPx = base.leftPx,
                topPx = (base.topPx - extraRows * base.cellHeight).coerceAtLeast(0f),
                rightPx = base.rightPx,
                bottomPx = base.bottomPx,
                cols = base.cols,
                rows = base.rows + extraRows,
                isWeijianExpanded = true
            )
        } else {
            base
        }
    }

    /**
     * Detects the horizontal Tower of Saviors team HP bar directly above [rowTopPx]
     * (band: [rowTopPx - 0.30*cellH .. rowTopPx - 0.02*cellH]).
     * On real Xiaomi 15 1200x2670 battle screenshots, the green HP bar sits at y=1364..1398
     * directly above row 0 (y=1410), spanning >60% of board width.
     */
    internal fun hasHpBarBarrier(
        frame: PixelFrame,
        leftPx: Float,
        rightPx: Float,
        rowTopPx: Float,
        cellH: Float
    ): Boolean {
        val bandTop = (rowTopPx - cellH * 0.30f).roundToInt().coerceAtLeast(0)
        val bandBottom = (rowTopPx - cellH * 0.02f).roundToInt().coerceIn(bandTop, frame.height - 1)
        if (bandBottom - bandTop < 4) return false

        val scanLeft = (leftPx + (rightPx - leftPx) * 0.08f).roundToInt().coerceAtLeast(0)
        val scanRight = (rightPx - (rightPx - leftPx) * 0.08f).roundToInt().coerceAtMost(frame.width - 1)
        if (scanRight - scanLeft < 40) return false

        val stepX = max(4, (scanRight - scanLeft) / 80)
        val stepY = max(1, (bandBottom - bandTop) / 18)

        var hpLikeRows = 0
        var scannedRows = 0
        var y = bandTop
        while (y <= bandBottom) {
            scannedRows++
            var greenHits = 0
            var lowHpRedYellowHits = 0
            var totalX = 0
            var x = scanLeft
            while (x <= scanRight) {
                totalX++
                val argb = frame.getArgb(x, y)
                val r = (argb ushr 16) and 0xFF
                val g = (argb ushr 8) and 0xFF
                val b = argb and 0xFF
                // Classic ToS bright green HP bar (even behind white HP numbers!)
                if (g >= 115 && (g - r) >= 35 && (g - b) >= 35) {
                    greenHits++
                } else if (r >= 160 && b <= 75 && (r - b) >= 95 && g in 20..195) {
                    // Low-HP warning bar (yellow/orange/red)
                    lowHpRedYellowHits++
                }
                x += stepX
            }
            if (totalX > 0) {
                val greenRatio = greenHits.toFloat() / totalX
                val lowHpRatio = lowHpRedYellowHits.toFloat() / totalX
                if (greenRatio >= 0.32f || lowHpRatio >= 0.45f) {
                    hpLikeRows++
                }
            }
            y += stepY
        }
        return scannedRows > 0 && hpLikeRows >= max(3, scannedRows / 4)
    }

    /**
     * Real-game screenshot auto-calibrator: locates the ToS horizontal green HP bar directly
     * above the 6x5 battle board, snaps topPx = hpBarBottomY + 0.06 * cellSize, and validates
     * all 5 rows contain real orbs before returning pixel-accurate 6x5 BoardGeometry.
     */
    fun autoCalibrateFromRealScreen(frame: PixelFrame): BoardGeometry? {
        val w = frame.width
        val h = frame.height
        if (w < 240 || h < 360) return null

        val cellSize = w / 6f
        val yStart = (h * 0.34f).roundToInt()
        val yEnd = (h * 0.66f).roundToInt()
        val xStart = (w * 0.08f).roundToInt()
        val xEnd = (w * 0.92f).roundToInt()
        val stepX = max(4, (xEnd - xStart) / 90)

        var bestRunStart = -1
        var bestRunEnd = -1
        var curStart = -1

        for (y in yStart..yEnd step 2) {
            var greenHits = 0
            var totalX = 0
            var x = xStart
            while (x <= xEnd) {
                totalX++
                val argb = frame.getArgb(x, y)
                val r = (argb ushr 16) and 0xFF
                val g = (argb ushr 8) and 0xFF
                val b = argb and 0xFF
                if (g >= 115 && (g - r) >= 35 && (g - b) >= 35) greenHits++
                x += stepX
            }
            val isGreenBarRow = totalX > 0 && (greenHits.toFloat() / totalX) >= 0.34f
            if (isGreenBarRow) {
                if (curStart < 0) curStart = y
                val curHeight = y - curStart + 2
                val bestHeight = if (bestRunStart >= 0) bestRunEnd - bestRunStart + 2 else 0
                if (curHeight >= bestHeight) {
                    bestRunStart = curStart
                    bestRunEnd = y
                }
            } else {
                curStart = -1
            }
        }

        val barHeight = if (bestRunStart >= 0) bestRunEnd - bestRunStart + 2 else 0
        val minBarH = (cellSize * 0.07f).roundToInt().coerceAtLeast(8)
        val maxBarH = (cellSize * 0.32f).roundToInt()
        if (barHeight !in minBarH..maxBarH) return null

        val boardTop = (bestRunEnd + cellSize * 0.06f).coerceIn(0f, h - cellSize * 5f)
        val boardBottom = boardTop + cellSize * 5f
        if (boardBottom > h + 4f) return null

        // Verify at least 4 of 5 rows exhibit strong orb vitality
        var goodRows = 0
        for (r in 0 until 5) {
            val vit = rowOrbVitality(frame, 0f, boardTop + r * cellSize, cellSize, cellSize, 6)
            if (vit >= 0.25f) goodRows++
        }
        if (goodRows < 4) return null

        return BoardGeometry(
            leftPx = 0f,
            topPx = boardTop,
            rightPx = w.toFloat(),
            bottomPx = boardBottom.coerceAtMost(h.toFloat()),
            cols = 6,
            rows = 5,
            isWeijianExpanded = false
        )
    }

    /**
     * Evaluates how strongly a candidate horizontal row of [cols] square cells resembles ToS rune orbs.
     * Combines:
     *  1) Inner orb circular saturation & luminance (orbs are vivid colored spheres, even BW orbs have bright centers)
     *  2) Radial center-vs-corner luminance drop (orbs are round with dark grid tile corners)
     */
    internal fun rowOrbVitality(
        frame: PixelFrame,
        leftPx: Float,
        rowTopPx: Float,
        cellW: Float,
        cellH: Float,
        cols: Int
    ): Float {
        var orbLikeCells = 0f
        for (c in 0 until cols) {
            val cx = leftPx + (c + 0.5f) * cellW
            val cy = rowTopPx + 0.5f * cellH
            val score = cellOrbLikeness(frame, cx, cy, cellW, cellH)
            orbLikeCells += score
        }
        return orbLikeCells / cols
    }

    internal fun cellOrbLikeness(
        frame: PixelFrame,
        cx: Float,
        cy: Float,
        cellW: Float,
        cellH: Float
    ): Float {
        val rInner = min(cellW, cellH) * 0.25f
        val rCorner = min(cellW, cellH) * 0.44f

        // Sample 5 inner ring points
        val innerPts = arrayOf(
            cx to cy,
            (cx - rInner) to cy,
            (cx + rInner) to cy,
            cx to (cy - rInner),
            cx to (cy + rInner)
        )
        var satSum = 0f
        var valSum = 0f
        for ((px, py) in innerPts) {
            val argb = frame.getArgb(px.roundToInt(), py.roundToInt())
            val hsv = rgbToHsvFast(argb)
            satSum += hsv[1]
            valSum += hsv[2]
        }
        val avgSat = satSum / innerPts.size
        val avgVal = valSum / innerPts.size

        // Sample 4 tile corners (typically darker checkerboard background around circular rune!)
        val cornerPts = arrayOf(
            (cx - rCorner) to (cy - rCorner),
            (cx + rCorner) to (cy - rCorner),
            (cx - rCorner) to (cy + rCorner),
            (cx + rCorner) to (cy + rCorner)
        )
        var cornerValSum = 0f
        for ((px, py) in cornerPts) {
            val argb = frame.getArgb(px.roundToInt(), py.roundToInt())
            cornerValSum += rgbToHsvFast(argb)[2]
        }
        val avgCornerVal = cornerValSum / cornerPts.size
        val radialContrast = (avgVal - avgCornerVal).coerceAtLeast(0f)

        // Score combining vividness + circular radial drop
        val satScore = ((avgSat - 0.18f) / 0.35f).coerceIn(0f, 1f)
        val valScore = ((avgVal - 0.20f) / 0.40f).coerceIn(0f, 1f)
        val radialScore = (radialContrast / 0.25f).coerceIn(0f, 1f)

        return (satScore * 0.45f + valScore * 0.20f + radialScore * 0.35f).coerceIn(0f, 1f)
    }

    /**
     * Stage 2: General periodic gradient-projection autocorrelation detector for arbitrary Cols x Rows (4x4..8x8).
     */
    fun detectArbitraryGrid(frame: PixelFrame): BoardGeometry? {
        val w = frame.width
        val h = frame.height
        if (w < 120 || h < 120) return null

        // Scan lower 75% of screen where the puzzle board resides
        val roiTop = (h * 0.25f).toInt()
        val roiBottom = (h * 0.99f).toInt()
        val roiH = roiBottom - roiTop
        if (roiH < 80) return null

        // Vertical gradient energy projected onto X (finds column boundaries)
        val projX = FloatArray(w)
        val stepY = max(1, roiH / 160)
        for (y in roiTop until roiBottom step stepY) {
            var prevLum = lum(frame.getArgb(0, y))
            for (x in 1 until w) {
                val curLum = lum(frame.getArgb(x, y))
                projX[x] += abs(curLum - prevLum)
                prevLum = curLum
            }
        }

        // Autocorrelation of projX over candidate cell sizes (cols in 4..8)
        val minCell = (w / 9).coerceAtLeast(16)
        val maxCell = (w / 4).coerceAtMost(w - 1)
        var bestCellSize = -1
        var bestAutoCorr = -1.0

        for (lag in minCell..maxCell) {
            var sum = 0.0
            val limit = w - lag
            for (x in 0 until limit) {
                sum += projX[x].toDouble() * projX[x + lag].toDouble()
            }
            val norm = sum / limit
            // Slight bias toward realistic 6-col (w/6) & 5..8 col integer divisions of width
            val colsEst = w.toDouble() / lag.toDouble()
            val nearIntPenalty = 1.0 - min(abs(colsEst - colsEst.roundToInt()), 0.5) * 0.15
            val score = norm * nearIntPenalty
            if (score > bestAutoCorr) {
                bestAutoCorr = score
                bestCellSize = lag
            }
        }

        if (bestCellSize < 16) return null

        val cols = (w.toFloat() / bestCellSize).roundToInt().coerceIn(4, 8)
        val exactCell = bestCellSize.toFloat()

        // Find horizontal offset (phase) maximizing grid-line alignment
        val totalW = cols * exactCell
        val maxLeft = (w - totalW).coerceAtLeast(0f).toInt()
        var bestLeft = 0f
        var bestPhaseScore = -1f
        for (lx in 0..maxLeft step max(1, maxLeft / 24)) {
            var s = 0f
            for (c in 0..cols) {
                val gx = (lx + c * exactCell).roundToInt().coerceIn(0, w - 1)
                s += projX[gx]
            }
            if (s > bestPhaseScore) {
                bestPhaseScore = s
                bestLeft = lx.toFloat()
            }
        }

        // Scan vertically using exactCell to locate contiguous orb rows
        val maxBottom = h - 4
        var bestStartY = -1f
        var bestRowCount = 0
        var bestTotalVitality = 0f

        val stepScanY = max(2, (exactCell * 0.12f).toInt())
        var y = roiTop.toFloat()
        while (y + exactCell * 4 <= maxBottom) {
            var count = 0
            var vitSum = 0f
            while (count < 8 && y + (count + 1) * exactCell <= maxBottom) {
                val v = rowOrbVitality(frame, bestLeft, y + count * exactCell, exactCell, exactCell, cols)
                if (v >= 0.34f) {
                    vitSum += v
                    count++
                } else {
                    break
                }
            }
            if (count >= 4 && (count > bestRowCount || (count == bestRowCount && vitSum > bestTotalVitality))) {
                bestRowCount = count
                bestStartY = y
                bestTotalVitality = vitSum
            }
            y += stepScanY
        }

        if (bestRowCount < 4 || bestStartY < 0f) return null

        return BoardGeometry(
            leftPx = bestLeft,
            topPx = bestStartY,
            rightPx = bestLeft + cols * exactCell,
            bottomPx = bestStartY + bestRowCount * exactCell,
            cols = cols,
            rows = bestRowCount,
            isWeijianExpanded = (cols == 6 && bestRowCount > 5)
        )
    }

    private fun lum(argb: Int): Float {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    internal fun rgbToHsvFast(argb: Int): FloatArray {
        val r = ((argb ushr 16) and 0xFF) / 255f
        val g = ((argb ushr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        val h = when {
            d < 1e-5f -> 0f
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }.let { if (it < 0f) it + 360f else it }
        val s = if (mx < 1e-5f) 0f else d / mx
        return floatArrayOf(h, s, mx)
    }
}
