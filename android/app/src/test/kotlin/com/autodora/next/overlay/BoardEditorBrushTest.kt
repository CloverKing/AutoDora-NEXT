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
}
