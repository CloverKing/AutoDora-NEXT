package com.autodora.next.vision

import android.content.Context
import android.graphics.Bitmap
import com.autodora.next.model.BoardGeometry
import com.autodora.next.model.BoardVisualHints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pluggable AI + Rule-CV Hybrid Board Recognizer (Phase 4):
 *  - Executes calibrated CvBoardRecognizer baseline (preserving Starting Buoy, Jordan-curve
 *    Puzzle Polygons, Traffic Light Start/End, and Storm full-column tornado tips).
 *  - When enabled (`useAiModel == true`) and `.tflite` weights are available (bundled asset or
 *    user custom model file), runs batched LiteRT `AutoDoraOrbNetV1` (`[Cols*Rows, 64, 64, 3]`)
 *    and merges predictions through confidence-gated [HybridFusionPolicy].
 *  - Gracefully falls back to pure CvBoardRecognizer whenever disabled or unavailable.
 */
class HybridBoardRecognizer(
    private val context: Context,
    private val cvFallback: CvBoardRecognizer = CvBoardRecognizer()
) : BoardRecognizer {

    private var customModelFile: File? = null
    private val liteRtClassifier: LiteRtOrbClassifier by lazy { LiteRtOrbClassifier(context) }

    @Volatile
    var useAiModel: Boolean = true

    @Volatile
    var activeVisualHints: BoardVisualHints = BoardVisualHints()

    override val name: String
        get() = if (useAiModel && hasAiModel()) "Hybrid-LiteRT+CV-v1" else cvFallback.name

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

    fun modelDisplayStatus(): String = when {
        !hasAiModel() -> "CV Fallback (No .tflite asset)"
        !useAiModel -> "Pure CV Mode (AI Paused)"
        else -> "Hybrid LiteRT + CV Active"
    }

    fun close() {
        liteRtClassifier.close()
    }

    override suspend fun recognize(frames: List<Bitmap>, geometry: BoardGeometry): RecognitionResult =
        withContext(Dispatchers.Default) {
            val t0 = System.nanoTime()
            require(frames.isNotEmpty()) { "At least one frame is required for recognition" }
            val visionFrames = frames.map { DirectBitmapVisionFrame(it) }
            val cvResult = cvFallback.recognizeFrames(visionFrames, geometry)

            if (!useAiModel || !hasAiModel()) {
                return@withContext cvResult
            }

            val primaryBmp = frames.first()
            val gain = if (cvResult.isDimmedBoard) {
                cvFallback.computeDimmedBrightnessGain(visionFrames.first(), geometry)
            } else {
                1.0f
            }
            val preds = liteRtClassifier.classifyBitmap(
                bitmap = primaryBmp,
                fullWidth = primaryBmp.width,
                fullHeight = primaryBmp.height,
                yOffset = 0,
                geometry = geometry,
                brightnessGain = gain,
                customModelFile = customModelFile
            ) ?: return@withContext cvResult

            val totalMs = (System.nanoTime() - t0) / 1_000_000.0
            HybridFusionPolicy.fuse(
                cvResult = cvResult,
                predictions = preds,
                totalElapsedMs = totalMs,
                config = liteRtClassifier.fusionConfig,
                visualHints = activeVisualHints
            )
        }

    override suspend fun recognizeBand(
        bandBitmap: Bitmap,
        fullWidth: Int,
        fullHeight: Int,
        yOffset: Int,
        geometry: BoardGeometry
    ): RecognitionResult = withContext(Dispatchers.Default) {
        val t0 = System.nanoTime()
        val vf = DirectBitmapVisionFrame(bandBitmap, fullWidth, fullHeight, yOffset)
        val cvResult = cvFallback.recognizeFrames(listOf(vf), geometry)

        if (!useAiModel || !hasAiModel()) {
            return@withContext cvResult
        }

        val gain = if (cvResult.isDimmedBoard) {
            cvFallback.computeDimmedBrightnessGain(vf, geometry)
        } else {
            1.0f
        }
        val preds = liteRtClassifier.classifyBitmap(
            bitmap = bandBitmap,
            fullWidth = fullWidth,
            fullHeight = fullHeight,
            yOffset = yOffset,
            geometry = geometry,
            brightnessGain = gain,
            customModelFile = customModelFile
        ) ?: return@withContext cvResult

        val totalMs = (System.nanoTime() - t0) / 1_000_000.0
        HybridFusionPolicy.fuse(
            cvResult = cvResult,
            predictions = preds,
            totalElapsedMs = totalMs,
            config = liteRtClassifier.fusionConfig
        )
    }
}
