package com.autodora.next.vision

import com.autodora.next.model.ShieldCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnemyBadgeDigitExtractorTest {

    @Test
    fun `whitelist CTC decoder ignores higher-scoring kanji and decodes multi-digit numbers cleanly`() {
        val vocab = listOf("blank", "福", "成", "0", "1", "2", "5", "7", "9", " ")
        val (wlIdx, wlChars) = EnemyBadgeDigitExtractor.buildDigitWhitelistIndices(vocab)
        assertTrue(wlIdx.size >= 6)

        // Sequence of 5 timesteps: ['1' + huge kanji '成', '1', blank, '7' + huge kanji '福', blank]
        val vSize = vocab.size
        val seq = 5
        val probs = FloatArray(seq * vSize)
        // t=0: '成' has 0.70, '1' has 0.25, blank 0.02
        probs[0 * vSize + 2] = 0.70f
        probs[0 * vSize + 4] = 0.25f
        probs[0 * vSize + 0] = 0.02f
        // t=1: repeated '1'
        probs[1 * vSize + 4] = 0.80f
        probs[1 * vSize + 0] = 0.05f
        // t=2: blank separator
        probs[2 * vSize + 0] = 0.90f
        // t=3: '福' has 0.60, '7' has 0.35, blank 0.03
        probs[3 * vSize + 1] = 0.60f
        probs[3 * vSize + 7] = 0.35f
        probs[3 * vSize + 0] = 0.03f
        // t=4: blank
        probs[4 * vSize + 0] = 0.95f

        val (decoded, conf) = EnemyBadgeDigitExtractor.ctcDecodeDigitsWhitelist(probs, seq, vSize, wlIdx, wlChars)
        assertEquals("17", decoded)
        assertTrue("Expected high conditional digit confidence, got $conf", conf >= 0.85f)
    }

    @Test
    fun `resolveParameterizedBadgeChip maps all 5 numeric shield classes and rejects invalid ranges`() {
        val minCombo = EnemyBadgeDigitExtractor.resolveParameterizedBadgeChip(
            "ICON_MIN_COMBO",
            listOf("5" to 0.99f, "5" to 0.98f)
        )
        assertNotNull(minCombo)
        assertEquals("min_combo", minCombo!!.id)
        assertEquals(5, minCombo.paramInt)
        assertEquals(ShieldCategory.COMBO, minCombo.category)
        assertTrue(minCombo.autoDetected)

        val num17 = EnemyBadgeDigitExtractor.resolveParameterizedBadgeChip(
            "ICON_DIGITAL_COUNT",
            listOf("15" to 0.60f, "17" to 0.99f, "17" to 0.85f)
        )
        assertNotNull(num17)
        assertEquals("digital_orb_count", num17!!.id)
        assertEquals(17, num17.paramInt)

        val exact7 = EnemyBadgeDigitExtractor.resolveParameterizedBadgeChip(
            "ICON_EXACT_COMBO",
            listOf("7" to 0.92f)
        )
        assertEquals("exact_first_combo", exact7?.id)
        assertEquals(7, exact7?.paramInt)

        // Out of range (e.g. 99 combo or 1 orb digital count) must be rejected
        assertNull(EnemyBadgeDigitExtractor.resolveParameterizedBadgeChip("ICON_MIN_COMBO", listOf("99" to 0.99f)))
        assertNull(EnemyBadgeDigitExtractor.resolveParameterizedBadgeChip("ICON_DIGITAL_COUNT", listOf("1" to 0.99f)))
    }

    @Test
    fun `buildCleanDigitRecTensor isolates black-outlined white stroke and suppresses tiny sparks`() {
        val w = 60
        val h = 30
        val pixels = IntArray(w * h) { 0xFF3A2848.toInt() } // Muted purple boss background (V ~ 0.28)
        // Draw a vertical white '1' (x=18..21, y=6..23) surrounded by a 2px black outline (x=16..23, y=4..25)
        for (y in 4..25) {
            for (x in 16..23) {
                pixels[y * w + x] = 0xFF08080A.toInt()
            }
        }
        for (y in 6..23) {
            for (x in 18..21) {
                pixels[y * w + x] = 0xFFF8F8FA.toInt()
            }
        }
        // Add a tiny un-outlined / short spark at (42, 8..9) that must be filtered out
        pixels[8 * w + 42] = 0xFFFFFFFF.toInt()
        pixels[9 * w + 42] = 0xFFFFFFFF.toInt()

        val vf = object : VisionFrame {
            override val width: Int = w
            override val height: Int = h
            override fun getArgb(x: Int, y: Int): Int = pixels[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)]
        }

        val built = EnemyBadgeDigitExtractor.buildCleanDigitRecTensor(vf, 0, 0, w, h)
        assertNotNull(built)
        val (nchw, recW) = built!!
        assertEquals(3 * EnemyBadgeDigitExtractor.REC_H * recW, nchw.size)
        assertTrue("Expected bright normalized foreground pixels > 0.5", nchw.any { it > 0.5f })
        assertFalse(EnemyBadgeDigitExtractor.hasNumericBadgeCandidate(listOf("ICON_PUZZLE")))
        assertTrue(EnemyBadgeDigitExtractor.hasNumericBadgeCandidate(listOf("ICON_PUZZLE", "ICON_DIGITAL_COUNT")))
    }
}
