package com.autodora.next.vision

import com.autodora.next.model.ShieldCategory
import com.autodora.next.model.WaveShieldChip
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class BattleIconScanResult(
    val detectedChips: List<WaveShieldChip>,
    val detectedBadgesSummary: List<String>,
    val enemyZoneHash: Long,
    val hasEnemyStatusIcons: Boolean
)

data class IconMatchCandidate(
    val template: OfficialIconTemplate,
    val score: Float,
    val marginOverRunnerUp: Float
)

/**
 * Zero-tap Stage-1 Battle Enemy Status Icon Matcher & Wave-Change Detector.
 *
 * Uses alpha-masked 12x12 perceptual RGB descriptors calibrated directly from 187 official
 * Tower of Saviors battle status icon assets (`GameplayIcons/ICON###`, covering all 6 elements),
 * with 2-stage coarse-to-fine alignment, fused with live board CV signals (Storm, Puzzle, Question orbs).
 */
object EnemyStatusIconMatcher {

    /**
     * Computes a stable hash of the battle enemy upper zone (y ∈ [18%..43%], x ∈ [18%..82%])
     * used to automatically detect when a battle advances to a new Wave.
     */
    fun computeBattleEnemyZoneHash(frame: VisionFrame): Long =
        StageInfoUiAnalyzer.computeRegionHash(
            frame = frame,
            xLeftRatio = 0.18f,
            xRightRatio = 0.82f,
            yTopRatio = 0.18f,
            yBotRatio = 0.43f
        )

    /**
     * Performs instant zero-tap visual icon scanning on a live battle frame and optional [RecognitionResult].
     */
    fun scanBattleScreen(
        frame: VisionFrame,
        boardRec: RecognitionResult? = null
    ): BattleIconScanResult {
        val chipsById = LinkedHashMap<String, WaveShieldChip>()
        val notes = ArrayList<String>()
        val enemyHash = computeBattleEnemyZoneHash(frame)

        // 1. Official-asset calibrated 12x12 alpha-masked status badge matching across the enemy strip
        val matchedIcons = matchOfficialStatusBadges(frame)
        for (hit in matchedIcons) {
            for (chip in hit.template.toChips()) {
                val existing = chipsById[chip.id]
                if (existing != null && chip.paramElementMask != 0 &&
                    chip.id in setOf("require_elements", "elem_burst_shield", "anti_first_wave", "forbid_all", "clear_all_element", "cross_shield")
                ) {
                    chipsById[chip.id] = existing.copy(
                        paramElementMask = existing.paramElementMask or chip.paramElementMask,
                        paramInt = max(existing.paramInt, chip.paramInt)
                    )
                } else {
                    chipsById.putIfAbsent(chip.id, chip)
                }
            }
            notes.add("${hit.template.defaultLabel} (${hit.template.iconId})")
        }
        // Fallback generic badge presence detector (handles unmapped / event-exclusive icons like Tornado)
        val genericBadgeClusters = if (matchedIcons.isEmpty()) countDistinctStatusIconClusters(frame) else matchedIcons.size

        // 2. Fuse board-level real CV signals
        if (boardRec != null) {
            if (boardRec.puzzleCellsDetected >= 3) {
                chipsById["puzzle_shield"] = WaveShieldChip(
                    id = "puzzle_shield",
                    label = "拼图盾 (${boardRec.puzzleCellsDetected}格)",
                    category = ShieldCategory.SHAPE_AND_PUZZLE,
                    paramInt = 1, // strict default
                    autoDetected = true
                )
                notes.add("盘面金框拼图(${boardRec.puzzleCellsDetected}格)")
            }
            if (boardRec.stormCellsDetected > 0) {
                notes.add("暴风区域(${boardRec.stormCellsDetected}格已自动绕开)")
            }
            if (boardRec.questionOrbsDetected >= 6) {
                notes.add("问号珠(${boardRec.questionOrbsDetected}颗)")
            }
        }

        return BattleIconScanResult(
            detectedChips = chipsById.values.toList(),
            detectedBadgesSummary = notes,
            enemyZoneHash = enemyHash,
            hasEnemyStatusIcons = matchedIcons.isNotEmpty() || genericBadgeClusters > 0 || notes.isNotEmpty()
        )
    }

