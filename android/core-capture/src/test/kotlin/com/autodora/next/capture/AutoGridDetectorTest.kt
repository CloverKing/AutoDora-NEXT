package com.autodora.next.capture

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class AutoGridDetectorTest {

    private class SyntheticFrame(override val width: Int, override val height: Int) : PixelFrame {
        val pixels = IntArray(width * height) { 0xFF1A1614.toInt() } // Dark battle background

        override fun getArgb(x: Int, y: Int): Int {
            val cx = x.coerceIn(0, width - 1)
            val cy = y.coerceIn(0, height - 1)
            return pixels[cy * width + cx]
        }

        fun drawOrbGrid(left: Int, top: Int, cellSize: Int, cols: Int, rows: Int) {
            val colors = intArrayOf(
                0xFF22A0FF.toInt(), // Water
                0xFFFF3A28.toInt(), // Fire
                0xFF34C844.toInt(), // Wood
                0xFFFFDA24.toInt(), // Light
                0xFF9C3CF0.toInt(), // Dark
                0xFFFF5CA8.toInt()  // Heart
            )
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val x0 = left + c * cellSize
                    val y0 = top + r * cellSize
                    val cx = x0 + cellSize * 0.5f
                    val cy = y0 + cellSize * 0.5f
                    val radius = cellSize * 0.40f
                    val tileBg = if ((r + c) % 2 == 0) 0xFF28221E.toInt() else 0xFF1F1A16.toInt()
                    val orbColor = colors[(r * 3 + c) % colors.size]

                    for (y in y0 until (y0 + cellSize).coerceAtMost(height)) {
                        for (x in x0 until (x0 + cellSize).coerceAtMost(width)) {
                            if (x < 0 || y < 0) continue
                            val d = hypot((x - cx).toDouble(), (y - cy).toDouble()).toFloat()
                            pixels[y * width + x] = if (d <= radius) orbColor else tileBg
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `detects standard 6x5 board on Xiaomi 15 resolution without false weijian`() {
        val frame = SyntheticFrame(width = 600, height = 1335) // 1/2 scale Xiaomi 15 (1200x2670)
        val calib = BoardCalibration(leftRatio = 0.01f, rightRatio = 0.99f, bottomRatio = 0.968f)
        val base = calib.toBase6x5Geometry(frame.width, frame.height)
        frame.drawOrbGrid(base.leftPx.toInt(), base.topPx.toInt(), base.cellWidth.toInt(), 6, 5)

        val detected = AutoGridDetector.detect(frame, calib)
        assertEquals(6, detected.cols)
        assertEquals(5, detected.rows)
        assertFalse(detected.isWeijianExpanded)
    }

    @Test
    fun `automatically detects 6x8 weijian expansion directly above 6x5 base`() {
        val frame = SyntheticFrame(width = 600, height = 1335)
        val calib = BoardCalibration(leftRatio = 0.01f, rightRatio = 0.99f, bottomRatio = 0.968f)
        val base = calib.toBase6x5Geometry(frame.width, frame.height)
        val cell = base.cellWidth.toInt()
        // Draw 8 rows ending at base.bottomPx (3 extra weijian rows directly above base 6x5!)
        val top8 = (base.bottomPx - cell * 8).toInt()
        frame.drawOrbGrid(base.leftPx.toInt(), top8, cell, 6, 8)

        val detected = AutoGridDetector.detect(frame, calib)
        assertEquals(6, detected.cols)
        assertEquals(8, detected.rows)
        assertTrue(detected.isWeijianExpanded)
    }

    @Test
    fun `detectArbitraryGrid finds 7x6 board automatically`() {
        val frame = SyntheticFrame(width = 560, height = 1100)
        val cell = 80 // 7 * 80 = 560 exact width
        frame.drawOrbGrid(0, 1100 - 6 * cell - 20, cell, 7, 6)

        val detected = AutoGridDetector.detectArbitraryGrid(frame)
        assertNotNull(detected)
        assertEquals(7, detected!!.cols)
        assertEquals(6, detected.rows)
    }
}
