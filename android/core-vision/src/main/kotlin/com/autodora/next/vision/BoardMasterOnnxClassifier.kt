package com.autodora.next.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import com.autodora.next.model.BoardGeometry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.FloatBuffer
import java.util.Collections
import kotlin.math.floor

/**
 * Pure Kotlin dual-frame NCHW preprocessor for Stage 2A `BoardMasterGridNet`:
 * bilinearly resamples `(frameA, frameB)` across any dynamic `(cols, rows)` board geometry
 * into `[1, 6, rows * 64, cols * 64]` Float32 `[0, 1]` in exact NCHW planar layout
 * `[R_a, G_a, B_a, R_b, G_b, B_b]`.
 */
object DualBoardPreprocessor {

    fun fillDualBoardNchw(
        frameA: VisionFrame,
        frameB: VisionFrame,
        geometry: BoardGeometry,
        brightnessGain: Float = 1.0f
    ): FloatArray {
        val cols = geometry.cols.coerceIn(3, 8)
        val rows = geometry.rows.coerceIn(3, 8)
        val cellPx = OrbNetSchema.INPUT_SIZE
        val outH = rows * cellPx
        val outW = cols * cellPx
        val planeSize = outH * outW
        val buf = FloatArray(6 * planeSize)

        val cw = geometry.cellWidth
        val ch = geometry.cellHeight
        val gain = brightnessGain.coerceIn(1.0f, 2.10f)
        val inv255 = 1f / 255f

        writeRgbPlanes(frameA, geometry, outH, outW, cw, ch, gain, inv255, buf, planeOffset = 0, planeSize = planeSize)
        writeRgbPlanes(frameB, geometry, outH, outW, cw, ch, gain, inv255, buf, planeOffset = 3 * planeSize, planeSize = planeSize)
        return buf
    }

    private fun writeRgbPlanes(
        frame: VisionFrame,
        geometry: BoardGeometry,
        outH: Int,
        outW: Int,
        cw: Float,
        ch: Float,
        gain: Float,
        inv255: Float,
        dst: FloatArray,
        planeOffset: Int,
        planeSize: Int
    ) {
        val maxX = frame.width - 1
        val maxY = frame.height - 1
        val rows = geometry.rows.coerceIn(3, 8)
        val cols = geometry.cols.coerceIn(3, 8)
        val totalBoardH = ch * rows
        val totalBoardW = cw * cols

        val rOff = planeOffset
        val gOff = planeOffset + planeSize
        val bOff = planeOffset + 2 * planeSize

        for (py in 0 until outH) {
            val sy = (geometry.topPx + ((py + 0.5f) / outH) * totalBoardH - 0.5f).coerceIn(0f, maxY.toFloat())
            val y0 = floor(sy.toDouble()).toInt().coerceIn(0, maxY)
            val y1 = (y0 + 1).coerceAtMost(maxY)
            val wy1 = sy - y0
            val wy0 = 1f - wy1
            val rowBase = py * outW

            for (px in 0 until outW) {
                val sx = (geometry.leftPx + ((px + 0.5f) / outW) * totalBoardW - 0.5f).coerceIn(0f, maxX.toFloat())
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

                val pixIdx = rowBase + px
                dst[rOff + pixIdx] = (rr * inv255 * gain).coerceIn(0f, 1f)
                dst[gOff + pixIdx] = (gg * inv255 * gain).coerceIn(0f, 1f)
                dst[bOff + pixIdx] = (bb * inv255 * gain).coerceIn(0f, 1f)
            }
        }
    }

    fun parseFusionConfigJson(rawJson: String): HybridFusionConfig {
        val base = HybridFusionConfig()
        return runCatching {
            val obj = Json.parseToJsonElement(rawJson).jsonObject
            val hObj = obj["hazard_thresholds"]?.jsonObject
            val oObj = obj["tile_overlay_thresholds"]?.jsonObject
            val hArr = base.hazardThresholds.copyOf()
            val oArr = base.overlayThresholds.copyOf()
            if (hObj != null) {
                OrbNetSchema.HAZARD_NAMES.forEachIndexed { idx, name ->
                    hObj[name]?.jsonPrimitive?.doubleOrNull?.let { hArr[idx] = it.toFloat() }
                }
            }
            if (oObj != null) {
                OrbNetSchema.TILE_OVERLAY_NAMES.forEachIndexed { idx, name ->
                    oObj[name]?.jsonPrimitive?.doubleOrNull?.let { oArr[idx] = it.toFloat() }
                }
            }
            base.copy(hazardThresholds = hArr, overlayThresholds = oArr)
        }.getOrElse { base }
    }
}

