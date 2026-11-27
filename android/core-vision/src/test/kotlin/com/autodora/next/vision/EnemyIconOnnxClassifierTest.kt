package com.autodora.next.vision

import org.junit.Assert.*
import org.junit.Test

class EnemyIconOnnxClassifierTest {

    private class GradientFrame(
        override val width: Int = 540,
        override val height: Int = 1200
    ) : VisionFrame {
        override fun getArgb(x: Int, y: Int): Int {
            return if (y < height / 2) 0xFF2080C0.toInt() else 0xFFFFFFFF.toInt()
        }
    }

    @Test
    fun `fillStripNchw crops upper battle band to 1x3x96x256 in NCHW planar RGB`() {
        val nchw = EnemyIconStripPreprocessor.fillStripNchw(GradientFrame())
        val planeSize = 96 * 256
        assertEquals(3 * planeSize, nchw.size)
        // Upper battle band lies strictly in y in [0.08..0.42] -> purely top half (0x20, 0x80, 0xC0)
        assertEquals(0x20 / 255f, nchw[0], 0.01f)
        assertEquals(0x80 / 255f, nchw[planeSize], 0.01f)
        assertEquals(0xC0 / 255f, nchw[2 * planeSize], 0.01f)
    }

    @Test
    fun `EnemyIconChipMapper maps non-parameterized safe shields and skips ambiguous numeric icons`() {
        val thMap = EnemyIconMetaConfig.DEFAULT_CLASSES.associateWith { 0.80f }.toMutableMap()
        thMap["ICON_FIVE_ELEMENTS"] = 1.01f
        val cfg = EnemyIconMetaConfig(
            classes = EnemyIconMetaConfig.DEFAULT_CLASSES,
            thresholds = thMap
        )
        val probs = FloatArray(EnemyIconMetaConfig.DEFAULT_CLASSES.size) { 0.10f }
        // Activate ICON_CROSS_SHIELD, ICON_EXACT_COMBO (ambiguous numeric -> must skip!), ICON_MONOCHROME_DISGUISE,
        // and verify fail-safe 1.01f suppresses even 0.999f probability on ICON_FIVE_ELEMENTS.
        probs[EnemyIconMetaConfig.DEFAULT_CLASSES.indexOf("ICON_CROSS_SHIELD")] = 0.92f
        probs[EnemyIconMetaConfig.DEFAULT_CLASSES.indexOf("ICON_EXACT_COMBO")] = 0.99f
        probs[EnemyIconMetaConfig.DEFAULT_CLASSES.indexOf("ICON_FIVE_ELEMENTS")] = 0.999f
        probs[EnemyIconMetaConfig.DEFAULT_CLASSES.indexOf("ICON_MONOCHROME_DISGUISE")] = 0.88f

        val res = EnemyIconChipMapper.mapDetections(probs, cfg)
        assertEquals(listOf("ICON_EXACT_COMBO", "ICON_CROSS_SHIELD", "ICON_MONOCHROME_DISGUISE"), res.detectedClasses)
        assertEquals(listOf("cross_shield"), res.chips.map { it.id })
        assertTrue(res.hints.hasMonochrome)
    }
}
