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
 * Designed for Tower of Saviors modern aspect ratios (e.g. Xiaomi 15 1200x2670 20:9).
 */
data class BoardCalibration(
    val leftRatio: Float = 0.008f,
    val rightRatio: Float = 0.992f,
    val bottomRatio: Float = 0.968f,
    val heightScale: Float = 1.0f, // 1.0 = square cells (height = 5/6 * width)
    val autoDetectWeijian: Boolean = true,
    val autoDetectArbitraryGrid: Boolean = false
) {
    fun toBase6x5Geometry(screenWidth: Int, screenHeight: Int): BoardGeometry {
        val left = leftRatio * screenWidth
        val right = rightRatio * screenWidth
        val boardW = (right - left).coerceAtLeast(60f)
        val cellW = boardW / 6f
        val cellH = cellW * heightScale
        val bottom = (bottomRatio * screenHeight).coerceIn(cellH * 5f, screenHeight.toFloat())
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
        val baseVitality = rowOrbVitality(frame, base.leftPx, base.topPx, base.cellWidth, base.cellHeight, base.cols)
        if (baseVitality < 0.25f) {
            // Base board may not be calibrated yet or not in battle; try arbitrary auto-detect fallback
            return detectArbitraryGrid(frame) ?: base
        }

        var extraRows = 0
        for (k in 1..3) {
            val probeTop = base.topPx - k * base.cellHeight
            if (probeTop < 0f) break
            val vitality = rowOrbVitality(frame, base.leftPx, probeTop, base.cellWidth, base.cellHeight, base.cols)
            // Real ToS weijian orb rows have circular orb saturation/radial contrast similar to base rows
            // (and much higher circular uniformity than team character portraits or HP bar)
            val threshold = max(0.32f, baseVitality * 0.62f)
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
