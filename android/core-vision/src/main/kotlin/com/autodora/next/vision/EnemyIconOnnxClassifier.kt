package com.autodora.next.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import com.autodora.next.model.BoardVisualHints
import com.autodora.next.model.ShieldCategory
import com.autodora.next.model.WaveShieldChip
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.FloatBuffer
import java.util.Collections
import kotlin.math.floor

data class EnemyIconMetaConfig(
    val classes: List<String>,
    val thresholds: Map<String, Float>
) {
    companion object {
        val DEFAULT_CLASSES = listOf(
            "ICON_PUZZLE",
            "ICON_EXACT_COMBO",
            "ICON_MIN_COMBO",
            "ICON_NO_CASCADE",
            "ICON_ANTI_FIRST_WAVE",
            "ICON_FORBID_ALL",
            "ICON_FIVE_ELEMENTS",
            "ICON_THREE_ELEMENTS",
            "ICON_ENCHANTED_BREAKTHROUGH",
            "ICON_CROSS_SHIELD",
            "ICON_L_SHAPE",
            "ICON_T_SHAPE",
            "ICON_SQUARE_2X2",
            "ICON_DIGITAL_COUNT",
            "ICON_FULL_CLEAR",
            "ICON_RACE_SHIELD",
            "ICON_UNMATCHABLE",
            "ICON_STEP_LIMIT",
            "ICON_TRAFFIC_LIGHT",
            "ICON_CHAR_ORB_TERRAIN",
            "ICON_STARTING_BUOY",
            "ICON_BURNING_TRAIL",
            "ICON_CORROSION_TRAIL",
            "ICON_MONOCHROME_DISGUISE",
            "ICON_MAX_COMBO",
            "ICON_EQUAL_COMBO",
            "ICON_COLUMN_CLEAR",
            "ICON_LUMINOUS",
            "ICON_WEAKENED_FORBID",
            "ICON_ORB_GROUP_SIZE",
            "ICON_WAVE1_KIND",
            "ICON_ELEM_BURST",
            "ICON_ELECTRIFIED_HINT",
            "ICON_FROZEN_PETRIFIED_HINT",
            "ICON_WEATHERED_BOMB_HINT",
            "ICON_TERRAIN_HAZARD_HINT"
        )

        fun fromJson(rawJson: String): EnemyIconMetaConfig {
            val defaultMap = DEFAULT_CLASSES.associateWith { 1.01f }
            return runCatching {
                val obj = Json.parseToJsonElement(rawJson).jsonObject
                val clsArr = obj["classes"]?.jsonArray
                val clsList = if (clsArr != null && clsArr.isNotEmpty()) {
                    clsArr.map { it.jsonPrimitive.content }
                } else {
                    DEFAULT_CLASSES
                }
                val thObj = obj["class_thresholds"]?.jsonObject
                val thMap = HashMap<String, Float>(clsList.size)
                for (c in clsList) {
                    val v = thObj?.get(c)?.jsonPrimitive?.doubleOrNull?.toFloat() ?: 1.01f
                    thMap[c] = v.coerceIn(0.45f, 1.05f)
                }
                EnemyIconMetaConfig(clsList, thMap)
            }.getOrElse {
                EnemyIconMetaConfig(DEFAULT_CLASSES, defaultMap)
            }
        }
    }
}

data class EnemyIconInferenceResult(
    val detectedClasses: List<String>,
    val chips: List<WaveShieldChip>,
    val hints: BoardVisualHints,
    val elapsedMs: Double,
    val classPeaksScreenRatio: Map<String, Pair<Float, Float>> = emptyMap()
)

/**
 * Pure Kotlin upper-battle viewport preprocessor for Stage 2B `EnemyIconNet`:
 * crops `x in [0.04..0.96], y in [0.08..0.42]` and resamples to `[1, 3, 160, 256]` NCHW Float32 `[0, 1]`.
 */
object EnemyIconStripPreprocessor {
    const val STRIP_H = 160
    const val STRIP_W = 256

    fun fillStripNchw(frame: VisionFrame): FloatArray {
        val planeSize = STRIP_H * STRIP_W
        val dst = FloatArray(3 * planeSize)
        val w = frame.width.coerceAtLeast(32)
        val h = frame.height.coerceAtLeast(32)
        val xStart = w * 0.04f
        val xSpan = w * 0.92f
        val yStart = h * 0.08f
        val ySpan = h * 0.34f
        val maxX = w - 1
        val maxY = h - 1
        val inv255 = 1f / 255f

        val gOff = planeSize
        val bOff = 2 * planeSize

        for (py in 0 until STRIP_H) {
            val sy = (yStart + ((py + 0.5f) / STRIP_H) * ySpan - 0.5f).coerceIn(0f, maxY.toFloat())
            val y0 = floor(sy.toDouble()).toInt().coerceIn(0, maxY)
            val y1 = (y0 + 1).coerceAtMost(maxY)
            val wy1 = sy - y0
            val wy0 = 1f - wy1
            val rowBase = py * STRIP_W

            for (px in 0 until STRIP_W) {
                val sx = (xStart + ((px + 0.5f) / STRIP_W) * xSpan - 0.5f).coerceIn(0f, maxX.toFloat())
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

                val idx = rowBase + px
                dst[idx] = (rr * inv255).coerceIn(0f, 1f)
                dst[gOff + idx] = (gg * inv255).coerceIn(0f, 1f)
                dst[bOff + idx] = (bb * inv255).coerceIn(0f, 1f)
            }
        }
        return dst
    }
}

