package com.autodora.next.vision

import com.autodora.next.model.BoardVisualHints
import com.autodora.next.model.Element
import com.autodora.next.model.RaceType
import com.autodora.next.model.ShieldCategory
import com.autodora.next.model.WaveShieldChip
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs

@Serializable
data class EnemySkillRuleEntry(
    val id: String,
    val chipId: String,
    val label: String,
    val category: String,
    val pattern: String,
    val intGroup: Int = 0,
    val elementGroup: Int = 0,
    val defaultInt: Int = 0
)

@Serializable
data class EnemySkillRuleSet(
    val version: String = "2026.11-v2",
    val rules: List<EnemySkillRuleEntry> = emptyList()
)

object EnemySkillParseStatus {
    const val PARSED = "PARSED"
    const val NON_BOARD_KNOWN = "NON_BOARD_KNOWN"
    const val UNPARSED = "UNPARSED"
}

data class ParsedEnemySkillBlock(
    val skillHash: String,
    val titleZhTw: String,
    val rawBodyZhTw: String,
    val normalizedText: String,
    val isGreyBypassed: Boolean,
    val matchedRuleIds: List<String>,
    val chips: List<WaveShieldChip>,
    val visualHints: BoardVisualHints,
    val parseStatus: String
)

data class EnemySkillParseResult(
    val allBlocks: List<ParsedEnemySkillBlock>,
    val activeChips: List<WaveShieldChip>,
    val bypassedBlocks: List<ParsedEnemySkillBlock>,
    val combinedHints: BoardVisualHints,
    val hasNoSkillBanner: Boolean = false
)

/**
 * Upgraded 98-Skill Tower of Saviors Traditional/Simplified Enemy Skill Parser (Phase 4.2 V2).
 *
 * Guarantees strict separation between Knowledge Collection and Live Battle Strategy:
 *  - [EnemySkillParseResult.allBlocks] ALWAYS preserves EVERY non-chrome enemy skill verbatim
 *    (including greyed-out bypassed skills) for persistence into the local Room knowledge DB.
 *  - [EnemySkillParseResult.activeChips] dynamically EXCLUDES greyed-out bypassed skill blocks so
 *    bypassed skills never over-constrain the C++20 solver during battle.
 */
class TosEnemySkillParserV2(ruleSet: EnemySkillRuleSet) {

    val version: String = ruleSet.version
    val ruleCount: Int get() = compiled.size

    private data class CompiledRule(
        val entry: EnemySkillRuleEntry,
        val regex: Regex,
        val category: ShieldCategory
    )

    private val compiled: List<CompiledRule> = ruleSet.rules.mapNotNull { entry ->
        val regex = runCatching { Regex(entry.pattern, RegexOption.IGNORE_CASE) }.getOrNull() ?: return@mapNotNull null
        val cat = runCatching { ShieldCategory.valueOf(entry.category) }.getOrElse { ShieldCategory.COMBO }
        CompiledRule(entry, regex, cat)
    }

    /**
     * Parses OCR lines (carrying per-line greyed-out luminance flags) into structured skill blocks
     * and merged active battle WaveShieldChips.
     */
    fun parseLines(ocrLines: List<OcrTextLine>): EnemySkillParseResult {
        if (ocrLines.isEmpty()) {
            return EnemySkillParseResult(emptyList(), emptyList(), emptyList(), BoardVisualHints())
        }
        val blocks = segmentOcrLinesIntoBlocks(ocrLines)
        return evaluateSegmentedBlocks(blocks)
    }

