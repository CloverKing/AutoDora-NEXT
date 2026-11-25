package com.autodora.next.model

import kotlinx.serialization.Serializable

@Serializable
enum class Element(val code: Int, val symbol: Char) {
    WATER(0, 'W'),
    FIRE(1, 'F'),
    WOOD(2, 'G'),
    LIGHT(3, 'L'),
    DARK(4, 'D'),
    HEART(5, 'H'),
    HIDDEN(6, '?'),
    UNKNOWN(7, 'U'),
    EMPTY(8, '.');

    val isMatchable: Boolean get() = code in 0..5
    val bitMask: Int get() = if (isMatchable) (1 shl code) else 0

    companion object {
        val MATCHABLE = listOf(WATER, FIRE, WOOD, LIGHT, DARK, HEART)
        fun fromCode(code: Int): Element = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

@Serializable
enum class Variant(val code: Int) {
    NORMAL(0),
    ENCHANTED(1),
    RACE(2),
    WEAKENED(3);

    companion object {
        fun fromCode(code: Int): Variant = entries.firstOrNull { it.code == code } ?: NORMAL
    }
}

/**
 * Official 7 Tower of Saviors Race Runestone Types (神魔之塔 7 大种族符石).
 */
@Serializable
enum class RaceType(val code: Int, val bitMask: Int) {
    NONE(0, 0),
    GOD(1, 1 shl 0),      // 神族
    DEMON(2, 1 shl 1),    // 魔族
    HUMAN(3, 1 shl 2),    // 人类
    BEAST(4, 1 shl 3),    // 兽类
    DRAGON(5, 1 shl 4),   // 龙类
    ELF(6, 1 shl 5),      // 妖精
    MACHINA(7, 1 shl 6);  // 机械族

    companion object {
        val ALL_RACES = listOf(GOD, DEMON, HUMAN, BEAST, DRAGON, ELF, MACHINA)
        fun fromCode(code: Int): RaceType = entries.firstOrNull { it.code == code } ?: NONE
    }
}

object HazardBits {
    const val NONE = 0
    const val WEATHERED = 1 shl 0
    const val ELECTRIFIED = 1 shl 1
    const val BURNING_ORB = 1 shl 2
    const val STICKY_ORB = 1 shl 3
    const val FROZEN = 1 shl 4
    const val ICE_SEALED = 1 shl 5
    const val LOCKED = 1 shl 6
    const val PETRIFIED = 1 shl 7
    const val BLOOD_DRAIN = 1 shl 8
    const val BOMB = 1 shl 9
    const val BLACK_WHITE = 1 shl 10
    const val QUESTION = 1 shl 11
    const val PRIORITY = 1 shl 12
    const val RESERVE = 1 shl 13
    const val FOAM = 1 shl 14
    const val PURGATORY_FIRE = 1 shl 15
    const val LIGHT_PRISON = 1 shl 16
    const val CORRODED = 1 shl 17
    const val LOCKED_HURT = 1 shl 18
    const val LOCKED_ATK_DOWN = 1 shl 19
    const val WITHERED_VINE = 1 shl 20
    const val SLEEP = 1 shl 21
    const val LUMINOUS = 1 shl 22
    const val COAGULATED = 1 shl 23
    const val EVIL_ENERGY = 1 shl 24
    const val MAGIC_ORB = 1 shl 25
    const val BLOOD_THORN = 1 shl 26
    const val THORN = 1 shl 27
}

object TileFlagBits {
    const val NONE = 0
    const val PUZZLE_1 = 1 shl 0
    const val PUZZLE_2 = 1 shl 1
    const val START_ALLOWED = 1 shl 2
    const val END_REQUIRED = 1 shl 3
    const val CHECKPOINT = 1 shl 4
    const val STORM = 1 shl 5
    const val SHATTERED = 1 shl 6
    const val BURNING_TILE = 1 shl 7
    const val STICKY_TILE = 1 shl 8
    const val BLACK_HOLE = 1 shl 9
    const val NO_PASS = 1 shl 10
    const val VOID_TILE = 1 shl 11
    const val BARRIER_FIRST_WAVE_ONLY = 1 shl 12
    const val SEALED_STONE = 1 shl 13
    const val METAMORPHOSIS = 1 shl 14
    const val TRAIL_CORROSION = 1 shl 15
    const val MIASMA = 1 shl 16
    const val CHAR_ORB_PASS = 1 shl 17
    const val CHAR_ORB_END = 1 shl 18
    const val SCORCHING_TILE = 1 shl 19
    const val END_FORBIDDEN = 1 shl 20
}

@Serializable
enum class Direction(val code: Int, val dr: Int, val dc: Int) {
    UP(0, -1, 0),
    DOWN(1, 1, 0),
    LEFT(2, 0, -1),
    RIGHT(3, 0, 1),
    UP_LEFT(4, -1, -1),
    UP_RIGHT(5, -1, 1),
    DOWN_LEFT(6, 1, -1),
    DOWN_RIGHT(7, 1, 1);

    val isDiagonal: Boolean get() = code >= 4

    companion object {
        fun fromCode(code: Int): Direction = entries.first { it.code == code }
    }
}

@Serializable
data class CellCoord(val row: Int, val col: Int)

@Serializable
data class CellState(
    val element: Element = Element.UNKNOWN,
    val variant: Variant = Variant.NORMAL,
    val hazards: Int = HazardBits.NONE,
    val confidence: Float = 1.0f,
    val race: RaceType = RaceType.NONE
)

@Serializable
data class BoardGeometry(
    val leftPx: Float,
    val topPx: Float,
    val rightPx: Float,
    val bottomPx: Float,
    val cols: Int = 6,
    val rows: Int = 5,
    val isWeijianExpanded: Boolean = false
) {
    val cellWidth: Float get() = (rightPx - leftPx) / cols
    val cellHeight: Float get() = (bottomPx - topPx) / rows

    fun cellCenterX(col: Int): Float = leftPx + (col + 0.5f) * cellWidth
    fun cellCenterY(row: Int): Float = topPx + (row + 0.5f) * cellHeight

    // Team character portrait pull origin right above HP bar & row 0 (calibrated across 4 real ToS layouts:
    // HP bar spans [topPx - 0.25*ch .. topPx - 0.05*ch], portraits span [topPx - 1.24*ch .. topPx - 0.30*ch])
    fun characterAvatarCenterY(): Float = topPx - 0.77f * cellHeight
}

@Serializable
data class BoardState(
    val cols: Int = 6,
    val rows: Int = 5,
    val cells: List<CellState>,
    val tileFlags: List<Int> = List(cols * rows) { TileFlagBits.NONE },
    val orderedCheckpoints: List<Int> = emptyList(),
    val startingBuoyCol: Int = -1,
    val startingBuoyElement: Element = Element.UNKNOWN
) {
    init {
        require(cols in 3..8 && rows in 3..8) { "Board dimensions must be within 3..8, got ${cols}x${rows}" }
        require(cells.size == cols * rows) { "Expected ${cols * rows} cells, got ${cells.size}" }
        require(tileFlags.size == cols * rows) { "Expected ${cols * rows} tileFlags, got ${tileFlags.size}" }
    }

    fun index(row: Int, col: Int): Int = row * cols + col
    fun cellAt(row: Int, col: Int): CellState = cells[index(row, col)]
}
