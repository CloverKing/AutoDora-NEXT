package com.autodora.next.vision

import com.autodora.next.model.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class StageInfoUiAnalyzerTest {

    private fun loadRealFrame(relPath: String): VisionFrame? {
        val file = File(
            System.getProperty("user.home"),
            ".gemini/antigravity/brain/57099816-f54e-41fc-8a2c-48bf0a7b1510/scratch/$relPath"
        )
        if (!file.exists()) return null
        val imageIo = Class.forName("javax.imageio.ImageIO")
        val img = imageIo.getMethod("read", File::class.java).invoke(null, file)
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

    @Test
    fun `detects Stage Info modal vs battle screens accurately on real screenshots`() {
        loadRealFrame("real_tos_samples/emu_gear.png")?.let { f ->
            assertTrue("emu_gear.png must be detected as Stage Info modal", StageInfoUiAnalyzer.isStageInfoModalOpen(f))
            val arrows = StageInfoUiAnalyzer.findCollapsedSkillArrows(f)
            assertEquals("No-skill enemy card must have 0 collapsed arrows", 0, arrows.size)
            val portraits = StageInfoUiAnalyzer.findEnemyPortraitsInStageInfo(f)
            assertEquals("WAVE 1 has 2 enemy portraits", 2, portraits.size)
        }
        loadRealFrame("real_tos_samples/emu_check_now.png")?.let { f ->
            assertTrue("emu_check_now.png must be detected as Stage Info modal", StageInfoUiAnalyzer.isStageInfoModalOpen(f))
            val arrows = StageInfoUiAnalyzer.findCollapsedSkillArrows(f)
            assertEquals("Collapsed 【固定 4 連擊盾】 must detect exactly 1 right-pointing ▶ arrow", 1, arrows.size)
            assertEquals(112f, arrows[0].x, 18f)
            assertEquals(1208f, arrows[0].y, 20f)
            val portraits = StageInfoUiAnalyzer.findEnemyPortraitsInStageInfo(f)
            assertEquals("WAVE 5 has 2 enemy portraits", 2, portraits.size)
        }
        loadRealFrame("real_tos_samples/emu_gear_expanded.png")?.let { f ->
            assertTrue("emu_gear_expanded.png must be detected as Stage Info modal", StageInfoUiAnalyzer.isStageInfoModalOpen(f))
            val arrows = StageInfoUiAnalyzer.findCollapsedSkillArrows(f)
            assertEquals("Expanded ▼ triangle must NOT be re-clicked as collapsed ▶", 0, arrows.size)
        }
        loadRealFrame("real_tos_samples/emu_mech_check.png")?.let { f ->
            assertFalse("Battle screen must NOT be detected as Stage Info modal", StageInfoUiAnalyzer.isStageInfoModalOpen(f))
            val iconRes = EnemyStatusIconMatcher.scanBattleScreen(f)
            assertTrue("Battle enemy with tornado status icon must be detected", iconRes.hasEnemyStatusIcons)
        }
        loadRealFrame("mi15_now.png")?.let { f ->
            assertFalse("Xiaomi 15 battle screen must NOT be detected as Stage Info modal", StageInfoUiAnalyzer.isStageInfoModalOpen(f))
            val gear = StageInfoUiAnalyzer.findGearTapPoint(f.width, f.height)
            assertEquals(1118f, gear.x, 22f)
            assertEquals(298f, gear.y, 25f)
        }
    }

    @Test
    fun `always-on memory tracker returns NeedHoldAndNudgeReveal when entering directly onto full-question board`() {
        val tracker = FlashRevealMemoryTracker()
        val fullQuestion = BoardState(
            cols = 6,
            rows = 5,
            cells = List(30) { CellState(Element.HIDDEN, hazards = HazardBits.QUESTION, confidence = 0.25f) }
        )
        val rec = RecognitionResult(
            board = fullQuestion,
            averageConfidence = 0.25f,
            lowConfidenceIndices = (0 until 30).toList(),
            puzzleCellsDetected = 0,
            engineName = "test",
            elapsedMs = 2.0,
            questionOrbsDetected = 30
        )
        val res = tracker.resolveForSolve(rec, nowMs = 5_000L)
        assertTrue(
            "Direct full-question board with no prior snapshot must request NeedHoldAndNudgeReveal",
            res is FlashRevealMemoryTracker.MemoryResolution.NeedHoldAndNudgeReveal
        )
    }
}
