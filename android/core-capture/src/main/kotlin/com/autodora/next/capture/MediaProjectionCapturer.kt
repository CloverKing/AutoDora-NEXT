package com.autodora.next.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Android 14/15 compliant MediaProjection screen capturer with Zero-Copy Idle Frame Holding:
 *  - Keeps a single persistent VirtualDisplay + ImageReader (maxImages = 4) to avoid Android 14+ single-use token errors
 *  - While idle at 60/120Hz (e.g. Xiaomi 15), swaps only the latest native Image reference (0 Bitmap allocations, 0 heap churn)
 *    while ALWAYS preserving the exact latest settled screen frame (never stale!).
 *  - Converts to ARGB_8888 Bitmap strictly on demand in captureSingleFrame() with Qualcomm rowStride padding support.
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
    private val imageLock = Any()
    private var latestImage: Image? = null
    @Volatile
    private var lastImageTimestampMs: Long = 0L

    override val lastFrameAgeMs: Long
        get() {
            val ts = lastImageTimestampMs
            return if (ts <= 0L) Long.MAX_VALUE else (SystemClock.elapsedRealtime() - ts).coerceAtLeast(0L)
        }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection stopped by system or user")
            _isReady.value = false
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
        val reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 4)
        reader.setOnImageAvailableListener({ r ->
            val incoming = try {
                r.acquireLatestImage()
            } catch (e: Exception) {
                Log.w(TAG, "acquireLatestImage failed: ${e.message}")
                null
            } ?: return@setOnImageAvailableListener

            synchronized(imageLock) {
                if (!_isReady.value) {
                    incoming.close()
                    return@synchronized
                }
                latestImage?.close()
                latestImage = incoming
                lastImageTimestampMs = SystemClock.elapsedRealtime()
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

    override suspend fun captureSingleFrame(): Bitmap? = withContext(Dispatchers.Default) {
        if (!_isReady.value) return@withContext null

        // Wait briefly if the very first frame hasn't arrived yet
        var retries = 0
        while (retries < 15 && _isReady.value) {
            val hasFrame = synchronized(imageLock) { latestImage != null }
            if (hasFrame) break
            delay(25)
            retries++
        }

        synchronized(imageLock) {
            // Pull one last time if an even fresher image just landed in the queue
            try {
                val fresher = imageReader?.acquireLatestImage()
                if (fresher != null) {
                    latestImage?.close()
                    latestImage = fresher
                    lastImageTimestampMs = SystemClock.elapsedRealtime()
                }
            } catch (_: Exception) {
            }

            val img = latestImage ?: return@synchronized null
            return@synchronized imageToBitmap(img)
        }
    }

    override suspend fun captureBandBitmap(yStart: Int, yEnd: Int): BandBitmap? = withContext(Dispatchers.Default) {
        if (!_isReady.value) return@withContext null

        var retries = 0
        while (retries < 10 && _isReady.value) {
            val hasFrame = synchronized(imageLock) { latestImage != null }
            if (hasFrame) break
            delay(16)
            retries++
        }

        synchronized(imageLock) {
            try {
                val fresher = imageReader?.acquireLatestImage()
                if (fresher != null) {
                    latestImage?.close()
                    latestImage = fresher
                    lastImageTimestampMs = SystemClock.elapsedRealtime()
                }
            } catch (_: Exception) {
            }

            val img = latestImage ?: return@synchronized null
            return@synchronized imageToBandBitmap(img, yStart, yEnd)
        }
    }

    private fun imageToBandBitmap(image: Image, yStart: Int, yEnd: Int): BandBitmap? {
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * screenWidth
            val paddedWidth = screenWidth + rowPadding / pixelStride

            val safeY0 = yStart.coerceIn(0, (screenHeight - 1).coerceAtLeast(0))
            var safeY1 = yEnd.coerceIn(safeY0 + 1, screenHeight)
            var cropH = safeY1 - safeY0
            var neededBytes = cropH * rowStride

            // On some Qualcomm GPU buffers the final row omits trailing rowStride padding; trim 1px if needed
            if (safeY0 * rowStride + neededBytes > buffer.capacity() && cropH > 2) {
                safeY1 -= 1
                cropH = safeY1 - safeY0
                neededBytes = cropH * rowStride
            }

            if (cropH <= 0 || safeY0 * rowStride + neededBytes > buffer.capacity()) {
                val full = imageToBitmap(image) ?: return null
                return BandBitmap(full, screenWidth, screenHeight, 0)
            }

            val dup = buffer.duplicate()
            dup.position(safeY0 * rowStride)
            dup.limit(safeY0 * rowStride + neededBytes)
            val slice = dup.slice()

            val bandBmp = Bitmap.createBitmap(paddedWidth, cropH, Bitmap.Config.ARGB_8888)
            bandBmp.copyPixelsFromBuffer(slice)
            BandBitmap(bandBmp, screenWidth, screenHeight, safeY0)
        } catch (e: Exception) {
            Log.e(TAG, "imageToBandBitmap error, falling back to full frame", e)
            val full = imageToBitmap(image) ?: return null
            BandBitmap(full, screenWidth, screenHeight, 0)
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            buffer.rewind()
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * screenWidth
            val paddedWidth = screenWidth + rowPadding / pixelStride

            val fullBmp = Bitmap.createBitmap(paddedWidth, screenHeight, Bitmap.Config.ARGB_8888)
            fullBmp.copyPixelsFromBuffer(buffer)
            buffer.rewind()

            if (paddedWidth != screenWidth) {
                val cropped = Bitmap.createBitmap(fullBmp, 0, 0, screenWidth, screenHeight)
                fullBmp.recycle()
                cropped
            } else {
                fullBmp
            }
        } catch (e: Exception) {
            Log.e(TAG, "imageToBitmap conversion error", e)
            null
        }
    }

    override fun clearCachedFrame() {
        synchronized(imageLock) {
            try {
                // Drain any pending pre-hide image sitting in ImageReader before discarding latestImage
                val stale = imageReader?.acquireLatestImage()
                stale?.close()
            } catch (_: Exception) {
            }
            latestImage?.close()
            latestImage = null
            lastImageTimestampMs = 0L
        }
    }

    override suspend fun tryRecoverSurface(): Boolean = withContext(Dispatchers.Default) {
        if (!_isReady.value) return@withContext false
        val vd = virtualDisplay ?: return@withContext false
        Log.w(TAG, "Attempting VirtualDisplay ImageReader surface recovery (lastFrameAgeMs=$lastFrameAgeMs)")
        try {
            val newReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 4)
            newReader.setOnImageAvailableListener({ r ->
                val incoming = try {
                    r.acquireLatestImage()
                } catch (e: Exception) {
                    Log.w(TAG, "acquireLatestImage (recovered) failed: ${e.message}")
                    null
                } ?: return@setOnImageAvailableListener

                synchronized(imageLock) {
                    if (!_isReady.value) {
                        incoming.close()
                        return@synchronized
                    }
                    latestImage?.close()
                    latestImage = incoming
                    lastImageTimestampMs = SystemClock.elapsedRealtime()
                }
            }, handler)

            val oldReader: ImageReader?
            synchronized(imageLock) {
                oldReader = imageReader
                latestImage?.close()
                latestImage = null
                lastImageTimestampMs = 0L
                imageReader = newReader
            }
            // Swap the live surface onto the existing VirtualDisplay (Android 14/15/16 safe without re-consent!)
            vd.resize(screenWidth, screenHeight, densityDpi)
            vd.setSurface(newReader.surface)
            try {
                oldReader?.setOnImageAvailableListener(null, null)
                oldReader?.close()
            } catch (_: Exception) {
            }

            // Wait up to 480ms for a fresh compositor frame to land on the recovered surface
            var waited = 0
            while (waited < 16 && _isReady.value) {
                if (synchronized(imageLock) { latestImage != null }) {
                    Log.i(TAG, "VirtualDisplay surface recovery succeeded in ${waited * 30}ms")
                    return@withContext true
                }
                delay(30L)
                waited++
            }
            Log.w(TAG, "VirtualDisplay surface recovery timed out waiting for frame")
            false
        } catch (e: Exception) {
            Log.e(TAG, "VirtualDisplay surface recovery failed", e)
            false
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
        synchronized(imageLock) {
            latestImage?.close()
            latestImage = null
        }
        imageReader?.close()
        imageReader = null
    }

    override fun release() {
        _isReady.value = false
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

        /**
         * Fast sparse check (< 0.05ms) detecting all-black / privacy-blanked MediaProjection frames
         * (e.g., when Android 14/15/16 HyperOS suspends a long-running VirtualDisplay or another
         * screen recorder takes over the projection slot).
         */
        fun isBlankOrBlackFrame(bitmap: Bitmap): Boolean {
            val w = bitmap.width
            val h = bitmap.height
            if (w < 16 || h < 16) return true
            var dark = 0
            var total = 0
            val cols = 8
            val rows = 10
            for (ry in 0 until rows) {
                val y = ((ry + 0.5f) / rows * h).toInt().coerceIn(0, h - 1)
                for (rx in 0 until cols) {
                    val x = ((rx + 0.5f) / cols * w).toInt().coerceIn(0, w - 1)
                    val argb = bitmap.getPixel(x, y)
                    val mx = max((argb ushr 16) and 0xFF, max((argb ushr 8) and 0xFF, argb and 0xFF))
                    if (mx <= 8) dark++
                    total++
                }
            }
            return total > 0 && dark.toFloat() / total >= 0.96f
        }
    }
}
