package com.autodora.next.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.nio.FloatBuffer
import java.util.Collections
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure-offline Traditional/Simplified Chinese OCR engine backed by ONNX Runtime Mobile (1.32.0)
 * and RapidAI PP-OCRv5 Mobile models (~20.5 MB total in `assets/ocr/`), with ZERO Google Play
 * Services / ML Kit dependency.
 *
 * Includes calibrated ToS 2025.41+ grey-text detection per recognized box so enemy skills
 * already bypassed by team leaders/skills are preserved verbatim for Room DB archiving while
 * dynamically ignored when generating active battle solver constraints.
 */
class PaddleOcrOnnxEngine(private val context: Context) {

    @Volatile
    private var env: OrtEnvironment? = null

    @Volatile
    private var detSession: OrtSession? = null

    @Volatile
    private var recSession: OrtSession? = null

    @Volatile
    private var vocab: List<String> = emptyList()

    @Volatile
    private var detInputName: String = "x"

    @Volatile
    private var recInputName: String = "x"

    @Synchronized
    private fun ensureInitialized(): Boolean {
        if (detSession != null && recSession != null && vocab.isNotEmpty()) return true
        return runCatching {
            val ortEnv = env ?: OrtEnvironment.getEnvironment().also { env = it }
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val detBytes = context.assets.open(DET_ASSET_PATH).use { it.readBytes() }
            val recBytes = context.assets.open(REC_ASSET_PATH).use { it.readBytes() }

            val dSess = ortEnv.createSession(detBytes, opts)
            val rSess = ortEnv.createSession(recBytes, opts)

            detInputName = dSess.inputNames.firstOrNull() ?: "x"
            recInputName = rSess.inputNames.firstOrNull() ?: "x"

            val loadedChars = extractCharacters(rSess)
            val fullVocab = ArrayList<String>(loadedChars.size + 2)
            fullVocab.add("blank")
            fullVocab.addAll(loadedChars)
            fullVocab.add(" ")

            detSession = dSess
            recSession = rSess
            vocab = fullVocab
            true
        }.getOrElse {
            false
        }
    }

