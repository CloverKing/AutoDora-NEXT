package com.autodora.next.vision

import com.autodora.next.model.BoardGeometry
import org.junit.Assert.*
import org.junit.Test

class BoardMasterOnnxClassifierTest {

    private class SolidPairFrame(
        override val width: Int,
        override val height: Int,
        private val argb: Int
    ) : VisionFrame {
        override fun getArgb(x: Int, y: Int): Int = argb
    }

    @Test
    fun `fillDualBoardNchw packs dynamic 6x5 and 6x8 NCHW tensors with brightness gain and ordering`() {
        val geom6x5 = BoardGeometry(
            leftPx = 0f,
            topPx = 0f,
            rightPx = 360f,
            bottomPx = 300f,
            cols = 6,
            rows = 5
        )
        // Frame A = pure red (100, 0, 0), Frame B = pure blue (0, 0, 128), gain = 2.0f -> red becomes 200/255
        val fA = SolidPairFrame(400, 400, 0xFF640000.toInt())
        val fB = SolidPairFrame(400, 400, 0xFF000080.toInt())

        val arr5 = DualBoardPreprocessor.fillDualBoardNchw(fA, fB, geom6x5, brightnessGain = 2.0f)
        val plane5 = 5 * 64 * 6 * 64
        assertEquals(6 * plane5, arr5.size)
        // Channel 0 (R_a) ~ 200/255, Channel 1 (G_a) = 0, Channel 5 (B_b) = clamp(256/255) = 1.0f
        assertEquals(200f / 255f, arr5[0], 0.01f)
        assertEquals(0f, arr5[plane5 + 100], 0.001f)
        assertEquals(1.0f, arr5[5 * plane5 + 500], 0.001f)

        val geom6x8 = geom6x5.copy(rows = 8, bottomPx = 480f)
        val f8A = SolidPairFrame(400, 600, 0xFF336699.toInt())
        val arr8 = DualBoardPreprocessor.fillDualBoardNchw(f8A, f8A, geom6x8, brightnessGain = 1.0f)
        val plane8 = 8 * 64 * 6 * 64
        assertEquals(6 * plane8, arr8.size)
        assertEquals(0x33 / 255f, arr8[0], 0.01f)
        assertEquals(0x66 / 255f, arr8[plane8], 0.01f)
        assertEquals(0x99 / 255f, arr8[2 * plane8], 0.01f)
    }

    @Test
    fun `parseFusionConfigJson safely loads calibrated hazard and overlay thresholds`() {
        val sampleJson = """
            {
              "hazard_thresholds": { "BLACK_WHITE": 0.65, "ICE_SEALED": 0.72 },
              "tile_overlay_thresholds": { "TRAFFIC_LIGHT_MARKER": 0.75, "STORM": 0.88 }
            }
        """.trimIndent()
        val cfg = DualBoardPreprocessor.parseFusionConfigJson(sampleJson)
        assertEquals(0.65f, cfg.hazardThresholds[OrbNetSchema.IDX_HAZ_BLACK_WHITE], 0.001f)
        assertEquals(0.72f, cfg.hazardThresholds[OrbNetSchema.IDX_HAZ_ICE_SEALED], 0.001f)
        assertEquals(0.88f, cfg.overlayThresholds[OrbNetSchema.IDX_OV_STORM], 0.001f)
    }
}
