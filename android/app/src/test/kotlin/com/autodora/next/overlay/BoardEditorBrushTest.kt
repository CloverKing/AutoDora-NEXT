package com.autodora.next.overlay

import com.autodora.next.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BoardEditorBrushTest {

    private fun empty6x5Board(): BoardState = BoardState(
        cols = 6,
        rows = 5,
        cells = List(30) { CellState(Element.WATER) },
        tileFlags = List(30) { TileFlagBits.NONE }
    )

    @Test
    fun startAndEndVortexBrushesAreStrictlySinglePointAndMutuallyExclusive() {
        val startBrush = BoardBrush.ToggleExclusiveTileFlag(
            flag = TileFlagBits.START_ALLOWED,
            conflictFlag = TileFlagBits.END_REQUIRED,
            textRes = 0,
            fallback = "绿色漩涡"
        )
        val endBrush = BoardBrush.ToggleExclusiveTileFlag(
            flag = TileFlagBits.END_REQUIRED,
            conflictFlag = TileFlagBits.START_ALLOWED,
            textRes = 0,
            fallback = "红色漩涡"
        )

        var b = empty6x5Board()
        // Paint start at (0,0), then (2,3), then (4,5) -> strictly 1 start cell at (4,5)!
        b = OverlayBoardEditorController.applyBrushToBoard(b, startBrush, 0, 0)
        b = OverlayBoardEditorController.applyBrushToBoard(b, startBrush, 2, 3)
        b = OverlayBoardEditorController.applyBrushToBoard(b, startBrush, 4, 5)
        assertEquals(1, b.tileFlags.count { (it and TileFlagBits.START_ALLOWED) != 0 })
        assertNotEquals(0, b.tileFlags[4 * 6 + 5] and TileFlagBits.START_ALLOWED)

        // Paint end on the SAME cell (4,5) -> clears start on (4,5) and sets strictly 1 end cell!
        b = OverlayBoardEditorController.applyBrushToBoard(b, endBrush, 4, 5)
        assertEquals(0, b.tileFlags.count { (it and TileFlagBits.START_ALLOWED) != 0 })
        assertEquals(1, b.tileFlags.count { (it and TileFlagBits.END_REQUIRED) != 0 })

        // Move end to (1,1) -> old end cleared, only (1,1) marked
        b = OverlayBoardEditorController.applyBrushToBoard(b, endBrush, 1, 1)
        assertEquals(1, b.tileFlags.count { (it and TileFlagBits.END_REQUIRED) != 0 })
        assertNotEquals(0, b.tileFlags[1 * 6 + 1] and TileFlagBits.END_REQUIRED)

        // Tap (1,1) again -> toggles OFF cleanly
        b = OverlayBoardEditorController.applyBrushToBoard(b, endBrush, 1, 1)
        assertEquals(0, b.tileFlags.count { (it and TileFlagBits.END_REQUIRED) != 0 })
    }

    @Test
    fun charOrbPassAndEndTerrainsAllowMultiCellMarking() {
        val passBrush = BoardBrush.ToggleTileFlag(TileFlagBits.CHAR_ORB_PASS, "角色通行")
        val endBrush = BoardBrush.ToggleTileFlag(TileFlagBits.CHAR_ORB_END, "角色停放")

        var b = empty6x5Board()
        // Encyclopedia #78: entire row 1 (6 cells) can be Char-Orb Pass terrain
        for (c in 0 until 6) {
            b = OverlayBoardEditorController.applyBrushToBoard(b, passBrush, 1, c)
        }
        assertEquals(6, b.tileFlags.count { (it and TileFlagBits.CHAR_ORB_PASS) != 0 })

        // Encyclopedia #54: multiple yellow target cells (e.g. (0,0) and (0,5)) for Char-Orb End
        b = OverlayBoardEditorController.applyBrushToBoard(b, endBrush, 0, 0)
        b = OverlayBoardEditorController.applyBrushToBoard(b, endBrush, 0, 5)
        assertEquals(2, b.tileFlags.count { (it and TileFlagBits.CHAR_ORB_END) != 0 })
    }

    @Test
    fun specificRaceBrushSwitchesAndClearsCleanly() {
        val godBrush = BoardBrush.ToggleRaceType(RaceType.GOD, 0, "神族")
        val demonBrush = BoardBrush.ToggleRaceType(RaceType.DEMON, 0, "魔族")

        var b = empty6x5Board()
        b = OverlayBoardEditorController.applyBrushToBoard(b, godBrush, 0, 0)
        assertEquals(Variant.RACE, b.cells[0].variant)
        assertEquals(RaceType.GOD, b.cells[0].race)

        // Switching to Demon on same cell replaces race directly
        b = OverlayBoardEditorController.applyBrushToBoard(b, demonBrush, 0, 0)
        assertEquals(Variant.RACE, b.cells[0].variant)
        assertEquals(RaceType.DEMON, b.cells[0].race)

        // Tapping same race toggles back to Normal/NONE
        b = OverlayBoardEditorController.applyBrushToBoard(b, demonBrush, 0, 0)
        assertEquals(Variant.NORMAL, b.cells[0].variant)
        assertEquals(RaceType.NONE, b.cells[0].race)

        // ClearMarkers resets variant, race, hazards, and tileFlags all at once
        b = OverlayBoardEditorController.applyBrushToBoard(b, godBrush, 0, 0)
        b = OverlayBoardEditorController.applyBrushToBoard(b, BoardBrush.ToggleHazard(HazardBits.BLOOD_DRAIN, "化血"), 0, 0)
        b = OverlayBoardEditorController.applyBrushToBoard(b, BoardBrush.ToggleTileFlag(TileFlagBits.PUZZLE_1, "拼图"), 0, 0)
        b = OverlayBoardEditorController.applyBrushToBoard(b, BoardBrush.ClearMarkers, 0, 0)
        assertEquals(Variant.NORMAL, b.cells[0].variant)
        assertEquals(RaceType.NONE, b.cells[0].race)
        assertEquals(HazardBits.NONE, b.cells[0].hazards)
        assertEquals(TileFlagBits.NONE, b.tileFlags[0])
    }

    @Test
    fun startingBuoyBrushSetsPortraitColumnMutuallyExclusiveWithGreenVortexAndClearsCleanly() {
        val startVortex = BoardBrush.ToggleExclusiveTileFlag(
            flag = TileFlagBits.START_ALLOWED,
            conflictFlag = TileFlagBits.END_REQUIRED,
            textRes = 0,
            fallback = "绿色漩涡"
        )
        val teamAttrs = listOf(Element.WATER, Element.WOOD, Element.DARK, Element.LIGHT, Element.FIRE, Element.WOOD)

        var b = empty6x5Board()
        // Mark board-cell green vortex at (2, 1) first
        b = OverlayBoardEditorController.applyBrushToBoard(b, startVortex, 2, 1)
        assertEquals(1, b.tileFlags.count { (it and TileFlagBits.START_ALLOWED) != 0 })

        // Paint Starting Buoy on col 5 (even via portrait row -1!) -> clears board-cell green vortex and sets col 5 WOOD
        b = OverlayBoardEditorController.applyBrushToBoard(b, BoardBrush.SetStartingBuoy, -1, 5, teamAttrs)
        assertEquals(0, b.tileFlags.count { (it and TileFlagBits.START_ALLOWED) != 0 })
        assertEquals(5, b.startingBuoyCol)
        assertEquals(Element.WOOD, b.startingBuoyElement)

        // Tap col 5 again -> toggles OFF cleanly
        b = OverlayBoardEditorController.applyBrushToBoard(b, BoardBrush.SetStartingBuoy, 0, 5, teamAttrs)
        assertEquals(-1, b.startingBuoyCol)
        assertEquals(Element.UNKNOWN, b.startingBuoyElement)

        // Set Starting Buoy on col 2 (DARK), then setting green vortex on (1, 1) overrides portrait buoy cleanly!
        b = OverlayBoardEditorController.applyBrushToBoard(b, BoardBrush.SetStartingBuoy, 3, 2, teamAttrs)
        assertEquals(2, b.startingBuoyCol)
        assertEquals(Element.DARK, b.startingBuoyElement)
        b = OverlayBoardEditorController.applyBrushToBoard(b, startVortex, 1, 1)
        assertEquals(-1, b.startingBuoyCol)
        assertEquals(1, b.tileFlags.count { (it and TileFlagBits.START_ALLOWED) != 0 })

        // Painting Starting Buoy on a Heart-only column without portrait detection never invents an impossible Heart portrait!
        val heartBoard = BoardState(
            cols = 6,
            rows = 5,
            cells = List(30) { CellState(Element.HEART) },
            tileFlags = List(30) { TileFlagBits.NONE }
        )
        val heartBuoy = OverlayBoardEditorController.applyBrushToBoard(heartBoard, BoardBrush.SetStartingBuoy, 0, 5)
        assertEquals(5, heartBuoy.startingBuoyCol)
        assertEquals(Element.UNKNOWN, heartBuoy.startingBuoyElement)
    }

    @Test
    fun customOpsPriorityReserveCheckpointsClearAndCrossTurnMergeWorkCleanly() {
        val prioBrush = BoardBrush.ToggleExclusiveHazard(HazardBits.PRIORITY, HazardBits.RESERVE, 0, "必消此珠")
        val resvBrush = BoardBrush.ToggleExclusiveHazard(HazardBits.RESERVE, HazardBits.PRIORITY, 0, "保留不消")

        var b = empty6x5Board()
        // Add official WEATHERED hazard on cell (0,0) and custom PRIORITY on (0,0)
        b = OverlayBoardEditorController.applyBrushToBoard(b, BoardBrush.ToggleHazard(HazardBits.WEATHERED, "风化"), 0, 0)
        b = OverlayBoardEditorController.applyBrushToBoard(b, prioBrush, 0, 0)
        assertNotEquals(0, b.cells[0].hazards and HazardBits.PRIORITY)
        assertNotEquals(0, b.cells[0].hazards and HazardBits.WEATHERED)

        // Switching same cell to RESERVE clears PRIORITY while keeping WEATHERED intact
        b = OverlayBoardEditorController.applyBrushToBoard(b, resvBrush, 0, 0)
        assertEquals(0, b.cells[0].hazards and HazardBits.PRIORITY)
        assertNotEquals(0, b.cells[0].hazards and HazardBits.RESERVE)
        assertNotEquals(0, b.cells[0].hazards and HazardBits.WEATHERED)

        // Paint 5 checkpoints -> FIFO caps at 4 ordered checkpoints and evicts the 1st!
        b = OverlayBoardEditorController.applyBrushToBoard(b, BoardBrush.ToggleCheckpoint, 0, 1) // idx 1 (evicted on 5th)
        b = OverlayBoardEditorController.applyBrushToBoard(b, BoardBrush.ToggleCheckpoint, 0, 2) // idx 2 -> ①
        b = OverlayBoardEditorController.applyBrushToBoard(b, BoardBrush.ToggleCheckpoint, 0, 3) // idx 3 -> ②
        b = OverlayBoardEditorController.applyBrushToBoard(b, BoardBrush.ToggleCheckpoint, 0, 4) // idx 4 -> ③
        b = OverlayBoardEditorController.applyBrushToBoard(b, BoardBrush.ToggleCheckpoint, 0, 5) // idx 5 -> ④
        assertEquals(listOf(2, 3, 4, 5), b.orderedCheckpoints)
        assertEquals(0, b.tileFlags[1] and TileFlagBits.CHECKPOINT)
        assertNotEquals(0, b.tileFlags[2] and TileFlagBits.CHECKPOINT)

        // Cross-turn merge onto a fresh recognized board preserves fresh orb colors while carrying over custom marks
        val freshBoard = BoardState(
            cols = 6,
            rows = 5,
            cells = List(30) { CellState(Element.DARK) },
            tileFlags = List(30) { TileFlagBits.NONE }
        )
        val merged = OverlayBoardEditorController.mergeLockedCustomOpsOntoBoard(b, freshBoard)
        assertEquals(Element.DARK, merged.cells[0].element)
        assertNotEquals(0, merged.cells[0].hazards and HazardBits.RESERVE)
        assertEquals("Official WEATHERED must not leak across turns as a custom op", 0, merged.cells[0].hazards and HazardBits.WEATHERED)
        assertEquals(listOf(2, 3, 4, 5), merged.orderedCheckpoints)

        // clearCustomOpsFromBoard strips custom ops while keeping official WEATHERED & orb colors
        val cleaned = OverlayBoardEditorController.clearCustomOpsFromBoard(b)
        assertEquals(0, cleaned.cells[0].hazards and HazardBits.RESERVE)
        assertNotEquals(0, cleaned.cells[0].hazards and HazardBits.WEATHERED)
        assertEquals(emptyList<Int>(), cleaned.orderedCheckpoints)
    }
}
