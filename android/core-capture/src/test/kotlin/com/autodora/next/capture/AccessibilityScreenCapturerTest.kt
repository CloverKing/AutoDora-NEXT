package com.autodora.next.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityScreenCapturerTest {

    @Test
    fun `respects cooldown interval and retries once on RATE_LIMITED`() = runBlocking {
        var fakeTime = 1_000L
        val sleeps = mutableListOf<Long>()
        var attempts = 0
        val ready = MutableStateFlow(true)

        val capturer = AccessibilityScreenCapturer(
            isReady = ready,
            screenSizeProvider = { 1080 to 2400 },
            captureProvider = {
                attempts++
                if (attempts == 1) {
                    AccessibilityCaptureOutcome(null, AccessibilityCaptureStatus.RATE_LIMITED)
                } else {
                    AccessibilityCaptureOutcome(null, AccessibilityCaptureStatus.SUCCESS)
                }
            },
            minIntervalMs = 370L,
            retryDelayMs = 220L,
            clockMs = { fakeTime },
            sleepFn = { ms ->
                sleeps.add(ms)
                fakeTime += ms
            }
        )

        capturer.captureSingleFrame()
        assertEquals("Should retry exactly once on RATE_LIMITED", 2, attempts)
        assertEquals(listOf(220L), sleeps)
        assertFalse(capturer.lastPermissionError)

        // Immediate second call 100ms later must wait remaining 270ms cooldown
        fakeTime += 100L
        capturer.captureSingleFrame()
        assertEquals(3, attempts)
        assertEquals(listOf(220L, 270L), sleeps)
    }

    @Test
    fun `surfaces NO_PERMISSION failure and aborts multi-frame burst immediately`() = runBlocking {
        var attempts = 0
        val ready = MutableStateFlow(true)
        var fakeTime = 10_000L

        val capturer = AccessibilityScreenCapturer(
            isReady = ready,
            screenSizeProvider = { 1080 to 2400 },
            captureProvider = {
                attempts++
                AccessibilityCaptureOutcome(null, AccessibilityCaptureStatus.NO_PERMISSION)
            },
            clockMs = { fakeTime },
            sleepFn = { fakeTime += it }
        )

        val frames = capturer.captureMultiFrames(count = 4, intervalMs = 50L)
        assertTrue(frames.isEmpty())
        assertTrue(capturer.lastPermissionError)
        assertEquals("Should stop burst immediately on NO_PERMISSION", 1, attempts)
    }

    @Test
    fun `caps multi-frame burst to 2 frames and throttles background watcher`() = runBlocking {
        var attempts = 0
        var fakeTime = 20_000L
        val ready = MutableStateFlow(true)

        val capturer = AccessibilityScreenCapturer(
            isReady = ready,
            screenSizeProvider = { 1080 to 2400 },
            captureProvider = {
                attempts++
                AccessibilityCaptureOutcome(null, AccessibilityCaptureStatus.SUCCESS)
            },
            minIntervalMs = 370L,
            backgroundMinIntervalMs = 850L,
            clockMs = { fakeTime },
            sleepFn = { fakeTime += it }
        )

        // Burst request for 4 frames must cap at 2 calls
        capturer.captureMultiFrames(count = 4, intervalMs = 50L)
        assertEquals(2, attempts)

        // Background watcher call 400ms later (< 850ms) must skip cleanly with zero provider calls
        fakeTime += 400L
        capturer.captureBackgroundFrame()
        assertEquals("Background call within 850ms window must skip", 2, attempts)

        // After >= 850ms, background watcher succeeds
        fakeTime += 500L
        capturer.captureBackgroundFrame()
        assertEquals(3, attempts)
    }
}
