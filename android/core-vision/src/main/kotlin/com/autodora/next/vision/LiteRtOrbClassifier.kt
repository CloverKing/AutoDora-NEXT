package com.autodora.next.vision

import android.content.Context
import android.graphics.Bitmap
import com.autodora.next.model.BoardGeometry
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.floor

/**
 * Pure Kotlin helper for bilinearly resampling every grid cell into `[N, 64, 64, 3]` float32 RGB `[0, 1]`.
 * Kept free of Android framework dependencies so JVM unit tests can verify 6x5, 6x8, yOffset, and dim gain.
 */
internal object OrbBatchPreprocessor {

    fun fillInputBuffer(
        frame: VisionFrame,
        geometry: BoardGeometry,
        brightnessGain: Float,
        target: ByteBuffer
    ): ByteBuffer {
        val cols = geometry.cols
        val rows = geometry.rows
        val totalCells = cols * rows
        val size = OrbNetSchema.INPUT_SIZE
        val neededBytes = totalCells * size * size * 3 * 4
        val buf = if (target.capacity() >= neededBytes) {
            target.apply { clear() }
        } else {
            ByteBuffer.allocateDirect(neededBytes).order(ByteOrder.nativeOrder())
        }

        val cw = geometry.cellWidth
        val ch = geometry.cellHeight
        val gain = brightnessGain.coerceIn(1.0f, 2.10f)
        val inv255 = 1f / 255f
        val maxX = frame.width - 1
        val maxY = frame.height - 1

        for (r in 0 until rows) {
            val cellTop = geometry.topPx + r * ch
            for (c in 0 until cols) {
                val cellLeft = geometry.leftPx + c * cw
                for (py in 0 until size) {
                    val sy = (cellTop + ((py + 0.5f) / size) * ch - 0.5f).coerceIn(0f, maxY.toFloat())
                    val y0 = floor(sy.toDouble()).toInt().coerceIn(0, maxY)
                    val y1 = (y0 + 1).coerceAtMost(maxY)
                    val wy1 = sy - y0
                    val wy0 = 1f - wy1

                    for (px in 0 until size) {
                        val sx = (cellLeft + ((px + 0.5f) / size) * cw - 0.5f).coerceIn(0f, maxX.toFloat())
                        val x0 = floor(sx.toDouble()).toInt().coerceIn(0, maxX)
                        val x1 = (x0 + 1).coerceAtMost(maxX)
                        val wx1 = sx - x0
                        val wx0 = 1f - wx1

                        val p00 = frame.getArgb(x0, y0)
                        val p10 = frame.getArgb(x1, y0)
                        val p01 = frame.getArgb(x0, y1)
                        val p11 = frame.getArgb(x1, y1)

                        val w00 = wx0 * wy0
                        val w10 = wx1 * wy0
                        val w01 = wx0 * wy1
                        val w11 = wx1 * wy1

                        val rr = ((p00 ushr 16) and 0xFF) * w00 +
                            ((p10 ushr 16) and 0xFF) * w10 +
                            ((p01 ushr 16) and 0xFF) * w01 +
                            ((p11 ushr 16) and 0xFF) * w11
                        val gg = ((p00 ushr 8) and 0xFF) * w00 +
                            ((p10 ushr 8) and 0xFF) * w10 +
                            ((p01 ushr 8) and 0xFF) * w01 +
                            ((p11 ushr 8) and 0xFF) * w11
                        val bb = (p00 and 0xFF) * w00 +
                            (p10 and 0xFF) * w10 +
                            (p01 and 0xFF) * w01 +
                            (p11 and 0xFF) * w11

                        buf.putFloat((rr * inv255 * gain).coerceIn(0f, 1f))
                        buf.putFloat((gg * inv255 * gain).coerceIn(0f, 1f))
                        buf.putFloat((bb * inv255 * gain).coerceIn(0f, 1f))
                    }
                }
            }
        }
        buf.rewind()
        return buf
    }
}

