package com.autodora.next.vision

import com.autodora.next.model.Element
import com.autodora.next.model.ShieldCategory
import com.autodora.next.model.WaveShieldChip
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class StageWaveScriptParserTest {

    private fun loadParser(): TosEnemySkillParserV2 {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val c1 = File(dir, "app/src/main/assets/tos_enemy_skill_rules_v2.json")
            if (c1.isFile) return TosEnemySkillParserV2.fromJson(c1.readText(Charsets.UTF_8))
            val c2 = File(dir, "android/app/src/main/assets/tos_enemy_skill_rules_v2.json")
            if (c2.isFile) return TosEnemySkillParserV2.fromJson(c2.readText(Charsets.UTF_8))
            dir = dir.parentFile
        }
        error("Could not locate tos_enemy_skill_rules_v2.json")
    }

    @Test
    fun `parseStageInfoLines segments multi-wave modal into StageWaveScript and detects current wave`() {
        val parser = loadParser()
        val lines = listOf(
            OcrTextLine("關卡資訊", 20, 10, 220, 34, 0.99f),
            OcrTextLine("煉獄的試煉", 20, 42, 320, 68, 0.98f),
            OcrTextLine("WAVE 1", 20, 82, 160, 106, 0.99f),
            OcrTextLine("【五屬盾】同時消除水火木光暗符石", 20, 112, 620, 136, 0.97f),
            OcrTextLine("WAVE 2 CURRENT", 20, 162, 260, 186, 0.99f),
            OcrTextLine("【固定連擊盾】首批消除 6 連擊", 20, 192, 560, 216, 0.98f),
            OcrTextLine("BOSS", 20, 242, 140, 266, 0.99f),
            OcrTextLine("【起動浮標】只能由起動浮標位置開始移動", 20, 272, 660, 296, 0.97f),
            OcrTextLine("幽閉空間", 20, 304, 240, 328, 0.95f)
        )

        val parsed = StageWaveScriptParser.parseStageInfoLines(lines, parser)
        assertNotNull(parsed)
        val res = parsed!!
        assertEquals("煉獄的試煉", res.script.stageName)
        assertEquals(2, res.detectedCurrentWave)
        assertEquals(3, res.script.waves.size)

        val w1 = res.script.waves[0]
        assertEquals(1, w1.waveIndex)
        assertFalse(w1.isBoss)
        assertTrue(w1.chips.any { it.id == "five_elements" })

        val w2 = res.script.waves[1]
        assertEquals(2, w2.waveIndex)
        assertEquals(6, w2.chips.first { it.id == "exact_first_combo" }.paramInt)

        val boss = res.script.waves[2]
        assertEquals(3, boss.waveIndex)
        assertTrue(boss.isBoss)
        assertTrue(boss.chips.any { it.id == "starting_buoy_shield" })
        assertTrue(boss.visualHints.hasMonochrome)
    }

    @Test
    fun `parseStageWikiJson supports both raw crawled skill text and structured bundles`() {
        val parser = loadParser()
        val rawWikiJson = """
            {
              "stages": [
                {
                  "stageName": "雙週災厄級",
                  "waves": [
                    { "waveIndex": 1, "rawText": "【反首消符石盾】首批禁消心符石" },
                    { "waveIndex": 2, "isBoss": true, "skills": ["【步數盾】20 至 25 步"] }
                  ]
                }
              ]
            }
        """.trimIndent()

        val scripts = StageWaveScriptParser.parseStageWikiJson(rawWikiJson, parser)
        assertEquals(1, scripts.size)
        val sc = scripts[0]
        assertEquals("雙週災厄級", sc.stageName)
        assertEquals(2, sc.waves.size)
        assertEquals(Element.HEART.bitMask, sc.waves[0].chips.first { it.id == "anti_first_wave" }.paramElementMask)
        val sr = sc.waves[1].chips.first { it.id == "step_range" }.paramInt
        assertEquals(20, sr and 0xFF)
        assertEquals(25, (sr ushr 8) and 0xFF)

        val structuredBundleJson = """
            {
              "stages": [
                {
                  "stageKey": "k1",
                  "stageName": "Prebuilt Stage",
                  "waves": [
                    {
                      "waveIndex": 1,
                      "chips": [
                        { "id": "cross_shield", "label": "十字盾", "category": "SHAPE_AND_PUZZLE", "paramInt": 1 }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        val structured = StageWaveScriptParser.parseStageWikiJson(structuredBundleJson, parser)
        assertEquals(1, structured.size)
        assertEquals("Prebuilt Stage", structured[0].stageName)
        assertEquals("cross_shield", structured[0].waves[0].chips[0].id)
    }
}
