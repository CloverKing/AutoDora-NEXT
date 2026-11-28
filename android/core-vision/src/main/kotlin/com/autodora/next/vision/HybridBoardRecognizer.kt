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
 *  - When `useAiModel == true`:
 *      1) Prefers Stage 2A `BoardMasterOnnxClassifier` (`board_master_v2.onnx`, dual-frame 6-ch
 *         grid Transformer supporting dynamic 6x5 and 6x8 boards + real flicker pairs).
 *      2) Falls back seamlessly to LiteRT `AutoDoraOrbNetV1` (`orb_multihead_v1.tflite`, or any
 *         user custom `.tflite` override) if Stage 2A ONNX is absent or fails.
 *  - Merges AI predictions through confidence-gated [HybridFusionPolicy] and falls back to pure
 *    CvBoardRecognizer whenever AI is disabled or unavailable.
 */
class HybridBoardRecognizer(
    private val context: Context,
    private val cvFallback: CvBoardRecognizer = CvBoardRecognizer()
) : BoardRecognizer {

    private var customModelFile: File? = null
    private val boardMasterClassifier: BoardMasterOnnxClassifier by lazy { BoardMasterOnnxClassifier(context) }
    private val liteRtClassifier: LiteRtOrbClassifier by lazy { LiteRtOrbClassifier(context) }

    @Volatile
    var useAiModel: Boolean = true

    @Volatile
    var activeVisualHints: BoardVisualHints = BoardVisualHints()

    override val name: String
        get() = when {
            !useAiModel -> cvFallback.name
            customModelFile == null && boardMasterClassifier.hasModel() -> "Hybrid-BoardMasterV2+CV"
            hasLiteRtModel() -> "Hybrid-LiteRT+CV-v1"
            else -> cvFallback.name
        }

    fun setCustomModelFile(file: File?) {
        customModelFile = file?.takeIf { it.exists() && it.length() > 0 }
    }

    private fun hasLiteRtModel(): Boolean {
        if (customModelFile != null) return true
        return try {
            context.assets.list("models")?.any { it.endsWith(".tflite") } == true
        } catch (_: Exception) {
            false
        }
    }

    fun hasAiModel(): Boolean = boardMasterClassifier.hasModel() || hasLiteRtModel()

    fun modelDisplayStatus(): String = when {
        !hasAiModel() -> "CV Fallback (No AI model asset)"
        !useAiModel -> "Pure CV Mode (AI Paused)"
        customModelFile == null && boardMasterClassifier.hasModel() -> "Hybrid BoardMasterV2 ONNX + CV Active"
        else -> "Hybrid LiteRT + CV Active"
    }

    fun close() {
        boardMasterClassifier.close()
        liteRtClassifier.close()
    }

    override suspend fun recognize(frames: List<Bitmap>, geometry: BoardGeometry): RecognitionResult =
        withContext(Dispatchers.Default) {
            val t0 = System.nanoTime()
            require(frames.isNotEmpty()) { "At least one frame is required for recognition" }
            val visionFrames = frames.map { DirectBitmapVisionFrame(it) }
            val hints = activeVisualHints
            val cvResult = cvFallback.recognizeFrames(visionFrames, geometry, hints)

            if (!useAiModel || !hasAiModel()) {
                return@withContext cvResult
            }

            val primaryBmp = frames.first()
            val gain = if (cvResult.isDimmedBoard) {
                cvFallback.computeDimmedBrightnessGain(visionFrames.first(), geometry)
            } else {
                1.0f
            }

            var usedConfig = liteRtClassifier.fusionConfig
            val preds: List<CellModelPrediction>? =
                if (customModelFile == null && boardMasterClassifier.hasModel()) {
                    boardMasterClassifier.classifyFrames(frames, geometry, gain)?.also {
                        usedConfig = boardMasterClassifier.fusionConfig
                    }
                } else {
                    null
                } ?: liteRtClassifier.classifyBitmap(
                    bitmap = primaryBmp,
                    fullWidth = primaryBmp.width,
                    fullHeight = primaryBmp.height,
                    yOffset = 0,
                    geometry = geometry,
                    brightnessGain = gain,
                    customModelFile = customModelFile
                )

            if (preds == null) return@withContext cvResult

            val totalMs = (System.nanoTime() - t0) / 1_000_000.0
            HybridFusionPolicy.fuse(
                cvResult = cvResult,
                predictions = preds,
                totalElapsedMs = totalMs,
                config = usedConfig,
                visualHints = activeVisualHints
            ).copy(engineName = name)
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
        val hints = activeVisualHints
        val cvResult = cvFallback.recognizeFrames(listOf(vf), geometry, hints)

        if (!useAiModel || !hasAiModel()) {
            return@withContext cvResult
        }

        val gain = if (cvResult.isDimmedBoard) {
            cvFallback.computeDimmedBrightnessGain(vf, geometry)
        } else {
            1.0f
        }
        var usedConfig = liteRtClassifier.fusionConfig
        val preds: List<CellModelPrediction>? =
            if (customModelFile == null && boardMasterClassifier.hasModel()) {
                boardMasterClassifier.classifyVisionFrames(vf, vf, geometry, gain)?.also {
                    usedConfig = boardMasterClassifier.fusionConfig
                }
            } else {
                null
            } ?: liteRtClassifier.classifyBitmap(
                bitmap = bandBitmap,
                fullWidth = fullWidth,
                fullHeight = fullHeight,
                yOffset = yOffset,
                geometry = geometry,
                brightnessGain = gain,
                customModelFile = customModelFile
            )

        if (preds == null) return@withContext cvResult

        val totalMs = (System.nanoTime() - t0) / 1_000_000.0
        HybridFusionPolicy.fuse(
            cvResult = cvResult,
            predictions = preds,
            totalElapsedMs = totalMs,
            config = usedConfig,
            visualHints = activeVisualHints
        ).copy(engineName = name)
    }
}
