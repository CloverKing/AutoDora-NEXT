package com.autodora.next.vision

import android.graphics.Bitmap
import com.autodora.next.model.BoardGeometry
import com.autodora.next.model.BoardState
import com.autodora.next.model.Element
import com.autodora.next.model.HazardBits

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
    val stormCellsDetected: Int = 0
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
