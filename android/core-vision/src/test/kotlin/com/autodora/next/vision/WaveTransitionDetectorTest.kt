package com.autodora.next.vision

import org.junit.Assert.*
import org.junit.Test

class WaveTransitionDetectorTest {

    @Test
    fun `detects sustained upper-zone shift after 4 confirming frames and ignores brief 1-frame flash`() {
        val detector = WaveTransitionDetector()
        val wave1Hash = 0x0000_0000_0000_0000L
        val wave2Hash = 0x0F0F_0F0F_0F0F_0F0FL // Hamming = 32 bits vs wave1Hash

        // Lock baseline on wave 1 (2 stable ticks)
        assertFalse(detector.onBattleUpperZoneHash(wave1Hash))
        assertFalse(detector.onBattleUpperZoneHash(wave1Hash))

        // Brief 1-frame skill flash (Hamming = 32) followed by return to wave1Hash -> MUST NOT trigger wave change
        assertFalse(detector.onBattleUpperZoneHash(wave2Hash))
        assertFalse(detector.onBattleUpperZoneHash(wave1Hash))

        // True wave transition: 4 consecutive frames on wave2Hash -> triggers exactly on 4th tick
        assertFalse(detector.onBattleUpperZoneHash(wave2Hash))
        assertFalse(detector.onBattleUpperZoneHash(wave2Hash))
        assertFalse(detector.onBattleUpperZoneHash(wave2Hash))
        assertTrue(detector.onBattleUpperZoneHash(wave2Hash))

        // Subsequent settled ticks on wave2Hash do NOT re-trigger
        assertFalse(detector.onBattleUpperZoneHash(wave2Hash))
        assertFalse(detector.onBattleUpperZoneHash(wave2Hash))
    }
}
