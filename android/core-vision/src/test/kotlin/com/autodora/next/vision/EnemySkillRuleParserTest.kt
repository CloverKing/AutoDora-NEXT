package com.autodora.next.vision

import com.autodora.next.model.Element
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class EnemySkillRuleParserTest {

    private fun loadBundledParser(): EnemySkillRuleParser {
        val assetFile = File("src/main/assets/tos_enemy_skill_rules.json")
        assertTrue("Bundled tos_enemy_skill_rules.json must exist", assetFile.exists())
        return EnemySkillRuleParser.fromJson(assetFile.readText())
    }

    @Test
    fun `parses traditional HK-TW combo, element, puzzle, cross, and trail enemy skills accurately`() {
        val parser = loadBundledParser()

        val samplePopup = """
            敵人技能：
            • 首消 7 Combo 盾
            • 五屬強化盾
            • 拼圖盾-限 (不可黏連外部同色)
            • 雙十字盾
            • 首消禁心
            • 軌跡燃燒
            • 首消光暗同等連擊
            • 數字盾 20 顆符石
        """.trimIndent()

        val parsed = parser.parse(samplePopup)
        val byId = parsed.chips.associateBy { it.id }

        assertEquals(7, byId["exact_first_combo"]?.paramInt)
        assertTrue(byId.containsKey("five_elements_enchanted"))
        assertFalse("Generic five_elements must not duplicate five_elements_enchanted", byId.containsKey("five_elements"))
        assertTrue(byId.containsKey("strict_puzzle"))
        assertFalse("Generic loose_puzzle must not duplicate strict_puzzle", byId.containsKey("loose_puzzle"))
        assertEquals(2, byId["cross_shield"]?.paramInt)
        assertEquals(1 shl Element.HEART.code, byId["anti_first_wave"]?.paramElementMask)
        assertTrue(byId.containsKey("zero_trail_burning"))
        assertTrue(byId.containsKey("equal_pair_light_dark"))
        assertEquals(20, byId["digital_orb_count"]?.paramInt)
    }

    @Test
    fun `parses 2025-2026 modern mechanics including race shield, second wave, trail corrosion, and char orb terrain`() {
        val parser = loadBundledParser()
        val sample = """
            次批消除 2 Combo
            種族符石首消盾
            軌跡腐蝕
            角色符石通行停放地形
            0 Combo 盾
        """.trimIndent()

        val parsed = parser.parse(sample)
        val ids = parsed.chips.map { it.id }.toSet()

        assertTrue(ids.contains("second_wave_combo"))
        assertTrue(ids.contains("race_orb_shield"))
        assertTrue(ids.contains("trail_corrosion"))
        assertTrue(ids.contains("char_orb_terrain"))
        assertTrue(ids.contains("zero_combo"))
    }
}
