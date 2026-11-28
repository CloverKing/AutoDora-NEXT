package com.autodora.next.vision

import com.autodora.next.model.ShieldCategory
import com.autodora.next.model.WaveShieldChip
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure Kotlin CenterNet-keypoint-guided numeric badge extractor for Stage 2B `EnemyIconNetV2` + `PP-OCRv5`.
 *
 * When `EnemyIconNetV2` detects a parameterized enemy skill icon (`ICON_MIN_COMBO`, `ICON_MAX_COMBO`,
 * `ICON_EXACT_COMBO`, `ICON_DIGITAL_COUNT`, `ICON_STEP_LIMIT`) at subpixel spatial peak `(cxRatio, cyRatio)`,
 * this helper:
 *  1) Crops the exact single-line digit region relative to `(cx, cy)` (excluding the bottom `COM` bar and
 *     colored `[NUM]` emblem).
 *  2) Isolates crisp white/pale-gold HUD digits surrounded by a pitch-dark stroke outline while removing
 *     detached background boss lightning sparks via 8-connected-component cap-height filtering.
 *  3) Builds the `[1, 3, 48, W]` NCHW recognition tensor directly from [VisionFrame] (zero `Bitmap` dependency)
 *     and decodes strictly over the digit whitelist `{'blank', '0'..'9'}` so kanji hallucinations are impossible.
 */
object EnemyBadgeDigitExtractor {

    const val REC_H = 48
    private const val REC_MIN_W = 32
    private const val REC_MAX_W = 320

    val NUMERIC_BADGE_CLASSES = setOf(
        "ICON_MIN_COMBO",
        "ICON_MAX_COMBO",
        "ICON_EXACT_COMBO",
        "ICON_DIGITAL_COUNT",
        "ICON_STEP_LIMIT"
    )

    fun hasNumericBadgeCandidate(detectedClasses: Collection<String>): Boolean =
        detectedClasses.any { it in NUMERIC_BADGE_CLASSES }

    /**
     * Converts `EnemyIconNetV2` `[20, 32]` subpixel grid coordinates `(gx, gy)` into full-screen `[0, 1]` ratios.
     */
    fun gridPeakToScreenRatio(gx: Float, gy: Float): Pair<Float, Float> {
        val cxRatio = (0.04f + ((gx + 0.5f) / 32f) * 0.92f).coerceIn(0.04f, 0.96f)
        val cyRatio = (0.08f + ((gy + 0.5f) / 20f) * 0.34f).coerceIn(0.08f, 0.42f)
        return cxRatio to cyRatio
    }

    /**
     * Computes `[left, top, right, bottom]` pixel bounds for the numeric portion of [iconClass].
     */
    fun computeBadgeDigitRoi(
        frameWidth: Int,
        frameHeight: Int,
        iconClass: String,
        cxRatio: Float,
        cyRatio: Float
    ): IntArray? {
        if (frameWidth < 64 || frameHeight < 64) return null
        val cx = cxRatio * frameWidth
        val cy = cyRatio * frameHeight
        val iw = frameWidth * 0.068f
        val raw = when (iconClass) {
            "ICON_MIN_COMBO", "ICON_MAX_COMBO" -> floatArrayOf(
                cx - iw * 0.06f, cy - iw * 0.58f, cx + iw * 0.56f, cy - iw * 0.03f
            )
            "ICON_EXACT_COMBO" -> floatArrayOf(
                cx - iw * 0.44f, cy - iw * 0.58f, cx + iw * 0.48f, cy - iw * 0.03f
            )
            "ICON_DIGITAL_COUNT" -> floatArrayOf(
                cx + iw * 0.08f, cy - iw * 0.48f, cx + iw * 1.08f, cy + iw * 0.48f
            )
            "ICON_STEP_LIMIT" -> floatArrayOf(
                cx - iw * 0.44f, cy - iw * 0.58f, cx + iw * 0.56f, cy + iw * 0.10f
            )
            else -> return null
        }
        val x0 = raw[0].roundToInt().coerceIn(0, frameWidth - 2)
        val y0 = raw[1].roundToInt().coerceIn(0, frameHeight - 2)
        val x1 = raw[2].roundToInt().coerceIn(x0 + 4, frameWidth)
        val y1 = raw[3].roundToInt().coerceIn(y0 + 4, frameHeight)
        if (x1 - x0 < 6 || y1 - y0 < 6) return null
        return intArrayOf(x0, y0, x1, y1)
    }

