package com.autodora.next.vision

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class UiTapTarget(val x: Float, val y: Float)

/**
 * Real-screenshot calibrated UI analyzer for Tower of Saviors 「齿轮 ⚙ → 關卡資訊」 modal.
 *
 * Calibrated directly on real 1080×2400 (Emulator) and 1200×2670 (Xiaomi 15) battle & Stage Info captures:
 *  - Battle top-right Gear (⚙): x = 0.930 * W, y = topOffset + 0.014 * playH (consistent within 0.17% across devices!)
 *  - Stage Info bottom buttons: 「離開戰鬥」 (x 0.10..0.44) & 「取消」 (x 0.56..0.90), y 0.900..0.948, vivid dark-teal-blue
 *  - Collapsed skill arrow (▶): pure white triangle at x ∈ [0.080..0.130], L / R >= 1.55 and balanced T / B
 *  - Expanded skill arrow (▼): pure white triangle at x ∈ [0.080..0.130], T / B >= 1.55 and balanced L / R
 */
object StageInfoUiAnalyzer {

    /**
     * Computes exact screen tap point for the top-right double-gear (⚙) button in battle across any aspect ratio.
     */
    fun findGearTapPoint(screenW: Int, screenH: Int): UiTapTarget {
        val w = screenW.toFloat()
        val h = screenH.toFloat()
        val playH = w * 16f / 9f
        val topOff = ((h - playH) * 0.5f).coerceAtLeast(0f)
        return UiTapTarget(
            x = w * 0.932f,
            y = (topOff + playH * 0.015f).coerceIn(h * 0.04f, h * 0.18f)
        )
    }

    /**
     * Computes exact tap point for the bottom-right 「取消」 button inside 「關卡資訊」.
     */
    fun findCancelTapPoint(screenW: Int, screenH: Int): UiTapTarget =
        UiTapTarget(x = screenW * 0.73f, y = screenH * 0.924f)

    /**
     * Verifies whether the ToS 「關卡資訊」 modal is currently open by checking the distinctive
     * pair of dark-teal-blue bottom action buttons (離開戰鬥 / 取消) at y ∈ [0.902..0.945].
     */
    fun isStageInfoModalOpen(frame: VisionFrame): Boolean {
        val w = frame.width
        val h = frame.height
        if (w < 100 || h < 180) return false

        fun blueButtonRatio(xStartRatio: Float, xEndRatio: Float): Float {
            var hits = 0
            var total = 0
            val x0 = (w * xStartRatio).toInt()
            val x1 = (w * xEndRatio).toInt()
            val y0 = (h * 0.905f).toInt()
            val y1 = (h * 0.943f).toInt()
            val stepX = max(1, (x1 - x0) / 16)
            val stepY = max(1, (y1 - y0) / 6)
            var y = y0
            while (y <= y1) {
                var x = x0
                while (x <= x1) {
                    val argb = frame.getArgb(x, y)
                    val r = (argb ushr 16) and 0xFF
                    val g = (argb ushr 8) and 0xFF
                    val b = argb and 0xFF
                    // Teal-blue button body (excluding crisp white label text)
                    if (b >= 70 && b > r + 35 && r <= 65) {
                        hits++
                    }
                    total++
                    x += stepX
                }
                y += stepY
            }
            return if (total > 0) hits.toFloat() / total else 0f
        }

        val leftBtn = blueButtonRatio(0.12f, 0.42f)
        val rightBtn = blueButtonRatio(0.58f, 0.88f)
        return leftBtn >= 0.35f && rightBtn >= 0.35f
    }

