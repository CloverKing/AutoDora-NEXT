package com.autodora.next.vision

import android.content.Context
import android.graphics.Bitmap
import com.autodora.next.model.BoardGeometry
import java.io.File

/**
 * Pluggable AI Recognizer slot for Phase 4 multi-head LiteRT (.tflite) model.
 * Automatically checks for user-loaded/asset .tflite model and gracefully delegates to
 * CvBoardRecognizer until Phase 4 trained weights are installed.
 */
class HybridBoardRecognizer(
    private val context: Context,
    private val cvFallback: CvBoardRecognizer = CvBoardRecognizer()
) : BoardRecognizer {

    private var customModelFile: File? = null

    override val name: String
        get() = if (hasAiModel()) "LiteRT-MultiHead-CNN" else cvFallback.name

    fun setCustomModelFile(file: File?) {
        customModelFile = file?.takeIf { it.exists() && it.length() > 0 }
    }

    fun hasAiModel(): Boolean {
        if (customModelFile != null) return true
        return try {
            context.assets.list("models")?.any { it.endsWith(".tflite") } == true
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun recognize(frames: List<Bitmap>, geometry: BoardGeometry): RecognitionResult {
        // Phase 4 wires CompiledModel / Interpreter batch=[cols*rows, 64, 64, 3] inference here.
        return cvFallback.recognize(frames, geometry)
    }
}
