package com.autodora.next.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Android 14/15 compliant MediaProjection screen capturer.
 * Keeps a single persistent VirtualDisplay + ImageReader to avoid Android 14+ single-use token errors
 * and handles rowStride padding cleanly on Qualcomm Adreno (e.g. Xiaomi 15) & Mali GPUs.
 */
class MediaProjectionCapturer(
    context: Context,
    private val mediaProjection: MediaProjection,
    private val onProjectionStopped: (() -> Unit)? = null
) : ScreenCapturer {

    private val appContext = context.applicationContext
    private val _isReady = MutableStateFlow(false)
    override val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    override val screenWidth: Int
    override val screenHeight: Int
    private val densityDpi: Int

    private val handlerThread = HandlerThread("AutoDora-ScreenCapture").apply { start() }
    private val handler = Handler(handlerThread.looper)

    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val latestFrameLock = Any()
    private val latestBitmapRef = AtomicReference<Bitmap?>(null)
    private val pendingCaptureRef = AtomicReference<CompletableDeferred<Bitmap?>?>(null)
    private val lastIdleConvertMs = AtomicLong(0L)

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection stopped by system or user")
            _isReady.value = false
            pendingCaptureRef.getAndSet(null)?.complete(null)
            cleanupVirtualDisplay()
            onProjectionStopped?.invoke()
        }
    }

    init {
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
            densityDpi = appContext.resources.configuration.densityDpi
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            screenWidth = dm.widthPixels
            screenHeight = dm.heightPixels
            densityDpi = dm.densityDpi
        }

        // Android 14+ REQUIRES registering callback BEFORE createVirtualDisplay!
        mediaProjection.registerCallback(projectionCallback, handler)
        setupVirtualDisplay()
    }

    private fun setupVirtualDisplay() {
        val reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ r ->
            val image = try {
                r.acquireLatestImage()
            } catch (e: Exception) {
                Log.w(TAG, "acquireLatestImage failed: ${e.message}")
                null
            } ?: return@setOnImageAvailableListener

            try {
                // On 120Hz devices (e.g. Xiaomi 15), skip heavy ARGB Bitmap allocation while idle
                // unless a capture was requested or >= 450ms elapsed since last cached fallback frame.
                val pending = pendingCaptureRef.getAndSet(null)
                val now = System.currentTimeMillis()
                val shouldConvert = pending != null ||
                    latestBitmapRef.get() == null ||
                    (now - lastIdleConvertMs.get() >= IDLE_CACHE_INTERVAL_MS)

                if (!shouldConvert) {
                    return@setOnImageAvailableListener
                }

                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * screenWidth

                val paddedWidth = screenWidth + rowPadding / pixelStride
                val fullBmp = Bitmap.createBitmap(paddedWidth, screenHeight, Bitmap.Config.ARGB_8888)
                fullBmp.copyPixelsFromBuffer(buffer)

                val cropped = if (paddedWidth != screenWidth) {
                    val c = Bitmap.createBitmap(fullBmp, 0, 0, screenWidth, screenHeight)
                    fullBmp.recycle()
                    c
                } else {
                    fullBmp
                }

                lastIdleConvertMs.set(now)
                val deliveredCopy = if (pending != null) {
                    cropped.copy(Bitmap.Config.ARGB_8888, false)
                } else {
                    null
                }

                synchronized(latestFrameLock) {
                    val prev = latestBitmapRef.getAndSet(cropped)
                    if (prev != null && prev !== cropped && !prev.isRecycled) {
                        prev.recycle()
                    }
                }
                pending?.complete(deliveredCopy)
                if (!_isReady.value) {
                    _isReady.value = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Frame conversion error", e)
                pendingCaptureRef.getAndSet(null)?.complete(null)
            } finally {
                image.close()
            }
        }, handler)

        imageReader = reader
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "AutoDora-VirtualDisplay",
            screenWidth,
            screenHeight,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler
        )
        _isReady.value = true
        Log.i(TAG, "VirtualDisplay active: ${screenWidth}x${screenHeight} @ ${densityDpi}dpi")
    }

    override suspend fun captureSingleFrame(): Bitmap? {
        if (!_isReady.value) return null

        // Request an immediate fresh frame from VirtualDisplay; fall back to cached frame if display is completely static
        val deferred = CompletableDeferred<Bitmap?>()
        pendingCaptureRef.getAndSet(deferred)?.complete(null)
        val fresh = withTimeoutOrNull(95L) { deferred.await() }
        if (fresh != null) return fresh
        pendingCaptureRef.compareAndSet(deferred, null)

        var retries = 0
        while (latestBitmapRef.get() == null && retries < 12 && _isReady.value) {
            delay(25)
            retries++
        }
        synchronized(latestFrameLock) {
            val bmp = latestBitmapRef.get() ?: return null
            if (bmp.isRecycled) return null
            return bmp.copy(Bitmap.Config.ARGB_8888, false)
        }
    }

    override suspend fun captureMultiFrames(count: Int, intervalMs: Long): List<Bitmap> {
        val safeCount = count.coerceIn(1, 5)
        val frames = ArrayList<Bitmap>(safeCount)
        for (i in 0 until safeCount) {
            captureSingleFrame()?.let { frames.add(it) }
            if (i + 1 < safeCount) {
                delay(intervalMs)
            }
        }
        return frames
    }

    private fun cleanupVirtualDisplay() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null
        synchronized(latestFrameLock) {
            latestBitmapRef.getAndSet(null)?.let {
                if (!it.isRecycled) it.recycle()
            }
        }
    }

    override fun release() {
        _isReady.value = false
        pendingCaptureRef.getAndSet(null)?.complete(null)
        try {
            mediaProjection.unregisterCallback(projectionCallback)
        } catch (_: Exception) {
        }
        cleanupVirtualDisplay()
        try {
            mediaProjection.stop()
        } catch (_: Exception) {
        }
        handlerThread.quitSafely()
    }

    companion object {
        private const val TAG = "MediaProjCapturer"
        private const val IDLE_CACHE_INTERVAL_MS = 450L
    }
}
