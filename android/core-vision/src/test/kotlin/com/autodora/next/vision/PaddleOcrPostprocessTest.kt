package com.autodora.next.vision

import com.autodora.next.model.*
import org.junit.Assert.*
import org.junit.Test

class PaddleOcrPostprocessTest {

    private class ArrayVisionFrame(
        override val width: Int,
        override val height: Int,
        private val pixels: IntArray
    ) : VisionFrame {
        override fun getArgb(x: Int, y: Int): Int =
            pixels[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]
    }

    @Test
    fun `extractBoxesFromProbMap finds connected boxes, unclips, and sorts in reading order`() {
        val w = 80
        val h = 40
        val prob = FloatArray(w * h)
        // Box 1 (row 0, right side): x=[40..60], y=[4..10]
        for (y in 4..10) for (x in 40..60) prob[y * w + x] = 0.85f
        // Box 2 (row 0, left side): x=[6..26], y=[5..11]
        for (y in 5..11) for (x in 6..26) prob[y * w + x] = 0.88f
        // Box 3 (row 1): x=[8..50], y=[24..31]
        for (y in 24..31) for (x in 8..50) prob[y * w + x] = 0.82f
        // Low-score box below boxThresh: x=[62..74], y=[24..31] -> should be filtered out
        for (y in 24..31) for (x in 62..74) prob[y * w + x] = 0.35f

        val boxes = PaddleOcrPostprocess.extractBoxesFromProbMap(
            prob = prob,
            mapW = w,
            mapH = h,
            origW = 320,
            origH = 160,
            thresh = 0.30f,
            boxThresh = 0.50f,
            unclipRatio = 1.6f
        )

        assertEquals(3, boxes.size)
        // Row 0 sorted left-to-right: Box 2 before Box 1, then Box 3 on row 1
        assertTrue(boxes[0].left < boxes[1].left)
        assertTrue(boxes[2].top > boxes[0].bottom)
    }

    @Test
    fun `ctcGreedyDecode removes blanks and repeated adjacent indices cleanly`() {
        val vocab = listOf("blank", "首", "消", "6", "C", " ")
        // Sequence length 7: [blank, 首, 首, 消, blank, 6, C]
        val seqLen = 7
        val vocabSize = vocab.size
        val probs = FloatArray(seqLen * vocabSize) { 0.01f }
        val chosen = intArrayOf(0, 1, 1, 2, 0, 3, 4)
        for (t in 0 until seqLen) {
            probs[t * vocabSize + chosen[t]] = 0.94f
        }

        val (decoded, conf) = PaddleOcrPostprocess.ctcGreedyDecode(
            output = probs,
            seqLen = seqLen,
            vocabSize = vocabSize,
            vocab = vocab,
            blankIndex = 0
        )

        assertEquals("首消6C", decoded)
        assertTrue("Expected high average confidence, got $conf", conf > 0.90f)
    }

    @Test
    fun `sampleForegroundColorStats separates muted grey bypassed text from bright white or gold active text`() {
        val w = 120
        val h = 40
        // Dark popup background RGB(18, 22, 28) with muted grey text RGB(126, 128, 132) across 30% of pixels
        val greyPixels = IntArray(w * h) { 0xFF12161C.toInt() }
        for (y in 10 until 30) {
            for (x in 15 until 105 step 2) {
                greyPixels[y * w + x] = 0xFF7E8084.toInt()
            }
        }
        val greyStats = PaddleOcrPostprocess.sampleForegroundColorStats(ArrayVisionFrame(w, h, greyPixels), 0, 0, w, h)
        assertTrue("Grey muted text must be detected as bypassed: $greyStats", greyStats.isGreyBypassed)

        // Crisp bright white active text RGB(242, 245, 248)
        val whitePixels = IntArray(w * h) { 0xFF12161C.toInt() }
        for (y in 10 until 30) {
            for (x in 15 until 105 step 2) {
                whitePixels[y * w + x] = 0xFFF2F5F8.toInt()
            }
        }
        val whiteStats = PaddleOcrPostprocess.sampleForegroundColorStats(ArrayVisionFrame(w, h, whitePixels), 0, 0, w, h)
        assertFalse("Bright white active text must NOT be filtered as grey: $whiteStats", whiteStats.isGreyBypassed)

        // Vivid golden highlight text RGB(255, 200, 45)
        val goldPixels = IntArray(w * h) { 0xFF12161C.toInt() }
        for (y in 10 until 30) {
            for (x in 15 until 105 step 2) {
                goldPixels[y * w + x] = 0xFFFFC82D.toInt()
            }
        }
        val goldStats = PaddleOcrPostprocess.sampleForegroundColorStats(ArrayVisionFrame(w, h, goldPixels), 0, 0, w, h)
        assertFalse("Vivid golden text must NOT be filtered as grey: $goldStats", goldStats.isGreyBypassed)
    }

    @Test
    fun `BoardAutoPolicySummary summarizes active board hazards and terrains`() {
        val cells = MutableList(30) { CellState(element = Element.WATER, variant = Variant.NORMAL) }
        val flags = MutableList(30) { TileFlagBits.NONE }
        cells[0] = cells[0].copy(hazards = HazardBits.BOMB)
        cells[1] = cells[1].copy(hazards = HazardBits.BOMB or HazardBits.EVIL_ENERGY)
        cells[2] = cells[2].copy(hazards = HazardBits.PETRIFIED)
        flags[5] = TileFlagBits.BURNING_TILE
        flags[6] = TileFlagBits.BURNING_TILE
        flags[29] = TileFlagBits.BLACK_HOLE

        val board = BoardState(cols = 6, rows = 5, cells = cells, tileFlags = flags)
        val summary = BoardAutoPolicySummary.analyze(board).associate { it.code to it.count }

        assertEquals(2, summary["bomb"])
        assertEquals(1, summary["evil"])
        assertEquals(1, summary["petrified"])
        assertEquals(2, summary["burn"])
        assertEquals(1, summary["black_hole"])
        assertNull(summary["sticky"])
    }
}
