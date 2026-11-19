package com.autodora.next.vision

import android.graphics.Bitmap
import com.autodora.next.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.*

/**
 * Pure ARGB image abstraction enabling 100% deterministic JVM unit testing of CV recognition.
 */
interface VisionFrame {
    val width: Int
    val height: Int
    fun getArgb(x: Int, y: Int): Int
}

class BitmapVisionFrame(bitmap: Bitmap) : VisionFrame {
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
 * Phase 2.5 Real-Game-Calibrated CV Recognizer with Multi-Frame Temporal Fusion:
 *  1) Classifies 6 base elements using real ToS HSV boundaries (Dark 248..316°, Heart 316..346°),
 *     strictly excluding the top-right Race Badge quadrant from color & star-gleam sampling!
 *  2) Detects modern ToS top-right Race Badges (种族符石徽章) separately via B&W contrast signature
 *  3) Distinguishes dark-slate Question Orbs ('?') from vivid bright Water orbs (prevents false Water!)
 *  4) Detects Enchanted orbs (强化珠) via multi-frame peak specular star-gleam ratio
 *  5) Auto-detects Puzzle Shield golden glowing borders (拼图盾亮边) across multi-frame peak brightness
 */
class CvBoardRecognizer : BoardRecognizer {

    override val name: String = "CV-Rule-v2.5 (RealGame+RaceFree)"

    override suspend fun recognize(frames: List<Bitmap>, geometry: BoardGeometry): RecognitionResult =
        withContext(Dispatchers.Default) {
            val visionFrames = frames.map { BitmapVisionFrame(it) }
            recognizeFrames(visionFrames, geometry)
        }

