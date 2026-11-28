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
enum class DarkModalKind {
    NONE,
    ENEMY_LONG_PRESS_POPUP,
    STAGE_INFO_MODAL
}

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
                val isShattered = (board.tileFlags[i] and com.autodora.next.model.TileFlagBits.SHATTERED) != 0
                val isPetrified = (board.cells[i].hazards and HazardBits.PETRIFIED) != 0
                !isStorm && !isShattered && !isPetrified && (el == Element.UNKNOWN || el == Element.EMPTY)
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

            data class RegionDarkStats(
                val darkNeutralRatio: Float,
                val pureBlackRatio: Float,
                val vividColoredRatio: Float
            )

            fun measureDarkRegion(xFrac0: Float, xFrac1: Float, yFrac0: Float, yFrac1: Float): RegionDarkStats {
                val x0 = (w * xFrac0).toInt()
                val x1 = (w * xFrac1).toInt()
                val y0 = (h * yFrac0).toInt()
                val y1 = (h * yFrac1).toInt()
                val stepX = max(2, (x1 - x0) / 16)
                val stepY = max(2, (y1 - y0) / 10)
                var darkNeutral = 0
                var pureBlack = 0
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
                            if (maxC <= 45 && (maxC - minC) <= 22) {
                                pureBlack++
                            }
                        } else if (maxC >= 120 && (maxC - minC) >= 55) {
                            vividColored++
                        }
                        total++
                        x += stepX
                    }
                    y += stepY
                }
                if (total <= 0) return RegionDarkStats(0f, 0f, 1f)
                return RegionDarkStats(
                    darkNeutralRatio = darkNeutral.toFloat() / total,
                    pureBlackRatio = pureBlack.toFloat() / total,
                    vividColoredRatio = vividColored.toFloat() / total
                )
            }

            // 1) Dual-region enemy skill / stage info modal:
            //    Upper header/title band (41%..53%) tolerates golden attribute icons & yellow 【技能】 titles
            //    (vivid <= 0.28f) on top of >= 55% dark modal background, paired with dark lower body (58%..78%).
            val upper = measureDarkRegion(0.18f, 0.82f, 0.41f, 0.53f)
            val lower = measureDarkRegion(0.20f, 0.80f, 0.58f, 0.78f)
            if (upper.darkNeutralRatio >= 0.55f && upper.vividColoredRatio <= 0.28f &&
                lower.darkNeutralRatio >= 0.58f && lower.vividColoredRatio <= 0.12f
            ) {
                return true
            }

            // 2) Pitch-black board-covering modal body (54%..84%):
            //    Even if a compact popup begins below the portraits, >= 58% pure-black neutral pixels
            //    with <= 8% vivid pixels cleanly separates text modals from real 30/30 '?' or locked orb boards
            //    (where slate-blue '?' orbs and stone-locked orbs keep pureBlack <= 0.30).
            val boardBody = measureDarkRegion(0.16f, 0.84f, 0.54f, 0.84f)
            return boardBody.pureBlackRatio >= 0.58f && boardBody.vividColoredRatio <= 0.08f
        }

        /**
         * Classifies whether a detected dark modal frame contains readable text suitable for training capture,
         * separating full-screen Stage Info modals (top dark backdrop + bottom teal action buttons) from
         * in-battle enemy long-press skill popups while rejecting pure-black transition/blank screens.
         */
        fun classifyDarkModalForCapture(frame: VisionFrame): DarkModalKind {
            if (!isDarkModalCoveringBoard(frame)) return DarkModalKind.NONE
            val w = frame.width
            val h = frame.height
            if (w < 120 || h < 200) return DarkModalKind.NONE

            // Check modal interior [14%..86%] x [42%..82%] for crisp readable white/grey/yellow skill text lines
            val ix0 = (w * 0.14f).toInt()
            val ix1 = (w * 0.86f).toInt()
            val iy0 = (h * 0.42f).toInt()
            val iy1 = (h * 0.82f).toInt()
            val iStepX = max(2, (ix1 - ix0) / 24)
            val iStepY = max(2, (iy1 - iy0) / 16)
            var readableTextPx = 0
            var totalInteriorPx = 0
            var y = iy0
            while (y <= iy1) {
                var x = ix0
                while (x <= ix1) {
                    val argb = frame.getArgb(x, y)
                    val r = (argb ushr 16) and 0xFF
                    val g = (argb ushr 8) and 0xFF
                    val b = argb and 0xFF
                    val maxC = max(r, max(g, b))
                    val minC = min(r, min(g, b))
                    if (maxC >= 85 && (maxC - minC) <= 68) {
                        readableTextPx++
                    }
                    totalInteriorPx++
                    x += iStepX
                }
                y += iStepY
            }
            val textRatio = if (totalInteriorPx > 0) readableTextPx.toFloat() / totalInteriorPx else 0f
            if (textRatio < 0.004f || textRatio > 0.38f) return DarkModalKind.NONE

            // Distinguish full-screen Stage Info modals (top header + covered portrait/HP strip + bottom teal buttons)
            // from in-battle Enemy Long-Press Popups on dark/abyss battle backgrounds (where portraits/HP strip remain visible).
            val ux0 = (w * 0.15f).toInt()
            val ux1 = (w * 0.85f).toInt()
            val uy0 = (h * 0.09f).toInt()
            val uy1 = (h * 0.28f).toInt()
            val uStepX = max(2, (ux1 - ux0) / 16)
            val uStepY = max(2, (uy1 - uy0) / 10)
            var upperDark = 0
            var upperVivid = 0
            var upperTotal = 0
            y = uy0
            while (y <= uy1) {
                var x = ux0
                while (x <= ux1) {
                    val argb = frame.getArgb(x, y)
                    val r = (argb ushr 16) and 0xFF
                    val g = (argb ushr 8) and 0xFF
                    val b = argb and 0xFF
                    val maxC = max(r, max(g, b))
                    val minC = min(r, min(g, b))
                    if (maxC <= 62 && (maxC - minC) <= 24) upperDark++
                    else if (maxC >= 115 && (maxC - minC) >= 48) upperVivid++
                    upperTotal++
                    x += uStepX
                }
                y += uStepY
            }
            val upperDarkRatio = if (upperTotal > 0) upperDark.toFloat() / upperTotal else 0f
            val upperVividRatio = if (upperTotal > 0) upperVivid.toFloat() / upperTotal else 0f

            // Check battle team-portrait & pink HP bar strip [0.33..0.41]:
            // In-battle long-press popups sit over the board while team portraits / HP bar stay vivid above the popup;
            // full-screen Stage Info modals cover [0.06..0.93] with dark neutral list rows.
            val mx0 = (w * 0.08f).toInt()
            val mx1 = (w * 0.92f).toInt()
            val my0 = (h * 0.33f).toInt()
            val my1 = (h * 0.41f).toInt()
            val mStepX = max(2, (mx1 - mx0) / 20)
            val mStepY = max(2, (my1 - my0) / 6)
            var midDark = 0
            var midVivid = 0
            var midTotal = 0
            y = my0
            while (y <= my1) {
                var x = mx0
                while (x <= mx1) {
                    val argb = frame.getArgb(x, y)
                    val r = (argb ushr 16) and 0xFF
                    val g = (argb ushr 8) and 0xFF
                    val b = argb and 0xFF
                    val maxC = max(r, max(g, b))
                    val minC = min(r, min(g, b))
                    if (maxC <= 65 && (maxC - minC) <= 26) midDark++
                    else if (maxC >= 120 && (maxC - minC) >= 50) midVivid++
                    midTotal++
                    x += mStepX
                }
                y += mStepY
            }
            val midDarkRatio = if (midTotal > 0) midDark.toFloat() / midTotal else 0f
            val midVividRatio = if (midTotal > 0) midVivid.toFloat() / midTotal else 0f

            val bx0 = (w * 0.12f).toInt()
            val bx1 = (w * 0.88f).toInt()
            val by0 = (h * 0.89f).toInt()
            val by1 = (h * 0.95f).toInt()
            val bStepX = max(2, (bx1 - bx0) / 20)
            val bStepY = max(2, (by1 - by0) / 6)
            var tealButtons = 0
            var botTotal = 0
            y = by0
            while (y <= by1) {
                var x = bx0
                while (x <= bx1) {
                    val argb = frame.getArgb(x, y)
                    val r = (argb ushr 16) and 0xFF
                    val g = (argb ushr 8) and 0xFF
                    val b = argb and 0xFF
                    val maxC = max(r, max(g, b))
                    if (b > r + 15 && b >= 65 && maxC <= 185) tealButtons++
                    botTotal++
                    x += bStepX
                }
                y += bStepY
            }
            val tealRatio = if (botTotal > 0) tealButtons.toFloat() / botTotal else 0f

            val isFullScreenStageModal = tealRatio >= 0.08f || (
                upperDarkRatio >= 0.72f &&
                    upperVividRatio <= 0.06f &&
                    midDarkRatio >= 0.62f &&
                    midVividRatio <= 0.08f
                )

            return if (isFullScreenStageModal) {
                DarkModalKind.STAGE_INFO_MODAL
            } else {
                DarkModalKind.ENEMY_LONG_PRESS_POPUP
            }
        }
    }
}