    private class PrecomputedTemplate(
        val tmpl: OfficialIconTemplate,
        val fgIndices: IntArray,
        val tR: FloatArray,
        val tG: FloatArray,
        val tB: FloatArray,
        val dtR: FloatArray,
        val dtG: FloatArray,
        val dtB: FloatArray,
        val tNorm: Float,
        val meanR: Float,
        val meanG: Float,
        val meanB: Float
    )

    private val precomputedTemplates: List<PrecomputedTemplate> by lazy {
        OfficialEnemyIconTemplates.templates.mapNotNull { tmpl ->
            val arr = tmpl.argb12x12
            val idxs = ArrayList<Int>(arr.size)
            for (i in arr.indices) {
                if ((arr[i] ushr 24) != 0) idxs.add(i)
            }
            if (idxs.size < 24) return@mapNotNull null
            val n = idxs.size
            val fgIndices = IntArray(n) { idxs[it] }
            val tR = FloatArray(n)
            val tG = FloatArray(n)
            val tB = FloatArray(n)
            var sumR = 0f
            var sumG = 0f
            var sumB = 0f
            for (k in 0 until n) {
                val tv = arr[fgIndices[k]]
                val r = ((tv ushr 16) and 0xFF).toFloat()
                val g = ((tv ushr 8) and 0xFF).toFloat()
                val b = (tv and 0xFF).toFloat()
                tR[k] = r; tG[k] = g; tB[k] = b
                sumR += r; sumG += g; sumB += b
            }
            val tMean = (sumR + sumG + sumB) / (n * 3f)
            val dtR = FloatArray(n)
            val dtG = FloatArray(n)
            val dtB = FloatArray(n)
            var tNormSq = 0f
            for (k in 0 until n) {
                val dr = tR[k] - tMean
                val dg = tG[k] - tMean
                val db = tB[k] - tMean
                dtR[k] = dr; dtG[k] = dg; dtB[k] = db
                tNormSq += dr * dr + dg * dg + db * db
            }
            val tNorm = sqrt(tNormSq)
            if (tNorm < 1f) return@mapNotNull null
            PrecomputedTemplate(
                tmpl = tmpl,
                fgIndices = fgIndices,
                tR = tR, tG = tG, tB = tB,
                dtR = dtR, dtG = dtG, dtB = dtB,
                tNorm = tNorm,
                meanR = sumR / n,
                meanG = sumG / n,
                meanB = sumB / n
            )
        }
    }