    /**
     * Upsamples the badge ROI `2x`, isolates black-outlined low-saturation white digits, filters out
     * non-cap-height background sparks via 8-connected components, pads onto a clean `(20, 20, 24)` strip,
     * and returns `(nchwTensorFloats, recW)` ready for `PP-OCRv5` `recSession` (`[1, 3, 48, recW]`).
     */
    fun buildCleanDigitRecTensor(
        frame: VisionFrame,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): Pair<FloatArray, Int>? {
        val cw = right - left
        val ch = bottom - top
        if (cw < 6 || ch < 6) return null

        val upW = cw * 2
        val upH = ch * 2
        val n = upW * upH
        val mxArr = FloatArray(n)
        val satArr = FloatArray(n)
        val maxFx = frame.width - 1
        val maxFy = frame.height - 1
        val inv255 = 1f / 255f

        for (uy in 0 until upH) {
            val sy = (top + (uy + 0.5f) * 0.5f - 0.5f).coerceIn(0f, maxFy.toFloat())
            val y0 = floor(sy.toDouble()).toInt().coerceIn(0, maxFy)
            val y1 = (y0 + 1).coerceAtMost(maxFy)
            val wy1 = sy - y0
            val wy0 = 1f - wy1
            val rowOff = uy * upW

            for (ux in 0 until upW) {
                val sx = (left + (ux + 0.5f) * 0.5f - 0.5f).coerceIn(0f, maxFx.toFloat())
                val x0 = floor(sx.toDouble()).toInt().coerceIn(0, maxFx)
                val x1 = (x0 + 1).coerceAtMost(maxFx)
                val wx1 = sx - x0
                val wx0 = 1f - wx1

                val p00 = frame.getArgb(x0, y0)
                val p10 = frame.getArgb(x1, y0)
                val p01 = frame.getArgb(x0, y1)
                val p11 = frame.getArgb(x1, y1)

                val w00 = wx0 * wy0
                val w10 = wx1 * wy0
                val w01 = wx0 * wy1
                val w11 = wx1 * wy1

                val r = (((p00 ushr 16) and 0xFF) * w00 + ((p10 ushr 16) and 0xFF) * w10 +
                    ((p01 ushr 16) and 0xFF) * w01 + ((p11 ushr 16) and 0xFF) * w11) * inv255
                val g = (((p00 ushr 8) and 0xFF) * w00 + ((p10 ushr 8) and 0xFF) * w10 +
                    ((p01 ushr 8) and 0xFF) * w01 + ((p11 ushr 8) and 0xFF) * w11) * inv255
                val b = ((p00 and 0xFF) * w00 + (p10 and 0xFF) * w10 +
                    (p01 and 0xFF) * w01 + (p11 and 0xFF) * w11) * inv255

                val mx = max(r, max(g, b))
                val mn = min(r, min(g, b))
                mxArr[rowOff + ux] = mx
                satArr[rowOff + ux] = if (mx > 1e-4f) (mx - mn) / mx else 0f
            }
        }

        val fg = BooleanArray(n)
        for (uy in 0 until upH) {
            val rowOff = uy * upW
            for (ux in 0 until upW) {
                val idx = rowOff + ux
                if (mxArr[idx] < 0.45f || satArr[idx] > 0.28f) continue
                var minNb = 1f
                for (dy in -4..4 step 2) {
                    val ny = (uy + dy).coerceIn(0, upH - 1) * upW
                    for (dx in -4..4 step 2) {
                        val nx = (ux + dx).coerceIn(0, upW - 1)
                        val v = mxArr[ny + nx]
                        if (v < minNb) minNb = v
                    }
                }
                if (minNb <= 0.23f) {
                    fg[idx] = true
                }
            }
        }

        // 8-connected-component filtering: retain only full cap-height digit strokes
        val visited = BooleanArray(n)
        val queue = IntArray(n)
        val comps = ArrayList<Pair<Int, IntArray>>()
        var maxCompH = 0

        for (start in 0 until n) {
            if (!fg[start] || visited[start]) continue
            visited[start] = true
            var head = 0
            var tail = 0
            queue[tail++] = start
            var minY = start / upW
            var maxY = minY

            while (head < tail) {
                val cur = queue[head++]
                val cy = cur / upW
                val cx = cur % upW
                if (cy < minY) minY = cy
                if (cy > maxY) maxY = cy
                for (dy in -1..1) {
                    val ny = cy + dy
                    if (ny !in 0 until upH) continue
                    val nyOff = ny * upW
                    for (dx in -1..1) {
                        if (dy == 0 && dx == 0) continue
                        val nx = cx + dx
                        if (nx !in 0 until upW) continue
                        val nb = nyOff + nx
                        if (fg[nb] && !visited[nb]) {
                            visited[nb] = true
                            queue[tail++] = nb
                        }
                    }
                }
            }

            val compH = maxY - minY + 1
            val midY = 0.5f * (minY + maxY) / max(1, upH)
            if (tail >= 8 && midY in 0.18f..0.85f) {
                val pts = queue.copyOfRange(0, tail)
                comps.add(compH to pts)
                if (compH > maxCompH) maxCompH = compH
            }
        }

        if (comps.isEmpty() || maxCompH < max(4, (upH * 0.26f).toInt())) return null

        val keep = BooleanArray(n)
        val minKeepH = max((maxCompH * 0.56f).toInt(), (upH * 0.26f).toInt())
        for ((compH, pts) in comps) {
            if (compH >= minKeepH) {
                for (idx in pts) keep[idx] = true
            }
        }

        val padX = max(12, upH / 2)
        val padY = max(4, upH / 6)
        val canW = upW + 2 * padX
        val canH = upH + 2 * padY
        val canvasGray = FloatArray(canW * canH) { 20f / 255f }

        for (uy in 0 until upH) {
            val srcOff = uy * upW
            val dstOff = (uy + padY) * canW + padX
            for (ux in 0 until upW) {
                val idx = srcOff + ux
                if (keep[idx]) {
                    val strength = ((mxArr[idx] - 0.38f) / 0.50f).coerceIn(0f, 1f)
                    canvasGray[dstOff + ux] = (20f + strength * 230f) * inv255
                }
            }
        }

        val recW = ((canW * (REC_H.toFloat() / canH))).roundToInt().coerceIn(REC_MIN_W, REC_MAX_W)
        val plane = REC_H * recW
        val outNchw = FloatArray(3 * plane)
        val maxCx = canW - 1
        val maxCy = canH - 1

        for (ry in 0 until REC_H) {
            val sy = ((ry + 0.5f) * (canH.toFloat() / REC_H) - 0.5f).coerceIn(0f, maxCy.toFloat())
            val y0 = floor(sy.toDouble()).toInt().coerceIn(0, maxCy)
            val y1 = (y0 + 1).coerceAtMost(maxCy)
            val wy1 = sy - y0
            val wy0 = 1f - wy1
            val rOff = ry * recW

            for (rx in 0 until recW) {
                val sx = ((rx + 0.5f) * (canW.toFloat() / recW) - 0.5f).coerceIn(0f, maxCx.toFloat())
                val x0 = floor(sx.toDouble()).toInt().coerceIn(0, maxCx)
                val x1 = (x0 + 1).coerceAtMost(maxCx)
                val wx1 = sx - x0
                val wx0 = 1f - wx1

                val gVal = canvasGray[y0 * canW + x0] * (wx0 * wy0) +
                    canvasGray[y0 * canW + x1] * (wx1 * wy0) +
                    canvasGray[y1 * canW + x0] * (wx0 * wy1) +
                    canvasGray[y1 * canW + x1] * (wx1 * wy1)
                val norm = (gVal - 0.5f) / 0.5f
                val dstIdx = rOff + rx
                outNchw[dstIdx] = norm
                outNchw[plane + dstIdx] = norm
                outNchw[2 * plane + dstIdx] = norm
            }
        }
        return outNchw to recW
    }

