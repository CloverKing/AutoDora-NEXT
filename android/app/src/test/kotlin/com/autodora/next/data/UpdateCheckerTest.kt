package com.autodora.next.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun normalizeVersionStripsLeadingVAndWhitespace() {
        assertEquals("0.3.3", UpdateChecker.normalizeVersion("v0.3.3"))
        assertEquals("1.0.0", UpdateChecker.normalizeVersion("  V1.0.0  "))
        assertEquals("0.4.1", UpdateChecker.normalizeVersion("0.4.1"))
    }

    @Test
    fun compareSemVerOrdersMajorMinorPatchCorrectly() {
        assertEquals(0, UpdateChecker.compareSemVer("v0.3.3", "0.3.3"))
        assertTrue(UpdateChecker.compareSemVer("v0.3.4", "0.3.3") > 0)
        assertTrue(UpdateChecker.compareSemVer("v0.4.0", "0.3.9") > 0)
        assertTrue(UpdateChecker.compareSemVer("v1.0.0", "0.9.9") > 0)
        assertTrue(UpdateChecker.compareSemVer("v0.3.10", "0.3.9") > 0)
        assertTrue(UpdateChecker.compareSemVer("0.3.2", "v0.3.3") < 0)
        assertEquals(0, UpdateChecker.compareSemVer("v0.3.3-beta.1", "0.3.3"))
    }
}
