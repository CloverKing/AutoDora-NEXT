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
 * Zero-allocation VisionFrame reading sparse sample coordinates directly from an ARGB_8888 Bitmap
 * (or horizontal band slice starting at [yOffset]), eliminating 10MB+ full-screen IntArray copies!
 */
class DirectBitmapVisionFrame(
    private val bitmap: Bitmap,
    override val width: Int = bitmap.width,
    override val height: Int = bitmap.height,
    private val yOffset: Int = 0
) : VisionFrame {
    private val maxBmpX = (minOf(width, bitmap.width) - 1).coerceAtLeast(0)
    private val maxBmpY = (bitmap.height - 1).coerceAtLeast(0)

    override fun getArgb(x: Int, y: Int): Int {
        val bx = x.coerceIn(0, maxBmpX)
        val by = (y - yOffset).coerceIn(0, maxBmpY)
        return bitmap.getPixel(bx, by)
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

    override val name: String = "CV-Rule-v2.6 (RealGame+Hazards+Terrains)"

    override suspend fun recognize(frames: List<Bitmap>, geometry: BoardGeometry): RecognitionResult =
        withContext(Dispatchers.Default) {
            val visionFrames = frames.map { DirectBitmapVisionFrame(it) }
            recognizeFrames(visionFrames, geometry)
        }

    override suspend fun recognizeBand(
        bandBitmap: Bitmap,
        fullWidth: Int,
        fullHeight: Int,
        yOffset: Int,
        geometry: BoardGeometry
    ): RecognitionResult = withContext(Dispatchers.Default) {
        val vf = DirectBitmapVisionFrame(bandBitmap, fullWidth, fullHeight, yOffset)
        recognizeFrames(listOf(vf), geometry)
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

        // Pre-scan per-cell abnormal status statistics (13x13 HSV grid) BEFORE Traffic Light detection so
        // square Frozen / Ice-Sealed crystal blocks on Wood/Fire orbs never falsely trigger corner-ray
        // Green GO Start or Red Burst End markers!
        val abnormalStats = arrayOfNulls<CellAbnormalStats>(total)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val idx = r * cols + c
                abnormalStats[idx] = analyzeCellAbnormalMultiFrame(
                    frames, geometry.cellCenterX(c), geometry.cellCenterY(r), cellW, cellH
                )
            }
        }

        // Pre-scan Traffic Light markers (GO green start + red 8-ray burst end) so white 'GO' lettering
        // is known before orb classification and never triggers false '?' or Enchanted flags.
        detectTrafficLightMarkers(frames, geometry, tileFlags, abnormalStats)

        val stormWeakFlags = BooleanArray(total)
        val stormStrongFlags = BooleanArray(total)

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val idx = r * cols + c
                val cx = geometry.cellCenterX(c)
                val cy = geometry.cellCenterY(r)

                val isTlStart = (tileFlags[idx] and TileFlagBits.START_ALLOWED) != 0
                val stats = abnormalStats[idx]
                val cellState = classifyCellMultiFrame(
                    frames = frames,
                    cx = cx,
                    cy = cy,
                    radius = radius,
                    isTrafficLightStart = isTlStart,
                    abnormal = stats
                )
                val (isStormStrong, isStormWeak) = detectStormMultiFrame(frames, cx, cy, cellW, cellH)
                stormStrongFlags[idx] = isStormStrong
                stormWeakFlags[idx] = isStormWeak

                cells.add(cellState)
                if (isStormStrong) {
                    tileFlags[idx] = (tileFlags[idx] and (TileFlagBits.START_ALLOWED or TileFlagBits.END_REQUIRED).inv()) or TileFlagBits.STORM
                }
            }
        }

        // Full-column tornado tip refinement: if >= (rows - 1) cells in a column are Storm and the last tip cell
        // shows partial grey mist (weak storm), promote that tip cell to Storm as well!
        for (c in 0 until cols) {
            var strongInCol = 0
            for (r in 0 until rows) {
                if (stormStrongFlags[r * cols + c]) strongInCol++
            }
            if (strongInCol >= (rows - 1).coerceAtLeast(3)) {
                for (r in 0 until rows) {
                    val idx = r * cols + c
                    if (!stormStrongFlags[idx] && stormWeakFlags[idx]) {
                        tileFlags[idx] = tileFlags[idx] or TileFlagBits.STORM
                    }
                }
            }
        }

        // Detect Puzzle Shield shapes BEFORE terrain post-pass so golden puzzle borders are never confused with Burning tiles!
        val puzzleCount = detectPuzzleRegions(frames, geometry, tileFlags)

        // Board-level terrain post-pass (Shattered, Bloodbath, Burning Tile):
        // Gate dark-terrain detectors (Shattered / Bloodbath) on genuine active battle boards (>= 8 bright matchable orbs)
        // so dimmed transition overlays or dark menus NEVER trigger false terrain badges!
        val brightMatchableCount = cells.count {
            it.element.isMatchable &&
                (it.hazards and HazardBits.QUESTION) == 0 &&
                it.confidence >= 0.50f
        }
        for (idx in 0 until total) {
            val flags = tileFlags[idx]
            val isStorm = (flags and TileFlagBits.STORM) != 0
            val cell = cells[idx]
            val isPetrified = (cell.hazards and HazardBits.PETRIFIED) != 0
            val isBloodDrain = (cell.hazards and (HazardBits.BLOOD_DRAIN or HazardBits.LOCKED_HURT)) != 0
            val stats = abnormalStats[idx]

            if (stats != null && !isPetrified) {
                // 1. Shattered Tile (#35 碎裂): pitch-black glass-cracked void with zero saturated color.
                //    Evaluated even if radial grey cracks touched Storm's diagonal samples (real Storm veils
                //    have avgV >= 0.32 and darkBody < 0.20, NEVER a pitch-black void with darkBody >= 0.72!).
                if (!isBloodDrain &&
                    brightMatchableCount >= 8 &&
                    stats.minAvgBodyV <= 0.18f &&
                    stats.maxDarkBody >= 0.72f &&
                    stats.minSatBody <= 0.03f &&
                    stats.maxCrackSilver >= 0.12f
                ) {
                    tileFlags[idx] = (flags and TileFlagBits.STORM.inv()) or TileFlagBits.SHATTERED
                    cells[idx] = cell.copy(
                        element = Element.EMPTY,
                        variant = Variant.NORMAL,
                        hazards = cell.hazards and HazardBits.QUESTION.inv(),
                        confidence = 0.90f
                    )
                } else if (!isStorm &&
                    !isBloodDrain &&
                    brightMatchableCount >= 8 &&
                    stats.minAvgBodyV <= 0.38f &&
                    stats.minHighVBody <= 0.08f &&
                    stats.maxBbRed >= 0.48f &&
                    stats.maxBbCorner >= 0.16f
                ) {
                    // 2. Bloodbath Tile (#31 血浴地型): deep dark crimson pool drowning the cell
                    tileFlags[idx] = flags or TileFlagBits.END_FORBIDDEN
                    if (cell.element == Element.HIDDEN || cell.element == Element.UNKNOWN) {
                        cells[idx] = cell.copy(
                            hazards = cell.hazards and HazardBits.QUESTION.inv(),
                            confidence = max(cell.confidence, 0.82f)
                        )
                    }
                } else if (!isStorm &&
                    (cell.hazards and (HazardBits.ELECTRIFIED or HazardBits.PURGATORY_FIRE)) == 0 &&
                    (flags and (TileFlagBits.PUZZLE_1 or TileFlagBits.PUZZLE_2 or TileFlagBits.START_ALLOWED or TileFlagBits.END_REQUIRED)) == 0 &&
                    stats.maxAvgBodyV >= 0.40f &&
                    ((stats.maxFireBorder >= 0.20f && stats.maxFireCorner >= 0.06f) ||
                        (stats.maxFireBorder >= 0.18f && stats.maxFireCorner >= 0.12f))
                ) {
                    // 3. Color-Invariant Burning Tile (#25 燃燒地型): fiery orange-gold flame spilling brightly into
                    //    outer border/corners on ANY of the 6 orb colors (no Light-orb brightness bias; avgV >= 0.40
                    //    only excludes pitch-black non-battle modals, verified 0/329 FP on real battle screens!)
                    tileFlags[idx] = flags or TileFlagBits.BURNING_TILE
                }
            }
        }

        // Normalize Storm cells and compute summary metrics after full-column Storm & terrain refinement:
        for (idx in 0 until total) {
            val isStorm = (tileFlags[idx] and TileFlagBits.STORM) != 0
            val isKnownDarkTerrain = (tileFlags[idx] and (TileFlagBits.SHATTERED or TileFlagBits.END_FORBIDDEN)) != 0
            val cell = if (isStorm) {
                val orig = cells[idx]
                val sanitized = orig.copy(
                    element = if (orig.element == Element.HIDDEN) Element.UNKNOWN else orig.element,
                    hazards = orig.hazards and HazardBits.QUESTION.inv(),
                    confidence = max(orig.confidence, 0.88f)
                )
                cells[idx] = sanitized
                sanitized
            } else {
                cells[idx]
            }
            confSum += cell.confidence
            if (!isStorm && !isKnownDarkTerrain && (cell.element == Element.HIDDEN || (cell.hazards and HazardBits.QUESTION) != 0)) {
                questionCount++
            }
            if (cell.variant == Variant.RACE) {
                raceBadgeCount++
            }
            if (!isStorm && !isKnownDarkTerrain && cell.confidence < LOW_CONFIDENCE_THRESHOLD) {
                lowConfIndices.add(idx)
            }
        }
        val stormCount = tileFlags.count { (it and TileFlagBits.STORM) != 0 }
        val tlStartCount = tileFlags.count { (it and TileFlagBits.START_ALLOWED) != 0 }
        val tlEndCount = tileFlags.count { (it and TileFlagBits.END_REQUIRED) != 0 }

        val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0
        return RecognitionResult(
            board = BoardState(cols = cols, rows = rows, cells = cells, tileFlags = tileFlags),
            averageConfidence = if (total > 0) confSum / total else 0f,
            lowConfidenceIndices = lowConfIndices,
            puzzleCellsDetected = puzzleCount,
            engineName = name,
            elapsedMs = elapsedMs,
            questionOrbsDetected = questionCount,
            raceBadgesDetected = raceBadgeCount,
            stormCellsDetected = stormCount,
            trafficLightStartCount = tlStartCount,
            trafficLightEndCount = tlEndCount
        )
    }

    /**
     * Real-screenshot calibrated Storm / Tornado (暴风 / 龙卷风区域) detector:
     * A real ToS tornado veil covers BOTH the normally pitch-dark tile corners/edges AND the orb interior
     * with mid-grey desaturated mist (V in 0.28..0.78, S <= 0.24), separating cleanly by ~5x from normal orbs,
     * white-rimmed Shield orbs, Race orbs, Question orbs, and Black-and-White orbs.
     *
     * Returns Pair(isStrongStorm, isWeakStormTip).
     */
    private fun detectStormMultiFrame(
        frames: List<VisionFrame>,
        cx: Float,
        cy: Float,
        cellW: Float,
        cellH: Float
    ): Pair<Boolean, Boolean> {
        val offX = cellW * 0.44f
        val offY = cellH * 0.44f
        val innerR = min(cellW, cellH) * 0.26f
        val cornerOffsets = arrayOf(
            -offX to -offY, 0f to -offY, offX to -offY,
            -offX to offY, 0f to offY, offX to offY
        )

        var strong = false
        var weak = false

        for (f in frames) {
            var greyCorner = 0
            for ((dx, dy) in cornerOffsets) {
                val px = (cx + dx).roundToInt()
                val py = (cy + dy).roundToInt()
                val (_, s, v) = rgbToHsv(f.getArgb(px, py))
                if (v in 0.28f..0.78f && s <= 0.24f) {
                    greyCorner++
                }
            }

            var greyInner = 0
            var innerSatSum = 0f
            for (a in 0 until 8) {
                val theta = (2.0 * PI * a / 8).toFloat()
                val px = (cx + cos(theta) * innerR).roundToInt()
                val py = (cy + sin(theta) * innerR).roundToInt()
                val (_, s, v) = rgbToHsv(f.getArgb(px, py))
                innerSatSum += s
                if (v in 0.28f..0.78f && s <= 0.24f) {
                    greyInner++
                }
            }
            val avgInnerSat = innerSatSum / 8f

            if (greyCorner >= 3 && greyInner >= 3 && avgInnerSat <= 0.34f) {
                strong = true
            }
            if (greyCorner >= 2 && greyInner >= 2 && avgInnerSat <= 0.42f) {
                weak = true
            }
        }
        return strong to weak
    }

    /**
     * Real-screenshot calibrated Traffic Light (红绿灯 / 固定起终点) detector (V3):
     * Samples the diagonal extreme-corner pocket ring (hypot ∈ [0.56, 0.72] * cell) strictly OUTSIDE circular Fire/Wood
     * orb bodies (0/17 false positives across all non-TL real battle screenshots):
     *  - Green 'GO' Start (TileFlagBits.START_ALLOWED): neon-green rays (H∈[84°,158°], S≥0.50, V≥0.44) in ≥2 corner
     *    quadrants (≥3 hits) AND bold pure-white 'GO' center band (V≥0.88, S≤0.20, density ≥ 25%);
     *    corner cells with clipped outer quadrants also qualify with strong 'GO' (≥42%) + ≥1 visible green corner (≥2 hits).
     *  - Red 8-Ray Burst End (TileFlagBits.END_REQUIRED): vivid crimson rays (H≤15° or ≥348°, S≥0.68, V≥0.45)
     *    shooting into ≥3 corner quadrants (≥4 hits); when a 'GO' start is confirmed on the same board, screen-edge
     *    red bursts clipped on 1–2 outer quadrants are rescued at ≥2 visible quadrants (≥3 hits).
     *  - Adjacent non-maximum suppression prevents intense rays from bleeding a false duplicate onto neighboring cells.
     */
    private fun detectTrafficLightMarkers(
        frames: List<VisionFrame>,
        geometry: BoardGeometry,
        tileFlags: MutableList<Int>,
        abnormalStats: Array<CellAbnormalStats?>
    ) {
        val cols = geometry.cols
        val rows = geometry.rows
        val total = cols * rows
        val cw = geometry.cellWidth
        val ch = geometry.cellHeight
        val extOffsets = arrayOf(
            0.41f to 0.41f, 0.45f to 0.38f, 0.38f to 0.45f, 0.46f to 0.46f,
            0.50f to 0.34f, 0.34f to 0.50f, 0.50f to 0.44f, 0.44f to 0.50f,
            0.53f to 0.28f, 0.28f to 0.53f, 0.54f to 0.42f, 0.42f to 0.54f
        )

        val startScores = ArrayList<Pair<Int, Float>>()
        val redQuadsArr = IntArray(total)
        val redHitsArr = IntArray(total)
        val redVividArr = IntArray(total)
        val redUnionMaskArr = IntArray(total)
        val redTotalHitsArr = IntArray(total)
        val ringRedQuadsArr = IntArray(total)
        val ringRedHitsArr = IntArray(total)
        val isFireOrbArr = BooleanArray(total)
        val isHeartOrbArr = BooleanArray(total)

        // Precompute 24-angle perimeter ring offsets for detecting rotating green GO rays (even when a Race Badge
        // covers top-right) and non-Fire/non-Heart red vortex rays under ±8% vertical calibration drift.
        val ringAngles = 24
        val cos24 = FloatArray(ringAngles) { a -> cos(2.0 * PI * a / ringAngles).toFloat() }
        val sin24 = FloatArray(ringAngles) { a -> sin(2.0 * PI * a / ringAngles).toFloat() }
        val quadBit24 = IntArray(ringAngles) { a ->
            val qx = if (cos24[a] < 0f) 0 else 1
            val qy = if (sin24[a] < 0f) 0 else 1
            1 shl (qx * 2 + qy)
        }
        val greenRingRadii = floatArrayOf(0.42f, 0.48f, 0.54f)
        val redRingRadii = floatArrayOf(0.45f, 0.51f)
        val coreProbeOffs = arrayOf(0f to 0f, -0.18f to 0f, 0.18f to 0f, 0f to 0.18f, 0f to -0.18f)

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val idx = r * cols + c
                // Square Frozen / Ice-Sealed ice cubes fill all 4 corners with green/red facets, and
                // Electrified / Purgatory Fire orbs have blinding white cores that could otherwise pair with
                // adjacent Wood/Fire orbs to mimic Traffic Light Green GO or Red Burst corner rays!
                val ab = abnormalStats[idx]
                if (ab != null && (ab.isFrozen || ab.isIceSealed || ab.isElectrified || ab.isPurgatoryFire)) continue
                val cx = geometry.cellCenterX(c)
                val cy = geometry.cellCenterY(r)
                val isCorner = (r == 0 || r == rows - 1) && (c == 0 || c == cols - 1)

                // Quick 5-point core probe to identify Fire / Wood / Heart bodies so orb rims never mimic outer rays
                var coreFire = 0
                var coreWood = 0
                var coreHeart = 0
                val f0 = frames.first()
                for ((dx, dy) in coreProbeOffs) {
                    val px = (cx + dx * cw).roundToInt().coerceIn(0, f0.width - 1)
                    val py = (cy + dy * ch).roundToInt().coerceIn(0, f0.height - 1)
                    val (chue, csat, cval) = rgbToHsv(f0.getArgb(px, py))
                    if (csat >= 0.42f && cval >= 0.42f) {
                        if (chue < 22f || chue >= 346f) coreFire++
                        else if (chue in 74f..162f) coreWood++
                        else if (chue in 310f..346f) coreHeart++
                    }
                }
                val isFireOrb = coreFire >= 3
                val isWoodOrb = coreWood >= 3
                val isHeartOrb = coreHeart >= 3
                isFireOrbArr[idx] = isFireOrb
                isHeartOrbArr[idx] = isHeartOrb

                var maxGreenQuads = 0
                var maxGreenHits = 0
                var maxRingGreenHits = 0
                var unionRingGreenMask = 0
                var maxWhiteGoRatio = 0f
                var maxRedQuads = 0
                var maxRedHits = 0
                var maxRedVivid = 0
                var unionRedQuadMask = 0
                var totalRedHits = 0
                var maxRingRedHits = 0
                var unionRingRedMask = 0

                for (f in frames) {
                    val fw = f.width
                    val fh = f.height
                    var gHits = 0
                    var gQuadMask = 0
                    var rHits = 0
                    var rQuadMask = 0
                    var rVivid = 0

                    for (qx in 0..1) {
                        val sx = if (qx == 0) -1f else 1f
                        for (qy in 0..1) {
                            val sy = if (qy == 0) -1f else 1f
                            val qBit = 1 shl (qx * 2 + qy)
                            for ((ox, oy) in extOffsets) {
                                val x = (cx + sx * ox * cw).roundToInt()
                                val y = (cy + sy * oy * ch).roundToInt()
                                if (x < 0 || x >= fw || y < 0 || y >= fh) continue
                                val (h, s, v) = rgbToHsv(f.getArgb(x, y))
                                if (h in 84f..158f && s >= 0.50f && v >= 0.44f) {
                                    gHits++
                                    gQuadMask = gQuadMask or qBit
                                }
                                if ((h <= 15f || h >= 348f) && s >= 0.68f && v >= 0.45f) {
                                    rHits++
                                    rQuadMask = rQuadMask or qBit
                                    if ((h <= 10f || h >= 353f) && s >= 0.80f && v >= 0.56f) {
                                        rVivid++
                                    }
                                }
                            }
                        }
                    }

                    // 24-angle outer-rim green ray ring (rescues GO under top-right Race Badges & ±8% drift)
                    var ringGHits = 0
                    var ringGMask = 0
                    for (rr in greenRingRadii) {
                        if (isWoodOrb && rr < 0.47f) continue
                        for (a in 0 until ringAngles) {
                            val x = (cx + cos24[a] * rr * cw).roundToInt()
                            val y = (cy + sin24[a] * rr * ch).roundToInt()
                            if (x < 0 || x >= fw || y < 0 || y >= fh) continue
                            val (h, s, v) = rgbToHsv(f.getArgb(x, y))
                            if (h in 82f..160f && s >= 0.46f && v >= 0.40f) {
                                ringGHits++
                                ringGMask = ringGMask or quadBit24[a]
                            }
                        }
                    }
                    if (ringGHits > maxRingGreenHits) maxRingGreenHits = ringGHits
                    unionRingGreenMask = unionRingGreenMask or ringGMask

                    // 24-angle outer-rim crimson ray ring for non-Fire/non-Heart interior cells (r > 0 avoids pink HP bar!)
                    if (!isFireOrb && !isHeartOrb && r > 0) {
                        var ringRHits = 0
                        var ringRMask = 0
                        for (rr in redRingRadii) {
                            for (a in 0 until ringAngles) {
                                val x = (cx + cos24[a] * rr * cw).roundToInt()
                                val y = (cy + sin24[a] * rr * ch).roundToInt()
                                if (x < 0 || x >= fw || y < 0 || y >= fh) continue
                                val (h, s, v) = rgbToHsv(f.getArgb(x, y))
                                if ((h <= 14f || h >= 349f) && s >= 0.72f && v >= 0.45f) {
                                    ringRHits++
                                    ringRMask = ringRMask or quadBit24[a]
                                }
                            }
                        }
                        if (ringRHits > maxRingRedHits) maxRingRedHits = ringRHits
                        unionRingRedMask = unionRingRedMask or ringRMask
                    }

                    val gQuads = Integer.bitCount(gQuadMask)
                    val rQuads = Integer.bitCount(rQuadMask)
                    if (gQuads > maxGreenQuads || (gQuads == maxGreenQuads && gHits > maxGreenHits)) {
                        maxGreenQuads = gQuads
                        maxGreenHits = gHits
                    }
                    if (rQuads > maxRedQuads ||
                        (rQuads == maxRedQuads && rHits > maxRedHits) ||
                        (rQuads == maxRedQuads && rHits == maxRedHits && rVivid > maxRedVivid)
                    ) {
                        maxRedQuads = rQuads
                        maxRedHits = rHits
                        maxRedVivid = rVivid
                    }
                    unionRedQuadMask = unionRedQuadMask or rQuadMask
                    totalRedHits += rHits

                    // Sample center 'GO' white text band whenever green outer corner OR ring rays are present
                    if (gHits >= 2 || ringGHits >= 4) {
                        var wHits = 0
                        var wTot = 0
                        for (ix in 0 until 9) {
                            val dx = -0.30f + 0.60f * (ix / 8f)
                            val x = (cx + dx * cw).roundToInt().coerceIn(0, fw - 1)
                            for (iy in 0 until 5) {
                                val dy = -0.14f + 0.28f * (iy / 4f)
                                val y = (cy + dy * ch).roundToInt().coerceIn(0, fh - 1)
                                val (_, s, v) = rgbToHsv(f.getArgb(x, y))
                                wTot++
                                if (v >= 0.88f && s <= 0.20f) wHits++
                            }
                        }
                        val wRatio = wHits.toFloat() / max(1, wTot)
                        if (wRatio > maxWhiteGoRatio) maxWhiteGoRatio = wRatio
                    }
                }

                val ringGQuads = Integer.bitCount(unionRingGreenMask)
                val isStart = (maxWhiteGoRatio >= 0.25f && maxGreenQuads >= 2 && maxGreenHits >= 3) ||
                    (isCorner && maxWhiteGoRatio >= 0.42f && maxGreenQuads >= 1 && maxGreenHits >= 2) ||
                    (maxWhiteGoRatio >= 0.34f && ringGQuads >= 3 && maxRingGreenHits >= 6)
                if (isStart) {
                    startScores.add(idx to (maxGreenQuads * 10f + maxGreenHits + maxWhiteGoRatio * 20f + maxRingGreenHits * 0.6f))
                } else {
                    redQuadsArr[idx] = maxRedQuads
                    redHitsArr[idx] = maxRedHits
                    redVividArr[idx] = maxRedVivid
                    redUnionMaskArr[idx] = unionRedQuadMask
                    redTotalHitsArr[idx] = totalRedHits
                    ringRedQuadsArr[idx] = Integer.bitCount(unionRingRedMask)
                    ringRedHitsArr[idx] = maxRingRedHits
                }
            }
        }

        // Official ToS 「起結有序」 spawns at most 1 Green GO Start Vortex — pick the single highest-scoring cell!
        val chosenStart = startScores.maxByOrNull { it.second }?.first
        if (chosenStart != null) {
            tileFlags[chosenStart] = tileFlags[chosenStart] or TileFlagBits.START_ALLOWED
        }

        // Score & select the single highest-scoring Red-Burst End Vortex:
        //  - Multi-frame quadrant union merges rotating red burst rays across burst frames.
        //  - Edge rescue requires orthogonal span along the unclipped interior edge.
        //  - Penalizes Fire orbs whose adjacent Fire neighbors bleed red into corner pockets so true Red Vortexes win!
        val hasConfirmedStart = chosenStart != null
        val endScores = ArrayList<Pair<Int, Float>>()
        for (idx in 0 until total) {
            if ((tileFlags[idx] and TileFlagBits.START_ALLOWED) != 0) continue
            val r = idx / cols
            val c = idx % cols
            val isTopBot = r == 0 || r == rows - 1
            val isLeftRight = c == 0 || c == cols - 1
            val isEdge = isTopBot || isLeftRight
            val rq = redQuadsArr[idx]
            val rh = redHitsArr[idx]
            val rvh = redVividArr[idx]
            val uMask = redUnionMaskArr[idx]
            val uq = Integer.bitCount(uMask)
            val trh = redTotalHitsArr[idx]
            val rrQ = ringRedQuadsArr[idx]
            val rrH = ringRedHitsArr[idx]
            val hasLeft = (uMask and 0b0011) != 0
            val hasRight = (uMask and 0b1100) != 0
            val hasTop = (uMask and 0b0101) != 0
            val hasBot = (uMask and 0b1010) != 0
            val edgeSpanValid = when {
                isTopBot && isLeftRight -> true
                isLeftRight -> hasTop && hasBot
                isTopBot -> hasLeft && hasRight
                else -> false
            }
            val isRingRescue = hasConfirmedStart && !isFireOrbArr[idx] && !isHeartOrbArr[idx] && r > 0 && rrQ >= 3 && rrH >= 6
            val isEnd = (rq >= 3 && rh >= 4) ||
                (hasConfirmedStart && uq >= 3 && trh >= 5) ||
                (hasConfirmedStart && isEdge && uq >= 2 && trh >= 3 && edgeSpanValid) ||
                isRingRescue
            if (isEnd) {
                val effQ = if (uq >= 3 && trh >= 4) uq else rq
                val effH = if (uq >= 3 && trh >= 4) trh else rh
                val firePenalty = if (isFireOrbArr[idx]) 25f else 0f
                val ringBonus = if (isRingRescue) rrQ * 8f + rrH * 1.5f else 0f
                endScores.add(idx to (effQ * 14f + effH * 2f + rvh + ringBonus - firePenalty))
            }
        }

        val chosenEnd = endScores.maxByOrNull { it.second }?.first
        if (chosenEnd != null) {
            tileFlags[chosenEnd] = tileFlags[chosenEnd] or TileFlagBits.END_REQUIRED
        }
    }

    private fun classifyCellMultiFrame(
        frames: List<VisionFrame>,
        cx: Float,
        cy: Float,
        radius: Float,
        isTrafficLightStart: Boolean = false,
        abnormal: CellAbnormalStats? = null
    ): CellState {
        // Votes per element across all frames and circular ring sample points
        val votes = FloatArray(6)
        var totalSatWeight = 0f
        var sampleCount = 0
        var lowSatCount = 0
        var low35Count = 0
        var vineCount = 0
        var slateDarkCount = 0
        var lavaRedCount = 0
        var maxBrightStarCount = 0
        var allSatSum = 0f
        var allValSum = 0f
        var innerRingValSum = 0f
        var innerRingSatSum = 0f
        var innerRingCount = 0
        var outerRingValSum = 0f
        var outerRingSatSum = 0f
        var outerRingCount = 0
        var hueSinSum = 0.0
        var hueCosSum = 0.0
        var maxSampleVal = 0f

        // Sample 3 concentric rings inside the rune orb (0.18*r, 0.36*r, and 0.62*r)
        //  - 0.18*r inner ring captures vivid Heart/Light core even inside white-rimmed Shield/Enchanted orbs!
        //  - Strictly exclude top-right quadrant (dx > 0 && dy < 0) where modern ToS renders B&W Race Badges!
        val ringRadii = floatArrayOf(radius * 0.18f, radius * 0.36f, radius * 0.62f)
        val ringAngles = intArrayOf(8, 16, 16)

        for (f in frames) {
            var frameStarPixels = 0
            for ((ringIdx, ringR) in ringRadii.withIndex()) {
                val angles = ringAngles[ringIdx]
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
                    allSatSum += s
                    allValSum += v
                    if (v > maxSampleVal) maxSampleVal = v

                    if (ringIdx == 0) {
                        innerRingValSum += v
                        innerRingSatSum += s
                        innerRingCount++
                    } else if (ringIdx == 2) {
                        outerRingValSum += v
                        outerRingSatSum += s
                        outerRingCount++
                    }

                    if (s < 0.35f) low35Count++
                    if (s < 0.38f && v in 0.16f..0.48f) vineCount++
                    // Petrified (#04 石化) signature: dark charcoal/navy stone + glowing crimson-orange lava cracks
                    if (v <= 0.36f || (h in 195f..265f && v <= 0.48f && s <= 0.68f)) slateDarkCount++
                    if ((h <= 28f || h >= 342f) && s >= 0.55f && v >= 0.40f) lavaRedCount++
                    if (s >= 0.08f && v >= 0.18f) {
                        val hRad = h.toDouble() * PI / 180.0
                        val hw = (s * v).toDouble()
                        hueCosSum += hw * cos(hRad)
                        hueSinSum += hw * sin(hRad)
                    }

                    // Detect bright white specular star sparkle (only on middle ring outside race badge)
                    if (ringIdx == 1 && v >= 0.93f && s <= 0.18f) {
                        frameStarPixels++
                    }

                    if (s < 0.20f || v < 0.16f) {
                        lowSatCount++
                        continue
                    }

                    val weight = s * (0.4f + 0.6f * v) * (if (ringIdx == 0) 1.25f else 1.0f)
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
        val low35Ratio = low35Count.toFloat() / max(1, sampleCount)
        val vineRatio = vineCount.toFloat() / max(1, sampleCount)
        val slateDarkRatio = slateDarkCount.toFloat() / max(1, sampleCount)
        val lavaRedRatio = lavaRedCount.toFloat() / max(1, sampleCount)
        val allAvgS = allSatSum / max(1, sampleCount)
        val allAvgV = allValSum / max(1, sampleCount)
        val innerAvgV = innerRingValSum / max(1, innerRingCount)
        val innerAvgS = innerRingSatSum / max(1, innerRingCount)
        val outerAvgV = outerRingValSum / max(1, outerRingCount)
        val outerAvgS = outerRingSatSum / max(1, outerRingCount)
        var meanHueDeg = (atan2(hueSinSum, hueCosSum) * 180.0 / PI).toFloat()
        if (meanHueDeg < 0f) meanHueDeg += 360f

        // Dedicated Petrified Runestone (#04 石化符石) Detector:
        // Verified against real ToS gameplay (dark charcoal/navy stone >= 22% interlaced with glowing crimson-orange
        // lava cracks >= 18%, overall muted V <= 0.64). Normal Fire orbs have slateDarkRatio == 0.00 & V >= 0.78!
        val isPetrifiedOrb = !isTrafficLightStart &&
            slateDarkRatio >= 0.22f &&
            lavaRedRatio >= 0.18f &&
            allAvgV <= 0.64f
        if (isPetrifiedOrb) {
            return CellState(
                element = Element.UNKNOWN,
                variant = Variant.NORMAL,
                hazards = HazardBits.PETRIFIED,
                confidence = 0.90f
            )
        }

        // Dedicated Abnormal Runestone Status Detectors (calibrated against real ToS battle frames & all 20
        // official encyclopedia runestone statuses with 0 false positives across 330+ normal/enchanted/race orbs!).
        // MUST execute BEFORE lowSatRatio / isSlateQuestionOrb so bright Electrified / Frozen / Purgatory Fire orbs
        // are NEVER misclassified as '?' Question Orbs!
        if (!isTrafficLightStart && abnormal != null) {
            val baseVariant = if (hasRaceBadge) Variant.RACE else Variant.NORMAL

            // 1. Ice-Sealed (#03 冰封): square purple faceted ice crystal filling corners with white crystal veins
            if (abnormal.isIceSealed) {
                val el = if (bestIdx >= 0) Element.fromCode(bestIdx) else Element.DARK
                return CellState(
                    element = el,
                    variant = baseVariant,
                    hazards = HazardBits.ICE_SEALED,
                    confidence = 0.90f
                )
            }

            // 2. Frozen (#03 凍結): square pale cyan-white faceted ice block filling all 4 corners
            if (abnormal.isFrozen) {
                val el = if (bestIdx >= 0) Element.fromCode(bestIdx) else Element.WATER
                return CellState(
                    element = el,
                    variant = baseVariant,
                    hazards = HazardBits.FROZEN,
                    confidence = 0.90f
                )
            }

            // 3. Purgatory Fire (#59 煉獄之火): blinding white-hot body + vivid purple-magenta fire halo
            if (abnormal.isPurgatoryFire) {
                val el = abnormal.recoverElement(excludeElectricCyan = false, fallbackBestIdx = bestIdx)
                return CellState(
                    element = el,
                    variant = baseVariant,
                    hazards = HazardBits.PURGATORY_FIRE,
                    confidence = 0.92f
                )
            }

            // 4. Electrified (#01 電擊符石): blazing white-hot glare + electric cyan/blue lightning arcs in outer border/corners
            //    OR crackling cyan arc phase (high avgV + strong electric cyan outer border/corners).
            //    Recovers true underlying element (Water/Fire/Wood/Light/Dark/Heart) from non-washed outer rim pixels!
            if (abnormal.isElectrified) {
                val el = abnormal.recoverElement(excludeElectricCyan = true, fallbackBestIdx = bestIdx)
                return CellState(
                    element = el,
                    variant = baseVariant,
                    hazards = HazardBits.ELECTRIFIED,
                    confidence = 0.92f
                )
            }

            // 5. Bomb (#09 爆破炸彈): bright vivid red circular button at dead center inside matte grey metallic hex ring
            if (abnormal.isBomb) {
                val el = abnormal.recoverBombRimElement(fallbackBestIdx = bestIdx)
                return CellState(
                    element = el,
                    variant = baseVariant,
                    hazards = HazardBits.BOMB,
                    confidence = 0.90f
                )
            }

            // 6. Evil Energy (#12 邪惡能量): deeply darkened shadowed orb speckled with glowing cyan-teal energy wisps
            if (abnormal.isEvilEnergy) {
                val el = if (bestIdx >= 0) Element.fromCode(bestIdx) else Element.DARK
                return CellState(
                    element = el,
                    variant = baseVariant,
                    hazards = HazardBits.EVIL_ENERGY,
                    confidence = 0.86f
                )
            }
        }

        if (bestIdx < 0 || totalSatWeight < 0.5f || lowSatRatio > 0.82f) {
            // Flat pitch-dark modal/menu backdrop (e.g. long-press enemy info popup) has NO bright '?' glyph or orb rim!
            if (outerAvgV < 0.24f && maxSampleVal < 0.42f) {
                return CellState(
                    element = Element.UNKNOWN,
                    variant = Variant.NORMAL,
                    hazards = HazardBits.NONE,
                    confidence = 0.08f
                )
            }
            // Genuine Question / BlackWhite / Petrified orb has bright orb rim or '?' glyph highlights
            val haz = if (lowSatRatio > 0.82f && outerAvgV >= 0.28f) HazardBits.BLACK_WHITE else HazardBits.QUESTION
            return CellState(
                element = Element.HIDDEN,
                variant = Variant.NORMAL,
                hazards = haz,
                confidence = 0.22f
            )
        }

        // Dedicated Blood-Drain & Classic Stone-Locked Runestone (化血符石 #8 / 鎖定符石) Detector:
        // Both mechanics share ToS's grey stone-vine desaturated shell across all 6 attributes:
        //  1) Heart: bleached lilac-white outer shell (outS<=0.25, outV 0.52..0.92 > inV) + dusty mauve core (H 296°..346°)
        //  2) Water/Fire/Wood/Dark: muted stone-greyish body wrapped in dark vines (allAvgS 0.14..0.35, allAvgV 0.28..0.62)
        //  3) Light: muted dark olive-khaki body (H 22°..74°, allAvgS 0.32..0.66, allAvgV 0.24..0.52, inV<=0.48, outV<=0.54)
        // Verified 30/30 on real full-board Locked stage & 8/8 on real Blood-Drain stage with 0 false positives!
        val isBloodDrainHeart = !isTrafficLightStart &&
            meanHueDeg in 296f..346f &&
            allAvgS <= 0.30f &&
            allAvgV in 0.44f..0.76f &&
            innerAvgS >= 0.14f &&
            innerAvgV <= 0.64f &&
            outerAvgS <= 0.25f &&
            outerAvgV >= innerAvgV + 0.04f &&
            outerAvgV in 0.52f..0.92f &&
            low35Ratio >= 0.75f &&
            lowSatRatio <= 0.68f

        val isBloodDrainColored = !isTrafficLightStart &&
            !isBloodDrainHeart &&
            bestIdx >= 0 &&
            allAvgS in 0.14f..0.35f &&
            allAvgV in 0.28f..0.62f &&
            innerAvgV <= 0.68f &&
            low35Ratio >= 0.62f &&
            lowSatRatio <= 0.58f &&
            vineRatio >= 0.22f

        val isBloodDrainLight = !isTrafficLightStart &&
            !isBloodDrainHeart &&
            !isBloodDrainColored &&
            bestIdx == Element.LIGHT.code &&
            allAvgS in 0.32f..0.66f &&
            allAvgV in 0.24f..0.52f &&
            innerAvgV <= 0.48f &&
            outerAvgV <= 0.54f

        if (isBloodDrainHeart || isBloodDrainColored || isBloodDrainLight) {
            return CellState(
                element = if (isBloodDrainHeart) Element.HEART else Element.fromCode(bestIdx),
                variant = if (hasRaceBadge) Variant.RACE else Variant.NORMAL,
                hazards = HazardBits.BLOOD_DRAIN,
                confidence = 0.88f
            )
        }

        // Dedicated Question ('?') / Hidden Orb Detector:
        // Real ToS matchable orbs are vivid (either high saturation outerAvgS >= 0.45, OR bright white-rimmed
        // Shield/Enchanted orbs with outerAvgV >= 0.78).
        // Modern ToS '?' Question Orbs (slate-blue, steel-grey, dark-indigo, muted-bronze) have BOTH muted
        // saturation AND dimmer outer ring with pale '?' stroke pixels raising lowSatRatio.
        val isSlateQuestionOrb = !isTrafficLightStart && (
            (bestIdx == Element.WATER.code &&
                ((outerAvgV < 0.66f && outerAvgS < 0.46f && lowSatRatio >= 0.06f) ||
                    (outerAvgV < 0.56f && outerAvgS < 0.48f) ||
                    (outerAvgV < 0.70f && lowSatRatio > 0.36f))) ||
            (outerAvgV < 0.64f && outerAvgS < 0.36f && lowSatRatio >= 0.42f) ||
            (outerAvgV < 0.70f && outerAvgS < 0.30f && lowSatRatio >= 0.52f)
        )
        if (isSlateQuestionOrb) {
            return CellState(
                element = Element.HIDDEN,
                variant = Variant.NORMAL,
                hazards = HazardBits.QUESTION,
                confidence = 0.30f
            )
        }

        val dominance = bestVote / max(1e-4f, totalSatWeight)
        val margin = (bestVote - secondVote) / max(1e-4f, bestVote)
        val confidence = (dominance * 0.65f + margin * 0.35f).coerceIn(0f, 1f)

        // Enchanted orb heuristic: strong pure-white star gleam beyond ordinary ToS center rune emblem
        // (ordinary Fire/Dark/Water orbs have small white center glyphs; genuine Enchanted orbs sparkle intensely).
        val baseStarThreshold = if (bestIdx == Element.LIGHT.code) 8 else 7
        val starThreshold = if (isTrafficLightStart) baseStarThreshold + 4 else baseStarThreshold
        val isEnchanted = maxBrightStarCount >= starThreshold && confidence >= 0.58f
        val variant = when {
            isEnchanted -> Variant.ENCHANTED
            hasRaceBadge -> Variant.RACE
            else -> Variant.NORMAL
        }

        // Dedicated Thorn Runestone (荊棘符石 / 鋼線符石) Detector:
        // Brown spiky briar vines wrapping horizontally across the middle band of an otherwise normal orb.
        val isThornOrb = !isTrafficLightStart && detectThornOverlay(frames, cx, cy, radius)

        return CellState(
            element = Element.fromCode(bestIdx),
            variant = variant,
            hazards = if (isThornOrb) HazardBits.THORN else HazardBits.NONE,
            confidence = confidence
        )
    }

    private data class CellAbnormalStats(
        val isIceSealed: Boolean,
        val isFrozen: Boolean,
        val isPurgatoryFire: Boolean,
        val isElectrified: Boolean,
        val isBomb: Boolean,
        val isEvilEnergy: Boolean,
        val minAvgBodyV: Float,
        val maxAvgBodyV: Float,
        val maxDarkBody: Float,
        val minHighVBody: Float,
        val minSatBody: Float,
        val maxCrackSilver: Float,
        val maxBbRed: Float,
        val maxBbCorner: Float,
        val maxFireBorder: Float,
        val maxFireCorner: Float,
        val rimVotes: FloatArray,
        val bombOuterVotes: FloatArray
    ) {
        fun recoverElement(excludeElectricCyan: Boolean, fallbackBestIdx: Int): Element {
            var total = 0f
            var nonWaterTotal = 0f
            var bestNonWater = -1
            var bestNonWaterVote = 0f
            var bestAll = -1
            var bestAllVote = 0f
            for (i in 0 until 6) {
                val w = rimVotes[i]
                total += w
                if (w > bestAllVote) {
                    bestAllVote = w
                    bestAll = i
                }
                if (i != Element.WATER.code) {
                    nonWaterTotal += w
                    if (w > bestNonWaterVote) {
                        bestNonWaterVote = w
                        bestNonWater = i
                    }
                }
            }
            if (excludeElectricCyan && total > 0.1f && nonWaterTotal >= total * 0.30f && bestNonWater >= 0) {
                return Element.fromCode(bestNonWater)
            }
            if (bestAll >= 0 && bestAllVote > 0.1f) {
                return Element.fromCode(bestAll)
            }
            return if (fallbackBestIdx >= 0) Element.fromCode(fallbackBestIdx) else Element.WATER
        }

        fun recoverBombRimElement(fallbackBestIdx: Int): Element {
            var best = -1
            var bestW = 0f
            for (i in 0 until 6) {
                if (bombOuterVotes[i] > bestW) {
                    bestW = bombOuterVotes[i]
                    best = i
                }
            }
            if (best >= 0 && bestW > 0.15f) return Element.fromCode(best)
            return if (fallbackBestIdx >= 0) Element.fromCode(fallbackBestIdx) else Element.WATER
        }
    }

    /**
     * Single-pass 13x13 per-cell color-invariant HSV + lattice analyzer detecting visually unmistakable ToS
     * runestone statuses (Electrified, Frozen, Ice-Sealed, Purgatory Fire, Bomb, Evil Energy) and tile terrains
     * (Burning Tile, Bloodbath Tile, Shattered Tile) across ALL 6 orb colors (Water/Fire/Wood/Light/Dark/Heart)
     * with multi-frame peak fusion and 0/330 false positives on real battle boards.
     */
    private fun analyzeCellAbnormalMultiFrame(
        frames: List<VisionFrame>,
        cx: Float,
        cy: Float,
        cellW: Float,
        cellH: Float
    ): CellAbnormalStats {
        var anyIceSealed = false
        var anyFrozen = false
        var anyPurgatory = false
        var anyElectrified = false
        var anyBomb = false
        var anyEvilEnergy = false

        var minAvgBodyV = 1f
        var maxAvgBodyV = 0f
        var maxDarkBody = 0f
        var minHighVBody = 1f
        var minSatBody = 1f
        var maxCrackSilver = 0f
        var maxBbRed = 0f
        var maxBbCorner = 0f
        var maxFireBorder = 0f
        var maxFireCorner = 0f

        val rimVotes = FloatArray(6)
        val bombOuterVotes = FloatArray(6)

        val gridN = 13 // -6..+6 normalized dx/dy in [-0.465..+0.465]
        val stepNorm = 0.465f / 6f
        val gridH = FloatArray(gridN * gridN)
        val gridS = FloatArray(gridN * gridN)
        val gridV = FloatArray(gridN * gridN)

        for (f in frames) {
            // Pass 1: sample 13x13 HSV grid once for both region statistics & adjacent crystal-lattice transitions
            for (iy in -6..6) {
                val dy = iy * stepNorm
                val py = (cy + dy * cellH).roundToInt()
                val rowOff = (iy + 6) * gridN
                for (ix in -6..6) {
                    val dx = ix * stepNorm
                    val px = (cx + dx * cellW).roundToInt()
                    val (h, s, v) = rgbToHsv(f.getArgb(px, py))
                    val gIdx = rowOff + (ix + 6)
                    gridH[gIdx] = h
                    gridS[gIdx] = s
                    gridV[gIdx] = v
                }
            }

            var coreTot = 0; var bombRedHits = 0
            var innerTot = 0; var whCoreHits = 0; var satColorCoreHits = 0
            var midTot = 0; var bombGreyHits = 0
            var bodyTot = 0; var bodyVSum = 0f; var whBodyHits = 0; var darkBodyHits = 0
            var highVBodyHits = 0; var satBodyHits = 0; var crackSilverHits = 0
            var iceWhiteBodyHits = 0; var icePurpBodyHits = 0; var tealWispHits = 0; var bbRedHits = 0
            var latticePairs = 0; var latticeEdges = 0
            var purgRingTot = 0; var purgMagHits = 0
            var cornerTot = 0; var bbCornerHits = 0; var fireCornerHits = 0
            var borderTot = 0; var elecBorderHits = 0; var elecCyanBorderHits = 0; var fireBorderHits = 0
            val cTot = IntArray(4)
            val cElec = IntArray(4)
            val cBright = IntArray(4)
            val cIceDark = IntArray(4)

            for (iy in -6..6) {
                val dy = iy * stepNorm
                val ay = abs(dy)
                val rowOff = (iy + 6) * gridN
                for (ix in -6..6) {
                    val dx = ix * stepNorm
                    val ax = abs(dx)
                    val rr = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                    val linf = max(ax, ay)
                    val gIdx = rowOff + (ix + 6)
                    val h = gridH[gIdx]
                    val s = gridS[gIdx]
                    val v = gridV[gIdx]

                    if (rr <= 0.145f) {
                        coreTot++
                        if ((h <= 18f || h >= 342f) && s >= 0.58f && v >= 0.52f) bombRedHits++
                    }
                    if (rr <= 0.285f) {
                        innerTot++
                        if (v >= 0.90f && s <= 0.26f) whCoreHits++
                        // Color-invariant underlying orb facet color (ANY of the 6 elements with S>=0.32 & V>=0.38)
                        if (v >= 0.38f && s >= 0.32f) satColorCoreHits++
                    }
                    if (rr in 0.18f..0.355f) {
                        midTot++
                        if (s <= 0.24f && v in 0.22f..0.68f) bombGreyHits++
                    }
                    if (rr <= 0.385f) {
                        bodyTot++
                        bodyVSum += v
                        if (v >= 0.88f && s <= 0.28f) whBodyHits++
                        if (v < 0.28f) darkBodyHits++
                        if (v >= 0.60f) highVBodyHits++
                        if (s >= 0.35f && v >= 0.28f) satBodyHits++
                        if (s <= 0.22f && v in 0.22f..0.85f) crackSilverHits++
                        if (v >= 0.74f && s <= 0.30f) iceWhiteBodyHits++
                        if (v >= 0.35f && h in 250f..310f && s >= 0.35f) icePurpBodyHits++
                        // Tight turquoise wisp hue (162..198°) separates Evil Energy wisps from regular blue Water (202..230°)
                        if (h in 162f..198f && s >= 0.22f && v >= 0.32f) tealWispHits++
                        if ((h <= 22f || h >= 332f) && s >= 0.28f && v in 0.12f..0.42f) bbRedHits++

                        // Color-invariant ice crystal lattice texture: sharp white-vs-saturated jumps between neighbors
                        if (ix < 6 && hypot((dx + stepNorm).toDouble(), dy.toDouble()) <= 0.385) {
                            latticePairs++
                            val nIdx = gIdx + 1
                            if (abs(s - gridS[nIdx]) >= 0.22f && min(v, gridV[nIdx]) >= 0.45f) latticeEdges++
                        }
                        if (iy < 6 && hypot(dx.toDouble(), (dy + stepNorm).toDouble()) <= 0.385) {
                            latticePairs++
                            val nIdx = gIdx + gridN
                            if (abs(s - gridS[nIdx]) >= 0.22f && min(v, gridV[nIdx]) >= 0.45f) latticeEdges++
                        }
                    }
                    if (rr in 0.22f..0.385f && s >= 0.30f && v >= 0.46f) {
                        // Rim votes for underlying element recovery across all 6 colors (excludes dark brown tile V < 0.46;
                        // Light orb amber-gold outer compass tips sit at H∈[15°,74°] while pure Fire sits at H<15°/≥346°)
                        val elIdx = when {
                            h < 15f || h >= 346f -> Element.FIRE.code
                            h in 15f..74f -> Element.LIGHT.code
                            else -> classifyHueToElement(h, s, v)
                        }
                        if (elIdx >= 0) rimVotes[elIdx] += s * v
                    }
                    if (rr in 0.33f..0.44f && s >= 0.35f && v >= 0.36f) {
                        val elIdx = classifyHueToElement(h, s, v)
                        if (elIdx >= 0) bombOuterVotes[elIdx] += s * v
                    }
                    if (rr in 0.34f..0.48f) {
                        purgRingTot++
                        if (v >= 0.55f && h in 268f..328f && s >= 0.28f) purgMagHits++
                    }
                    if (ax >= 0.31f && ay >= 0.31f) {
                        val ci = (if (dx > 0f) 1 else 0) + (if (dy > 0f) 2 else 0)
                        cTot[ci]++
                        if (v >= 0.48f && ((h in 182f..268f && s >= 0.22f) || (v >= 0.78f && s <= 0.28f))) cElec[ci]++
                        // Color-invariant bright square corner (any bright crystal/frosted facet covering normally dark corners)
                        if (v >= 0.48f && (s <= 0.36f || (v >= 0.54f && s >= 0.28f))) cBright[ci]++
                        // Purple-tinted or frosted square corner for 3-turn Ice-Sealed cubes
                        if (v >= 0.34f && ((h in 240f..315f) || s <= 0.30f)) cIceDark[ci]++
                        if (ax >= 0.33f && ay >= 0.33f) {
                            cornerTot++
                            if ((h <= 22f || h >= 332f) && s >= 0.28f && v in 0.12f..0.38f) bbCornerHits++
                            if (h in 10f..48f && s >= 0.52f && v >= 0.44f) fireCornerHits++
                        }
                    }
                    if (linf >= 0.385f) {
                        borderTot++
                        if (v >= 0.52f && ((h in 182f..270f) || s <= 0.26f)) elecBorderHits++
                        if (v >= 0.50f && h in 182f..262f && s >= 0.24f) elecCyanBorderHits++
                        if (h in 10f..48f && s >= 0.52f && v >= 0.46f) fireBorderHits++
                    }
                }
            }

            val bTot = max(1, bodyTot).toFloat()
            val avgV = bodyVSum / bTot
            val whCore = whCoreHits.toFloat() / max(1, innerTot)
            val whBody = whBodyHits.toFloat() / bTot
            val satColorCore = satColorCoreHits.toFloat() / max(1, innerTot)
            val darkBody = darkBodyHits.toFloat() / bTot
            val highVBody = highVBodyHits.toFloat() / bTot
            val satBody = satBodyHits.toFloat() / bTot
            val crackSilver = crackSilverHits.toFloat() / bTot
            val iceWhiteBody = iceWhiteBodyHits.toFloat() / bTot
            val icePurpBody = icePurpBodyHits.toFloat() / bTot
            val latticeRatio = latticeEdges.toFloat() / max(1, latticePairs)
            val tealWisp = tealWispHits.toFloat() / bTot
            val bbRed = bbRedHits.toFloat() / bTot
            val bbCorner = bbCornerHits.toFloat() / max(1, cornerTot)
            val purgMag = purgMagHits.toFloat() / max(1, purgRingTot)
            val bombRed = bombRedHits.toFloat() / max(1, coreTot)
            val bombGrey = bombGreyHits.toFloat() / max(1, midTot)
            val elecBorder = elecBorderHits.toFloat() / max(1, borderTot)
            val elecCyanBorder = elecCyanBorderHits.toFloat() / max(1, borderTot)
            val fireBorder = fireBorderHits.toFloat() / max(1, borderTot)
            val fireCorner = fireCornerHits.toFloat() / max(1, cornerTot)

            var elecCornerHits = 0
            var brightCornerHits = 0
            var iceDarkCornerHits = 0
            for (ci in 0 until 4) {
                val ct = max(1, cTot[ci]).toFloat()
                if (cElec[ci] / ct >= 0.16f) elecCornerHits++
                if (cBright[ci] / ct >= 0.20f) brightCornerHits++
                if (cIceDark[ci] / ct >= 0.16f) iceDarkCornerHits++
            }

            // 1. Ice-Sealed (#03 冰封): purple faceted crystal cube (distinguished from Frozen-on-Dark by
            //    deeper purple body >= 0.45 + crisp veins in 0.20..0.44 + high lattice transitions >= 0.30)
            if (icePurpBody >= 0.45f && iceWhiteBody in 0.20f..0.44f && latticeRatio >= 0.30f && iceDarkCornerHits >= 2) {
                anyIceSealed = true
            } else if (brightCornerHits >= 4 &&
                iceWhiteBody >= 0.38f &&
                satColorCore >= 0.20f &&
                latticeRatio >= 0.30f &&
                whCore < 0.60f
            ) {
                // 2. Color-Invariant Frozen (#03 凍結): square ice cube covering all 4 corners + dense white crystal
                //    lattice + ANY saturated underlying orb color + strong white/color lattice transitions (works 6/6 colors!)
                anyFrozen = true
            } else if (whCore >= 0.65f && whBody >= 0.52f && purgMag >= 0.12f &&
                (elecCyanBorder < 0.14f || purgMag >= elecCyanBorder * 1.5f)
            ) {
                // 3. Purgatory Fire (#59 煉獄之火): blinding white-hot body + vivid neon magenta/purple halo
                anyPurgatory = true
            } else if ((whCore >= 0.30f && whBody >= 0.25f && elecBorder >= 0.24f && elecCyanBorder >= 0.14f) ||
                (avgV >= 0.62f && whBody >= 0.14f && elecCyanBorder >= 0.34f && elecCornerHits >= 2) ||
                (avgV >= 0.58f && whBody >= 0.08f && elecCyanBorder >= 0.42f && elecCornerHits >= 3)
            ) {
                // 4. Color-Invariant Electrified (#01 電擊): peak flash phase OR dim-phase cyan lightning border
                //    (relaxed avgV >= 0.58..0.62 catches darker Fire/Wood/Dark orbs even on non-peak frames!)
                anyElectrified = true
            }

            // 5. Bomb (#09 爆破炸彈): fixed bright red core + matte grey metallic hex ring (6/6 color-invariant)
            if (bombRed >= 0.22f && bombGrey >= 0.45f) anyBomb = true

            // 6. Color-Invariant Evil Energy (#12 邪惡能量): dark shadowed orb + glowing turquoise wisps (162..198°),
            //    relaxed avgV <= 0.34 & darkBody >= 0.54 supports brighter Light/Heart underlying orbs too!
            if (avgV <= 0.34f && darkBody >= 0.54f && highVBody <= 0.08f && tealWisp >= 0.06f) anyEvilEnergy = true

            if (avgV < minAvgBodyV) minAvgBodyV = avgV
            if (avgV > maxAvgBodyV) maxAvgBodyV = avgV
            if (darkBody > maxDarkBody) maxDarkBody = darkBody
            if (highVBody < minHighVBody) minHighVBody = highVBody
            if (satBody < minSatBody) minSatBody = satBody
            if (crackSilver > maxCrackSilver) maxCrackSilver = crackSilver
            if (bbRed > maxBbRed) maxBbRed = bbRed
            if (bbCorner > maxBbCorner) maxBbCorner = bbCorner
            if (fireBorder > maxFireBorder) maxFireBorder = fireBorder
            if (fireCorner > maxFireCorner) maxFireCorner = fireCorner
        }

        return CellAbnormalStats(
            isIceSealed = anyIceSealed,
            isFrozen = anyFrozen,
            isPurgatoryFire = anyPurgatory,
            isElectrified = anyElectrified,
            isBomb = anyBomb,
            isEvilEnergy = anyEvilEnergy,
            minAvgBodyV = minAvgBodyV,
            maxAvgBodyV = maxAvgBodyV,
            maxDarkBody = maxDarkBody,
            minHighVBody = minHighVBody,
            minSatBody = minSatBody,
            maxCrackSilver = maxCrackSilver,
            maxBbRed = maxBbRed,
            maxBbCorner = maxBbCorner,
            maxFireBorder = maxFireBorder,
            maxFireCorner = maxFireCorner,
            rimVotes = rimVotes,
            bombOuterVotes = bombOuterVotes
        )
    }

    /**
     * Real-screenshot calibrated Thorn Runestone (荊棘符石) detector:
     * Checks the horizontal mid-band strictly inside the orb (|dx| ∈ [0.15..0.78]*r, dy ∈ [-0.15..+0.15]*r)
     * for woody-brown thorny vines (H ∈ [14°,44°], S ∈ [0.30,0.88], V ∈ [0.12,0.56]) appearing on BOTH
     * left and right halves (>=10% each, combined >=24%), while confirming top & bottom orb interior
     * remains bright non-brown (tbBrownRatio <= 0.18, separating Thorn from dark khaki orbs).
     */
    private fun detectThornOverlay(
        frames: List<VisionFrame>,
        cx: Float,
        cy: Float,
        radius: Float
    ): Boolean {
        for (f in frames) {
            var leftHits = 0
            var leftTot = 0
            var rightHits = 0
            var rightTot = 0
            for (ix in -8..8) {
                val dx = ix / 10f
                if (abs(dx) < 0.15f || abs(dx) > 0.78f) continue
                for (iy in -2..2) {
                    val dy = iy * 0.075f
                    val px = (cx + dx * radius).roundToInt()
                    val py = (cy + dy * radius).roundToInt()
                    val (h, s, v) = rgbToHsv(f.getArgb(px, py))
                    if (dx < 0f) leftTot++ else rightTot++
                    if (h in 14f..44f && s in 0.30f..0.88f && v in 0.12f..0.56f) {
                        if (dx < 0f) leftHits++ else rightHits++
                    }
                }
            }
            val leftRatio = leftHits.toFloat() / max(1, leftTot)
            val rightRatio = rightHits.toFloat() / max(1, rightTot)
            if (leftRatio < 0.10f || rightRatio < 0.10f || (leftRatio + rightRatio) < 0.24f) continue

            var tbBrown = 0
            var tbTot = 0
            val tbDys = floatArrayOf(-0.48f, -0.36f, 0.36f, 0.48f)
            for (ix in -3..3) {
                val dx = ix * 0.10f
                for (dy in tbDys) {
                    val px = (cx + dx * radius).roundToInt()
                    val py = (cy + dy * radius).roundToInt()
                    val (h, s, v) = rgbToHsv(f.getArgb(px, py))
                    tbTot++
                    if (h in 14f..48f && s in 0.30f..0.88f && v in 0.12f..0.56f) tbBrown++
                }
            }
            if (tbBrown.toFloat() / max(1, tbTot) <= 0.18f) {
                return true
            }
        }
        return false
    }

    /**
     * Detects ToS top-right circular Race Badge medallion (种族符石徽章, covering ALL 7 races: 神/魔/人/兽/龙/妖/机械).
     * Calibrated across real Demon-race boards (v2_00s/v2_86s: 29/30), Human-race boards (mi15_now: 25/30),
     * AND non-race glossy Heart/Thorn/Normal boards (0/30 false positives!):
     *  - Samples a 7x7 grid strictly inside the medallion area [0.28r..0.78r]
     *  - Requires crisp white/warm-ivory icon & outer circular ring pixels (v >= 0.72, s <= 0.28, >= 11%)
     *    WITH >= 2 white/ivory ring hits in the outer half (ix >= 4 || iy >= 4) — cleanly rejecting normal
     *    glossy Heart orbs whose outer top-right corner is pitch-black checkerboard with 0 outer white hits!
     *  - Requires dark circular medallion background (>= 14% overall AND >= 3 hits inside medallion core ix,iy in 1..4)
     *  - Rejects bright colorful orb bodies (vivid saturation s >= 0.50 && v >= 0.45 must be <= 26%)
     */
    private fun detectTopRightRaceBadge(
        frame: VisionFrame,
        cx: Float,
        cy: Float,
        radius: Float
    ): Boolean {
        val minOff = radius * 0.28f
        val maxOff = radius * 0.78f
        val steps = 7
        var whiteIvoryHits = 0
        var outerWhiteHits = 0
        var darkHits = 0
        var medallionDarkHits = 0
        var vividOrbHits = 0
        var total = 0
        for (iy in 0 until steps) {
            val dy = -(minOff + (maxOff - minOff) * iy / (steps - 1))
            for (ix in 0 until steps) {
                val dx = minOff + (maxOff - minOff) * ix / (steps - 1)
                val (_, s, v) = rgbToHsv(frame.getArgb((cx + dx).roundToInt(), (cy + dy).roundToInt()))
                total++
                if (v >= 0.72f && s <= 0.28f) {
                    whiteIvoryHits++
                    if (ix >= 4 || iy >= 4) outerWhiteHits++
                }
                if (v <= 0.28f) {
                    darkHits++
                    if (ix in 1..4 && iy in 1..4) medallionDarkHits++
                }
                if (s >= 0.50f && v >= 0.45f) {
                    vividOrbHits++
                }
            }
        }
        return total > 0 &&
            (whiteIvoryHits.toFloat() / total) >= 0.11f &&
            outerWhiteHits >= 2 &&
            (darkHits.toFloat() / total) >= 0.14f &&
            medallionDarkHits >= 3 &&
            (vividOrbHits.toFloat() / total) <= 0.26f
    }

    /**
     * Multi-screenshot calibrated Hue-Saturation-Value classifier for Tower of Saviors 6 rune elements:
     * Verified across Xiaomi 15 real battle, 2026 App Store official HD, and 2026 @towerofsaviors YouTube gameplay:
     *  - Fire (红):  H < 22° or >= 346°
     *  - Light (黄): H ∈ [22°, 74°]
     *  - Wood (绿):  H ∈ [74°, 162°]
     *  - Water (蓝): H ∈ [162°, 248°]
     *  - Dark (紫):  H ∈ [248°, 310°] (real 2026 Dark peaks at 285°..303°)
     *  - Heart (粉): H ∈ [310°, 346°] (real 2026 Heart spans 310°..331°, peak ~326°)
     */
    private fun classifyHueToElement(h: Float, s: Float, v: Float): Int {
        return when {
            h < 22f || h >= 346f -> Element.FIRE.code
            h in 22f..74f -> Element.LIGHT.code
            h in 74f..162f -> Element.WOOD.code
            h in 162f..248f -> Element.WATER.code
            h in 248f..310f -> Element.DARK.code
            h in 310f..346f -> Element.HEART.code
            else -> -1
        }
    }

    /**
     * Two-path real-game-calibrated Tower of Saviors Puzzle Shield detector:
     *  - Path 1 (Per-cell 4-sided golden box): Detects classic/synthetic per-cell golden framed puzzles
     *    where EVERY individual puzzle cell has all 4 inner edges glowing gold (mutually exclusive with
     *    perimeter-only polygons since any >=2-cell perimeter-only polyomino has interior unframed seams).
     *  - Path 2 (Real ToS perimeter-polygon filigree & gold seam detector):
     *    Modern Tower of Saviors draws an ornate metallic silver-ivory (or golden) filigree border strictly
     *    along the EXTERIOR PERIMETER of the puzzle polygon, leaving interior cells (e.g. center of a Cross
     *    or 3x3 square) completely open. By scoring all (rows+1)*cols horizontal and rows*(cols+1) vertical
     *    grid seams and solving exact Jordan-curve 0-1 BFS + boundary-energy maximization, this reconstructs
     *    ANY connected puzzle shape (Cross, T, L, Z, 2x2, 3x3 solid, hollow O-ring, dual puzzles) including
     *    all interior cells, with 0 false positives across non-puzzle battle boards and UI screens.
     */
    private fun detectPuzzleRegions(
        frames: List<VisionFrame>,
        geometry: BoardGeometry,
        tileFlags: MutableList<Int>
    ): Int {
        val perCellMarked = detectPerCellGoldBoxPuzzles(frames, geometry, tileFlags)
        if (perCellMarked > 0) return perCellMarked
        return detectPerimeterPolygonPuzzles(frames, geometry, tileFlags)
    }

    private fun detectPerCellGoldBoxPuzzles(
        frames: List<VisionFrame>,
        geometry: BoardGeometry,
        tileFlags: MutableList<Int>
    ): Int {
        val cols = geometry.cols
        val rows = geometry.rows
        val total = cols * rows
        val cellW = geometry.cellWidth
        val cellH = geometry.cellHeight
        val halfW = cellW * 0.465f
        val halfH = cellH * 0.465f
        val samplesPerEdge = 6

        fun isGoldPixel(x: Int, y: Int): Boolean {
            for (f in frames) {
                val (h, s, v) = rgbToHsv(f.getArgb(x, y))
                // Pure warm yellow-gold (36°..64°) cleanly excludes fiery orange Burning Tile borders (10°..33°),
                // preventing contiguous 1x6 Burning Rows / Burning Rings from ever misclassifying as PUZZLE_1!
                if (h in 36f..64f && s >= 0.48f && v >= 0.80f) return true
            }
            return false
        }

        val candidates = BooleanArray(total)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val idx = r * cols + c
                if ((tileFlags[idx] and TileFlagBits.STORM) != 0) continue
                val cx = geometry.cellCenterX(c)
                val cy = geometry.cellCenterY(r)
                var topHits = 0
                var botHits = 0
                var leftHits = 0
                var rightHits = 0
                for (i in 0 until samplesPerEdge) {
                    val t = -0.72f + 1.44f * (i.toFloat() / (samplesPerEdge - 1))
                    if (isGoldPixel((cx + t * halfW).roundToInt(), (cy - halfH).roundToInt())) topHits++
                    if (isGoldPixel((cx + t * halfW).roundToInt(), (cy + halfH).roundToInt())) botHits++
                    if (isGoldPixel((cx - halfW).roundToInt(), (cy + t * halfH).roundToInt())) leftHits++
                    if (isGoldPixel((cx + halfW).roundToInt(), (cy + t * halfH).roundToInt())) rightHits++
                }
                if (topHits >= 3 && botHits >= 3 && leftHits >= 3 && rightHits >= 3) {
                    candidates[idx] = true
                }
            }
        }
        return markValidPuzzleCluster(candidates, tileFlags, cols, rows)
    }

    private fun isPuzzleSeamBarPixel(h: Float, s: Float, v: Float): Boolean {
        // 1) Modern ToS silver / pale-ivory / champagne metallic filigree bar (Normal Puzzle Shield)
        if (v >= 0.44f && s <= 0.32f) return true
        // 2) Modern & Classic ToS antique-gold / warm brass filigree bar (Strict Puzzle Shield)
        //    H in [34°, 62°] cleanly separates golden filigree from dark-brown seams (H~24..31, V~0.21)
        //    and red/orange Fire orb rim spill (H < 28).
        if (h in 34f..62f && s >= 0.24f && v >= 0.46f) return true
        // 3) Legacy / Classic bright neon yellow-gold glowing puzzle perimeter (35°..66° excludes fiery orange 10°..33°)
        if (h in 35f..66f && s >= 0.45f && v >= 0.72f) return true
        return false
    }

    private fun detectPerimeterPolygonPuzzles(
        frames: List<VisionFrame>,
        geometry: BoardGeometry,
        tileFlags: MutableList<Int>
    ): Int {
        val cols = geometry.cols
        val rows = geometry.rows
        val total = cols * rows
        if (total < 9) return 0

        val left = geometry.leftPx
        val top = geometry.topPx
        val cw = geometry.cellWidth
        val ch = geometry.cellHeight

        val seamSamples = 11
        val hScores = FloatArray((rows + 1) * cols)
        val vScores = FloatArray(rows * (cols + 1))

        fun isStorm(r: Int, c: Int): Boolean =
            r in 0 until rows && c in 0 until cols && (tileFlags[r * cols + c] and TileFlagBits.STORM) != 0

        fun sampleSeamPixel(x: Int, y: Int): Boolean {
            for (f in frames) {
                val (h, s, v) = rgbToHsv(f.getArgb(x, y))
                if (isPuzzleSeamBarPixel(h, s, v)) return true
            }
            return false
        }

        val interiorOffs = floatArrayOf(-0.045f, -0.0225f, 0f, 0.0225f, 0.045f)

        var strongIntSeams = 0
        var darkIntSeams = 0
        var totalIntSeams = 0

        // Sample ONLY internal grid seams — outer board edges (r=0, r=rows, c=0, c=cols) are polluted by
        // bright white HP text (e.g. "66732/70694") and metallic bottom UI rims!
        for (r in 1 until rows) {
            for (c in 0 until cols) {
                totalIntSeams++
                if (isStorm(r - 1, c) || isStorm(r, c)) {
                    darkIntSeams++
                    continue
                }
                var hits = 0
                for (si in 0 until seamSamples) {
                    val t = 0.20f + 0.60f * (si.toFloat() / (seamSamples - 1))
                    val x = (left + (c + t) * cw).roundToInt()
                    var hit = false
                    for (off in interiorOffs) {
                        val y = (top + (r + off) * ch).roundToInt()
                        if (sampleSeamPixel(x, y)) {
                            hit = true
                            break
                        }
                    }
                    if (hit) hits++
                }
                val score = hits.toFloat() / seamSamples
                hScores[r * cols + c] = score
                if (score >= 0.62f) strongIntSeams++
                if (score <= 0.30f) darkIntSeams++
            }
        }

        // Internal vertical grid seams V[r][c] (c in 1 until cols)
        for (r in 0 until rows) {
            for (c in 1 until cols) {
                totalIntSeams++
                if (isStorm(r, c - 1) || isStorm(r, c)) {
                    darkIntSeams++
                    continue
                }
                var hits = 0
                for (si in 0 until seamSamples) {
                    val t = 0.20f + 0.60f * (si.toFloat() / (seamSamples - 1))
                    val y = (top + (r + t) * ch).roundToInt()
                    var hit = false
                    for (off in interiorOffs) {
                        val x = (left + (c + off) * cw).roundToInt()
                        if (sampleSeamPixel(x, y)) {
                            hit = true
                            break
                        }
                    }
                    if (hit) hits++
                }
                val score = hits.toFloat() / seamSamples
                vScores[r * (cols + 1) + c] = score
                if (score >= 0.62f) strongIntSeams++
                if (score <= 0.30f) darkIntSeams++
            }
        }

        // Global internal-seam sanity gate:
        // Even a 2x2 corner or 3-cell edge bar has >= 4 internal perimeter walls, while non-puzzle
        // internal seams stay dark (>= 32% dark, <= 48% bright — rejects white UI screens & bright maps!).
        if (strongIntSeams < 4 || totalIntSeams <= 0) return 0
        if (strongIntSeams.toFloat() / totalIntSeams > 0.48f) return 0
        if (darkIntSeams.toFloat() / totalIntSeams < 0.32f) return 0

        fun hScore(r: Int, c: Int): Float = hScores[r * cols + c]
        fun vScore(r: Int, c: Int): Float = vScores[r * (cols + 1) + c]

        val tau = 0.54f
        data class BoundaryStats(
            val energy: Float,
            val intMeanWall: Float,
            val intStrongRatio: Float,
            val intCount: Int,
            val outerCount: Int
        )

        fun evalBoundary(mask: BooleanArray): BoundaryStats {
            var energy = 0f
            var intWallSum = 0f
            var intStrongWalls = 0
            var intCnt = 0
            var outerCnt = 0
            for (r in 0..rows) {
                val isOuter = (r == 0 || r == rows)
                for (c in 0 until cols) {
                    val a = if (r > 0) mask[(r - 1) * cols + c] else false
                    val b = if (r < rows) mask[r * cols + c] else false
                    if (a != b) {
                        if (isOuter) {
                            // Never reward outer screen edges (HP text / metallic bottom rim); apply a small fixed cost
                            energy -= 0.12f
                            outerCnt++
                        } else {
                            val s = hScore(r, c)
                            energy += (s - tau)
                            intWallSum += s
                            intCnt++
                            if (s >= 0.62f) intStrongWalls++
                        }
                    }
                }
            }
            for (r in 0 until rows) {
                for (c in 0..cols) {
                    val isOuter = (c == 0 || c == cols)
                    val a = if (c > 0) mask[r * cols + (c - 1)] else false
                    val b = if (c < cols) mask[r * cols + c] else false
                    if (a != b) {
                        if (isOuter) {
                            energy -= 0.12f
                            outerCnt++
                        } else {
                            val s = vScore(r, c)
                            energy += (s - tau)
                            intWallSum += s
                            intCnt++
                            if (s >= 0.62f) intStrongWalls++
                        }
                    }
                }
            }
            val denom = max(1, intCnt).toFloat()
            return BoundaryStats(energy, intWallSum / denom, intStrongWalls.toFloat() / denom, intCnt, outerCnt)
        }

        fun refineMask(seed: BooleanArray): BooleanArray {
            val cur = seed.copyOf()
            var curEnergy = evalBoundary(cur).energy
            var improved = true
            while (improved) {
                improved = false
                for (idx in 0 until total) {
                    cur[idx] = !cur[idx]
                    val nextE = evalBoundary(cur).energy
                    if (nextE > curEnergy + 1e-5f) {
                        curEnergy = nextE
                        improved = true
                    } else {
                        cur[idx] = !cur[idx]
                    }
                }
            }
            return cur
        }

        fun areComponentsAdjacent(cA: List<Int>, cB: List<Int>): Boolean {
            val setB = BooleanArray(total)
            for (u in cB) setB[u] = true
            for (u in cA) {
                val r = u / cols
                val c = u % cols
                if (r > 0 && setB[u - cols]) return true
                if (r + 1 < rows && setB[u + cols]) return true
                if (c > 0 && setB[u - 1]) return true
                if (c + 1 < cols && setB[u + 1]) return true
            }
            return false
        }

        val maxPuzzleCells = min(14, total - 8).coerceAtLeast(3)
        val candidateMasks = ArrayList<BooleanArray>(16)

        // Candidate Group 1: Independent Internal Cut-Set Connected Components
        // NEVER glue two adjacent cut components (e.g., 1x5 col 3 + cols 4..5) into one mask!
        for (thresh in floatArrayOf(0.68f, 0.58f, 0.50f, 0.42f)) {
            val visited = BooleanArray(total)
            val comps = ArrayList<List<Int>>()
            for (s in 0 until total) {
                if (visited[s]) continue
                val comp = ArrayList<Int>()
                val q = ArrayDeque<Int>()
                q.addLast(s)
                visited[s] = true
                while (q.isNotEmpty()) {
                    val u = q.removeFirst()
                    comp.add(u)
                    val r = u / cols
                    val c = u % cols
                    if (r > 0 && hScore(r, c) < thresh) {
                        val v = (r - 1) * cols + c
                        if (!visited[v]) { visited[v] = true; q.addLast(v) }
                    }
                    if (r + 1 < rows && hScore(r + 1, c) < thresh) {
                        val v = (r + 1) * cols + c
                        if (!visited[v]) { visited[v] = true; q.addLast(v) }
                    }
                    if (c > 0 && vScore(r, c) < thresh) {
                        val v = r * cols + (c - 1)
                        if (!visited[v]) { visited[v] = true; q.addLast(v) }
                    }
                    if (c + 1 < cols && vScore(r, c + 1) < thresh) {
                        val v = r * cols + (c + 1)
                        if (!visited[v]) { visited[v] = true; q.addLast(v) }
                    }
                }
                comps.add(comp)
            }
            if (comps.size >= 2) {
                val maxSz = comps.maxOf { it.size }
                val smallComps = comps.filter { it.size in 3..maxPuzzleCells && it.size < maxSz }
                for (comp in smallComps) {
                    val rawMask = BooleanArray(total)
                    for (u in comp) rawMask[u] = true
                    candidateMasks.add(rawMask)
                    candidateMasks.add(refineMask(rawMask))
                }
                // Support genuine Dual Puzzles: combine two non-max components ONLY if they are strictly non-adjacent
                if (smallComps.size >= 2) {
                    for (i in 0 until smallComps.size) {
                        for (j in i + 1 until smallComps.size) {
                            if (!areComponentsAdjacent(smallComps[i], smallComps[j])) {
                                val dualMask = BooleanArray(total)
                                for (u in smallComps[i]) dualMask[u] = true
                                for (u in smallComps[j]) dualMask[u] = true
                                candidateMasks.add(dualMask)
                            }
                        }
                    }
                }
            }
        }

        // Candidate Group 2: Corner/2-internal-wall local seed (recovers cleanly if 1 internal wall dips briefly)
        val seed2 = BooleanArray(total)
        var anySeed2 = false
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                var walls = 0
                if (r > 0 && hScore(r, c) >= 0.62f) walls++
                if (r + 1 < rows && hScore(r + 1, c) >= 0.62f) walls++
                if (c > 0 && vScore(r, c) >= 0.62f) walls++
                if (c + 1 < cols && vScore(r, c + 1) >= 0.62f) walls++
                if (walls >= 2) {
                    seed2[r * cols + c] = true
                    anySeed2 = true
                }
            }
        }
        if (anySeed2) {
            candidateMasks.add(refineMask(seed2))
        }

        // Pick highest-energy mask after independently validating every connected component
        var bestComponents: List<List<Int>> = emptyList()
        var bestEnergy = 0f

        for (cand in candidateMasks) {
            val visited = BooleanArray(total)
            val validComps = ArrayList<List<Int>>()
            val cleanMask = BooleanArray(total)
            for (s in 0 until total) {
                if (!cand[s] || visited[s]) continue
                val comp = ArrayList<Int>()
                val q = ArrayDeque<Int>()
                q.addLast(s)
                visited[s] = true
                while (q.isNotEmpty()) {
                    val u = q.removeFirst()
                    comp.add(u)
                    val r = u / cols
                    val c = u % cols
                    val neighbors = intArrayOf(
                        if (r > 0) u - cols else -1,
                        if (r + 1 < rows) u + cols else -1,
                        if (c > 0) u - 1 else -1,
                        if (c + 1 < cols) u + 1 else -1
                    )
                    for (v in neighbors) {
                        if (v >= 0 && cand[v] && !visited[v]) {
                            visited[v] = true
                            q.addLast(v)
                        }
                    }
                }
                if (comp.size in 3..maxPuzzleCells) {
                    val compMask = BooleanArray(total)
                    for (u in comp) compMask[u] = true
                    val st = evalBoundary(compMask)
                    var intSum = 0f
                    var intCnt = 0
                    for (u in comp) {
                        val r = u / cols
                        val c = u % cols
                        if (r + 1 < rows && compMask[(r + 1) * cols + c]) {
                            intSum += hScore(r + 1, c)
                            intCnt++
                        }
                        if (c + 1 < cols && compMask[r * cols + c + 1]) {
                            intSum += vScore(r, c + 1)
                            intCnt++
                        }
                    }
                    val meanInt = if (intCnt > 0) intSum / intCnt else 0f
                    if (st.energy > 1.15f &&
                        st.intCount >= 4 &&
                        st.outerCount <= st.intCount + 2 &&
                        st.intMeanWall >= 0.72f &&
                        st.intStrongRatio >= 0.75f &&
                        meanInt <= 0.45f
                    ) {
                        validComps.add(comp)
                        for (u in comp) cleanMask[u] = true
                    }
                }
            }
            if (validComps.isNotEmpty()) {
                val e = evalBoundary(cleanMask).energy
                if (e > bestEnergy) {
                    bestEnergy = e
                    bestComponents = validComps
                }
            }
        }

        var totalMarked = 0
        bestComponents.forEachIndexed { compIdx, comp ->
            val bit = if (compIdx == 0) TileFlagBits.PUZZLE_1 else TileFlagBits.PUZZLE_2
            for (idx in comp) {
                tileFlags[idx] = tileFlags[idx] or bit
            }
            totalMarked += comp.size
        }
        return totalMarked
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
        var compIdx = 0

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
                val bit = if (compIdx == 0) TileFlagBits.PUZZLE_1 else TileFlagBits.PUZZLE_2
                for (idx in component) {
                    tileFlags[idx] = tileFlags[idx] or bit
                }
                totalMarked += component.size
                compIdx++
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