    /**
     * Resolves vocab indices `[blankIndex, idx('0'), ..., idx('9')]` once from `PP-OCRv5` vocabulary.
     */
    fun buildDigitWhitelistIndices(vocab: List<String>): Pair<IntArray, CharArray> {
        val indices = ArrayList<Int>(11)
        val chars = ArrayList<Char>(11)
        indices.add(0)
        chars.add('\u0000')
        for (d in '0'..'9') {
            val s = d.toString()
            val idx = vocab.indexOf(s)
            if (idx > 0) {
                indices.add(idx)
                chars.add(d)
            }
        }
        return indices.toIntArray() to chars.toCharArray()
    }

    /**
     * Whitelist-constrained CTC greedy decoder over `{'blank', '0'..'9'}`.
     */
    fun ctcDecodeDigitsWhitelist(
        probsAll: FloatArray,
        seqLen: Int,
        vocabSize: Int,
        whitelistIndices: IntArray,
        whitelistChars: CharArray
    ): Pair<String, Float> {
        if (seqLen <= 0 || vocabSize <= 0 || probsAll.size < seqLen * vocabSize || whitelistIndices.size < 2) {
            return "" to 0f
        }
        val sb = StringBuilder(4)
        var prevLocal = -1
        var confSum = 0f
        var emitted = 0

        for (t in 0 until seqLen) {
            val base = t * vocabSize
            var bestLocal = 0
            var bestVal = -1e9f
            var subSum = 0f
            for (k in whitelistIndices.indices) {
                val gIdx = whitelistIndices[k]
                if (gIdx !in 0 until vocabSize) continue
                val v = probsAll[base + gIdx].coerceAtLeast(0f)
                subSum += v
                if (v > bestVal) {
                    bestVal = v
                    bestLocal = k
                }
            }
            if (bestLocal != 0 && bestLocal != prevLocal) {
                sb.append(whitelistChars[bestLocal])
                val p = if (subSum > 1e-6f) (bestVal / subSum).coerceIn(0f, 1f) else 0f
                confSum += p
                emitted++
            }
            prevLocal = bestLocal
        }
        val avgConf = if (emitted > 0) confSum / emitted else 0f
        return sb.toString() to avgConf
    }