    private fun extractCharacters(rSess: OrtSession): List<String> {
        val metaVal = runCatching { rSess.metadata.customMetadata["character"] }.getOrNull()
        val rawText = if (!metaVal.isNullOrBlank()) {
            metaVal
        } else {
            runCatching {
                context.assets.open(DICT_ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }.getOrNull().orEmpty()
        }
        return rawText.lineSequence().map { it.trimEnd('\r') }.toList()
    }

    /**
     * Runs offline DBNet text detection + SVTR-CTC recognition on [screenBitmap], cropped tightly
     * to the expected region for [modalKind], and returns reading-order [OcrTextLine]s with greyed-out metadata.
     */
    fun recognize(screenBitmap: Bitmap, modalKind: DarkModalKind): OcrFrameResult {
        val t0 = android.os.SystemClock.elapsedRealtime()
        if (screenBitmap.isRecycled || screenBitmap.width < 64 || screenBitmap.height < 64) {
            return OcrFrameResult(emptyList(), 0L, modalKind)
        }
        if (!ensureInitialized()) {
            return OcrFrameResult(emptyList(), android.os.SystemClock.elapsedRealtime() - t0, modalKind)
        }

        val w = screenBitmap.width
        val h = screenBitmap.height
        val (rx0, ry0, rx1, ry1) = when (modalKind) {
            // Long-press popup sits cleanly in the middle/lower card region (38%..82% height),
            // strictly excluding AutoDora's top floating toolbar and Android's bottom Toast band.
            DarkModalKind.ENEMY_LONG_PRESS_POPUP -> intArrayOf(
                (w * 0.05f).toInt(), (h * 0.38f).toInt(), (w * 0.95f).toInt(), (h * 0.82f).toInt()
            )
            // Stage Info modal (both pre-battle & in-battle) spans [14%..85%], below the floating toolbar
            // and above the bottom Toast / navigation area.
            DarkModalKind.STAGE_INFO_MODAL -> intArrayOf(
                (w * 0.04f).toInt(), (h * 0.14f).toInt(), (w * 0.96f).toInt(), (h * 0.85f).toInt()
            )
            DarkModalKind.NONE -> intArrayOf(0, 0, w, h)
        }
        val cropW = (rx1 - rx0).coerceAtLeast(32)
        val cropH = (ry1 - ry0).coerceAtLeast(32)
        val roi = Bitmap.createBitmap(
            screenBitmap,
            rx0.coerceIn(0, w - 1),
            ry0.coerceIn(0, h - 1),
            min(cropW, w - rx0.coerceIn(0, w - 1)),
            min(cropH, h - ry0.coerceIn(0, h - 1))
        )

        try {
            val rawLines = recognizeRoi(roi)
            val merged = PaddleOcrPostprocess.mergeAdjacentLineSegments(rawLines)
            val elapsed = android.os.SystemClock.elapsedRealtime() - t0
            return OcrFrameResult(merged, elapsed, modalKind)
        } finally {
            if (!roi.isRecycled) roi.recycle()
        }
    }

    private fun recognizeRoi(roi: Bitmap): List<OcrTextLine> {
        val ortEnv = env ?: return emptyList()
        val dSess = detSession ?: return emptyList()
        val rSess = recSession ?: return emptyList()
        val curVocab = vocab

        val rw = roi.width
        val rh = roi.height
        val maxSide = max(rw, rh)
        val scale = if (maxSide <= DET_MAX_SIDE) 1f else DET_MAX_SIDE.toFloat() / maxSide.toFloat()
        val detW = max(32, ((rw * scale) / 32f).roundToInt() * 32)
        val detH = max(32, ((rh * scale) / 32f).roundToInt() * 32)

        val detScaled = if (detW == rw && detH == rh) roi else Bitmap.createScaledBitmap(roi, detW, detH, true)
        val boxes: List<OcrBox> = try {
            val detTensor = bitmapToNchwFloatTensor(
                ortEnv = ortEnv,
                bmp = detScaled,
                mean0 = 0.406f, mean1 = 0.456f, mean2 = 0.485f,
                std0 = 0.225f, std1 = 0.224f, std2 = 0.229f,
                bgr = true
            )
            detTensor.use { inp ->
                dSess.run(Collections.singletonMap(detInputName, inp)).use { results ->
                    val outTensor = results[0] as OnnxTensor
                    val shape = outTensor.info.shape
                    val outH = shape.getOrElse(2) { detH.toLong() }.toInt()
                    val outW = shape.getOrElse(3) { detW.toLong() }.toInt()
                    val buf = outTensor.floatBuffer
                    val prob = FloatArray(buf.remaining())
                    buf.get(prob)
                    PaddleOcrPostprocess.extractBoxesFromProbMap(
                        prob = prob,
                        mapW = outW,
                        mapH = outH,
                        origW = rw,
                        origH = rh,
                        thresh = 0.30f,
                        boxThresh = 0.45f,
                        unclipRatio = 1.6f
                    )
                }
            }
        } finally {
            if (detScaled !== roi && !detScaled.isRecycled) detScaled.recycle()
        }

        if (boxes.isEmpty()) return emptyList()

        val roiFrame = DirectBitmapVisionFrame(roi)
        val recognized = ArrayList<OcrTextLine>(min(boxes.size, MAX_BOXES_PER_FRAME))

        for (box in boxes.take(MAX_BOXES_PER_FRAME)) {
            val bw = (box.right - box.left).coerceAtLeast(1)
            val bh = (box.bottom - box.top).coerceAtLeast(1)
            val crop = Bitmap.createBitmap(roi, box.left, box.top, bw, bh)
            val recW = ((bw * (REC_IMG_H.toFloat() / bh))).roundToInt().coerceIn(16, REC_MAX_W)
            val recScaled = Bitmap.createScaledBitmap(crop, recW, REC_IMG_H, true)
            try {
                val recTensor = bitmapToNchwFloatTensor(
                    ortEnv = ortEnv,
                    bmp = recScaled,
                    mean0 = 0.5f, mean1 = 0.5f, mean2 = 0.5f,
                    std0 = 0.5f, std1 = 0.5f, std2 = 0.5f,
                    bgr = false
                )
                val (text, conf) = recTensor.use { inp ->
                    rSess.run(Collections.singletonMap(recInputName, inp)).use { results ->
                        val outTensor = results[0] as OnnxTensor
                        val shape = outTensor.info.shape
                        val seqLen = shape.getOrElse(1) { 0L }.toInt()
                        val vocabSize = shape.getOrElse(2) { curVocab.size.toLong() }.toInt()
                        val buf = outTensor.floatBuffer
                        val probs = FloatArray(buf.remaining())
                        buf.get(probs)
                        PaddleOcrPostprocess.ctcGreedyDecode(
                            output = probs,
                            seqLen = seqLen,
                            vocabSize = vocabSize,
                            vocab = curVocab,
                            blankIndex = 0
                        )
                    }
                }
                if (text.isBlank() || conf < MIN_LINE_CONFIDENCE) continue
                val colorStats = PaddleOcrPostprocess.sampleForegroundColorStats(
                    frame = roiFrame,
                    left = box.left,
                    top = box.top,
                    right = box.right,
                    bottom = box.bottom
                )
                recognized.add(
                    OcrTextLine(
                        text = text,
                        left = box.left,
                        top = box.top,
                        right = box.right,
                        bottom = box.bottom,
                        confidence = conf,
                        p50Lum = colorStats.p50Lum,
                        p95Lum = colorStats.p95Lum,
                        vividRatio = colorStats.vividRatio,
                        isGreyBypassed = colorStats.isGreyBypassed
                    )
                )
            } finally {
                if (!recScaled.isRecycled) recScaled.recycle()
                if (!crop.isRecycled) crop.recycle()
            }
        }
        return recognized
    }

    private fun bitmapToNchwFloatTensor(
        ortEnv: OrtEnvironment,
        bmp: Bitmap,
        mean0: Float, mean1: Float, mean2: Float,
        std0: Float, std1: Float, std2: Float,
        bgr: Boolean
    ): OnnxTensor {
        val w = bmp.width
        val h = bmp.height
        val plane = w * h
        val pixels = IntArray(plane)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        val floats = FloatArray(3 * plane)
        val inv255 = 1f / 255f
        for (i in 0 until plane) {
            val argb = pixels[i]
            val r = ((argb ushr 16) and 0xFF) * inv255
            val g = ((argb ushr 8) and 0xFF) * inv255
            val b = (argb and 0xFF) * inv255
            if (bgr) {
                floats[i] = (b - mean0) / std0
                floats[plane + i] = (g - mean1) / std1
                floats[2 * plane + i] = (r - mean2) / std2
            } else {
                floats[i] = (r - mean0) / std0
                floats[plane + i] = (g - mean1) / std1
                floats[2 * plane + i] = (b - mean2) / std2
            }
        }
        return OnnxTensor.createTensor(
            ortEnv,
            FloatBuffer.wrap(floats),
            longArrayOf(1L, 3L, h.toLong(), w.toLong())
        )
    }

    @Synchronized
    fun release() {
        runCatching { detSession?.close() }
        runCatching { recSession?.close() }
        detSession = null
        recSession = null
        vocab = emptyList()
    }

    companion object {
        private const val DET_ASSET_PATH = "ocr/ch_PP-OCRv5_det_mobile.onnx"
        private const val REC_ASSET_PATH = "ocr/ch_PP-OCRv5_rec_mobile.onnx"
        private const val DICT_ASSET_PATH = "ocr/ppocrv5_dict.txt"
        private const val DET_MAX_SIDE = 960
        private const val REC_IMG_H = 48
        private const val REC_MAX_W = 640
        private const val MAX_BOXES_PER_FRAME = 64
        private const val MIN_LINE_CONFIDENCE = 0.45f
    }
}
