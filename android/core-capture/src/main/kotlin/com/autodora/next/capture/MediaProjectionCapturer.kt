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
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

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
                }
            } catch (_: Exception) {
            }

            val img = latestImage ?: return@synchronized null
            return@synchronized imageToBitmap(img)
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
    }
}
