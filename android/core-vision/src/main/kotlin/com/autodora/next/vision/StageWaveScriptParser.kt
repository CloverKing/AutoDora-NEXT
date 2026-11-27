package com.autodora.next.vision

import com.autodora.next.model.BoardVisualHints
import com.autodora.next.model.StageWaveEntry
import com.autodora.next.model.StageWaveScript
import com.autodora.next.model.StageWikiImportBundle
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

data class ParsedStageScriptResult(
    val script: StageWaveScript,
    val detectedCurrentWave: Int?,
    val allBlocks: List<ParsedEnemySkillBlock>
)

@Serializable
private data class RawWikiWaveJson(
    val waveIndex: Int = 1,
    val isBoss: Boolean = false,
    val skillTitles: List<String> = emptyList(),
    val rawText: String = "",
    val skills: List<String> = emptyList()
)

@Serializable
private data class RawWikiStageJson(
    val stageKey: String = "",
    val stageName: String = "",
    val region: String = "",
    val dungeon: String = "",
    val difficulty: String = "",
    val entryCategory: String = "",
    val subCategory: String = "",
    val dungeonSeries: String = "",
    val difficultyTag: String = "",
    val waves: List<RawWikiWaveJson> = emptyList()
)

@Serializable
private data class RawWikiBundleJson(
    val stages: List<RawWikiStageJson> = emptyList()
)

/**
 * Parses full multi-wave Stage Info modals (`STAGE_INFO_MODAL`) and external crawled Tower of Saviors
 * Wiki dungeon JSONs into structured [StageWaveScript]s ready for persistent storage and wave-by-wave execution.
 */
object StageWaveScriptParser {

    private val json = Json { ignoreUnknownKeys = true }

    private val WAVE_HEADER_REGEX = Regex(
        "(?:wave\\s*(\\d+)|第\\s*(\\d+)\\s*(?:[层波層]|阶?段|血)|\\b(boss)\\b)",
        RegexOption.IGNORE_CASE
    )
    private val CURRENT_MARKER_REGEX = Regex("(?:current|目前|当前|進行中|进行中)", RegexOption.IGNORE_CASE)
    private val TITLE_SKIP_REGEX = Regex(
        "^(?:基本资讯|关卡资讯|队伍资讯|敌人技能|敌技总览|快速筛选|指定筛选|战斗准备|我的队伍|预设队伍|点击空白处关闭|离开战斗|关闭|取消|确认|确定|stage\\s*info|ability)$",
        RegexOption.IGNORE_CASE
    )

