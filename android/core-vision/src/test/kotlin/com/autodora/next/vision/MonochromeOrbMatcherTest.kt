package com.autodora.next.vision

import com.autodora.next.model.BoardGeometry
import com.autodora.next.model.BoardState
import com.autodora.next.model.BoardVisualHints
import com.autodora.next.model.CellState
import com.autodora.next.model.Element
import com.autodora.next.model.HazardBits
import com.autodora.next.model.Variant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonochromeOrbMatcherTest {

    @Test
    fun `isBoardMonochrome distinguishes greyscale board from colored board and dark modal`() {
        val geom = BoardGeometry(leftPx = 0f, topPx = 0f, rightPx = 300f, bottomPx = 250f, cols = 6, rows = 5)

        val monoFrame = object : VisionFrame {
            override val width: Int = 300
            override val height: Int = 250
            override fun getArgb(x: Int, y: Int): Int = 0xFF828282.toInt()
        }
        assertTrue(MonochromeOrbMatcher.isBoardMonochrome(monoFrame, geom))

        val coloredFrame = object : VisionFrame {
            override val width: Int = 300
            override val height: Int = 250
            override fun getArgb(x: Int, y: Int): Int = 0xFFE53935.toInt()
        }
        assertFalse(MonochromeOrbMatcher.isBoardMonochrome(coloredFrame, geom))

        val pitchBlackModal = object : VisionFrame {
            override val width: Int = 300
            override val height: Int = 250
            override fun getArgb(x: Int, y: Int): Int = 0xFF101014.toInt()
        }
        assertFalse(MonochromeOrbMatcher.isBoardMonochrome(pitchBlackModal, geom))
    }

    @Test
    fun `HybridFusionPolicy fuses CNN and CV monochrome probabilities to rescue ambiguous cell`() {
        val total = 30
        val cvBoard = BoardState(
            cols = 6,
            rows = 5,
            cells = List(total) { CellState(Element.HEART, Variant.NORMAL, HazardBits.BLACK_WHITE, confidence = 0.72f) }
        )
        // CV monochrome matcher strongly favors HEART (index 5 = 0.88) while CNN slightly favored LIGHT (index 3 = 0.56 vs HEART = 0.40)
        val cvMonoProbs = List(total) { floatArrayOf(0.02f, 0.02f, 0.02f, 0.04f, 0.02f, 0.88f) }
        val cvRes = RecognitionResult(
            board = cvBoard,
            averageConfidence = 0.72f,
            lowConfidenceIndices = emptyList(),
            puzzleCellsDetected = 0,
            engineName = "CV",
            elapsedMs = 2.0,
            monochromeCellProbs = cvMonoProbs
        )
        val preds = List(total) {
            CellModelPrediction(
                isRune = 0.99f,
                elementProbs = floatArrayOf(0.01f, 0.01f, 0.01f, 0.56f, 0.01f, 0.40f, 0f, 0f, 0f),
                variantProbs = floatArrayOf(0.95f, 0.02f, 0.02f, 0.01f),
                raceProbs = FloatArray(8).apply { this[0] = 0.99f },
                hazardProbs = FloatArray(OrbNetSchema.NUM_HAZARDS).apply {
                    this[OrbNetSchema.IDX_HAZ_BLACK_WHITE] = 0.98f
                },
                tileOverlayProbs = FloatArray(OrbNetSchema.NUM_TILE_OVERLAYS)
            )
        }

        val fused = HybridFusionPolicy.fuse(
            cvResult = cvRes,
            predictions = preds,
            visualHints = BoardVisualHints(hasMonochrome = true)
        )
        assertEquals(Element.HEART, fused.board.cells[0].element)
        assertEquals(30, fused.blackWhiteOrbsDetected)
    }
}
