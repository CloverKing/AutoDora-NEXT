package com.autodora.next.vision

import android.graphics.Bitmap
import com.autodora.next.model.BoardGeometry
import com.autodora.next.model.BoardState

data class RecognitionResult(
    val board: BoardState,
    val averageConfidence: Float,
    val lowConfidenceIndices: List<Int>,
    val puzzleCellsDetected: Int,
    val engineName: String,
    val elapsedMs: Double
)

/**
 * Pluggable board recognition interface:
 *  - Phase 2: CvBoardRecognizer (zero-training HSV/Lab + multi-frame enchanted/puzzle border detector)
 *  - Phase 4: LiteRtBoardRecognizer (multi-head lightweight CNN batch = Cols x Rows)
 */
interface BoardRecognizer {
    val name: String
    suspend fun recognize(frames: List<Bitmap>, geometry: BoardGeometry): RecognitionResult
}
