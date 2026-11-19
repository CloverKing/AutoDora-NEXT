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

        val rawPoints = ArrayList<Pair<ScreenPoint, Long>>()

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

        // 1. Origin setup (continuation from live held finger, Character Orb vertical pull, or fresh orb grab)
        val originPt = when {
            continuationOrigin != null -> {
                rawPoints.add(continuationOrigin to 0L)
                continuationOrigin
            }
            solution.usedCharacterOrb -> {
                val avatarPt = ScreenPoint(
                    geometry.cellCenterX(solution.characterOrbCol),
                    geometry.characterAvatarCenterY().coerceAtLeast(12f)
                )
                rawPoints.add(avatarPt to 0L)
                rawPoints.add(ScreenPoint(avatarPt.x, avatarPt.y + 1.0f) to config.startHoldMs.coerceAtLeast(16L))
                val pullTarget = ScreenPoint(geometry.cellCenterX(solution.characterOrbCol), geometry.cellCenterY(0))
                rawPoints.add(pullTarget to (config.stepDurationMs * 1.2f).roundToLong().coerceAtLeast(30L))
                pullTarget
            }
            else -> {
                rawPoints.add(firstCellPt to 0L)
                val grabMs = config.startHoldMs.coerceAtLeast(16L)
                rawPoints.add(ScreenPoint(firstCellPt.x + 0.5f, firstCellPt.y + 0.5f) to grabMs)
                firstCellPt
            }
        }

        // 2. Append every grid step with diagonal speed optimization, optional inverted mirror & micro-jitter
        var prevPt = originPt
        val maxJitter = if (config.humanMode) (geometry.cellWidth * 0.035f).coerceAtMost(5f) else 0f

        for (i in solution.moves.indices) {
            val move = solution.moves[i]
            val targetCell = solution.waypoints[i + 1]
            val isLastMove = (i == solution.moves.lastIndex)

            val jx = if (!isLastMove && maxJitter > 0f) (rng.nextFloat() * 2f - 1f) * maxJitter else 0f
            val jy = if (!isLastMove && maxJitter > 0f) (rng.nextFloat() * 2f - 1f) * maxJitter else 0f
            val logicalPt = ScreenPoint(
                geometry.cellCenterX(targetCell.col) + jx,
                geometry.cellCenterY(targetCell.row) + jy
            )
            val targetPt = mapInverted(logicalPt)

            val speedVar = if (config.humanMode) (0.90f + rng.nextFloat() * 0.20f) else 1.0f
            val stepMs = if (move.isDiagonal) {
                (config.stepDurationMs * config.diagonalSpeedFactor * speedVar).roundToLong().coerceAtLeast(16L)
            } else {
                (config.stepDurationMs * speedVar).roundToLong().coerceAtLeast(16L)
            }

            if (move.isDiagonal) {
                // Keep exact midpoint on 45° line so ToS crosses 4-cell corner cleanly without orthogonal bounce
                val midPt = ScreenPoint((prevPt.x + targetPt.x) * 0.5f, (prevPt.y + targetPt.y) * 0.5f)
                rawPoints.add(midPt to (stepMs / 2).coerceAtLeast(8L))
                rawPoints.add(targetPt to (stepMs - stepMs / 2).coerceAtLeast(8L))
            } else {
                rawPoints.add(targetPt to stepMs)
            }
            prevPt = targetPt
        }

        // 3. Final settle hold at destination cell before finger lift
        rawPoints.add(
            ScreenPoint(prevPt.x + 0.5f, prevPt.y) to config.endHoldMs.coerceAtLeast(16L)
        )

        // 4. Chunk into seamless chained StrokeSegments (each segment starts exactly where previous ended!)
        val maxPtsPerSeg = (config.maxStepsPerSegment.coerceIn(6, 24)) + 2
        val segments = ArrayList<StrokeSegment>()
        var cursor = 0

        while (cursor < rawPoints.size - 1) {
            val endIdx = minOf(cursor + maxPtsPerSeg - 1, rawPoints.lastIndex)
            val segPts = ArrayList<ScreenPoint>(endIdx - cursor + 1)
            var segDur = 0L
            segPts.add(rawPoints[cursor].first)
            for (k in (cursor + 1)..endIdx) {
                segPts.add(rawPoints[k].first)
                segDur += rawPoints[k].second
            }
            val isFinal = (endIdx == rawPoints.lastIndex)
            segments.add(
                StrokeSegment(
                    points = segPts,
                    durationMs = segDur.coerceAtLeast(12L),
                    willContinue = !isFinal
                )
            )
            cursor = endIdx
        }

        return segments
    }
}