    /**
     * Aggregates multi-frame `(digitsText, confidence)` votes and emits a validated parameterized [WaveShieldChip].
     */
    fun resolveParameterizedBadgeChip(
        iconClass: String,
        frameReads: List<Pair<String, Float>>,
        minConfidence: Float = 0.55f
    ): WaveShieldChip? {
        if (frameReads.isEmpty()) return null
        val validRange = when (iconClass) {
            "ICON_MIN_COMBO" -> 2..19
            "ICON_MAX_COMBO" -> 1..15
            "ICON_EXACT_COMBO" -> 1..19
            "ICON_DIGITAL_COUNT" -> 3..36
            "ICON_STEP_LIMIT" -> 3..99
            else -> return null
        }

        val scoreByValue = HashMap<Int, Float>()
        val maxConfByValue = HashMap<Int, Float>()
        for ((txt, conf) in frameReads) {
            if (txt.isEmpty() || conf < minConfidence) continue
            val n = txt.toIntOrNull() ?: continue
            if (n !in validRange) continue
            scoreByValue[n] = (scoreByValue[n] ?: 0f) + conf
            val prevMax = maxConfByValue[n] ?: 0f
            if (conf > prevMax) maxConfByValue[n] = conf
        }

        val winner = scoreByValue.maxByOrNull { it.value }?.key ?: return null
        val bestSingleConf = maxConfByValue[winner] ?: 0f
        if (bestSingleConf < minConfidence) return null

        return when (iconClass) {
            "ICON_MIN_COMBO" -> WaveShieldChip(
                id = "min_combo",
                label = "≥ ${winner}C(图标)",
                category = ShieldCategory.COMBO,
                autoDetected = true,
                paramInt = winner
            )
            "ICON_MAX_COMBO" -> WaveShieldChip(
                id = "max_combo",
                label = "≤ ${winner}C(图标)",
                category = ShieldCategory.COMBO,
                autoDetected = true,
                paramInt = winner
            )
            "ICON_EXACT_COMBO" -> WaveShieldChip(
                id = "exact_first_combo",
                label = "首消 ${winner}C(图标)",
                category = ShieldCategory.COMBO,
                autoDetected = true,
                paramInt = winner
            )
            "ICON_DIGITAL_COUNT" -> WaveShieldChip(
                id = "digital_orb_count",
                label = "首消 $winner 粒(图标)",
                category = ShieldCategory.COUNT_AND_ELEMENT,
                autoDetected = true,
                paramInt = winner
            )
            "ICON_STEP_LIMIT" -> WaveShieldChip(
                id = "step_limit",
                label = "≤ $winner 步(图标)",
                category = ShieldCategory.HAZARD_AND_PATH,
                autoDetected = true,
                paramInt = winner
            )
            else -> null
        }
    }

