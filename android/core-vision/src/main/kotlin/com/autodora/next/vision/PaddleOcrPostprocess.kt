package com.autodora.next.vision

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure Kotlin post-processing helpers for RapidAI / PaddleOCR PP-OCRv5 ONNX models
 * (zero Android Bitmap dependencies so every detector/CTC/color invariant runs in JVM tests).
 */
data class OcrBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val score: Float
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val midY: Float get() = (top + bottom) * 0.5f
}

data class ForegroundColorStats(
    val fgPixelCount: Int,
    val p50Lum: Int,
    val p95Lum: Int,
    val vividRatio: Float,
    val isGreyBypassed: Boolean
)

data class OcrTextLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val confidence: Float,
    val p50Lum: Int = 220,
    val p95Lum: Int = 250,
    val vividRatio: Float = 0.2f,
    val isGreyBypassed: Boolean = false
)

data class OcrFrameResult(
    val lines: List<OcrTextLine>,
    val elapsedMs: Long,
    val modalKind: DarkModalKind
)

object PaddleOcrPostprocess {

    /**
     * Extracts text bounding boxes from DBNet single-channel probability map [prob] ([mapH] x [mapW])
     * and rescales them into the source ROI coordinate space ([origW] x [origH]), sorted in visual reading order.
     */
    fun extractBoxesFromProbMap(
        prob: FloatArray,
        mapW: Int,
        mapH: Int,
        origW: Int,
        origH: Int,
        thresh: Float = 0.30f,
        boxThresh: Float = 0.50f,
        unclipRatio: Float = 1.6f
    ): List<OcrBox> {
        if (mapW <= 0 || mapH <= 0 || origW <= 0 || origH <= 0 || prob.size < mapW * mapH) {
            return emptyList()
        }
        val n = mapW * mapH
        val visited = BooleanArray(n)
        val queue = IntArray(n)
        val rawBoxes = ArrayList<OcrBox>(32)
        val scaleX = origW.toFloat() / mapW.toFloat()
        val scaleY = origH.toFloat() / mapH.toFloat()

        for (y in 0 until mapH) {
            val rowOff = y * mapW
            for (x in 0 until mapW) {
                val startIdx = rowOff + x
                if (visited[startIdx] || prob[startIdx] < thresh) continue

                visited[startIdx] = true
                var head = 0
                var tail = 0
                queue[tail++] = startIdx

                var minX = x
                var maxX = x
                var minY = y
                var maxY = y
                var area = 0

                while (head < tail) {
                    val cur = queue[head++]
                    area++
                    val cx = cur % mapW
                    val cy = cur / mapW
                    if (cx < minX) minX = cx
                    if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy
                    if (cy > maxY) maxY = cy

                    if (cx > 0) {
                        val nb = cur - 1
                        if (!visited[nb] && prob[nb] >= thresh) {
                            visited[nb] = true
                            queue[tail++] = nb
                        }
                    }
                    if (cx + 1 < mapW) {
                        val nb = cur + 1
                        if (!visited[nb] && prob[nb] >= thresh) {
                            visited[nb] = true
                            queue[tail++] = nb
                        }
                    }
                    if (cy > 0) {
                        val nb = cur - mapW
                        if (!visited[nb] && prob[nb] >= thresh) {
                            visited[nb] = true
                            queue[tail++] = nb
                        }
                    }
                    if (cy + 1 < mapH) {
                        val nb = cur + mapW
                        if (!visited[nb] && prob[nb] >= thresh) {
                            visited[nb] = true
                            queue[tail++] = nb
                        }
                    }
                }

                val bw = maxX - minX + 1
                val bh = maxY - minY + 1
                if (bw < 2 || bh < 2 || area < 4) continue

                var scoreSum = 0f
                var scoreCount = 0
                for (sy in minY..maxY) {
                    val sOff = sy * mapW
                    for (sx in minX..maxX) {
                        scoreSum += prob[sOff + sx]
                        scoreCount++
                    }
                }
                val meanScore = if (scoreCount > 0) scoreSum / scoreCount else 0f
                if (meanScore < boxThresh) continue

                val unclipD = (bw * bh * unclipRatio) / (2f * (bw + bh))
                val padX = max(1.5f, unclipD * 1.1f)
                val padY = max(1.0f, unclipD * 0.9f)

                val ux0 = max(0f, minX - padX)
                val uy0 = max(0f, minY - padY)
                val ux1 = min(mapW.toFloat(), maxX + 1f + padX)
                val uy1 = min(mapH.toFloat(), maxY + 1f + padY)

                val ox0 = (ux0 * scaleX).roundToInt().coerceIn(0, origW - 1)
                val oy0 = (uy0 * scaleY).roundToInt().coerceIn(0, origH - 1)
                val ox1 = (ux1 * scaleX).roundToInt().coerceIn(ox0, origW)
                val oy1 = (uy1 * scaleY).roundToInt().coerceIn(oy0, origH)
                if (ox1 - ox0 >= 8 && oy1 - oy0 >= 8) {
                    rawBoxes.add(OcrBox(ox0, oy0, ox1, oy1, meanScore))
                }
            }
        }
        return sortReadingOrder(rawBoxes)
    }