/**
 * On-device ONNX Runtime inference engine for Stage 2A `BoardMasterGridNetV2` (`board_master_v2.onnx`),
 * dynamically supporting both 6x5 (30 cells) and 6x8 Weijian (48 cells) boards with dual-frame
 * temporal flicker encoding and global Grid Transformer context.
 */
class BoardMasterOnnxClassifier(
    private val context: Context,
    private val assetModelPath: String = DEFAULT_MODEL_ASSET_PATH,
    private val assetMetaPath: String = DEFAULT_META_ASSET_PATH
) {
    @Volatile
    private var env: OrtEnvironment? = null

    @Volatile
    private var session: OrtSession? = null

    @Volatile
    private var inputName: String = "dual_board"

    @Volatile
    private var checkedAvailability: Boolean = false

    @Volatile
    private var assetAvailable: Boolean = false

    val fusionConfig: HybridFusionConfig by lazy {
        runCatching {
            val raw = context.assets.open(assetMetaPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
            DualBoardPreprocessor.parseFusionConfigJson(raw)
        }.getOrElse { HybridFusionConfig() }
    }

    fun hasModel(): Boolean {
        if (!checkedAvailability) {
            checkedAvailability = true
            assetAvailable = runCatching {
                context.assets.list("models")?.contains(assetModelPath.substringAfterLast('/')) == true
            }.getOrDefault(false)
        }
        return assetAvailable
    }

    @Synchronized
    private fun ensureLoaded(): Boolean {
        if (session != null) return true
        if (!hasModel()) return false
        return runCatching {
            val ortEnv = env ?: OrtEnvironment.getEnvironment().also { env = it }
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val bytes = context.assets.open(assetModelPath).use { it.readBytes() }
            val s = ortEnv.createSession(bytes, opts)
            inputName = s.inputNames.firstOrNull() ?: "dual_board"
            session = s
            true
        }.getOrElse { false }
    }

    fun classifyFrames(
        frames: List<Bitmap>,
        geometry: BoardGeometry,
        brightnessGain: Float = 1.0f
    ): List<CellModelPrediction>? {
        if (frames.isEmpty()) return null
        val bmpA = frames.first()
        val bmpB = frames.last()
        val vfA = DirectBitmapVisionFrame(bmpA)
        val vfB = if (bmpB === bmpA) vfA else DirectBitmapVisionFrame(bmpB)
        return classifyVisionFrames(vfA, vfB, geometry, brightnessGain)
    }

    fun classifyVisionFrames(
        frameA: VisionFrame,
        frameB: VisionFrame,
        geometry: BoardGeometry,
        brightnessGain: Float = 1.0f
    ): List<CellModelPrediction>? {
        if (!ensureLoaded()) return null
        val sess = session ?: return null
        val ortEnv = env ?: return null
        val cols = geometry.cols.coerceIn(3, 8)
        val rows = geometry.rows.coerceIn(3, 8)
        val outH = rows * OrbNetSchema.INPUT_SIZE
        val outW = cols * OrbNetSchema.INPUT_SIZE
        val totalCells = cols * rows

        return runCatching {
            val nchw = DualBoardPreprocessor.fillDualBoardNchw(frameA, frameB, geometry, brightnessGain)
            val shape = longArrayOf(1L, 6L, outH.toLong(), outW.toLong())
            OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(nchw), shape).use { tensor ->
                sess.run(Collections.singletonMap(inputName, tensor)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val rawOut = result[0].value as Array<Array<FloatArray>>
                    val gridRows = rawOut.firstOrNull() ?: return null
                    if (gridRows.size != totalCells) return null
                    List(totalCells) { idx -> CellModelPrediction.fromOutputRow(gridRows[idx]) }
                }
            }
        }.getOrNull()
    }

    @Synchronized
    fun close() {
        runCatching { session?.close() }
        session = null
    }

    companion object {
        const val DEFAULT_MODEL_ASSET_PATH = "models/board_master_v2.onnx"
        const val DEFAULT_META_ASSET_PATH = "models/board_master_meta.json"
    }
}
