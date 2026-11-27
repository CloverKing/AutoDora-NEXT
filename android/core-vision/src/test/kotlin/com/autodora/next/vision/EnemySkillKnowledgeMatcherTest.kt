package com.autodora.next.vision

import com.autodora.next.model.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class EnemySkillKnowledgeMatcherTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun resolveRepoFile(relativePath: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val f = File(dir, relativePath)
            if (f.isFile) return f
            dir = dir.parentFile
        }
        error("Could not locate $relativePath from ${File(".").absolutePath}")
    }

    private fun loadParser(): TosEnemySkillParserV2 {
        val f = resolveRepoFile("android/app/src/main/assets/tos_enemy_skill_rules_v2.json")
        return TosEnemySkillParserV2.fromJson(f.readText(Charsets.UTF_8))
    }

    private fun loadMatcher(): EnemySkillKnowledgeMatcher {
        val f = resolveRepoFile("android/app/src/main/assets/tos_authoritative_skills_499.json")
        val bundle = json.decodeFromString<AuthoritativeSkillBundle>(f.readText(Charsets.UTF_8))
        return EnemySkillKnowledgeMatcher(bundle.skills)
    }

    @Test
    fun `built-in 499 authoritative skills and 140 stage bundle deserialize cleanly with 3-level hierarchy`() {
        val matcher = loadMatcher()
        assertEquals(499, matcher.authoritativeList().size)

        val stageFile = resolveRepoFile("android/app/src/main/assets/tos_stage_wiki_bundle_140.json")
        val parser = loadParser()
        val scripts = StageWaveScriptParser.parseStageWikiJson(stageFile.readText(Charsets.UTF_8), parser)
        assertEquals(140, scripts.size)
        assertTrue(scripts.all { it.entryCategory in StageHierarchyHelper.ENTRY_ORDER })
        assertTrue(scripts.any { it.entryCategory == "古神遺跡" })
        assertTrue(scripts.any { it.entryCategory == "大型活動" })
    }

    @Test
    fun `v0_9_0 parser rules cover forbid_cross, max_combo, keep_element_at_least_one, max_element_orbs and square_3x3`() {
        val parser = loadParser()

        val forbidCross = parser.parsePlainText("【十之詛咒】首批消除任何十字形符石時，敵人攻擊力提升並連擊")
        assertTrue(forbidCross.activeChips.any { it.id == "forbid_cross" })

        val maxCombo = parser.parsePlainText("【連擊上限盾】達成 7 連擊或以上時，敵人會吸收所有傷害")
        val mcChip = maxCombo.activeChips.firstOrNull { it.id == "max_combo" }
        assertNotNull(mcChip)
        assertEquals(6, mcChip!!.paramInt)

        val keepOne = parser.parsePlainText("【心符石加護】消除所有心符石時敵人無敵，場上必須保留至少 1 粒心符石")
        val koChip = keepOne.activeChips.firstOrNull { it.id == "keep_element_at_least_one" }
        assertNotNull(koChip)
        assertEquals(Element.HEART.bitMask, koChip!!.paramElementMask and 0x3F)

        val maxOrbs = parser.parsePlainText("【限消水符石】每回合最多只能消除 5 粒水符石，超過時反擊")
        val moChip = maxOrbs.activeChips.firstOrNull { it.id == "max_element_orbs" }
        assertNotNull(moChip)
        assertEquals(5, moChip!!.paramInt)
        assertEquals(Element.WATER.bitMask, moChip.paramElementMask and 0x3F)

        val sq3 = parser.parsePlainText("【3×3正方盾】首批須以 3×3 正方形消除 9 粒暗符石")
        val sqChip = sq3.activeChips.firstOrNull { it.id == "square_3x3" }
        assertNotNull(sqChip)
    }

    @Test
    fun `tier-0 MANUAL_EDIT overrides live regex while tier-2 fuzzy fallback rescues OCR corruption and rehydrates live numbers`() {
        val parser = loadParser()
        val matcher = EnemySkillKnowledgeMatcher(
            authoritativeEntries = listOf(
                AuthoritativeSkillEntry(
                    hash = "auth_burst_template",
                    titleZhTw = "【火屬累積盾】",
                    cleanBodyZhTw = "累計消除 15 粒火符石才會對敵人造成傷害",
                    normTitle = "火屬累積盾",
                    normBody = "累計消除15粒火符石才會對敵人造成傷害"
                )
            )
        )

        // 1. Live OCR corrupted "消除" -> "消涂" so strict regex produces zero chips, AND live text says 9 水符石 (not 15 火!)
        val corruptedLive = parser.parsePlainText("【水屬累積盾】\n累計消涂 9 粒水符石才會對敵人造成傷害")
        assertTrue(corruptedLive.activeChips.isEmpty())

        val rescued = matcher.enrichParseResult(corruptedLive, parser)
        assertEquals(EnemySkillParseStatus.PARSED, rescued.allBlocks.single().parseStatus)
        val rescuedChip = rescued.activeChips.single()
        assertEquals("elem_burst_shield", rescuedChip.id)
        assertTrue("Fuzzy KB rescue must tag fromKnowledgeFallback=true (✦库)", rescuedChip.fromKnowledgeFallback)
        assertEquals("Must re-hydrate live number 9 instead of historical template 15", 9, rescuedChip.paramInt)
        assertEquals("Must re-hydrate live element WATER instead of historical FIRE", Element.WATER.bitMask, rescuedChip.paramElementMask and 0x3F)

        // 2. Tier-0 MANUAL_EDIT overrides an exact skill block with user-curated chip
        val rawCustom = "【特殊試煉】\n自訂手動覆寫測試敵技內容"
        val customBase = parser.parsePlainText(rawCustom)
        val customHash = customBase.allBlocks.single().skillHash
        matcher.updateLocalRecords(
            listOf(
                LocalCapturedSkillRecord(
                    skillHash = customHash,
                    titleZhTw = "特殊試煉",
                    rawBodyZhTw = "自訂手動覆寫測試敵技內容",
                    sourceType = "MANUAL_EDIT",
                    parseStatus = EnemySkillParseStatus.PARSED,
                    hitCount = 1,
                    mappedChips = listOf(
                        WaveShieldChip("exact_steps", "恰好 18 步", ShieldCategory.HAZARD_AND_PATH, paramInt = 18)
                    )
                )
            )
        )
        val manualApplied = matcher.enrichParseResult(customBase, parser)
        assertEquals("exact_steps", manualApplied.activeChips.single().id)
        assertEquals(18, manualApplied.activeChips.single().paramInt)
        assertFalse(manualApplied.activeChips.single().fromKnowledgeFallback)
    }
}
