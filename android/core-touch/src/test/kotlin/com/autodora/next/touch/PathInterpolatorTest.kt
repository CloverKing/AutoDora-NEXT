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
}
