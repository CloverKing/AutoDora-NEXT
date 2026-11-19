package com.autodora.next.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min

/**
 * Offline Chinese OCR Enemy Skill Intelligence Engine (Google ML Kit Chinese Text Recognition).
 *
 * Features:
 *  - Zero network dependency (< 80ms on-device)
 *  - Hot-updatable dictionary: checks app filesDir/tos_enemy_skill_rules.json first, falls back to bundled asset
 *  - Tower of Saviors 2025.41 / 2026.2 Greyed-Out Skill Filter: inspects foreground text brightness inside each
 *    recognized OCR line rect and automatically skips greyed/dimmed skills already bypassed by the player's team!
 */
class EnemySkillOcrEngine(private val context: Context) {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    @Volatile
    private var cachedParser: EnemySkillRuleParser? = null

    fun getParser(): EnemySkillRuleParser {
        cachedParser?.let { return it }
        val jsonText = loadRulesJson()
        return EnemySkillRuleParser.fromJson(jsonText).also { cachedParser = it }
    }

    fun reloadRules() {
        cachedParser = null
        getParser()
    }

    private fun loadRulesJson(): String {
        val hotOverride = File(context.filesDir, RULES_FILENAME)
        if (hotOverride.exists() && hotOverride.length() > 16) {
            return runCatching { hotOverride.readText() }.getOrElse { readBundledAsset() }
        }
        return readBundledAsset()
    }

    private fun readBundledAsset(): String {
        return runCatching {
            context.assets.open(RULES_FILENAME).bufferedReader().use { it.readText() }
        }.getOrElse { "{}" }
    }

    /**
     * Runs offline Chinese OCR on [screenBitmap] (typically upper 70% where enemy skill popups render),
     * filters out greyed-out bypassed enemy skills, and returns structured WaveShieldChips.
     */
    suspend fun recognizeEnemySkills(screenBitmap: Bitmap): ParsedEnemySkillResult {
        val cropH = (screenBitmap.height * 0.72f).toInt().coerceIn(64, screenBitmap.height)
        val roi = Bitmap.createBitmap(screenBitmap, 0, 0, screenBitmap.width, cropH)
        try {
            val visionText = runMlKitOcr(roi)
            val activeLines = ArrayList<String>()
            var skippedGrey = 0

            for (block in visionText.textBlocks) {
                for (line in block.lines) {
                    val text = line.text.trim()
                    if (text.length < 2) continue
                    val box = line.boundingBox
                    if (box != null && isGreyedOutIgnoredSkill(roi, box)) {
                        skippedGrey++
                        continue
                    }
                    activeLines.add(text)
                }
            }

            val joined = activeLines.joinToString("\n")
            return getParser().parse(joined, skippedGreyLines = skippedGrey)
        } finally {
            if (!roi.isRecycled) roi.recycle()
        }
    }

    /**
     * Parse plain text directly (e.g. from Dashboard OCR Rule Tester without capturing screen).
     */
    fun parseText(rawText: String): ParsedEnemySkillResult = getParser().parse(rawText)

    private suspend fun runMlKitOcr(bitmap: Bitmap): Text = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { text ->
                if (cont.isActive) cont.resume(text)
            }
            .addOnFailureListener {
                if (cont.isActive) cont.resume(Text("", emptyList<Text.TextBlock>()))
            }
    }

    /**
     * Tower of Saviors 2025.41 / 2026.2 UI marks enemy skills already ignored/bypassed by your team
     * in muted low-luminance grey (~110..155 peak brightness, near-zero saturation) instead of crisp
     * bright white/gold/red active skill text (> 185 luminance or vivid colored highlight).
     */
    private fun isGreyedOutIgnoredSkill(bitmap: Bitmap, rect: Rect): Boolean {
        val left = rect.left.coerceIn(0, bitmap.width - 1)
        val top = rect.top.coerceIn(0, bitmap.height - 1)
        val right = rect.right.coerceIn(left + 1, bitmap.width)
        val bottom = rect.bottom.coerceIn(top + 1, bitmap.height)
        val w = right - left
        val h = bottom - top
        if (w < 12 || h < 8) return false

        val stepX = max(1, w / 28)
        val stepY = max(1, h / 10)
        val lums = ArrayList<Int>(280)
        var vividCount = 0
        var total = 0

        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val argb = bitmap.getPixel(x, y)
                val r = (argb ushr 16) and 0xFF
                val g = (argb ushr 8) and 0xFF
                val b = argb and 0xFF
                val maxCh = max(r, max(g, b))
                val minCh = min(r, min(g, b))
                val lum = (r * 30 + g * 59 + b * 11) / 100
                lums.add(lum)
                if (maxCh >= 150 && (maxCh - minCh) >= 48) {
                    vividCount++
                }
                total++
                x += stepX
            }
            y += stepY
        }
        if (lums.size < 16 || total == 0) return false

        lums.sort()
        val p95Lum = lums[(lums.size * 95) / 100]
        val p50Lum = lums[lums.size / 2]
        val vividRatio = vividCount.toFloat() / total

        // Dark background popup with muted grey foreground text (not bright white >=185 and not vivid gold/red)
        return p50Lum < 95 && p95Lum in 85..162 && vividRatio < 0.05f
    }

    companion object {
        const val RULES_FILENAME = "tos_enemy_skill_rules.json"
    }
}
