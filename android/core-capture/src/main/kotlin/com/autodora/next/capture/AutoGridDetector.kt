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
     * Checks whether horizontal scanline [y] matches a Tower of Saviors HP bar:
     *  - Full/mid HP bar in Coral-Pink/Red, Bright Green, or Cyan Shield
     *  - Low-HP partially-filled bar (vivid green/coral/cyan on the left + dark empty track on the right)
     */
    private fun isHpBarScanline(
        frame: PixelFrame,
        y: Int,
        scanLeft: Int,
        scanRight: Int,
        stepX: Int
    ): Boolean {
        val span = (scanRight - scanLeft).coerceAtLeast(1)
        val leftCut = scanLeft + (span * 0.24f).roundToInt()

        var greenHits = 0
        var coralRedHits = 0
        var cyanHits = 0
        var totalX = 0

        var leftHpHits = 0
        var leftTotal = 0
        var rightDarkHits = 0
        var rightVividHits = 0
        var rightTotal = 0

        var x = scanLeft
        while (x <= scanRight) {
            totalX++
            val argb = frame.getArgb(x, y)
            val r = (argb ushr 16) and 0xFF
            val g = (argb ushr 8) and 0xFF
            val b = argb and 0xFF

            val isGreen = g >= 115 && (g - r) >= 32 && (g - b) >= 32
            // Accept both classic Coral-Red (R>B+18) AND modern glowing Pink-Magenta (R>=B-12, R-G>=32) ToS HP bars
            val isCoral = r >= 155 && (r - g) >= 32 && r >= (b - 12)
            val isCyan = b >= 145 && g >= 125 && (b - r) >= 40
            if (isGreen) greenHits++
            else if (isCoral) coralRedHits++
            else if (isCyan) cyanHits++

            if (x <= leftCut) {
                leftTotal++
                if (isGreen || isCoral || isCyan) leftHpHits++
            } else {
                rightTotal++
                val maxCh = max(r, max(g, b))
                val minCh = min(r, min(g, b))
                // Empty HP track is a dark charcoal slot (allow white monochrome HP text on far right)
                if (maxCh <= 85) rightDarkHits++
                // Vivid colored pixels indicate an orb row (e.g. Weijian 6x6..6x8), NEVER an empty HP track!
                if ((maxCh - minCh) >= 50 && maxCh >= 95) rightVividHits++
            }
            x += stepX
        }
        if (totalX == 0) return false

        val greenRatio = greenHits.toFloat() / totalX
        val coralRatio = coralRedHits.toFloat() / totalX
        val cyanRatio = cyanHits.toFloat() / totalX
        if (greenRatio >= 0.30f || coralRatio >= 0.32f || cyanRatio >= 0.35f) {
            return true
        }

        // Low-HP partial player HP bar (e.g. 15%..30% remaining HP on left, dark empty track with ZERO vivid orbs on right)
        val leftRatio = if (leftTotal > 0) leftHpHits.toFloat() / leftTotal else 0f
        val rightDarkRatio = if (rightTotal > 0) rightDarkHits.toFloat() / rightTotal else 0f
        val rightVividRatio = if (rightTotal > 0) rightVividHits.toFloat() / rightTotal else 1f
        return leftRatio >= 0.42f && rightDarkRatio >= 0.45f && rightVividRatio <= 0.12f
    }

    /**
     * Detects the horizontal Tower of Saviors team HP bar directly above [rowTopPx]
     * (band: [rowTopPx - 0.30*cellH .. rowTopPx - 0.02*cellH]).
     * Multi-screenshot calibrated across ALL 3 authentic ToS HP-bar styles (including low-HP partial bars!):
     *  - Classic Coral-Pink / Red HP bar (verified on official 2026 App Store & YouTube gameplay)
     *  - Bright Green HP bar (full & partial, verified on real Xiaomi 15 & 2026 YouTube Extra 2)
     *  - Cyan / Blue Shielded HP bar
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
        if (bandBottom - bandTop < 3) return false

        val scanLeft = (leftPx + (rightPx - leftPx) * 0.08f).roundToInt().coerceAtLeast(0)
        val scanRight = (rightPx - (rightPx - leftPx) * 0.08f).roundToInt().coerceAtMost(frame.width - 1)
        if (scanRight - scanLeft < 30) return false

        val stepX = max(2, (scanRight - scanLeft) / 80)
        val stepY = max(1, (bandBottom - bandTop) / 18)

        var hpLikeRows = 0
        var scannedRows = 0
        var y = bandTop
        while (y <= bandBottom) {
            scannedRows++
            if (isHpBarScanline(frame, y, scanLeft, scanRight, stepX)) {
                hpLikeRows++
            }
            y += stepY
        }
        return scannedRows > 0 && hpLikeRows >= max(2, scannedRows / 4)
    }

    /**
     * Real-screenshot calibrated battle-board presence gate separating genuine Tower of Saviors
     * battle boards (including 30/30 full-board '?' Question Orbs, Storm columns, and Puzzle shields)
     * from non-battle screens (ToS Home Map, Victory/Reward screens, Team/Shop menus, Android UI).
     *
     * Calibrated across 35+ real screenshots:
     *  - Every authentic ToS battle board exhibits the horizontal Player HP bar directly above row 0
     *    coupled with dark checkerboard tile corners (V <= 0.34, ratio >= 0.32) or circular orb contrast;
     *  - Fallback (for cropped/synthetic/0-HP frames) requires strong periodic circular-orb vs dark-corner
     *    checkerboard structure across the full grid (darkCornerRatio >= 0.62, posContrast >= 0.72).
     */
    fun isBattleBoardPresent(bitmap: Bitmap, geometry: BoardGeometry): Boolean {
        val frame = object : PixelFrame {
            override val width: Int = bitmap.width
            override val height: Int = bitmap.height
            override fun getArgb(x: Int, y: Int): Int =
                bitmap.getPixel(x.coerceIn(0, width - 1), y.coerceIn(0, height - 1))
        }
        return isBattleBoardPresent(frame, geometry)
    }

    fun isBattleBoardPresent(frame: PixelFrame, geometry: BoardGeometry): Boolean {
        val cols = geometry.cols
        val rows = geometry.rows
        val total = cols * rows
        if (total < 9 || geometry.cellWidth < 12f || geometry.cellHeight < 12f) return false

        val cw = geometry.cellWidth
        val ch = geometry.cellHeight
        var darkCorners = 0
        var totalCorners = 0
        var radialSum = 0f
        var posContrastCells = 0
        var vividCenters = 0

        val centerOffsets = arrayOf(
            0f to 0f,
            0.24f to 0f,
            -0.24f to 0f,
            0f to 0.24f,
            0f to -0.24f
        )

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val cx = geometry.cellCenterX(c)
                val cy = geometry.cellCenterY(r)

                var cornerVSum = 0f
                for (sx in intArrayOf(-1, 1)) {
                    for (sy in intArrayOf(-1, 1)) {
                        val px = (cx + sx * cw * 0.43f).roundToInt().coerceIn(0, frame.width - 1)
                        val py = (cy + sy * ch * 0.43f).roundToInt().coerceIn(0, frame.height - 1)
                        val argb = frame.getArgb(px, py)
                        val v = max((argb ushr 16) and 0xFF, max((argb ushr 8) and 0xFF, argb and 0xFF)) / 255f
                        cornerVSum += v
                        totalCorners++
                        if (v <= 0.34f) darkCorners++
                    }
                }
                val cornerMeanV = cornerVSum * 0.25f

                var inVSum = 0f
                var inSSum = 0f
                for ((dx, dy) in centerOffsets) {
                    val px = (cx + dx * cw).roundToInt().coerceIn(0, frame.width - 1)
                    val py = (cy + dy * ch).roundToInt().coerceIn(0, frame.height - 1)
                    val argb = frame.getArgb(px, py)
                    val rr = ((argb ushr 16) and 0xFF) / 255f
                    val gg = ((argb ushr 8) and 0xFF) / 255f
                    val bb = (argb and 0xFF) / 255f
                    val mx = max(rr, max(gg, bb))
                    val mn = min(rr, min(gg, bb))
                    inVSum += mx
                    inSSum += if (mx < 1e-4f) 0f else (mx - mn) / mx
                }
                val inMeanV = inVSum * 0.2f
                val inMeanS = inSSum * 0.2f
                val contrast = inMeanV - cornerMeanV
                radialSum += contrast
                if (contrast >= 0.18f) posContrastCells++
                if (inMeanV >= 0.48f && (inMeanS >= 0.32f || cornerMeanV <= 0.28f)) vividCenters++
            }
        }

        val darkCornerRatio = darkCorners.toFloat() / totalCorners.coerceAtLeast(1)
        val meanRadial = radialSum / total
        val posContrastRatio = posContrastCells.toFloat() / total
        val vividCenterRatio = vividCenters.toFloat() / total

        val hasHp = hasHpBarBarrier(frame, geometry.leftPx, geometry.rightPx, geometry.topPx, ch)
        if (hasHp && (darkCornerRatio >= 0.32f || posContrastRatio >= 0.45f)) {
            return true
        }
        return darkCornerRatio >= 0.62f &&
            posContrastRatio >= 0.72f &&
            meanRadial >= 0.30f &&
            vividCenterRatio >= 0.65f
    }

    /**
     * Detects dimmed wave-transition / VICTORY / enemy-cutscene overlays covering a battle board:
     * During VICTORY or wave transitions, ToS keeps the HP bar visible while dimming the entire 6x5 board
     * with a translucent black veil (~45-55% brightness -> 90th-percentile orb center V < 0.72f), while most
     * orbs remain recognizable colored runes rather than flipping to a genuine full-board '?' screen (>=60% '?').
     */
    fun isDimmedTransitionBoard(
        bitmap: Bitmap,
        geometry: BoardGeometry,
        questionCount: Int,
        specialDarkOrbCount: Int = 0
    ): Boolean {
        val frame = object : PixelFrame {
            override val width: Int = bitmap.width
            override val height: Int = bitmap.height
            override fun getArgb(x: Int, y: Int): Int =
                bitmap.getPixel(x.coerceIn(0, width - 1), y.coerceIn(0, height - 1))
        }
        return isDimmedTransitionBoard(frame, geometry, questionCount, specialDarkOrbCount)
    }

    fun isDimmedTransitionBoard(
        frame: PixelFrame,
        geometry: BoardGeometry,
        questionCount: Int,
        specialDarkOrbCount: Int = 0
    ): Boolean {
        val cols = geometry.cols
        val rows = geometry.rows
        val total = cols * rows
        if (total < 9) return false
        // Genuine full-board '?' Question stages OR full-board Locked/Blood-Drain/Petrified boards (>=60%)
        // are naturally dark/desaturated — never treat them as dimmed VICTORY / wave-transition veils!
        val massThreshold = max(18, (total * 0.60f).roundToInt())
        if (questionCount + specialDarkOrbCount >= massThreshold) return false

        val cw = geometry.cellWidth
        val ch = geometry.cellHeight
        val centerVs = FloatArray(total)
        val offs = arrayOf(0f to 0f, 0.24f to 0f, -0.24f to 0f, 0f to 0.24f, 0f to -0.24f)
        var idx = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val cx = geometry.cellCenterX(c)
                val cy = geometry.cellCenterY(r)
                var vSum = 0f
                for ((dx, dy) in offs) {
                    val px = (cx + dx * cw).roundToInt().coerceIn(0, frame.width - 1)
                    val py = (cy + dy * ch).roundToInt().coerceIn(0, frame.height - 1)
                    val argb = frame.getArgb(px, py)
                    vSum += max((argb ushr 16) and 0xFF, max((argb ushr 8) and 0xFF, argb and 0xFF)) / 255f
                }
                centerVs[idx++] = vSum * 0.2f
            }
        }
        centerVs.sort()
        val p90Idx = ((total - 1) * 0.90f).roundToInt().coerceIn(0, total - 1)
        val p90V = centerVs[p90Idx]
        val maxV = centerVs[total - 1]
        // Active live battle boards consistently have p90V >= 0.85, maxV >= 0.90;
        // dimmed VICTORY / wave transitions consistently sit at p90V <= 0.66, maxV <= 0.72!
        return p90V < 0.72f && maxV < 0.76f
    }

    /**
     * Multi-screenshot calibrated auto-calibrator: collects ALL candidate horizontal HP bars
     * (Coral-Pink/Red, Green full/partial, Cyan) in the middle third of the screen, validates
     * each candidate against the 5-row orb grid directly below it (cleanly distinguishing the
     * Player HP bar right above row 0 from any Boss HP bar above team portraits!), and picks
     * the highest-scoring true 6x5 board geometry.
     */
    fun autoCalibrateFromRealScreen(frame: PixelFrame): BoardGeometry? {
        val w = frame.width
        val h = frame.height
        if (w < 120 || h < 200) return null

        val cellSize = w / 6f
        val autoBase = BoardCalibration().toBase6x5Geometry(w, h)
        val yStart = (h * 0.34f).roundToInt()
        val yEnd = min((h * 0.64f).roundToInt(), (h - cellSize * 4.85f).roundToInt()).coerceAtLeast(yStart)
        val xStart = (w * 0.08f).roundToInt()
        val xEnd = (w * 0.92f).roundToInt()
        val stepX = max(2, (xEnd - xStart) / 90)
        val stepY = if (h >= 600) 2 else 1

        val minBarH = (cellSize * 0.06f).roundToInt().coerceAtLeast(2)
        val maxBarH = (cellSize * 0.35f).roundToInt().coerceAtLeast(minBarH + 2)

        val candidates = ArrayList<Pair<Int, Int>>() // (runStart, runEnd)
        var curStart = -1
        var y = yStart
        while (y <= yEnd) {
            if (isHpBarScanline(frame, y, xStart, xEnd, stepX)) {
                if (curStart < 0) curStart = y
            } else {
                if (curStart >= 0) {
                    val runEnd = y - stepY
                    val barH = runEnd - curStart + stepY
                    if (barH in minBarH..maxBarH) {
                        candidates.add(curStart to runEnd)
                    }
                    curStart = -1
                }
            }
            y += stepY
        }
        if (curStart >= 0) {
            val runEnd = yEnd
            val barH = runEnd - curStart + stepY
            if (barH in minBarH..maxBarH) {
                candidates.add(curStart to runEnd)
            }
        }

        if (candidates.isEmpty()) return null

        var bestGeom: BoardGeometry? = null
        var bestScore = Float.NEGATIVE_INFINITY

        for ((_, runEnd) in candidates) {
            val boardTop = (runEnd + cellSize * 0.06f).coerceIn(0f, h - cellSize * 5f)
            val boardBottom = boardTop + cellSize * 5f
            if (boardBottom > h + 4f) continue

            var goodRows = 0
            var vitSum = 0f
            var row0Vit = 0f
            for (r in 0 until 5) {
                val vit = rowOrbVitality(frame, 0f, boardTop + r * cellSize, cellSize, cellSize, 6)
                if (r == 0) row0Vit = vit
                vitSum += vit
                if (vit >= 0.25f) goodRows++
            }
            if (goodRows < 4 || row0Vit < 0.22f) continue

            val avgVit = vitSum / 5f
            // Prefer candidates whose 5-row board has strong circular orb vitality AND sits near ToS's 9:16 layout
            // (cleanly rejects Boss HP bars sitting ~1.2 cells higher above the square team portraits!)
            val distCells = abs(boardTop - autoBase.topPx) / cellSize
            val score = avgVit * 2.0f + (goodRows / 5f) * 0.8f - distCells * 0.65f
            if (score > bestScore) {
                bestScore = score
                bestGeom = BoardGeometry(
                    leftPx = 0f,
                    topPx = boardTop,
                    rightPx = w.toFloat(),
                    bottomPx = boardBottom.coerceAtMost(h.toFloat()),
                    cols = 6,
                    rows = 5,
                    isWeijianExpanded = false
                )
            }
        }

        return bestGeom
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