    /**
     * Finds all COLLAPSED white right-pointing triangles (▶) inside the 「關卡資訊」 skill panel.
     * Reliably distinguishes ▶ (L / R >= 1.50, balanced T/B) from already-expanded ▼ (T / B >= 1.50)
     * and from the HP water-drop icon above the skill list!
     */
    fun findCollapsedSkillArrows(frame: VisionFrame): List<UiTapTarget> {
        val w = frame.width
        val h = frame.height
        if (w < 120 || h < 200) return emptyList()

        val xMin = (w * 0.080f).toInt().coerceAtLeast(0)
        val xMax = (w * 0.132f).toInt().coerceAtMost(w - 1)
        val yMin = (h * 0.22f).toInt().coerceAtLeast(0)
        val yMax = (h * 0.85f).toInt().coerceAtMost(h - 1)

        // Step 1: Collect vertical bands containing bright neutral-white pixels in the triangle strip
        val rowWhiteCounts = IntArray(yMax - yMin + 1)
        for (y in yMin..yMax) {
            var cnt = 0
            for (x in xMin..xMax) {
                if (isCrispWhite(frame.getArgb(x, y))) cnt++
            }
            rowWhiteCounts[y - yMin] = cnt
        }

        val results = ArrayList<UiTapTarget>()
        val minBandH = max(8, (h * 0.006f).toInt())
        val maxBandH = max(24, (h * 0.024f).toInt())

        var idx = 0
        while (idx < rowWhiteCounts.size) {
            if (rowWhiteCounts[idx] < 2) {
                idx++
                continue
            }
            val startIdx = idx
            while (idx < rowWhiteCounts.size && rowWhiteCounts[idx] >= 1) {
                idx++
            }
            val endIdx = idx - 1
            val bandH = endIdx - startIdx + 1
            if (bandH !in minBandH..maxBandH) continue

            val y0 = yMin + startIdx
            val y1 = yMin + endIdx
            var minX = xMax
            var maxX = xMin
            var whiteTotal = 0
            val pts = ArrayList<Pair<Int, Int>>(128)
            for (y in y0..y1) {
                for (x in xMin..xMax) {
                    if (isCrispWhite(frame.getArgb(x, y))) {
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        whiteTotal++
                        pts.add(x to y)
                    }
                }
            }
            val bandW = maxX - minX + 1
            if (bandW < max(6, (w * 0.012f).toInt()) || bandW > (w * 0.042f).toInt()) continue
            if (whiteTotal < max(16, (bandW * bandH) / 4)) continue

            // Check dark left margin right beside the triangle (rejects stat bar / borders)
            val marginX = (minX - max(4, (w * 0.015f).toInt())).coerceAtLeast(0)
            val midY = (y0 + y1) / 2
            if (luminance(frame.getArgb(marginX, midY)) > 85) continue

            // Geometry test: Right-pointing triangle ▶ has heavy LEFT half vs RIGHT half, balanced TOP vs BOTTOM!
            val cx = (minX + maxX) * 0.5f
            val cy = (y0 + y1) * 0.5f
            var leftCnt = 0
            var rightCnt = 0
            var topCnt = 0
            var botCnt = 0
            for ((px, py) in pts) {
                if (px < cx) leftCnt++ else if (px > cx) rightCnt++
                if (py < cy) topCnt++ else if (py > cy) botCnt++
            }
            val isRightTriangle = leftCnt > rightCnt * 1.45f &&
                topCnt > 0 && botCnt > 0 &&
                (max(topCnt, botCnt).toFloat() / min(topCnt, botCnt)) <= 1.55f

            if (isRightTriangle) {
                // Verify skill header text exists to the right of the ▶ triangle (yellow or white text on dark bg)
                if (hasSkillTitleRightOf(frame, maxX, midY, w)) {
                    results.add(UiTapTarget(x = cx, y = cy))
                }
            }
        }
        return results
    }

    /**
     * Detects enemy portrait tap targets inside the currently expanded Wave card of 「關卡資訊」.
     * In ToS Stage Info, enemy portraits sit in a centered horizontal row directly above the enemy name/stats bar
     * (each portrait has a crisp gold/silver rectangular border). Returns tap centers left-to-right.
     */
    fun findEnemyPortraitsInStageInfo(frame: VisionFrame): List<UiTapTarget> {
        val w = frame.width
        val h = frame.height
        if (w < 120 || h < 200) return emptyList()

        // Locate the horizontal strip with peak golden/beige portrait borders in y ∈ [0.14..0.60]
        val yMin = (h * 0.14f).toInt()
        val yMax = (h * 0.58f).toInt()
        val xMin = (w * 0.12f).toInt()
        val xMax = (w * 0.88f).toInt()
        val step = max(2, w / 270)

        // Find rows with vivid portrait colors (excluding uniform dark background)
        val rowEnergy = IntArray(yMax - yMin)
        for (y in yMin until yMax step step) {
            var brightCols = 0
            for (x in xMin until xMax step step) {
                val argb = frame.getArgb(x, y)
                val r = (argb ushr 16) and 0xFF
                val g = (argb ushr 8) and 0xFF
                val b = argb and 0xFF
                val maxCh = max(r, max(g, b))
                val minCh = min(r, min(g, b))
                if (maxCh >= 120 && (maxCh - minCh) >= 35) {
                    brightCols++
                }
            }
            rowEnergy[y - yMin] = brightCols
        }

        // Find portrait band (~0.06..0.10 of screen height, centered portraits)
        val bandH = (h * 0.072f).toInt().coerceAtLeast(20)
        var bestY = -1
        var bestScore = 0
        for (y in yMin until (yMax - bandH) step step) {
            var sum = 0
            for (dy in 0 until bandH step step) {
                sum += rowEnergy[y - yMin + dy]
            }
            if (sum > bestScore) {
                bestScore = sum
                bestY = y
            }
        }
        if (bestY < 0 || bestScore < 18) return emptyList()

        val cy = bestY + bandH * 0.5f
        // Project across the lower 65% of the portrait band (avoiding top-right status badges like [4]).
        // A column belongs to an enemy portrait if either its interior has art brightness/saturation OR
        // its bottom frame edge has the crisp bright gold/silver portrait border (robust even on dark monster art!).
        val lowerStartDy = (bandH * 0.34f).toInt()
        val lowerEndDy = (bandH * 1.06f).toInt().coerceAtMost(h - 1 - bestY)
        val colActive = BooleanArray((xMax - xMin) / step + 1)
        for (ci in colActive.indices) {
            val x = xMin + ci * step
            var hits = 0
            var borderHits = 0
            var samples = 0
            for (dy in lowerStartDy until lowerEndDy step step) {
                val argb = frame.getArgb(x, bestY + dy)
                val r = (argb ushr 16) and 0xFF
                val g = (argb ushr 8) and 0xFF
                val b = argb and 0xFF
                val maxCh = max(r, max(g, b))
                val minCh = min(r, min(g, b))
                val lum = luminance(argb)
                if (lum > 56 || (maxCh >= 95 && (maxCh - minCh) >= 32)) hits++
                // Crisp portrait bottom frame border (gold/silver high luminance edge)
                if (dy >= (bandH * 0.80f).toInt() && lum >= 115) borderHits++
                samples++
            }
            colActive[ci] = samples > 0 && (hits.toFloat() / samples >= 0.25f || borderHits >= 1)
        }
        // Morphological closing across tiny internal dark art gaps (<= 3.5% screen width) while preserving ~10% inter-portrait gaps
        val maxGapCols = max(2, (w * 0.032f).toInt() / step)
        var gi = 0
        while (gi < colActive.size) {
            if (!colActive[gi]) {
                val gs = gi
                while (gi < colActive.size && !colActive[gi]) gi++
                if (gs > 0 && gi < colActive.size && (gi - gs) <= maxGapCols) {
                    for (k in gs until gi) colActive[k] = true
                }
            } else {
                gi++
            }
        }

        val minPortraitW = (w * 0.11f).toInt()
        val maxPortraitW = (w * 0.26f).toInt()
        val portraits = ArrayList<UiTapTarget>()
        var c = 0
        while (c < colActive.size) {
            if (!colActive[c]) {
                c++
                continue
            }
            val startC = c
            while (c < colActive.size && colActive[c]) c++
            val endC = c - 1
            val pxW = (endC - startC + 1) * step
            if (pxW in minPortraitW..maxPortraitW) {
                val cx = xMin + ((startC + endC) * 0.5f) * step
                portraits.add(UiTapTarget(cx, cy))
            }
        }
        return portraits
    }

