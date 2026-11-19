package com.autodora.next.vision

import com.autodora.next.model.BoardState
import com.autodora.next.model.Element
import com.autodora.next.model.HazardBits
import kotlin.math.max

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
    private val maxMemoryAgeMs: Long = 30_000L,
    private val minClearConfidence: Float = 0.64f
) {
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
    }

    /**
     * Feed a background watcher frame (typically 4-5 Hz during pre-turn watch mode).
     * Returns true if a mass flip to '?' occurred and locked a memorized snapshot!
     */
    @Synchronized
    fun onWatchFrame(result: RecognitionResult, nowMs: Long = System.currentTimeMillis()): Boolean {
        val total = result.board.cols * result.board.rows
        val hiddenCount = countHiddenOrQuestion(result.board)
        val hiddenFlipThreshold = max(6, total / 3)

        if (hiddenCount <= 2 && result.averageConfidence >= minClearConfidence) {
            // Clear genuine board observed -> refresh ring memory and release any stale lock
            lastClearBoard = result.board
            lastClearTimestampMs = nowMs
            lockedSnapshot = null
            return false
        }

        if (hiddenCount >= hiddenFlipThreshold) {
            val cached = lastClearBoard
            if (cached != null &&
                cached.cols == result.board.cols &&
                cached.rows == result.board.rows &&
                (nowMs - lastClearTimestampMs) <= maxMemoryAgeMs
            ) {
                if (lockedSnapshot == null) {
                    lockedSnapshot = cached
                    return true
                }
            }
        }
        return false
    }

    /**
     * Resolve which board to solve when user taps "算路":
     * If live frame is mass '?'/Hidden and a fresh memorized snapshot exists, returns [MemoryResolution.UseMemorizedSnapshot]
     * (preserving any freshly detected tileFlags like Puzzle borders from the live board).
     */
    @Synchronized
    fun resolveForSolve(liveResult: RecognitionResult, nowMs: Long = System.currentTimeMillis()): MemoryResolution {
        val total = liveResult.board.cols * liveResult.board.rows
        val hiddenCount = countHiddenOrQuestion(liveResult.board)
        val hiddenFlipThreshold = max(6, total / 3)

        if (hiddenCount >= hiddenFlipThreshold) {
            val snap = lockedSnapshot ?: lastClearBoard
            val age = nowMs - lastClearTimestampMs
            if (snap != null &&
                snap.cols == liveResult.board.cols &&
                snap.rows == liveResult.board.rows &&
                age <= maxMemoryAgeMs
            ) {
                lockedSnapshot = snap
                // Merge live tileFlags (e.g., golden puzzle borders visible over '?' orbs) onto remembered cells
                val mergedFlags = List(total) { i ->
                    snap.tileFlags[i] or liveResult.board.tileFlags[i]
                }
                val mergedBoard = snap.copy(tileFlags = mergedFlags)
                return MemoryResolution.UseMemorizedSnapshot(mergedBoard, hiddenCount, age)
            }
        } else if (hiddenCount <= 2 && liveResult.averageConfidence >= minClearConfidence) {
            lastClearBoard = liveResult.board
            lastClearTimestampMs = nowMs
            lockedSnapshot = null
        }
        return MemoryResolution.UseLiveBoard(liveResult)
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
        lastClearBoard = null
        lastClearTimestampMs = 0L
        lockedSnapshot = null
    }

    companion object {
        fun countHiddenOrQuestion(board: BoardState): Int = board.cells.count {
            it.element == Element.HIDDEN ||
                it.element == Element.UNKNOWN ||
                (it.hazards and HazardBits.QUESTION) != 0
        }
    }
}
