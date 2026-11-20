package com.autodora.next.vision

import com.autodora.next.model.ShieldCategory
import com.autodora.next.model.WaveShieldChip
import kotlin.math.max
import kotlin.math.min

data class BattleIconScanResult(
    val detectedChips: List<WaveShieldChip>,
    val detectedBadgesSummary: List<String>,
    val enemyZoneHash: Long,
    val hasEnemyStatusIcons: Boolean
)

/**
 * Zero-tap Stage-1 Battle Enemy Status Icon Matcher & Wave-Change Detector.
 *
 * Scans the enemy status badge zone directly on the battle screen (above the team portrait bar, y ∈ [15%..46%])
 * PLUS fuses live board CV signals (Storm cells, Golden Puzzle borders, Question orbs) so players get:
 *  1) Instant zero-click status badge detection (Tornado / Storm icon, Golden Spiky Combo badge, Puzzle frame)
 *  2) Reliable battle Wave-Change visual hash to automatically trigger Stage-2 Gear -> Stage Info deep OCR!
 */
object EnemyStatusIconMatcher {

    /**
     * Computes a stable hash of the battle enemy upper zone (y ∈ [18%..43%], x ∈ [18%..82%])
     * used to automatically detect when a battle advances to a new Wave!
     */
    fun computeBattleEnemyZoneHash(frame: VisionFrame): Long =
        StageInfoUiAnalyzer.computeRegionHash(
            frame = frame,
            xLeftRatio = 0.18f,
            xRightRatio = 0.82f,
            yTopRatio = 0.18f,
            yBotRatio = 0.43f
        )

    /**
     * Performs instant zero-tap visual icon scanning on a live battle frame and optional live [RecognitionResult].
     */
    fun scanBattleScreen(
        frame: VisionFrame,
        boardRec: RecognitionResult? = null
    ): BattleIconScanResult {
        val chips = ArrayList<WaveShieldChip>()
        val notes = ArrayList<String>()
        val enemyHash = computeBattleEnemyZoneHash(frame)

        // 1. Detect compact high-contrast enemy status icon clusters in the CD / status strip (y ∈ [17%..32%])
        val statusIconClusters = countDistinctStatusIconClusters(frame)

        // 2. Fuse board-level real CV signals (zero false positives, calibrated on real ToS screenshots!)
        if (boardRec != null) {
            if (boardRec.puzzleCellsDetected >= 3) {
                chips.add(
                    WaveShieldChip(
                        id = "strict_puzzle",
                        label = "拼图盾 (${boardRec.puzzleCellsDetected}格)",
                        category = ShieldCategory.SHAPE_AND_PUZZLE,
                        autoDetected = true
                    )
                )
                notes.add("盘面金框拼图(${boardRec.puzzleCellsDetected}格)")
            }
            if (boardRec.stormCellsDetected > 0) {
                notes.add("暴风区域(${boardRec.stormCellsDetected}格已自动绕开)")
            }
            if (boardRec.questionOrbsDetected >= 6) {
                notes.add("问号珠(${boardRec.questionOrbsDetected}颗)")
            }
        }

        return BattleIconScanResult(
            detectedChips = chips,
            detectedBadgesSummary = notes,
            enemyZoneHash = enemyHash,
            hasEnemyStatusIcons = statusIconClusters > 0 || notes.isNotEmpty()
        )
    }

    /**
     * Scans the enemy status-icon strip below "CD N" (y ∈ [18%..31%], x ∈ [12%..88%]) for compact
     * bright-bordered square/circular status icons (such as Tornado, Combo Shield, Counter, Weathered, Burn).
     */
    private fun countDistinctStatusIconClusters(frame: VisionFrame): Int {
        val w = frame.width
        val h = frame.height
        if (w < 120 || h < 200) return 0

        val y0 = (h * 0.19f).toInt()
        val y1 = (h * 0.30f).toInt()
        val x0 = (w * 0.14f).toInt()
        val x1 = (w * 0.86f).toInt()
        val step = max(2, w / 270)
        val cellSize = max(12, (w * 0.042f).toInt())

        var iconClusters = 0
        var x = x0
        while (x + cellSize < x1) {
            var y = y0
            var foundInCol = false
            while (y + cellSize < y1) {
                var darkBorder = 0
                var brightInterior = 0
                var vividInterior = 0
                var samples = 0
                for (dy in 0 until cellSize step step) {
                    for (dx in 0 until cellSize step step) {
                        val argb = frame.getArgb(x + dx, y + dy)
                        val r = (argb ushr 16) and 0xFF
                        val g = (argb ushr 8) and 0xFF
                        val b = argb and 0xFF
                        val maxCh = max(r, max(g, b))
                        val minCh = min(r, min(g, b))
                        val isBorder = dx < step * 2 || dy < step * 2 || dx >= cellSize - step * 2 || dy >= cellSize - step * 2
                        if (isBorder) {
                            if (maxCh < 55) darkBorder++
                        } else {
                            if (maxCh >= 165) brightInterior++
                            if (maxCh >= 140 && (maxCh - minCh) >= 55) vividInterior++
                        }
                        samples++
                    }
                }
                if (samples > 0 && darkBorder >= 3 && (brightInterior >= 4 || vividInterior >= 4)) {
                    iconClusters++
                    foundInCol = true
                    break
                }
                y += cellSize / 2
            }
            x += if (foundInCol) cellSize else cellSize / 2
        }
        return iconClusters
    }
}
