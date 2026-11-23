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

    @Test
    fun `live 1080x2400 emulator ToS battle screenshot snaps within 3px via both auto 9-to-16 and coral HP bar`() {
        val frame = loadRealFrame("real_tos_samples/emu_battle_01.png") ?: return
        val detected = AutoGridDetector.detect(frame, BoardCalibration())
        assertEquals(6, detected.cols)
        assertEquals(5, detected.rows)
        assertFalse("Coral HP bar must prevent false Weijian", detected.isWeijianExpanded)
        assertEquals(1267.7f, detected.topPx, 3f)
        assertEquals(2167.7f, detected.bottomPx, 3f)

        val snapped = AutoGridDetector.autoCalibrateFromRealScreen(frame)
        assertNotNull("Auto-calibrate must snap to Coral HP bar on 1080x2400 emulator", snapped)
        assertEquals(6, snapped!!.cols)
        assertEquals(5, snapped.rows)
        assertEquals(1268f, snapped.topPx, 4f)
        assertEquals(2168f, snapped.bottomPx, 4f)
    }

    @Test
    fun `isBattleBoardPresent distinguishes real battle boards from non-battle maps and UI screens`() {
        // 1) Pure synthetic non-battle bright UI / map backdrop -> FALSE
        val brightUi = SyntheticFrame(600, 1200)
        brightUi.pixels.fill(0xFFD8D6CF.toInt())
        val baseGeom = BoardCalibration().toBase6x5Geometry(600, 1200)
        assertFalse(
            "Bright map/UI backdrop without HP bar or dark grid corners must NOT pass battle gate",
            AutoGridDetector.isBattleBoardPresent(brightUi, baseGeom)
        )

        // 2) Pure synthetic battle board with dark grid corners + circular orbs + HP bar -> TRUE
        val synthBattle = SyntheticFrame(600, 1200)
        for (y in (baseGeom.topPx - 18f).toInt().coerceAtLeast(0) until (baseGeom.topPx - 4f).toInt()) {
            for (x in 40 until 560) {
                synthBattle.pixels[y * 600 + x] = 0xFF2ED458.toInt()
            }
        }
        synthBattle.drawOrbGrid(0, baseGeom.topPx.toInt(), baseGeom.cellWidth.toInt(), 6, 5)
        assertTrue(
            "Authentic battle board with HP bar & circular orbs must pass battle gate",
            AutoGridDetector.isBattleBoardPresent(synthBattle, baseGeom)
        )

        // 3) Real ToS battle screenshots (normal, storm, silver puzzle, strict gold puzzle) -> TRUE
        for (rel in listOf(
            "real_tos_samples/emu_battle_01.png",
            "real_tos_samples/emu_mech_check.png",
            "puzzle_vid_frames/t_4200ms.png",
            "strict_puzzle_frames/t_1400ms.png"
        )) {
            val frame = loadRealFrame(rel) ?: continue
            val g = AutoGridDetector.detect(frame, BoardCalibration())
            assertTrue("Real battle screen $rel must pass isBattleBoardPresent", AutoGridDetector.isBattleBoardPresent(frame, g))
        }

        // 4) Real ToS non-battle screens (Home Map, Android app UI) -> FALSE
        for (rel in listOf(
            "../.user_uploaded/media_1795317721071_84df87e5.jpg",
            "../.user_uploaded/media_1795317796519_61ba11e2.jpg",
            "verify_01_home.png"
        )) {
            val frame = loadRealFrame(rel) ?: continue
            val g = AutoGridDetector.detect(frame, BoardCalibration())
            assertFalse("Non-battle screen $rel must be REJECTED by isBattleBoardPresent", AutoGridDetector.isBattleBoardPresent(frame, g))
        }
    }

    @Test
    fun `isDimmedTransitionBoard catches dimmed VICTORY overlay while allowing active boards and full-board question orbs`() {
        val baseGeom = BoardCalibration().toBase6x5Geometry(600, 1200)

        // 1) Active bright battle board -> NOT dimmed transition
        val activeBoard = SyntheticFrame(600, 1200)
        activeBoard.drawOrbGrid(0, baseGeom.topPx.toInt(), baseGeom.cellWidth.toInt(), 6, 5)
        assertFalse(
            "Active battle board must not be flagged as dimmed transition",
            AutoGridDetector.isDimmedTransitionBoard(activeBoard, baseGeom, questionCount = 0)
        )

        // 2) Dimmed VICTORY / wave-walk screen (~50% brightness, 9 false '?') -> TRUE
        val dimmedVictory = SyntheticFrame(600, 1200)
        dimmedVictory.drawOrbGrid(0, baseGeom.topPx.toInt(), baseGeom.cellWidth.toInt(), 6, 5)
        for (i in dimmedVictory.pixels.indices) {
            val p = dimmedVictory.pixels[i]
            val r = (((p shr 16) and 0xFF) * 0.55f).toInt()
            val g = (((p shr 8) and 0xFF) * 0.55f).toInt()
            val b = ((p and 0xFF) * 0.55f).toInt()
            dimmedVictory.pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        assertTrue(
            "Dimmed VICTORY/wave-transition board must be detected to suppress false flash-reveal overlay",
            AutoGridDetector.isDimmedTransitionBoard(dimmedVictory, baseGeom, questionCount = 9)
        )

        // 3) Genuine full-board 30/30 '?' flash-reveal screen (dimmed slate look, q=30 >= 18) -> FALSE
        assertFalse(
            "Genuine full-board question-orb flash-reveal (30/30) must NOT be suppressed",
            AutoGridDetector.isDimmedTransitionBoard(dimmedVictory, baseGeom, questionCount = 30)
        )

        // 3.5) Genuine full-board 30/30 Stone-Locked / Blood-Drain battle board (p90V~0.59, specialDark=30 >= 18) -> FALSE
        assertFalse(
            "Genuine full-board 30/30 Stone-Locked / Blood-Drain board must NOT be suppressed as dimmed VICTORY",
            AutoGridDetector.isDimmedTransitionBoard(dimmedVictory, baseGeom, questionCount = 0, specialDarkOrbCount = 30)
        )

        // 4) Optional real local VICTORY screenshot verification (gitignored dataset)
        val victoryFile = java.io.File("../../backups/user_training_media/20261122_barrier_burning_victory/victory_flash_reveal_bug.jpg")
        if (victoryFile.exists()) {
            val imageIo = Class.forName("javax.imageio.ImageIO")
            val img = imageIo.getMethod("read", java.io.File::class.java).invoke(null, victoryFile)
            val imgCls = img.javaClass
            val w = imgCls.getMethod("getWidth").invoke(img) as Int
            val h = imgCls.getMethod("getHeight").invoke(img) as Int
            val getRgb = imgCls.getMethod("getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            val frame = object : PixelFrame {
                override val width = w
                override val height = h
                override fun getArgb(x: Int, y: Int): Int =
                    getRgb.invoke(img, x.coerceIn(0, width - 1), y.coerceIn(0, height - 1)) as Int
            }
            val g = AutoGridDetector.detect(frame, BoardCalibration())
            assertTrue(
                "Real VICTORY screen must be caught by isDimmedTransitionBoard",
                AutoGridDetector.isDimmedTransitionBoard(frame, g, questionCount = 9)
            )
        }
    }

    @Test
    fun `empty 1-HP dark track with left heart locks 6x5 and rejects tall beige event banners`() {
        val frame = SyntheticFrame(width = 600, height = 1335)
        val calib = BoardCalibration()
        val base = calib.toBase6x5Geometry(frame.width, frame.height)
        val cell = base.cellWidth.toInt()

        // Draw 5 real orb rows + colorful team portraits directly above + Boss Red HP bar way above portraits!
        frame.drawOrbGrid(0, base.topPx.toInt(), cell, 6, 5)
        frame.drawOrbGrid(0, (base.topPx - cell * 1.25f).toInt(), cell, 6, 1)
        // Boss red HP bar 1.55 cells above board top (simulating Video 2 boss floor!)
        val bossTop = (base.topPx - cell * 1.62f).toInt()
        val bossBot = (base.topPx - cell * 1.48f).toInt()
        for (y in bossTop..bossBot) {
            for (x in 50 until 550) {
                frame.pixels[y * frame.width + x] = 0xFFE53028.toInt()
            }
        }
        // Empty 1/584001 dark charcoal Player HP track + metallic top border + left red Heart medallion directly above row 0
        val hpTop = (base.topPx - cell * 0.21f).toInt()
        val hpBot = (base.topPx - cell * 0.04f).toInt()
        for (x in 10 until frame.width - 10) {
            frame.pixels[(hpTop - 2) * frame.width + x] = 0xFFC8B88A.toInt() // Gold portrait frame border above HP bar
        }
        for (y in hpTop..hpBot) {
            for (x in (frame.width * 0.10f).toInt() until (frame.width * 0.72f).toInt()) {
                frame.pixels[y * frame.width + x] = 0xFF1A1A1E.toInt()
            }
            for (x in (frame.width * 0.02f).toInt() until (frame.width * 0.085f).toInt()) {
                frame.pixels[y * frame.width + x] = 0xFFD93832.toInt()
            }
        }

        val detected = AutoGridDetector.detect(frame, calib)
        assertEquals(6, detected.cols)
        assertEquals("Empty 1-HP bar must lock 6x5 (no false 6x6 Weijian over portraits)", 5, detected.rows)
        assertFalse(detected.isWeijianExpanded)

        val snapped = AutoGridDetector.autoCalibrateFromRealScreen(frame)
        assertNotNull("autoCalibrateFromRealScreen must locate 1-HP Player HP track", snapped)
        assertEquals(base.topPx, snapped!!.topPx, 6.0f)

        // Tall golden-beige Event Banner covering HP band & portrait band must fail hasHpBarBarrier!
        val banner = SyntheticFrame(width = 600, height = 1335)
        for (y in (base.topPx - cell * 0.70f).toInt() until base.topPx.toInt()) {
            for (x in 20 until 580) {
                banner.pixels[y * banner.width + x] = 0xFFD8A660.toInt() // Warm beige/gold H≈35°
            }
        }
        assertFalse(
            "Warm golden/beige event banner must NOT pass hasHpBarBarrier",
            AutoGridDetector.hasHpBarBarrier(banner, base.leftPx, base.rightPx, base.topPx, base.cellHeight)
        )

        // Optional local real-frame verification on Video 1 Event Banner & Video 2 1/584001 Empty HP frames
        val localDir = java.io.File("../../backups/user_training_media/20261123_banner_emptyhp_race")
        if (localDir.exists()) {
            fun loadLocal(name: String): PixelFrame? {
                val f = java.io.File(localDir, name)
                if (!f.exists()) return null
                val imageIo = Class.forName("javax.imageio.ImageIO")
                val img = imageIo.getMethod("read", java.io.File::class.java).invoke(null, f)
                val cls = img.javaClass
                val iw = cls.getMethod("getWidth").invoke(img) as Int
                val ih = cls.getMethod("getHeight").invoke(img) as Int
                val getRgb = cls.getMethod("getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                return object : PixelFrame {
                    override val width = iw
                    override val height = ih
                    override fun getArgb(x: Int, y: Int): Int =
                        getRgb.invoke(img, x.coerceIn(0, width - 1), y.coerceIn(0, height - 1)) as Int
                }
            }
            loadLocal("v1_banner.png")?.let { bf ->
                val g = BoardCalibration().toBase6x5Geometry(bf.width, bf.height)
                assertFalse(
                    "Real Video 1 Event Banner List must FAIL hasHpBarBarrier",
                    AutoGridDetector.hasHpBarBarrier(bf, g.leftPx, g.rightPx, g.topPx, g.cellHeight)
                )
            }
            for (v2Name in listOf("v2_00s.png", "v2_86s.png")) {
                loadLocal(v2Name)?.let { vf ->
                    val g = AutoGridDetector.detect(vf, BoardCalibration())
                    assertEquals("Real Video 2 $v2Name (1/584001 HP) must lock 6x5", 5, g.rows)
                    assertFalse("Real Video 2 $v2Name must NOT trigger false 6x6 Weijian", g.isWeijianExpanded)
                    val autoG = AutoGridDetector.autoCalibrateFromRealScreen(vf)
                    assertNotNull("Real Video 2 $v2Name auto-calibrate must find 1-HP Player bar", autoG)
                    assertEquals("Real Video 2 $v2Name auto-calibrate must snap to real board top (~683px, NOT Boss HP!)", 683f, autoG!!.topPx, 10f)
                }
            }
        }
    }
}