    /**
     * Groups boxes into horizontal visual lines (mid-Y overlap) and sorts top-to-bottom, left-to-right.
     */
    fun sortReadingOrder(boxes: List<OcrBox>): List<OcrBox> {
        if (boxes.size <= 1) return boxes
        val sortedByY = boxes.sortedWith(compareBy<OcrBox> { it.midY }.thenBy { it.left })
        val rows = ArrayList<MutableList<OcrBox>>()
        for (b in sortedByY) {
            val targetRow = rows.lastOrNull()?.takeIf { row ->
                val refH = min(row[0].height, b.height).coerceAtLeast(8)
                kotlin.math.abs(b.midY - row[0].midY) <= refH * 0.55f
            }
            if (targetRow != null) {
                targetRow.add(b)
            } else {
                rows.add(mutableListOf(b))
            }
        }
        val result = ArrayList<OcrBox>(boxes.size)
        for (row in rows) {
            row.sortBy { it.left }
            result.addAll(row)
        }
        return result
    }

    /**
     * Merges horizontally adjacent recognized text fragments on the same visual line when
     * they share the same greyed-out bypassed status and sit within one character width horizontally.
     */
    fun mergeAdjacentLineSegments(lines: List<OcrTextLine>): List<OcrTextLine> {
        if (lines.size <= 1) return lines
        val sorted = lines.sortedWith(compareBy<OcrTextLine> { (it.top + it.bottom) * 0.5f }.thenBy { it.left })
        val out = ArrayList<OcrTextLine>(sorted.size)
        for (cur in sorted) {
            val prev = out.lastOrNull()
            if (prev != null && prev.isGreyBypassed == cur.isGreyBypassed) {
                val prevH = (prev.bottom - prev.top).coerceAtLeast(8)
                val curH = (cur.bottom - cur.top).coerceAtLeast(8)
                val refH = min(prevH, curH)
                val prevMidY = (prev.top + prev.bottom) * 0.5f
                val curMidY = (cur.top + cur.bottom) * 0.5f
                val horizGap = cur.left - prev.right
                if (kotlin.math.abs(curMidY - prevMidY) <= refH * 0.55f &&
                    horizGap >= -refH * 0.4f &&
                    horizGap <= refH * 1.15f
                ) {
                    val joiner = if (horizGap > refH * 0.35f &&
                        prev.text.lastOrNull()?.isLetterOrDigit() == true &&
                        cur.text.firstOrNull()?.isLetterOrDigit() == true
                    ) " " else ""
                    val prevLen = prev.text.length.coerceAtLeast(1)
                    val curLen = cur.text.length.coerceAtLeast(1)
                    val weightedConf = (prev.confidence * prevLen + cur.confidence * curLen) / (prevLen + curLen)
                    out[out.lastIndex] = OcrTextLine(
                        text = prev.text + joiner + cur.text,
                        left = min(prev.left, cur.left),
                        top = min(prev.top, cur.top),
                        right = max(prev.right, cur.right),
                        bottom = max(prev.bottom, cur.bottom),
                        confidence = weightedConf,
                        p50Lum = max(prev.p50Lum, cur.p50Lum),
                        p95Lum = max(prev.p95Lum, cur.p95Lum),
                        vividRatio = max(prev.vividRatio, cur.vividRatio),
                        isGreyBypassed = prev.isGreyBypassed
                    )
                    continue
                }
            }
            out.add(cur)
        }
        return out
    }

