package com.autodora.next.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInScreenRecorderTest {

    @Test
    fun `computeRecordingDimensions preserves aspect ratio and aligns to 16px`() {
        // Standard 1080x2400 phone (e.g. Pixel / Emulator) -> exact 720x1600
        val (w1, h1) = RecordingFormatUtils.computeRecordingDimensions(1080, 2400)
        assertEquals(720, w1)
        assertEquals(1600, h1)
        assertEquals(0, w1 % 16)
        assertEquals(0, h1 % 16)

        // Xiaomi 15 1200x2670 -> 720x1600 (16px aligned)
        val (w2, h2) = RecordingFormatUtils.computeRecordingDimensions(1200, 2670)
        assertEquals(720, w2)
        assertEquals(1600, h2)
        assertEquals(0, w2 % 16)
        assertEquals(0, h2 % 16)

        // Smaller screen 540x960 does not upscale above native size, aligns cleanly to 16px
        val (w3, h3) = RecordingFormatUtils.computeRecordingDimensions(540, 960)
        assertTrue(w3 <= 544)
        assertEquals(0, w3 % 16)
        assertEquals(0, h3 % 16)
    }

    @Test
    fun `formatDuration formats mm-ss and hh-mm-ss accurately`() {
        assertEquals("00:00", RecordingFormatUtils.formatDuration(0L))
        assertEquals("00:09", RecordingFormatUtils.formatDuration(9_400L))
        assertEquals("01:25", RecordingFormatUtils.formatDuration(85_000L))
        assertEquals("1:02:05", RecordingFormatUtils.formatDuration(3_725_000L))
    }

    @Test
    fun `buildGalleryDisplayName produces valid mp4 filename`() {
        val name = RecordingFormatUtils.buildGalleryDisplayName(1_732_442_400_000L)
        assertTrue(name.startsWith("AutoDora_"))
        assertTrue(name.endsWith(".mp4"))
    }
}