    /**
     * Convenience entry point for plain text tests/tools (lines optionally prefixed with "[GREY] ").
     */
    fun parsePlainText(rawText: String, defaultGrey: Boolean = false): EnemySkillParseResult {
        val fakeLines = ArrayList<OcrTextLine>()
        var y = 10
        for (rawLine in rawText.replace('\r', '\n').lineSequence()) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) {
                y += 45 // blank line forces block boundary
                continue
            }
            val isGrey = defaultGrey || trimmed.startsWith("[GREY]", ignoreCase = true)
            val clean = if (trimmed.startsWith("[GREY]", ignoreCase = true)) {
                trimmed.substring(6).trim()
            } else {
                trimmed
            }
            if (clean.isEmpty()) continue
            fakeLines.add(
                OcrTextLine(
                    text = clean,
                    left = 20,
                    top = y,
                    right = 800,
                    bottom = y + 24,
                    confidence = 0.99f,
                    isGreyBypassed = isGrey
                )
            )
            y += 28
        }
        return parseLines(fakeLines)
    }

    private data class RawSkillSegment(
        val rawLines: List<String>,
        val isGreyBypassed: Boolean
    )

    private fun segmentOcrLinesIntoBlocks(ocrLines: List<OcrTextLine>): List<RawSkillSegment> {
        // Keep genuine skill body lines intact during segmentation so vertical gaps never fragment a block;
        // only drop hard UI chrome, stat lines, and self-Toast/modal banners per line.
        val rawKept = ocrLines.filter { line ->
            val raw = cleanRawOcrLine(line.text)
            val norm = normalizeText(raw)
            raw.length >= 2 &&
                !EDGE_NOISE_LINE_REGEX.matches(norm) &&
                !isChromeOrStatLine(norm) &&
                !NOISE_CONTAINS_PATTERNS.any { it.containsMatchIn(norm) }
        }
        if (rawKept.isEmpty()) return emptyList()

        // Merge wrapped split brackets like "【十之詛咒 10%·" + "生命禁咒】" before block segmentation.
        val kept = ArrayList<OcrTextLine>(rawKept.size)
        var i = 0
        while (i < rawKept.size) {
            val cur = rawKept[i]
            val curTrim = cur.text.trim()
            if (curTrim.startsWith("【") && !curTrim.contains("】") && i + 1 < rawKept.size) {
                val nxt = rawKept[i + 1]
                val nxtTrim = nxt.text.trim()
                if (nxtTrim.contains("】") && !startsSkillBlockMarker(nxtTrim) && cur.isGreyBypassed == nxt.isGreyBypassed) {
                    val splitAt = nxtTrim.indexOf('】')
                    val mergedTitle = curTrim + nxtTrim.substring(0, splitAt + 1)
                    val trailing = nxtTrim.substring(splitAt + 1).trim()
                    kept.add(
                        cur.copy(
                            text = mergedTitle,
                            right = maxOf(cur.right, nxt.right),
                            bottom = if (trailing.isEmpty()) maxOf(cur.bottom, nxt.bottom) else cur.bottom
                        )
                    )
                    if (trailing.length >= 2) {
                        kept.add(nxt.copy(text = trailing))
                    }
                    i += 2
                    continue
                }
            }
            kept.add(cur.copy(text = cleanRawOcrLine(curTrim)))
            i++
        }
        if (kept.isEmpty()) return emptyList()

        val heights = kept.map { (it.bottom - it.top).coerceAtLeast(12) }.sorted()
        val medianH = heights[heights.size / 2].toFloat()

        val segments = ArrayList<RawSkillSegment>()
        var currentLines = ArrayList<String>()
        var currentGrey = kept[0].isGreyBypassed
        var prevBottom = kept[0].bottom

        for ((idx, line) in kept.withIndex()) {
            val raw = line.text.trim()
            val startsNewMarker = idx > 0 && startsSkillBlockMarker(raw)
            val verticalGap = if (idx > 0) (line.top - prevBottom).toFloat() else 0f
            val largeVerticalGap = idx > 0 && verticalGap > medianH * 1.55f
            val greyTransition = idx > 0 && line.isGreyBypassed != currentGrey

            // Keep a standalone 【Skill Title】 joined with its immediately following body lines
            val curIsStandaloneTitle = currentLines.size == 1 &&
                STANDALONE_BRACKET_TITLE_REGEX.matches(currentLines[0].trim())
            val keepTitleWithBody = curIsStandaloneTitle &&
                !startsNewMarker &&
                !greyTransition &&
                verticalGap <= medianH * 2.8f

            if (currentLines.isNotEmpty() && !keepTitleWithBody && (startsNewMarker || largeVerticalGap || greyTransition)) {
                segments.add(RawSkillSegment(currentLines.toList(), currentGrey))
                currentLines = ArrayList()
                currentGrey = line.isGreyBypassed
            }
            currentLines.add(raw)
            prevBottom = line.bottom
        }
        if (currentLines.isNotEmpty()) {
            segments.add(RawSkillSegment(currentLines.toList(), currentGrey))
        }
        return segments
    }

    private fun evaluateSegmentedBlocks(segments: List<RawSkillSegment>): EnemySkillParseResult {
        val parsedBlocks = ArrayList<ParsedEnemySkillBlock>(segments.size)
        val bypassedBlocks = ArrayList<ParsedEnemySkillBlock>()
        var combinedHints = BoardVisualHints()
        var hasNoSkillBanner = false

        val mergedActiveMap = LinkedHashMap<String, WaveShieldChip>()

        for (seg in segments) {
            val rawBody = unwrapGameLayoutLines(seg.rawLines)
            val norm = normalizeText(rawBody)
            if (norm.isBlank()) continue
            if (norm == "没有技能" || norm == "无技能") {
                hasNoSkillBanner = true
                continue
            }

            val title = extractSkillTitle(seg.rawLines)
            if (isLikelyNoiseEntry(title, rawBody)) continue

            val blockMatchedIds = ArrayList<String>()
            val blockChips = ArrayList<WaveShieldChip>()

            // 1. Run typed extractors first for complex parameterized ToS mechanics
            extractTypedChips(norm, blockMatchedIds, blockChips)

            // 2. Run JSON regex rules
            applyJsonRules(norm, blockMatchedIds, blockChips)

            // 3. Deduplicate & arbitrate within this block
            val canonicalBlockChips = consolidateChipList(blockChips)
            val hints = extractBoardVisualHints(norm)
            val isKnownNonBoard = canonicalBlockChips.isEmpty() && !hasAnyVisualHint(hints) && matchesKnownNonBoardSkill(norm)

            val parseStatus = when {
                canonicalBlockChips.isNotEmpty() || hasAnyVisualHint(hints) -> EnemySkillParseStatus.PARSED
                isKnownNonBoard -> EnemySkillParseStatus.NON_BOARD_KNOWN
                else -> EnemySkillParseStatus.UNPARSED
            }

            val block = ParsedEnemySkillBlock(
                skillHash = computeSkillHash(title, norm),
                titleZhTw = title,
                rawBodyZhTw = rawBody,
                normalizedText = norm,
                isGreyBypassed = seg.isGreyBypassed,
                matchedRuleIds = blockMatchedIds.distinct(),
                chips = canonicalBlockChips,
                visualHints = hints,
                parseStatus = parseStatus
            )
            parsedBlocks.add(block)

            if (seg.isGreyBypassed) {
                bypassedBlocks.add(block)
            } else {
                combinedHints = combinedHints.mergeWith(hints)
                for (chip in canonicalBlockChips) {
                    mergeChipIntoGlobalMap(mergedActiveMap, chip)
                }
            }
        }

        val finalActiveChips = consolidateChipList(mergedActiveMap.values.toList())
        return EnemySkillParseResult(
            allBlocks = parsedBlocks,
            activeChips = finalActiveChips,
            bypassedBlocks = bypassedBlocks,
            combinedHints = combinedHints,
            hasNoSkillBanner = hasNoSkillBanner
        )
    }

    private fun extractTypedChips(
        norm: String,
        matchedIds: MutableList<String>,
        chips: MutableList<WaveShieldChip>
    ) {
        // A. Puzzle Shield (#1 指定形狀盾 / 強化拼圖盾 / 嚴格拼圖 / 屬性拼圖 / 屬性限制形狀盾)
        if (norm.contains("拼图") || norm.contains("指定形状") || norm.contains("形状盾") || norm.contains("指定位置消除")) {
            val strict = norm.contains("不可黏连") || norm.contains("不可相连") || norm.contains("不可连同") ||
                norm.contains("严格") || norm.contains("限制形状盾") ||
                Regex("拼图盾\\s*[-·]?\\s*限").containsMatchIn(norm) || norm.contains("指定形状")
            val ench = norm.contains("强化")
            val elemMatch = Regex("([水火木光暗心])(?:属性)?(?:强化|限制){0,2}[^\\n]{0,14}?(?:拼图|指定形状|形状盾)|(?:拼图|指定形状|形状盾|指定位置消除)[^\\n]{0,14}?([水火木光暗心])(?:属性|强化)?符石")
                .find(norm)
            val elemChar = elemMatch?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.firstOrNull()
            val elemCode = elemChar?.let { charToElementCode(it) } ?: -1
            val packed = (if (strict) 1 else 0) or
                (if (ench) 2 else 0) or
                (if (elemCode in 0..5) ((elemCode + 1) shl 2) else 0)
            matchedIds.add("typed_puzzle_shield")
            chips.add(
                WaveShieldChip(
                    id = "puzzle_shield",
                    label = if (strict) "严格拼图盾" else "普通拼图盾",
                    category = ShieldCategory.SHAPE_AND_PUZZLE,
                    autoDetected = true,
                    paramInt = packed
                )
            )
        }

        // B. Step Range (#60 步數盾 e.g. 25~30 步 / 25至30步) & Exact Steps
        val stepRangeMatch = Regex("(?<!\\d)(\\d{1,3})\\s*(?:至|[-~～]|到)\\s*(\\d{1,3})\\s*步").find(norm)
        if (stepRangeMatch != null) {
            val a = stepRangeMatch.groupValues[1].toIntOrNull()?.coerceIn(1, 200)
            val b = stepRangeMatch.groupValues[2].toIntOrNull()?.coerceIn(1, 200)
            if (a != null && b != null) {
                val lo = minOf(a, b)
                val hi = maxOf(a, b)
                matchedIds.add("typed_step_range")
                chips.add(
                    WaveShieldChip(
                        id = "step_range",
                        label = "步数盾 $lo-$hi",
                        category = ShieldCategory.HAZARD_AND_PATH,
                        autoDetected = true,
                        paramInt = (lo and 0xFF) or ((hi and 0xFF) shl 8)
                    )
                )
            }
        } else {
            val exactStepsMatch = Regex("(?:刚好|恰好|指定|正好)\\s*(\\d{1,3})\\s*步").find(norm)
            val sVal = exactStepsMatch?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 200)
            if (sVal != null) {
                matchedIds.add("typed_exact_steps")
                chips.add(
                    WaveShieldChip(
                        id = "exact_steps",
                        label = "恰好 $sVal 步",
                        category = ShieldCategory.HAZARD_AND_PATH,
                        autoDetected = true,
                        paramInt = sVal
                    )
                )
            }
        }

        // C. Equal Combo Elements (#9 連擊相等盾 — generalized 2..6 colors)
        if (Regex("(?:连击相等|同等\\s*(?:combo|c)|连击数(?:须)?相等|combo数(?:须)?相等|同等连击|相同组数的?五属)", RegexOption.IGNORE_CASE).containsMatchIn(norm)) {
            val effMask = if (norm.contains("五属") || norm.contains("5属")) {
                0x1F
            } else {
                val mask = extractElementMaskNearKeywords(norm)
                if (Integer.bitCount(mask) >= 2) mask else ((1 shl Element.LIGHT.code) or (1 shl Element.DARK.code))
            }
            matchedIds.add("typed_equal_combo_elements")
            chips.add(
                WaveShieldChip(
                    id = "equal_pair_light_dark",
                    label = "${formatElementsCn(effMask)}同等 C",
                    category = ShieldCategory.COMBO,
                    autoDetected = true,
                    paramElementMask = 0x100 or effMask
                )
            )
        }

        // D. Race Orb Shield (#35/general 种族符石盾 -> exact RaceType.bitMask in paramInt)
        val isTransformRaceExempt = norm.contains("转化符石技能无效") || norm.contains("转化种族符石而不改变")
        if (!isTransformRaceExempt &&
            Regex("(?:种族|人族|神族|魔族|兽类|兽族|龙类|龙族|妖精|机械族?)[^\\n]{0,6}?符石").containsMatchIn(norm) &&
            (norm.contains("盾") || norm.contains("消除") || norm.contains("造成伤害"))
        ) {
            var raceMask = 0
            if (norm.contains("神族") || norm.contains("神")) {
                if (Regex("神(?:族|\\.魔|、|及|与|与|和)?").containsMatchIn(norm) && norm.contains("符石")) {
                    // Check explicit race keywords carefully so "神魔之塔" won't trigger
                    if (norm.contains("神族") || Regex("[神魔人兽龙妖机][、,及与和]+[神魔人兽龙妖机]").containsMatchIn(norm)) {
                        raceMask = raceMask or RaceType.GOD.bitMask
                    }
                }
            }
            if (norm.contains("魔族")) raceMask = raceMask or RaceType.DEMON.bitMask
            if (norm.contains("人族") || norm.contains("人类")) raceMask = raceMask or RaceType.HUMAN.bitMask
            if (norm.contains("兽类") || norm.contains("兽族")) raceMask = raceMask or RaceType.BEAST.bitMask
            if (norm.contains("龙类") || norm.contains("龙族")) raceMask = raceMask or RaceType.DRAGON.bitMask
            if (norm.contains("妖精")) raceMask = raceMask or RaceType.ELF.bitMask
            if (norm.contains("机械")) raceMask = raceMask or RaceType.MACHINA.bitMask
            val listMatch = Regex("([神魔人兽龙妖机](?:[、,及与和/][神魔人兽龙妖机])+)").find(norm)
            if (listMatch != null) {
                for (ch in listMatch.groupValues[1]) {
                    when (ch) {
                        '神' -> raceMask = raceMask or RaceType.GOD.bitMask
                        '魔' -> raceMask = raceMask or RaceType.DEMON.bitMask
                        '人' -> raceMask = raceMask or RaceType.HUMAN.bitMask
                        '兽' -> raceMask = raceMask or RaceType.BEAST.bitMask
                        '龙' -> raceMask = raceMask or RaceType.DRAGON.bitMask
                        '妖' -> raceMask = raceMask or RaceType.ELF.bitMask
                        '机' -> raceMask = raceMask or RaceType.MACHINA.bitMask
                    }
                }
            }
            val elemMask = extractElementMaskNearKeywords(norm)
            matchedIds.add("typed_race_orb_shield")
            chips.add(
                WaveShieldChip(
                    id = "race_orb_shield",
                    label = "种族符石首消",
                    category = ShieldCategory.COUNT_AND_ELEMENT,
                    autoDetected = true,
                    paramInt = raceMask and 0x7F,
                    paramElementMask = elemMask
                )
            )
        }

        // E. Explicit multi-attribute shield (#3 強化突破, #4-#7 二/三/四/五屬盾 & compound variants)
        val hasElemShieldTrigger = norm.contains("同时消除") ||
            Regex("(?:二|三|四|两|2|3|4)属(?:强化)?盾").containsMatchIn(norm) ||
            Regex("只受.+?属性.+?攻击").containsMatchIn(norm) ||
            Regex("属性符石(?:才可|方可)造成").containsMatchIn(norm)
        if (hasElemShieldTrigger && !norm.contains("五属") && !norm.contains("5属") &&
            !norm.contains("连击相等") && !norm.contains("同等")
        ) {
            val mask = extractElementMaskNearKeywords(norm)
            if (Integer.bitCount(mask) in 2..5 || (Integer.bitCount(mask) >= 1 && norm.contains("强化") && norm.contains("突破"))) {
                val isFirstWaveStrict = Regex("首[批消][^\\n]{0,12}?同时消除").containsMatchIn(norm)
                val allEnch = norm.contains("强化")
                // Support partial enchanted list like "水火强化及暗符石" if present, else apply to mask
                val enchMask = if (allEnch) extractEnchantedSubMask(norm, mask) else 0
                val packedMask = mask or ((enchMask and 0x3F) shl 8)
                var pInt = 0
                if (allEnch && enchMask == mask) pInt = pInt or 1
                if (isFirstWaveStrict) pInt = pInt or 2
                matchedIds.add("typed_require_elements")
                chips.add(
                    WaveShieldChip(
                        id = "require_elements",
                        label = formatElementsCn(mask) + if (enchMask != 0) "强" else "盾",
                        category = ShieldCategory.COUNT_AND_ELEMENT,
                        autoDetected = true,
                        paramInt = pInt,
                        paramElementMask = packedMask
                    )
                )
            }
        }

        // F. Anti-first-wave (#10 反首消符石盾 / #46 符石連擊零化 / 木連擊零化) with enumerated separator list
        val zeroStrikeBodyElemMatch = Regex(
            "首[批消]消除[^\\n]{0,8}?([水火木光暗心](?:[、,与及和\\s/]*[水火木光暗心]){0,5})符石[\\s\\S]{0,48}?(?:攻击力变\\s*0|攻击力归\\s*0|攻击无效)"
        ).find(norm)
        val zeroStrikeTitleElemMatch = Regex(
            "([水火木光暗心](?:[、,与及和\\s/]*[水火木光暗心]){0,5})连击零化"
        ).find(norm)
        val antiListMatch = Regex("(?:反首消|首[批消][^\\n]{0,6}?禁消|不可首[批消]消除|符石连击零化)[^\\n]{0,18}?([水火木光暗心](?:[、,与及和\\s/]*[水火木光暗心]){0,5})").find(norm)
        val antiElemStr = zeroStrikeBodyElemMatch?.groupValues?.getOrNull(1)
            ?: antiListMatch?.groupValues?.getOrNull(1)
            ?: zeroStrikeTitleElemMatch?.groupValues?.getOrNull(1)
        if (!antiElemStr.isNullOrBlank()) {
            val mask = parseElementMask(antiElemStr)
            if (mask != 0) {
                matchedIds.add("typed_anti_first_wave")
                chips.add(
                    WaveShieldChip(
                        id = "anti_first_wave",
                        label = "反首消${formatElementsCn(mask)}",
                        category = ShieldCategory.COUNT_AND_ELEMENT,
                        autoDetected = true,
                        paramElementMask = mask
                    )
                )
            }
        }

        // G. Forbid-all (#62 回復顛倒 / 禁消) with enumerated list
        if (norm.contains("回复颠倒") || norm.contains("回复力变为伤害")) {
            matchedIds.add("typed_recovery_reversal")
            chips.add(
                WaveShieldChip(
                    id = "forbid_all",
                    label = "禁消心(回复颠倒)",
                    category = ShieldCategory.COUNT_AND_ELEMENT,
                    autoDetected = true,
                    paramElementMask = 1 shl Element.HEART.code
                )
            )
        } else {
            val forbidListMatch = Regex("(?:无法消除|全禁消|(?<!首[批消])不可消除)[^\\n]{0,14}?([水火木光暗心](?:[、,与及和\\s/]*[水火木光暗心]){0,4})(?:属性)?符石").find(norm)
            if (forbidListMatch != null && !norm.contains("禁咒")) {
                val mask = parseElementMask(forbidListMatch.groupValues[1])
                if (mask != 0) {
                    matchedIds.add("typed_forbid_all")
                    chips.add(
                        WaveShieldChip(
                            id = "forbid_all",
                            label = "禁消${formatElementsCn(mask)}",
                            category = ShieldCategory.COUNT_AND_ELEMENT,
                            autoDetected = true,
                            paramElementMask = mask
                        )
                    )
                }
            }
        }

        // H. Clear-All Element (#12 全消盾) with enumerated list
        val clearAllTitleMatch = Regex("全消([水火木光暗心]{1,3})盾").find(norm)
        val clearAllBodyMatch = Regex("(?:全消盾|首[批消]消除(?:场上)?所有)[^\\n]{0,12}?([水火木光暗心](?:[、,与及和\\s/]*[水火木光暗心]){0,4})").find(norm)
        val clearAllChars = clearAllTitleMatch?.groupValues?.getOrNull(1) ?: clearAllBodyMatch?.groupValues?.getOrNull(1)
        if (!clearAllChars.isNullOrBlank()) {
            val mask = parseElementMask(clearAllChars)
            if (mask != 0) {
                matchedIds.add("typed_clear_all_element")
                chips.add(
                    WaveShieldChip(
                        id = "clear_all_element",
                        label = "首消全${formatElementsCn(mask)}",
                        category = ShieldCategory.COUNT_AND_ELEMENT,
                        autoDetected = true,
                        paramElementMask = mask
                    )
                )
            }
        }

        // I. Cross (#14), L (#16), T (#15) with optional explicit elements, group count & distinct flag
        val hasBracketCrossBody = norm.contains("【十】形") &&
            !norm.contains("十之诅咒") &&
            (norm.contains("造成伤害") || norm.contains("十字限") || norm.contains("十字盾") || norm.contains("十字消除"))
        if ((norm.contains("十字") || hasBracketCrossBody) && (norm.contains("相异") || norm.contains("不同"))) {
            val explicitCnt = Regex("(?:≥|>=)?\\s*([23])\\s*组").find(norm)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val cnt = explicitCnt ?: when {
                Regex("(?:三|3)\\s*(?:组|个)?\\s*十字").containsMatchIn(norm) -> 3
                Regex("(?:双|二|2)\\s*(?:组|个)?\\s*十字").containsMatchIn(norm) -> 2
                else -> 2
            }
            val em = extractElementMaskNearKeywords(norm)
            matchedIds.add("typed_cross_distinct")
            chips.add(
                WaveShieldChip(
                    id = "cross_shield",
                    label = "${cnt}相异十字盾",
                    category = ShieldCategory.SHAPE_AND_PUZZLE,
                    autoDetected = true,
                    paramInt = cnt or 0x20,
                    paramElementMask = em
                )
            )
        } else if ((norm.contains("十字") || hasBracketCrossBody) && extractElementMaskNearKeywords(norm) != 0) {
            val em = extractElementMaskNearKeywords(norm)
            val explicitCnt = Regex("(?:≥|>=)?\\s*([23])\\s*组").find(norm)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val cnt = explicitCnt ?: when {
                Regex("(?:三|3)\\s*(?:组|个)?\\s*十字").containsMatchIn(norm) -> 3
                Regex("(?:双|二|2)\\s*(?:组|个)?\\s*十字").containsMatchIn(norm) -> 2
                else -> 1
            }
            matchedIds.add("typed_cross_elem")
            chips.add(
                WaveShieldChip(
                    id = "cross_shield",
                    label = "${formatElementsCn(em)}十字盾",
                    category = ShieldCategory.SHAPE_AND_PUZZLE,
                    autoDetected = true,
                    paramInt = cnt,
                    paramElementMask = em
                )
            )
        }
        val hasShapeExplicitCount = Regex("(?:≥|>=)[ \\t]*([23])[ \\t]*组").containsMatchIn(norm)
        if (Regex("(?:(?<![a-zA-Z])l[ \\t]*[形字])(?:限?盾|消除)", RegexOption.IGNORE_CASE).containsMatchIn(norm)) {
            val em = extractElementMaskNearKeywords(norm)
            if (em != 0 || hasShapeExplicitCount) {
                matchedIds.add("typed_l_shape_elem")
                chips.add(
                    WaveShieldChip(
                        id = "l_shape",
                        label = "${if (em != 0) formatElementsCn(em) + " " else ""}L形盾",
                        category = ShieldCategory.SHAPE_AND_PUZZLE,
                        autoDetected = true,
                        paramInt = 0,
                        paramElementMask = em
                    )
                )
            }
        }
        if (Regex("(?:(?<![a-zA-Z])t[ \\t]*[形字])(?:限?盾|消除)", RegexOption.IGNORE_CASE).containsMatchIn(norm)) {
            val em = extractElementMaskNearKeywords(norm)
            if (em != 0 || hasShapeExplicitCount) {
                matchedIds.add("typed_t_shape_elem")
                chips.add(
                    WaveShieldChip(
                        id = "t_shape",
                        label = "${if (em != 0) formatElementsCn(em) + " " else ""}T形盾",
                        category = ShieldCategory.SHAPE_AND_PUZZLE,
                        autoDetected = true,
                        paramInt = 0,
                        paramElementMask = em
                    )
                )
            }
        }

        // J. #64 凝護 (e.g. 灵之凝护 / 心之凝护) & Elemental Orb Armor Shield (e.g. 【木符石護盾 30 粒】)
        val elemArmorMatch = Regex("([水火木光暗心])符石护盾[^\\n]{0,8}?(\\d{1,3})").find(norm)
        if (elemArmorMatch != null) {
            val code = charToElementCode(elemArmorMatch.groupValues[1].first())
            val nVal = elemArmorMatch.groupValues[2].toIntOrNull()?.coerceIn(3, 15) ?: 9
            if (code >= 0) {
                matchedIds.add("typed_elem_armor_shield")
                chips.add(
                    WaveShieldChip(
                        id = "elem_burst_shield",
                        label = "${elemArmorMatch.groupValues[1]}符石护盾",
                        category = ShieldCategory.COUNT_AND_ELEMENT,
                        autoDetected = true,
                        paramInt = nVal,
                        paramElementMask = 1 shl code
                    )
                )
            }
        }
        val condenseMatch = Regex("([水火木光暗心灵])之凝护").find(norm)
        if (condenseMatch != null) {
            val el = when (condenseMatch.groupValues[1]) {
                "水" -> Element.WATER
                "火" -> Element.FIRE
                "木" -> Element.WOOD
                "光" -> Element.LIGHT
                "暗" -> Element.DARK
                else -> Element.HEART
            }
            matchedIds.add("typed_condense_shield")
            chips.add(
                WaveShieldChip(
                    id = "elem_burst_shield",
                    label = "${condenseMatch.groupValues[1]}之凝护",
                    category = ShieldCategory.COUNT_AND_ELEMENT,
                    autoDetected = true,
                    paramInt = 9,
                    paramElementMask = el.bitMask
                )
            )
        }

        // K. Elemental Death-Rattle Gauges #89-#93 (冰魄暴劫, 熔焰焚爆, 风刃蔓藤, 圣剑裁决, 深渊灭袭)
        val deathRattlePairs = listOf(
            "冰魄暴劫" to (Element.WATER to Element.WOOD),
            "熔焰焚爆" to (Element.FIRE to Element.WATER),
            "风刃蔓藤" to (Element.WOOD to Element.FIRE),
            "圣剑裁决" to (Element.LIGHT to Element.DARK),
            "深渊灭袭" to (Element.DARK to Element.LIGHT)
        )
        for ((kw, pair) in deathRattlePairs) {
            if (norm.contains(kw)) {
                val (badEl, goodEl) = pair
                matchedIds.add("typed_gauge_$kw")
                chips.add(WaveShieldChip("forbid_all", "避消(${kw})", ShieldCategory.COUNT_AND_ELEMENT, true, paramElementMask = badEl.bitMask))
                chips.add(WaveShieldChip("elem_burst_shield", "减伤(${kw})", ShieldCategory.COUNT_AND_ELEMENT, true, paramInt = 6, paramElementMask = goodEl.bitMask))
            }
        }

        // L. #79 温律临界
        if (norm.contains("温律临界")) {
            if (norm.contains("降低温度")) {
                val m = Regex("([水火木光暗心])符石[^\\n]{0,8}降低").find(norm)?.groupValues?.getOrNull(1)
                val code = m?.firstOrNull()?.let { charToElementCode(it) } ?: Element.WATER.code
                matchedIds.add("typed_temp_critical")
                chips.add(WaveShieldChip("elem_burst_shield", "温律临界", ShieldCategory.COUNT_AND_ELEMENT, true, paramInt = 5, paramElementMask = 1 shl code))
            }
        }
    }

    private fun applyJsonRules(
        norm: String,
        matchedIds: MutableList<String>,
        chips: MutableList<WaveShieldChip>
    ) {
        val currentIds = chips.map { it.id }.toHashSet()
        for (rule in compiled) {
            val matches = rule.regex.findAll(norm).toList()
            if (matches.isEmpty()) continue
            val entry = rule.entry

            // Skip redundant generic fallback rules when a stronger/more specific variant already matched
            if (entry.chipId == "loose_puzzle" && "puzzle_shield" in currentIds) continue
            if (entry.chipId == "strict_puzzle" && "puzzle_shield" in currentIds) continue
            if (entry.chipId == "enchanted_puzzle" && "puzzle_shield" in currentIds) continue
            if (entry.chipId == "five_elements" && ("five_elements_enchanted" in currentIds || "five_elements" in currentIds)) continue
            if (entry.chipId == "cross_shield" && "cross_shield" in currentIds) continue
            if (entry.chipId == "l_shape" && "l_shape" in currentIds) continue
            if (entry.chipId == "t_shape" && "t_shape" in currentIds) continue
            if (entry.chipId == "exact_combo" && "exact_first_combo" in currentIds) continue
            if (entry.chipId == "min_combo" && "min_first_combo" in currentIds) continue
            if (entry.chipId == "step_limit" && ("step_range" in currentIds || "exact_steps" in currentIds)) continue
            if (entry.chipId == "elem_burst_shield" && "elem_burst_shield" in currentIds) continue
            if (entry.chipId == "digital_orb_count" && (norm.contains("累积") || norm.contains("护盾"))) continue

            // If a non-merge rule has multiple matches (e.g. title without digit + body with digit),
            // prefer the match that captures an explicit int parameter before falling back to match #1.
            val orderedMatches = if (entry.intGroup > 0 && entry.chipId !in MASK_MERGE_CHIP_IDS && matches.size > 1) {
                val withInt = matches.filter { m ->
                    (entry.intGroup until m.groupValues.size).any { idx -> m.groupValues[idx].toIntOrNull() != null }
                }
                withInt.ifEmpty { matches }
            } else {
                matches
            }

            for (match in orderedMatches) {
                var paramInt = entry.defaultInt
                if (entry.intGroup > 0) {
                    val extracted = (entry.intGroup until match.groupValues.size)
                        .firstNotNullOfOrNull { idx -> match.groupValues.getOrNull(idx)?.toIntOrNull() }
                    if (extracted != null) paramInt = extracted
                }
                if (entry.chipId == "digital_orb_count" && paramInt !in 1..30) continue
                // Detect cumulative / total-combo parity scope when odd/even combo specifies 累积/合共
                if (entry.chipId == "odd_combo" || entry.chipId == "even_combo") {
                    paramInt = if (norm.contains("累积") || norm.contains("合共") || norm.contains("总共")) 1 else 0
                }

                var elemMask = 0
                var elemText = ""
                if (entry.elementGroup > 0) {
                    val rawElems = (entry.elementGroup until match.groupValues.size)
                        .mapNotNull { idx -> match.groupValues.getOrNull(idx)?.takeIf { it.isNotEmpty() } }
                        .firstOrNull { parseElementMask(it) != 0 }
                        .orEmpty()
                    elemText = rawElems
                    elemMask = parseElementMask(rawElems)
                    if (elemMask == 0) continue
                }

                // Avoid misclassifying unmatchable "无法消除" as forbid_all if already parsed as unmatchable
                val finalLabel = entry.label
                    .replace("{n}", paramInt.toString())
                    .replace("{elem}", elemText)

                chips.add(
                    WaveShieldChip(
                        id = entry.chipId,
                        label = finalLabel,
                        category = rule.category,
                        autoDetected = true,
                        paramInt = paramInt,
                        paramElementMask = elemMask
                    )
                )
                currentIds.add(entry.chipId)
                if (entry.id !in matchedIds) matchedIds.add(entry.id)
                if (entry.chipId !in MASK_MERGE_CHIP_IDS) break
            }
        }
    }

    private fun extractEnchantedSubMask(norm: String, totalMask: Int): Int {
        var enchMask = 0
        val segs = Regex("([水火木光暗心](?:[、,与及和\\s]*[水火木光暗心])*)强化").findAll(norm)
        for (m in segs) {
            enchMask = enchMask or parseElementMask(m.groupValues[1])
        }
        return if (enchMask != 0) (enchMask and totalMask) else totalMask
    }

    private fun consolidateChipList(raw: List<WaveShieldChip>): List<WaveShieldChip> {
        if (raw.isEmpty()) return emptyList()
        val byKey = LinkedHashMap<String, WaveShieldChip>()
        for (chip in raw) {
            mergeChipIntoGlobalMap(byKey, chip)
        }
        // Cross-rule precedence filtering: more specific rule suppresses weaker duplicate
        val ids = byKey.values.map { it.id }.toSet()
        return byKey.values.filter { chip ->
            when (chip.id) {
                "five_elements" -> "five_elements_enchanted" !in ids
                "exact_combo" -> "exact_first_combo" !in ids
                "min_combo" -> "min_first_combo" !in ids && "exact_first_combo" !in ids && "exact_combo" !in ids
                "loose_puzzle", "strict_puzzle", "enchanted_puzzle" -> "puzzle_shield" !in ids
                "step_limit" -> "step_range" !in ids && "exact_steps" !in ids
                "first_wave_only" -> "no_cascade" !in ids
                else -> true
            }
        }
    }

    private fun mergeChipIntoGlobalMap(
        target: MutableMap<String, WaveShieldChip>,
        incoming: WaveShieldChip
    ) {
        if (incoming.id in MASK_MERGE_CHIP_IDS && incoming.paramElementMask != 0) {
            val prev = target[incoming.id]
            if (prev == null) {
                target[incoming.id] = incoming
            } else if (incoming.id == "require_elements") {
                val reqMask = (prev.paramElementMask and 0x3F) or (incoming.paramElementMask and 0x3F)
                val enchMask = ((prev.paramElementMask ushr 8) and 0x3F) or ((incoming.paramElementMask ushr 8) and 0x3F) or
                    (if ((prev.paramInt and 1) != 0) (prev.paramElementMask and 0x3F) else 0) or
                    (if ((incoming.paramInt and 1) != 0) (incoming.paramElementMask and 0x3F) else 0)
                val mergedInt = (prev.paramInt or incoming.paramInt) and (if (enchMask == reqMask) 3 else 2)
                target[incoming.id] = prev.copy(
                    paramInt = mergedInt,
                    paramElementMask = reqMask or ((enchMask and 0x3F) shl 8)
                )
            } else {
                target[incoming.id] = prev.copy(
                    paramInt = maxOf(prev.paramInt, incoming.paramInt),
                    paramElementMask = prev.paramElementMask or incoming.paramElementMask
                )
            }
            return
        }

        val prev = target[incoming.id]
        if (prev == null) {
            target[incoming.id] = incoming
            return
        }
        target[incoming.id] = when (incoming.id) {
            "cross_shield" -> {
                val cnt = maxOf(prev.paramInt and 0x0F, incoming.paramInt and 0x0F)
                val dist = (prev.paramInt and 0x20) or (incoming.paramInt and 0x20)
                prev.copy(
                    paramInt = cnt or dist,
                    paramElementMask = prev.paramElementMask or incoming.paramElementMask
                )
            }
            "puzzle_shield" -> {
                val packed = (prev.paramInt and 3) or (incoming.paramInt and 3) or
                    (if ((incoming.paramInt ushr 2) != 0) (incoming.paramInt and 0x3C) else (prev.paramInt and 0x3C))
                prev.copy(paramInt = packed)
            }
            "race_orb_shield" -> prev.copy(
                paramInt = (prev.paramInt and 0x7F) or (incoming.paramInt and 0x7F),
                paramElementMask = prev.paramElementMask or incoming.paramElementMask
            )
            "equal_pair_light_dark" -> prev.copy(
                paramElementMask = 0x100 or (prev.paramElementMask and 0x3F) or (incoming.paramElementMask and 0x3F)
            )
            "l_shape", "t_shape" -> prev.copy(
                paramInt = maxOf(prev.paramInt, incoming.paramInt),
                paramElementMask = prev.paramElementMask or incoming.paramElementMask
            )
            else -> {
                val base = if (incoming.paramInt >= prev.paramInt) incoming else prev
                base.copy(paramElementMask = prev.paramElementMask or incoming.paramElementMask)
            }
        }
    }

    private fun extractBoardVisualHints(norm: String): BoardVisualHints = BoardVisualHints(
        hasMonochrome = norm.contains("幽闭空间") || norm.contains("黑白"),
        hasDisguise = norm.contains("移形换影") || norm.contains("伪装成"),
        hasBurning = norm.contains("燃烧"),
        hasScorching = norm.contains("灼热地") || norm.contains("灼热地形"),
        hasElectrified = norm.contains("电击") && (norm.contains("符石") || norm.contains("电击之") || norm.contains("触碰") || norm.contains("电击水") || norm.contains("电击火") || norm.contains("电击木") || norm.contains("电击光") || norm.contains("电击暗")),
        hasSticky = norm.contains("黏腐") || norm.contains("粘腐") || norm.contains("连环光牢") || norm.contains("黏附") || norm.contains("粘附"),
        hasStorm = norm.contains("暴风"),
        hasShattered = norm.contains("碎裂"),
        hasBlackHole = norm.contains("黑洞"),
        hasBloodbath = norm.contains("血浴"),
        hasBarrier = norm.contains("结界地") || norm.contains("结界地形"),
        hasPurgatoryFire = norm.contains("炼狱之火"),
        hasMud = norm.contains("泥沼"),
        hasMiasma = norm.contains("瘴气"),
        hasHiddenOrbs = norm.contains("隐藏符石") || norm.contains("隐匿之途") ||
            norm.contains("漆黑照明") || norm.contains("漆黑结聚") ||
            norm.contains("迷雾") || norm.contains("雾化") || norm.contains("墨汁") ||
            norm.contains("才会显示场上的符石") || norm.contains("转为隐"),
        hasFrozenOrPetrified = norm.contains("冻结") || norm.contains("冰封") ||
            norm.contains("石化") || norm.contains("凝结") || norm.contains("锁定场上"),
        hasWeatheredOrBomb = norm.contains("风化") || norm.contains("爆破炸弹") ||
            norm.contains("化血符石") || norm.contains("缩时地") || norm.contains("之幻界"),
        hasSpecialMarkOrFruit = norm.contains("力的印记") || norm.contains("虚之印记") ||
            norm.contains("净化印记") || norm.contains("加护符石") ||
            norm.contains("毒蚀圣果") || norm.contains("不朽圣果") ||
            norm.contains("正方3x3") || norm.contains("3x3") ||
            norm.contains("温律临界") || norm.contains("指定连击法印") ||
            norm.contains("阴之气") || norm.contains("阳之气") || norm.contains("雷之气") ||
            Regex("(?:\\d+\\s*粒\\s*)?符石护盾").containsMatchIn(norm) ||
            norm.contains("全消盾") || norm.contains("首消粒数盾") ||
            norm.contains("首消组数属性符石盾") || norm.contains("固定连击盾") ||
            norm.contains("反首消符石盾") || norm.contains("符石连击零化") ||
            norm.contains("步数盾") || norm.contains("步数限制") ||
            norm.contains("累积符石盾") || norm.contains("符石禁咒") ||
            norm.contains("二属盾") || norm.contains("四属盾") || norm.contains("凝护")
    )

    private fun hasAnyVisualHint(h: BoardVisualHints): Boolean = h.hasAny()

    private fun matchesKnownNonBoardSkill(norm: String): Boolean =
        KNOWN_NON_BOARD_KEYWORDS.any { norm.contains(it, ignoreCase = true) }

    companion object {
        private const val RULES_ASSET_V2 = "tos_enemy_skill_rules_v2.json"
        private val json = Json { ignoreUnknownKeys = true }

        private val MASK_MERGE_CHIP_IDS = setOf(
            "elem_burst_shield", "require_elements", "anti_first_wave",
            "forbid_all", "clear_all_element", "unmatchable_elements"
        )

        private val CHROME_LINE_REGEX = Regex(
            "^(?:wave\\s*\\d+(?:\\s*(?:current|next))?|current|next|boss|hp|atk|def|cd\\s*\\d*|lv\\.?\\s*\\d+|turn\\s*\\d+|ep|com|leader|ability|complete|victory|battle\\s*\\d+/\\d+|第\\s*\\d+\\s*(?:[层波]|阶段|血)|基本资讯|关卡资讯|队伍资讯|敌人技能|敌技总览|快速筛选|指定筛选|没有对应标签的技能|战斗准备|我的队伍|预设队伍|队伍\\s*\\d+-\\d+|战斗结算|离开战斗|返回选单|关闭|取消|确认|确定|载入中\\.{0,3}|正在前往\\.{0,3}|点击空白处关闭)$",
            RegexOption.IGNORE_CASE
        )
        private val PURE_NUMBER_STAT_REGEX = Regex("^[x×]?\\s*[\\d,./+%\\-\\s]+$", RegexOption.IGNORE_CASE)
        private val STAT_PREFIX_REGEX = Regex("^(?:hp|atk|def|cd|lv|ep|turn)\\s*[:：]?\\s*[\\d,./+%\\-]+$", RegexOption.IGNORE_CASE)

        private val BLOCK_START_REGEX = Regex("^(?:[▼▶►▸]|【[^】]{1,28}】)")
        private val STANDALONE_BRACKET_TITLE_REGEX = Regex("^[▼▶►▸\\s]*【[^】]{1,28}】\\s*$")
        private val EDGE_NOISE_LINE_REGEX = Regex(
            "^(?:w?ave\\s*\\d+|w\\d+/\\d+|b?oss|jss|current|next|wave|boss|hp|atk|def|cd)$",
            RegexOption.IGNORE_CASE
        )
        private val CLAUSE_START_REGEX = Regex("^(?:•|·|‧|⇒|[①②③④⑤⑥⑦⑧⑨⑩]|▶|▼|【|\\(|※|★|◆)")
        private val CJK_SPACE_BETWEEN_REGEX = Regex("(?<=[\\u4e00-\\u9fff])\\s+(?=[\\u4e00-\\u9fff])")
        private val CJK_PIPE_ARTIFACT_REGEX = Regex("(?<=[\\u4e00-\\u9fff])[|｜](?=[\\u4e00-\\u9fff])")
        private val SENTENCE_END_CHARS = setOf('。', '；', ';', '！', '!', '？', '?', '：', ':', ')')

        private val KNOWN_NON_BOARD_KEYWORDS = listOf(
            "攻前盾", "首杀盾", "防御盾", "无法控制", "中毒", "亡命爆击", "隐身",
            "技能重置", "附加消除", "即效永生", "连击护盾", "连击法印护盾", "反馈",
            "终结倒数", "减伤状态", "混沌值", "崩坏值", "腐化", "石牢", "锈化值",
            "灵罩", "脉流", "两相消耗", "炎之气", "囚锢生命力", "能力点代价",
            "开技消耗代价", "ep代价", "ep 代价", "cd增加", "cd 增加", "ep减少", "ep 减少",
            "技能cd", "技能 cd", "技能ep", "技能 ep", "减伤抗性", "连击攻击",
            "连续攻击", "首攻", "非电击状态", "弱攻", "越战越强", "回复力变",
            "灵魂值", "生命枯竭", "净世", "佑律", "防御崩坏", "喋血屠刀", "反击",
            "愈战愈强", "愈攻愈强", "能力继承", "双体共生", "同伴之护", "丧伴自戕",
            "提升龙脉仪", "沉默状态", "缚 - 技能", "封锁", "转化符石技能无效",
            "突击", "优先突击", "溢血攻击", "受击护盾", "亿血护盾", "五属伤害盾",
            "回复弱化", "回复力减至", "生命禁咒", "十之诅咒", "符石转换", "符石转水",
            "符石转火", "符石转木", "符石转光", "符石转暗", "符石转心", "围城",
            "金穗圣果", "蚀骨腐果", "猎人", "猎神", "猎魔", "猎兽", "猎龙", "猎妖", "猎机",
            "无视所有减伤", "无视所有控制", "扣除召唤师", "转化为", "移动符石时间",
            "攻击力提升", "攻击力减少", "回复生命力", "回复100%", "回合后死亡", "伤害减至", "抵挡此伤害",
            "敌方所有召唤兽", "精灵守护", "符石净化", "反掌", "停止移动符石", "生命力剩1点"
        )

        private val NOISE_CONTAINS_PATTERNS = listOf(
            Regex("已归档.*(?:敌技|知识库)"),
            Regex("已自动装配.*解版"),
            Regex("悬浮助手"),
            Regex("知识库"),
            Regex("^<?\\s*w\\d+/\\d+\\s*>?\\s*×?$", RegexOption.IGNORE_CASE),
            Regex("获得金币|获得\\s*exp|获得友情点数|你刚从战斗获得", RegexOption.IGNORE_CASE),
            Regex("确定送出交友申请|是否进入剧情|回复体力|请保持网络讯号良好"),
            Regex("战斗准备|我的队伍|预设队伍|敌技总览|快速筛选|指定筛选|没有对应标签的技能"),
            Regex("队伍空间\\s*\\d+/\\d+|队长技能|队伍技能|战友技能"),
            Regex("(?:^|\\b)(?:dual\\s*max|all\\s*max|battle\\s*\\d+/\\d+)(?:\\b|\\d|$)", RegexOption.IGNORE_CASE),
            Regex("新手.{0,2}用")
        )

        private val POSITIVE_SKILL_KEYWORDS = listOf(
            "符石", "连击", "combo", "消除", "伤害", "敌人", "攻击", "盾",
            "光牢", "电击", "起结有序", "幽闭", "燃烧", "灼热", "风化",
            "冰封", "石化", "黏腐", "粘腐", "暴风", "碎裂", "黑洞",
            "血浴", "炼狱", "浮标", "凝护", "拼图", "十字", "回复", "回合", "无视"
        )

        fun isLikelyNoiseEntry(title: String, rawBody: String): Boolean {
            val trimmedBody = rawBody.trim()
            if (trimmedBody.length <= 2) return true
            val normBody = normalizeText(rawBody)
            val normTitle = normalizeText(title)
            if (normBody.isBlank() || normBody.length <= 2) return true
            if (EDGE_NOISE_LINE_REGEX.matches(normBody) || isChromeOrStatLine(normBody)) return true
            // Enemy name + HP/ATK/DEF stat card accidentally captured into popup OCR (never drop bracketed 【...】 skill blocks)
            if (!normBody.contains("【") &&
                (Regex("\\d+(?:\\.\\d+)?兆").containsMatchIn(normBody) ||
                    Regex("\\b\\d{1,3}(?:,\\d{3}){2,}\\b").containsMatchIn(normBody)) &&
                !Regex("(?:符石|连击|生命力|攻击力|防御力|伤害|灵魂|技能)").containsMatchIn(normBody)
            ) {
                return true
            }
            if (NOISE_CONTAINS_PATTERNS.any { it.containsMatchIn(normBody) || it.containsMatchIn(normTitle) }) {
                if (!Regex("【[^】]{2,26}】").containsMatchIn(normBody) ||
                    normBody.contains("已归档") || normBody.contains("知识库") ||
                    normBody.contains("新手") || normBody.contains("好友")
                ) {
                    return true
                }
            }
            // Reject pure player card / stage title stubs (no skill keywords and no 【...】)
            val hasSkillStructure = Regex("【[^】]{2,26}】").containsMatchIn(normBody) ||
                POSITIVE_SKILL_KEYWORDS.any { normBody.contains(it, ignoreCase = true) }
            if (!hasSkillStructure && (normBody.length <= 24 || Regex("^第[一二三四五六七八九十百\\d]+[话章话節节]").containsMatchIn(normBody))) {
                return true
            }
            return false
        }

        /**
         * Verifies that an OCR'd dark modal screenshot is genuinely an Enemy Long-Press Popup or
         * Stage Info modal containing real enemy-skill text — blocking startup splash, apple cutscene,
         * loading dialogs, VICTORY / Complete settlement, friend-request prompts, and empty icon-only grids.
         */
        fun isAuthenticEnemySkillScreen(
            ocrLines: List<OcrTextLine>,
            modalKind: DarkModalKind,
            parseRes: EnemySkillParseResult
        ): Boolean {
            if (ocrLines.isEmpty() || modalKind == DarkModalKind.NONE) return false
            val fullNorm = normalizeText(ocrLines.joinToString("\n") { it.text.trim() })
            if (fullNorm.length < 4) return false

            val hardRejectKeywords = listOf(
                "载入中", "请保持网络讯号良好", "正在前往", "战斗结算",
                "获得金币", "获得友情点数", "你刚从战斗获得", "确定送出交友申请",
                "是否进入剧情", "回复体力", "已回复至", "返回选单"
            )
            if (hardRejectKeywords.any { fullNorm.contains(it) }) return false
            if (fullNorm.contains("complete", ignoreCase = true) && !fullNorm.contains("【")) return false
            if (fullNorm.contains("victory", ignoreCase = true) && !fullNorm.contains("【")) return false
            if ((fullNorm.contains("队伍空间") || fullNorm.contains("队长技能")) && !fullNorm.contains("【")) {
                return false
            }

            if (parseRes.hasNoSkillBanner) return false
            if (parseRes.activeChips.isNotEmpty() || parseRes.combinedHints.hasAny()) return true

            return parseRes.allBlocks.any { blk ->
                !isLikelyNoiseEntry(blk.titleZhTw, blk.rawBodyZhTw) && (
                    Regex("【[^】]{2,26}】").containsMatchIn(blk.rawBodyZhTw) ||
                        (blk.rawBodyZhTw.length >= 6 && POSITIVE_SKILL_KEYWORDS.any {
                            blk.normalizedText.contains(it, ignoreCase = true)
                        })
                    )
            }
        }

        fun isChromeOrStatLine(normalizedLine: String): Boolean {
            val s = normalizedLine.trim()
            if (s.length < 2 && s != "0") return true
            if (CHROME_LINE_REGEX.matches(s)) return true
            if (STAT_PREFIX_REGEX.matches(s)) return true
            if (PURE_NUMBER_STAT_REGEX.matches(s)) return true
            return false
        }

        private fun startsSkillBlockMarker(rawLine: String): Boolean {
            val trimmed = rawLine.trim()
            if (BLOCK_START_REGEX.containsMatchIn(trimmed)) {
                // Inline status nouns like 【護盾】 mid-sentence won't hit at start unless wrapped nicely;
                // a line starting with ▼/▶/•/【...】 in ToS UI denotes a new enemy skill entry.
                return true
            }
            return false
        }

        private fun extractSkillTitle(rawLines: List<String>): String {
            val first = rawLines.firstOrNull().orEmpty().trim()
            val bracketMatch = Regex("【([^】]{1,28})】").find(first)
            if (bracketMatch != null) return bracketMatch.groupValues[1].trim()
            val cleaned = first.trimStart('▼', '▶', '►', '▸', '•', '●', '◆', '◇', '※', ' ')
            return cleaned.take(36)
        }

        fun computeSkillHash(title: String, normalizedBody: String): String {
            val canonical = (normalizeText(title) + "|" + normalizedBody)
                .replace(Regex("\\s+"), "")
            val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            return digest.take(8).joinToString("") { "%02x".format(it) }
        }

        fun extractElementMaskNearKeywords(norm: String): Int {
            // Strip neutral phrases so unrelated chars like 水/火 don't leak if none exist;
            // ToS enemy skill prose uses exact element kanji for orb types.
            return parseElementMask(norm)
        }

        fun parseElementMask(chars: String): Int {
            var mask = 0
            for (ch in chars) {
                val code = charToElementCode(ch)
                if (code >= 0) mask = mask or (1 shl code)
            }
            return mask
        }

        private fun charToElementCode(ch: Char): Int = when (ch) {
            '水' -> Element.WATER.code
            '火' -> Element.FIRE.code
            '木' -> Element.WOOD.code
            '光' -> Element.LIGHT.code
            '暗' -> Element.DARK.code
            '心' -> Element.HEART.code
            else -> -1
        }

        fun formatElementsCn(mask: Int): String {
            val chars = charArrayOf('水', '火', '木', '光', '暗', '心')
            val sb = StringBuilder()
            for (i in 0 until 6) {
                if ((mask and (1 shl i)) != 0) sb.append(chars[i])
            }
            return sb.toString()
        }

        fun cleanRawOcrLine(rawLine: String): String {
            var s = rawLine.trim()
            if (s.isEmpty()) return ""
            s = s.replace("「靈罩]", "「靈罩」")
                .replace("「灵罩]", "「灵罩」")
                .replace(Regex("CD\\s*變\\s*O"), "CD變0")
                .replace(Regex("CD\\s*变\\s*O"), "CD变0")
            s = CJK_PIPE_ARTIFACT_REGEX.replace(s, "")
            return s.trim()
        }

        /**
         * Repairs wrapped `【Title\nContinuation】` lines and unwraps mid-sentence game-layout line breaks
         * while preserving logical bullet (`•`), arrow (`⇒`), clause (`①`), and title boundaries.
         */
        fun unwrapGameLayoutLines(rawLines: List<String>): String {
            val repaired = ArrayList<String>(rawLines.size)
            var idx = 0
            while (idx < rawLines.size) {
                var s = cleanRawOcrLine(rawLines[idx])
                if (s.isEmpty() || EDGE_NOISE_LINE_REGEX.matches(normalizeText(s))) {
                    idx++
                    continue
                }
                if (s.startsWith("【") && !s.contains("】") && idx + 1 < rawLines.size) {
                    val nxt = cleanRawOcrLine(rawLines[idx + 1])
                    val closeIdx = nxt.indexOf('】')
                    if (closeIdx >= 0) {
                        s += nxt.substring(0, closeIdx + 1)
                        val rest = nxt.substring(closeIdx + 1).trim()
                        repaired.add(s)
                        if (rest.isNotEmpty()) repaired.add(rest)
                        idx += 2
                        continue
                    }
                }
                repaired.add(s)
                idx++
            }
            if (repaired.isEmpty()) return ""

            val merged = ArrayList<String>(repaired.size)
            merged.add(repaired[0])
            for (k in 1 until repaired.size) {
                val prev = merged.last()
                val nxt = repaired[k]
                val prevIsTitleHeader = prev.startsWith("【") && prev.endsWith("】")
                val keepBreak = prevIsTitleHeader ||
                    CLAUSE_START_REGEX.containsMatchIn(nxt) ||
                    (prev.isNotEmpty() && prev.last() in SENTENCE_END_CHARS)
                if (keepBreak) {
                    merged.add(nxt)
                } else {
                    val needsSpace = prev.isNotEmpty() && nxt.isNotEmpty() &&
                        prev.last().code < 128 && prev.last().isLetterOrDigit() &&
                        nxt.first().code < 128 && nxt.first().isLetterOrDigit()
                    merged[merged.lastIndex] = if (needsSpace) "$prev $nxt" else "$prev$nxt"
                }
            }
            val joined = merged.joinToString("\n").replace(Regex("[ ]+"), " ").trim()
            return CJK_SPACE_BETWEEN_REGEX.replace(joined, "")
        }

        fun normalizeText(raw: String): String {
            val sb = StringBuilder(raw.length)
            for (ch in raw) {
                val asciiMapped = when (ch) {
                    '\u3000' -> ' '
                    in '\uFF01'..'\uFF5E' -> (ch.code - 0xFEE0).toChar()
                    else -> ch
                }
                sb.append(TRAD_TO_SIMP_MAP[asciiMapped] ?: asciiMapped)
            }
            val withoutPipes = CJK_PIPE_ARTIFACT_REGEX.replace(sb.toString(), "")
            return withoutPipes
                .replace('\r', '\n')
                .replace(Regex("CD\\s*变\\s*O"), "CD变0")
                .replace(Regex("[ \t]+"), " ")
                .trim()
        }

        fun fromJson(jsonText: String): TosEnemySkillParserV2 {
            val set = runCatching { json.decodeFromString<EnemySkillRuleSet>(jsonText) }
                .getOrElse { EnemySkillRuleSet() }
            return TosEnemySkillParserV2(set)
        }

        fun loadFromAssets(assetsOpen: (String) -> String): TosEnemySkillParserV2 {
            val raw = runCatching { assetsOpen(RULES_ASSET_V2) }.getOrElse { "{}" }
            return fromJson(raw)
        }

        fun loadFromContext(context: android.content.Context): TosEnemySkillParserV2 {
            val overrideFile = File(context.filesDir, RULES_ASSET_V2)
            val jsonText = if (overrideFile.exists() && overrideFile.length() > 16) {
                runCatching { overrideFile.readText(Charsets.UTF_8) }
                    .getOrElse { context.assets.open(RULES_ASSET_V2).bufferedReader().use { it.readText() } }
            } else {
                runCatching { context.assets.open(RULES_ASSET_V2).bufferedReader().use { it.readText() } }
                    .getOrElse { "{}" }
            }
            return fromJson(jsonText)
        }

        private val TRAD_TO_SIMP_MAP: Map<Char, Char> = mapOf(
            '屬' to '属', '強' to '强', '圖' to '图', '連' to '连', '擊' to '击', '撃' to '击',
            '雙' to '双', '單' to '单', '燒' to '烧', '蝕' to '蚀', '種' to '种',
            '數' to '数', '顆' to '颗', '軌' to '轨', '跡' to '迹', '轉' to '转',
            '鎖' to '锁', '風' to '风', '電' to '电', '體' to '体', '無' to '无',
            '視' to '视', '計' to '计', '僅' to '仅', '隻' to '只', '與' to '与',
            '類' to '类', '龍' to '龙', '獸' to '兽', '機' to '机', '術' to '术',
            '個' to '个', '組' to '组', '沒' to '没', '嚴' to '严', '須' to '须',
            '為' to '为', '開' to '开', '關' to '关', '點' to '点', '線' to '线',
            '場' to '场', '滿' to '满', '動' to '动', '傷' to '伤', '減' to '减',
            '區' to '区', '異' to '异', '變' to '变', '換' to '换', '經' to '经',
            '過' to '过', '對' to '对', '應' to '应', '於' to '于', '會' to '会',
            '資' to '资', '訊' to '讯', '離' to '离', '戰' to '战', '鬥' to '斗',
            '隊' to '队', '敵' to '敌', '護' to '护', '圍' to '围', '繞' to '绕',
            '積' to '积', '達' to '达', '觸' to '触', '發' to '发', '時' to '时',
            '當' to '当', '後' to '后', '該' to '该', '內' to '内', '並' to '并',
            '將' to '将', '橫' to '横', '問' to '问', '號' to '号', '隱' to '隐',
            '條' to '条', '補' to '补', '進' to '进', '實' to '实', '選' to '选',
            '擇' to '择', '狀' to '状', '殺' to '杀', '禦' to '御', '記' to '记',
            '氣' to '气', '結' to '结', '閉' to '闭', '間' to '间', '熱' to '热',
            '陰' to '阴', '陽' to '阳', '靈' to '灵', '脈' to '脉', '鏽' to '锈',
            '溫' to '温', '臨' to '临', '獄' to '狱', '煉' to '炼', '復' to '复',
            '顛' to '颠', '終' to '终', '錮' to '锢', '籠' to '笼', '標' to '标',
            '啟' to '启', '紅' to '红', '綠' to '绿', '燈' to '灯', '壞' to '坏',
            '續' to '续', '絕' to '绝', '滅' to '灭', '襲' to '袭', '淵' to '渊',
            '劍' to '剑', '聖' to '圣', '決' to '决', '兩' to '两', '負' to '负',
            '饋' to '馈', '態' to '态', '偽' to '伪', '裝' to '装', '讓' to '让',
            '這' to '这', '彈' to '弹', '亂' to '乱', '凍' to '冻', '盡' to '尽',
            '餘' to '余', '從' to '从', '迴' to '回', '環' to '环', '欄' to '栏',
            '荊' to '荆', '顯' to '显', '藍' to '蓝', '黃' to '黄', '濕' to '湿',
            '溼' to '湿', '剛' to '刚', '夠' to '够', '齊' to '齐', '歸' to '归',
            '總' to '总', '覽' to '览', '篩' to '筛', '準' to '准', '備' to '备',
            '預' to '预', '設' to '设', '階' to '阶', '載' to '载', '網' to '网',
            '絡' to '络', '訊' to '讯', '驗' to '验', '結' to '结', '幣' to '币',
            '獲' to '获', '劇' to '剧', '情' to '情', '體' to '体', '確' to '确',
            '申' to '申', '請' to '请', '返' to '返', '單' to '单', '話' to '话',
            '籤' to '签', '應' to '应', '長' to '长',
            '價' to '价', '覧' to '览', '転' to '转', '図' to '图', '戦' to '战', '闘' to '斗',
            '変' to '变', '対' to '对', '応' to '应', '気' to '气', '悪' to '恶', '竜' to '龙',
            '獣' to '兽', '覚' to '觉', '満' to '满', '触' to '触', '発' to '发', '続' to '续',
            '経' to '经', '関' to '关', '単' to '单', '両' to '两', '残' to '残', '壊' to '坏',
            '霊' to '灵', '黒' to '黑', '砕' to '碎', '継' to '继', '繼' to '继', '総' to '总', '蔵' to '藏',
            '圧' to '压', '広' to '广', '譲' to '让', '読' to '读', '伝' to '传', '効' to '效',
            '収' to '收', '実' to '实', '専' to '专', '歳' to '岁', '畳' to '叠', '称' to '称',
            '縁' to '缘', '訳' to '译', '証' to '证', '軽' to '轻', '辺' to '边', '遅' to '迟',
            '鉄' to '铁', '録' to '录', '陥' to '陷', '険' to '险', '雑' to '杂', '難' to '难',
            '静' to '静', '響' to '响', '顔' to '颜', '願' to '愿', '駆' to '驱', '験' to '验',
            '虛' to '虚', '詛' to '诅', '隨' to '随', '喚' to '唤', '師' to '师', '優' to '优',
            '壓' to '压', '縮' to '缩', '縛' to '缚', '淨' to '净', '疊' to '叠', '霧' to '雾',
            '處' to '处', '劃' to '划', '擋' to '挡', '斃' to '毙', '魯' to '鲁', '儀' to '仪',
            '儲' to '储', '奪' to '夺', '漸' to '渐', '潰' to '溃', '斷' to '断', '斬' to '斩',
            '畫' to '画', '枯' to '枯', '竭' to '竭', '懲' to '惩', '罰' to '罚', '億' to '亿',
            '義' to '义', '質' to '质', '號' to '号',
            '０' to '0', '１' to '1', '２' to '2', '３' to '3', '４' to '4',
            '５' to '5', '６' to '6', '７' to '7', '８' to '8', '９' to '9',
            '＋' to '+', '－' to '-', '×' to 'x', '≧' to '≥', '＞' to '>',
            '～' to '~'
        )
    }
}

