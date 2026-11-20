package com.autodora.next.vision

import org.junit.Assert.*
import org.junit.Test

class OfficialEnemyIconMatcherTest {

    @Test
    fun `matches all key official ToS enemy shield icon templates accurately under brightness shift`() {
        assertEquals(44, OfficialEnemyIconTemplates.templates.size)

        val keyIds = listOf(
            "ICON051" to "puzzle_shield",
            "ICON067" to "exact_first_combo",
            "ICON105" to "min_combo",
            "ICON094" to "require_elements",
            "ICON190" to "require_elements",
            "ICON868" to "cross_shield",
            "ICON107" to "forbid_all",
            "ICON111" to "forbid_all",
            "ICON016" to "zero_trail_burning",
            "ICON1427" to "digital_orb_count"
        )

        for ((iconId, expectedChipId) in keyIds) {
            val tmpl = OfficialEnemyIconTemplates.templates.first { it.iconId == iconId }
            // Simulate realistic -12% display dimming / compression variance
            val dimmed = IntArray(64) { i ->
                val rgb = tmpl.rgb8x8[i]
                val r = (((rgb ushr 16) and 0xFF) * 88 / 100).coerceIn(0, 255)
                val g = (((rgb ushr 8) and 0xFF) * 88 / 100).coerceIn(0, 255)
                val b = ((rgb and 0xFF) * 88 / 100).coerceIn(0, 255)
                (r shl 16) or (g shl 8) or b
            }
            val hit = EnemyStatusIconMatcher.matchPatchRgb8x8(dimmed)
            assertNotNull("Expected match for $iconId", hit)
            assertEquals("Wrong icon for $iconId", iconId, hit!!.template.iconId)
            assertEquals("Wrong chipId for $iconId", expectedChipId, hit.template.chipId)
            assertTrue("Score too low for $iconId: ${hit.score}", hit.score >= 0.90f)
        }
    }

    @Test
    fun `rejects flat dark background patch without false positives`() {
        val darkPatch = IntArray(64) { 0x16161A }
        assertNull(EnemyStatusIconMatcher.matchPatchRgb8x8(darkPatch))
    }
}
