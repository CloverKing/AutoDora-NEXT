package com.autodora.next.vision

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class OfficialEnemyIconMatcherTest {

    private fun loadRealFrame(relPath: String): VisionFrame? {
        val baseDir = System.getenv("AUTODORA_TEST_SAMPLES_DIR") ?: return null
        val file = File(baseDir, relPath)
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

    private fun syntheticFrame(w: Int, h: Int, pixelFn: (Int, Int) -> Int): VisionFrame = object : VisionFrame {
        override val width: Int = w
        override val height: Int = h
        override fun getArgb(x: Int, y: Int): Int = pixelFn(x.coerceIn(0, w - 1), y.coerceIn(0, h - 1))
    }

    @Test
    fun `matches official ToS 12x12 alpha-masked descriptors accurately under brightness shift`() {
        assertTrue("Expected >= 180 official icon templates", OfficialEnemyIconTemplates.templates.size >= 180)

        val keyIconIds = listOf(
            "ICON051", "ICON067", "ICON104", "ICON094", "ICON190",
            "ICON107", "ICON423", "ICON868", "ICON615",
            // All 6 attribute starburst cumulative/burst orb shields (Water/Fire/Wood/Light/Dark/Heart)
            "ICON1255", "ICON1256", "ICON1257", "ICON1258", "ICON1259", "ICON1502",
            // All 6 attribute single-element enchanted shields
            "ICON217", "ICON218", "ICON219", "ICON220", "ICON221", "ICON227",
            // Cross / L / T shape families
            "ICON986", "ICON1301", "ICON1307"
        )
        for (iconId in keyIconIds) {
            val tmpl = OfficialEnemyIconTemplates.templates.first { it.iconId == iconId }
            // Simulate -12% brightness shift + slate battle sky in transparent corners!
            val perturbed = IntArray(OfficialEnemyIconTemplates.PIXEL_COUNT) { idx ->
                val v = tmpl.argb12x12[idx]
                if ((v ushr 24) == 0) {
                    // Arbitrary bright blue sky background in transparent corners must NOT break matching!
                    0x5A7EA0
                } else {
                    val r = (((v ushr 16) and 0xFF) * 88 / 100).coerceIn(0, 255)
                    val g = (((v ushr 8) and 0xFF) * 88 / 100).coerceIn(0, 255)
                    val b = ((v and 0xFF) * 88 / 100).coerceIn(0, 255)
                    (r shl 16) or (g shl 8) or b
                }
            }
            val match = EnemyStatusIconMatcher.matchPatchRgb12x12(perturbed)
            assertNotNull("Expected match for official $iconId", match)
            assertEquals("Expected exact chipId for $iconId", tmpl.chipId, match!!.template.chipId)
            assertEquals("Expected exact paramInt for $iconId", tmpl.paramInt, match.template.paramInt)
            assertEquals("Expected exact paramElementMask for $iconId", tmpl.paramElementMask, match.template.paramElementMask)
            assertTrue("Expected high score >= 0.90 for $iconId, got ${match.score}", match.score >= 0.90f)
        }
    }

    @Test
    fun `detects real battle Fixed 4 Combo icon (ICON067) zero-tap on user battle screenshot`() {
        loadRealFrame("real_tos_samples/user_4c_battle.jpg")?.let { frame ->
            val scan = EnemyStatusIconMatcher.scanBattleScreen(frame)
            assertTrue("Battle enemy with Fixed 4C icon must set hasEnemyStatusIcons", scan.hasEnemyStatusIcons)
            val fixed4 = scan.detectedChips.firstOrNull { it.id == "exact_first_combo" && it.paramInt == 4 }
            assertNotNull(
                "Must detect Fixed 4 Combo (ICON067) zero-tap! Found: ${scan.detectedBadgesSummary}",
                fixed4
            )
        }
    }

    @Test
    fun `detects real battle Fire Starburst Shield (ICON1256) zero-tap on sunlit corridor screenshot`() {
        loadRealFrame("real_tos_samples/user_fire5_battle.jpg")?.let { frame ->
            val scan = EnemyStatusIconMatcher.scanBattleScreen(frame)
            assertTrue("Battle enemy with Fire Starburst icon must set hasEnemyStatusIcons", scan.hasEnemyStatusIcons)
            val fireBurst = scan.detectedChips.firstOrNull {
                it.id == "elem_burst_shield" && (it.paramElementMask and (1 shl 1)) != 0
            }
            assertNotNull(
                "Must detect Fire Burst/Cumulative Shield (ICON1256) zero-tap! Found: ${scan.detectedBadgesSummary}",
                fireBurst
            )
        }
    }

    @Test
    fun `locates all live battle enemies accurately for automatic long-press skill inspection`() {
        loadRealFrame("real_tos_samples/user_fire5_battle.jpg")?.let { frame ->
            val enemies = StageInfoUiAnalyzer.findBattleEnemyTapPoints(frame)
            assertEquals("1-enemy corridor must find 1 enemy target, got $enemies", 1, enemies.size)
            assertEquals(0.50f, enemies[0].x / frame.width, 0.06f)
        }
        loadRealFrame("real_tos_samples/user_4c_battle.jpg")?.let { frame ->
            val enemies = StageInfoUiAnalyzer.findBattleEnemyTapPoints(frame)
            assertEquals("2-enemy wave must find 2 enemy targets, got $enemies", 2, enemies.size)
            assertTrue("Enemies must be ordered left-to-right", enemies[0].x < enemies[1].x)
        }
        loadRealFrame("real_tos_samples/user_enemy_popup.jpg")?.let { frame ->
            val enemies = StageInfoUiAnalyzer.findBattleEnemyTapPoints(frame)
            assertEquals("3-enemy wave must find 3 enemy targets, got $enemies", 3, enemies.size)
            assertTrue("3 enemies must be ordered left-to-right", enemies[0].x < enemies[1].x && enemies[1].x < enemies[2].x)
        }
    }

    @Test
    fun `rejects dark enemy long-press skill popup so question memory overlay NEVER triggers on it`() {
        val darkPopup = syntheticFrame(1080, 2400) { _, y ->
            if (y in 950..1350) 0xFF18181A.toInt() else 0xFF3A4A58.toInt()
        }
        assertTrue(
            "Synthetic dark enemy detail modal must be detected",
            StageInfoUiAnalyzer.isEnemyLongPressPopupOpen(darkPopup)
        )
        val brightBattle = syntheticFrame(1080, 2400) { x, y ->
            if ((x / 180 + y / 180) % 2 == 0) 0xFFE53935.toInt() else 0xFF1E88E5.toInt()
        }
        assertFalse(
            "Vivid battle frame must NOT be flagged as enemy long-press popup",
            StageInfoUiAnalyzer.isEnemyLongPressPopupOpen(brightBattle)
        )
        loadRealFrame("real_tos_samples/user_enemy_popup.jpg")?.let { frame ->
            assertTrue(
                "Enemy long-press detail popup must be detected to suppress question-orb memory overlay",
                StageInfoUiAnalyzer.isEnemyLongPressPopupOpen(frame)
            )
        }
        loadRealFrame("real_tos_samples/user_4c_battle.jpg")?.let { frame ->
            assertFalse(
                "Normal battle screen must NOT be flagged as enemy long-press popup",
                StageInfoUiAnalyzer.isEnemyLongPressPopupOpen(frame)
            )
        }
    }
}
