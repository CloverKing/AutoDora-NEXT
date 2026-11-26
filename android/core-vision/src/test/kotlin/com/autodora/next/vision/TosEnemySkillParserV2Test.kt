package com.autodora.next.vision

import com.autodora.next.model.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TosEnemySkillParserV2Test {

    private fun loadParser(): TosEnemySkillParserV2 {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate1 = File(dir, "app/src/main/assets/tos_enemy_skill_rules_v2.json")
            if (candidate1.isFile) return TosEnemySkillParserV2.fromJson(candidate1.readText(Charsets.UTF_8))
            val candidate2 = File(dir, "android/app/src/main/assets/tos_enemy_skill_rules_v2.json")
            if (candidate2.isFile) return TosEnemySkillParserV2.fromJson(candidate2.readText(Charsets.UTF_8))
            dir = dir.parentFile
        }
        error("Could not locate tos_enemy_skill_rules_v2.json from ${File(".").absolutePath}")
    }

    @Test
    fun `parses greyed-out bypassed skills into DB list while excluding them from active battle chips`() {
        val parser = loadParser()
        val lines = listOf(
            OcrTextLine("【起動浮標】", 20, 20, 400, 46, 0.99f, isGreyBypassed = false),
            OcrTextLine("移動符石時只能由起動浮標位置開始移動", 20, 50, 700, 74, 0.98f, isGreyBypassed = false),
            OcrTextLine("【指定形狀盾】", 20, 120, 400, 146, 0.99f, isGreyBypassed = true),
            OcrTextLine("須以水屬性強化符石消除指定拼圖形狀且不可黏連", 20, 150, 760, 174, 0.97f, isGreyBypassed = true)
        )

        val res = parser.parseLines(lines)
        assertEquals(2, res.allBlocks.size)
        assertEquals(1, res.bypassedBlocks.size)
        assertTrue(res.bypassedBlocks[0].chips.any { it.id == "puzzle_shield" })

        // Active battle chips MUST include Starting Buoy, and MUST NOT include the bypassed Puzzle Shield
        assertTrue(res.activeChips.any { it.id == "starting_buoy_shield" })
        assertFalse(res.activeChips.any { it.id == "puzzle_shield" })

        // Greyed-out block must still keep its verbatim title, raw text, and deterministic hash for Room DB
        val greyBlock = res.allBlocks.first { it.isGreyBypassed }
        assertEquals("指定形狀盾", greyBlock.titleZhTw)
        assertTrue(greyBlock.rawBodyZhTw.contains("拼圖形狀"))
        assertEquals(16, greyBlock.skillHash.length)
        assertEquals(EnemySkillParseStatus.PARSED, greyBlock.parseStatus)
    }

    @Test
    fun `covers key categories across the 98 official skills and produces RuleComposer-compatible chips`() {
        val parser = loadParser()

        // 1. Combo shields (#8 exact first, #18 min first elem, #57/#58 kind shields, #9 generalized equal combo, #88 wave-2, #66/#81 no cascade)
        val comboText = """
            【固定連擊盾】
            首批消除 6 連擊才可對敵人造成攻擊傷害
        """.trimIndent()
        val c1 = parser.parsePlainText(comboText).activeChips
        assertEquals(6, c1.first { it.id == "exact_first_combo" }.paramInt)

        val minElemRes = parser.parsePlainText("▼ 首批消除 5 組或以上的屬性符石才可對敵人造成攻擊傷害")
        assertEquals(5, minElemRes.activeChips.first { it.id == "min_first_elem_combo" }.paramInt)

        val sameKind = parser.parsePlainText("【首消相同種類盾】首消同種類 3 Combo").activeChips
        assertEquals(3, sameKind.first { it.id == "wave1_same_kind" }.paramInt)

        val distinctKind = parser.parsePlainText("【首消不同種類盾】首消不同種類 4 連擊").activeChips
        assertEquals(4, distinctKind.first { it.id == "wave1_distinct_kind" }.paramInt)

        val eq3 = parser.parsePlainText("【連擊相等盾】同時消除水、火及暗符石的連擊數須相等").activeChips.first { it.id == "equal_pair_light_dark" }
        val expectedEqMask = 0x100 or Element.WATER.bitMask or Element.FIRE.bitMask or Element.DARK.bitMask
        assertEquals(expectedEqMask, eq3.paramElementMask)

        val wave2 = parser.parsePlainText("【次批消除盾】第 2 批消除 3 連擊或以上").activeChips
        assertEquals(3, wave2.first { it.id == "second_wave_combo" }.paramInt)

        val noCasc = parser.parsePlainText("【只能消除首批符石】無法消除天降符石").activeChips
        assertTrue(noCasc.any { it.id == "no_cascade" })

        // 2. Element, orb count, race & death-rattle gauges (#3-#7, #10-#13, #23, #62, #89-#93)
        val multiEl = parser.parsePlainText("首批同時消除水、火強化及暗屬性符石").activeChips.first { it.id == "require_elements" }
        val reqMask = multiEl.paramElementMask and 0x3F
        assertEquals(Element.WATER.bitMask or Element.FIRE.bitMask or Element.DARK.bitMask, reqMask)
        assertTrue((multiEl.paramInt and 2) != 0) // Wave-1 strict

        val anti = parser.parsePlainText("【反首消符石盾】首批禁消水、火及心符石").activeChips.first { it.id == "anti_first_wave" }
        assertEquals(Element.WATER.bitMask or Element.FIRE.bitMask or Element.HEART.bitMask, anti.paramElementMask)

        val revHeal = parser.parsePlainText("【回復顛倒】消除心符石的回復力變為傷害").activeChips
        assertEquals(Element.HEART.bitMask, revHeal.first { it.id == "forbid_all" }.paramElementMask)

        val race = parser.parsePlainText("【種族符石盾】須首消龍類、機械族符石").activeChips.first { it.id == "race_orb_shield" }
        assertEquals(RaceType.DRAGON.bitMask or RaceType.MACHINA.bitMask, race.paramInt)

        val forbiddenSpell = parser.parsePlainText("【符石禁咒】木符石無法消除").activeChips.first { it.id == "unmatchable_elements" }
        assertEquals(Element.WOOD.bitMask, forbiddenSpell.paramElementMask)

        val deathRattle = parser.parsePlainText("【冰魄暴劫】戰敗扣血計數").activeChips
        assertEquals(Element.WATER.bitMask, deathRattle.first { it.id == "forbid_all" }.paramElementMask)
        assertEquals(Element.WOOD.bitMask, deathRattle.first { it.id == "elem_burst_shield" }.paramElementMask)

        // 3. Shapes & Path (#14 cross, #15 T, #16 L, #30 traffic light, #54 char orb terrain, #60 step range, #25-#36 hints)
        val cross = parser.parsePlainText("雙十字相異屬性消除盾").activeChips.first { it.id == "cross_shield" }
        assertEquals(2, cross.paramInt and 0x0F)
        assertTrue((cross.paramInt and 0x20) != 0)

        val lShape = parser.parsePlainText("火屬性 L 字限盾").activeChips.first { it.id == "l_shape" }
        assertEquals(Element.FIRE.bitMask, lShape.paramElementMask)

        val stepRange = parser.parsePlainText("【步數盾】移動步數須於 25 至 30 步內").activeChips.first { it.id == "step_range" }
        assertEquals(25, stepRange.paramInt and 0xFF)
        assertEquals(30, (stepRange.paramInt ushr 8) and 0xFF)

        val tl = parser.parsePlainText("【起結有序】從綠色漩渦起手並於紅色漩渦結束").activeChips
        assertTrue(tl.any { it.id == "traffic_light" })

        val hintRes = parser.parsePlainText("敵人展開幽閉空間與灼熱地型及黑洞")
        assertTrue(hintRes.combinedHints.hasMonochrome)
        assertTrue(hintRes.combinedHints.hasScorching)
        assertTrue(hintRes.combinedHints.hasBlackHole)

        // Verify round-trip into RuleComposer cleanly
        val composed = RuleComposer.compose(
            base = TeamBasePreset(id = "t", name = "t"),
            activeChips = c1 + listOf(stepRange, multiEl)
        )
        assertEquals(6, composed.exactFirstWaveCombo)
        assertEquals(25, composed.minSteps)
        assertEquals(30, composed.maxSteps)
        assertTrue((composed.requiredFirstWaveElementsMask and Element.WATER.bitMask) != 0)
    }

    @Test
    fun `rejects UI chrome and HP or DEF 0 stat lines without triggering zero_combo`() {
        val parser = loadParser()
        val chromeAndStats = listOf(
            OcrTextLine("關卡資訊", 20, 20, 200, 44, 0.99f),
            OcrTextLine("WAVE 2 CURRENT", 20, 50, 260, 74, 0.98f),
            OcrTextLine("ATK 18,450", 20, 80, 200, 102, 0.99f),
            OcrTextLine("DEF 0", 20, 110, 120, 132, 0.99f),
            OcrTextLine("0", 20, 140, 60, 162, 0.99f),
            OcrTextLine("CD 2", 20, 170, 110, 192, 0.99f),
            OcrTextLine("【連擊】", 20, 200, 140, 224, 0.99f),
            OcrTextLine("會在1回合內連續攻擊2次", 20, 230, 420, 254, 0.95f)
        )

        val res = parser.parseLines(chromeAndStats)
        assertFalse("Bare DEF 0 must NEVER emit zero_combo!", res.activeChips.any { it.id == "zero_combo" })
        assertTrue("Non-board combat skill must be archived without emitting active board chip", res.activeChips.isEmpty())
        assertEquals(1, res.allBlocks.size)
        assertEquals(EnemySkillParseStatus.NON_BOARD_KNOWN, res.allBlocks[0].parseStatus)
    }
}
