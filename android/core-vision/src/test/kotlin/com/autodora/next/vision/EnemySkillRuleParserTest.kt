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

    @Test
    fun `gear stage info screen with no-skill enemy and stats ending in zero never falsely triggers zero_combo`() {
        val parser = loadBundledParser()

        // Exact OCR text structure observed on real ToS 「齿轮 → 關卡資訊」 screen (emu_gear.png):
        // HP/DEF end in '0' right next to 'CD 2' and 'CURRENT' which previously falsely matched "0\s*c"!
        val noSkillGearOcr = """
            WAVE 1
            CURRENT
            霧象龍
            1,935,820
            3,198
            82,000
            CD 2
            沒有技能
            WAVE 2
            NEXT
            BOSS
        """.trimIndent()

        val parsed = parser.parse(noSkillGearOcr)
        assertTrue("No-skill enemy must produce ZERO shield chips (got ${parsed.chips})", parsed.chips.isEmpty())
        assertTrue("Must detect '沒有技能' banner", parsed.hasNoSkillBanner)

        // Same-line stats + CD edge case (in case OCR merges adjacent blocks onto one line)
        val mergedLineOcr = "霧象龍 82,000 CD 2\n1,935,820 CURRENT\n沒有技能"
        val parsedMerged = parser.parse(mergedLineOcr)
        assertTrue("Merged stats+CD line must NEVER trigger zero_combo (got ${parsedMerged.chips})", parsedMerged.chips.isEmpty())
    }

    @Test
    fun `10 Combo shield and HK-TW combo wording never falsely trigger zero_combo or duplicate anti-first-wave`() {
        val parser = loadBundledParser()

        val sample = """
            首批消除 10 連擊 (Combo) 或以上
            首批禁消火符石
        """.trimIndent()

        val parsed = parser.parse(sample)
        val byId = parsed.chips.associateBy { it.id }

        assertFalse("10 Combo must NEVER falsely match zero_combo", byId.containsKey("zero_combo"))
        assertEquals("Should parse >= 10 Combo", 10, byId["min_combo"]?.paramInt)
        assertFalse("8+ / 10+ Combo should not duplicate exact_first_combo", byId.containsKey("exact_first_combo"))
        assertTrue("Should parse anti_first_wave for 首批禁消火", byId.containsKey("anti_first_wave"))
        assertFalse("首批禁消火 must NOT falsely also trigger forbid_all", byId.containsKey("forbid_all"))
    }
}