/**
 * Maps threshold-passing `EnemyIconNet` predictions into conservative non-parameterized
 * solver chips & `BoardVisualHints` (Tier-3 Arbitration). Ambiguous numeric/element classes
 * (like `ICON_EXACT_COMBO` or `ICON_FORBID_ALL` without an exact element/count) are omitted
 * so Tier-3 never injects unverified numeric parameters ahead of OCR/User overrides.
 */
object EnemyIconChipMapper {

    fun mapDetections(
        probs: FloatArray,
        config: EnemyIconMetaConfig,
        peaksGrid: Array<FloatArray>? = null
    ): EnemyIconInferenceResult {
        val detected = ArrayList<String>()
        val chips = ArrayList<WaveShieldChip>()
        val peakMap = LinkedHashMap<String, Pair<Float, Float>>()
        var hasMonochrome = false
        var hasBurning = false
        var hasSticky = false
        var hasElectrified = false
        var hasFrozenOrPetrified = false
        var hasWeatheredOrBomb = false

        val count = minOf(probs.size, config.classes.size)
        for (i in 0 until count) {
            val cls = config.classes[i]
            val th = config.thresholds[cls] ?: 0.98f
            if (probs[i] < th) continue
            detected.add(cls)
            val pk = peaksGrid?.getOrNull(i)
            if (pk != null && pk.size >= 2) {
                peakMap[cls] = EnemyBadgeDigitExtractor.gridPeakToScreenRatio(pk[0], pk[1])
            }

            when (cls) {
                "ICON_PUZZLE" -> chips.add(WaveShieldChip("puzzle_shield", "拼图盾(图标)", ShieldCategory.SHAPE_AND_PUZZLE, autoDetected = true))
                "ICON_FIVE_ELEMENTS" -> chips.add(WaveShieldChip("five_elements", "五属盾(图标)", ShieldCategory.COUNT_AND_ELEMENT, autoDetected = true))
                "ICON_CROSS_SHIELD" -> chips.add(WaveShieldChip("cross_shield", "十字限盾(图标)", ShieldCategory.SHAPE_AND_PUZZLE, autoDetected = true, paramInt = 1))
                "ICON_L_SHAPE" -> chips.add(WaveShieldChip("l_shape", "L字限盾(图标)", ShieldCategory.SHAPE_AND_PUZZLE, autoDetected = true))
                "ICON_T_SHAPE" -> chips.add(WaveShieldChip("t_shape", "T字限盾(图标)", ShieldCategory.SHAPE_AND_PUZZLE, autoDetected = true))
                "ICON_SQUARE_2X2" -> chips.add(WaveShieldChip("square_2x2", "田字盾(图标)", ShieldCategory.SHAPE_AND_PUZZLE, autoDetected = true))
                "ICON_NO_CASCADE" -> chips.add(WaveShieldChip("no_cascade", "禁天降(图标)", ShieldCategory.COMBO, autoDetected = true))
                "ICON_FULL_CLEAR" -> chips.add(WaveShieldChip("first_wave_full_clear", "首消全版(图标)", ShieldCategory.COUNT_AND_ELEMENT, autoDetected = true))
                "ICON_ENCHANTED_BREAKTHROUGH" -> chips.add(WaveShieldChip("any_enchanted_orb_shield", "强化突破(图标)", ShieldCategory.COUNT_AND_ELEMENT, autoDetected = true))
                "ICON_TRAFFIC_LIGHT" -> chips.add(WaveShieldChip("traffic_light", "起结有序(图标)", ShieldCategory.HAZARD_AND_PATH, autoDetected = true))
                "ICON_STARTING_BUOY" -> chips.add(WaveShieldChip("starting_buoy_shield", "起动浮标(图标)", ShieldCategory.COUNT_AND_ELEMENT, autoDetected = true))
                "ICON_CHAR_ORB_TERRAIN" -> chips.add(WaveShieldChip("char_orb_terrain", "角色地形(图标)", ShieldCategory.HAZARD_AND_PATH, autoDetected = true))
                "ICON_COLUMN_CLEAR" -> chips.add(WaveShieldChip("all_columns_ameliorate", "纵横轮转/每列消除(图标)", ShieldCategory.SHAPE_AND_PUZZLE, autoDetected = true))
                "ICON_LUMINOUS" -> chips.add(WaveShieldChip("luminous_orb_shield", "流光符石盾(图标)", ShieldCategory.HAZARD_AND_PATH, autoDetected = true))
                "ICON_WEAKENED_FORBID" -> chips.add(WaveShieldChip("forbid_weakened_orb", "禁消弱化珠(图标)", ShieldCategory.HAZARD_AND_PATH, autoDetected = true))
                "ICON_BURNING_TRAIL" -> {
                    chips.add(WaveShieldChip("zero_trail_burning", "燃烧地形(图标)", ShieldCategory.HAZARD_AND_PATH, autoDetected = true))
                    hasBurning = true
                }
                "ICON_CORROSION_TRAIL" -> {
                    chips.add(WaveShieldChip("trail_corrosion", "轨迹/黏腐(图标)", ShieldCategory.HAZARD_AND_PATH, autoDetected = true, paramInt = 15))
                    hasSticky = true
                }
                "ICON_MONOCHROME_DISGUISE" -> hasMonochrome = true
                "ICON_ELECTRIFIED_HINT" -> hasElectrified = true
                "ICON_FROZEN_PETRIFIED_HINT" -> hasFrozenOrPetrified = true
                "ICON_WEATHERED_BOMB_HINT" -> hasWeatheredOrBomb = true
            }
        }

        val hints = BoardVisualHints(
            hasMonochrome = hasMonochrome,
            hasBurning = hasBurning,
            hasSticky = hasSticky,
            hasElectrified = hasElectrified,
            hasFrozenOrPetrified = hasFrozenOrPetrified,
            hasWeatheredOrBomb = hasWeatheredOrBomb
        )
        return EnemyIconInferenceResult(
            detectedClasses = detected,
            chips = chips.distinctBy { it.id },
            hints = hints,
            elapsedMs = 0.0,
            classPeaksScreenRatio = peakMap
        )
    }
}