    private val ELEMENT_NAMES_CN = arrayOf("水", "火", "木", "光", "暗", "心")

    fun elementCodeNameCn(code: Int): String = ELEMENT_NAMES_CN.getOrElse(code) { "" }

    /**
     * Inspects the CenterNet spatial peak of an elemental enemy skill icon (`ICON_ANTI_FIRST_WAVE`,
     * `ICON_FORBID_ALL`, `ICON_PUZZLE`, `ICON_CROSS_SHIELD`, `ICON_L_SHAPE`, `ICON_T_SHAPE`, `ICON_SQUARE_2X2`)
     * and extracts its dominant ToS orb element code (`0..5` = Water/Fire/Wood/Light/Dark/Heart, or `-1` if generic).
     */
    fun extractSingleElementFromIcon(
        frame: VisionFrame,
        iconClass: String,
        cxRatio: Float,
        cyRatio: Float,
        excludeRect: IntArray? = null
    ): Int {
        if (frame.width < 64 || frame.height < 64) return -1
        val cx = cxRatio * frame.width
        val cy = cyRatio * frame.height
        val iw = frame.width * 0.056f
        val x0 = (cx - iw * 0.44f).roundToInt().coerceIn(0, frame.width - 2)
        val x1 = (cx + iw * 0.44f).roundToInt().coerceIn(x0 + 2, frame.width - 1)
        val y0 = (cy - iw * 0.44f).roundToInt().coerceIn(0, frame.height - 2)
        val y1 = (cy + iw * 0.44f).roundToInt().coerceIn(y0 + 2, frame.height - 1)

        val votes = FloatArray(6)
        var vividTotal = 0f
        for (y in y0..y1) {
            for (x in x0..x1) {
                if (excludeRect != null && excludeRect.size >= 4 &&
                    x in excludeRect[0] until excludeRect[2] &&
                    y in excludeRect[1] until excludeRect[3]
                ) continue
                val argb = frame.getArgb(x, y)
                val code = classifyIconPixelElement(argb, iconClass == "ICON_ANTI_FIRST_WAVE")
                if (code in 0..5) {
                    val mx = max((argb ushr 16) and 0xFF, max((argb ushr 8) and 0xFF, argb and 0xFF)) / 255f
                    votes[code] += mx
                    vividTotal += mx
                }
            }
        }
        if (vividTotal < 6f) return -1
        var bestCode = -1
        var bestVote = 0f
        for (e in 0..5) {
            if (votes[e] > bestVote) {
                bestVote = votes[e]
                bestCode = e
            }
        }
        val domRatio = bestVote / vividTotal
        // Generic Puzzle / Cross / L / T icons use warm gold-amber metallic frames (Light hue),
        // so only accept non-Light colors on shape/puzzle icons unless strongly saturated non-gold.
        if (iconClass in setOf("ICON_PUZZLE", "ICON_CROSS_SHIELD", "ICON_L_SHAPE", "ICON_T_SHAPE", "ICON_SQUARE_2X2")) {
            if (bestCode == 3 || domRatio < 0.56f) return -1
        } else if (domRatio < 0.42f) {
            return -1
        }
        return bestCode
    }