    /**
     * Matches a 12x12 packed-RGB patch (`0xRRGGBB`, 144 ints) against all 187 official ToS icon
     * descriptors using alpha-masked zero-mean RGB NCC (0.75) + L1 similarity (0.25).
     * Transparent battle-background corners are excluded by each template's foreground mask bit!
     */
    fun matchPatchRgb12x12(
        patchRgb12x12: IntArray,
        minScore: Float = 0.80f,
        minMargin: Float = 0.04f
    ): IconMatchCandidate? {
        val nPix = OfficialEnemyIconTemplates.PIXEL_COUNT
        if (patchRgb12x12.size != nPix) return null

        // Unpack patch RGB once + quick energy check to skip flat sky/backdrop patches
        val pR = FloatArray(nPix)
        val pG = FloatArray(nPix)
        val pB = FloatArray(nPix)
        var sumRAll = 0f
        var sumGAll = 0f
        var sumBAll = 0f
        for (i in 0 until nPix) {
            val rgb = patchRgb12x12[i]
            val r = ((rgb ushr 16) and 0xFF).toFloat()
            val g = ((rgb ushr 8) and 0xFF).toFloat()
            val b = (rgb and 0xFF).toFloat()
            pR[i] = r; pG[i] = g; pB[i] = b
            sumRAll += r; sumGAll += g; sumBAll += b
        }
        val meanAll = (sumRAll + sumGAll + sumBAll) / (nPix * 3f)
        var varAll = 0f
        for (i in 0 until nPix) {
            val dr = pR[i] - meanAll
            val dg = pG[i] - meanAll
            val db = pB[i] - meanAll
            varAll += dr * dr + dg * dg + db * db
        }
        if (sqrt(varAll) < 300f) return null

        val patchMeanR = sumRAll / nPix
        val patchMeanG = sumGAll / nPix
        val patchMeanB = sumBAll / nPix

        var bestTmpl: OfficialIconTemplate? = null
        var bestScore = -1f
        var secondScore = -1f

        for (pt in precomputedTemplates) {
            // Fast mean-chromaticity reject: skips templates with wildly incompatible overall hue
            if (abs(patchMeanR - pt.meanR) + abs(patchMeanG - pt.meanG) + abs(patchMeanB - pt.meanB) > 250f) {
                continue
            }
            val fgIdx = pt.fgIndices
            val n = fgIdx.size
            var pSum = 0f
            for (k in 0 until n) {
                val i = fgIdx[k]
                pSum += pR[i] + pG[i] + pB[i]
            }
            val denom = n * 3f
            val pMean = pSum / denom

            var dot = 0f
            var pNormSq = 0f
            var l1Diff = 0f
            val dtR = pt.dtR; val dtG = pt.dtG; val dtB = pt.dtB
            val tR = pt.tR; val tG = pt.tG; val tB = pt.tB
            for (k in 0 until n) {
                val i = fgIdx[k]
                val pr = pR[i]; val pg = pG[i]; val pb = pB[i]
                val dpr = pr - pMean
                val dpg = pg - pMean
                val dpb = pb - pMean
                dot += dpr * dtR[k] + dpg * dtG[k] + dpb * dtB[k]
                pNormSq += dpr * dpr + dpg * dpg + dpb * dpb
                l1Diff += abs(pr - tR[k]) + abs(pg - tG[k]) + abs(pb - tB[k])
            }
            val pNorm = sqrt(pNormSq)
            if (pNorm < 180f) continue

            val ncc = (dot / (pNorm * pt.tNorm)).coerceIn(-1f, 1f)
            val l1Sim = (1f - (l1Diff / (denom * 255f))).coerceIn(0f, 1f)
            val score = 0.75f * ncc + 0.25f * l1Sim

            // Treat different (chipId, paramInt, paramElementMask) as distinct competing shields (e.g., 3C vs 4C vs 6C!)
            val tmpl = pt.tmpl
            val isSameShield = bestTmpl != null &&
                bestTmpl.chipId == tmpl.chipId &&
                bestTmpl.paramInt == tmpl.paramInt &&
                bestTmpl.paramElementMask == tmpl.paramElementMask

            if (score > bestScore) {
                if (bestTmpl != null && !isSameShield) {
                    secondScore = max(secondScore, bestScore)
                }
                bestScore = score
                bestTmpl = tmpl
            } else if (!isSameShield && score > secondScore) {
                secondScore = score
            }
        }

        val winner = bestTmpl ?: return null
        val margin = if (secondScore < 0f) 1f else (bestScore - secondScore)
        val accepted = (bestScore >= 0.92f && margin >= 0.012f) ||
            (bestScore >= minScore && margin >= minMargin) ||
            (bestScore >= (minScore - 0.045f) && margin >= 0.085f)
        return if (accepted) {
            IconMatchCandidate(winner, bestScore, margin)
        } else {
            null
        }
    }

