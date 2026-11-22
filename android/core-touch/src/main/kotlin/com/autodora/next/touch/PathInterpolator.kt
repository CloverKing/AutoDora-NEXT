package com.autodora.next.touch

import com.autodora.next.model.BoardGeometry
import com.autodora.next.model.SolutionPath
import kotlin.math.roundToLong
import kotlin.random.Random

data class ScreenPoint(val x: Float, val y: Float)

data class StrokeSegment(
    val points: List<ScreenPoint>,
    val durationMs: Long,
    val willContinue: Boolean
)

/**
 * Pure-math path interpolator converting discrete grid moves into smooth chained screen-pixel stroke segments.
 * Handles:
 *  - Initial grab hold (prevents missed orb pickup)
 *  - Character Orb strict vertical pull-down from team portrait bar
 *  - Clean 45° diagonal intersection crossing
 *  - Multi-segment continuous chaining (supports 40..120 step Weijian /排珠 paths without lifting finger!)
 *  - Final settle hold (prevents missed last swap)
 */
object PathInterpolator {

    fun buildSegments(
        solution: SolutionPath,
        geometry: BoardGeometry,
        config: SwipeConfig,
        rng: Random = Random.Default
    ): List<StrokeSegment> = buildSegmentsInternal(
        solution = solution,
        geometry = geometry,
        config = config,
        continuationOrigin = null,
        rng = rng
    )

    /**
     * Builds seamless continuation segments starting from an ALREADY-HELD finger position
     * (used by No-Root Hold-and-Replan mode after the board transforms mid-hold!).
     */
    fun buildContinuationSegments(
        solution: SolutionPath,
        geometry: BoardGeometry,
        config: SwipeConfig,
        currentFingerPoint: ScreenPoint,
        rng: Random = Random.Default
    ): List<StrokeSegment> = buildSegmentsInternal(
        solution = solution,
        geometry = geometry,
        config = config,
        continuationOrigin = currentFingerPoint,
        rng = rng
    )

