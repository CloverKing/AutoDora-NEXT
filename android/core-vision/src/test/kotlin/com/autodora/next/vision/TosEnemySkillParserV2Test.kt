package com.autodora.next.vision

import com.autodora.next.model.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TosEnemySkillParserV2Test {

    private fun resolveRepoFile(relativePath: String): File? {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val f = File(dir, relativePath)
            if (f.isFile) return f
            dir = dir.parentFile
        }
        return null
    }

    private fun loadParser(): TosEnemySkillParserV2 {
        val rulesFile = resolveRepoFile("app/src/main/assets/tos_enemy_skill_rules_v2.json")
            ?: resolveRepoFile("android/app/src/main/assets/tos_enemy_skill_rules_v2.json")
            ?: error("Could not locate tos_enemy_skill_rules_v2.json from ${File(".").absolutePath}")
        return TosEnemySkillParserV2.fromJson(rulesFile.readText(Charsets.UTF_8))
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

    @Test
    fun `unwraps split titles and game layout breaks, fixes A1 and A4 rules, and avoids false positives`() {
        val parser = loadParser()

        // 1. Smart layout unwrap + split 【Title\nContinuation】 repair + • bullet stays attached to ▼ title
        val splitOcrLines = listOf(
            OcrTextLine("▼ 【T字限盾 (≥3 組)】", 20, 40, 420, 68, 0.99f),
            OcrTextLine("• 以【T】形首批消除", 20, 74, 380, 100, 0.98f),
            OcrTextLine("≥3 組 5 粒光符石", 20, 104, 320, 130, 0.98f),
            OcrTextLine("⇒ 才能對敵人造成傷害", 20, 134, 420, 160, 0.98f),
            OcrTextLine("AVE3", 20, 164, 120, 186, 0.90f),
            OcrTextLine("【十之詛咒 10%·", 20, 220, 340, 246, 0.99f),
            OcrTextLine("生命禁咒】", 20, 250, 220, 276, 0.99f),
            OcrTextLine("每以【十】形首批消除1組≥5粒符石扣除召喚師10%生命力", 20, 280, 780, 306, 0.97f)
        )
        val splitRes = parser.parseLines(splitOcrLines)
        assertEquals(2, splitRes.allBlocks.size)
        val tBlk = splitRes.allBlocks[0]
        assertTrue(tBlk.rawBodyZhTw.contains("• 以【T】形首批消除≥3 組 5 粒光符石"))
        assertFalse(tBlk.rawBodyZhTw.contains("AVE3"))
        val tChip = tBlk.chips.firstOrNull { it.id == "t_shape" }
        assertNotNull(tChip)
        assertEquals(0, tChip!!.paramInt)
        assertEquals(Element.LIGHT.bitMask, tChip.paramElementMask)

        val curseBlk = splitRes.allBlocks[1]
        assertEquals("十之詛咒 10%·生命禁咒", curseBlk.titleZhTw)
        assertEquals(EnemySkillParseStatus.PARSED, curseBlk.parseStatus)
        assertTrue("十之詛咒 now maps to forbid_cross in v0.9.0", curseBlk.chips.any { it.id == "forbid_cross" })
        assertFalse("十之詛咒 must NEVER emit cross_shield!", curseBlk.chips.any { it.id == "cross_shield" })

        // 2. Fullwidth ASCII + OCR pipe artifact + variant kanji chrome
        val pipeRes = parser.parsePlainText("【終結倒|數】\n敵人倒|數 2 回合後消耗召喚師 100% 生命力")
        assertEquals(EnemySkillParseStatus.NON_BOARD_KNOWN, pipeRes.allBlocks.single().parseStatus)

        val fwRes = parser.parsePlainText("ＣＤ２\n【固定連擊盾】\n首批消除 ５ 連擊 (Combo) 才會造成傷害")
        assertEquals(5, fwRes.activeChips.first { it.id == "exact_first_combo" }.paramInt)

        // 3. Previously failing A1 / A4 rules
        val clearWater = parser.parsePlainText("【全消水盾】\n首批消除場上所有水符石")
        assertEquals(Element.WATER.bitMask, clearWater.activeChips.first { it.id == "clear_all_element" }.paramElementMask)

        val unmatchHeart = parser.parsePlainText("【消心禁咒】\n無法消除心符石。")
        assertEquals(Element.HEART.bitMask, unmatchHeart.activeChips.first { it.id == "unmatchable_elements" }.paramElementMask)
        assertFalse("消心禁咒 must NOT emit forbid_all!", unmatchHeart.activeChips.any { it.id == "forbid_all" })

        val step50 = parser.parsePlainText("【50步限制】\n每回合只能移動50步，步數用盡時將停止移動符石")
        assertEquals(50, step50.activeChips.first { it.id == "step_limit" }.paramInt)

        val eq5 = parser.parsePlainText("【五屬連擊相等盾】\n同時首批消除相同組數的五屬符石，才能對敵人造成傷害")
        assertEquals(0x100 or 0x1F, eq5.activeChips.first { it.id == "equal_pair_light_dark" }.paramElementMask)

        val shield60 = parser.parsePlainText("【60 粒符石護盾】\n累積首批消除60粒符石 ⇒ 可扣除敵人100%生命力")
        assertFalse("Cumulative 60-orb shield must NOT emit exactFirstWaveTotalOrbs!", shield60.activeChips.any { it.id == "digital_orb_count" })
        assertTrue(shield60.combinedHints.hasSpecialMarkOrFruit)

        val woodArmor = parser.parsePlainText("【木符石護盾 30 粒】\n累積消除 30 粒木符石")
        val woodBurst = woodArmor.activeChips.firstOrNull { it.id == "elem_burst_shield" }
        assertNotNull(woodBurst)
        assertEquals(Element.WOOD.bitMask, woodBurst!!.paramElementMask)

        val raceExempt = parser.parsePlainText("【轉化符石技能無效】\n所有轉化符石技能效果無效（轉化種族符石而不改變符石種類的技能除外）。")
        assertEquals(EnemySkillParseStatus.NON_BOARD_KNOWN, raceExempt.allBlocks.single().parseStatus)
        assertFalse(raceExempt.activeChips.any { it.id == "race_orb_shield" })

        val crossTypo = parser.parsePlainText("【个子限盾】\n• 以【十】形首批消除≥1組5粒木符石\n⇒ 才能對敵人造成傷害")
        assertEquals(Element.WOOD.bitMask, crossTypo.activeChips.first { it.id == "cross_shield" }.paramElementMask)

        // 4. Visual-hint & non-board A1 skills
        assertTrue(parser.parsePlainText("【毒果凝實 - 木(3x3)】\n以【正方3x3】形首批消除≥1組9粒木符石").combinedHints.hasSpecialMarkOrFruit)
        assertTrue(parser.parsePlainText("【隱匿之途 - 6格】\n敵人會將移動符石時所觸碰的符石轉化為隱藏符石").combinedHints.hasHiddenOrbs)
        assertEquals(
            EnemySkillParseStatus.NON_BOARD_KNOWN,
            parser.parsePlainText("【生命枯竭 (靈魂值)】\n每當敵人發動攻擊 ⇒ 扣除召喚師指定靈魂值").allBlocks.single().parseStatus
        )
        assertEquals(
            EnemySkillParseStatus.NON_BOARD_KNOWN,
            parser.parsePlainText("【淨世】\n進場時消除所有附加效果，相關技能CD將被重置。").allBlocks.single().parseStatus
        )
        assertEquals(
            EnemySkillParseStatus.NON_BOARD_KNOWN,
            parser.parsePlainText("【光之佑律】\n進場時、每回合結束時及每次發動主動技能 ⇒ 所有被移除及引爆的符石 ⇒ 以光符石代替").allBlocks.single().parseStatus
        )
    }

    @Test
    fun `matches A4 380-case benchmark and cleaned unified authoritative master`() {
        val parser = loadParser()
        val json = Json { ignoreUnknownKeys = true }

        val a4File = resolveRepoFile("backups/phase4_v2_inbox/enemy_text_bench/enemy_skill_text_bench.json")
        if (a4File != null) {
            val root = json.parseToJsonElement(a4File.readText(Charsets.UTF_8)).jsonObject
            val cases = root["cases"]!!.jsonArray

            var paramTotal = 0
            var paramPass = 0
            var greyTotal = 0
            var greyPass = 0
            var advTotal = 0
            var advPass = 0
            var encTotal = 0
            var encUnparsed = 0
            val failures = ArrayList<String>()

            for (el in cases) {
                val c = el.jsonObject
                val caseId = c["case_id"]!!.jsonPrimitive.content
                val suite = c["suite"]!!.jsonPrimitive.content
                val input = c["input_text_zh_tw"]!!.jsonPrimitive.content
                val res = parser.parsePlainText(input)

                when (suite) {
                    "parameterized_high_difficulty" -> {
                        paramTotal++
                        val expChips = (c["expected_active_chips"] as? JsonArray).orEmpty()
                        val gotOk = expChips.all { ecEl ->
                            val ec = ecEl.jsonObject
                            val id = ec["id"]!!.jsonPrimitive.content
                            val pInt = ec["paramInt"]?.jsonPrimitive?.intOrNull ?: 0
                            val pMask = ec["paramElementMask"]?.jsonPrimitive?.intOrNull ?: 0
                            res.activeChips.any { g -> g.id == id && g.paramInt == pInt && g.paramElementMask == pMask }
                        } && res.allBlocks.none { it.parseStatus == EnemySkillParseStatus.UNPARSED }
                        if (gotOk) paramPass++ else failures.add("PARAM $caseId got=${res.activeChips.map { "${it.id}(${it.paramInt},${it.paramElementMask})" }}")
                    }
                    "grey_bypassed_calibration" -> {
                        greyTotal++
                        val expBlocks = c["expected_archived_block_count"]!!.jsonPrimitive.intOrNull ?: 0
                        val expActive = c["expected_active_chip_ids"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
                        val expByp = c["expected_bypassed_chip_ids"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
                        val gotActive = res.activeChips.map { it.id }.toSet()
                        val gotByp = res.bypassedBlocks.flatMap { it.chips }.map { it.id }.toSet()
                        val ok = res.allBlocks.size == expBlocks && expActive.all { it in gotActive } && expByp.all { it in gotByp } && expByp.none { it in gotActive }
                        if (ok) greyPass++ else failures.add("GREY $caseId act=$gotActive byp=$gotByp")
                    }
                    "adversarial_zero_collapse_noise" -> {
                        advTotal++
                        val expStatus = c["expected_parse_status"]!!.jsonPrimitive.content
                        val expIds = c["expected_active_chip_ids"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
                        val gotIds = res.activeChips.map { it.id }.toSet()
                        val ok = when (expStatus) {
                            "NO_SKILL" -> res.hasNoSkillBanner && gotIds.isEmpty()
                            "NOISE_REJECTED" -> !TosEnemySkillParserV2.isAuthenticEnemySkillScreen(
                                input.lines().filter { it.isNotBlank() }.map { OcrTextLine(it, 10, 10, 400, 36, 0.99f) },
                                DarkModalKind.ENEMY_LONG_PRESS_POPUP,
                                res
                            )
                            else -> res.allBlocks.isNotEmpty() &&
                                res.allBlocks.none { it.parseStatus == EnemySkillParseStatus.UNPARSED } &&
                                expIds.all { it in gotIds } &&
                                ("zero_combo" in expIds || "zero_combo" !in gotIds)
                        }
                        if (ok) advPass++ else failures.add("ADV $caseId gotIds=$gotIds blocks=${res.allBlocks.map { it.parseStatus }}")
                    }
                    "encyclopedia_98" -> {
                        encTotal++
                        if (res.allBlocks.isEmpty() || res.allBlocks.any { it.parseStatus == EnemySkillParseStatus.UNPARSED }) {
                            encUnparsed++
                            failures.add("ENC $caseId UNPARSED")
                        }
                    }
                }
            }

            assertTrue("A4 failures:\n${failures.joinToString("\n")}", failures.isEmpty())
            assertEquals(106, paramPass)
            assertEquals(36, greyPass)
            assertEquals(20, advPass)
            assertEquals(98, encTotal)
            assertEquals(0, encUnparsed)
        }

        val masterFile = resolveRepoFile("ml/datasets/enemy_skill_master_clean/unified_enemy_skill_master.json")
        if (masterFile != null) {
            val root = json.parseToJsonElement(masterFile.readText(Charsets.UTF_8)).jsonObject
            val skills = root["skills"]!!.jsonArray
            var total = 0
            var unparsed = 0
            var falseZero = 0
            var falseRace = 0
            val unparsedSamples = ArrayList<String>()
            for (el in skills) {
                val s = el.jsonObject
                val title = s["title_zh_tw"]!!.jsonPrimitive.content
                val body = s["clean_body_zh_tw"]!!.jsonPrimitive.content
                val full = "$title\n$body".trim()
                val res = parser.parsePlainText(full)
                total++
                if (res.allBlocks.isEmpty() || res.allBlocks.any { it.parseStatus == EnemySkillParseStatus.UNPARSED }) {
                    unparsed++
                    unparsedSamples.add("$title || ${body.take(50)}")
                }
                if (res.activeChips.any { it.id == "zero_combo" } && !full.contains("連擊") && !full.lowercase().contains("combo") && !full.contains("任何符石")) {
                    falseZero++
                }
                if (res.activeChips.any { it.id == "race_orb_shield" } && title.contains("轉化符石技能無效")) {
                    falseRace++
                }
            }
            assertEquals("False zero_combo in unified master", 0, falseZero)
            assertEquals("False race_orb_shield in unified master", 0, falseRace)
            assertTrue(
                "Too many UNPARSED in unified master ($unparsed/$total): ${unparsedSamples.take(15)}",
                unparsed <= (total * 0.02).toInt().coerceAtLeast(5)
            )
        }
    }
}