    /**
     * Downsamples a square region `[x0, y0, size]` from [frame] into a 12x12 packed RGB descriptor.
     */
    fun samplePatchRgb12x12(frame: VisionFrame, x0: Int, y0: Int, size: Int): IntArray {
        val g = OfficialEnemyIconTemplates.GRID_SIZE
        val out = IntArray(g * g)
        val w = frame.width
        val h = frame.height
        val cell = size / g.toFloat()
        for (gy in 0 until g) {
            val py0 = (y0 + gy * cell).toInt().coerceIn(0, h - 1)
            val py1 = (y0 + (gy + 1) * cell).toInt().coerceIn(py0 + 1, h)
            for (gx in 0 until g) {
                val px0 = (x0 + gx * cell).toInt().coerceIn(0, w - 1)
                val px1 = (x0 + (gx + 1) * cell).toInt().coerceIn(px0 + 1, w)
                var sr = 0
                var sg = 0
                var sb = 0
                var cnt = 0
                val stepY = max(1, (py1 - py0) / 2)
                val stepX = max(1, (px1 - px0) / 2)
                var py = py0
                while (py < py1) {
                    var px = px0
                    while (px < px1) {
                        val argb = frame.getArgb(px, py)
                        sr += (argb ushr 16) and 0xFF
                        sg += (argb ushr 8) and 0xFF
                        sb += argb and 0xFF
                        cnt++
                        px += stepX
                    }
                    py += stepY
                }
                if (cnt > 0) {
                    out[gy * g + gx] = ((sr / cnt) shl 16) or ((sg / cnt) shl 8) or (sb / cnt)
                }
            }
        }
        return out
    }

    /**
     * Two-stage coarse-to-fine scanner across the enemy status-icon strip below "CD N"
     * (`y ∈ [18%..34%]`, `x ∈ [8%..92%]`):
     *  - Coarse scan locates compact dark-bordered vivid/bright icon candidates (~5.0% screen width).
     *  - Fine 1px-level refinement aligns `(x, y, size)` cleanly before accepting a match!
     */
    private fun matchOfficialStatusBadges(frame: VisionFrame): List<IconMatchCandidate> {
        val w = frame.width
        val h = frame.height
        if (w < 160 || h < 280) return emptyList()

        // Start strictly below the top HUD/floating bar (y >= 19.5% of H)
        val yStart = (h * 0.195f).toInt()
        val yEnd = (h * 0.345f).toInt()
        val xStart = (w * 0.08f).toInt()
        val xEnd = (w * 0.92f).toInt()
        val baseSize = max(18, (w * 0.049f).toInt())
        val coarseStride = max(3, baseSize / 5)

        val candidateCenters = ArrayList<Pair<Int, Int>>()
        var y = yStart
        while (y + baseSize <= yEnd) {
            var x = xStart
            while (x + baseSize <= xEnd) {
                if (isLikelyBadgePatch(frame, x, y, baseSize)) {
                    val cx = x + baseSize / 2
                    val cy = y + baseSize / 2
                    // Non-maximum spatial clustering: merge nearby candidate detections within 0.65 * baseSize
                    val mergeR = (baseSize * 0.65f).toInt()
                    val already = candidateCenters.any { abs(it.first - cx) <= mergeR && abs(it.second - cy) <= mergeR }
                    if (!already) {
                        candidateCenters.add(cx to cy)
                    }
                }
                x += coarseStride
            }
            y += coarseStride
        }

        val bestByKey = LinkedHashMap<String, IconMatchCandidate>()
        val refineSizes = intArrayOf(
            max(16, (w * 0.046f).toInt()),
            max(18, (w * 0.050f).toInt()),
            max(20, (w * 0.054f).toInt())
        )
        val fineStep = max(1, baseSize / 16)
        val fineRadius = max(2, baseSize / 6)

        for ((cx, cy) in candidateCenters) {
            var localBest: IconMatchCandidate? = null
            for (size in refineSizes) {
                val half = size / 2
                var dy = -fineRadius
                while (dy <= fineRadius) {
                    var dx = -fineRadius
                    while (dx <= fineRadius) {
                        val px = (cx + dx - half).coerceIn(0, w - size)
                        val py = (cy + dy - half).coerceIn(0, h - size)
                        val patch = samplePatchRgb12x12(frame, px, py, size)
                        val hit = matchPatchRgb12x12(patch, minScore = 0.80f, minMargin = 0.04f)
                        if (hit != null && (localBest == null || hit.score > localBest.score)) {
                            localBest = hit
                        }
                        dx += fineStep
                    }
                    dy += fineStep
                }
            }
            if (localBest != null) {
                val key = "${localBest.template.chipId}_${localBest.template.paramElementMask}"
                val prev = bestByKey[key]
                if (prev == null || localBest.score > prev.score) {
                    bestByKey[key] = localBest
                }
            }
        }
        return bestByKey.values.sortedByDescending { it.score }
    }

