package com.autodora.next.model

import kotlinx.serialization.Serializable

/**
 * Board-level visual & terrain hints extracted from enemy skill descriptions (Phase 4.2 V2).
 * Shared across :core-vision (OCR + HybridFusionPolicy), :core-data (StageWaveScript), and :app (Overlay HUD).
 */
@Serializable
data class BoardVisualHints(
    val hasMonochrome: Boolean = false,
    val hasDisguise: Boolean = false,
    val hasBurning: Boolean = false,
    val hasScorching: Boolean = false,
    val hasElectrified: Boolean = false,
    val hasSticky: Boolean = false,
    val hasStorm: Boolean = false,
    val hasShattered: Boolean = false,
    val hasBlackHole: Boolean = false,
    val hasBloodbath: Boolean = false,
    val hasBarrier: Boolean = false,
    val hasPurgatoryFire: Boolean = false,
    val hasMud: Boolean = false,
    val hasMiasma: Boolean = false,
    val hasHiddenOrbs: Boolean = false,
    val hasFrozenOrPetrified: Boolean = false,
    val hasWeatheredOrBomb: Boolean = false,
    val hasSpecialMarkOrFruit: Boolean = false
) {
    fun mergeWith(other: BoardVisualHints): BoardVisualHints = BoardVisualHints(
        hasMonochrome = hasMonochrome || other.hasMonochrome,
        hasDisguise = hasDisguise || other.hasDisguise,
        hasBurning = hasBurning || other.hasBurning,
        hasScorching = hasScorching || other.hasScorching,
        hasElectrified = hasElectrified || other.hasElectrified,
        hasSticky = hasSticky || other.hasSticky,
        hasStorm = hasStorm || other.hasStorm,
        hasShattered = hasShattered || other.hasShattered,
        hasBlackHole = hasBlackHole || other.hasBlackHole,
        hasBloodbath = hasBloodbath || other.hasBloodbath,
        hasBarrier = hasBarrier || other.hasBarrier,
        hasPurgatoryFire = hasPurgatoryFire || other.hasPurgatoryFire,
        hasMud = hasMud || other.hasMud,
        hasMiasma = hasMiasma || other.hasMiasma,
        hasHiddenOrbs = hasHiddenOrbs || other.hasHiddenOrbs,
        hasFrozenOrPetrified = hasFrozenOrPetrified || other.hasFrozenOrPetrified,
        hasWeatheredOrBomb = hasWeatheredOrBomb || other.hasWeatheredOrBomb,
        hasSpecialMarkOrFruit = hasSpecialMarkOrFruit || other.hasSpecialMarkOrFruit
    )

    fun hasAny(): Boolean =
        hasMonochrome || hasDisguise || hasBurning || hasScorching ||
            hasElectrified || hasSticky || hasStorm || hasShattered || hasBlackHole ||
            hasBloodbath || hasBarrier || hasPurgatoryFire || hasMud || hasMiasma ||
            hasHiddenOrbs || hasFrozenOrPetrified || hasWeatheredOrBomb || hasSpecialMarkOrFruit
}

/**
 * Single wave (battle floor / BOSS) inside a Stage Wave Script parsed from pre-battle/in-battle Stage Info
 * or imported from crawled Tower of Saviors Wiki dungeon data.
 */
@Serializable
data class StageWaveEntry(
    val waveIndex: Int,
    val isBoss: Boolean = false,
    val skillTitles: List<String> = emptyList(),
    val chips: List<WaveShieldChip> = emptyList(),
    val visualHints: BoardVisualHints = BoardVisualHints()
)

@Serializable
data class StageWaveScript(
    val stageKey: String,
    val stageName: String,
    val waves: List<StageWaveEntry>,
    val sourceType: String = "STAGE_INFO_OCR",
    val updatedAtMs: Long = 0L,
    val entryCategory: String = "",
    val subCategory: String = "",
    val dungeonSeries: String = "",
    val difficultyTag: String = ""
)

@Serializable
data class StageWikiImportBundle(
    val stages: List<StageWaveScript> = emptyList()
)

data class StageHierarchyTuple(
    val entryCategory: String,
    val subCategory: String,
    val dungeonSeries: String,
    val difficultyTag: String
)

/**
 * Canonical 3-level Tower of Saviors in-game portal classifier shared across `:core-model`,
 * `:core-data`, `:core-vision`, and `:app`:
 *   Level 1 (`entryCategory`): `古神遺跡 | 大型活動 | 故事模式 | 育成訓練 | 遺跡特許 | 战中采集`
 *   Level 2 (`subCategory`):   e.g. `災厄級 | 夢魘級 | 地獄級 | 每月挑戰 | 主線故事 | 敵技訓練 ...`
 *   Level 3 (`dungeonSeries`): Cleaned dungeon/chapter series name + `difficultyTag`
 */
object StageHierarchyHelper {

    val ENTRY_ORDER = listOf("古神遺跡", "大型活動", "故事模式", "育成訓練", "遺跡特許", "战中采集")

