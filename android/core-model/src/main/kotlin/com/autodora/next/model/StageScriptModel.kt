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
    val hasSticky: Boolean = false,
    val hasStorm: Boolean = false,
    val hasShattered: Boolean = false,
    val hasBlackHole: Boolean = false,
    val hasBloodbath: Boolean = false,
    val hasBarrier: Boolean = false,
    val hasPurgatoryFire: Boolean = false,
    val hasMud: Boolean = false,
    val hasMiasma: Boolean = false
) {
    fun mergeWith(other: BoardVisualHints): BoardVisualHints = BoardVisualHints(
        hasMonochrome = hasMonochrome || other.hasMonochrome,
        hasDisguise = hasDisguise || other.hasDisguise,
        hasBurning = hasBurning || other.hasBurning,
        hasScorching = hasScorching || other.hasScorching,
        hasSticky = hasSticky || other.hasSticky,
        hasStorm = hasStorm || other.hasStorm,
        hasShattered = hasShattered || other.hasShattered,
        hasBlackHole = hasBlackHole || other.hasBlackHole,
        hasBloodbath = hasBloodbath || other.hasBloodbath,
        hasBarrier = hasBarrier || other.hasBarrier,
        hasPurgatoryFire = hasPurgatoryFire || other.hasPurgatoryFire,
        hasMud = hasMud || other.hasMud,
        hasMiasma = hasMiasma || other.hasMiasma
    )

    fun hasAny(): Boolean =
        hasMonochrome || hasDisguise || hasBurning || hasScorching ||
            hasSticky || hasStorm || hasShattered || hasBlackHole ||
            hasBloodbath || hasBarrier || hasPurgatoryFire || hasMud || hasMiasma
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
    val updatedAtMs: Long = 0L
)

@Serializable
data class StageWikiImportBundle(
    val stages: List<StageWaveScript> = emptyList()
)