    private fun isLikelyBadgePatch(frame: VisionFrame, x0: Int, y0: Int, size: Int): Boolean {
        val step = max(2, size / 7)
        var darkPixels = 0
        var brightOrVivid = 0
        var y = 0
        while (y < size) {
            var x = 0
            while (x < size) {
                val argb = frame.getArgb(x0 + x, y0 + y)
                val r = (argb ushr 16) and 0xFF
                val g = (argb ushr 8) and 0xFF
                val b = argb and 0xFF
                val maxC = max(r, max(g, b))
                val minC = min(r, min(g, b))
                if (maxC < 84) darkPixels++
                if (maxC >= 150 || (maxC >= 105 && (maxC - minC) >= 45)) brightOrVivid++
                x += step
            }
            y += step
        }
        return darkPixels >= 3 && brightOrVivid >= 4
    }

    /**
     * Counts compact high-contrast status icon badges below "CD N" (y ∈ [19.5%..31%]) regardless of
     * whether the icon is in the curated shield dictionary, so Stage-2 inspection still triggers on
     * exotic / event-exclusive enemy skills.
     */
    private fun countDistinctStatusIconClusters(frame: VisionFrame): Int {
        val w = frame.width
        val h = frame.height
        if (w < 120 || h < 200) return 0

        val y0 = (h * 0.195f).toInt()
        val y1 = (h * 0.31f).toInt()
        val x0 = (w * 0.14f).toInt()
        val x1 = (w * 0.86f).toInt()
        val step = max(2, w / 270)
        val cellSize = max(12, (w * 0.045f).toInt())

        var iconClusters = 0
        var x = x0
        while (x + cellSize < x1) {
            var y = y0
            var foundInCol = false
            while (y + cellSize < y1) {
                var darkBorder = 0
                var brightInterior = 0
                var vividInterior = 0
                var samples = 0
                for (dy in 0 until cellSize step step) {
                    for (dx in 0 until cellSize step step) {
                        val argb = frame.getArgb(x + dx, y + dy)
                        val r = (argb ushr 16) and 0xFF
                        val g = (argb ushr 8) and 0xFF
                        val b = argb and 0xFF
                        val maxCh = max(r, max(g, b))
                        val minCh = min(r, min(g, b))
                        val isBorder = dx < step * 2 || dy < step * 2 || dx >= cellSize - step * 2 || dy >= cellSize - step * 2
                        if (isBorder) {
                            if (maxCh < 58) darkBorder++
                        } else {
                            if (maxCh >= 165) brightInterior++
                            if (maxCh >= 140 && (maxCh - minCh) >= 55) vividInterior++
                        }
                        samples++
                    }
                }
                if (samples > 0 && darkBorder >= 3 && (brightInterior >= 4 || vividInterior >= 4)) {
                    iconClusters++
                    foundInCol = true
                    break
                }
                y += cellSize / 2
            }
            x += if (foundInCol) cellSize else cellSize / 2
        }
        return iconClusters
    }
}