/**
 * High-speed single-JNI-copy VisionFrame backed only by the clamped board ROI pixels from an ARGB Bitmap.
 * Avoids 120k+ per-pixel Bitmap.getPixel() JNI roundtrips during 64x64 patch resampling.
 */
private class FastBitmapRoiVisionFrame(
    bitmap: Bitmap,
    override val width: Int,
    override val height: Int,
    private val yOffset: Int,
    roiLeft: Int,
    roiTop: Int,
    roiWidth: Int,
    roiHeight: Int
) : VisionFrame {
    override val minAccessibleY: Int = yOffset
    private val clampedLeft = roiLeft.coerceIn(0, (bitmap.width - 1).coerceAtLeast(0))
    private val clampedTopInBmp = (roiTop - yOffset).coerceIn(0, (bitmap.height - 1).coerceAtLeast(0))
    private val copyW = roiWidth.coerceIn(1, (bitmap.width - clampedLeft).coerceAtLeast(1))
    private val copyH = roiHeight.coerceIn(1, (bitmap.height - clampedTopInBmp).coerceAtLeast(1))
    private val pixels = IntArray(copyW * copyH).also {
        bitmap.getPixels(it, 0, copyW, clampedLeft, clampedTopInBmp, copyW, copyH)
    }

    override fun getArgb(x: Int, y: Int): Int {
        val lx = (x - clampedLeft).coerceIn(0, copyW - 1)
        val ly = (y - yOffset - clampedTopInBmp).coerceIn(0, copyH - 1)
        return pixels[ly * copyW + lx]
    }
}

/**
 * On-device LiteRT (.tflite) batched cell classifier running `AutoDoraOrbNetV1`.
 * Safely manages Interpreter lifecycle, dynamic batch resizing (e.g. 6x5=30 or 6x8=48),
 * calibrated `model_meta.json` thresholds, and graceful fallback on load/runtime errors.
 */