/**
 * On-device Stage 2B `EnemyIconNetV1` ONNX classifier scanning live battle upper strips for
 * enemy shield icons (`Tier-3` fallback when neither Stage Script nor Tier-2 OCR covered the wave).
 */
class EnemyIconOnnxClassifier(
    private val context: Context,
    private val assetModelPath: String = DEFAULT_MODEL_ASSET_PATH,
    private val assetMetaPath: String = DEFAULT_META_ASSET_PATH
) {
    @Volatile
    private var env: OrtEnvironment? = null

    @Volatile
    private var session: OrtSession? = null

    @Volatile
    private var inputName: String = "upper_strip"

    @Volatile
    private var checkedAvailability: Boolean = false

    @Volatile
    private var assetAvailable: Boolean = false

    val metaConfig: EnemyIconMetaConfig by lazy {
        val raw = runCatching {
            context.assets.open(assetMetaPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrDefault("")
        EnemyIconMetaConfig.fromJson(raw)
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
            inputName = s.inputNames.firstOrNull() ?: "upper_strip"
            session = s
            true
        }.getOrElse { false }
    }

    fun classifyBitmap(bitmap: Bitmap): EnemyIconInferenceResult? {
        if (bitmap.isRecycled || bitmap.width < 64 || bitmap.height < 64) return null
        return classifyVisionFrame(DirectBitmapVisionFrame(bitmap))
    }

    fun classifyVisionFrame(frame: VisionFrame): EnemyIconInferenceResult? {
        if (!ensureLoaded()) return null
        val sess = session ?: return null
        val ortEnv = env ?: return null
        val t0 = System.nanoTime()

        return runCatching {
            val nchw = EnemyIconStripPreprocessor.fillStripNchw(frame)
            val shape = longArrayOf(
                1L,
                3L,
                EnemyIconStripPreprocessor.STRIP_H.toLong(),
                EnemyIconStripPreprocessor.STRIP_W.toLong()
            )
            OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(nchw), shape).use { tensor ->
                sess.run(Collections.singletonMap(inputName, tensor)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val rawOut = result[0].value as Array<FloatArray>
                    val probs = rawOut.firstOrNull() ?: return null
                    @Suppress("UNCHECKED_CAST")
                    val rawPeaks = if (result.size() >= 2) {
                        runCatching { (result[1].value as? Array<Array<FloatArray>>)?.firstOrNull() }.getOrNull()
                    } else null
                    val mapped = EnemyIconChipMapper.mapDetections(probs, metaConfig, rawPeaks)
                    mapped.copy(elapsedMs = (System.nanoTime() - t0) / 1_000_000.0)
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
        const val DEFAULT_MODEL_ASSET_PATH = "models/enemy_icon_v1.onnx"
        const val DEFAULT_META_ASSET_PATH = "models/enemy_icon_meta.json"
    }
}