    /**
     * Extracts multi-element bitmask (`2..6` colors) from `ICON_EQUAL_COMBO` (`水木光 = COM`, `光暗 = COM`, etc.).
     */
    fun extractEqualComboMask(
        frame: VisionFrame,
        cxRatio: Float,
        cyRatio: Float,
        excludeRect: IntArray? = null
    ): Int {
        if (frame.width < 64 || frame.height < 64) return 0
        val cx = cxRatio * frame.width
        val cy = cyRatio * frame.height
        val iw = frame.width * 0.066f
        val x0 = (cx - iw * 0.52f).roundToInt().coerceIn(0, frame.width - 2)
        val x1 = (cx + iw * 0.52f).roundToInt().coerceIn(x0 + 2, frame.width - 1)
        val y0 = (cy - iw * 0.56f).roundToInt().coerceIn(0, frame.height - 2)
        val y1 = (cy + iw * 0.08f).roundToInt().coerceIn(y0 + 2, frame.height - 1)

        val votes = IntArray(6)
        var totalHits = 0
        for (y in y0..y1) {
            for (x in x0..x1) {
                if (excludeRect != null && excludeRect.size >= 4 &&
                    x in excludeRect[0] until excludeRect[2] &&
                    y in excludeRect[1] until excludeRect[3]
                ) continue
                val code = classifyIconPixelElement(frame.getArgb(x, y), ignorePureRedSlash = false)
                if (code in 0..5) {
                    votes[code]++
                    totalHits++
                }
            }
        }
        if (totalHits < 10) return 0
        val minPerElem = max(4, (totalHits * 0.12f).toInt())
        var mask = 0
        for (e in 0..5) {
            if (votes[e] >= minPerElem) {
                mask = mask or (1 shl e)
            }
        }
        return if (Integer.bitCount(mask) >= 2) mask else 0
    }

    private fun classifyIconPixelElement(argb: Int, ignorePureRedSlash: Boolean): Int {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val delta = maxC - minC
        if (maxC < 72 || delta < 28) return -1
        val s = delta.toFloat() / maxC.toFloat()
        val v = maxC / 255f
        if (s < 0.30f) return -1
        val rawH = when (maxC) {
            r -> 60f * (((g - b).toFloat() / delta) % 6f)
            g -> 60f * (((b - r).toFloat() / delta) + 2f)
            else -> 60f * (((r - g).toFloat() / delta) + 4f)
        }
        val h = if (rawH < 0f) rawH + 360f else rawH
        return when {
            h in 176f..242f && v >= 0.42f -> 0 // WATER
            (h >= 350f || h <= 24f) && v >= 0.48f && s >= 0.45f -> {
                // On anti_first_wave, crimson prohibition slash has low G (h ~ 352..4, s > 0.78, g < 48);
                // genuine Fire orb core has warm orange-gold highlights (h in 8..24 or g >= 58).
                if (ignorePureRedSlash && (h >= 352f || h <= 5f) && g < 52) -1 else 1
            }
            h in 84f..158f && v >= 0.38f -> 2 // WOOD
            h in 38f..66f && v >= 0.56f && s >= 0.36f -> 3 // LIGHT
            h in 256f..306f && v >= 0.30f && s >= 0.28f -> 4 // DARK
            h in 312f..346f && v >= 0.48f && s >= 0.34f -> 5 // HEART
            else -> -1
        }
    }
}
