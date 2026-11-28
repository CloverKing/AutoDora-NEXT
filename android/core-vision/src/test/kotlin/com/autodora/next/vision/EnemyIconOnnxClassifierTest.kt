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
    fun `fillStripNchw crops upper battle band to 1x3x160x256 in NCHW planar RGB`() {
        val nchw = EnemyIconStripPreprocessor.fillStripNchw(GradientFrame())
        val planeSize = EnemyIconStripPreprocessor.STRIP_H * EnemyIconStripPreprocessor.STRIP_W
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
        assertFalse(res.hints.hasElectrified)
        assertFalse(res.hints.hasFrozenOrPetrified)
    }

    @Test
    fun `fillStripNchw masks out overlapping floating UI excludeRect and rejects peaks inside excludeRect`() {
        val brightTopFrame = object : VisionFrame {
            override val width: Int = 576
            override val height: Int = 1280
            override fun getArgb(x: Int, y: Int): Int = 0xFFF0D040.toInt()
        }
        val exRect = intArrayOf(0, 0, 576, 600)
        val nchw = EnemyIconStripPreprocessor.fillStripNchw(brightTopFrame, exRect)
        // Entire upper strip [0.08..0.42] falls inside y in [0..600], so all pixels must equal neutral dark mask (18, 20, 26)
        assertEquals(18f / 255f, nchw[0], 0.01f)

        val thMap = EnemyIconMetaConfig.DEFAULT_CLASSES.associateWith { 0.75f }
        val cfg = EnemyIconMetaConfig(EnemyIconMetaConfig.DEFAULT_CLASSES, thMap)
        val probs = FloatArray(EnemyIconMetaConfig.DEFAULT_CLASSES.size) { 0.1f }
        val tIdx = EnemyIconMetaConfig.DEFAULT_CLASSES.indexOf("ICON_T_SHAPE")
        probs[tIdx] = 0.95f
        val peaks = Array(EnemyIconMetaConfig.DEFAULT_CLASSES.size) { floatArrayOf(4f, 2f) }
        val res = EnemyIconChipMapper.mapDetections(probs, cfg, peaks, brightTopFrame, exRect)
        assertTrue("Peak inside floating toolbar excludeRect must be suppressed!", res.chips.isEmpty())
    }
}
