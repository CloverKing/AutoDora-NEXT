package com.autodora.next.vision

import com.autodora.next.model.*
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

    @Test
    fun `distinguishes slate-blue question orbs from vivid water orbs and detects top-right race badge`() {
        val cell = 100
        val cols = 6
        val rows = 5
        val frame = SyntheticBoardFrame(cols * cell, rows * cell)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val x0 = c * cell
                val y0 = r * cell
                if (r == 0) {
                    // Row 0: Slate-blue Question ('?') orbs (muted dark-indigo body + pale center)
                    frame.drawCell(x0, y0, cell, color = 0xFF567498.toInt())
                    // Paint pale '?' mark center strokes
                    for (dy in -14..14) {
                        for (dx in -6..6) {
                            frame.pixels[(y0 + 50 + dy) * frame.width + (x0 + 50 + dx)] = 0xFFDCE6EE.toInt()
                        }
                    }
                } else {
                    // Rows 1..4: Real vivid magenta-purple Dark orb (H~295°) with top-right B&W Race Badge
                    frame.drawCell(x0, y0, cell, color = 0xFFEF8AFF.toInt())
                    val cx = x0 + 50
                    val cy = y0 + 50
                    for (dy in -34..-12) {
                        for (dx in 12..34) {
                            val bd = hypot((dx - 23).toDouble(), (dy + 23).toDouble())
                            if (bd <= 12.0) {
                                frame.pixels[(cy + dy) * frame.width + (cx + dx)] =
                                    if (bd <= 7.0) 0xFF141414.toInt() else 0xFFFAFAFA.toInt()
                            }
                        }
                    }
                }
            }
        }

        val geom = BoardGeometry(0f, 0f, (cols * cell).toFloat(), (rows * cell).toFloat(), cols, rows)
        val result = CvBoardRecognizer().recognizeFrames(listOf(frame), geom)

        // Row 0 must be detected as HIDDEN + QUESTION (NEVER falsely Water!)
        for (c in 0 until cols) {
            assertEquals("Row 0 col $c must be HIDDEN question orb", Element.HIDDEN, result.board.cells[c].element)
            assertTrue((result.board.cells[c].hazards and HazardBits.QUESTION) != 0)
        }
        assertEquals(6, result.questionOrbsDetected)

        // Rows 1..4 magenta-purple Dark orbs with Race badge must classify as DARK + Variant.RACE (NOT Enchanted/Heart!)
        for (i in 6 until 30) {
            assertEquals("Cell $i must be DARK", Element.DARK, result.board.cells[i].element)
            assertEquals("Cell $i must detect top-right RACE badge", Variant.RACE, result.board.cells[i].variant)
        }
        assertEquals(24, result.raceBadgesDetected)
    }

    @Test
    fun `flash reveal memory tracker locks clear snapshot when board flips to mass question orbs`() {
        val tracker = FlashRevealMemoryTracker(maxMemoryAgeMs = 15_000L)
        val clearCells = List(30) { i -> CellState(Element.MATCHABLE[i % 6], confidence = 0.92f) }
        val clearRec = RecognitionResult(
            board = BoardState(6, 5, clearCells),
            averageConfidence = 0.92f,
            lowConfidenceIndices = emptyList(),
            puzzleCellsDetected = 0,
            engineName = "test",
            elapsedMs = 2.0
        )
        assertFalse(tracker.onWatchFrame(clearRec, nowMs = 1_000L))
        assertTrue(tracker.hasFreshClearMemory(nowMs = 2_000L))

        val hiddenCells = List(30) { CellState(Element.HIDDEN, hazards = HazardBits.QUESTION, confidence = 0.30f) }
        val liveFlags = MutableList(30) { if (it in 7..9) TileFlagBits.PUZZLE_1 else TileFlagBits.NONE }
        val hiddenRec = RecognitionResult(
            board = BoardState(6, 5, hiddenCells, tileFlags = liveFlags),
            averageConfidence = 0.30f,
            lowConfidenceIndices = (0 until 30).toList(),
            puzzleCellsDetected = 3,
            engineName = "test",
            elapsedMs = 2.0
        )

        assertTrue("Mass '?' flip must lock memorized snapshot", tracker.onWatchFrame(hiddenRec, nowMs = 2_500L))
        val resolved = tracker.resolveForSolve(hiddenRec, nowMs = 3_000L)
        assertTrue(resolved is FlashRevealMemoryTracker.MemoryResolution.UseMemorizedSnapshot)
        val mem = resolved as FlashRevealMemoryTracker.MemoryResolution.UseMemorizedSnapshot
        assertEquals(30, mem.liveHiddenCount)
        assertEquals(clearCells[0].element, mem.rememberedBoard.cells[0].element)
        assertTrue("Live puzzle tile flags must merge onto memorized board", (mem.rememberedBoard.tileFlags[7] and TileFlagBits.PUZZLE_1) != 0)
    }

    @Test
    fun `real Xiaomi 15 ToS battle screenshot recognizes Light Dark Water Wood Fire and Race badges cleanly`() {
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
        val frame = object : VisionFrame {
            override val width: Int = imgW
            override val height: Int = imgH
            override fun getArgb(x: Int, y: Int): Int =
                getRgb.invoke(img, x.coerceIn(0, width - 1), y.coerceIn(0, height - 1)) as Int
        }
        val geom = BoardGeometry(0f, 1410f, 1200f, 2410f, 6, 5)
        val rec = CvBoardRecognizer().recognizeFrames(listOf(frame), geom)

        // Verify unobscured real orbs across all 5 colors!
        // Note: in mi15_now.png (captured mid-swipe), (0,4), (1,4), and (2,3) are the active drag/empty slot!
        val expected = mapOf(
            (0 to 0) to Element.LIGHT, (0 to 1) to Element.LIGHT, (0 to 2) to Element.LIGHT,
            (0 to 3) to Element.DARK,  (0 to 5) to Element.LIGHT,
            (1 to 0) to Element.DARK,  (1 to 1) to Element.DARK,  (1 to 2) to Element.WATER,
            (1 to 3) to Element.DARK,  (1 to 5) to Element.DARK,
            (2 to 1) to Element.WATER, (2 to 2) to Element.WATER,
            (2 to 4) to Element.WATER, (2 to 5) to Element.WATER,
            (3 to 0) to Element.WOOD,  (3 to 1) to Element.WOOD,  (3 to 2) to Element.WOOD,
            (3 to 3) to Element.WOOD,  (3 to 4) to Element.WOOD,  (3 to 5) to Element.WOOD,
            (4 to 0) to Element.FIRE,  (4 to 1) to Element.FIRE,  (4 to 2) to Element.FIRE,
            (4 to 3) to Element.FIRE,  (4 to 4) to Element.FIRE,  (4 to 5) to Element.FIRE
        )
        for ((rc, el) in expected) {
            val actual = rec.board.cellAt(rc.first, rc.second)
            assertEquals("Real ToS orb (${rc.first},${rc.second}) mismatch", el, actual.element)
        }
        assertTrue("Real Human-race orbs must detect Race badges (detected=${rec.raceBadgesDetected})", rec.raceBadgesDetected >= 18)
        assertEquals("Real vivid Water orbs must NOT be falsely flagged as Question orbs", 0, rec.questionOrbsDetected)
    }

    private fun loadRealVisionFrame(relPath: String): VisionFrame? {
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
        return object : VisionFrame {
            override val width: Int = imgW
            override val height: Int = imgH
            override fun getArgb(x: Int, y: Int): Int =
                getRgb.invoke(img, x.coerceIn(0, width - 1), y.coerceIn(0, height - 1)) as Int
        }
    }

    private fun parseGrid(vararg rows: String): List<Element> {
        val out = ArrayList<Element>()
        for (line in rows) {
            for (ch in line.replace(" ", "")) {
                out.add(
                    when (ch) {
                        'W' -> Element.WATER
                        'F' -> Element.FIRE
                        'G' -> Element.WOOD
                        'L' -> Element.LIGHT
                        'D' -> Element.DARK
                        'H' -> Element.HEART
                        else -> Element.UNKNOWN
                    }
                )
            }
        }
        return out
    }

    @Test
    fun `real 2026 official YouTube gameplay screenshots achieve 100 percent recognition across all 6 elements, shield orbs, and enchanted orbs`() {
        val recognizer = CvBoardRecognizer()
        // Auto 9:16 geometry on 134x262 portrait phone screen:
        // playH = 134 * 16 / 9 = 238.22; topOffset = (262 - 238.22) / 2 = 11.89; bottom = 250.11; cell = 22.33; top = 138.45
        val geom134 = BoardGeometry(0f, 138.45f, 134f, 250.11f, 6, 5)

        // 1) Nov 2026 Lv.10 clean 30/30 all 6 elements (including Heart vs Dark!)
        loadRealVisionFrame("real_tos_samples/real_yt_nov10_clean.png")?.let { frame ->
            val rec = recognizer.recognizeFrames(listOf(frame), geom134)
            val expected = parseGrid(
                "D W D G G D",
                "L D W L W W",
                "L W D L W D",
                "F W L F L L",
                "G D D H L H"
            )
            for (i in 0 until 30) {
                assertEquals("nov10_clean (${i / 6},${i % 6}) mismatch", expected[i], rec.board.cells[i].element)
            }
            assertEquals("nov10_clean zero false question orbs", 0, rec.questionOrbsDetected)
        }

        // 2) 2026 Disaster stage clean 30/30 all 6 elements
        loadRealVisionFrame("real_tos_samples/real_yt_disaster_clean.png")?.let { frame ->
            val rec = recognizer.recognizeFrames(listOf(frame), geom134)
            val expected = parseGrid(
                "L G D F G H",
                "L F L D W L",
                "H D D H D W",
                "G L D G L W",
                "W L L W G D"
            )
            for (i in 0 until 30) {
                assertEquals("disaster_clean (${i / 6},${i % 6}) mismatch", expected[i], rec.board.cells[i].element)
            }
            assertEquals("disaster_clean zero false question orbs", 0, rec.questionOrbsDetected)
        }

        // 3) Nov 2026 Lv.10 with white-rimmed Shield Heart (4) & Shield Light (3) & Enchanted Water (2)
        loadRealVisionFrame("real_tos_samples/real_yt_nov10_shield.png")?.let { frame ->
            val rec = recognizer.recognizeFrames(listOf(frame), geom134)
            val expected = parseGrid(
                "G L G F D L",
                "G H H G H W",
                "F H D W D D",
                "L W W F G G",
                "G D W W G W"
            )
            for (i in 0 until 30) {
                assertEquals("nov10_shield (${i / 6},${i % 6}) mismatch", expected[i], rec.board.cells[i].element)
            }
            assertEquals("nov10_shield zero false question orbs", 0, rec.questionOrbsDetected)
        }

        // 4) 2026 Extra 2 with heavily Enchanted orbs across all 6 elements (rows 0..3 unobscured; row 4 has subtitle overlay, (1,4) is active drag gap)
        loadRealVisionFrame("real_tos_samples/real_yt_extra2_ench.png")?.let { frame ->
            val rec = recognizer.recognizeFrames(listOf(frame), geom134)
            val expected = parseGrid(
                "F L F D W D",
                "G W D D ? W",
                "H H L D D H",
                "G W H F F L"
            )
            for (i in 0 until 24) {
                if (expected[i] == Element.UNKNOWN) continue
                assertEquals("extra2_ench (${i / 6},${i % 6}) mismatch", expected[i], rec.board.cells[i].element)
            }
        }
    }

    @Test
    fun `live 1080x2400 emulator ToS battle screenshot achieves 30-of-30 recognition with zero false question orbs`() {
        val frame = loadRealVisionFrame("real_tos_samples/emu_battle_01.png") ?: return
        val geom = BoardGeometry(0f, 1267.7f, 1080f, 2167.7f, 6, 5)
        val rec = CvBoardRecognizer().recognizeFrames(listOf(frame), geom)
        val expected = parseGrid(
            "D G F W W L",
            "W G H W F D",
            "W H F D G W",
            "G H D F D D",
            "D L D L H W"
        )
        for (i in 0 until 30) {
            assertEquals("emu_battle_01 (${i / 6},${i % 6}) mismatch", expected[i], rec.board.cells[i].element)
        }
        assertEquals("emu_battle_01 must have zero false question orbs", 0, rec.questionOrbsDetected)
        assertTrue("emu_battle_01 average confidence should be high (actual=${rec.averageConfidence})", rec.averageConfidence >= 0.80f)
    }
}
