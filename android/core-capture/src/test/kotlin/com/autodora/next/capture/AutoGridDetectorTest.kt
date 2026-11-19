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

    @Test
    fun `auto 9-to-16 bottom ratio matches real Xiaomi 15 1200x2670 ToS battle board within 2px`() {
        val calib = BoardCalibration() // Default AUTO_BOTTOM_RATIO
        val geom = calib.toBase6x5Geometry(1200, 2670)
        assertEquals(0f, geom.leftPx, 0.5f)
        assertEquals(1200f, geom.rightPx, 0.5f)
        assertEquals(2410f, geom.bottomPx, 2.5f)
        assertEquals(1410f, geom.topPx, 2.5f)
        assertEquals(200f, geom.cellWidth, 0.5f)
    }

    @Test
    fun `green HP bar barrier directly above row 0 locks 6x5 and prevents false 6x6 weijian`() {
        val frame = SyntheticFrame(width = 600, height = 1335)
        val calib = BoardCalibration() // 1/2 scale Xiaomi 15: top=705, bottom=1205, cell=100
        val base = calib.toBase6x5Geometry(frame.width, frame.height)
        val cell = base.cellWidth.toInt()
        // Draw 5 real orb rows + colorful portrait-like row directly above!
        frame.drawOrbGrid(0, base.topPx.toInt(), cell, 6, 5)
        frame.drawOrbGrid(0, (base.topPx - cell * 1.25f).toInt(), cell, 6, 1)
        // Draw authentic ToS green HP bar between row 0 and the portrait row (y = top - 23 .. top - 6)
        val hpTop = (base.topPx - 23).toInt()
        val hpBot = (base.topPx - 6).toInt()
        for (y in hpTop..hpBot) {
            for (x in 60 until 540) {
                frame.pixels[y * frame.width + x] = 0xFF42D436.toInt()
            }
        }

        // 1) HP-bar barrier must prevent false 6x6 Weijian expansion
        val detected = AutoGridDetector.detect(frame, calib)
        assertEquals(6, detected.cols)
        assertEquals(5, detected.rows)
        assertFalse("HP bar must lock 6x5 and prevent portrait row being misclassified as Weijian", detected.isWeijianExpanded)

        // 2) Real-screen HP-bar anchor auto-calibrator must snap accurately to 6x5 board
        val snapped = AutoGridDetector.autoCalibrateFromRealScreen(frame)
        assertNotNull("autoCalibrateFromRealScreen must locate HP bar and snap board", snapped)
        assertEquals(6, snapped!!.cols)
        assertEquals(5, snapped.rows)
        assertEquals(base.topPx, snapped.topPx, 4.0f)
    }

    @Test
    fun `real Xiaomi 15 ToS battle screenshot locks 6x5 via HP bar with zero false Weijian`() {
        val realPng = java.io.File(
            System.getProperty("user.home"),
            ".gemini/antigravity/brain/57099816-f54e-41fc-8a2c-48bf0a7b1510/scratch/mi15_now.png"
        )
        if (!realPng.exists()) return
        val imageIo = Class.forName("javax.imageio.ImageIO")
        val img = imageIo.getMethod("read", java.io.File::class.java).invoke(null, realPng)
        val imgCls = img.javaClass
        val imgW = imgCls.getMethod("getWidth").invoke(img) as Int
        val imgH = imgCls.getMethod("getHeight").invoke(img) as Int
        val getRgb = imgCls.getMethod("getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        val frame = object : PixelFrame {
            override val width: Int = imgW
            override val height: Int = imgH
            override fun getArgb(x: Int, y: Int): Int =
                getRgb.invoke(img, x.coerceIn(0, width - 1), y.coerceIn(0, height - 1)) as Int
        }

        // 1) Default auto-calibration detect must NOT falsely expand onto portraits!
        val detected = AutoGridDetector.detect(frame, BoardCalibration())
        assertEquals("Real ToS screenshot must stay 6x5", 6, detected.cols)
        assertEquals("Real ToS screenshot must NOT falsely trigger 6x6 Weijian", 5, detected.rows)
        assertFalse(detected.isWeijianExpanded)
        assertEquals(1410f, detected.topPx, 5f)
        assertEquals(2410f, detected.bottomPx, 5f)

        // 2) HP-bar real-screen auto-calibrator must snap accurately to 1410..2410!
        val snapped = AutoGridDetector.autoCalibrateFromRealScreen(frame)
        assertNotNull("HP-bar real-screen auto-calibrator must succeed on real ToS screenshot", snapped)
        assertEquals(6, snapped!!.cols)
        assertEquals(5, snapped.rows)
        assertEquals(1410f, snapped.topPx, 6f)
        assertEquals(2410f, snapped.bottomPx, 6f)
    }

    private fun loadRealFrame(relPath: String): PixelFrame? {
        val file = java.io.File(
            System.getProperty("user.home"),
            ".gemini/antigravity/brain/57099816-f54e-41fc-8a2c-48bf0a7b1510/scratch/$relPath"
        )
        if (!file.exists()) return null
        val imageIo = Class.forName("javax.imageio.ImageIO")
        val img = imageIo.getMethod("read", java.io.File::class.java).invoke(null, file)
        val imgCls = img.javaClass
        val imgW = imgCls.getMethod("getWidth").invoke(img) as Int
        val imgH = imgCls.getMethod("getHeight").invoke(img) as Int
        val getRgb = imgCls.getMethod("getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        return object : PixelFrame {
            override val width: Int = imgW
            override val height: Int = imgH
            override fun getArgb(x: Int, y: Int): Int =
                getRgb.invoke(img, x.coerceIn(0, width - 1), y.coerceIn(0, height - 1)) as Int
        }
    }

    @Test
    fun `real 2026 official YouTube gameplay screenshots lock 6x5 across Coral-Red and Partial-Green HP bars`() {
        val samples = listOf(
            "real_tos_samples/real_yt_nov10_clean.png",
            "real_tos_samples/real_yt_disaster_clean.png",
            "real_tos_samples/real_yt_nov10_shield.png",
            "real_tos_samples/real_yt_extra2_ench.png"
        )
        for (rel in samples) {
            val frame = loadRealFrame(rel) ?: continue
            // 1) Default auto-9:16 + HP-bar barrier must stay 6x5 (never falsely expand onto team portraits!)
            val detected = AutoGridDetector.detect(frame, BoardCalibration())
            assertEquals("$rel cols must be 6", 6, detected.cols)
            assertEquals("$rel rows must stay 5 (zero false Weijian)", 5, detected.rows)
            assertFalse("$rel must NOT falsely flag Weijian", detected.isWeijianExpanded)

            // 2) Real-screen HP-bar anchor auto-calibrator must snap within +/-4px of true board top (~140px on 134x262)
            val snapped = AutoGridDetector.autoCalibrateFromRealScreen(frame)
            assertNotNull("$rel autoCalibrateFromRealScreen must find HP bar", snapped)
            assertEquals("$rel snapped cols", 6, snapped!!.cols)
            assertEquals("$rel snapped rows", 5, snapped.rows)
            assertEquals("$rel snapped topPx should match auto 9:16 baseline", detected.topPx, snapped.topPx, 5.5f)
            assertEquals("$rel snapped bottomPx should match auto 9:16 baseline", detected.bottomPx, snapped.bottomPx, 5.5f)
        }
    }
}
