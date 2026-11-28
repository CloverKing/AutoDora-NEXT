package com.autodora.next.vision

import com.autodora.next.model.BoardState
import com.autodora.next.model.HazardBits
import com.autodora.next.model.TileFlagBits

/**
 * Transparent summary of board-resident hazards and terrains detected by computer vision /
 * LiteRT CNN and automatically handled by the C++20 solver (no manual shield chip needed).
 */
data class BoardAutoPolicyItem(
    val code: String,
    val count: Int
)

object BoardAutoPolicySummary {

    fun analyze(board: BoardState): List<BoardAutoPolicyItem> {
        if (board.cells.isEmpty()) return emptyList()

        var burn = 0
        var scorch = 0
        var sticky = 0
        var purgatory = 0
        var bomb = 0
        var weathered = 0
        var electrified = 0
        var petrified = 0
        var frozen = 0
        var ice = 0
        var thorn = 0
        var priority = 0
        var reserve = 0
        var bloodDrain = 0
        var evil = 0
        var luminous = 0
        var blackHole = 0
        var bloodbath = 0
        var barrier = 0
        var shattered = 0
        var miasma = 0

        for (i in board.cells.indices) {
            val haz = board.cells[i].hazards
            val flags = board.tileFlags.getOrElse(i) { TileFlagBits.NONE }

            if ((flags and TileFlagBits.BURNING_TILE) != 0) burn++
            if ((flags and TileFlagBits.SCORCHING_TILE) != 0) scorch++
            if ((flags and TileFlagBits.STICKY_TILE) != 0) sticky++
            if ((flags and TileFlagBits.BLACK_HOLE) != 0) blackHole++
            if ((flags and TileFlagBits.END_FORBIDDEN) != 0) bloodbath++
            if ((flags and TileFlagBits.BARRIER_FIRST_WAVE_ONLY) != 0) barrier++
            if ((flags and TileFlagBits.SHATTERED) != 0) shattered++
            if ((flags and TileFlagBits.MIASMA) != 0) miasma++

            if ((haz and HazardBits.PURGATORY_FIRE) != 0) purgatory++
            if ((haz and HazardBits.BOMB) != 0) bomb++
            if ((haz and HazardBits.WEATHERED) != 0) weathered++
            if ((haz and HazardBits.ELECTRIFIED) != 0) electrified++
            if ((haz and HazardBits.PETRIFIED) != 0) petrified++
            if ((haz and HazardBits.FROZEN) != 0) frozen++
            if ((haz and HazardBits.ICE_SEALED) != 0) ice++
            if ((haz and HazardBits.THORN) != 0) thorn++
            if ((haz and HazardBits.PRIORITY) != 0) priority++
            if ((haz and HazardBits.RESERVE) != 0) reserve++
            if ((haz and HazardBits.BLOOD_DRAIN) != 0) bloodDrain++
            if ((haz and HazardBits.EVIL_ENERGY) != 0) evil++
            if ((haz and HazardBits.LUMINOUS) != 0) luminous++
        }

        val items = ArrayList<BoardAutoPolicyItem>(10)
        if (priority > 0) items.add(BoardAutoPolicyItem("priority", priority))
        if (reserve > 0) items.add(BoardAutoPolicyItem("reserve", reserve))
        if (purgatory > 0) items.add(BoardAutoPolicyItem("purgatory", purgatory))
        if (sticky > 0) items.add(BoardAutoPolicyItem("sticky", sticky))
        if (blackHole > 0) items.add(BoardAutoPolicyItem("black_hole", blackHole))
        if (bloodbath > 0) items.add(BoardAutoPolicyItem("bloodbath", bloodbath))
        if (burn > 0) items.add(BoardAutoPolicyItem("burn", burn))
        if (scorch > 0) items.add(BoardAutoPolicyItem("scorch", scorch))
        if (miasma > 0) items.add(BoardAutoPolicyItem("miasma", miasma))
        if (bomb > 0) items.add(BoardAutoPolicyItem("bomb", bomb))
        if (weathered > 0) items.add(BoardAutoPolicyItem("weathered", weathered))
        if (thorn > 0) items.add(BoardAutoPolicyItem("thorn", thorn))
        if (electrified > 0) items.add(BoardAutoPolicyItem("electrified", electrified))
        if (petrified > 0) items.add(BoardAutoPolicyItem("petrified", petrified))
        if (frozen > 0) items.add(BoardAutoPolicyItem("frozen", frozen))
        if (ice > 0) items.add(BoardAutoPolicyItem("ice", ice))
        if (bloodDrain > 0) items.add(BoardAutoPolicyItem("blood_drain", bloodDrain))
        if (evil > 0) items.add(BoardAutoPolicyItem("evil", evil))
        if (luminous > 0) items.add(BoardAutoPolicyItem("luminous", luminous))
        if (barrier > 0) items.add(BoardAutoPolicyItem("barrier", barrier))
        if (shattered > 0) items.add(BoardAutoPolicyItem("shattered", shattered))
        return items
    }
}
