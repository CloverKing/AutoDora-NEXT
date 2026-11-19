package com.autodora.next.vision

import com.autodora.next.model.BoardGeometry
import com.autodora.next.model.Element
import com.autodora.next.model.TileFlagBits
import com.autodora.next.model.Variant
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class CvBoardRecognizerTest {

    private class SyntheticBoardFrame(override val width: Int, override val height: Int) : VisionFrame {
        val pixels = IntArray(width * height) { 0xFF181412.toInt() }
        override fun getArgb(x: Int, y: Int): Int = pixels[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]

        fun drawCell(
            x0: Int, y0: Int, size: Int,
            color: Int,
            enchanted: Boolean = false,
            puzzleGoldBorder: Boolean = false
        ) {
            val cx = x0 + size * 0.5f
            val cy = y0 + size * 0.5f
            val r = size * 0.40f
            for (y in y0 until y0 + size) {
                for (x in x0 until x0 + size) {
                    val borderDist = minOf(x - x0, x0 + size - 1 - x, y - y0, y0 + size - 1 - y)
                    if (puzzleGoldBorder && borderDist <= 3) {
                        pixels[y * width + x] = 0xFFFFD220.toInt()
                        continue
                    }
                    val d = hypot((x - cx).toDouble(), (y - cy).toDouble()).toFloat()
                    if (d <= r) {
                        // Draw bright white specular star sparkle for enchanted orbs
                        val isStar = enchanted && (d < size * 0.14f || (x > cx && y < cy && d < size * 0.28f && (x - y) % 2 == 0))
                        pixels[y * width + x] = if (isStar) 0xFFFCFCFC.toInt() else color
                    }
                }
            }
        }
    }

    @Test
    fun `recognizes all 6 elements, enchanted stars, and golden puzzle borders`() {
        val cell = 80
        val cols = 6
        val rows = 5
        val frame = SyntheticBoardFrame(cols * cell, rows * cell)
        val palette = mapOf(
            Element.WATER to 0xFF1E9CFF.toInt(),
            Element.FIRE  to 0xFFFF3020.toInt(),
            Element.WOOD  to 0xFF2EC840.toInt(),
            Element.LIGHT to 0xFFFFDF20.toInt(),
            Element.DARK  to 0xFF8C32E6.toInt(),
            Element.HEART to 0xFFFF5CA8.toInt()
        )
        val expected = ArrayList<Element>(30)
        val puzzleSet = setOf(7, 8, 9, 13, 19) // 5-cell L puzzle

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val idx = r * cols + c
                val el = Element.MATCHABLE[idx % 6]
                expected.add(el)
                frame.drawCell(
                    x0 = c * cell,
                    y0 = r * cell,
                    size = cell,
                    color = palette.getValue(el),
                    enchanted = (idx == 1),
                    puzzleGoldBorder = (idx in puzzleSet)
                )
            }
        }

        val geom = BoardGeometry(0f, 0f, (cols * cell).toFloat(), (rows * cell).toFloat(), cols, rows)
        val recognizer = CvBoardRecognizer()
        val result = recognizer.recognizeFrames(listOf(frame), geom)

        for (i in 0 until 30) {
            assertEquals("Cell $i mismatch", expected[i], result.board.cells[i].element)
        }
        assertEquals(Variant.ENCHANTED, result.board.cells[1].variant)
        assertEquals(5, result.puzzleCellsDetected)
        for (p in puzzleSet) {
            assertTrue("Cell $p should have PUZZLE_1 flag", (result.board.tileFlags[p] and TileFlagBits.PUZZLE_1) != 0)
        }
        assertTrue(result.averageConfidence > 0.85f)
    }
}
