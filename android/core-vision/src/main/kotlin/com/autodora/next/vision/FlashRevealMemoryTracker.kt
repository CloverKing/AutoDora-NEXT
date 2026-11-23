package com.autodora.next.vision

import com.autodora.next.model.BoardState
import com.autodora.next.model.Element
import com.autodora.next.model.HazardBits
import kotlin.math.max
import kotlin.math.min

/**
 * Flash-Reveal Memory Snapshot Tracker (先露后藏全版问号珠 / 隐藏珠记忆快照引擎).
 *
 * Tower of Saviors stages (e.g., 全版问号珠, 先露后藏) display the genuine rune board for ~1-2 seconds
 * at wave/turn start before flipping the entire board to '?' Question/Hidden orbs.
 *
 * This lightweight tracker continuously watches sampled boards:
 *  1) When a clear high-confidence board is seen, caches it as [lastClearBoard].
 *  2) The instant the board flips to mass '?' / Hidden orbs, locks [lockedSnapshot] and seamlessly
 *     provides the remembered real board for translucent overlay rendering and C++20 solving!
 */
class FlashRevealMemoryTracker(
    private val maxMemoryAgeMs: Long = 90_000L,
    private val minClearConfidence: Float = 0.68f
) {
    private data class ClearSnapshot(
        val board: BoardState,
        val timestampMs: Long,
        val avgConfidence: Float
    )

    // Rolling ring of recent vivid clear boards so a sudden flip or fading transition frame NEVER overwrites clear memory!
    private val clearHistory = ArrayDeque<ClearSnapshot>(10)

    @Volatile
    private var lastClearBoard: BoardState? = null

    @Volatile
    private var lastClearTimestampMs: Long = 0L

    @Volatile
    private var lockedSnapshot: BoardState? = null

    sealed class MemoryResolution {
        data class UseLiveBoard(val result: RecognitionResult) : MemoryResolution()
        data class UseMemorizedSnapshot(
            val rememberedBoard: BoardState,
            val liveHiddenCount: Int,
            val snapshotAgeMs: Long
        ) : MemoryResolution()

        /**
         * Live board is mass '?' / Hidden and NO prior clear board was seen (e.g. entered wave already full '?'
         * in 「问号‧转」 stages) -> triggers legacy-AutoDora-style Hold-and-Nudge-Reveal automatically!
         */
        data class NeedHoldAndNudgeReveal(
            val liveHiddenCount: Int
        ) : MemoryResolution()
    }

    /**
     * Always-on background watcher feed (~6 Hz when idle).
     * Returns true the instant a mass flip to '?' locks a memorized pre-'?' snapshot!
     */
    @Synchronized
    fun onWatchFrame(result: RecognitionResult, nowMs: Long = System.currentTimeMillis()): Boolean {
        val total = result.board.cols * result.board.rows
        val unknownCount = result.board.cells.count { it.element == Element.UNKNOWN || it.element == Element.EMPTY }
        // Never treat dark modals/menus/home screens (high UNKNOWN cells) as a question-orb flip!
        if (unknownCount >= max(6, total / 3)) {
            return false
        }
        val hiddenCount = countHiddenOrQuestion(result.board)
        // Genuine ToS full-board '?' (先露后藏) flips >= 60% of the board (18+/30 cells) simultaneously;
        // requiring >= 60% prevents dimmed VICTORY or wave-transition animations (6..12 dim cells) from falsely popping overlays!
        val hiddenFlipThreshold = max(18, (total * 0.60f).toInt())

        if (isCleanVividBattleBoard(result, minClearConfidence)) {
            val snap = ClearSnapshot(result.board, nowMs, result.averageConfidence)
            if (clearHistory.size >= 8) clearHistory.removeFirst()
            clearHistory.addLast(snap)
            lastClearBoard = result.board
            lastClearTimestampMs = nowMs
            // Only unlock a locked snapshot if the board has genuinely returned to a clear non-'?' state
            lockedSnapshot = null
            return false
        }

        if (hiddenCount >= hiddenFlipThreshold) {
            val cached = pickBestRecentClearSnapshot(result.board.cols, result.board.rows, nowMs)
            if (cached != null) {
                if (lockedSnapshot == null) {
                    lockedSnapshot = cached.board
                    lastClearBoard = cached.board
                    lastClearTimestampMs = cached.timestampMs
                    return true
                }
            }
        }
        return false
    }

    /**
     * Resolve which board to solve when user taps "算":
     *  1) If live frame is mass '?'/Hidden and a fresh memorized snapshot exists -> UseMemorizedSnapshot
     *  2) If live frame is mass '?'/Hidden (>= half board) and NO snapshot exists -> NeedHoldAndNudgeReveal
     *  3) Otherwise -> UseLiveBoard (and refresh rolling clear memory if vivid)
     */
    @Synchronized
    fun resolveForSolve(liveResult: RecognitionResult, nowMs: Long = System.currentTimeMillis()): MemoryResolution {
        val total = liveResult.board.cols * liveResult.board.rows
        val hiddenCount = countHiddenOrQuestion(liveResult.board)
        val hiddenFlipThreshold = max(6, total / 3)

        if (hiddenCount >= hiddenFlipThreshold) {
            val snapBoard = lockedSnapshot ?: pickBestRecentClearSnapshot(
                liveResult.board.cols, liveResult.board.rows, nowMs
            )?.board
            val age = nowMs - lastClearTimestampMs
            if (snapBoard != null &&
                snapBoard.cols == liveResult.board.cols &&
                snapBoard.rows == liveResult.board.rows &&
                age <= maxMemoryAgeMs
            ) {
                lockedSnapshot = snapBoard
                // Merge live tileFlags (e.g., golden puzzle borders / storm visible over '?' orbs) onto remembered cells
                val mergedFlags = List(total) { i ->
                    snapBoard.tileFlags[i] or liveResult.board.tileFlags[i]
                }
                val mergedBoard = snapBoard.copy(tileFlags = mergedFlags)
                return MemoryResolution.UseMemorizedSnapshot(mergedBoard, hiddenCount, age)
            }
            if (hiddenCount >= max(10, total / 2)) {
                return MemoryResolution.NeedHoldAndNudgeReveal(hiddenCount)
            }
        } else if (isCleanVividBattleBoard(liveResult, minClearConfidence)) {
            val snap = ClearSnapshot(liveResult.board, nowMs, liveResult.averageConfidence)
            if (clearHistory.size >= 8) clearHistory.removeFirst()
            clearHistory.addLast(snap)
            lastClearBoard = liveResult.board
            lastClearTimestampMs = nowMs
            lockedSnapshot = null
        }
        return MemoryResolution.UseLiveBoard(liveResult)
    }

    private fun pickBestRecentClearSnapshot(cols: Int, rows: Int, nowMs: Long): ClearSnapshot? {
        // Walk newest -> oldest; prefer the freshest vivid frame within maxMemoryAgeMs
        for (i in clearHistory.indices.reversed()) {
            val s = clearHistory[i]
            if (s.board.cols == cols && s.board.rows == rows && (nowMs - s.timestampMs) <= maxMemoryAgeMs) {
                return s
            }
        }
        val fallback = lastClearBoard
        if (fallback != null && fallback.cols == cols && fallback.rows == rows && (nowMs - lastClearTimestampMs) <= maxMemoryAgeMs) {
            return ClearSnapshot(fallback, lastClearTimestampMs, minClearConfidence)
        }
        return null
    }

    @Synchronized
    fun currentLockedSnapshot(): BoardState? = lockedSnapshot

    @Synchronized
    fun hasFreshClearMemory(nowMs: Long = System.currentTimeMillis()): Boolean =
        lastClearBoard != null && (nowMs - lastClearTimestampMs) <= maxMemoryAgeMs

    @Synchronized
    fun clearLock() {
        lockedSnapshot = null
    }

    @Synchronized
    fun resetAll() {
        clearHistory.clear()
        lastClearBoard = null
        lastClearTimestampMs = 0L
        lockedSnapshot = null
    }

    companion object {
        /**
         * Only count genuine '?' Question / Hidden orbs (NEVER menu/fade-out UNKNOWN frames!),
         * so stage-entry loading screens or animations never falsely lock a stale board snapshot.
         */
        fun countHiddenOrQuestion(board: BoardState): Int = board.cells.count {
            it.element == Element.HIDDEN ||
                (it.hazards and HazardBits.QUESTION) != 0
        }

        /**
         * Strict quality gate ensuring ONLY genuine clear battle boards enter rolling memory —
         * fading animations, menu screens, or partially-flipped '?' boards NEVER pollute memory!
         */
        fun isCleanVividBattleBoard(result: RecognitionResult, minConf: Float): Boolean {
            val board = result.board
            val total = board.cols * board.rows
            if (total < 9) return false
            val hiddenCount = countHiddenOrQuestion(board)
            if (hiddenCount > 1) return false
            val unknownCount = board.cells.indices.count { i ->
                val el = board.cells[i].element
                val isStorm = (board.tileFlags[i] and com.autodora.next.model.TileFlagBits.STORM) != 0
                val isPetrified = (board.cells[i].hazards and HazardBits.PETRIFIED) != 0
                !isStorm && !isPetrified && (el == Element.UNKNOWN || el == Element.EMPTY)
            }
            if (unknownCount > 2) return false
            if (result.averageConfidence < minConf) return false
            // Require >= 2 distinct matchable colors so uniform single-color enemy attack/skill flashes
            // (e.g., bright golden hit flash) NEVER overwrite memorized question-orb snapshots with 30 fake Light orbs!
            val distinctColors = board.cells.mapNotNull { if (it.element.isMatchable) it.element else null }.toSet().size
            return distinctColors >= 2
        }

        /**
         * Lightweight check for dark full-screen/popup modals (e.g., player manually long-pressing an enemy
         * or opening the Stage Info modal) so memorized translucent question-orb overlays never paint on top of them.
         */
        fun isDarkModalCoveringBoard(frame: VisionFrame): Boolean {
            val w = frame.width
            val h = frame.height
            if (w < 120 || h < 200) return false

            fun isRegionUniformDark(xFrac0: Float, xFrac1: Float, yFrac0: Float, yFrac1: Float): Boolean {
                val x0 = (w * xFrac0).toInt()
                val x1 = (w * xFrac1).toInt()
                val y0 = (h * yFrac0).toInt()
                val y1 = (h * yFrac1).toInt()
                val stepX = max(2, (x1 - x0) / 16)
                val stepY = max(2, (y1 - y0) / 10)
                var darkNeutral = 0
                var vividColored = 0
                var total = 0
                var y = y0
                while (y <= y1) {
                    var x = x0
                    while (x <= x1) {
                        val argb = frame.getArgb(x, y)
                        val r = (argb ushr 16) and 0xFF
                        val g = (argb ushr 8) and 0xFF
                        val b = argb and 0xFF
                        val maxC = max(r, max(g, b))
                        val minC = min(r, min(g, b))
                        if (maxC <= 62 && (maxC - minC) <= 24) {
                            darkNeutral++
                        } else if (maxC >= 120 && (maxC - minC) >= 55) {
                            vividColored++
                        }
                        total++
                        x += stepX
                    }
                    y += stepY
                }
                return total > 0 && (darkNeutral.toFloat() / total) >= 0.58f && (vividColored.toFloat() / total) <= 0.08f
            }

            // Require BOTH upper modal header/body (41%..53%) AND lower modal body covering the board (58%..78%)
            // to be uniform dark charcoal — guarantees 100% detection of enemy long-press / stage-info modals
            // with 0% false positives on dark dungeon battle backgrounds!
            return isRegionUniformDark(0.18f, 0.82f, 0.41f, 0.53f) &&
                isRegionUniformDark(0.20f, 0.80f, 0.58f, 0.78f)
        }
    }
}
