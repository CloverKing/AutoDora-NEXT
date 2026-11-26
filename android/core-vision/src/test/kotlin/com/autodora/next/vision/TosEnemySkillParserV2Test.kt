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

    @Test
    fun `parses element zero-strike, prefix min combo, electrified hints, and rejects loading or self-toast noise`() {
        val parser = loadParser()

        // 1. 【木連撃零化】 -> anti_first_wave(WOOD), NOT swallowed by NON_BOARD_KNOWN
        val woodZero = parser.parsePlainText(
            """
            【木連撃零化】
            首批消除木符石
            ①該回合全隊攻擊力變 0
            ②敵人根據首消木符石組數⇒額外追打相應次數攻擊
            """.trimIndent()
        )
        val antiWood = woodZero.activeChips.firstOrNull { it.id == "anti_first_wave" }
        assertNotNull("Wood zero-strike must map to anti_first_wave!", antiWood)
        assertEquals(Element.WOOD.bitMask, antiWood!!.paramElementMask)

        // 2. 【≥5連撃盾】 -> min_combo(5)
        val min5 = parser.parsePlainText("【≥5連撃盾】")
        val minChip = min5.activeChips.firstOrNull { it.id == "min_combo" }
        assertNotNull("Prefix >=5 combo shield must parse!", minChip)
        assertEquals(5, minChip!!.paramInt)

        // 3. Separate title + body gap merge & 【電撃之水】 -> hasElectrified PARSED
        val mergedGap = parser.parseLines(
            listOf(
                OcrTextLine("【電撃之水】", 40, 100, 260, 128, 0.99f),
                OcrTextLine("進場時電擊所有水符石；電擊符石一經觸碰，即時停止移動符石", 40, 165, 920, 195, 0.96f)
            )
        )
        assertEquals(1, mergedGap.allBlocks.size)
        assertEquals(EnemySkillParseStatus.PARSED, mergedGap.allBlocks[0].parseStatus)
        assertTrue(mergedGap.combinedHints.hasElectrified)

        // 4. Rejects App Toast, <W1/1>×, Loading, Victory/Complete Settlement & Friend Request noise
        assertTrue(TosEnemySkillParserV2.isLikelyNoiseEntry("AI 已归档", "AI 已归档 2 条关卡敌技至本地知识库"))
        assertTrue(TosEnemySkillParserV2.isLikelyNoiseEntry("<W1/1 >×", "<W1/1 >×"))
        assertTrue(TosEnemySkillParserV2.isLikelyNoiseEntry("可愛mumu", "可愛mumu\n完美主義者\n凰族\nDual Max20"))

        val fakeFriendPrompt = listOf(
            OcrTextLine("你剛從戰鬥獲得5友情點數", 100, 300, 600, 330, 0.98f),
            OcrTextLine("確定送出交友申請？", 140, 500, 560, 530, 0.98f)
        )
        assertFalse(
            TosEnemySkillParserV2.isAuthenticEnemySkillScreen(
                fakeFriendPrompt,
                DarkModalKind.ENEMY_LONG_PRESS_POPUP,
                parser.parseLines(fakeFriendPrompt)
            )
        )

        val realPopup = listOf(
            OcrTextLine("【連環光牢】", 100, 420, 320, 450, 0.99f),
            OcrTextLine("敵人會射擊1直行的位置，一經觸碰即時停止移動符石", 100, 465, 860, 495, 0.97f)
        )
        val realRes = parser.parseLines(realPopup)
        assertTrue(TosEnemySkillParserV2.isAuthenticEnemySkillScreen(realPopup, DarkModalKind.ENEMY_LONG_PRESS_POPUP, realRes))
        assertTrue(realRes.combinedHints.hasSticky)
    }
}