/**
 * Thread-safe hot-reloadable wrapper around [TosEnemySkillParserV2] backed by
 * `context.filesDir/tos_enemy_skill_rules_v2.json` with instant fallback to bundled APK asset.
 */
class HotReloadEnemySkillParser(private val context: android.content.Context) {

    @Volatile
    private var parser: TosEnemySkillParserV2 = TosEnemySkillParserV2.loadFromContext(context)

    fun current(): TosEnemySkillParserV2 = parser

    fun hasCustomOverride(): Boolean {
        val f = File(context.filesDir, RULES_ASSET_V2)
        return f.isFile && f.length() > 16L
    }

    @Synchronized
    fun reload(): TosEnemySkillParserV2 {
        val next = TosEnemySkillParserV2.loadFromContext(context)
        parser = next
        return next
    }

    @Synchronized
    fun validateAndSaveRulesJson(jsonText: String): Result<Int> {
        return runCatching {
            val decoded = SHARED_JSON.decodeFromString<EnemySkillRuleSet>(jsonText)
            require(decoded.rules.isNotEmpty()) { "Rule JSON contains 0 rules" }
            val next = TosEnemySkillParserV2(decoded)
            require(next.ruleCount > 0) { "No valid regex rules compiled" }
            File(context.filesDir, RULES_ASSET_V2).writeText(jsonText, Charsets.UTF_8)
            parser = next
            next.ruleCount
        }
    }

    @Synchronized
    fun resetToBundled(): Int {
        val f = File(context.filesDir, RULES_ASSET_V2)
        if (f.exists()) f.delete()
        val next = TosEnemySkillParserV2.loadFromContext(context)
        parser = next
        return next.ruleCount
    }

    companion object {
        private const val RULES_ASSET_V2 = "tos_enemy_skill_rules_v2.json"
        private val SHARED_JSON = Json { ignoreUnknownKeys = true }
    }
}
