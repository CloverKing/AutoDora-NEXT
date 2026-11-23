package com.autodora.next.capture

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenCaptureSessionTest {

    private class FakeCapturer : ScreenCapturer {
        private val _isReady = MutableStateFlow(true)
        override val isReady: StateFlow<Boolean> = _isReady
        override val screenWidth: Int = 1080
        override val screenHeight: Int = 2400
        var releaseCount: Int = 0
            private set

        override suspend fun captureSingleFrame(): Bitmap? = null
        override suspend fun captureMultiFrames(count: Int, intervalMs: Long): List<Bitmap> = emptyList()
        override fun release() {
            releaseCount++
            _isReady.value = false
        }
    }

    @After
    fun tearDown() {
        ScreenCaptureSession.clear()
        ScreenCaptureSession.clearInitError()
    }

    @Test
    fun delayedOldOnStopCannotKillNewlyReplacedCapturer() {
        val oldCap = FakeCapturer()
        val newCap = FakeCapturer()

        ScreenCaptureSession.setCapturer(oldCap)
        assertSame(oldCap, ScreenCaptureSession.capturer.value)
        assertTrue(ScreenCaptureSession.isActive)

        // Replace with newly re-authorized capturer
        ScreenCaptureSession.setCapturer(newCap)
        assertEquals(1, oldCap.releaseCount)
        assertSame(newCap, ScreenCaptureSession.capturer.value)
        assertTrue(ScreenCaptureSession.isActive)

        // Simulate old MediaProjection.Callback.onStop() firing asynchronously after replacement
        val cleared = ScreenCaptureSession.clearIfCurrent(oldCap)
        assertFalse("Old capturer must NOT clear the newly active session", cleared)
        assertSame(newCap, ScreenCaptureSession.capturer.value)
        assertEquals(0, newCap.releaseCount)
        assertTrue(ScreenCaptureSession.isActive)
    }

    @Test
    fun clearIfCurrentReleasesActiveCapturerAndResetsState() {
        val cap = FakeCapturer()
        ScreenCaptureSession.setCapturer(cap)
        assertTrue(ScreenCaptureSession.isActive)

        val cleared = ScreenCaptureSession.clearIfCurrent(cap)
        assertTrue(cleared)
        assertNull(ScreenCaptureSession.capturer.value)
        assertFalse(ScreenCaptureSession.isActive)
        assertEquals(1, cap.releaseCount)
    }

    @Test
    fun initErrorClearsAutomaticallyWhenNewCapturerSucceeds() {
        ScreenCaptureSession.reportInitError("token expired")
        assertEquals("token expired", ScreenCaptureSession.lastInitError.value)

        val cap = FakeCapturer()
        ScreenCaptureSession.setCapturer(cap)
        assertNull(ScreenCaptureSession.lastInitError.value)
    }
}
