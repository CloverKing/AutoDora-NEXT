package com.autodora.next.vision

import com.autodora.next.model.Element
import com.autodora.next.model.ShieldCategory
import com.autodora.next.model.WaveShieldChip
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
    val version: String = "2026.03",
    val rules: List<EnemySkillRuleEntry> = emptyList()
)

data class ParsedEnemySkillResult(
    val normalizedText: String,
    val chips: List<WaveShieldChip>,
    val matchedRuleIds: List<String>,
    val skippedGreyLines: Int = 0,
    val hasNoSkillBanner: Boolean = false
)

/**
 * Hot-updatable Traditional/Simplified Chinese enemy skill rule parser for Tower of Saviors.
 * Normalizes HK/TW Traditional characters + OCR confusions and extracts exact shield slots.
 */
class EnemySkillRuleParser(ruleSet: EnemySkillRuleSet) {

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

    fun parse(rawText: String, skippedGreyLines: Int = 0): ParsedEnemySkillResult {
        val normFull = normalizeText(rawText)
        val filteredLines = stripStageInfoChromeLines(normFull)
        val hasNoSkillBanner = filteredLines.any { it == "没有技能" || it == "无技能" }
        val norm = filteredLines.joinToString("\n")
        val resultChips = LinkedHashMap<String, WaveShieldChip>()
        val matchedIds = ArrayList<String>()

        for (rule in compiled) {
            val match = rule.regex.find(norm) ?: continue
            val entry = rule.entry

            // Skip generic loose_puzzle if strict_puzzle or enchanted_puzzle already matched
            if (entry.chipId == "loose_puzzle" &&
                (resultChips.containsKey("strict_puzzle") || resultChips.containsKey("enchanted_puzzle"))
            ) {
                continue
            }
            // Skip five_elements if five_elements_enchanted already matched
            if (entry.chipId == "five_elements" && resultChips.containsKey("five_elements_enchanted")) {
                continue
            }
            // Skip lower cross count if higher cross count already matched
            if (entry.chipId == "cross_shield" && resultChips.containsKey("cross_shield")) {
                continue
            }
            // Skip exact_combo if exact_first_combo already matched
            if (entry.chipId == "exact_combo" && resultChips.containsKey("exact_first_combo")) {
                continue
            }
            // Skip min_combo if min_first_combo already matched
            if (entry.chipId == "min_combo" && resultChips.containsKey("min_first_combo")) {
                continue
            }

            var paramInt = entry.defaultInt
            if (entry.intGroup > 0) {
                // Support alternation rules where capture group 1 or 2 holds the digit
                val extracted = (entry.intGroup until match.groupValues.size)
                    .firstNotNullOfOrNull { idx -> match.groupValues.getOrNull(idx)?.toIntOrNull() }
                if (extracted != null) paramInt = extracted
            }

            var elemMask = 0
            var elemText = ""
            if (entry.elementGroup > 0) {
                val rawElems = match.groupValues.getOrNull(entry.elementGroup).orEmpty()
                elemText = rawElems
                elemMask = parseElementMask(rawElems)
                if (elemMask == 0) continue
            }

            val finalLabel = entry.label
                .replace("{n}", paramInt.toString())
                .replace("{elem}", elemText)

            val uniqueKey = if (elemMask != 0) "${entry.chipId}_$elemMask" else entry.chipId
            resultChips[uniqueKey] = WaveShieldChip(
                id = entry.chipId,
                label = finalLabel,
                category = rule.category,
                autoDetected = true,
                paramInt = paramInt,
                paramElementMask = elemMask
            )
            matchedIds.add(entry.id)
        }

        return ParsedEnemySkillResult(
            normalizedText = norm,
            chips = resultChips.values.toList(),
            matchedRuleIds = matchedIds,
            skippedGreyLines = skippedGreyLines,
            hasNoSkillBanner = hasNoSkillBanner
        )
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        // Chrome / stat-bar lines from ToS 「齿轮 → 關卡資訊」 that never contain enemy skill rules
        private val STAGE_INFO_CHROME_REGEX = Regex(
            "^(?:wave\\s*\\d+(?:\\s*(?:current|next))?|current|next|boss|cd\\s*\\d+|基本资讯|关卡资讯|队伍资讯|离开战斗|取消)$",
            RegexOption.IGNORE_CASE
        )
        private val PURE_STATS_NUMBERS_REGEX = Regex("^[\\d,./+\\-\\s]+$")

        fun stripStageInfoChromeLines(normalizedText: String): List<String> {
            return normalizedText.lineSequence()
                .map { it.trim() }
                .filter { line ->
                    line.isNotEmpty() &&
                        !STAGE_INFO_CHROME_REGEX.matches(line) &&
                        !PURE_STATS_NUMBERS_REGEX.matches(line)
                }
                .toList()
        }

        fun fromJson(jsonText: String): EnemySkillRuleParser {
            val set = runCatching { json.decodeFromString<EnemySkillRuleSet>(jsonText) }
                .getOrElse { EnemySkillRuleSet() }
            return EnemySkillRuleParser(set)
        }

        /**
         * Fast Traditional -> Simplified & full-width -> half-width normalization tailored for ToS HK/TW OCR text.
         */
        fun normalizeText(raw: String): String {
            val map = mapOf(
                '屬' to '属', '強' to '强', '圖' to '图', '連' to '连', '擊' to '击',
                '雙' to '双', '單' to '单', '燒' to '烧', '蝕' to '蚀', '種' to '种',
                '數' to '数', '顆' to '颗', '軌' to '轨', '跡' to '迹', '轉' to '转',
                '鎖' to '锁', '風' to '风', '電' to '电', '體' to '体', '無' to '无',
                '視' to '视', '計' to '计', '僅' to '仅', '隻' to '只', '與' to '与',
                '類' to '类', '龍' to '龙', '獸' to '兽', '機' to '机', '術' to '术',
                '個' to '个', '粒' to '粒', '組' to '组', '黏' to '黏', '沒' to '没',
                '嚴' to '严', '格' to '格', '剛' to '刚', '須' to '须', '為' to '为',
                '開' to '开', '關' to '关', '點' to '点', '線' to '线', '場' to '场',
                '滿' to '满', '動' to '动', '傷' to '伤', '減' to '减', '區' to '区',
                '域' to '域', '異' to '异', '變' to '变', '換' to '换', '經' to '经',
                '過' to '过', '對' to '对', '應' to '应', '於' to '于', '會' to '会',
                '資' to '资', '訊' to '讯', '離' to '离', '戰' to '战', '鬥' to '斗',
                '隊' to '队', '敵' to '敌', '護' to '护', '圍' to '围', '繞' to '绕',
                '積' to '积', '達' to '达', '觸' to '触', '發' to '发', '時' to '时',
                '當' to '当', '後' to '后', '該' to '该', '內' to '内', '並' to '并',
                '將' to '将', '橫' to '横', '問' to '问', '號' to '号', '隱' to '隐',
                '條' to '条', '補' to '补', '進' to '进', '實' to '实', '選' to '选',
                '擇' to '择', '０' to '0', '１' to '1', '２' to '2', '３' to '3', '４' to '4',
                '５' to '5', '６' to '6', '７' to '7', '８' to '8', '９' to '9',
                '＋' to '+', '－' to '-', '×' to 'x'
            )
            val sb = StringBuilder(raw.length)
            for (ch in raw) {
                sb.append(map[ch] ?: ch)
            }
            return sb.toString()
                .replace('\r', '\n')
                .replace(Regex("[ \t]+"), " ")
                .trim()
        }

        private fun parseElementMask(chars: String): Int {
            var mask = 0
            for (ch in chars) {
                when (ch) {
                    '水' -> mask = mask or (1 shl Element.WATER.code)
                    '火' -> mask = mask or (1 shl Element.FIRE.code)
                    '木' -> mask = mask or (1 shl Element.WOOD.code)
                    '光' -> mask = mask or (1 shl Element.LIGHT.code)
                    '暗' -> mask = mask or (1 shl Element.DARK.code)
                    '心' -> mask = mask or (1 shl Element.HEART.code)
                }
            }
            return mask
        }
    }
}