    fun recognizeFrames(frames: List<VisionFrame>, geometry: BoardGeometry): RecognitionResult {
        val t0 = System.nanoTime()
        require(frames.isNotEmpty()) { "At least one frame is required for recognition" }

        val cols = geometry.cols
        val rows = geometry.rows
        val total = cols * rows
        val cellW = geometry.cellWidth
        val cellH = geometry.cellHeight
        val radius = min(cellW, cellH) * 0.5f

        val cells = ArrayList<CellState>(total)
        val tileFlags = MutableList(total) { TileFlagBits.NONE }
        val lowConfIndices = ArrayList<Int>()
        var confSum = 0f
        var questionCount = 0
        var raceBadgeCount = 0

        val puzzleBorderScores = FloatArray(total)

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val idx = r * cols + c
                val cx = geometry.cellCenterX(c)
                val cy = geometry.cellCenterY(r)

                val (cellState, borderGlow) = classifyCellMultiFrame(frames, cx, cy, radius, cellW, cellH)
                cells.add(cellState)
                puzzleBorderScores[idx] = borderGlow
                confSum += cellState.confidence
                if (cellState.element == Element.HIDDEN || (cellState.hazards and HazardBits.QUESTION) != 0) {
                    questionCount++
                }
                if (cellState.variant == Variant.RACE) {
                    raceBadgeCount++
                }
                if (cellState.confidence < LOW_CONFIDENCE_THRESHOLD) {
                    lowConfIndices.add(idx)
                }
            }
        }

        // Only mark Puzzle1 if a coherent cluster of >= 3 cells exhibits golden glowing borders
        val candidatePuzzle = BooleanArray(total) { puzzleBorderScores[it] >= PUZZLE_BORDER_THRESHOLD }
        val puzzleCount = markValidPuzzleCluster(candidatePuzzle, tileFlags, cols, rows)

        val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0
        return RecognitionResult(
            board = BoardState(cols = cols, rows = rows, cells = cells, tileFlags = tileFlags),
            averageConfidence = if (total > 0) confSum / total else 0f,
            lowConfidenceIndices = lowConfIndices,
            puzzleCellsDetected = puzzleCount,
            engineName = name,
            elapsedMs = elapsedMs,
            questionOrbsDetected = questionCount,
            raceBadgesDetected = raceBadgeCount
        )
    }

    private fun classifyCellMultiFrame(
        frames: List<VisionFrame>,
        cx: Float,
        cy: Float,
        radius: Float,
        cellW: Float,
        cellH: Float
    ): Pair<CellState, Float> {
        // Votes per element across all frames and circular ring sample points
        val votes = FloatArray(6)
        var totalSatWeight = 0f
        var sampleCount = 0
        var lowSatCount = 0
        var maxBrightStarCount = 0
        var outerRingValSum = 0f
        var outerRingSatSum = 0f
        var outerRingCount = 0

        // Sample 2 concentric rings inside the rune orb (0.36*r and 0.62*r, 16 angles each)
        // IMPORTANT: Exclude top-right quadrant (dx > 0 && dy < 0) where modern ToS renders
        // black-and-white Race Badges (人类/神/魔/龙/兽/妖/机械) so they NEVER pollute orb color or enchanted gleam!
        val ringRadii = floatArrayOf(radius * 0.36f, radius * 0.62f)
        val angles = 16

        for (f in frames) {
            var frameStarPixels = 0
            for ((ringIdx, ringR) in ringRadii.withIndex()) {
                for (a in 0 until angles) {
                    val theta = (2.0 * PI * a / angles).toFloat()
                    val dx = cos(theta) * ringR
                    val dy = sin(theta) * ringR
                    // Exclude top-right quadrant (screen +X right, -Y up)
                    if (dx > radius * 0.08f && dy < -radius * 0.08f) continue

                    val px = (cx + dx).roundToInt()
                    val py = (cy + dy).roundToInt()
                    val argb = f.getArgb(px, py)
                    val (h, s, v) = rgbToHsv(argb)
                    sampleCount++

                    if (ringIdx == 1) {
                        outerRingValSum += v
                        outerRingSatSum += s
                        outerRingCount++
                    }

                    // Detect bright white specular star sparkle (only on inner ring outside race badge)
                    if (ringIdx == 0 && v >= 0.93f && s <= 0.18f) {
                        frameStarPixels++
                    }

                    if (s < 0.20f || v < 0.16f) {
                        lowSatCount++
                        continue
                    }

                    val weight = s * (0.4f + 0.6f * v)
                    totalSatWeight += weight
                    val elIdx = classifyHueToElement(h, s, v)
                    if (elIdx >= 0) {
                        votes[elIdx] += weight
                    }
                }
            }
            // Inner star-gleam scan (center + inner ring at 0.20*r, 8 angles, excluding TR) for ToS Enchanted orb sparkles
            val (_, cs, cv) = rgbToHsv(f.getArgb(cx.roundToInt(), cy.roundToInt()))
            if (cv >= 0.94f && cs <= 0.18f) frameStarPixels += 2
            val innerStarR = radius * 0.20f
            for (a in 0 until 8) {
                val theta = (2.0 * PI * a / 8).toFloat()
                val dx = cos(theta) * innerStarR
                val dy = sin(theta) * innerStarR
                if (dx > 0f && dy < 0f) continue
                val sx = (cx + dx).roundToInt()
                val sy = (cy + dy).roundToInt()
                val (_, ss, sv) = rgbToHsv(f.getArgb(sx, sy))
                if (sv >= 0.93f && ss <= 0.18f) frameStarPixels++
            }
            if (frameStarPixels > maxBrightStarCount) {
                maxBrightStarCount = frameStarPixels
            }
        }

        // Detect golden glowing puzzle border around the cell perimeter across multi-frame peak
        val borderGlow = computePuzzleBorderScore(frames, cx, cy, cellW, cellH)
        // Detect modern ToS top-right B&W Race Badge
        val hasRaceBadge = detectTopRightRaceBadge(frames.first(), cx, cy, radius)

        // Pick winning element
        var bestIdx = -1
        var bestVote = 0f
        var secondVote = 0f
        for (i in 0 until 6) {
            if (votes[i] > bestVote) {
                secondVote = bestVote
                bestVote = votes[i]
                bestIdx = i
            } else if (votes[i] > secondVote) {
                secondVote = votes[i]
            }
        }

        val lowSatRatio = lowSatCount.toFloat() / max(1, sampleCount)
        val outerAvgV = outerRingValSum / max(1, outerRingCount)
        val outerAvgS = outerRingSatSum / max(1, outerRingCount)

        if (bestIdx < 0 || totalSatWeight < 0.5f || lowSatRatio > 0.82f) {
            // Likely BlackWhite, Question, Hidden, or Petrified orb — flag low confidence
            val haz = if (lowSatRatio > 0.82f) HazardBits.BLACK_WHITE else HazardBits.QUESTION
            return CellState(
                element = Element.HIDDEN,
                variant = Variant.NORMAL,
                hazards = haz,
                confidence = 0.22f
            ) to borderGlow
        }

        // Dedicated Question ('?') Orb Detector:
        // Real ToS Water orbs are vivid bright sky-cyan (outerAvgV >= 0.78..1.00, outerAvgS >= 0.50).
        // ToS blue/slate Question ('?') orbs have a noticeably dimmer slate-indigo body (outerAvgV < 0.68)
        // and muted saturation (outerAvgS < 0.46) with pale-cyan/grey '?' mark pixels raising lowSatRatio.
        val isSlateQuestionOrb = (bestIdx == Element.WATER.code) &&
            ((outerAvgV < 0.66f && outerAvgS < 0.46f) || (outerAvgV < 0.70f && lowSatRatio > 0.36f))
        if (isSlateQuestionOrb) {
            return CellState(
                element = Element.HIDDEN,
                variant = Variant.NORMAL,
                hazards = HazardBits.QUESTION,
                confidence = 0.30f
            ) to borderGlow
        }

        val dominance = bestVote / max(1e-4f, totalSatWeight)
        val margin = (bestVote - secondVote) / max(1e-4f, bestVote)
        val confidence = (dominance * 0.65f + margin * 0.35f).coerceIn(0f, 1f)

        // Enchanted orb heuristic: strong pure-white star gleam beyond ordinary ToS center rune emblem
        // (ordinary Fire/Dark/Water orbs have small white center glyphs; genuine Enchanted orbs sparkle intensely).
        val starThreshold = if (bestIdx == Element.LIGHT.code) 8 else 7
        val isEnchanted = maxBrightStarCount >= starThreshold && confidence >= 0.58f
        val variant = when {
            isEnchanted -> Variant.ENCHANTED
            hasRaceBadge -> Variant.RACE
            else -> Variant.NORMAL
        }

        return CellState(
            element = Element.fromCode(bestIdx),
            variant = variant,
            hazards = HazardBits.NONE,
            confidence = confidence
        ) to borderGlow
    }

    /**
     * Detects ToS top-right circular black-and-white Race Badge (种族符石徽章, e.g. 人类/神/魔/龙/兽/妖/机械).
     * Verified against real Xiaomi 15 battle screenshot: badge sits around (+0.58r, -0.58r) with >= 20%
     * bright neutral-white ring pixels (v >= 0.80, s <= 0.26) AND >= 20% deep dark silhouette pixels (v <= 0.28).
     */
    private fun detectTopRightRaceBadge(
        frame: VisionFrame,
        cx: Float,
        cy: Float,
        radius: Float
    ): Boolean {
        val minOff = radius * 0.28f
        val maxOff = radius * 0.82f
        val steps = 5
        var whiteHits = 0
        var darkHits = 0
        var total = 0
        for (iy in 0 until steps) {
            val dy = -(minOff + (maxOff - minOff) * iy / (steps - 1))
            for (ix in 0 until steps) {
                val dx = minOff + (maxOff - minOff) * ix / (steps - 1)
                val (_, s, v) = rgbToHsv(frame.getArgb((cx + dx).roundToInt(), (cy + dy).roundToInt()))
                total++
                if (v >= 0.80f && s <= 0.26f) whiteHits++
                if (v <= 0.28f) darkHits++
            }
        }
        return total > 0 &&
            (whiteHits.toFloat() / total) >= 0.20f &&
            (darkHits.toFloat() / total) >= 0.20f
    }

    /**
     * Real-game calibrated Hue-Saturation-Value classifier for Tower of Saviors 6 rune elements:
     * Verified on real Xiaomi 15 battle screenshot:
     *  - Fire (红):  H < 22° or >= 346°
     *  - Light (黄): H ∈ [22°, 74°]
     *  - Wood (绿):  H ∈ [74°, 162°]
     *  - Water (蓝): H ∈ [162°, 248°]
     *  - Dark (紫):  H ∈ [248°, 316°] (modern ToS bright magenta-purple Dark reaches ~314°!)
     *  - Heart (粉): H ∈ [316°, 346°]
     */
    private fun classifyHueToElement(h: Float, s: Float, v: Float): Int {
        return when {
            h < 22f || h >= 346f -> Element.FIRE.code
            h in 22f..74f -> Element.LIGHT.code
            h in 74f..162f -> Element.WOOD.code
            h in 162f..248f -> Element.WATER.code
            h in 248f..316f -> Element.DARK.code
            h in 316f..346f -> Element.HEART.code
            else -> -1
        }
    }

    /**
     * Inspects outer 6% edge ring of a cell across all frames (taking max golden luminance per sample)
     * to detect Tower of Saviors glowing Puzzle Shield frames.
     */
    private fun computePuzzleBorderScore(
        frames: List<VisionFrame>,
        cx: Float,
        cy: Float,
        cellW: Float,
        cellH: Float
    ): Float {
        val halfW = cellW * 0.47f
        val halfH = cellH * 0.47f
        val samplesPerEdge = 6
        var goldHits = 0
        val totalPts = samplesPerEdge * 4

        fun samplePeakGold(x: Int, y: Int): Boolean {
            for (f in frames) {
                val (h, s, v) = rgbToHsv(f.getArgb(x, y))
                // Golden / bright amber glowing puzzle border
                if (h in 26f..64f && s >= 0.48f && v >= 0.80f) return true
            }
            return false
        }

        for (i in 0 until samplesPerEdge) {
            val t = -0.75f + 1.5f * (i.toFloat() / (samplesPerEdge - 1))
            if (samplePeakGold((cx + t * halfW).roundToInt(), (cy - halfH).roundToInt())) goldHits++
            if (samplePeakGold((cx + t * halfW).roundToInt(), (cy + halfH).roundToInt())) goldHits++
            if (samplePeakGold((cx - halfW).roundToInt(), (cy + t * halfH).roundToInt())) goldHits++
            if (samplePeakGold((cx + halfW).roundToInt(), (cy + t * halfH).roundToInt())) goldHits++
        }
        return goldHits.toFloat() / totalPts
    }

    private fun markValidPuzzleCluster(
        candidates: BooleanArray,
        tileFlags: MutableList<Int>,
        cols: Int,
        rows: Int
    ): Int {
        val total = cols * rows
        val visited = BooleanArray(total)
        var totalMarked = 0

        for (start in 0 until total) {
            if (!candidates[start] || visited[start]) continue
            val component = ArrayList<Int>()
            val queue = ArrayDeque<Int>()
            queue.add(start)
            visited[start] = true
            while (queue.isNotEmpty()) {
                val u = queue.removeFirst()
                component.add(u)
                val r = u / cols
                val c = u % cols
                val neighbors = intArrayOf(
                    if (r > 0) u - cols else -1,
                    if (r + 1 < rows) u + cols else -1,
                    if (c > 0) u - 1 else -1,
                    if (c + 1 < cols) u + 1 else -1
                )
                for (v in neighbors) {
                    if (v >= 0 && candidates[v] && !visited[v]) {
                        visited[v] = true
                        queue.add(v)
                    }
                }
            }
            // Genuine ToS puzzle shields always have >= 3 connected cells
            if (component.size >= 3) {
                for (idx in component) {
                    tileFlags[idx] = tileFlags[idx] or TileFlagBits.PUZZLE_1
                }
                totalMarked += component.size
            }
        }
        return totalMarked
    }

    private fun rgbToHsv(argb: Int): Triple<Float, Float, Float> {
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
        return Triple(h, s, mx)
    }

    companion object {
        const val LOW_CONFIDENCE_THRESHOLD = 0.55f
        const val PUZZLE_BORDER_THRESHOLD = 0.42f
    }
}
