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
        assertEquals("Real clean battle board must have zero false Storm flags", 0, rec.stormCellsDetected)
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
            assertEquals("nov10_clean zero false storm cells", 0, rec.stormCellsDetected)
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
            assertEquals("disaster_clean zero false storm cells", 0, rec.stormCellsDetected)
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
            assertEquals("nov10_shield zero false storm cells", 0, rec.stormCellsDetected)
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
            assertEquals("extra2_ench zero false storm cells", 0, rec.stormCellsDetected)
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
        assertEquals("emu_battle_01 must have zero false storm cells", 0, rec.stormCellsDetected)
        assertTrue("emu_battle_01 average confidence should be high (actual=${rec.averageConfidence})", rec.averageConfidence >= 0.80f)
    }

    @Test
    fun `live 1080x2400 emulator Storm tornado column detects all 5 storm cells and recognizes all 25 unobscured orbs`() {
        val frame = loadRealVisionFrame("real_tos_samples/emu_mech_check.png") ?: return
        val geom = BoardGeometry(0f, 1267.7f, 1080f, 2167.7f, 6, 5)
        val rec = CvBoardRecognizer().recognizeFrames(listOf(frame), geom)

        // Column 0 (rows 0..4) is covered by a full-column Storm / Tornado overlay
        assertEquals("Should detect exactly 5 Storm cells on Col 0", 5, rec.stormCellsDetected)
        for (r in 0 until 5) {
            val idx = r * 6 + 0
            assertTrue("Cell ($r,0) must have TileFlagBits.STORM", (rec.board.tileFlags[idx] and TileFlagBits.STORM) != 0)
            assertFalse("Storm cell ($r,0) must NOT be falsely flagged as low-confidence", idx in rec.lowConfidenceIndices)
        }

        // Columns 1..5 (25 unobscured orbs) must have ZERO Storm flags and 25/25 exact element recognition
        val expectedCols1to5 = parseGrid(
            "D L L D H",
            "D W G D W",
            "L H D G D",
            "G D D G G",
            "H D G W F"
        )
        for (r in 0 until 5) {
            for (c in 1 until 6) {
                val idx = r * 6 + c
                assertEquals("Non-storm cell ($r,$c) must NOT have TileFlagBits.STORM", 0, rec.board.tileFlags[idx] and TileFlagBits.STORM)
                assertEquals("emu_mech_check ($r,$c) mismatch", expectedCols1to5[r * 5 + (c - 1)], rec.board.cells[idx].element)
            }
        }
        assertEquals("Storm board must have zero false question orbs", 0, rec.questionOrbsDetected)
    }

    @Test
    fun `always-on memory tracker returns NeedHoldAndNudgeReveal when entering directly onto full-question board`() {
        val tracker = FlashRevealMemoryTracker()
        val fullQuestion = BoardState(
            cols = 6,
            rows = 5,
            cells = List(30) { CellState(Element.HIDDEN, hazards = HazardBits.QUESTION, confidence = 0.25f) }
        )
        val liveRec = RecognitionResult(
            board = fullQuestion,
            averageConfidence = 0.25f,
            lowConfidenceIndices = (0 until 30).toList(),
            puzzleCellsDetected = 0,
            engineName = "test",
            elapsedMs = 1.0,
            questionOrbsDetected = 30
        )
        val res = tracker.resolveForSolve(liveRec, nowMs = 10_000L)
        assertTrue(
            "Direct full-question board with no prior snapshot must request NeedHoldAndNudgeReveal",
            res is FlashRevealMemoryTracker.MemoryResolution.NeedHoldAndNudgeReveal
        )
    }

    @Test
    fun `dark modal check detects dark enemy popup and rejects bright battle frame`() {
        val darkModal = object : VisionFrame {
            override val width = 360
            override val height = 800
            override fun getArgb(x: Int, y: Int): Int = 0xFF141416.toInt()
        }
        assertTrue(
            "Dark charcoal modal must be detected by isDarkModalCoveringBoard",
            FlashRevealMemoryTracker.isDarkModalCoveringBoard(darkModal)
        )

        val brightBattle = object : VisionFrame {
            override val width = 360
            override val height = 800
            override fun getArgb(x: Int, y: Int): Int = 0xFF33AA44.toInt()
        }
        assertFalse(
            "Vivid battle frame must NOT trigger isDarkModalCoveringBoard",
            FlashRevealMemoryTracker.isDarkModalCoveringBoard(brightBattle)
        )
    }

    private fun drawPerimeterSilverFiligree(
        frame: SyntheticBoardFrame,
        cols: Int,
        rows: Int,
        cell: Int,
        puzzleSet: Set<Pair<Int, Int>>,
        barArgb: Int = 0xFFD2CEC4.toInt() // Real ToS pale metallic silver-ivory filigree
    ) {
        val halfThick = 2
        // Draw ONLY exterior boundary seams of puzzleSet (interior seams stay open & dark, exactly like real ToS!)
        for (r in 0..rows) {
            for (c in 0 until cols) {
                val up = (r - 1 to c) in puzzleSet
                val dn = (r to c) in puzzleSet
                if (up != dn) {
                    val yBase = (r * cell).coerceIn(halfThick, frame.height - 1 - halfThick)
                    val yStart = if (r == 0) 1 else if (r == rows) frame.height - 4 else yBase - halfThick
                    val yEnd = if (r == 0) 4 else if (r == rows) frame.height - 2 else yBase + halfThick
                    for (y in yStart..yEnd) {
                        for (x in (c * cell + 3) until ((c + 1) * cell - 3)) {
                            frame.pixels[y.coerceIn(0, frame.height - 1) * frame.width + x.coerceIn(0, frame.width - 1)] = barArgb
                        }
                    }
                }
            }
        }
        for (r in 0 until rows) {
            for (c in 0..cols) {
                val lt = (r to c - 1) in puzzleSet
                val rt = (r to c) in puzzleSet
                if (lt != rt) {
                    val xBase = (c * cell).coerceIn(halfThick, frame.width - 1 - halfThick)
                    val xStart = if (c == 0) 1 else if (c == cols) frame.width - 4 else xBase - halfThick
                    val xEnd = if (c == 0) 4 else if (c == cols) frame.width - 2 else xBase + halfThick
                    for (x in xStart..xEnd) {
                        for (y in (r * cell + 3) until ((r + 1) * cell - 3)) {
                            frame.pixels[y.coerceIn(0, frame.height - 1) * frame.width + x.coerceIn(0, frame.width - 1)] = barArgb
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `detects modern silver-ivory perimeter-polygon puzzles including interior cells and hollow rings`() {
        val cell = 84
        val cols = 6
        val rows = 5
        val palette = mapOf(
            Element.WATER to 0xFF1E9CFF.toInt(),
            Element.FIRE to 0xFFFF3020.toInt(),
            Element.WOOD to 0xFF2EC840.toInt(),
            Element.LIGHT to 0xFFFFDF20.toInt(),
            Element.DARK to 0xFF8C32E6.toInt(),
            Element.HEART to 0xFFFF5CA8.toInt()
        )
        val geom = BoardGeometry(0f, 0f, (cols * cell).toFloat(), (rows * cell).toFloat(), cols, rows)
        val recognizer = CvBoardRecognizer()

        val testShapes = listOf(
            "5-Cell Cross touching right edge" to setOf(1 to 4, 2 to 3, 2 to 4, 2 to 5, 3 to 4),
            "3x3 Solid Square with open center" to (1..3).flatMap { r -> (2..4).map { c -> r to c } }.toSet(),
            "3x3 Hollow O-Ring (center must stay excluded)" to ((1..3).flatMap { r -> (2..4).map { c -> r to c } }.toSet() - (2 to 3))
        )

        for ((label, expectedSet) in testShapes) {
            val frame = SyntheticBoardFrame(cols * cell, rows * cell)
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val el = Element.MATCHABLE[(r * cols + c) % 6]
                    frame.drawCell(c * cell, r * cell, cell, palette.getValue(el))
                }
            }
            drawPerimeterSilverFiligree(frame, cols, rows, cell, expectedSet)

            val rec = recognizer.recognizeFrames(listOf(frame), geom)
            val detectedSet = (0 until cols * rows)
                .filter { (rec.board.tileFlags[it] and TileFlagBits.PUZZLE_1) != 0 }
                .map { (it / cols) to (it % cols) }
                .toSet()
            assertEquals("$label exact cells mismatch", expectedSet, detectedSet)
            assertEquals("$label count mismatch", expectedSet.size, rec.puzzleCellsDetected)
        }

        // Solid bright-white app UI screen must produce ZERO false-positive puzzle flags
        val whiteUi = object : VisionFrame {
            override val width = cols * cell
            override val height = rows * cell
            override fun getArgb(x: Int, y: Int): Int = 0xFFF7F7F7.toInt()
        }
        val uiRec = recognizer.recognizeFrames(listOf(whiteUi), geom)
        assertEquals("White UI screen must never produce false puzzle cells", 0, uiRec.puzzleCellsDetected)

        // Strict Antique-Gold Filigree 5-cell inverted-L touching far-left edge (c=0) with CLIPPED left borders!
        val strictL = setOf(1 to 0, 1 to 1, 1 to 2, 2 to 0, 3 to 0)
        val strictFrame = SyntheticBoardFrame(cols * cell, rows * cell)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val el = Element.MATCHABLE[(r * cols + c) % 6]
                strictFrame.drawCell(c * cell, r * cell, cell, palette.getValue(el))
            }
        }
        // Draw antique-gold filigree ONLY on internal seams (simulating clipped x=0 screen edge!)
        val antiqueGold = 0xFFB89E4A.toInt() // H≈46°, S≈0.60, V≈0.72 (exact real ToS strict puzzle filigree)
        for (r in 1 until rows) {
            for (c in 0 until cols) {
                if (((r - 1 to c) in strictL) != ((r to c) in strictL)) {
                    for (y in (r * cell - 2)..(r * cell + 2)) {
                        for (x in (c * cell + 3) until ((c + 1) * cell - 3)) {
                            strictFrame.pixels[y * strictFrame.width + x] = antiqueGold
                        }
                    }
                }
            }
        }
        for (r in 0 until rows) {
            for (c in 1 until cols) {
                if (((r to c - 1) in strictL) != ((r to c) in strictL)) {
                    for (x in (c * cell - 2)..(c * cell + 2)) {
                        for (y in (r * cell + 3) until ((r + 1) * cell - 3)) {
                            strictFrame.pixels[y * strictFrame.width + x] = antiqueGold
                        }
                    }
                }
            }
        }
        val strictRec = recognizer.recognizeFrames(listOf(strictFrame), geom)
        val strictDetected = (0 until cols * rows)
            .filter { (strictRec.board.tileFlags[it] and TileFlagBits.PUZZLE_1) != 0 }
            .map { (it / cols) to (it % cols) }
            .toSet()
        assertEquals("Edge-clipped strict antique-gold L puzzle must detect exact 5 cells", strictL, strictDetected)
        assertEquals(5, strictRec.puzzleCellsDetected)
    }

    @Test
    fun `real ToS silver-filigree cross puzzle frames detect exact 5 cells with zero false positives on clean boards`() {
        val recognizer = CvBoardRecognizer()
        val expectedCross = setOf(1 to 4, 2 to 3, 2 to 4, 2 to 5, 3 to 4)

        fun autoBaseGeom(w: Int, h: Int): BoardGeometry {
            val gameH = w * (16f / 9f)
            val letterbox = ((h - gameH) * 0.5f).coerceAtLeast(0f)
            val bottomPx = (h - letterbox + gameH * 0.004f).coerceAtMost(h.toFloat())
            val cell = w / 6f
            val bottom = bottomPx.coerceIn(cell * 5f, h.toFloat())
            val top = (bottom - cell * 5f).coerceAtLeast(0f)
            return BoardGeometry(0f, top, w.toFloat(), bottom, 6, 5)
        }

        for (name in listOf("t_100ms.png", "t_3500ms.png", "t_4200ms.png", "t_6200ms.png")) {
            val frame = loadRealVisionFrame("puzzle_vid_frames/$name") ?: continue
            val rec = recognizer.recognizeFrames(listOf(frame), autoBaseGeom(frame.width, frame.height))
            val detected = (0 until 30)
                .filter { (rec.board.tileFlags[it] and TileFlagBits.PUZZLE_1) != 0 }
                .map { (it / 6) to (it % 6) }
                .toSet()
            assertEquals("Real puzzle frame $name must detect exact 5-cell Cross", expectedCross, detected)
            assertEquals("Real puzzle frame $name count", 5, rec.puzzleCellsDetected)
        }

        // Strict Antique-Gold Filigree 5-cell L puzzle touching left screen edge (x=0 clipped!)
        val expectedStrictL = setOf(1 to 0, 1 to 1, 1 to 2, 2 to 0, 3 to 0)
        for (name in listOf("t_200ms.png", "t_1000ms.png", "t_1400ms.png", "t_1800ms.png", "t_2200ms.png", "t_3200ms.png", "t_4200ms.png")) {
            val frame = loadRealVisionFrame("strict_puzzle_frames/$name") ?: continue
            val rec = recognizer.recognizeFrames(listOf(frame), autoBaseGeom(frame.width, frame.height))
            val detected = (0 until 30)
                .filter { (rec.board.tileFlags[it] and TileFlagBits.PUZZLE_1) != 0 }
                .map { (it / 6) to (it % 6) }
                .toSet()
            assertEquals("Real strict antique-gold puzzle frame $name must detect exact 5-cell L", expectedStrictL, detected)
            assertEquals("Real strict antique-gold puzzle frame $name count", 5, rec.puzzleCellsDetected)
        }

        for (neg in listOf(
            "mi15_now.png",
            "real_tos_samples/emu_battle_01.png",
            "real_tos_samples/emu_mech_check.png",
            "real_tos_samples/user_fire5_battle.jpg",
            "real_tos_samples/user_wood5_battle.jpg",
            "real_tos_samples/user_light_dark5_battle.jpg"
        )) {
            val frame = loadRealVisionFrame(neg) ?: continue
            val rec = recognizer.recognizeFrames(listOf(frame), autoBaseGeom(frame.width, frame.height))
            assertEquals("Non-puzzle real battle $neg must detect 0 puzzle cells", 0, rec.puzzleCellsDetected)
            assertEquals("Non-TL real battle $neg must detect 0 traffic-light starts", 0, rec.trafficLightStartCount)
            assertEquals("Non-TL real battle $neg must detect 0 traffic-light ends", 0, rec.trafficLightEndCount)
        }
    }

    @Test
    fun `1x5 full-column puzzle ignores bright white HP bar text and metallic bottom rim on adjacent columns`() {
        val cell = 84
        val cols = 6
        val rows = 5
        val palette = mapOf(
            Element.WATER to 0xFF1E9CFF.toInt(),
            Element.FIRE to 0xFFFF3020.toInt(),
            Element.WOOD to 0xFF2EC840.toInt(),
            Element.LIGHT to 0xFFFFDF20.toInt(),
            Element.DARK to 0xFF8C32E6.toInt(),
            Element.HEART to 0xFFFF5CA8.toInt()
        )
        val geom = BoardGeometry(0f, 0f, (cols * cell).toFloat(), (rows * cell).toFloat(), cols, rows)
        val frame = SyntheticBoardFrame(cols * cell, rows * cell)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                frame.drawCell(c * cell, r * cell, cell, palette.getValue(Element.MATCHABLE[(r * cols + c) % 6]))
            }
        }
        // Draw antique-gold filigree strictly along V[0..4, 3] and V[0..4, 4] (1x5 full vertical column at col 3)
        val antiqueGold = 0xFFB89E4A.toInt()
        for (r in 0 until rows) {
            for (cSeam in listOf(3, 4)) {
                for (x in (cSeam * cell - 2)..(cSeam * cell + 2)) {
                    for (y in (r * cell + 3) until ((r + 1) * cell - 3)) {
                        frame.pixels[y * frame.width + x] = antiqueGold
                    }
                }
            }
        }
        // Pollute top outer rim (HP numbers "66732/70694") and bottom outer rim across cols 4..5 with bright white/gold!
        for (c in 4 until 6) {
            for (x in (c * cell + 2) until ((c + 1) * cell - 2)) {
                for (y in 0..4) frame.pixels[y * frame.width + x] = 0xFFF8F4F6.toInt()
                for (y in (frame.height - 5) until frame.height) frame.pixels[y * frame.width + x] = 0xFFD4C490.toInt()
            }
        }

        val rec = CvBoardRecognizer().recognizeFrames(listOf(frame), geom)
        val expectedCol3 = (0 until 5).map { it to 3 }.toSet()
        val detected = (0 until 30)
            .filter { (rec.board.tileFlags[it] and TileFlagBits.PUZZLE_1) != 0 }
            .map { (it / 6) to (it % 6) }
            .toSet()
        assertEquals("1x5 full-column puzzle must detect exact col 3 without gluing cols 4..5", expectedCol3, detected)
        assertEquals(5, rec.puzzleCellsDetected)
    }

    @Test
    fun `detects Traffic Light green GO start and red burst end with zero false positives on Fire and Wood orbs`() {
        val cell = 90
        val cols = 6
        val rows = 5
        val palette = mapOf(
            Element.WATER to 0xFF1E9CFF.toInt(),
            Element.FIRE to 0xFFFF2418.toInt(),
            Element.WOOD to 0xFF28C83C.toInt(),
            Element.LIGHT to 0xFFFFD820.toInt(),
            Element.DARK to 0xFF8C32E6.toInt(),
            Element.HEART to 0xFFFF5CA8.toInt()
        )
        val geom = BoardGeometry(0f, 0f, (cols * cell).toFloat(), (rows * cell).toFloat(), cols, rows)
        val frame = SyntheticBoardFrame(cols * cell, rows * cell)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val el = when {
                    r == 1 && c == 5 -> Element.LIGHT
                    r == 3 && c == 2 -> Element.LIGHT
                    (r + c) % 2 == 0 -> Element.FIRE
                    else -> Element.WOOD
                }
                frame.drawCell(c * cell, r * cell, cell, palette.getValue(el))
            }
        }

        // Paint neon-green corner rays + bold white "GO" band on (1, 5)
        val gCx = (5 + 0.5f) * cell
        val gCy = (1 + 0.5f) * cell
        val neonGreen = 0xFF22FF38.toInt()
        for (sx in listOf(-1f, 1f)) {
            for (sy in listOf(-1f, 1f)) {
                for (d in 36..44) {
                    val px = (gCx + sx * d).toInt().coerceIn(0, frame.width - 1)
                    val py = (gCy + sy * d).toInt().coerceIn(0, frame.height - 1)
                    frame.pixels[py * frame.width + px] = neonGreen
                    val px2 = (gCx + sx * d).toInt().coerceIn(0, frame.width - 1)
                    val py2 = (gCy + sy * (d - 6)).toInt().coerceIn(0, frame.height - 1)
                    frame.pixels[py2 * frame.width + px2] = neonGreen
                }
            }
        }
        for (dy in (-cell * 0.12f).toInt()..(cell * 0.12f).toInt()) {
            for (dx in (-cell * 0.28f).toInt()..(cell * 0.28f).toInt()) {
                val px = (gCx + dx).toInt().coerceIn(0, frame.width - 1)
                val py = (gCy + dy).toInt().coerceIn(0, frame.height - 1)
                frame.pixels[py * frame.width + px] = 0xFFFFFFFF.toInt()
            }
        }

        // Paint vivid crimson-red 8-ray burst into all 4 extreme corners on (3, 2)
        val rCx = (2 + 0.5f) * cell
        val rCy = (3 + 0.5f) * cell
        val crimsonRed = 0xFFFF1818.toInt()
        for (sx in listOf(-1f, 1f)) {
            for (sy in listOf(-1f, 1f)) {
                for (d in 36..44) {
                    val px = (rCx + sx * d).toInt().coerceIn(0, frame.width - 1)
                    val py = (rCy + sy * d).toInt().coerceIn(0, frame.height - 1)
                    frame.pixels[py * frame.width + px] = crimsonRed
                    val px2 = (rCx + sx * (d - 5)).toInt().coerceIn(0, frame.width - 1)
                    val py2 = (rCy + sy * d).toInt().coerceIn(0, frame.height - 1)
                    frame.pixels[py2 * frame.width + px2] = crimsonRed
                }
            }
        }

        val rec = CvBoardRecognizer().recognizeFrames(listOf(frame), geom)
        assertEquals("Must detect exactly 1 Traffic Light start", 1, rec.trafficLightStartCount)
        assertEquals("Must detect exactly 1 Traffic Light end", 1, rec.trafficLightEndCount)
        assertTrue("(1,5) must have START_ALLOWED", (rec.board.tileFlags[1 * 6 + 5] and TileFlagBits.START_ALLOWED) != 0)
        assertTrue("(3,2) must have END_REQUIRED", (rec.board.tileFlags[3 * 6 + 2] and TileFlagBits.END_REQUIRED) != 0)
        assertEquals("(1,5) GO orb must still be classified as LIGHT", Element.LIGHT, rec.board.cellAt(1, 5).element)
        assertEquals("(3,2) Red-Burst orb must still be classified as LIGHT", Element.LIGHT, rec.board.cellAt(3, 2).element)
        assertEquals("Zero false question orbs", 0, rec.questionOrbsDetected)
    }

    @Test
    fun `local Phase-4 dataset regression verifies 1x5 strict column puzzle and real Traffic Light screenshot`() {
        val datasetRoot = java.io.File("../../backups/phase4_training_dataset")
        if (!datasetRoot.exists()) return
        val recognizer = CvBoardRecognizer()

        fun loadDatasetFrame(rel: String): VisionFrame? {
            val f = java.io.File(datasetRoot, rel)
            if (!f.exists()) return null
            val imageIo = Class.forName("javax.imageio.ImageIO")
            val img = imageIo.getMethod("read", java.io.File::class.java).invoke(null, f)
            val imgCls = img.javaClass
            val w = imgCls.getMethod("getWidth").invoke(img) as Int
            val h = imgCls.getMethod("getHeight").invoke(img) as Int
            val getRgb = imgCls.getMethod("getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            return object : VisionFrame {
                override val width = w
                override val height = h
                override fun getArgb(x: Int, y: Int): Int =
                    getRgb.invoke(img, x.coerceIn(0, width - 1), y.coerceIn(0, height - 1)) as Int
            }
        }

        fun autoBaseGeom(w: Int, h: Int): BoardGeometry {
            val gameH = w * (16f / 9f)
            val letterbox = ((h - gameH) * 0.5f).coerceAtLeast(0f)
            val bottomPx = (h - letterbox + gameH * 0.004f).coerceAtMost(h.toFloat())
            val cell = w / 6f
            val bottom = bottomPx.coerceIn(cell * 5f, h.toFloat())
            val top = (bottom - cell * 5f).coerceAtLeast(0f)
            return BoardGeometry(0f, top, w.toFloat(), bottom, 6, 5)
        }

        // 1) Video 3 clean un-overlayed frames: 1x5 full-column strict puzzle at col 3
        val expectedCol3 = (0 until 5).map { it to 3 }.toSet()
        for (name in listOf("t_7700ms.png", "t_10200ms.png")) {
            val frame = loadDatasetFrame("extracted_frames/strict_gold_1x5_col_puzzle/$name") ?: continue
            val rec = recognizer.recognizeFrames(listOf(frame), autoBaseGeom(frame.width, frame.height))
            val detected = (0 until 30)
                .filter { (rec.board.tileFlags[it] and TileFlagBits.PUZZLE_1) != 0 }
                .map { (it / 6) to (it % 6) }
                .toSet()
            assertEquals("Video 3 clean frame $name must detect exact 5-cell col 3", expectedCol3, detected)
            assertEquals(5, rec.puzzleCellsDetected)
        }

        // 2) Real ToS Traffic Light screenshot: GO start at (1,5), Red-Burst end at (3,2), 30/30 elements!
        loadDatasetFrame("raw_uploads/images/media_1795321079536_b0156749.jpg")?.let { frame ->
            val rec = recognizer.recognizeFrames(listOf(frame), autoBaseGeom(frame.width, frame.height))
            assertEquals("Real Traffic Light must detect 1 start", 1, rec.trafficLightStartCount)
            assertEquals("Real Traffic Light must detect 1 end", 1, rec.trafficLightEndCount)
            assertTrue("Real Traffic Light start must be (1,5)", (rec.board.tileFlags[1 * 6 + 5] and TileFlagBits.START_ALLOWED) != 0)
            assertTrue("Real Traffic Light end must be (3,2)", (rec.board.tileFlags[3 * 6 + 2] and TileFlagBits.END_REQUIRED) != 0)
            assertEquals("Real Traffic Light (1,5) must be LIGHT", Element.LIGHT, rec.board.cellAt(1, 5).element)
            assertEquals("Real Traffic Light (3,2) must be LIGHT", Element.LIGHT, rec.board.cellAt(3, 2).element)
            assertEquals("Real Traffic Light must have 0 false question orbs", 0, rec.questionOrbsDetected)
            assertEquals("Real Traffic Light must have 0 false puzzle cells", 0, rec.puzzleCellsDetected)
        }

        // 3) Real ToS Traffic Light Video (corner GO at (4,0) Fire orb + right-edge Red Burst at (3,5) Heart orb!)
        val tlVidNames = listOf("t_02600ms.png", "t_02900ms.png", "t_03200ms.png", "t_13200ms.png", "t_13600ms.png")
        for (name in tlVidNames) {
            val frame = loadDatasetFrame("extracted_frames/traffic_light_start_end_vid/$name") ?: continue
            val rec = recognizer.recognizeFrames(listOf(frame), autoBaseGeom(frame.width, frame.height))
            assertEquals("TL video $name must detect 1 start", 1, rec.trafficLightStartCount)
            assertEquals("TL video $name must detect 1 end", 1, rec.trafficLightEndCount)
            assertTrue("TL video $name start must be (4,0)", (rec.board.tileFlags[4 * 6 + 0] and TileFlagBits.START_ALLOWED) != 0)
            assertTrue("TL video $name end must be (3,5)", (rec.board.tileFlags[3 * 6 + 5] and TileFlagBits.END_REQUIRED) != 0)
        }
        // Multi-frame fusion spanning deepest pulse trough + peak must detect exact (4,0) -> (3,5)!
        val troughFrame = loadDatasetFrame("extracted_frames/traffic_light_start_end_vid/t_02300ms.png")
        val peakFrame = loadDatasetFrame("extracted_frames/traffic_light_start_end_vid/t_02600ms.png")
        if (troughFrame != null && peakFrame != null) {
            val multiRec = recognizer.recognizeFrames(listOf(troughFrame, peakFrame), autoBaseGeom(troughFrame.width, troughFrame.height))
            assertEquals(1, multiRec.trafficLightStartCount)
            assertEquals(1, multiRec.trafficLightEndCount)
            assertTrue((multiRec.board.tileFlags[4 * 6 + 0] and TileFlagBits.START_ALLOWED) != 0)
            assertTrue((multiRec.board.tileFlags[3 * 6 + 5] and TileFlagBits.END_REQUIRED) != 0)
        }
    }

    @Test
    fun `barrier terrain dimmed water orb stays WATER and FlashReveal rejects partial dimmed transitions`() {
        // 1) Synthetic Barrier Terrain Water orb (outer HSV: H~210, S~0.43, V~0.62, zero low-sat slate pixels)
        //    must classify as Element.WATER, NOT Question Orb ('?')!
        val cell = 60
        val w = cell * 6
        val h = cell * 5
        val pixels = IntArray(w * h) { 0xFF1A1815.toInt() }
        val baseColors = intArrayOf(
            0xFF288CF0.toInt(), // Water
            0xFFE83A28.toInt(), // Fire
            0xFF34C844.toInt(), // Wood
            0xFFFFD824.toInt(), // Light
            0xFF9C3CF0.toInt(), // Dark
            0xFFFF5CA8.toInt()  // Heart
        )
        for (r in 0 until 5) {
            for (c in 0 until 6) {
                val cx = c * cell + cell * 0.5f
                val cy = r * cell + cell * 0.5f
                val rad = cell * 0.40f
                val col = if (r == 4 && c == 5) {
                    // Exact Barrier Terrain dimmed Water orb (RGB ~ 90, 128, 158 => H=206, S=0.43, V=0.62)
                    0xFF5A809E.toInt()
                } else {
                    baseColors[(r * 2 + c) % baseColors.size]
                }
                for (y in r * cell until (r + 1) * cell) {
                    for (x in c * cell until (c + 1) * cell) {
                        val dx = x - cx
                        val dy = y - cy
                        if (dx * dx + dy * dy <= rad * rad) {
                            pixels[y * w + x] = col
                        }
                    }
                }
            }
        }
        val frame = object : VisionFrame {
            override val width = w
            override val height = h
            override fun getArgb(x: Int, y: Int): Int =
                pixels[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]
        }
        val geom = BoardGeometry(0f, 0f, w.toFloat(), h.toFloat(), 6, 5)
        val recognizer = CvBoardRecognizer()
        val rec = recognizer.recognizeFrames(listOf(frame), geom)
        assertEquals("Barrier-dimmed Water orb at (4,5) must stay WATER", Element.WATER, rec.board.cellAt(4, 5).element)
        assertEquals("Barrier-dimmed Water orb must NOT trigger false '?'", 0, rec.questionOrbsDetected)

        // 2) FlashRevealMemoryTracker must reject partial dimmed transition (9/30 '?') while locking real 25/30 '?'
        val tracker = FlashRevealMemoryTracker()
        val cleanCells = List(30) { idx ->
            CellState(
                element = Element.MATCHABLE[idx % 6],
                confidence = 0.96f
            )
        }
        val cleanBoard = BoardState(6, 5, cleanCells, List(30) { 0 })
        val cleanRes = RecognitionResult(
            board = cleanBoard,
            averageConfidence = 0.96f,
            lowConfidenceIndices = emptyList(),
            puzzleCellsDetected = 0,
            engineName = "test",
            elapsedMs = 4.0
        )
        assertFalse(tracker.onWatchFrame(cleanRes, nowMs = 1_000L))

        val partialDimCells = cleanCells.mapIndexed { idx, c ->
            if (idx < 9) c.copy(hazards = HazardBits.QUESTION) else c
        }
        val partialRes = RecognitionResult(
            board = BoardState(6, 5, partialDimCells, List(30) { 0 }),
            averageConfidence = 0.85f,
            lowConfidenceIndices = emptyList(),
            puzzleCellsDetected = 0,
            engineName = "test",
            elapsedMs = 4.0,
            questionOrbsDetected = 9
        )
        assertFalse(
            "Partial 9/30 '?' (e.g. VICTORY/wave transition dim) must NOT lock flash-reveal overlay",
            tracker.onWatchFrame(partialRes, nowMs = 1_500L)
        )
        assertNull(tracker.currentLockedSnapshot())

        val massQCells = cleanCells.mapIndexed { idx, c ->
            if (idx < 25) c.copy(hazards = HazardBits.QUESTION) else c
        }
        val massQRes = RecognitionResult(
            board = BoardState(6, 5, massQCells, List(30) { 0 }),
            averageConfidence = 0.85f,
            lowConfidenceIndices = emptyList(),
            puzzleCellsDetected = 0,
            engineName = "test",
            elapsedMs = 4.0,
            questionOrbsDetected = 25
        )
        assertTrue(
            "Genuine mass >=60% '?' (25/30) must lock memorized snapshot",
            tracker.onWatchFrame(massQRes, nowMs = 2_000L)
        )
        assertNotNull(tracker.currentLockedSnapshot())

        // 3) Optional local verification on real Barrier Terrain video frames & Burning Tile screenshot (gitignored)
        val mediaRoot = java.io.File("../../backups/user_training_media/20261122_barrier_burning_victory")
        if (mediaRoot.exists()) {
            fun loadLocal(rel: String): VisionFrame? {
                val f = java.io.File(mediaRoot, rel)
                if (!f.exists()) return null
                val imageIo = Class.forName("javax.imageio.ImageIO")
                val img = imageIo.getMethod("read", java.io.File::class.java).invoke(null, f)
                val imgCls = img.javaClass
                val iw = imgCls.getMethod("getWidth").invoke(img) as Int
                val ih = imgCls.getMethod("getHeight").invoke(img) as Int
                val getRgb = imgCls.getMethod("getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                return object : VisionFrame {
                    override val width = iw
                    override val height = ih
                    override fun getArgb(x: Int, y: Int): Int =
                        getRgb.invoke(img, x.coerceIn(0, width - 1), y.coerceIn(0, height - 1)) as Int
                }
            }
            fun autoBase(iw: Int, ih: Int): BoardGeometry {
                val gameH = iw * (16f / 9f)
                val letterbox = ((ih - gameH) * 0.5f).coerceAtLeast(0f)
                val bottomPx = (ih - letterbox + gameH * 0.004f).coerceAtMost(ih.toFloat())
                val c = iw / 6f
                val bot = bottomPx.coerceIn(c * 5f, ih.toFloat())
                return BoardGeometry(0f, (bot - c * 5f).coerceAtLeast(0f), iw.toFloat(), bot, 6, 5)
            }
            val bFrames = listOf("barrier_frames/t_08500ms.png", "barrier_frames/t_09500ms.png", "barrier_frames/t_10500ms.png").mapNotNull(::loadLocal)
            if (bFrames.size == 3) {
                val brec = recognizer.recognizeFrames(bFrames, autoBase(bFrames[0].width, bFrames[0].height))
                assertEquals("Real Barrier Terrain (4,5) must be WATER", Element.WATER, brec.board.cellAt(4, 5).element)
                assertEquals("Real Barrier Terrain must have 0 false question orbs", 0, brec.questionOrbsDetected)
                assertEquals("Real Barrier Terrain Traffic Light start count", 1, brec.trafficLightStartCount)
                assertEquals("Real Barrier Terrain Traffic Light end count", 1, brec.trafficLightEndCount)
                assertTrue("Real Barrier Terrain GO start at (3,2)", (brec.board.tileFlags[3 * 6 + 2] and TileFlagBits.START_ALLOWED) != 0)
                assertTrue("Real Barrier Terrain Red end at (2,0)", (brec.board.tileFlags[2 * 6 + 0] and TileFlagBits.END_REQUIRED) != 0)
                assertTrue("Neighbor (3,0) must NOT falsely grab END_REQUIRED via vertical bleed", (brec.board.tileFlags[3 * 6 + 0] and TileFlagBits.END_REQUIRED) == 0)
            }
            loadLocal("burning_tile_board.jpg")?.let { bf ->
                val burnRec = recognizer.recognizeFrames(listOf(bf), autoBase(bf.width, bf.height))
                assertEquals("Real Burning Tile (1,0) underlying orb must be LIGHT", Element.LIGHT, burnRec.board.cellAt(1, 0).element)
                assertEquals("Real Burning Tile board must have 0 false question orbs", 0, burnRec.questionOrbsDetected)
                assertTrue("Real Burning Tile average confidence >= 0.90", burnRec.averageConfidence >= 0.90f)
            }
        }
    }

    @Test
    fun `detects BloodDrain Heart and Wood orbs accurately without misclassifying as Dark or Enchanted`() {
        // 1) Synthetic BloodDrain Heart (pale lilac shell + mauve core) + BloodDrain Wood (muted olive + dark vines)
        val cell = 80
        val w = cell * 6
        val h = cell * 5
        val pixels = IntArray(w * h) { 0xFF1A1614.toInt() }
        val normalPalette = intArrayOf(
            0xFF22A0FF.toInt(), // Water
            0xFFFF3A28.toInt(), // Fire
            0xFF34C844.toInt(), // Wood
            0xFFFFDA24.toInt(), // Light
            0xFFD438F4.toInt(), // Dark
            0xFFFF5CA8.toInt()  // Heart
        )
        for (r in 0 until 5) {
            for (c in 0 until 6) {
                val cx = c * cell + cell * 0.5f
                val cy = r * cell + cell * 0.5f
                val rad = cell * 0.42f
                for (y in r * cell until (r + 1) * cell) {
                    for (x in c * cell until (c + 1) * cell) {
                        val d = hypot((x - cx).toDouble(), (y - cy).toDouble()).toFloat()
                        if (d > rad) continue
                        pixels[y * w + x] = when {
                            r == 0 && c == 0 -> {
                                // BloodDrain Heart: mauve heart core up to 0.52*r (H~302, S~0.27, V~0.54) + pale lilac outer shell (S~0.14, V~0.72) + dark vines
                                when {
                                    d < rad * 0.52f -> 0xFF8A6588.toInt()
                                    (x + y) % 5 == 0 -> 0xFF665664.toInt()
                                    else -> 0xFFB8A6B6.toInt()
                                }
                            }
                            r == 0 && c == 3 -> {
                                // BloodDrain Wood: muted olive stone (H~112, S~0.28, V~0.42) + dark thorny vines
                                if ((x * 3 + y) % 5 == 0) 0xFF475744.toInt() else 0xFF5A7356.toInt()
                            }
                            else -> normalPalette[(r * 2 + c) % normalPalette.size]
                        }
                    }
                }
            }
        }
        val frame = object : VisionFrame {
            override val width = w
            override val height = h
            override fun getArgb(x: Int, y: Int): Int =
                pixels[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]
        }
        val recognizer = CvBoardRecognizer()
        val rec = recognizer.recognizeFrames(listOf(frame), BoardGeometry(0f, 0f, w.toFloat(), h.toFloat(), 6, 5))

        val bh = rec.board.cellAt(0, 0)
        assertEquals("Synthetic BloodDrain Heart must classify as HEART (not DARK!)", Element.HEART, bh.element)
        assertTrue("Synthetic BloodDrain Heart must carry BLOOD_DRAIN hazard", (bh.hazards and HazardBits.BLOOD_DRAIN) != 0)
        assertEquals("Synthetic BloodDrain Heart must not false-trigger ENCHANTED", Variant.NORMAL, bh.variant)

        val bg = rec.board.cellAt(0, 3)
        assertEquals("Synthetic BloodDrain Wood must classify as WOOD", Element.WOOD, bg.element)
        assertTrue("Synthetic BloodDrain Wood must carry BLOOD_DRAIN hazard", (bg.hazards and HazardBits.BLOOD_DRAIN) != 0)

        for (r in 0 until 5) {
            for (c in 0 until 6) {
                if ((r == 0 && c == 0) || (r == 0 && c == 3)) continue
                assertEquals(
                    "Normal cell ($r,$c) must NOT false-trigger BLOOD_DRAIN",
                    0,
                    rec.board.cellAt(r, c).hazards and HazardBits.BLOOD_DRAIN
                )
            }
        }

        // 2) Optional local verification on real ToS BloodDrain video frame (30/30 elements + exact 8/8 BloodDrain orbs!)
        val bloodFile = java.io.File("../../backups/user_training_media/20261122_blood_drain/frames/t_01688ms.png")
        if (bloodFile.exists()) {
            val imageIo = Class.forName("javax.imageio.ImageIO")
            val img = imageIo.getMethod("read", java.io.File::class.java).invoke(null, bloodFile)
            val imgCls = img.javaClass
            val iw = imgCls.getMethod("getWidth").invoke(img) as Int
            val ih = imgCls.getMethod("getHeight").invoke(img) as Int
            val getRgb = imgCls.getMethod("getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            val vf = object : VisionFrame {
                override val width = iw
                override val height = ih
                override fun getArgb(x: Int, y: Int): Int =
                    getRgb.invoke(img, x.coerceIn(0, width - 1), y.coerceIn(0, height - 1)) as Int
            }
            val gameH = iw * (16f / 9f)
            val lb = ((ih - gameH) * 0.5f).coerceAtLeast(0f)
            val botPx = (ih - lb + gameH * 0.004f).coerceAtMost(ih.toFloat())
            val cSize = iw / 6f
            val bot = botPx.coerceIn(cSize * 5f, ih.toFloat())
            val geom = BoardGeometry(0f, (bot - cSize * 5f).coerceAtLeast(0f), iw.toFloat(), bot, 6, 5)
            val brec = recognizer.recognizeFrames(listOf(vf), geom)

            val expectedElements = arrayOf(
                arrayOf(Element.HEART, Element.DARK, Element.WATER, Element.WOOD, Element.LIGHT, Element.LIGHT),
                arrayOf(Element.WATER, Element.WATER, Element.DARK, Element.LIGHT, Element.FIRE, Element.WATER),
                arrayOf(Element.DARK, Element.DARK, Element.LIGHT, Element.WOOD, Element.WOOD, Element.DARK),
                arrayOf(Element.FIRE, Element.FIRE, Element.WATER, Element.HEART, Element.DARK, Element.HEART),
                arrayOf(Element.DARK, Element.DARK, Element.HEART, Element.WATER, Element.WATER, Element.HEART)
            )
            val expectedBlood = setOf(
                0 to 0, 0 to 3, 2 to 3, 2 to 4, 3 to 3, 3 to 5, 4 to 2, 4 to 5
            )
            for (r in 0 until 5) {
                for (c in 0 until 6) {
                    val cellState = brec.board.cellAt(r, c)
                    assertEquals("Real BloodDrain ($r,$c) element mismatch", expectedElements[r][c], cellState.element)
                    val isBlood = (cellState.hazards and HazardBits.BLOOD_DRAIN) != 0
                    assertEquals("Real BloodDrain ($r,$c) hazard flag mismatch", (r to c) in expectedBlood, isBlood)
                    if ((r to c) in expectedBlood) {
                        assertNotEquals("Real BloodDrain ($r,$c) must not false-trigger ENCHANTED", Variant.ENCHANTED, cellState.variant)
                    }
                }
            }
            assertEquals("Real BloodDrain board must have 0 false question orbs", 0, brec.questionOrbsDetected)
        }
    }

    @Test
    fun `detects Petrified (#04), Full-Board 30-of-30 Stone-Locked (incl Light), and Thorn runestones with zero false Heart Race badges`() {
        val recognizer = CvBoardRecognizer()

        fun loadImg(relPath: String): Pair<VisionFrame, BoardGeometry>? {
            val f = java.io.File(relPath)
            if (!f.exists()) return null
            val imageIo = Class.forName("javax.imageio.ImageIO")
            val img = imageIo.getMethod("read", java.io.File::class.java).invoke(null, f)
            val cls = img.javaClass
            val iw = cls.getMethod("getWidth").invoke(img) as Int
            val ih = cls.getMethod("getHeight").invoke(img) as Int
            val getRgb = cls.getMethod("getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            val vf = object : VisionFrame {
                override val width = iw
                override val height = ih
                override fun getArgb(x: Int, y: Int): Int =
                    getRgb.invoke(img, x.coerceIn(0, width - 1), y.coerceIn(0, height - 1)) as Int
            }
            val gameH = iw * (16f / 9f)
            val lb = ((ih - gameH) * 0.5f).coerceAtLeast(0f)
            val botPx = (ih - lb + gameH * 0.004f).coerceAtMost(ih.toFloat())
            val cSize = iw / 6f
            val bot = botPx.coerceIn(cSize * 5f, ih.toFloat())
            val geom = BoardGeometry(0f, (bot - cSize * 5f).coerceAtLeast(0f), iw.toFloat(), bot, 6, 5)
            return vf to geom
        }

        // 1) Deterministic synthetic Petrified + Thorn + Locked-Light board test (always runs in CI)
        val cell = 100
        val syn = SyntheticBoardFrame(6 * cell, 5 * cell)
        val palette = mapOf(
            Element.WATER to 0xFF1E9CFF.toInt(),
            Element.FIRE to 0xFFFF3020.toInt(),
            Element.WOOD to 0xFF2EC840.toInt(),
            Element.LIGHT to 0xFFFFDF20.toInt(),
            Element.DARK to 0xFF8C32E6.toInt(),
            Element.HEART to 0xFFFF5CA8.toInt()
        )
        for (r in 0 until 5) {
            for (c in 0 until 6) {
                val idx = r * 6 + c
                val x0 = c * cell
                val y0 = r * cell
                val cx = x0 + cell / 2
                val cy = y0 + cell / 2
                when (idx) {
                    2 -> {
                        // Petrified orb: dark slate-navy stone + crimson-orange lava cracks
                        syn.drawCell(x0, y0, cell, 0xFF1B243A.toInt())
                        for (dy in -32..32) {
                            for (dx in -32..32) {
                                if (hypot(dx.toDouble(), dy.toDouble()) <= 36.0 &&
                                    ((dx + dy) % 9 in -2..2 || (dx - 2 * dy) % 11 in -1..1)
                                ) {
                                    syn.pixels[(cy + dy) * syn.width + (cx + dx)] = 0xFFE63018.toInt()
                                }
                            }
                        }
                    }
                    10 -> {
                        // Thorn Heart orb: bright pink heart + woody-brown horizontal thorn vine across mid-band
                        syn.drawCell(x0, y0, cell, palette.getValue(Element.HEART))
                        for (dy in -8..8) {
                            for (dx in -38..38) {
                                if (kotlin.math.abs(dy) <= 5 || (dx % 7 == 0 && kotlin.math.abs(dy) <= 8)) {
                                    syn.pixels[(cy + dy) * syn.width + (cx + dx)] = 0xFF6B421B.toInt()
                                }
                            }
                        }
                    }
                    15 -> {
                        // Locked Light orb: muted dark olive-khaki body
                        syn.drawCell(x0, y0, cell, 0xFF64582C.toInt())
                    }
                    else -> {
                        syn.drawCell(x0, y0, cell, palette.getValue(Element.MATCHABLE[idx % 6]))
                    }
                }
            }
        }
        val synRec = recognizer.recognizeFrames(listOf(syn), BoardGeometry(0f, 0f, 600f, 500f, 6, 5))
        assertTrue("Synthetic cell 2 must detect PETRIFIED", (synRec.board.cells[2].hazards and HazardBits.PETRIFIED) != 0)
        assertEquals(1, synRec.petrifiedOrbsDetected)
        assertEquals("Synthetic cell 10 must preserve HEART element", Element.HEART, synRec.board.cells[10].element)
        assertTrue("Synthetic cell 10 must detect THORN", (synRec.board.cells[10].hazards and HazardBits.THORN) != 0)
        assertEquals(1, synRec.thornOrbsDetected)
        assertEquals("Synthetic cell 15 must classify Locked Light as LIGHT", Element.LIGHT, synRec.board.cells[15].element)
        assertTrue("Synthetic cell 15 must detect BLOOD_DRAIN/LOCKED", (synRec.board.cells[15].hazards and HazardBits.BLOOD_DRAIN) != 0)

        // 2) Optional real-game frame verification: Video 2 Petrified (#04 石化)
        loadImg("../../backups/user_training_media/20261123_petrified_locked_thorn/v2_petrified.png")?.let { (vf, g) ->
            val rec = recognizer.recognizeFrames(listOf(vf), g)
            val expectedPetrified = setOf(0 to 2, 4 to 2, 4 to 4)
            for (r in 0 until 5) {
                for (c in 0 until 6) {
                    val isP = (rec.board.cellAt(r, c).hazards and HazardBits.PETRIFIED) != 0
                    assertEquals("Real Video 2 ($r,$c) PETRIFIED mismatch", (r to c) in expectedPetrified, isP)
                }
            }
            assertEquals(3, rec.petrifiedOrbsDetected)
            assertEquals(0, rec.thornOrbsDetected)
        }

        // 3) Optional real-game frame verification: Video 3 Full-Board 30/30 Stone-Locked (全版鎖定)
        loadImg("../../backups/user_training_media/20261123_petrified_locked_thorn/v3_locked_all30.png")?.let { (vf, g) ->
            val rec = recognizer.recognizeFrames(listOf(vf), g)
            val expected = arrayOf(
                arrayOf(Element.HEART, Element.LIGHT, Element.HEART, Element.WOOD, Element.WATER, Element.HEART),
                arrayOf(Element.HEART, Element.DARK, Element.DARK, Element.WATER, Element.WOOD, Element.WOOD),
                arrayOf(Element.WOOD, Element.FIRE, Element.LIGHT, Element.WATER, Element.WATER, Element.LIGHT),
                arrayOf(Element.WOOD, Element.FIRE, Element.HEART, Element.HEART, Element.FIRE, Element.WOOD),
                arrayOf(Element.WATER, Element.WOOD, Element.LIGHT, Element.WATER, Element.LIGHT, Element.WATER)
            )
            for (r in 0 until 5) {
                for (c in 0 until 6) {
                    val cs = rec.board.cellAt(r, c)
                    assertEquals("Real Video 3 ($r,$c) element mismatch", expected[r][c], cs.element)
                    assertTrue("Real Video 3 ($r,$c) must flag BLOOD_DRAIN/LOCKED", (cs.hazards and HazardBits.BLOOD_DRAIN) != 0)
                }
            }
            assertEquals(30, rec.bloodDrainOrbsDetected)
            assertEquals(0, rec.questionOrbsDetected)
        }

        // 4) Optional real-game frame verification: Image 1 Thorn Runestones (荊棘符石) & Zero False Heart Race Badge
        loadImg("../../backups/user_training_media/20261123_petrified_locked_thorn/img1_thorn.jpg")?.let { (vf, g) ->
            val rec = recognizer.recognizeFrames(listOf(vf), g)
            val expectedThorn = setOf(1 to 4, 2 to 3)
            for (r in 0 until 5) {
                for (c in 0 until 6) {
                    val cs = rec.board.cellAt(r, c)
                    val isTh = (cs.hazards and HazardBits.THORN) != 0
                    assertEquals("Real Image 1 ($r,$c) THORN mismatch", (r to c) in expectedThorn, isTh)
                    assertNotEquals("Real Image 1 ($r,$c) must NOT false-trigger RACE badge", Variant.RACE, cs.variant)
                }
            }
            assertEquals(Element.HEART, rec.board.cellAt(1, 4).element)
            assertEquals(Element.LIGHT, rec.board.cellAt(2, 3).element)
            assertEquals(2, rec.thornOrbsDetected)
            assertEquals(0, rec.raceBadgesDetected)
        }

        // 5) Optional real-game Video 2 verification: Heavy Demon-Race + Enchanted + Traffic Light on 1/584001 team!
        loadImg("../../backups/user_training_media/20261123_banner_emptyhp_race/v2_00s.png")?.let { (vf, g) ->
            val rec = recognizer.recognizeFrames(listOf(vf), g)
            val expected00 = parseGrid(
                "L D G F H D",
                "D D W D D L",
                "D W D D G L",
                "F W D F L F",
                "D D F H F F"
            )
            for (i in 0 until 30) {
                assertEquals("Video 2 v2_00s (${i / 6},${i % 6}) element mismatch", expected00[i], rec.board.cells[i].element)
            }
            assertTrue("Video 2 v2_00s Demon-Race badges must detect >= 25/30 (actual=${rec.raceBadgesDetected})", rec.raceBadgesDetected >= 25)
            assertEquals("Video 2 v2_00s must detect exactly 1 GO start", 1, rec.trafficLightStartCount)
            assertEquals("Video 2 v2_00s must detect exactly 1 Red Vortex end", 1, rec.trafficLightEndCount)
            assertTrue("Video 2 v2_00s start at (3,5)", (rec.board.tileFlags[3 * 6 + 5] and TileFlagBits.START_ALLOWED) != 0)
            assertTrue("Video 2 v2_00s end at (1,1)", (rec.board.tileFlags[1 * 6 + 1] and TileFlagBits.END_REQUIRED) != 0)
            assertEquals(0, rec.questionOrbsDetected)
        }

        loadImg("../../backups/user_training_media/20261123_banner_emptyhp_race/v2_86s.png")?.let { (vf, _) ->
            // Exact board geometry snapped from Player 1-HP bar (top=683, bot=1163 on 576x1280)
            val exactG = BoardGeometry(0f, 683f, 576f, 1163f, 6, 5)
            val rec = recognizer.recognizeFrames(listOf(vf), exactG)
            val expected86 = parseGrid(
                "L D W L D W",
                "L D W L D W",
                "L D W L D W",
                "L D W L D W",
                "L D W L D W"
            )
            for (i in 0 until 30) {
                assertEquals("Video 2 v2_86s (${i / 6},${i % 6}) element mismatch", expected86[i], rec.board.cells[i].element)
            }
            assertTrue("Video 2 v2_86s Demon-Race badges must detect >= 25/30 (actual=${rec.raceBadgesDetected})", rec.raceBadgesDetected >= 25)
            assertEquals("Video 2 v2_86s must detect exactly 1 GO start", 1, rec.trafficLightStartCount)
            assertEquals("Video 2 v2_86s must detect exactly 1 Red Vortex end", 1, rec.trafficLightEndCount)
            assertTrue("Video 2 v2_86s start at (2,4)", (rec.board.tileFlags[2 * 6 + 4] and TileFlagBits.START_ALLOWED) != 0)
            assertTrue("Video 2 v2_86s end at (0,4)", (rec.board.tileFlags[0 * 6 + 4] and TileFlagBits.END_REQUIRED) != 0)
            assertEquals(0, rec.questionOrbsDetected)
        }
    }

    @Test
    fun `detects electrified, frozen, ice-sealed, bomb, evil-energy, purgatory, burning, bloodbath, and shattered tiles with zero false positives`() {
        val cell = 90
        val cols = 6
        val rows = 5
        val frame = SyntheticBoardFrame(cols * cell, rows * cell)
        val palette = mapOf(
            Element.WATER to 0xFF1E9CFF.toInt(),
            Element.FIRE to 0xFFFF3020.toInt(),
            Element.WOOD to 0xFF2EC840.toInt(),
            Element.LIGHT to 0xFFFFDF20.toInt(),
            Element.DARK to 0xFF8C32E6.toInt(),
            Element.HEART to 0xFFFF5CA8.toInt()
        )
        // Base 30 normal orbs
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val idx = r * cols + c
                frame.drawCell(c * cell, r * cell, cell, palette.getValue(Element.MATCHABLE[idx % 6]))
            }
        }

        fun paintCell(r: Int, c: Int, fn: (dx: Float, dy: Float, rr: Float) -> Int?) {
            val x0 = c * cell
            val y0 = r * cell
            val cx = x0 + cell * 0.5f
            val cy = y0 + cell * 0.5f
            for (y in y0 until y0 + cell) {
                for (x in x0 until x0 + cell) {
                    val dx = (x - cx) / cell
                    val dy = (y - cy) / cell
                    val rr = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                    fn(dx, dy, rr)?.let { frame.pixels[y * frame.width + x] = it }
                }
            }
        }

        // (0,0) Electrified Water (peak white-hot glare + electric cyan border/corners + water rim)
        paintCell(0, 0) { dx, dy, rr ->
            val linf = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
            when {
                rr <= 0.28f -> 0xFFF5FCFF.toInt()
                rr <= 0.38f -> 0xFF28A4FF.toInt()
                linf >= 0.38f -> 0xFF36B8FF.toInt()
                else -> null
            }
        }
        // (0,1) Electrified Light (white-hot glare + golden rim + electric cyan outer arcs -> must recover LIGHT!)
        paintCell(0, 1) { dx, dy, rr ->
            val linf = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
            when {
                rr <= 0.28f -> 0xFFFAF9F4.toInt()
                rr <= 0.37f -> 0xFFFFCC18.toInt()
                linf >= 0.39f -> 0xFF42B8FF.toInt()
                else -> null
            }
        }
        // (0,2) Frozen (square pale cyan-white faceted crystal block filling all 4 corners!)
        paintCell(0, 2) { dx, dy, _ ->
            val facet = (kotlin.math.floor((dx + 0.5f) * 8f).toInt() + kotlin.math.floor((dy + 0.5f) * 8f).toInt()) and 1
            if (facet == 0) 0xFFBCD0E6.toInt() else 0xFF4E86F0.toInt()
        }
        // (0,3) Ice-Sealed (square purple faceted crystal block with crisp white veins filling corners!)
        paintCell(0, 3) { dx, dy, _ ->
            val vein = (((dx * 18).toInt() + (dy * 18).toInt()) % 3) == 0
            if (vein) 0xFFF2EAFF.toInt() else 0xFF9C28E8.toInt()
        }
        // (0,4) Purgatory Fire (blinding white-hot center + vivid neon magenta/purple halo)
        paintCell(0, 4) { _, _, rr ->
            when {
                rr <= 0.34f -> 0xFFFFFAFE.toInt()
                rr <= 0.46f -> 0xFFD030E8.toInt()
                else -> null
            }
        }
        // (0,5) Bomb (vivid red circular core r<=0.14 inside matte grey metallic hex ring r in 0.17..0.36 + Water outer rim)
        paintCell(0, 5) { _, _, rr ->
            when {
                rr <= 0.14f -> 0xFFFF1818.toInt()
                rr <= 0.35f -> 0xFF7A7D82.toInt()
                rr <= 0.42f -> 0xFF1E9CFF.toInt()
                else -> null
            }
        }
        // (1,0) Evil Energy (pitch-dark shadowed orb + soft glowing cyan-teal wisps V~0.55)
        paintCell(1, 0) { dx, dy, rr ->
            val wisp = (kotlin.math.abs(dx - 0.12f) < 0.07f && kotlin.math.abs(dy - 0.08f) < 0.07f) ||
                (kotlin.math.abs(dx + 0.14f) < 0.07f && kotlin.math.abs(dy + 0.10f) < 0.07f)
            when {
                wisp -> 0xFF34888C.toInt()
                rr <= 0.40f -> 0xFF181E24.toInt()
                else -> null
            }
        }
        // (1,1) Burning Tile (fiery bright orange-gold flame igniting all 4 corners & tile border around a bright orb)
        paintCell(1, 1) { dx, dy, rr ->
            val linf = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
            when {
                linf >= 0.34f -> 0xFFFF8C18.toInt()
                rr <= 0.34f -> 0xFFFFE448.toInt()
                else -> 0xFFFF9A20.toInt()
            }
        }
        // (1,2) Bloodbath Tile (deep dark crimson-maroon blood pool drowning the tile)
        paintCell(1, 2) { _, _, _ -> 0xFF48141E.toInt() }
        // (1,3) Shattered Tile (pitch-black void with thin medium-silver radial spiderweb cracks)
        paintCell(1, 3) { dx, dy, _ ->
            val onCrack = kotlin.math.abs(dx - dy) < 0.025f || kotlin.math.abs(dx + dy) < 0.025f
            if (onCrack) 0xFF6E747A.toInt() else 0xFF0A0C0E.toInt()
        }

        val geom = BoardGeometry(0f, 0f, (cols * cell).toFloat(), (rows * cell).toFloat(), cols, rows)
        val recognizer = CvBoardRecognizer()
        val rec = recognizer.recognizeFrames(listOf(frame), geom)

        // Verify all 10 positive cells
        assertTrue("(0,0) must detect ELECTRIFIED", (rec.board.cellAt(0, 0).hazards and HazardBits.ELECTRIFIED) != 0)
        assertEquals("(0,0) Electrified Water must recover WATER", Element.WATER, rec.board.cellAt(0, 0).element)
        assertTrue("(0,1) must detect ELECTRIFIED", (rec.board.cellAt(0, 1).hazards and HazardBits.ELECTRIFIED) != 0)
        assertEquals("(0,1) Electrified Light must recover LIGHT", Element.LIGHT, rec.board.cellAt(0, 1).element)
        assertTrue("(0,2) must detect FROZEN", (rec.board.cellAt(0, 2).hazards and HazardBits.FROZEN) != 0)
        assertTrue("(0,3) must detect ICE_SEALED", (rec.board.cellAt(0, 3).hazards and HazardBits.ICE_SEALED) != 0)
        assertTrue("(0,4) must detect PURGATORY_FIRE", (rec.board.cellAt(0, 4).hazards and HazardBits.PURGATORY_FIRE) != 0)
        assertTrue("(0,5) must detect BOMB", (rec.board.cellAt(0, 5).hazards and HazardBits.BOMB) != 0)
        assertEquals("(0,5) Bomb must recover underlying WATER from outer rim", Element.WATER, rec.board.cellAt(0, 5).element)
        assertTrue("(1,0) must detect EVIL_ENERGY", (rec.board.cellAt(1, 0).hazards and HazardBits.EVIL_ENERGY) != 0)
        assertTrue("(1,1) must detect BURNING_TILE", (rec.board.tileFlags[1 * 6 + 1] and TileFlagBits.BURNING_TILE) != 0)
        assertTrue("(1,2) must detect END_FORBIDDEN (Bloodbath)", (rec.board.tileFlags[1 * 6 + 2] and TileFlagBits.END_FORBIDDEN) != 0)
        assertTrue("(1,3) must detect SHATTERED", (rec.board.tileFlags[1 * 6 + 3] and TileFlagBits.SHATTERED) != 0)
        assertEquals("(1,3) Shattered cell must be Element.EMPTY", Element.EMPTY, rec.board.cellAt(1, 3).element)

        // Verify ZERO false positives across all remaining 20 normal cells & zero false '?' Question Orbs!
        assertEquals(0, rec.questionOrbsDetected)
        assertEquals(2, rec.electrifiedOrbsDetected)
        assertEquals(2, rec.frozenOrbsDetected)
        assertEquals(1, rec.purgatoryFireOrbsDetected)
        assertEquals(1, rec.bombOrbsDetected)
        assertEquals(1, rec.evilEnergyOrbsDetected)
        assertEquals(1, rec.burningTilesDetected)
        assertEquals(1, rec.bloodbathTilesDetected)
        assertEquals(1, rec.shatteredTilesDetected)
        for (i in 10 until 30) {
            assertEquals("Normal cell $i must have ZERO false hazards", HazardBits.NONE, rec.board.cells[i].hazards)
            assertEquals("Normal cell $i must have ZERO false terrains", TileFlagBits.NONE, rec.board.tileFlags[i])
        }

        // Optional local real-game verification on gitignored Electrified & Burning user screenshots
        fun loadLocalBoard(relPath: String): Pair<VisionFrame, BoardGeometry>? {
            val file = java.io.File(relPath)
            if (!file.exists()) return null
            val imageIo = Class.forName("javax.imageio.ImageIO")
            val img = imageIo.getMethod("read", java.io.File::class.java).invoke(null, file)
            val cls = img.javaClass
            val w = cls.getMethod("getWidth").invoke(img) as Int
            val h = cls.getMethod("getHeight").invoke(img) as Int
            val getRgb = cls.getMethod("getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            val vf = object : VisionFrame {
                override val width: Int = w
                override val height: Int = h
                override fun getArgb(x: Int, y: Int): Int =
                    getRgb.invoke(img, x.coerceIn(0, w - 1), y.coerceIn(0, h - 1)) as Int
            }
            val gameH = w * (16f / 9f)
            val letterbox = maxOf(0f, (h - gameH) * 0.5f)
            val bot = minOf(h.toFloat(), h - letterbox + gameH * 0.004f)
            val cw = w / 6f
            val top = maxOf(0f, bot - cw * 5f)
            return vf to BoardGeometry(0f, top, w.toFloat(), bot, 6, 5)
        }

        loadLocalBoard("../../backups/user_training_media/20261123_electrified/electrified_board.jpg")?.let { (vf, g) ->
            val r = recognizer.recognizeFrames(listOf(vf), g)
            assertEquals("Real Electrified screenshot must detect exactly 2 electrified orbs", 2, r.electrifiedOrbsDetected)
            assertTrue("Real (1,1) must flag ELECTRIFIED", (r.board.cellAt(1, 1).hazards and HazardBits.ELECTRIFIED) != 0)
            assertTrue("Real (2,5) must flag ELECTRIFIED", (r.board.cellAt(2, 5).hazards and HazardBits.ELECTRIFIED) != 0)
            assertEquals("Real (1,1) Electrified Water must recover WATER", Element.WATER, r.board.cellAt(1, 1).element)
            assertEquals("Real (2,5) Electrified Light must recover LIGHT", Element.LIGHT, r.board.cellAt(2, 5).element)
            assertEquals("Electrified orbs must NEVER be misclassified as '?'", 0, r.questionOrbsDetected)
        }

        loadLocalBoard("../../backups/user_training_media/20261122_barrier_burning_victory/burning_tile_board.jpg")?.let { (vf, g) ->
            val r = recognizer.recognizeFrames(listOf(vf), g)
            assertEquals("Real Burning Tile screenshot must detect exactly 1 burning tile", 1, r.burningTilesDetected)
            assertTrue("Real (1,0) must flag BURNING_TILE", (r.board.tileFlags[1 * 6 + 0] and TileFlagBits.BURNING_TILE) != 0)
        }
    }

    @Test
    fun `full 6-color Cartesian product detects Electrified, Frozen, Ice-Sealed, Bomb, Evil Energy, Purgatory, and Burning across all 6 elements`() {
        val cell = 90
        val cols = 6
        val rows = 5
        val recognizer = CvBoardRecognizer()
        val geom = BoardGeometry(0f, 0f, (cols * cell).toFloat(), (rows * cell).toFloat(), cols, rows)

        val elementColors = listOf(
            Element.WATER to 0xFF1E9CFF.toInt(),
            Element.FIRE to 0xFFE8261E.toInt(),
            Element.WOOD to 0xFF28B83C.toInt(),
            Element.LIGHT to 0xFFFFD420.toInt(),
            Element.DARK to 0xFF8C2CE0.toInt(),
            Element.HEART to 0xFFF44E9C.toInt()
        )
        // Darker variants (testing color-invariance on darker Water/Wood/Dark orbs on Burning & Electrified dim-arc!)
        val dimElementColors = mapOf(
            Element.WATER to 0xFF1874C8.toInt(),
            Element.FIRE to 0xFFC2221A.toInt(),
            Element.WOOD to 0xFF228C32.toInt(),
            Element.LIGHT to 0xFFD8B21C.toInt(),
            Element.DARK to 0xFF6A20B0.toInt(),
            Element.HEART to 0xFFC83E80.toInt()
        )
        val darkenedEvilColors = mapOf(
            Element.WATER to 0xFF142230.toInt(),
            Element.FIRE to 0xFF2E1414.toInt(),
            Element.WOOD to 0xFF142818.toInt(),
            Element.LIGHT to 0xFF3E3818.toInt(), // brighter darkened Light (V~0.24)
            Element.DARK to 0xFF221430.toInt(),
            Element.HEART to 0xFF441E30.toInt()  // brighter darkened Heart (V~0.27)
        )

        fun buildBaseFrame(): SyntheticBoardFrame {
            val f = SyntheticBoardFrame(cols * cell, rows * cell)
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    f.drawCell(c * cell, r * cell, cell, elementColors[c].second)
                }
            }
            return f
        }

        fun paintCell(f: SyntheticBoardFrame, r: Int, c: Int, fn: (dx: Float, dy: Float, rr: Float) -> Int?) {
            val x0 = c * cell
            val y0 = r * cell
            val cx = x0 + cell * 0.5f
            val cy = y0 + cell * 0.5f
            for (y in y0 until y0 + cell) {
                for (x in x0 until x0 + cell) {
                    val dx = (x - cx) / cell
                    val dy = (y - cy) / cell
                    val rr = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                    fn(dx, dy, rr)?.let { f.pixels[y * f.width + x] = it }
                }
            }
        }

        // Board A: Row 0 = Electrified (Peak Flash), Row 1 = Electrified (Dim Arc), Row 2 = Frozen (6 colors!),
        //          Row 3 = Bomb (6 colors!), Row 4 = Normal control row (must have 0 false hazards!)
        val boardA = buildBaseFrame()
        for (c in 0 until 6) {
            val (el, baseColor) = elementColors[c]
            val dimColor = dimElementColors.getValue(el)

            // Row 0: Electrified Peak Flash × 6 colors
            paintCell(boardA, 0, c) { dx, dy, rr ->
                val linf = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                when {
                    rr <= 0.27f -> 0xFFF7FBFF.toInt()
                    rr <= 0.37f -> baseColor
                    linf >= 0.39f -> 0xFF38B8FF.toInt()
                    else -> null
                }
            }

            // Row 1: Electrified Dim Arc Phase × 6 colors (even dark Wood/Dark/Fire at V~0.65..0.78!)
            paintCell(boardA, 1, c) { dx, dy, rr ->
                val linf = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                when {
                    rr <= 0.16f -> 0xFFF2FAFF.toInt()
                    rr <= 0.37f -> dimColor
                    linf >= 0.385f -> 0xFF34B4FF.toInt()
                    else -> null
                }
            }

            // Row 2: Color-Invariant Frozen Square Crystal × 6 colors (including Dark!)
            paintCell(boardA, 2, c) { dx, dy, _ ->
                val facet = (kotlin.math.floor((dx + 0.5f) * 8f).toInt() + kotlin.math.floor((dy + 0.5f) * 8f).toInt()) and 1
                if (facet == 0) 0xFFCCD6E4.toInt() else baseColor
            }

            // Row 3: Bomb × 6 colors (red core + grey metallic hex casing + 6-color outer rim)
            paintCell(boardA, 3, c) { _, _, rr ->
                when {
                    rr <= 0.14f -> 0xFFFF1818.toInt()
                    rr <= 0.35f -> 0xFF787C82.toInt()
                    rr <= 0.42f -> baseColor
                    else -> null
                }
            }
        }

        val recA = recognizer.recognizeFrames(listOf(boardA), geom)
        for (c in 0 until 6) {
            val el = elementColors[c].first
            // Row 0: Electrified Peak Flash + exact underlying element recovery
            val r0 = recA.board.cellAt(0, c)
            assertTrue("Electrified Peak ($el) must flag ELECTRIFIED", (r0.hazards and HazardBits.ELECTRIFIED) != 0)
            assertEquals("Electrified Peak ($el) must recover $el", el, r0.element)

            // Row 1: Electrified Dim Arc + exact underlying element recovery
            val r1 = recA.board.cellAt(1, c)
            assertTrue("Electrified DimArc ($el) must flag ELECTRIFIED", (r1.hazards and HazardBits.ELECTRIFIED) != 0)
            assertEquals("Electrified DimArc ($el) must recover $el", el, r1.element)

            // Row 2: Color-Invariant Frozen × 6 colors (even Dark must be FROZEN, NOT ICE_SEALED!)
            val r2 = recA.board.cellAt(2, c)
            assertTrue("Frozen ($el) must flag FROZEN", (r2.hazards and HazardBits.FROZEN) != 0)
            assertEquals("Frozen ($el) must recover $el", el, r2.element)

            // Row 3: Bomb × 6 colors + exact underlying element recovery
            val r3 = recA.board.cellAt(3, c)
            assertTrue("Bomb ($el) must flag BOMB", (r3.hazards and HazardBits.BOMB) != 0)
            assertEquals("Bomb ($el) must recover $el", el, r3.element)

            // Row 4: Normal 6-color control row -> ZERO false positives
            val r4 = recA.board.cellAt(4, c)
            assertEquals("Control ($el) must have ZERO hazards", HazardBits.NONE, r4.hazards)
            assertEquals("Control ($el) must have ZERO terrain flags", TileFlagBits.NONE, recA.board.tileFlags[4 * 6 + c])
            assertEquals("Control ($el) element mismatch", el, r4.element)
        }
        assertEquals(0, recA.questionOrbsDetected)
        assertEquals("Frozen Wood must NEVER falsely trigger Traffic Light Start", 0, recA.trafficLightStartCount)
        assertEquals("Frozen Fire must NEVER falsely trigger Traffic Light End", 0, recA.trafficLightEndCount)

        // Board B: Row 0 = Burning Tile × 6 dim colors (no Light-orb brightness bias!),
        //          Row 1 = Evil Energy × 6 colors (including brighter darkened Light/Heart!),
        //          Row 2 = Ice-Sealed × 6 colors, Row 3 = Purgatory Fire × 6 colors, Row 4 = Normal control row!
        val boardB = buildBaseFrame()
        for (c in 0 until 6) {
            val (el, baseColor) = elementColors[c]
            val dimColor = dimElementColors.getValue(el)
            val evilColor = darkenedEvilColors.getValue(el)

            // Row 0: Burning Tile × 6 dim colors (avgV down to ~0.55 on Dark/Water/Wood!)
            paintCell(boardB, 0, c) { dx, dy, rr ->
                val linf = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                when {
                    linf >= 0.37f -> 0xFFFF8214.toInt()
                    rr <= 0.36f -> dimColor
                    else -> 0xFFE86A10.toInt()
                }
            }

            // Row 1: Evil Energy × 6 colors (dark shadowed orb + turquoise wisps H~183°)
            paintCell(boardB, 1, c) { dx, dy, rr ->
                val wisp = (kotlin.math.abs(dx - 0.12f) < 0.07f && kotlin.math.abs(dy - 0.08f) < 0.07f) ||
                    (kotlin.math.abs(dx + 0.14f) < 0.07f && kotlin.math.abs(dy + 0.10f) < 0.07f)
                when {
                    wisp -> 0xFF348C8E.toInt()
                    rr <= 0.40f -> evilColor
                    else -> null
                }
            }

            // Row 2: Ice-Sealed (purple faceted crystal cube: ~66% purple + ~33% white veins!)
            paintCell(boardB, 2, c) { dx, dy, _ ->
                val vein = (kotlin.math.floor((dx + 0.5f) * 9f).toInt() + kotlin.math.floor((dy + 0.5f) * 9f).toInt()) % 3 == 0
                if (vein) 0xFFEDE4FA.toInt() else 0xFF9424E0.toInt()
            }

            // Row 3: Purgatory Fire × 6 colors (blinding white core + underlying 6-color orb rim + neon magenta halo on dark tile)
            paintCell(boardB, 3, c) { _, _, rr ->
                when {
                    rr <= 0.315f -> 0xFFFFFAFE.toInt()
                    rr <= 0.365f -> baseColor
                    rr <= 0.46f -> 0xFFD030E8.toInt()
                    else -> null
                }
            }
        }

        val recB = recognizer.recognizeFrames(listOf(boardB), geom)
        for (c in 0 until 6) {
            val el = elementColors[c].first
            assertTrue("Burning Tile ($el dim) must flag BURNING_TILE", (recB.board.tileFlags[0 * 6 + c] and TileFlagBits.BURNING_TILE) != 0)
            assertEquals("Burning Tile ($el dim) must preserve $el", el, recB.board.cellAt(0, c).element)

            assertTrue("Evil Energy ($el) must flag EVIL_ENERGY", (recB.board.cellAt(1, c).hazards and HazardBits.EVIL_ENERGY) != 0)
            assertTrue("Ice-Sealed ($el) must flag ICE_SEALED", (recB.board.cellAt(2, c).hazards and HazardBits.ICE_SEALED) != 0)
            assertTrue("Purgatory Fire ($el) must flag PURGATORY_FIRE", (recB.board.cellAt(3, c).hazards and HazardBits.PURGATORY_FIRE) != 0)

            assertEquals("ControlB ($el) must have ZERO hazards", HazardBits.NONE, recB.board.cellAt(4, c).hazards)
            assertEquals("ControlB ($el) must have ZERO terrain flags", TileFlagBits.NONE, recB.board.tileFlags[4 * 6 + c])
        }
        assertEquals(6, recB.burningTilesDetected)
        assertEquals(6, recB.evilEnergyOrbsDetected)
        assertEquals(6, recB.frozenOrbsDetected)
        assertEquals(6, recB.purgatoryFireOrbsDetected)
        assertEquals("Contiguous 1x6 Burning Row must NEVER falsely trigger Puzzle Shield", 0, recB.puzzleCellsDetected)
        assertEquals("Board B must have ZERO false Traffic Light Starts", 0, recB.trafficLightStartCount)
        assertEquals("Board B must have ZERO false Traffic Light Ends", 0, recB.trafficLightEndCount)
    }

    @Test
    fun `isDarkModalCoveringBoard catches enemy skill popup with golden header while allowing full-board question orbs`() {
        val w = 576
        val h = 1280

        // 1) Enemy Skill Detail Popup: ~74% pure black background + ~18% golden/yellow header accents in upper band (0.41..0.53)
        val popupPixels = IntArray(w * h) { 0xFF18181A.toInt() }
        // Golden border & yellow 【固定 8 連擊盾】 header text in 0.41..0.53 (~18% vivid yellow pixels)
        for (y in (h * 0.42f).toInt() until (h * 0.52f).toInt()) {
            for (x in (w * 0.20f).toInt() until (w * 0.80f).toInt()) {
                if ((y - (h * 0.42f).toInt()) % 6 == 0) {
                    popupPixels[y * w + x] = 0xFFE8C438.toInt()
                }
            }
        }
        val popupFrame = object : VisionFrame {
            override val width = w
            override val height = h
            override fun getArgb(x: Int, y: Int): Int =
                popupPixels[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]
        }
        assertTrue(
            "Enemy skill popup with golden header accents must be caught by isDarkModalCoveringBoard",
            FlashRevealMemoryTracker.isDarkModalCoveringBoard(popupFrame)
        )

        // 2) Full-board 30/30 '?' Question Orbs (colorful portraits above + slate-blue glowing spheres in 6x5 grid) -> FALSE
        val qPixels = IntArray(w * h) { 0xFF1E1C1A.toInt() }
        // Colorful portraits above board (0.41..0.52)
        for (y in (h * 0.41f).toInt() until (h * 0.52f).toInt()) {
            for (x in 0 until w) {
                qPixels[y * w + x] = if ((x / 40) % 2 == 0) 0xFFD06040.toInt() else 0xFF4090D8.toInt()
            }
        }
        // 30 slate-blue '?' spheres (RGB ~ 78, 92, 118 -> NOT pure black!)
        val cell = w / 6
        val boardTop = (h * 0.528f).toInt()
        for (r in 0 until 5) {
            for (c in 0 until 6) {
                val cx = c * cell + cell / 2
                val cy = boardTop + r * cell + cell / 2
                for (dy in -cell / 2 until cell / 2) {
                    for (dx in -cell / 2 until cell / 2) {
                        if (dx * dx + dy * dy <= (cell * 0.42f) * (cell * 0.42f)) {
                            val py = (cy + dy).coerceIn(0, h - 1)
                            val px = (cx + dx).coerceIn(0, w - 1)
                            qPixels[py * w + px] = 0xFF566882.toInt()
                        }
                    }
                }
            }
        }
        val qFrame = object : VisionFrame {
            override val width = w
            override val height = h
            override fun getArgb(x: Int, y: Int): Int =
                qPixels[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]
        }
        assertFalse(
            "Genuine full-board 30/30 '?' question-orb battle board must NOT be mistaken for a dark modal popup",
            FlashRevealMemoryTracker.isDarkModalCoveringBoard(qFrame)
        )
    }
}
