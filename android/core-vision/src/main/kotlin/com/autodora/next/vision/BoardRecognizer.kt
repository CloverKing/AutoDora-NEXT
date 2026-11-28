package com.autodora.next.vision

import android.graphics.Bitmap
import com.autodora.next.model.BoardGeometry
import com.autodora.next.model.BoardState
import com.autodora.next.model.Element
import com.autodora.next.model.HazardBits
import com.autodora.next.model.TileFlagBits

data class RecognitionResult(
    val board: BoardState,
    val averageConfidence: Float,
    val lowConfidenceIndices: List<Int>,
    val puzzleCellsDetected: Int,
    val engineName: String,
    val elapsedMs: Double,
    val questionOrbsDetected: Int = board.cells.count {
        it.element == Element.HIDDEN || (it.hazards and HazardBits.QUESTION) != 0
    },
    val raceBadgesDetected: Int = 0,
    val stormCellsDetected: Int = 0,
    val trafficLightStartCount: Int = 0,
    val trafficLightEndCount: Int = 0,
    val petrifiedOrbsDetected: Int = board.cells.count { (it.hazards and HazardBits.PETRIFIED) != 0 },
    val bloodDrainOrbsDetected: Int = board.cells.count {
        (it.hazards and (HazardBits.BLOOD_DRAIN or HazardBits.LOCKED_HURT)) != 0
    },
    val thornOrbsDetected: Int = board.cells.count { (it.hazards and HazardBits.THORN) != 0 },
    val electrifiedOrbsDetected: Int = board.cells.count { (it.hazards and HazardBits.ELECTRIFIED) != 0 },
    val frozenOrbsDetected: Int = board.cells.count {
        (it.hazards and (HazardBits.FROZEN or HazardBits.ICE_SEALED)) != 0
    },
    val bombOrbsDetected: Int = board.cells.count { (it.hazards and HazardBits.BOMB) != 0 },
    val evilEnergyOrbsDetected: Int = board.cells.count { (it.hazards and HazardBits.EVIL_ENERGY) != 0 },
    val purgatoryFireOrbsDetected: Int = board.cells.count { (it.hazards and HazardBits.PURGATORY_FIRE) != 0 },
    val burningTilesDetected: Int = board.tileFlags.count {
        (it and (TileFlagBits.BURNING_TILE or TileFlagBits.SCORCHING_TILE)) != 0
    },
    val bloodbathTilesDetected: Int = board.tileFlags.count { (it and TileFlagBits.END_FORBIDDEN) != 0 },
    val shatteredTilesDetected: Int = board.tileFlags.count { (it and TileFlagBits.SHATTERED) != 0 },
    val blackWhiteOrbsDetected: Int = board.cells.count { (it.hazards and HazardBits.BLACK_WHITE) != 0 },
    val startingBuoyCol: Int = board.startingBuoyCol,
    val startingBuoyElement: Element = board.startingBuoyElement,
    val isDimmedBoard: Boolean = false,
    val monochromeCellProbs: List<FloatArray>? = null
)

/**
 * Pluggable board recognition interface:
 *  - Phase 2: CvBoardRecognizer (zero-training HSV/Lab + multi-frame enchanted/puzzle border detector)
 *  - Phase 4: LiteRtBoardRecognizer (multi-head lightweight CNN batch = Cols x Rows)
 */
interface BoardRecognizer {
    val name: String
    suspend fun recognize(frames: List<Bitmap>, geometry: BoardGeometry): RecognitionResult
    suspend fun recognizeBand(
        bandBitmap: Bitmap,
        fullWidth: Int,
        fullHeight: Int,
        yOffset: Int,
        geometry: BoardGeometry
    ): RecognitionResult = recognize(listOf(bandBitmap), geometry)
}