    fun parseStageInfoLines(
        ocrLines: List<OcrTextLine>,
        parser: TosEnemySkillParserV2,
        fallbackStageName: String = ""
    ): ParsedStageScriptResult? {
        if (ocrLines.isEmpty()) return null

        val preHeaderLines = ArrayList<String>()
        val waveBuckets = LinkedHashMap<Int, Pair<Boolean, MutableList<OcrTextLine>>>()
        var currentBucketIdx: Int? = null
        var detectedCurrentWave: Int? = null
        var maxAssignedIdx = 0

        for (line in ocrLines) {
            val raw = line.text.trim()
            if (raw.isEmpty()) continue
            val norm = TosEnemySkillParserV2.normalizeText(raw)
            val m = WAVE_HEADER_REGEX.find(norm)
            if (m != null) {
                val explicitNum = m.groupValues[1].toIntOrNull() ?: m.groupValues[2].toIntOrNull()
                val isBossHeader = m.groupValues[3].isNotEmpty() || norm.contains("boss", ignoreCase = true)
                val waveNum = explicitNum ?: (maxAssignedIdx + 1)
                if (waveNum > maxAssignedIdx) maxAssignedIdx = waveNum
                currentBucketIdx = waveNum
                val existing = waveBuckets[waveNum]
                if (existing == null) {
                    waveBuckets[waveNum] = (isBossHeader to ArrayList())
                } else if (isBossHeader && !existing.first) {
                    waveBuckets[waveNum] = (true to existing.second)
                }
                if (CURRENT_MARKER_REGEX.containsMatchIn(norm)) {
                    detectedCurrentWave = waveNum
                }
                continue
            }
            val bucketIdx = currentBucketIdx
            if (bucketIdx == null) {
                val isUiPrepNoise = TITLE_SKIP_REGEX.matches(norm) ||
                    TosEnemySkillParserV2.isChromeOrStatLine(norm) ||
                    norm.contains("dual max", ignoreCase = true) ||
                    norm.contains("all max", ignoreCase = true) ||
                    norm.contains("知识库") ||
                    norm.contains("已归档") ||
                    norm.contains("新手") ||
                    Regex("^<?w\\d+/\\d+>?×?$", RegexOption.IGNORE_CASE).matches(norm)
                if (!isUiPrepNoise) {
                    preHeaderLines.add(raw)
                }
            } else {
                waveBuckets.getValue(bucketIdx).second.add(line)
            }
        }

        if (waveBuckets.isEmpty()) return null

        val stageName = preHeaderLines.firstOrNull {
            val clean = TosEnemySkillParserV2.normalizeText(it)
            it.length in 2..40 &&
                !clean.contains("battle") &&
                !clean.contains("新手") &&
                !clean.startsWith("队伍")
        } ?: fallbackStageName.ifBlank { "Stage ${waveBuckets.size}W" }

        val entries = ArrayList<StageWaveEntry>(waveBuckets.size)
        val allParsedBlocks = ArrayList<ParsedEnemySkillBlock>()

        for ((idx, pair) in waveBuckets) {
            val (isBoss, wLines) = pair
            val parsedWave = parser.parseLines(wLines)
            allParsedBlocks.addAll(parsedWave.allBlocks)
            val titles = parsedWave.allBlocks.mapNotNull { blk ->
                blk.titleZhTw.takeIf { it.isNotBlank() }
            }.distinct()
            if (titles.isNotEmpty() || parsedWave.activeChips.isNotEmpty() || parsedWave.combinedHints.hasAny()) {
                entries.add(
                    StageWaveEntry(
                        waveIndex = idx,
                        isBoss = isBoss,
                        skillTitles = titles,
                        chips = parsedWave.activeChips,
                        visualHints = parsedWave.combinedHints
                    )
                )
            }
        }

        if (allParsedBlocks.isEmpty() || entries.isEmpty()) return null

        val sortedEntries = entries.sortedBy { it.waveIndex }
        val script = StageWaveScript(
            stageKey = computeStageKey(stageName, sortedEntries),
            stageName = stageName,
            waves = sortedEntries,
            sourceType = "STAGE_INFO_OCR",
            updatedAtMs = System.currentTimeMillis(),
            entryCategory = "战中采集",
            subCategory = "关卡资讯抓取",
            dungeonSeries = stageName,
            difficultyTag = com.autodora.next.model.StageHierarchyHelper.inferDifficultyTag(stageName, "")
        )
        return ParsedStageScriptResult(
            script = script,
            detectedCurrentWave = detectedCurrentWave ?: sortedEntries.firstOrNull()?.waveIndex,
            allBlocks = allParsedBlocks
        )
    }

    /**
     * Parses imported Wiki / crawled stage script JSON across all common formats:
     *  1) Structured [StageWikiImportBundle] (`{"stages": [...]}` with `chips` already filled)
     *  2) Structured JSON list or single [StageWaveScript]
     *  3) Raw Wiki JSON containing `rawText` / `skills` per wave -> compiles via [parser] automatically.
     */
    fun parseStageWikiJson(
        jsonText: String,
        parser: TosEnemySkillParserV2
    ): List<StageWaveScript> {
        val now = System.currentTimeMillis()
        val structured = runCatching { json.decodeFromString<StageWikiImportBundle>(jsonText).stages }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() && it.any { st -> st.waves.any { w -> w.chips.isNotEmpty() } } }
            ?: runCatching { json.decodeFromString<List<StageWaveScript>>(jsonText) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() && it.any { st -> st.waves.any { w -> w.chips.isNotEmpty() } } }
            ?: runCatching { listOf(json.decodeFromString<StageWaveScript>(jsonText)) }
                .getOrNull()
                ?.takeIf { it.any { st -> st.waves.any { w -> w.chips.isNotEmpty() } } }