    fun inferDifficultyTag(stageName: String, rawDiff: String): String {
        if (rawDiff.isNotBlank()) return rawDiff.trim()
        val s = stageName
        return when {
            s.contains("災厄") || s.contains("灾厄") -> "災厄級"
            s.contains("夢魘") || s.contains("梦魇") -> "夢魘級"
            s.contains("地獄") || s.contains("地狱") -> "地獄級"
            s.contains("Extra", ignoreCase = true) || Regex("Lv\\.?\\s*\\d+", RegexOption.IGNORE_CASE).containsMatchIn(s) -> "挑戰/Lv"
            s.contains("困難") || s.contains("困难") -> "困難"
            s.contains("進階") || s.contains("进阶") || s.contains("高級") || s.contains("高级") || s.contains("專修") || s.contains("专修") -> "進階"
            s.contains("初級") || s.contains("初级") || s.contains("初階") || s.contains("初阶") -> "初階"
            else -> ""
        }
    }

    fun infer(
        rawEntry: String,
        rawSub: String,
        rawSeries: String,
        rawDiff: String,
        stageName: String,
        sourceType: String
    ): StageHierarchyTuple {
        val diff = inferDifficultyTag(stageName, rawDiff)
        val cleanSeries = rawSeries
            .replace(Regex("^\\d{3}_"), "")
            .replace("|", "I")
            .replace("ⅡI", "III")
            .replace("Ⅱ", "II")
            .replace(Regex("_\\d+$"), "")
            .replace("(新手適用", "雙週活動")
            .replace("《新手適用", "雙週活動")
            .trim()
            .ifBlank { stageName.substringBefore(" · ").trim() }

        if (rawEntry in ENTRY_ORDER && rawSub.isNotBlank()) {
            return StageHierarchyTuple(rawEntry, rawSub.trim(), cleanSeries, diff)
        }

        val combined = "$rawEntry $cleanSeries $stageName"
        val entry = when {
            sourceType == "STAGE_INFO_OCR" && rawEntry.isBlank() -> "战中采集"
            combined.contains("古神遺跡") || combined.contains("古神遗迹") -> "古神遺跡"
            combined.contains("大型活動") || combined.contains("大型活动") -> "大型活動"
            combined.contains("新故事") || combined.contains("故事模式") -> "故事模式"
            combined.contains("育成訓練") || combined.contains("育成训练") -> "育成訓練"
            combined.contains("遺跡特許") || combined.contains("遗迹特许") -> "遺跡特許"
            combined.contains("挑戰任務") || combined.contains("挑战任务") || combined.contains("靈罩塔") || combined.contains("灵罩塔") -> "大型活動"
            combined.contains("第") && (combined.contains("話") || combined.contains("话")) -> "故事模式"
            combined.contains("敵技") || combined.contains("敌技") || combined.contains("之日") -> "育成訓練"
            combined.contains("災厄") || combined.contains("灾厄") || combined.contains("夢魘") || combined.contains("梦魇") || combined.contains("地獄") || combined.contains("地狱") -> "古神遺跡"
            else -> if (sourceType == "STAGE_INFO_OCR") "战中采集" else "大型活動"
        }

        val sub = when (entry) {
            "古神遺跡" -> when {
                combined.contains("災厄") || combined.contains("灾厄") -> "災厄級"
                combined.contains("夢魘") || combined.contains("梦魇") -> "夢魘級"
                combined.contains("地獄") || combined.contains("地狱") -> "地獄級"
                combined.contains("是日") || combined.contains("資源") || combined.contains("资源") -> "日常遺跡"
                else -> "高難試煉"
            }
            "大型活動" -> when {
                combined.contains("初心") || combined.contains("精選") || combined.contains("精选") || combined.contains("契機") || combined.contains("契机") -> "初心試煉塔"
                combined.contains("每月挑戰") || combined.contains("每月挑战") || combined.contains("挑戰任務") || combined.contains("挑战任务") -> "每月挑戰"
                combined.contains("靈罩塔") || combined.contains("灵罩塔") -> "靈罩塔"
                else -> "雙週與活動"
            }
            "故事模式" -> when {
                combined.contains("以諾") || combined.contains("以诺") -> "以諾時代"
                combined.contains("嶄新") || combined.contains("崭新") || combined.contains("間章") || combined.contains("间章") -> "嶄新封印"
                combined.contains("副線") || combined.contains("副线") -> "故事副線"
                else -> "主線故事"
            }
            "育成訓練" -> when {
                combined.contains("敵技") || combined.contains("敌技") || combined.contains("模擬應戰") || combined.contains("模拟应战") -> "敵技訓練"
                combined.contains("之日") || combined.contains("靈魂石") || combined.contains("灵魂石") -> "每日資源關"
                else -> "成長與挑戰"
            }
            "遺跡特許" -> when {
                combined.contains("夢魘") || combined.contains("梦魇") -> "夢魘級"
                combined.contains("地獄") || combined.contains("地狱") -> "地獄級"
                else -> "戰魂憶魂"
            }
            else -> "关卡资讯抓取"
        }

        return StageHierarchyTuple(entry, sub, cleanSeries, diff)
    }
}