class LiteRtOrbClassifier(
    private val context: Context,
    private val assetModelPath: String = DEFAULT_MODEL_ASSET_PATH,
    private val assetMetaPath: String = DEFAULT_META_ASSET_PATH
) {
    private var interpreter: Interpreter? = null
    private var loadedSourceKey: String? = null
    private var lastBatchSize: Int = -1
    private var inputBuffer: ByteBuffer = ByteBuffer.allocateDirect(
        30 * OrbNetSchema.INPUT_SIZE * OrbNetSchema.INPUT_SIZE * 3 * 4
    ).order(ByteOrder.nativeOrder())

    val fusionConfig: HybridFusionConfig by lazy { loadFusionConfigFromAssets() }

    @Synchronized
    fun ensureLoaded(customModelFile: File?): Boolean {
        val targetKey = customModelFile?.takeIf { it.exists() && it.length() > 0 }?.absolutePath
            ?: "asset:$assetModelPath"
        if (interpreter != null && loadedSourceKey == targetKey) return true

        close()
        return try {
            val modelBuffer = if (customModelFile != null && customModelFile.exists() && customModelFile.length() > 0) {
                FileInputStream(customModelFile).use { fis ->
                    fis.channel.map(FileChannel.MapMode.READ_ONLY, 0, customModelFile.length())
                }
            } else {
                loadAssetModelBuffer(assetModelPath) ?: return false
            }
            val opts = Interpreter.Options().apply {
                setNumThreads(4)
                setUseXNNPACK(true)
            }
            interpreter = Interpreter(modelBuffer, opts)
            loadedSourceKey = targetKey
            lastBatchSize = -1
            true
        } catch (_: Throwable) {
            interpreter = null
            loadedSourceKey = null
            false
        }
    }

    fun classifyBitmap(
        bitmap: Bitmap,
        fullWidth: Int,
        fullHeight: Int,
        yOffset: Int,
        geometry: BoardGeometry,
        brightnessGain: Float,
        customModelFile: File?
    ): List<CellModelPrediction>? {
        val roiL = (geometry.leftPx - 4f).toInt()
        val roiT = (geometry.topPx - 4f).toInt()
        val roiW = ((geometry.rightPx - geometry.leftPx) + 8f).toInt().coerceAtLeast(1)
        val roiH = ((geometry.bottomPx - geometry.topPx) + 8f).toInt().coerceAtLeast(1)
        val vf = FastBitmapRoiVisionFrame(
            bitmap = bitmap,
            width = fullWidth,
            height = fullHeight,
            yOffset = yOffset,
            roiLeft = roiL,
            roiTop = roiT,
            roiWidth = roiW,
            roiHeight = roiH
        )
        return classifyVisionFrame(vf, geometry, brightnessGain, customModelFile)
    }

    @Synchronized
    fun classifyVisionFrame(
        frame: VisionFrame,
        geometry: BoardGeometry,
        brightnessGain: Float = 1.0f,
        customModelFile: File? = null
    ): List<CellModelPrediction>? {
        if (!ensureLoaded(customModelFile)) return null
        val interp = interpreter ?: return null
        val totalCells = geometry.cols * geometry.rows
        if (totalCells <= 0) return null

        return try {
            if (lastBatchSize != totalCells) {
                interp.resizeInput(
                    0,
                    intArrayOf(totalCells, OrbNetSchema.INPUT_SIZE, OrbNetSchema.INPUT_SIZE, 3)
                )
                interp.allocateTensors()
                lastBatchSize = totalCells
            }
            inputBuffer = OrbBatchPreprocessor.fillInputBuffer(frame, geometry, brightnessGain, inputBuffer)
            val outputs = Array(totalCells) { FloatArray(OrbNetSchema.OUTPUT_DIM) }
            interp.run(inputBuffer, outputs)
            List(totalCells) { idx -> CellModelPrediction.fromOutputRow(outputs[idx]) }
        } catch (_: Throwable) {
            null
        }
    }

    @Synchronized
    fun close() {
        try {
            interpreter?.close()
        } catch (_: Throwable) {
        }
        interpreter = null
        loadedSourceKey = null
        lastBatchSize = -1
    }

    private fun loadAssetModelBuffer(assetPath: String): ByteBuffer? {
        return try {
            context.assets.openFd(assetPath).use { afd ->
                FileInputStream(afd.fileDescriptor).use { fis ->
                    fis.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.length)
                }
            }
        } catch (_: Throwable) {
            // Compressed or fallback stream load into direct ByteBuffer
            try {
                context.assets.open(assetPath).use { inp ->
                    val bytes = inp.readBytes()
                    ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                        put(bytes)
                        rewind()
                    }
                }
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun loadFusionConfigFromAssets(): HybridFusionConfig {
        val base = HybridFusionConfig()
        return try {
            val raw = context.assets.open(assetMetaPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(raw)
            val hObj = json.optJSONObject("hazard_thresholds")
            val oObj = json.optJSONObject("tile_overlay_thresholds")
            val hArr = base.hazardThresholds.copyOf()
            val oArr = base.overlayThresholds.copyOf()
            if (hObj != null) {
                OrbNetSchema.HAZARD_NAMES.forEachIndexed { idx, name ->
                    if (hObj.has(name)) hArr[idx] = hObj.optDouble(name, hArr[idx].toDouble()).toFloat()
                }
            }
            if (oObj != null) {
                OrbNetSchema.TILE_OVERLAY_NAMES.forEachIndexed { idx, name ->
                    if (oObj.has(name)) oArr[idx] = oObj.optDouble(name, oArr[idx].toDouble()).toFloat()
                }
            }
            base.copy(hazardThresholds = hArr, overlayThresholds = oArr)
        } catch (_: Throwable) {
            base
        }
    }

    companion object {
        const val DEFAULT_MODEL_ASSET_PATH = "models/orb_multihead_v1.tflite"
        const val DEFAULT_META_ASSET_PATH = "models/model_meta.json"
    }
}
