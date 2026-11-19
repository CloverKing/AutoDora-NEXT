package com.autodora.next.capture

import android.graphics.Bitmap
import kotlinx.coroutines.flow.StateFlow

interface ScreenCapturer {
    val isReady: StateFlow<Boolean>
    val screenWidth: Int
    val screenHeight: Int

    suspend fun captureSingleFrame(): Bitmap?
    suspend fun captureMultiFrames(count: Int = 3, intervalMs: Long = 55L): List<Bitmap>
    fun release()
}