    /**
     * CTC greedy decoder supporting BOTH post-softmax probabilities (RapidOCR PP-OCRv5 ONNX default,
     * row sum ~ 1.0) and unnormalized logits cleanly.
     *
     * [vocab] uses RapidOCR layout: index 0 = CTC blank ("blank"), 1..N = dictionary characters,
     * optional trailing " " at N+1.
     */
    fun ctcGreedyDecode(
        output: FloatArray,
        seqLen: Int,
        vocabSize: Int,
        vocab: List<String>,
        blankIndex: Int = 0
    ): Pair<String, Float> {
        if (seqLen <= 0 || vocabSize <= 0 || output.size < seqLen * vocabSize || vocab.isEmpty()) {
            return "" to 0f
        }
        // Detect once on timestep 0 whether the ONNX model already outputs probabilities (sum ~ 1.0).
        var row0Sum = 0f
        for (v in 0 until vocabSize) {
            val valAt = output[v]
            if (valAt < -0.001f || valAt > 1.001f) {
                row0Sum = -1f
                break
            }
            row0Sum += valAt
        }
        val alreadyProbs = row0Sum in 0.95f..1.05f

        val sb = StringBuilder()
        var prevIdx = -1
        var confSum = 0f
        var emitted = 0

        for (t in 0 until seqLen) {
            val base = t * vocabSize
            var bestIdx = 0
            var bestVal = output[base]
            for (v in 1 until vocabSize) {
                val cur = output[base + v]
                if (cur > bestVal) {
                    bestVal = cur
                    bestIdx = v
                }
            }
            if (bestIdx != blankIndex && bestIdx != prevIdx && bestIdx in vocab.indices) {
                val token = vocab[bestIdx]
                if (token.isNotEmpty() && token != "blank") {
                    sb.append(token)
                    val prob = if (alreadyProbs) {
                        bestVal.coerceIn(0f, 1f)
                    } else {
                        var expSum = 0.0
                        for (v in 0 until vocabSize) {
                            expSum += exp((output[base + v] - bestVal).toDouble())
                        }
                        if (expSum > 0.0) (1.0 / expSum).toFloat() else 0f
                    }
                    confSum += prob
                    emitted++
                }
            }
            prevIdx = bestIdx
        }
        val avgConf = if (emitted > 0) confSum / emitted else 0f
        return sb.toString().trim() to avgConf
    }

    /**
     * Calibrated ToS 2025.41 / 2026.2 foreground brightness & saturation classifier:
     * Greyed-out bypassed enemy skills render on the dark popup card with low foreground luminance
     * (`p50Lum < 95 && p95Lum in 85..162`) and near-zero chroma (`vividRatio < 0.05f`), whereas
     * active enemy skills render in crisp bright white (`p95 >= 185`) or vivid gold/red/cyan highlight.
     */
    fun sampleForegroundColorStats(
        frame: VisionFrame,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): ForegroundColorStats {
        val x0 = left.coerceIn(0, frame.width - 1)
        val y0 = top.coerceIn(0, frame.height - 1)
        val x1 = right.coerceIn(x0 + 1, frame.width)
        val y1 = bottom.coerceIn(y0 + 1, frame.height)
        val w = x1 - x0
        val h = y1 - y0
        if (w < 8 || h < 8) {
            return ForegroundColorStats(0, 220, 250, 0.2f, false)
        }

        val stepX = max(1, w / 32)
        val stepY = max(1, h / 12)
        val allLums = ArrayList<Int>(384)
        val fgLums = ArrayList<Int>(256)
        var vividFg = 0
        var vividAll = 0
        var total = 0

        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val argb = frame.getArgb(x, y)
                val r = (argb ushr 16) and 0xFF
                val g = (argb ushr 8) and 0xFF
                val b = argb and 0xFF
                val maxCh = max(r, max(g, b))
                val minCh = min(r, min(g, b))
                val lum = (r * 30 + g * 59 + b * 11) / 100
                allLums.add(lum)
                val isVivid = maxCh >= 130 && (maxCh - minCh) >= 45
                if (isVivid) vividAll++
                if (lum >= 45) {
                    fgLums.add(lum)
                    if (isVivid) vividFg++
                }
                total++
                x += stepX
            }
            y += stepY
        }

        if (total < 12 || allLums.isEmpty()) {
            return ForegroundColorStats(0, 220, 250, 0.2f, false)
        }

        // Match EnemySkillOcrEngine's percentile calibration while checking foreground pixel presence
        allLums.sort()
        val p50 = allLums[allLums.size / 2]
        val p95 = allLums[(allLums.size * 95) / 100]
        val fgCount = fgLums.size
        val fgP95 = if (fgCount >= 8) {
            fgLums.sort()
            fgLums[(fgCount * 95) / 100]
        } else {
            p95
        }
        val effectiveP95 = max(p95, fgP95)
        val vividRatio = vividAll.toFloat() / total.toFloat()
        val isGrey = fgCount >= 8 && p50 < 95 && effectiveP95 in 85..162 && vividRatio < 0.05f
        return ForegroundColorStats(
            fgPixelCount = fgCount,
            p50Lum = p50,
            p95Lum = effectiveP95,
            vividRatio = vividRatio,
            isGreyBypassed = isGrey
        )
    }
}