    /**
     * Computes a compact 64-bit difference hash (dHash) of a vertical screen slice [yTopRatio..yBotRatio].
     * Used for:
     *  - Detecting when scrolling inside 「關卡資訊」 reached the bottom (hammingDistance <= 3)
     *  - Detecting battle Wave transitions automatically (hammingDistance >= 10)
     */
    fun computeRegionHash(
        frame: VisionFrame,
        xLeftRatio: Float = 0.08f,
        xRightRatio: Float = 0.92f,
        yTopRatio: Float = 0.25f,
        yBotRatio: Float = 0.83f
    ): Long {
        val cols = 9
        val rows = 8
        val x0 = frame.width * xLeftRatio
        val x1 = frame.width * xRightRatio
        val y0 = frame.height * yTopRatio
        val y1 = frame.height * yBotRatio
        val grid = IntArray(cols * rows)
        for (r in 0 until rows) {
            val py = (y0 + (y1 - y0) * (r + 0.5f) / rows).toInt().coerceIn(0, frame.height - 1)
            for (c in 0 until cols) {
                val px = (x0 + (x1 - x0) * (c + 0.5f) / cols).toInt().coerceIn(0, frame.width - 1)
                grid[r * cols + c] = luminance(frame.getArgb(px, py))
            }
        }
        var hash = 0L
        var bit = 0
        for (r in 0 until rows) {
            for (c in 0 until (cols - 1)) {
                if (grid[r * cols + c] > grid[r * cols + c + 1]) {
                    hash = hash or (1L shl bit)
                }
                bit++
            }
        }
        return hash
    }

    fun hammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    private fun hasSkillTitleRightOf(frame: VisionFrame, triMaxX: Int, midY: Int, w: Int): Boolean {
        val scanStart = (triMaxX + (w * 0.015f).toInt()).coerceAtMost(w - 1)
        val scanEnd = (triMaxX + (w * 0.42f).toInt()).coerceAtMost(w - 1)
        var textPixels = 0
        val dyRange = max(8, (frame.height * 0.008f).toInt())
        for (y in (midY - dyRange).coerceAtLeast(0)..(midY + dyRange).coerceAtMost(frame.height - 1) step 2) {
            for (x in scanStart..scanEnd step 3) {
                val argb = frame.getArgb(x, y)
                val r = (argb ushr 16) and 0xFF
                val g = (argb ushr 8) and 0xFF
                val b = argb and 0xFF
                // Crisp yellow title 【...】 OR white/grey text on dark panel
                if (max(r, max(g, b)) >= 145) {
                    textPixels++
                }
            }
        }
        return textPixels >= 12
    }

    private fun isCrispWhite(argb: Int): Boolean {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return r >= 185 && g >= 185 && b >= 185 && abs(r - b) <= 35
    }

    private fun luminance(argb: Int): Int {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 30 + g * 59 + b * 11) / 100
    }
}
