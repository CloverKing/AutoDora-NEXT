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
        assertTrue(segs.size >= 4)
        // Seg 0 = start hold above portrait bar, Seg 1 = strict vertical pull into row 0
        val p0 = segs[0].points.first()
        val pPullEnd = segs[1].points.last()
        assertTrue("Origin Y must be above board top (portrait bar)", p0.y < geom.topPx)
        assertEquals("Character pull must be strictly vertical", p0.x, pPullEnd.x, 0.01f)
        assertEquals(geom.cellCenterY(0), pPullEnd.y, 0.01f)
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
        assertEquals("Should emit startHold + orthoMove + endHold", 3, segs.size)
        val pts = segs[1].points
        val x0 = geom.cellCenterX(2)
        val y0 = geom.cellCenterY(2)
        // Seg 0 is dedicated startHold; Seg 1 is orthogonal moves: logical RIGHT (+200) maps to physical LEFT (-200)!
        assertEquals(x0 - geom.cellWidth, pts[1].x, 0.5f)
        assertEquals(y0, pts[1].y, 0.5f)
        // Logical DOWN (+200) must map to physical UP (-200)!
        assertEquals(x0 - geom.cellWidth, pts[2].x, 0.5f)
        assertEquals(y0 - geom.cellHeight, pts[2].y, 0.5f)
    }

    @Test
    fun `isolates startHold, diagonal crossings, and endHold so GestureDescription never crushes timing`() {
        val geom = BoardGeometry(leftPx = 0f, topPx = 1400f, rightPx = 1200f, bottomPx = 2400f, cols = 6, rows = 5)
        val sol = SolutionPath(
            start = CellCoord(1, 1),
            usedCharacterOrb = false,
            characterOrbCol = 0,
            moves = listOf(Direction.RIGHT, Direction.DOWN_RIGHT, Direction.DOWN),
            waypoints = listOf(CellCoord(1, 1), CellCoord(1, 2), CellCoord(2, 3), CellCoord(3, 3)),
            stepCount = 3,
            firstWaveCombo = 5,
            totalCombo = 5,
            firstWaveOrbs = 15,
            totalOrbs = 15,
            allHardConstraintsMet = true,
            satisfiedFlags = 0,
            failedFlags = 0,
            score = 800.0,
            tag = "DiagIso",
            groups = emptyList()
        )
        val cfg = SwipeConfig(stepDurationMs = 60L, diagonalSpeedFactor = 0.85f, startHoldMs = 55L, endHoldMs = 45L)
        val segs = PathInterpolator.buildSegments(sol, geom, cfg)

        // Expected 5 segments: startHold(55ms) -> RIGHT(60ms) -> DOWN_RIGHT(51ms) -> DOWN(60ms) -> endHold(45ms)
        assertEquals(5, segs.size)
        assertEquals(55L, segs[0].durationMs)
        assertEquals(60L, segs[1].durationMs)
        assertEquals(51L, segs[2].durationMs)
        assertEquals(60L, segs[3].durationMs)
        assertEquals(45L, segs[4].durationMs)
        for (i in 0 until segs.lastIndex) {
            assertTrue(segs[i].willContinue)
            assertEquals(segs[i].points.last(), segs[i + 1].points.first())
        }
        assertFalse(segs.last().willContinue)
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

    @Test
    fun `brisk Hold-and-Replan speed packs orthogonal and diagonal steps into minimal chained segments`() {
        val geom = BoardGeometry(leftPx = 0f, topPx = 1268f, rightPx = 1080f, bottomPx = 2168f, cols = 6, rows = 5)
        val moves = List(26) { i ->
            when (i % 4) {
                0 -> Direction.RIGHT
                1 -> Direction.DOWN_LEFT
                2 -> Direction.UP_RIGHT
                else -> Direction.LEFT
            }
        }
        val waypoints = buildList {
            var r = 2
            var c = 2
            add(CellCoord(r, c))
            for (m in moves) {
                r = (r + m.dr).coerceIn(0, 4)
                c = (c + m.dc).coerceIn(0, 5)
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
            tag = "FastHold",
            groups = emptyList()
        )
        val heldPt = ScreenPoint(geom.cellCenterX(2) + 0.5f, geom.cellCenterY(2))
        val fastCfg = SwipeConfig(stepDurationMs = 38L, endHoldMs = 28L, maxStepsPerSegment = 18)
        val segs = PathInterpolator.buildContinuationSegments(sol, geom, fastCfg, heldPt)
        // 26 mixed ortho+diag steps at maxStepsPerSegment=18 => only 2 motion segments + 1 endHold = 3 segments total!
        assertEquals("Brisk continuation must pack into 3 segments (2 motion + 1 settle)", 3, segs.size)
        for (i in 0 until segs.lastIndex) {
            assertTrue(segs[i].willContinue)
            assertEquals(segs[i].points.last(), segs[i + 1].points.first())
        }
        assertFalse(segs.last().willContinue)
    }

    @Test
    fun `isolates first orthogonal step before immediate 90-degree turn to prevent Unity wake-up diagonal slip`() {
        val geom = BoardGeometry(leftPx = 0f, topPx = 1268f, rightPx = 1080f, bottomPx = 2168f, cols = 6, rows = 5)
        // Step 1: (4,0)->(4,1) RIGHT, Step 2: (4,1)->(3,1) UP (immediate 90-degree corner!)
        val sol = SolutionPath(
            start = CellCoord(4, 0),
            usedCharacterOrb = false,
            characterOrbCol = 0,
            moves = listOf(Direction.RIGHT, Direction.UP, Direction.LEFT),
            waypoints = listOf(CellCoord(4, 0), CellCoord(4, 1), CellCoord(3, 1), CellCoord(3, 0)),
            stepCount = 3,
            firstWaveCombo = 5,
            totalCombo = 7,
            firstWaveOrbs = 15,
            totalOrbs = 21,
            allHardConstraintsMet = true,
            satisfiedFlags = 0,
            failedFlags = 0,
            score = 1000.0,
            tag = "CornerGuard",
            groups = emptyList()
        )
        val segs = PathInterpolator.buildSegments(sol, geom, SwipeConfig(stepDurationMs = SwipeSpeedPreset.STANDARD.stepDurationMs))
        // Expected: startHold -> isolated Step 1 (4,0)->(4,1) -> remaining ortho (4,1)->(3,1)->(3,0) -> endHold
        assertEquals(4, segs.size)
        assertEquals(2, segs[1].points.size)
        assertTrue("Isolated Step 1 duration must be >= 58ms", segs[1].durationMs >= 58L)
        assertEquals(geom.cellCenterX(1), segs[1].points.last().x, 0.5f)
        assertEquals(geom.cellCenterY(4), segs[1].points.last().y, 0.5f)
        for (i in 0 until segs.lastIndex) {
            assertTrue(segs[i].willContinue)
            assertEquals(segs[i].points.last(), segs[i + 1].points.first())
        }
        assertFalse(segs.last().willContinue)
    }

    @Test
    fun `six-tier swipe speed presets span 2s to 7s with strictly increasing step durations`() {
        val entries = SwipeSpeedPreset.entries
        assertEquals("Expected 6 swipe speed tiers (2s..7s)", 6, entries.size)
        for (i in 1 until entries.size) {
            assertTrue(
                "${entries[i]} (${entries[i].stepDurationMs}ms) must be slower than ${entries[i - 1]} (${entries[i - 1].stepDurationMs}ms)",
                entries[i].stepDurationMs > entries[i - 1].stepDurationMs
            )
        }
        for (preset in entries) {
            assertEquals(preset, SwipeSpeedPreset.fromStepDurationMs(preset.stepDurationMs))
        }
    }

    @Test
    fun `estimateSwipeDurationMs accurately reflects path length, speed tier, diagonals, and character orb pull`() {
        val geom = BoardGeometry(leftPx = 0f, topPx = 1400f, rightPx = 1200f, bottomPx = 2400f, cols = 6, rows = 5)
        val moves = List(20) { i -> if (i % 2 == 0) Direction.RIGHT else Direction.LEFT }
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
            firstWaveCombo = 6,
            totalCombo = 6,
            firstWaveOrbs = 18,
            totalOrbs = 18,
            allHardConstraintsMet = true,
            satisfiedFlags = 0,
            failedFlags = 0,
            score = 800.0,
            tag = "Dur",
            groups = emptyList()
        )

        val turboMs = PathInterpolator.estimateSwipeDurationMs(sol, geom, SwipeConfig(stepDurationMs = SwipeSpeedPreset.TURBO_2S.stepDurationMs))
        val standardMs = PathInterpolator.estimateSwipeDurationMs(sol, geom, SwipeConfig(stepDurationMs = SwipeSpeedPreset.STANDARD.stepDurationMs))
        val steadyMs = PathInterpolator.estimateSwipeDurationMs(sol, geom, SwipeConfig(stepDurationMs = SwipeSpeedPreset.STEADY.stepDurationMs))
        assertTrue("Turbo ($turboMs ms) must be faster than Standard ($standardMs ms)", turboMs < standardMs)
        assertTrue("Standard ($standardMs ms) must be faster than Steady ($steadyMs ms)", standardMs < steadyMs)
        // Deterministic even when humanMode=true
        val stdHumanMs = PathInterpolator.estimateSwipeDurationMs(sol, geom, SwipeConfig(stepDurationMs = SwipeSpeedPreset.STANDARD.stepDurationMs, humanMode = true))
        assertEquals("Duration estimate must be deterministic regardless of humanMode jitter", standardMs, stdHumanMs)

        // Character orb pull adds extra vertical pull-down duration
        val charSol = sol.copy(usedCharacterOrb = true, characterOrbCol = 2)
        val charMs = PathInterpolator.estimateSwipeDurationMs(charSol, geom, SwipeConfig(stepDurationMs = SwipeSpeedPreset.STANDARD.stepDurationMs))
        assertTrue("Character orb pull ($charMs ms) must exceed normal start ($standardMs ms)", charMs > standardMs)

        // Verify Starting Buoy / Character Orb pull starts at portrait center, pulls strictly vertically through topPx to (0, col),
        // and maintains firm grab >= 45ms + pull >= 72ms even at Turbo 24ms speed!
        val buoySol = sol.copy(start = CellCoord(0, 5), waypoints = listOf(CellCoord(0, 5), CellCoord(1, 5)), moves = listOf(Direction.DOWN), usedCharacterOrb = true, characterOrbCol = 5)
        val turboSegs = PathInterpolator.buildSegments(buoySol, geom, SwipeConfig(stepDurationMs = SwipeSpeedPreset.TURBO_2S.stepDurationMs, startHoldMs = 24L))
        assertTrue("Must emit grab + vertical pull + move + settle", turboSegs.size >= 4)
        assertTrue("Portrait grab hold (${turboSegs[0].durationMs}ms) must be >= 45ms", turboSegs[0].durationMs >= 45L)
        assertTrue("Vertical pull duration (${turboSegs[1].durationMs}ms) must be >= 72ms", turboSegs[1].durationMs >= 72L)
        val expectedX = geom.cellCenterX(5)
        assertEquals("Grab starts strictly at portrait center Y", geom.characterAvatarCenterY(), turboSegs[0].points.first().y, 0.5f)
        assertTrue("Pull segment must stay strictly vertical at col 5 X", turboSegs[1].points.all { kotlin.math.abs(it.x - expectedX) < 0.01f })
        assertEquals("Pull passes through board topPx", geom.topPx, turboSegs[1].points[1].y, 0.5f)
        assertEquals("Pull lands at cell (0,5) center Y", geom.cellCenterY(0), turboSegs[1].points.last().y, 0.5f)
    }
}
