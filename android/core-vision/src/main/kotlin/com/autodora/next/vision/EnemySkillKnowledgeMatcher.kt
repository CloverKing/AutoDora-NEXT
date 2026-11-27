package com.autodora.next.vision

import android.content.Context
import com.autodora.next.model.BoardVisualHints
import com.autodora.next.model.WaveShieldChip
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.max

@Serializable
data class AuthoritativeSkillEntry(
    val hash: String,
    val titleZhTw: String,
    val cleanBodyZhTw: String,
    val normTitle: String,
    val normBody: String,
    val rawVariantsNorm: List<String> = emptyList(),
    val occurrenceCount: Int = 1,
    val stageCount: Int = 1,
    val sampleStages: List<String> = emptyList()
)

@Serializable
data class AuthoritativeSkillBundle(
    val version: String = "1.0",
    val skills: List<AuthoritativeSkillEntry> = emptyList()
)

data class LocalCapturedSkillRecord(
    val skillHash: String,
    val titleZhTw: String,
    val rawBodyZhTw: String,
    val sourceType: String,
    val parseStatus: String,
    val hitCount: Int,
    val mappedChips: List<WaveShieldChip>
)

/**
 * Multi-tier Enemy Skill Knowledge Matcher (`v0.9.0`):
 *  - **Tier 0 (Highest — `MANUAL_EDIT`)**: User-curated skill mappings from local Knowledge Base
 *    always take top priority when normalized hash or high-similarity title+body matches.
 *  - **Tier 1 (Primary — `TosEnemySkillParserV2`)**: Live regex + typed extractors run first on
 *    complete live OCR text to preserve exact live numbers & attributes.
 *  - **Tier 2 (Low-Priority Fuzzy Rescue — Built-in 499 Authoritative Master + Local `PARSED` DB)**:
 *    Invoked ONLY when a live OCR block is `UNPARSED` (e.g. half-cut scrolled popup leaving only
 *    `【Skill Title】`, OCR character corruption like `擊→撃 / 鎖→銷 / 拼→拚`, or alternate phrasing).
 *    Applies parameter re-hydration from live text so historical numbers never overwrite live numbers!
 */
