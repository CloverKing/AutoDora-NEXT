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
    val template: OfficialEnemyIconTemplates.IconTemplate,
    val score: Float,
    val marginOverRunnerUp: Float
)

/**
 * Zero-tap Stage-1 Battle Enemy Status Icon Matcher & Wave-Change Detector.
 *
 * Uses compact 8x8 perceptual RGB descriptors calibrated directly from 44 official
 * Tower of Saviors battle status icon assets (`GameplayIcons/ICON###`), fused with
 * live board CV signals (Storm cells, Golden Puzzle borders, Question orbs).
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

        // 1. Official-asset calibrated status badge template matching across the enemy badge strip
        val matchedIcons = matchOfficialStatusBadges(frame)
        for (hit in matchedIcons) {
            val chip = hit.template.toChip().copy(autoDetected = true)
            chipsById.putIfAbsent(chip.id, chip)
            notes.add("${hit.template.defaultLabel} (${hit.template.iconId})")
        }
        // Fallback generic badge cluster presence detector (handles exotic / unmapped event status badges)
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

    /**
     * Matches an 8x8 packed-RGB patch (`0xRRGGBB`, 64 ints) against all 44 official ToS icon descriptors
     * using combined zero-mean RGB NCC (0.65) + L1 RGB similarity (0.35).
     */
    fun matchPatchRgb8x8(
        patchRgb8x8: IntArray,
        minScore: Float = 0.82f,
        minMargin: Float = 0.03f
    ): IconMatchCandidate? {
        if (patchRgb8x8.size != 64) return null

        // Precompute zero-mean vector norm for candidate patch
        val pVec = FloatArray(192)
        var pSum = 0f
        for (i in 0 until 64) {
            val rgb = patchRgb8x8[i]
            val r = ((rgb ushr 16) and 0xFF).toFloat()
            val g = ((rgb ushr 8) and 0xFF).toFloat()
            val b = (rgb and 0xFF).toFloat()
            pVec[i * 3] = r
            pVec[i * 3 + 1] = g
            pVec[i * 3 + 2] = b
            pSum += r + g + b
        }
        val pMean = pSum / 192f
        var pNormSq = 0f
        for (k in 0 until 192) {
            val d = pVec[k] - pMean
            pNormSq += d * d
        }
        val pNorm = sqrt(pNormSq)
        // Reject flat/empty patches (dark background or uniform sky)
        if (pNorm < 220f) return null

        var bestTmpl: OfficialEnemyIconTemplates.IconTemplate? = null
        var bestScore = -1f
        var secondScore = -1f

        for (tmpl in OfficialEnemyIconTemplates.templates) {
            val tArr = tmpl.rgb8x8
            var tSum = 0f
            for (i in 0 until 64) {
                val rgb = tArr[i]
                tSum += ((rgb ushr 16) and 0xFF) + ((rgb ushr 8) and 0xFF) + (rgb and 0xFF)
            }
            val tMean = tSum / 192f
            var dot = 0f
            var tNormSq = 0f
            var l1Diff = 0f
            for (i in 0 until 64) {
                val rgb = tArr[i]
                val tr = ((rgb ushr 16) and 0xFF).toFloat()
                val tg = ((rgb ushr 8) and 0xFF).toFloat()
                val tb = (rgb and 0xFF).toFloat()

                val pr = pVec[i * 3]
                val pg = pVec[i * 3 + 1]
                val pb = pVec[i * 3 + 2]

                val dpr = pr - pMean
                val dpg = pg - pMean
                val dpb = pb - pMean
                val dtr = tr - tMean
                val dtg = tg - tMean
                val dtb = tb - tMean

                dot += dpr * dtr + dpg * dtg + dpb * dtb
                tNormSq += dtr * dtr + dtg * dtg + dtb * dtb
                l1Diff += abs(pr - tr) + abs(pg - tg) + abs(pb - tb)
            }
            val tNorm = sqrt(tNormSq)
            if (tNorm < 1f) continue
            val ncc = (dot / (pNorm * tNorm)).coerceIn(-1f, 1f)
            val l1Sim = (1f - (l1Diff / (192f * 255f))).coerceIn(0f, 1f)
            val score = 0.65f * ncc + 0.35f * l1Sim

            if (score > bestScore) {
                // Only treat a different chipId as a competing runner-up
                if (bestTmpl != null && bestTmpl.chipId != tmpl.chipId) {
                    secondScore = max(secondScore, bestScore)
                }
                bestScore = score
                bestTmpl = tmpl
            } else if (bestTmpl != null && tmpl.chipId != bestTmpl.chipId && score > secondScore) {
                secondScore = score
            }
        }

        val winner = bestTmpl ?: return null
        val margin = if (secondScore < 0f) 1f else (bestScore - secondScore)
        return if (bestScore >= minScore && margin >= minMargin) {
            IconMatchCandidate(winner, bestScore, margin)
        } else {
            null
        }
    }

    /**
     * Downsamples a square region `[x0, y0, size]` from [frame] into an 8x8 packed RGB descriptor.
     */
    fun samplePatchRgb8x8(frame: VisionFrame, x0: Int, y0: Int, size: Int): IntArray {
        val out = IntArray(64)
        val w = frame.width
        val h = frame.height
        val cell = size / 8f
        for (gy in 0 until 8) {
            val py0 = (y0 + gy * cell).toInt().coerceIn(0, h - 1)
            val py1 = (y0 + (gy + 1) * cell).toInt().coerceIn(py0 + 1, h)
            for (gx in 0 until 8) {
                val px0 = (x0 + gx * cell).toInt().coerceIn(0, w - 1)
                val px1 = (x0 + (gx + 1) * cell).toInt().coerceIn(px0 + 1, w)
                var sr = 0
                var sg = 0
                var sb = 0
                var cnt = 0
                val stepY = max(1, (py1 - py0) / 3)
                val stepX = max(1, (px1 - px0) / 3)
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
                    out[gy * 8 + gx] = ((sr / cnt) shl 16) or ((sg / cnt) shl 8) or (sb / cnt)
                }
            }
        }
        return out
    }

    /**
     * Scans the enemy status-icon strip below "CD N" (y ∈ [15%..34%], x ∈ [8%..92%]) for compact
     * high-contrast enemy status badges and matches each candidate against [OfficialEnemyIconTemplates].
     */
    private fun matchOfficialStatusBadges(frame: VisionFrame): List<IconMatchCandidate> {
        val w = frame.width
        val h = frame.height
        if (w < 160 || h < 280) return emptyList()

        val yStart = (h * 0.15f).toInt()
        val yEnd = (h * 0.34f).toInt()
        val xStart = (w * 0.08f).toInt()
        val xEnd = (w * 0.92f).toInt()
        val badgeSizes = intArrayOf(
            max(16, (w * 0.044f).toInt()),
            max(20, (w * 0.056f).toInt())
        )

        val bestByChip = LinkedHashMap<String, IconMatchCandidate>()
        for (size in badgeSizes) {
            val stride = max(4, size / 4)
            var y = yStart
            while (y + size <= yEnd) {
                var x = xStart
                while (x + size <= xEnd) {
                    if (isLikelyBadgePatch(frame, x, y, size)) {
                        val patch = samplePatchRgb8x8(frame, x, y, size)
                        val hit = matchPatchRgb8x8(patch, minScore = 0.84f, minMargin = 0.035f)
                        if (hit != null) {
                            val prev = bestByChip[hit.template.chipId]
                            if (prev == null || hit.score > prev.score) {
                                bestByChip[hit.template.chipId] = hit
                            }
                            x += size - stride // Skip past matched badge
                        }
                    }
                    x += stride
                }
                y += stride
            }
        }
        return bestByChip.values.sortedByDescending { it.score }
    }

    private fun isLikelyBadgePatch(frame: VisionFrame, x0: Int, y0: Int, size: Int): Boolean {
        val step = max(2, size / 6)
        var darkBorder = 0
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
                val onEdge = x < step || y < step || x >= size - step || y >= size - step
                if (onEdge) {
                    if (maxC < 65) darkBorder++
                } else {
                    if (maxC >= 150 || (maxC >= 115 && (maxC - minC) >= 50)) brightOrVivid++
                }
                x += step
            }
            y += step
        }
        return darkBorder >= 4 && brightOrVivid >= 4
    }

    /**
     * Counts compact high-contrast status icon badges below "CD N" (y ∈ [19%..30%]) regardless of
     * whether the icon is in the curated shield dictionary, so Stage-2 inspection still triggers on
     * exotic / event-exclusive enemy skills.
     */
    private fun countDistinctStatusIconClusters(frame: VisionFrame): Int {
        val w = frame.width
        val h = frame.height
        if (w < 120 || h < 200) return 0

        val y0 = (h * 0.19f).toInt()
        val y1 = (h * 0.30f).toInt()
        val x0 = (w * 0.14f).toInt()
        val x1 = (w * 0.86f).toInt()
        val step = max(2, w / 270)
        val cellSize = max(12, (w * 0.042f).toInt())

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
                            if (maxCh < 55) darkBorder++
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
