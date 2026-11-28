package com.autodora.next.vision

import com.autodora.next.model.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class HybridBoardRecognizerTest {

    private fun buildPredictionRow(
        isRune: Float = 0.99f,
        element: Element = Element.WATER,
        elProb: Float = 0.96f,
        variant: Variant = Variant.NORMAL,
        varProb: Float = 0.92f,
        race: RaceType = RaceType.NONE,
        raceProb: Float = 0.90f,
        hazards: Map<Int, Float> = emptyMap(),
        overlays: Map<Int, Float> = emptyMap()
    ): CellModelPrediction {
        val raw = FloatArray(OrbNetSchema.OUTPUT_DIM)
        raw[OrbNetSchema.OFFSET_IS_RUNE] = isRune
        val elRem = (1f - elProb).coerceAtLeast(0f) / (OrbNetSchema.NUM_ELEMENTS - 1)
        for (i in 0 until OrbNetSchema.NUM_ELEMENTS) {
            raw[OrbNetSchema.OFFSET_ELEMENT + i] = if (i == element.code) elProb else elRem
        }
        val varRem = (1f - varProb).coerceAtLeast(0f) / (OrbNetSchema.NUM_VARIANTS - 1)
        for (i in 0 until OrbNetSchema.NUM_VARIANTS) {
            raw[OrbNetSchema.OFFSET_VARIANT + i] = if (i == variant.code) varProb else varRem
        }
        val rcRem = (1f - raceProb).coerceAtLeast(0f) / (OrbNetSchema.NUM_RACES - 1)
        for (i in 0 until OrbNetSchema.NUM_RACES) {
            raw[OrbNetSchema.OFFSET_RACE + i] = if (i == race.code) raceProb else rcRem
        }
        hazards.forEach { (idx, p) -> raw[OrbNetSchema.OFFSET_HAZARDS + idx] = p }
        overlays.forEach { (idx, p) -> raw[OrbNetSchema.OFFSET_TILE_OVERLAY + idx] = p }
        return CellModelPrediction.fromOutputRow(raw)
    }

    @Test
    fun `OrbNetSchema aligns 1-to-1 with 47-dim output layout and model enums`() {
        assertEquals(47, OrbNetSchema.OUTPUT_DIM)
        assertEquals(17, OrbNetSchema.HAZARD_NAMES.size)
        assertEquals(17, OrbNetSchema.HAZARD_BITS_MAP.size)
        assertEquals(8, OrbNetSchema.TILE_OVERLAY_NAMES.size)
        assertEquals(8, OrbNetSchema.TILE_OVERLAY_BITS_MAP.size)
        assertEquals(9, OrbNetSchema.NUM_ELEMENTS)
        assertEquals(4, OrbNetSchema.NUM_VARIANTS)
        assertEquals(8, OrbNetSchema.NUM_RACES)

        val pred = buildPredictionRow(
            element = Element.HEART,
            variant = Variant.WEAKENED,
            race = RaceType.MACHINA,
            hazards = mapOf(12 to 0.91f, 16 to 0.87f),
            overlays = mapOf(OrbNetSchema.IDX_OV_SCORCHING to 0.89f)
        )
        assertEquals(Element.HEART to 0.96f, pred.bestElement())
        assertEquals(Variant.WEAKENED to 0.92f, pred.bestVariant())
        assertEquals(RaceType.MACHINA to 0.90f, pred.bestRace())
        assertEquals(0.91f, pred.hazardProbs[12], 1e-5f)
        assertEquals(0.87f, pred.hazardProbs[16], 1e-5f)
        assertEquals(0.89f, pred.tileOverlayProbs[OrbNetSchema.IDX_OV_SCORCHING], 1e-5f)
    }

    @Test
    fun `HybridFusionPolicy upgrades monochrome weakened C-class hazards and 7-class race while preserving CV board flags`() {
        val cols = 6
        val rows = 5
        val total = cols * rows
        val cvCells = MutableList(total) { i ->
            when (i) {
                // Cell 0: CV misclassified monochrome Dark orb as HIDDEN + BLACK_WHITE
                0 -> CellState(Element.HIDDEN, Variant.NORMAL, HazardBits.BLACK_WHITE, confidence = 0.22f)
                // Cell 1: CV saw Heart normal without knowing it is Weakened or Dragon Race
                1 -> CellState(Element.HEART, Variant.NORMAL, HazardBits.NONE, confidence = 0.86f)
                // Cell 2: CV saw Fire normal without detecting Coagulated / Blood-Thorn
                2 -> CellState(Element.FIRE, Variant.NORMAL, HazardBits.NONE, confidence = 0.84f)
                // Cell 3: CV already high-conf Light normal orb -> low-conf disagreeing CNN must NOT override
                3 -> CellState(Element.LIGHT, Variant.ENCHANTED, HazardBits.NONE, confidence = 0.95f)
                // Cell 4: CV Storm cell -> must stay Storm
                4 -> CellState(Element.UNKNOWN, Variant.NORMAL, HazardBits.NONE, confidence = 0.88f)
                else -> CellState(Element.MATCHABLE[i % 6], Variant.NORMAL, HazardBits.NONE, confidence = 0.92f)
            }
        }
        val cvFlags = MutableList(total) { i ->
            when (i) {
                4 -> TileFlagBits.STORM
                7, 8, 9 -> TileFlagBits.PUZZLE_1
                10 -> TileFlagBits.START_ALLOWED
                12 -> TileFlagBits.END_REQUIRED
                else -> TileFlagBits.NONE
            }
        }
        val cvResult = RecognitionResult(
            board = BoardState(
                cols = cols,
                rows = rows,
                cells = cvCells,
                tileFlags = cvFlags,
                startingBuoyCol = 2,
                startingBuoyElement = Element.WOOD
            ),
            averageConfidence = 0.85f,
            lowConfidenceIndices = listOf(0),
            puzzleCellsDetected = 3,
            engineName = "CV-Rule-v2.7",
            elapsedMs = 4.2,
            questionOrbsDetected = 1,
            stormCellsDetected = 1,
            trafficLightStartCount = 1,
            trafficLightEndCount = 1,
            startingBuoyCol = 2,
            startingBuoyElement = Element.WOOD,
            isDimmedBoard = true
        )

        val preds = List(total) { i ->
            when (i) {
                // Recover Dark element + keep BLACK_WHITE
                0 -> buildPredictionRow(element = Element.DARK, elProb = 0.78f, hazards = mapOf(OrbNetSchema.IDX_HAZ_BLACK_WHITE to 0.94f))
                // Weakened Heart + Dragon Race
                1 -> buildPredictionRow(element = Element.HEART, elProb = 0.91f, variant = Variant.WEAKENED, varProb = 0.89f, race = RaceType.DRAGON, raceProb = 0.86f)
                // Coagulated + Blood-Thorn + Scorching Tile + Magic Orb
                2 -> buildPredictionRow(
                    element = Element.FIRE,
                    elProb = 0.88f,
                    hazards = mapOf(12 to 0.90f, 15 to 0.84f, 16 to 0.92f),
                    overlays = mapOf(OrbNetSchema.IDX_OV_SCORCHING to 0.88f)
                )
                // Low-conf disagreement on high-conf CV cell -> CV must win!
                3 -> buildPredictionRow(element = Element.WATER, elProb = 0.65f, race = RaceType.NONE, raceProb = 0.90f)
                // Cell 5: Black Hole overlay + God Race
                5 -> buildPredictionRow(element = Element.WOOD, elProb = 0.93f, race = RaceType.GOD, raceProb = 0.88f, overlays = mapOf(OrbNetSchema.IDX_OV_BLACK_HOLE to 0.86f))
                else -> buildPredictionRow(element = Element.MATCHABLE[i % 6], elProb = 0.93f)
            }
        }

        val fused = HybridFusionPolicy.fuse(cvResult, preds, totalElapsedMs = 11.5)
        assertEquals("Hybrid-LiteRT+CV-v1", fused.engineName)
        assertEquals(11.5, fused.elapsedMs, 1e-4)

        // Preserved CV board geometry flags
        assertEquals(2, fused.startingBuoyCol)
        assertEquals(Element.WOOD, fused.startingBuoyElement)
        assertTrue(fused.isDimmedBoard)
        assertEquals(3, fused.puzzleCellsDetected)
        assertTrue((fused.board.tileFlags[7] and TileFlagBits.PUZZLE_1) != 0)
        assertTrue((fused.board.tileFlags[10] and TileFlagBits.START_ALLOWED) != 0)
        assertTrue((fused.board.tileFlags[12] and TileFlagBits.END_REQUIRED) != 0)
        assertTrue((fused.board.tileFlags[4] and TileFlagBits.STORM) != 0)

        // Cell 0 recovered DARK from monochrome
        assertEquals(Element.DARK, fused.board.cells[0].element)
        assertTrue((fused.board.cells[0].hazards and HazardBits.BLACK_WHITE) != 0)
        assertEquals(0, fused.questionOrbsDetected)

        // Cell 1 got WEAKENED + RaceType.DRAGON
        assertEquals(Element.HEART, fused.board.cells[1].element)
        assertEquals(Variant.WEAKENED, fused.board.cells[1].variant)
        assertEquals(RaceType.DRAGON, fused.board.cells[1].race)

        // Cell 2 got COAGULATED + MAGIC_ORB + BLOOD_THORN + SCORCHING_TILE
        val h2 = fused.board.cells[2].hazards
        assertTrue((h2 and HazardBits.COAGULATED) != 0)
        assertTrue((h2 and HazardBits.MAGIC_ORB) != 0)
        assertTrue((h2 and HazardBits.BLOOD_THORN) != 0)
        assertTrue((fused.board.tileFlags[2] and TileFlagBits.SCORCHING_TILE) != 0)

        // Cell 3 kept high-conf CV LIGHT + ENCHANTED
        assertEquals(Element.LIGHT, fused.board.cells[3].element)
        assertEquals(Variant.ENCHANTED, fused.board.cells[3].variant)

        // Cell 5 got GOD race + BLACK_HOLE
        assertEquals(RaceType.GOD, fused.board.cells[5].race)
        assertTrue((fused.board.tileFlags[5] and TileFlagBits.BLACK_HOLE) != 0)
        assertEquals(2, fused.raceBadgesDetected)
    }

    @Test
    fun `OrbBatchPreprocessor supports 6x5 and 6x8 dynamic batching with yOffset and dimmed brightness gain`() {
        // 6x8 Weijian board band frame starting at yOffset = 600
        val cols = 6
        val rows = 8
        val cellPx = 80
        val yOffset = 600
        val bandH = rows * cellPx
        val fullW = cols * cellPx
        val fullH = yOffset + bandH
        val geom = BoardGeometry(0f, yOffset.toFloat(), fullW.toFloat(), fullH.toFloat(), cols, rows, isWeijianExpanded = true)

        val frame = object : VisionFrame {
            override val width: Int = fullW
            override val height: Int = fullH
            override val minAccessibleY: Int = yOffset
            override fun getArgb(x: Int, y: Int): Int {
                val c = (x / cellPx).coerceIn(0, cols - 1)
                val r = ((y - yOffset) / cellPx).coerceIn(0, rows - 1)
                // Darkened RGB values (e.g. ~50% scrim: R=100, G=50, B=20)
                return if (r == 0 && c == 0) 0xFF643214.toInt() else 0xFF145078.toInt()
            }
        }

        val initial = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        val out = OrbBatchPreprocessor.fillInputBuffer(frame, geom, brightnessGain = 2.0f, target = initial)

        val expectedFloats = 48 * 64 * 64 * 3
        assertEquals(expectedFloats * 4, out.remaining())

        // Cell (0,0) center pixel should be normalized and gained x2.0 -> (200/255, 100/255, 40/255)
        val centerOffsetFloats = ((32 * 64) + 32) * 3
        val r0 = out.getFloat(centerOffsetFloats * 4)
        val g0 = out.getFloat((centerOffsetFloats + 1) * 4)
        val b0 = out.getFloat((centerOffsetFloats + 2) * 4)
        assertEquals(200f / 255f, r0, 0.02f)
        assertEquals(100f / 255f, g0, 0.02f)
        assertEquals(40f / 255f, b0, 0.02f)

        // Cell (7,5) center pixel -> (40/255, 160/255, 240/255)
        val cell47Offset = (47 * 64 * 64 + 32 * 64 + 32) * 3
        val r47 = out.getFloat(cell47Offset * 4)
        val g47 = out.getFloat((cell47Offset + 1) * 4)
        val b47 = out.getFloat((cell47Offset + 2) * 4)
        assertEquals(40f / 255f, r47, 0.02f)
        assertEquals(160f / 255f, g47, 0.02f)
        assertEquals(240f / 255f, b47, 0.02f)
    }

    @Test
    fun `visualHints hasMonochrome unlocks AI monochrome recovery even without CV BLACK_WHITE bit`() {
        val total = 30
        val cvCells = List(total) { i ->
            CellState(
                element = if (i == 0) Element.WOOD else Element.MATCHABLE[i % 6],
                variant = Variant.NORMAL,
                hazards = HazardBits.NONE,
                confidence = 0.85f
            )
        }
        val cvResult = RecognitionResult(
            board = BoardState(cols = 6, rows = 5, cells = cvCells, tileFlags = List(total) { TileFlagBits.NONE }),
            averageConfidence = 0.85f,
            lowConfidenceIndices = emptyList(),
            puzzleCellsDetected = 0,
            engineName = "CV-Rule-v2.7",
            elapsedMs = 3.0
        )
        val preds = List(total) { i ->
            if (i == 0) {
                buildPredictionRow(element = Element.LIGHT, elProb = 0.62f) // below normal override 0.88, above mono recover 0.56!
            } else {
                buildPredictionRow(element = Element.MATCHABLE[i % 6], elProb = 0.92f)
            }
        }

        val withoutHint = HybridFusionPolicy.fuse(cvResult, preds)
        assertEquals("Without monochrome hint, CV cell must remain WOOD", Element.WOOD, withoutHint.board.cells[0].element)

        val withMonoHint = HybridFusionPolicy.fuse(cvResult, preds, visualHints = BoardVisualHints(hasMonochrome = true))
        assertEquals("With hasMonochrome visual hint, AI must recover LIGHT at 0.62", Element.LIGHT, withMonoHint.board.cells[0].element)
    }
}