    private fun buildSegmentsInternal(
        solution: SolutionPath,
        geometry: BoardGeometry,
        config: SwipeConfig,
        continuationOrigin: ScreenPoint?,
        rng: Random
    ): List<StrokeSegment> {
        if (solution.waypoints.isEmpty()) return emptyList()

        // Android's GestureDescription.StrokeDescription interpolates a Path at CONSTANT PIXEL VELOCITY
        // along total arc length (PathMeasure). Mixing a 0.5px grab/settle hold OR a 1.414L diagonal step
        // into the same Path as 1.0L orthogonal steps crushes holds to ~0.1ms and slows diagonals by 1.414x,
        // causing missed first/last swaps and diagonal corner slips (which mismatch actual vs solved combos!).
        // Solution: emit dedicated exact-duration segments for startHold, diagonal crossings, orthogonal runs, and endHold!
        val draftSegments = ArrayList<Pair<List<ScreenPoint>, Long>>()

        val firstCell = solution.waypoints.first()
        val firstCellPt = ScreenPoint(
            geometry.cellCenterX(firstCell.col),
            geometry.cellCenterY(firstCell.row)
        )
        val anchorPt = continuationOrigin ?: firstCellPt

        // Map logical target pixel to physical finger pixel (supports Inverted Drag Controls 颠倒操控!)
        fun mapInverted(pt: ScreenPoint): ScreenPoint {
            if (!config.invertX && !config.invertY) return pt
            val fx = if (config.invertX) (anchorPt.x - (pt.x - firstCellPt.x)).coerceAtLeast(4f) else pt.x
            val fy = if (config.invertY) (anchorPt.y - (pt.y - firstCellPt.y)).coerceAtLeast(4f) else pt.y
            return ScreenPoint(fx, fy)
        }

        // 1. Origin setup (continuation from held finger, Character Orb vertical pull, or exact start-grab hold)
        var prevPt: ScreenPoint = when {
            continuationOrigin != null -> continuationOrigin
            solution.usedCharacterOrb -> {
                val avatarPt = ScreenPoint(
                    geometry.cellCenterX(solution.characterOrbCol),
                    geometry.characterAvatarCenterY().coerceAtLeast(12f)
                )
                val grabEnd = ScreenPoint(avatarPt.x, avatarPt.y + 0.5f)
                draftSegments.add(listOf(avatarPt, grabEnd) to config.startHoldMs.coerceAtLeast(24L))
                val pullTarget = ScreenPoint(geometry.cellCenterX(solution.characterOrbCol), geometry.cellCenterY(0))
                val pullMs = (config.stepDurationMs * 1.2f).roundToLong().coerceAtLeast(36L)
                draftSegments.add(listOf(grabEnd, pullTarget) to pullMs)
                pullTarget
            }
            else -> {
                // Shift 0.5px strictly along the first move's direction so a diagonal first move
                // stays mathematically centered on the exact 4-cell diagonal intersection!
                val firstMove = solution.moves.firstOrNull()
                val dirDx = when {
                    firstMove == null -> 0.5f
                    firstMove.dc > 0 -> 0.5f
                    firstMove.dc < 0 -> -0.5f
                    else -> 0.5f
                }
                val dirDy = when {
                    firstMove == null -> 0.5f
                    firstMove.dr > 0 -> 0.5f
                    firstMove.dr < 0 -> -0.5f
                    else -> 0f
                }
                val grabEnd = ScreenPoint(firstCellPt.x + dirDx, firstCellPt.y + dirDy)
                draftSegments.add(listOf(firstCellPt, grabEnd) to config.startHoldMs.coerceAtLeast(24L))
                grabEnd
            }
        }

        // 2. Pack consecutive moves into smooth chained segments:
        //    - At brisk speeds (<= 46ms/step: Turbo 2s, Fast 3s, Hold-and-Replan), diagonal arc-length (1.414x)
        //      naturally lands in the crisp 34..64ms sweet spot, so packing orthogonal + diagonal together cuts
        //      Binder IPC segment handoffs from ~35 down to 2..3 (saving ~1.2-1.5s on real turns!).
        //    - At slower speeds (> 46ms/step), isolate 45° diagonals into dedicated brisk 22..52ms segments so
        //      slow steps never linger at 4-cell corners.
        val maxOrthoSteps = config.maxStepsPerSegment.coerceIn(6, 20)
        val packDiagonalsInline = config.stepDurationMs <= 46L
        val maxJitter = if (config.humanMode) (geometry.cellWidth * 0.03f).coerceAtMost(4f) else 0f

        var orthoPts = ArrayList<ScreenPoint>()
        var orthoDurMs = 0L

        fun flushOrtho() {
            if (orthoPts.size >= 2) {
                draftSegments.add(orthoPts to orthoDurMs.coerceAtLeast(16L))
            }
            orthoPts = ArrayList()
            orthoDurMs = 0L
        }

        for (i in solution.moves.indices) {
            val move = solution.moves[i]
            val targetCell = solution.waypoints[i + 1]
            val isLastMove = (i == solution.moves.lastIndex)

            val jx = if (!isLastMove && !move.isDiagonal && maxJitter > 0f) (rng.nextFloat() * 2f - 1f) * maxJitter else 0f
            val jy = if (!isLastMove && !move.isDiagonal && maxJitter > 0f) (rng.nextFloat() * 2f - 1f) * maxJitter else 0f
            val logicalPt = ScreenPoint(
                geometry.cellCenterX(targetCell.col) + jx,
                geometry.cellCenterY(targetCell.row) + jy
            )
            val targetPt = mapInverted(logicalPt)

            val speedVar = if (config.humanMode) (0.92f + rng.nextFloat() * 0.16f) else 1.0f

            if (move.isDiagonal && !packDiagonalsInline) {
                flushOrtho()
                // Brisk 45° diagonal crossing (22..52ms) minimizes samples lingering at the 4-cell corner boundary
                // so ToS cleanly performs the diagonal swap without orthogonal neighbor bounce!
                val diagMs = (config.stepDurationMs * config.diagonalSpeedFactor * speedVar)
                    .roundToLong()
                    .coerceIn(22L, 52L)
                val midPt = ScreenPoint((prevPt.x + targetPt.x) * 0.5f, (prevPt.y + targetPt.y) * 0.5f)
                draftSegments.add(listOf(prevPt, midPt, targetPt) to diagMs)
            } else {
                val arcScale = if (move.isDiagonal) 1.35f else 1.0f
                val stepMs = (config.stepDurationMs * arcScale * speedVar).roundToLong().coerceAtLeast(16L)
                if (orthoPts.isEmpty()) {
                    orthoPts.add(prevPt)
                }
                orthoPts.add(targetPt)
                orthoDurMs += stepMs
                if (orthoPts.size - 1 >= maxOrthoSteps) {
                    flushOrtho()
                }
            }
            prevPt = targetPt
        }
        flushOrtho()

        // 3. Dedicated final settle hold at destination orb before finger lift
        val settleEnd = ScreenPoint(prevPt.x + 0.5f, prevPt.y)
        draftSegments.add(listOf(prevPt, settleEnd) to config.endHoldMs.coerceAtLeast(24L))

        return draftSegments.mapIndexed { idx, (pts, dur) ->
            StrokeSegment(
                points = pts,
                durationMs = dur.coerceAtLeast(16L),
                willContinue = idx < draftSegments.lastIndex
            )
        }
    }
}