        if (structured != null) {
            return structured.filter { it.waves.isNotEmpty() }.map { st ->
                val cleanWaves = st.waves.sortedBy { it.waveIndex }
                val (ec, sc, ds, dt) = com.autodora.next.model.StageHierarchyHelper.infer(
                    rawEntry = st.entryCategory,
                    rawSub = st.subCategory,
                    rawSeries = st.dungeonSeries,
                    rawDiff = st.difficultyTag,
                    stageName = st.stageName,
                    sourceType = st.sourceType
                )
                st.copy(
                    stageKey = st.stageKey.ifBlank { computeStageKey(st.stageName, cleanWaves) },
                    stageName = st.stageName.ifBlank { "Wiki Stage" },
                    waves = cleanWaves,
                    sourceType = st.sourceType.ifBlank { "WIKI_IMPORT" },
                    updatedAtMs = if (st.updatedAtMs > 0L) st.updatedAtMs else now,
                    entryCategory = ec,
                    subCategory = sc,
                    dungeonSeries = ds,
                    difficultyTag = dt
                )
            }
        }

        val rawStages = runCatching { json.decodeFromString<RawWikiBundleJson>(jsonText).stages }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: runCatching { json.decodeFromString<List<RawWikiStageJson>>(jsonText) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
            ?: runCatching { listOf(json.decodeFromString<RawWikiStageJson>(jsonText)) }
                .getOrNull()
                .orEmpty()

        return rawStages.filter { it.waves.isNotEmpty() }.map { rawStage ->
            val waves = rawStage.waves.map { rw ->
                val body = buildString {
                    if (rw.rawText.isNotBlank()) {
                        appendLine(rw.rawText.trim())
                        appendLine()
                    }
                    for (s in rw.skills) {
                        if (s.isNotBlank()) {
                            appendLine(s.trim())
                            appendLine()
                        }
                    }
                }
                val parsed = parser.parsePlainText(body)
                val titles = (rw.skillTitles + parsed.allBlocks.map { it.titleZhTw })
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                StageWaveEntry(
                    waveIndex = rw.waveIndex,
                    isBoss = rw.isBoss,
                    skillTitles = titles,
                    chips = parsed.activeChips,
                    visualHints = if (parsed.allBlocks.isEmpty()) BoardVisualHints() else parsed.combinedHints
                )
            }.sortedBy { it.waveIndex }
            val name = rawStage.stageName.ifBlank { "Wiki Stage" }
            val (ec, sc, ds, dt) = com.autodora.next.model.StageHierarchyHelper.infer(
                rawEntry = rawStage.entryCategory.ifBlank { rawStage.region },
                rawSub = rawStage.subCategory,
                rawSeries = rawStage.dungeonSeries.ifBlank { rawStage.dungeon },
                rawDiff = rawStage.difficultyTag.ifBlank { rawStage.difficulty },
                stageName = name,
                sourceType = "WIKI_IMPORT"
            )
            StageWaveScript(
                stageKey = rawStage.stageKey.ifBlank { computeStageKey(name, waves) },
                stageName = name,
                waves = waves,
                sourceType = "WIKI_IMPORT",
                updatedAtMs = now,
                entryCategory = ec,
                subCategory = sc,
                dungeonSeries = ds,
                difficultyTag = dt
            )
        }
    }

    fun computeStageKey(stageName: String, waves: List<StageWaveEntry>): String {
        val signature = buildString {
            append(TosEnemySkillParserV2.normalizeText(stageName))
            for (w in waves.sortedBy { it.waveIndex }) {
                append("|w").append(w.waveIndex).append(':')
                append(w.chips.joinToString(",") { "${it.id}#${it.paramInt}#${it.paramElementMask}" })
                append('/').append(w.skillTitles.joinToString(","))
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}
