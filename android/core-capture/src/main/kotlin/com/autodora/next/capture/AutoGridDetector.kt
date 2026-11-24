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
        val midCut = scanLeft + (span * 0.62f).roundToInt()

        var greenHits = 0
        var coralRedHits = 0
        var cyanHits = 0
        var totalX = 0

        var leftHpHits = 0
        var leftTotal = 0
        var midDarkHits = 0
        var midTotal = 0
        var rightDarkHits = 0
        var rightNonHpVividHits = 0
        var rightTotal = 0

        var x = scanLeft
        while (x <= scanRight) {
            totalX++
            val argb = frame.getArgb(x, y)
            val r = (argb ushr 16) and 0xFF
            val g = (argb ushr 8) and 0xFF
            val b = argb and 0xFF

            val isGreen = g >= 115 && (g - r) >= 32 && (g - b) >= 32
            // Accept both classic Coral-Red (H <= 22°) AND modern glowing Pink-Magenta (H >= 295°) ToS HP bars,
            // strictly rejecting warm golden/beige/orange UI event banners (H in 23°..60°)!
            val isCoralHue = if (g > b) {
                (g - b) * 30 <= 11 * (r - b) // H <= 22°
            } else {
                r >= b || (r - g) * 12 >= 11 * (b - g) // H >= 295°
            }
            val isCoral = r >= 138 && (r - g) >= 32 && r >= (b - 12) && isCoralHue
            val isCyan = b >= 145 && g >= 125 && (b - r) >= 40
            val isHpColor = isGreen || isCoral || isCyan
            if (isGreen) greenHits++
            else if (isCoral) coralRedHits++
            else if (isCyan) cyanHits++

            if (x <= leftCut) {
                leftTotal++
                if (isHpColor) leftHpHits++
            } else {
                rightTotal++
                val maxCh = max(r, max(g, b))
                val minCh = min(r, min(g, b))
                // Empty HP track is a dark charcoal slot (allow white/red monochrome HP digits on far right)
                if (maxCh <= 85) rightDarkHits++
                if (x <= midCut) {
                    midTotal++
                    if (maxCh <= 85) midDarkHits++
                }
                // Foreign vivid colored pixels (non-HP-color blue/yellow/purple orbs) indicate a Weijian orb row,
                // whereas low-HP red danger numbers (e.g. red "51709/517091" on the right) share isCoral and must NOT reject!
                if (!isHpColor && (maxCh - minCh) >= 50 && maxCh >= 95) rightNonHpVividHits++
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

        // Low-HP partial player HP bar (5%..30% remaining HP on left, solid dark empty track in middle/right,
        // tolerating glowing red danger digits "51709/517091" on the right with near-zero foreign vivid orbs!)
        val leftRatio = if (leftTotal > 0) leftHpHits.toFloat() / leftTotal else 0f
        val midDarkRatio = if (midTotal > 0) midDarkHits.toFloat() / midTotal else 0f
        val rightDarkRatio = if (rightTotal > 0) rightDarkHits.toFloat() / rightTotal else 0f
        val rightNonHpVividRatio = if (rightTotal > 0) rightNonHpVividHits.toFloat() / rightTotal else 1f
        return leftRatio >= 0.22f &&
            (rightDarkRatio >= 0.45f || midDarkRatio >= 0.58f) &&
            rightNonHpVividRatio <= 0.12f
    }

    /**
     * Checks whether horizontal scanline [y] matches an EMPTY / 1-HP ToS player HP bar track
     * (e.g., 1/584001 self-drain or low-HP teams where the entire bar slot is dark charcoal
     * across x in [10%..68%] with near-zero vivid orb pixels).
     */
    private fun isEmptyHpTrackScanline(
        frame: PixelFrame,
        y: Int,
        scanLeft: Int,
        scanRight: Int,
        stepX: Int
    ): Boolean {
        var darkHits = 0
        var vividHits = 0
        var totalX = 0
        var x = scanLeft
        while (x <= scanRight) {
            totalX++
            val argb = frame.getArgb(x, y)
            val r = (argb ushr 16) and 0xFF
            val g = (argb ushr 8) and 0xFF
            val b = argb and 0xFF
            val maxCh = max(r, max(g, b))
            val minCh = min(r, min(g, b))
            if (maxCh <= 58 && (maxCh - minCh) <= 26) darkHits++
            if ((maxCh - minCh) >= 50 && maxCh >= 95) vividHits++
            x += stepX
        }
        return totalX > 0 &&
            (darkHits.toFloat() / totalX) >= 0.72f &&
            (vividHits.toFloat() / totalX) <= 0.08f
    }

    /**
     * Detects the iconic red/pink Heart medallion anchored at the left end of the ToS Player HP bar
     * (x in [1.5%..9.5%] of board width, directly above [rowTopPx]), separating a real 1-HP empty
     * player HP bar from dark non-battle UI / skill modals / Weijian orb rows!
     */
    private fun hasLeftHpHeartMedallion(
        frame: PixelFrame,
        leftPx: Float,
        rightPx: Float,
        rowTopPx: Float,
        cellH: Float
    ): Boolean {
        val boardW = (rightPx - leftPx).coerceAtLeast(60f)
        val hx0 = (leftPx + boardW * 0.015f).roundToInt().coerceIn(0, frame.width - 1)
        val hx1 = (leftPx + boardW * 0.095f).roundToInt().coerceIn(hx0, frame.width - 1)
        val hy0 = (rowTopPx - cellH * 0.32f).roundToInt().coerceIn(0, frame.height - 1)
        val hy1 = (rowTopPx - cellH * 0.01f).roundToInt().coerceIn(hy0, frame.height - 1)
        if (hx1 - hx0 < 4 || hy1 - hy0 < 4) return false

        val step = max(1, (hx1 - hx0) / 16)
        var warmHits = 0
        var total = 0
        var y = hy0
        while (y <= hy1) {
            var x = hx0
            while (x <= hx1) {
                val argb = frame.getArgb(x, y)
                val r = (argb ushr 16) and 0xFF
                val g = (argb ushr 8) and 0xFF
                val b = argb and 0xFF
                total++
                // Strictly Red/Coral/Pink heart hue (H <= 22° or H >= 310°), rejecting yellow Light orbs (H~50°)!
                val isHeartRedHue = if (g > b) {
                    (g - b) * 30 <= 11 * (r - b)
                } else {
                    r >= b || (r - g) * 6 >= 5 * (b - g)
                }
                if (r >= 135 && (r - g) >= 42 && (r - b) >= 20 && isHeartRedHue) {
                    warmHits++
                }
                x += step
            }
            y += step
        }
        return total > 0 && (warmHits.toFloat() / total) >= 0.09f
    }

    /**
     * Public helper for overlay background watcher: verifies an authentic horizontal ToS HP bar
     * strictly ABOVE [geometry.topPx] (never polluted by translucent overlays drawn inside the grid!).
     */
    fun hasHpBarBarrier(bitmap: Bitmap, geometry: BoardGeometry): Boolean {
        val frame = object : PixelFrame {
            override val width: Int = bitmap.width
            override val height: Int = bitmap.height
            override fun getArgb(x: Int, y: Int): Int =
                bitmap.getPixel(x.coerceIn(0, width - 1), y.coerceIn(0, height - 1))
        }
        return hasHpBarBarrier(frame, geometry.leftPx, geometry.rightPx, geometry.topPx, geometry.cellHeight)
    }

    /**
     * Detects the horizontal Tower of Saviors team HP bar directly above [rowTopPx]
     * (band: [rowTopPx - 0.30*cellH .. rowTopPx - 0.02*cellH]).
     * Multi-screenshot calibrated across ALL 4 authentic ToS HP-bar states:
     *  - Classic Coral-Red & modern Pink-Magenta HP bar (full & partial)
     *  - Bright Green HP bar (full & partial)
     *  - Cyan / Blue Shielded HP bar
     *  - Empty / 1-HP dark charcoal HP track paired with the left Heart medallion (prevents false 6x6 Weijian!)
     * Also enforces a vertical thinness check above the HP band so tall red/green event banners never false-trigger.
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

        val boardW = (rightPx - leftPx).coerceAtLeast(60f)
        val scanLeft = (leftPx + boardW * 0.08f).roundToInt().coerceAtLeast(0)
        val scanRight = (rightPx - boardW * 0.08f).roundToInt().coerceAtMost(frame.width - 1)
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
        if (scannedRows > 0 && hpLikeRows >= max(2, scannedRows / 4)) {
            // Vertical thinness guard: a real HP bar sits strictly below the 6 team portraits;
            // reject full-height solid red/green UI banners that fill the entire portrait band above it!
            val aboveTop = (rowTopPx - cellH * 0.65f).roundToInt().coerceAtLeast(0)
            val aboveBot = (rowTopPx - cellH * 0.34f).roundToInt().coerceIn(aboveTop, frame.height - 1)
            if (aboveBot - aboveTop >= 4) {
                val aboveStepY = max(1, (aboveBot - aboveTop) / 10)
                var aboveHits = 0
                var aboveScanned = 0
                var ay = aboveTop
                while (ay <= aboveBot) {
                    aboveScanned++
                    if (isHpBarScanline(frame, ay, scanLeft, scanRight, stepX)) {
                        aboveHits++
                    }
                    ay += aboveStepY
                }
                if (aboveScanned == 0 || (aboveHits.toFloat() / aboveScanned) < 0.50f) {
                    return true
                }
            } else {
                return true
            }
        }

        // Empty / 1-HP Player HP Bar Track (e.g., 1/584001 teams):
        // Dark charcoal horizontal slot across x in [10%..68%] paired with left red Heart medallion!
        val eTop = (rowTopPx - cellH * 0.22f).roundToInt().coerceAtLeast(0)
        val eBot = (rowTopPx - cellH * 0.02f).roundToInt().coerceIn(eTop, frame.height - 1)
        val eLeft = (leftPx + boardW * 0.10f).roundToInt().coerceAtLeast(0)
        val eRight = (leftPx + boardW * 0.68f).roundToInt().coerceAtMost(frame.width - 1)
        if (eBot - eTop >= 3 && eRight - eLeft >= 24) {
            val eStepX = max(2, (eRight - eLeft) / 50)
            val eStepY = max(1, (eBot - eTop) / 12)
            var emptyRows = 0
            var eScanned = 0
            var ey = eTop
            while (ey <= eBot) {
                eScanned++
                if (isEmptyHpTrackScanline(frame, ey, eLeft, eRight, eStepX)) {
                    emptyRows++
                }
                ey += eStepY
            }
            if (eScanned > 0 &&
                emptyRows >= max(4, (eScanned * 0.62f).roundToInt()) &&
                hasLeftHpHeartMedallion(frame, leftPx, rightPx, rowTopPx, cellH)
            ) {
                return true
            }
        }
        return false
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
        if (hasHp && ((darkCornerRatio >= 0.32f && (posContrastRatio >= 0.20f || vividCenterRatio >= 0.15f)) || posContrastRatio >= 0.45f)) {
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

        // Also scan for Empty / 1-HP dark charcoal HP bar tracks paired with the left red Heart medallion
        // so tapping '自动' on a 1/584001 team snaps onto the real 6x5 Player HP bar instead of jumping up to a Boss HP bar!
        // Note: an empty dark HP track merges directly with Row 0's dark top checkerboard seam, so allow up to 0.50*cellSize.
        val maxEmptyBarH = (cellSize * 0.50f).roundToInt().coerceAtLeast(maxBarH)
        val eLeft = (w * 0.10f).roundToInt()
        val eRight = (w * 0.68f).roundToInt()
        val eStepX = max(2, (eRight - eLeft) / 50)
        var curEmptyStart = -1
        var ey = yStart
        while (ey <= yEnd) {
            if (isEmptyHpTrackScanline(frame, ey, eLeft, eRight, eStepX)) {
                if (curEmptyStart < 0) curEmptyStart = ey
            } else {
                if (curEmptyStart >= 0) {
                    val runEnd = ey - stepY
                    val barH = runEnd - curEmptyStart + stepY
                    val estBoardTop = (curEmptyStart + cellSize * 0.24f).coerceAtMost(runEnd + cellSize * 0.06f)
                    if (barH in minBarH..maxEmptyBarH &&
                        hasLeftHpHeartMedallion(frame, 0f, w.toFloat(), estBoardTop, cellSize)
                    ) {
                        candidates.add(curEmptyStart to (estBoardTop - cellSize * 0.06f).roundToInt())
                    }
                    curEmptyStart = -1
                }
            }
            ey += stepY
        }
        if (curEmptyStart >= 0) {
            val runEnd = yEnd
            val barH = runEnd - curEmptyStart + stepY
            val estBoardTop = (curEmptyStart + cellSize * 0.24f).coerceAtMost(runEnd + cellSize * 0.06f)
            if (barH in minBarH..maxEmptyBarH &&
                hasLeftHpHeartMedallion(frame, 0f, w.toFloat(), estBoardTop, cellSize)
            ) {
                candidates.add(curEmptyStart to (estBoardTop - cellSize * 0.06f).roundToInt())
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
