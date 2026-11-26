package com.autodora.next.vision

import com.autodora.next.model.*
import org.junit.Assert.*
import org.junit.Test

class BoardCaptureQualityGateTest {

    private class ArrayVisionFrame(
        override val width: Int,
        override val height: Int,
        private val fillArgb: Int = 0xFF1A1412.toInt()
    ) : VisionFrame {
        private val pixels = IntArray(width * height) { fillArgb }

        fun fillRect(x0: Int, y0: Int, x1: Int, y1: Int, argb: Int) {
            val ax0 = x0.coerceIn(0, width)
            val ay0 = y0.coerceIn(0, height)
            val ax1 = x1.coerceIn(ax0, width)
            val ay1 = y1.coerceIn(ay0, height)
            for (y in ay0 until ay1) {
                val off = y * width
                for (x in ax0 until ax1) {
                    pixels[off + x] = argb
                }
            }
        }

        fun paintOrb(cx: Int, cy: Int, r: Int, argb: Int) {
            val r2 = r * r
            for (y in (cy - r)..(cy + r)) {
                if (y !in 0 until height) continue
                for (x in (cx - r)..(cx + r)) {
                    if (x !in 0 until width) continue
                    val dx = x - cx
                    val dy = y - cy
                    if (dx * dx + dy * dy <= r2) {
                        pixels[y * width + x] = argb
                    }
                }
            }
        }

        override fun getArgb(x: Int, y: Int): Int =
            pixels[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]
    }

    private fun makeCleanBoardAndFrame(
        brightRgb: Int = 0xFFE63030.toInt()
    ): Pair<ArrayVisionFrame, Pair<BoardGeometry, RecognitionResult>> {
        val geom = BoardGeometry(0f, 100f, 360f, 400f, 6, 5, false)
        val frame = ArrayVisionFrame(360, 420, 0xFF16100E.toInt())
        val cells = List(30) { idx ->
            val r = idx / 6
            val c = idx % 6
            val cx = geom.cellCenterX(c).toInt()
            val cy = geom.cellCenterY(r).toInt()
            val col = when (idx % 3) {
                0 -> brightRgb
                1 -> 0xFF2896F0.toInt()
                else -> 0xFF2CD050.toInt()
            }
            frame.paintOrb(cx, cy, 22, col)
            CellState(element = listOf(Element.FIRE, Element.WATER, Element.WOOD)[idx % 3])
        }
        val rec = RecognitionResult(
            board = BoardState(6, 5, cells, List(30) { TileFlagBits.NONE }),
            averageConfidence = 0.96f,
            lowConfidenceIndices = emptyList(),
            puzzleCellsDetected = 0,
            engineName = "Test",
            elapsedMs = 1.0
        )
        return frame to (geom to rec)
    }

    @Test
    fun `accepts clean static playable board and rejects friend select, mid-clear holes, combo glow, and dimmed turn-end`() {
        val (cleanFrame, pair) = makeCleanBoardAndFrame()
        val (geom, rec) = pair

        val ok = BoardCaptureQualityGate.evaluateBoardCaptureQuality(cleanFrame, geom, rec, hasHpBar = true)
        assertTrue(ok.isCleanStaticPlayable)
        assertEquals("OK", ok.rejectReason)

        // 1. Friend select list (no HP bar)
        val noHp = BoardCaptureQualityGate.evaluateBoardCaptureQuality(cleanFrame, geom, rec, hasHpBar = false)
        assertFalse(noHp.isCleanStaticPlayable)
        assertEquals("NO_HP_BAR", noHp.rejectReason)

        // 2. Dimmed end-of-turn / attack phase
        val (dimFrame, pair2) = makeCleanBoardAndFrame(brightRgb = 0xFF702020.toInt())
        for (r in 0 until 5) {
            for (c in 0 until 6) {
                dimFrame.paintOrb(pair2.first.cellCenterX(c).toInt(), pair2.first.cellCenterY(r).toInt(), 22, 0xFF5A3030.toInt())
            }
        }
        val dimEval = BoardCaptureQualityGate.evaluateBoardCaptureQuality(dimFrame, pair2.first, pair2.second, hasHpBar = true)
        assertFalse(dimEval.isCleanStaticPlayable)
        assertEquals("DIMMED_END_OF_TURN", dimEval.rejectReason)

        // 3. Mid-clear empty checkerboard holes (even if some cells are bright)
        val (holeFrame, pair3) = makeCleanBoardAndFrame()
        for (idx in 0 until 6) {
            val r = idx / 6
            val c = idx % 6
            val cx = pair3.first.cellCenterX(c).toInt()
            val cy = pair3.first.cellCenterY(r).toInt()
            holeFrame.fillRect(cx - 28, cy - 28, cx + 28, cy + 28, 0xFF1A1412.toInt())
        }
        val holeEval = BoardCaptureQualityGate.evaluateBoardCaptureQuality(holeFrame, pair3.first, pair3.second, hasHpBar = true)
        assertFalse(holeEval.isCleanStaticPlayable)
        assertEquals("MID_CLEAR_EMPTY_HOLES", holeEval.rejectReason)

        // 4. Bottom-right Combo!! explosion white-gold bloom
        val (glowFrame, pair4) = makeCleanBoardAndFrame()
        glowFrame.fillRect(190, 290, 355, 395, 0xFFFDF8D8.toInt())
        val glowEval = BoardCaptureQualityGate.evaluateBoardCaptureQuality(glowFrame, pair4.first, pair4.second, hasHpBar = true)
        assertFalse(glowEval.isCleanStaticPlayable)
        assertEquals("COMBO_EXPLOSION_GLOW", glowEval.rejectReason)
    }

    @Test
    fun `BoardTurnStabilityTracker captures once per settled turn and resets after movement`() {
        val tracker = BoardTurnStabilityTracker()
        val h1 = 0x1122334455667788L

        assertEquals(StabilityDecision.WAIT_FIRST_FRAME, tracker.onStableFrameCheck(h1, null))
        assertEquals(StabilityDecision.CAPTURE_DUAL_PAIR, tracker.onStableFrameCheck(h1, 2.5f))
        assertEquals(StabilityDecision.ALREADY_CAPTURED_THIS_TURN, tracker.onStableFrameCheck(h1, 1.8f))

        // Large cascade / swipe movement resets turn gate
        val h2 = h1.inv()
        assertEquals(StabilityDecision.BOARD_CHANGED_RESET, tracker.onStableFrameCheck(h2, 42.0f))
        assertEquals(StabilityDecision.CAPTURE_DUAL_PAIR, tracker.onStableFrameCheck(h2, 3.0f))
        assertEquals(StabilityDecision.ALREADY_CAPTURED_THIS_TURN, tracker.onStableFrameCheck(h2, 2.0f))
    }
}