class EnemySkillKnowledgeMatcher(
    private val authoritativeEntries: List<AuthoritativeSkillEntry> = emptyList()
) {
    private data class IndexedEntry(
        val hash: String,
        val titleZhTw: String,
        val rawBodyZhTw: String,
        val normTitle: String,
        val skelTitle: String,
        val normBody: String,
        val compactBody: String,
        val skelBody: String,
        val variantCompacts: List<String>,
        val tier: Int, // 0=MANUAL_EDIT, 1=AUTHORITATIVE_499, 2=CAPTURED_MULTI_HIT, 3=CAPTURED_SINGLE_HIT
        val chips: List<WaveShieldChip>,
        val hints: BoardVisualHints,
        val isNonBoard: Boolean
    )

    @Volatile
    private var localRecords: List<LocalCapturedSkillRecord> = emptyList()

    @Volatile
    private var cachedParserVersion: String = ""

    @Volatile
    private var cachedIndex: List<IndexedEntry> = emptyList()

    fun updateLocalRecords(records: List<LocalCapturedSkillRecord>) {
        localRecords = records
        cachedParserVersion = "" // trigger fast rebuild on next query
    }

    fun authoritativeList(): List<AuthoritativeSkillEntry> = authoritativeEntries

    @Synchronized
    private fun ensureIndex(parser: TosEnemySkillParserV2): List<IndexedEntry> {
        val verKey = "${parser.version}:${parser.ruleCount}:${localRecords.size}"
        if (verKey == cachedParserVersion && cachedIndex.isNotEmpty()) {
            return cachedIndex
        }
        val out = ArrayList<IndexedEntry>(authoritativeEntries.size + localRecords.size)

        // 1. User Manual Edits (Tier 0) & Local Captured Parsed Entries (Tier 2/3)
        for (rec in localRecords) {
            val nTitle = compactNorm(rec.titleZhTw)
            val nBody = compactNorm(rec.rawBodyZhTw)
            if (nBody.length < 4 && nTitle.length < 2) continue
            val isManual = rec.sourceType.equals("MANUAL_EDIT", ignoreCase = true)
            if (!isManual && rec.parseStatus != EnemySkillParseStatus.PARSED && rec.mappedChips.isEmpty()) {
                continue
            }
            val parsedHints = parser.parsePlainText(rec.rawBodyZhTw).combinedHints
            val tier = when {
                isManual -> 0
                rec.hitCount >= 2 -> 2
                else -> 3
            }
            out.add(
                IndexedEntry(
                    hash = rec.skillHash,
                    titleZhTw = rec.titleZhTw,
                    rawBodyZhTw = rec.rawBodyZhTw,
                    normTitle = nTitle,
                    skelTitle = skeletonNorm(nTitle),
                    normBody = TosEnemySkillParserV2.normalizeText(rec.rawBodyZhTw),
                    compactBody = nBody,
                    skelBody = skeletonNorm(nBody),
                    variantCompacts = emptyList(),
                    tier = tier,
                    chips = rec.mappedChips,
                    hints = parsedHints,
                    isNonBoard = rec.parseStatus == EnemySkillParseStatus.NON_BOARD_KNOWN
                )
            )
        }

        // 2. Built-in 499 Authoritative Cleaned Master (Tier 1)
        for (auth in authoritativeEntries) {
            val fullText = if (auth.titleZhTw.isNotBlank() && !auth.cleanBodyZhTw.contains(auth.titleZhTw)) {
                "${auth.titleZhTw}\n${auth.cleanBodyZhTw}"
            } else {
                auth.cleanBodyZhTw
            }
            val reparsed = parser.parsePlainText(fullText)
            val isNonBoard = reparsed.activeChips.isEmpty() && !reparsed.combinedHints.hasAny() &&
                reparsed.allBlocks.any { it.parseStatus == EnemySkillParseStatus.NON_BOARD_KNOWN }
            if (reparsed.activeChips.isEmpty() && !reparsed.combinedHints.hasAny() && !isNonBoard) {
                continue
            }
            val cTitle = compactNorm(auth.normTitle.ifBlank { auth.titleZhTw })
            val cBody = compactNorm(auth.normBody.ifBlank { fullText })
            out.add(
                IndexedEntry(
                    hash = auth.hash,
                    titleZhTw = auth.titleZhTw.removePrefix("【").removeSuffix("】").trim(),
                    rawBodyZhTw = auth.cleanBodyZhTw,
                    normTitle = cTitle,
                    skelTitle = skeletonNorm(cTitle),
                    normBody = TosEnemySkillParserV2.normalizeText(fullText),
                    compactBody = cBody,
                    skelBody = skeletonNorm(cBody),
                    variantCompacts = auth.rawVariantsNorm.map { compactNorm(it) }.filter { it.length >= 4 },
                    tier = 1,
                    chips = reparsed.activeChips,
                    hints = reparsed.combinedHints,
                    isNonBoard = isNonBoard
                )
            )
        }

        cachedIndex = out
        cachedParserVersion = verKey
        return out
    }

    /**
     * Enriches [parseRes] using Tier-0 user manual overrides and Tier-2/3 low-priority fuzzy fallback
     * on `UNPARSED` blocks. Never overwrites live regex numeric/element extractions!
     */
    fun enrichParseResult(
        parseRes: EnemySkillParseResult,
        parser: TosEnemySkillParserV2
    ): EnemySkillParseResult {
        if (parseRes.allBlocks.isEmpty()) return parseRes
        val index = ensureIndex(parser)
        if (index.isEmpty()) return parseRes

        var anyChanged = false
        val updatedBlocks = ArrayList<ParsedEnemySkillBlock>(parseRes.allBlocks.size)

        for (blk in parseRes.allBlocks) {
            val blkTitleNorm = compactNorm(blk.titleZhTw)
            val blkBodyCompact = compactNorm(blk.normalizedText)

            // Step A: Check Tier-0 User Manual Edit override first
            val manualHit = index.firstOrNull { cand ->
                cand.tier == 0 && (
                    cand.hash == blk.skillHash ||
                        (blkTitleNorm.length >= 2 && blkTitleNorm == cand.normTitle &&
                            bigramDice(blkBodyCompact, cand.compactBody) >= 0.86f)
                    )
            }
            if (manualHit != null && manualHit.chips.isNotEmpty()) {
                val manualChips = manualHit.chips.map { it.copy(autoDetected = true, fromKnowledgeFallback = false) }
                updatedBlocks.add(
                    blk.copy(
                        matchedRuleIds = (blk.matchedRuleIds + "kb_manual_override").distinct(),
                        chips = manualChips,
                        visualHints = blk.visualHints.mergeWith(manualHit.hints),
                        parseStatus = EnemySkillParseStatus.PARSED
                    )
                )
                anyChanged = true
                continue
            }

            // Step B: Low-Priority Fuzzy Rescue ONLY when live regex produced NO board chips or hints
            // (Covers both UNPARSED blocks AND corrupted board skills that fell through to NON_BOARD_KNOWN via generic words like "造成傷害"!)
            if (blk.chips.isNotEmpty() || blk.visualHints.hasAny()) {
                updatedBlocks.add(blk)
                continue
            }

            val blkTitleSkel = skeletonNorm(blkTitleNorm)
            val blkBodySkel = skeletonNorm(blkBodyCompact)
            var bestCand: IndexedEntry? = null
            var bestScore = 0f

            for (cand in index) {
                if (cand.tier == 0) continue
                if (blk.parseStatus == EnemySkillParseStatus.NON_BOARD_KNOWN && cand.chips.isEmpty() && !cand.hints.hasAny()) {
                    continue
                }
                if (hasPolarityConflict(blkBodyCompact, cand.compactBody)) continue

                val titleExact = blkTitleNorm.length >= 3 && (blkTitleNorm == cand.normTitle || blkTitleSkel == cand.skelTitle)
                val rawSim = maxOf(
                    bigramDice(blkBodyCompact, cand.compactBody),
                    cand.variantCompacts.maxOfOrNull { v -> bigramDice(blkBodyCompact, v) } ?: 0f
                )
                val skelSim = bigramDice(blkBodySkel, cand.skelBody) * 0.97f
                val bodySim = maxOf(rawSim, skelSim)

                // Truncated popup rescue (e.g. only 【十之詛咒】 or 【凝結技能】 visible at bottom edge)
                val truncatedTitleRescue = titleExact && blkBodyCompact.length <= blkTitleNorm.length + 8
                val minBodyThresh = if (blk.parseStatus == EnemySkillParseStatus.NON_BOARD_KNOWN) 0.75f else 0.72f
                val fullFuzzyMatch = bodySim >= minBodyThresh || (titleExact && bodySim >= 0.56f)

                if (!truncatedTitleRescue && !fullFuzzyMatch) continue

                val rankScore = (if (truncatedTitleRescue) 0.78f else bodySim) + when (cand.tier) {
                    1 -> 0.06f // Prefer cleaned 499 authoritative master over noisy captures
                    2 -> 0.03f
                    else -> 0.0f
                }
                if (rankScore > bestScore) {
                    bestScore = rankScore
                    bestCand = cand
                }
            }

            if (bestCand != null) {
                if (bestCand.isNonBoard && bestCand.chips.isEmpty() && !bestCand.hints.hasAny()) {
                    updatedBlocks.add(blk.copy(parseStatus = EnemySkillParseStatus.NON_BOARD_KNOWN))
                    anyChanged = true
                } else if (bestCand.chips.isNotEmpty() || bestCand.hints.hasAny()) {
                    val rescuedChips = bestCand.chips.map { tpl ->
                        rehydrateTemplateChip(tpl, blk.normalizedText)
                    }
                    updatedBlocks.add(
                        blk.copy(
                            matchedRuleIds = (blk.matchedRuleIds + "kb_fuzzy_fallback").distinct(),
                            chips = rescuedChips,
                            visualHints = blk.visualHints.mergeWith(bestCand.hints),
                            parseStatus = EnemySkillParseStatus.PARSED
                        )
                    )
                    anyChanged = true
                } else {
                    updatedBlocks.add(blk)
                }
            } else {
                updatedBlocks.add(blk)
            }
        }

        if (!anyChanged) return parseRes

        // Rebuild activeChips & combinedHints from non-bypassed blocks using parser's consolidation
        val activePool = ArrayList<WaveShieldChip>()
        val bypassedOut = ArrayList<ParsedEnemySkillBlock>()
        var hintsOut = BoardVisualHints()
        for (b in updatedBlocks) {
            if (b.isGreyBypassed) {
                bypassedOut.add(b)
            } else {
                hintsOut = hintsOut.mergeWith(b.visualHints)
                activePool.addAll(b.chips)
            }
        }
        val dedupedChips = LinkedHashMap<String, WaveShieldChip>()
        for (c in activePool) {
            val prev = dedupedChips[c.id]
            if (prev == null) {
                dedupedChips[c.id] = c
            } else if (prev.fromKnowledgeFallback && !c.fromKnowledgeFallback) {
                // Live regex chip ALWAYS beats knowledge-base fallback chip!
                dedupedChips[c.id] = c
            }
        }
        return parseRes.copy(
            allBlocks = updatedBlocks,
            activeChips = dedupedChips.values.toList(),
            bypassedBlocks = bypassedOut,
            combinedHints = hintsOut
        )
    }

    private fun rehydrateTemplateChip(template: WaveShieldChip, liveNormText: String): WaveShieldChip {
        val liveElemMask = TosEnemySkillParserV2.parseElementMask(liveNormText)
        val liveNumber = Regex("(?<!\\d)([1-9]\\d?)(?!\\d|%)").find(liveNormText)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()

        var newParamInt = template.paramInt
        var newElemMask = template.paramElementMask
        var newLabel = template.label

        if (template.id in NUMERIC_SENSITIVE_IDS && liveNumber != null && liveNumber in 1..30) {
            val oldNum = template.paramInt
            newParamInt = liveNumber
            if (oldNum > 0 && newLabel.contains(oldNum.toString())) {
                newLabel = newLabel.replaceFirst(oldNum.toString(), liveNumber.toString())
            }
        }
        if (template.id in ELEMENT_SENSITIVE_IDS && liveElemMask != 0) {
            newElemMask = liveElemMask
            val elemCn = TosEnemySkillParserV2.formatElementsCn(liveElemMask)
            newLabel = when (template.id) {
                "anti_first_wave" -> "反首消$elemCn"
                "forbid_all" -> "禁消$elemCn"
                "clear_all_element" -> "首消全$elemCn"
                "keep_element_at_least_one" -> "留底${elemCn}≥1"
                else -> newLabel
            }
        }

        return template.copy(
            label = newLabel,
            autoDetected = true,
            paramInt = newParamInt,
            paramElementMask = newElemMask,
            fromKnowledgeFallback = true
        )
    }

    companion object {
        const val ASSET_AUTHORITATIVE_499 = "tos_authoritative_skills_499.json"
        private val json = Json { ignoreUnknownKeys = true }

        private val NUMERIC_SENSITIVE_IDS = setOf(
            "min_combo", "max_combo", "exact_combo", "exact_first_combo", "min_first_combo",
            "min_first_elem_combo", "digital_orb_count", "elem_burst_shield",
            "step_limit", "exact_steps", "max_element_orbs"
        )
        private val ELEMENT_SENSITIVE_IDS = setOf(
            "anti_first_wave", "forbid_all", "clear_all_element", "unmatchable_elements",
            "elem_burst_shield", "keep_element_at_least_one", "max_element_orbs"
        )

        private val NON_ALNUM_CJK_REGEX = Regex("[^\\u4e00-\\u9fff0-9a-zA-Z+]")
        private val SKELETON_ELEM_REGEX = Regex("[水火木光暗心]")
        private val SKELETON_NUM_REGEX = Regex("\\d+")

        fun compactNorm(raw: String): String {
            val simp = TosEnemySkillParserV2.normalizeText(raw).lowercase()
            return NON_ALNUM_CJK_REGEX.replace(simp, "")
        }

        fun skeletonNorm(compact: String): String {
            return SKELETON_NUM_REGEX.replace(SKELETON_ELEM_REGEX.replace(compact, "e"), "n")
        }

        private fun hasPolarityConflict(a: String, b: String): Boolean {
            val negWords = arrayOf("禁", "不可", "不能", "无法", "反首消", "上限", "超过")
            val aNeg = negWords.any { a.contains(it) }
            val bNeg = negWords.any { b.contains(it) }
            return aNeg != bNeg
        }

        fun bigramDice(a: String, b: String): Float {
            if (a.isEmpty() || b.isEmpty()) return 0f
            if (a == b) return 1f
            if (a.length < 2 || b.length < 2) return if (a == b) 1f else 0f
            val gramsA = HashMap<Int, Int>(a.length)
            var totalA = 0
            for (i in 0 until a.length - 1) {
                val g = (a[i].code shl 16) or a[i + 1].code
                gramsA[g] = (gramsA[g] ?: 0) + 1
                totalA++
            }
            var overlap = 0
            var totalB = 0
            for (i in 0 until b.length - 1) {
                val g = (b[i].code shl 16) or b[i + 1].code
                val rem = gramsA[g] ?: 0
                if (rem > 0) {
                    overlap++
                    gramsA[g] = rem - 1
                }
                totalB++
            }
            return (2f * overlap) / max(1, totalA + totalB)
        }

        fun loadFromContext(context: Context): EnemySkillKnowledgeMatcher {
            val entries = runCatching {
                val raw = context.assets.open(ASSET_AUTHORITATIVE_499).bufferedReader(Charsets.UTF_8).use { it.readText() }
                json.decodeFromString<AuthoritativeSkillBundle>(raw).skills
            }.getOrDefault(emptyList())
            return EnemySkillKnowledgeMatcher(entries)
        }
    }
}
