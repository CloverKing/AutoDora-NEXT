package com.autodora.next.touch

import com.autodora.next.model.*
import org.junit.Assert.*
import org.junit.Test

class PathInterpolatorTest {

    @Test
    fun `long 30-step path splits into seamless chained segments with exact endpoint continuity`() {
        val geom = BoardGeometry(leftPx = 10f, topPx = 1500f, rightPx = 1190f, bottomPx = 2483f, cols = 6, rows = 5)
        val moves = List(30) { idx -> if (idx % 2 == 0) Direction.RIGHT else Direction.LEFT }
        val waypoints = buildList {
            var r = 2
            var c = 2
            add(CellCoord(r, c))
            for (m in moves) {
                r += m.dr
                c += m.dc
                add(CellCoord(r, c))
            }
        }
        val sol = SolutionPath(
            start = CellCoord(2, 2),
            usedCharacterOrb = false,
            characterOrbCol = 0,
            moves = moves,
            waypoints = waypoints,
            stepCount = moves.size,
            firstWaveCombo = 7,
            totalCombo = 8,
            firstWaveOrbs = 21,
            totalOrbs = 24,
            allHardConstraintsMet = true,
            satisfiedFlags = 0,
            failedFlags = 0,
            score = 1000.0,
            tag = "Best",
            groups = emptyList()
        )

        val segs = PathInterpolator.buildSegments(sol, geom, SwipeConfig(maxStepsPerSegment = 10))
        assertTrue("30 steps should split into multiple chained segments", segs.size >= 3)

        for (i in 0 until segs.lastIndex) {
            assertTrue("Non-final segment $i must set willContinue=true", segs[i].willContinue)
            assertEquals(
                "Segment $i end must match segment ${i + 1} start for zero finger lift",
                segs[i].points.last(),
                segs[i + 1].points.first()
            )
        }
        assertFalse("Final segment must set willContinue=false", segs.last().willContinue)
    }

    @Test
    fun `character orb pull starts above row 0 and pulls strictly vertically into column`() {
        val geom = BoardGeometry(leftPx = 10f, topPx = 1500f, rightPx = 1190f, bottomPx = 2483f, cols = 6, rows = 5)
        val sol = SolutionPath(
            start = CellCoord(0, 2),
            usedCharacterOrb = true,
            characterOrbCol = 2,
            moves = listOf(Direction.DOWN, Direction.RIGHT),
            waypoints = listOf(CellCoord(0, 2), CellCoord(1, 2), CellCoord(1, 3)),
            stepCount = 2,
            firstWaveCombo = 3,
            totalCombo = 3,
            firstWaveOrbs = 9,
            totalOrbs = 9,
            allHardConstraintsMet = true,
            satisfiedFlags = 0,
            failedFlags = 0,
            score = 500.0,
            tag = "CharOrb",
            groups = emptyList()
        )

        val segs = PathInterpolator.buildSegments(sol, geom, SwipeConfig())
        assertTrue(segs.isNotEmpty())
        val p0 = segs.first().points.first()
        val p2 = segs.first().points[2]
        assertTrue("Origin Y must be above board top (portrait bar)", p0.y < geom.topPx)
        assertEquals("Character pull must be strictly vertical", p0.x, p2.x, 0.01f)
        assertEquals(geom.cellCenterY(0), p2.y, 0.01f)
    }

    @Test
    fun `inverted drag controls mirror X and Y deltas around start orb`() {
        val geom = BoardGeometry(leftPx = 0f, topPx = 1400f, rightPx = 1200f, bottomPx = 2400f, cols = 6, rows = 5)
        // Start at (2, 2), move RIGHT -> (2, 3) then DOWN -> (3, 3)
        val sol = SolutionPath(
            start = CellCoord(2, 2),
            usedCharacterOrb = false,
            characterOrbCol = 0,
            moves = listOf(Direction.RIGHT, Direction.DOWN),
            waypoints = listOf(CellCoord(2, 2), CellCoord(2, 3), CellCoord(3, 3)),
            stepCount = 2,
            firstWaveCombo = 3,
            totalCombo = 3,
            firstWaveOrbs = 9,
            totalOrbs = 9,
            allHardConstraintsMet = true,
            satisfiedFlags = 0,
            failedFlags = 0,
            score = 500.0,
            tag = "Inv",
            groups = emptyList()
        )
        val segs = PathInterpolator.buildSegments(
            sol,
            geom,
            SwipeConfig(invertX = true, invertY = true, stepDurationMs = SwipeConfig.TURBO_2S_STEP_MS)
        )
        assertTrue(segs.isNotEmpty())
        val pts = segs.first().points
        val x0 = geom.cellCenterX(2)
        val y0 = geom.cellCenterY(2)
        // After start hold (pts[0], pts[1]), logical RIGHT (+200) must map to physical LEFT (-200)!
        assertEquals(x0 - geom.cellWidth, pts[2].x, 0.5f)
        assertEquals(y0, pts[2].y, 0.5f)
        // Logical DOWN (+200) must map to physical UP (-200)!
        assertEquals(x0 - geom.cellWidth, pts[3].x, 0.5f)
        assertEquals(y0 - geom.cellHeight, pts[3].y, 0.5f)
    }

    @Test
    fun `buildContinuationSegments starts directly at live held finger without re-grabbing`() {
        val geom = BoardGeometry(leftPx = 0f, topPx = 1400f, rightPx = 1200f, bottomPx = 2400f, cols = 6, rows = 5)
        val sol = SolutionPath(
            start = CellCoord(2, 3),
            usedCharacterOrb = false,
            characterOrbCol = 0,
            moves = listOf(Direction.LEFT, Direction.UP),
            waypoints = listOf(CellCoord(2, 3), CellCoord(2, 2), CellCoord(1, 2)),
            stepCount = 2,
            firstWaveCombo = 4,
            totalCombo = 4,
            firstWaveOrbs = 12,
            totalOrbs = 12,
            allHardConstraintsMet = true,
            satisfiedFlags = 0,
            failedFlags = 0,
            score = 600.0,
            tag = "HoldReplan",
            groups = emptyList()
        )
        val heldPt = ScreenPoint(geom.cellCenterX(3) + 0.5f, geom.cellCenterY(2))
        val segs = PathInterpolator.buildContinuationSegments(sol, geom, SwipeConfig(), heldPt)
        assertTrue(segs.isNotEmpty())
        assertEquals(heldPt, segs.first().points.first())
        assertEquals(geom.cellCenterX(2), segs.first().points[1].x, 0.5f)
        assertFalse(segs.last().willContinue)
    }
}
