package com.autodora.next.capture

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Snapshot copy result for zero-allocation 30fps built-in screen recording
 * sharing the existing single MediaProjection VirtualDisplay.
 */
data class RecordingFrameCopy(
    val bitmap: Bitmap?,
    val frameSeq: Long,
    val contentWidth: Int,
    val contentHeight: Int,
    val changed: Boolean
)

/**
 * Frame source implemented by [MediaProjectionCapturer] so built-in recording reuses
 * the already-active single VirtualDisplay (100% Android 14/15/16 compliant — never calls
 * `MediaProjection.createVirtualDisplay()` twice!).
 */
interface RecordingFrameSource {
    val screenWidth: Int
    val screenHeight: Int
    val isReady: StateFlow<Boolean>
    fun copyLatestFrameForRecording(reusable: Bitmap?, lastSeq: Long): RecordingFrameCopy

    /**
     * Zero-copy GPU recording path (EGL SurfaceTexture -> MediaCodec Input Surface at locked 60/30fps).
     * Returns true when attached; false falls back to CPU Surface Canvas copy.
     */
    fun attachGpuRecorderSurface(
        codecSurface: Surface,
        width: Int,
        height: Int,
        targetFps: Int,
        onFrameEncoded: () -> Unit,
        onError: (String) -> Unit
    ): Boolean = false

    fun detachGpuRecorderSurface() {}
}

sealed class ScreenRecordingState {
    data object Idle : ScreenRecordingState()
    data class Recording(val startedAtElapsedMs: Long) : ScreenRecordingState()
    data object Saving : ScreenRecordingState()
}

sealed class RecordingSaveOutcome {
    data class Saved(
        val displayName: String,
        val contentUri: Uri?,
        val durationMs: Long,
        val sizeBytes: Long
    ) : RecordingSaveOutcome()

    data class Failed(val reason: String) : RecordingSaveOutcome()
}

/**
 * Pure-Kotlin utilities for H.264 recording dimensions, naming, and timer display (unit-tested on JVM).
 */
object RecordingFormatUtils {
    /**
     * Computes 16-pixel-aligned H.264-safe recording dimensions preserving aspect ratio,
     * capping the shorter edge at [maxShortSide] (default 720p) for crisp UI clarity & low CPU load.
     */
    fun computeRecordingDimensions(
        screenW: Int,
        screenH: Int,
        maxShortSide: Int = 720
    ): Pair<Int, Int> {
        if (screenW <= 0 || screenH <= 0) return 720 to 1280
        val shortSide = minOf(screenW, screenH)
        val scale = if (shortSide > maxShortSide) maxShortSide.toFloat() / shortSide.toFloat() else 1f
        val rawW = (screenW * scale).roundToInt()
        val rawH = (screenH * scale).roundToInt()
        val alignedW = ((rawW + 8) / 16 * 16).coerceAtLeast(160)
        val alignedH = ((rawH + 8) / 16 * 16).coerceAtLeast(160)
        return alignedW to alignedH
    }

    fun formatDuration(elapsedMs: Long): String {
        val totalSec = (elapsedMs / 1000L).coerceAtLeast(0L)
        val hours = totalSec / 3600L
        val mins = (totalSec % 3600L) / 60L
        val secs = totalSec % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, mins, secs)
        } else {
            String.format(Locale.US, "%02d:%02d", mins, secs)
        }
    }

    fun buildGalleryDisplayName(nowMs: Long = System.currentTimeMillis()): String {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return "AutoDora_${sdf.format(Date(nowMs))}.mp4"
    }
}

/**
 * Hardware-accelerated H.264 MP4 screen recorder that taps frames directly from the active
 * [MediaProjectionCapturer] without creating a second VirtualDisplay or requesting extra consent.
 * Finalized videos are written directly to system Gallery (`Movies/AutoDora-NEXT/`) via Scoped MediaStore
 * (zero storage permissions required on Android 10–16).
 */
class BuiltInScreenRecorder(
    context: Context,
    private val frameSource: RecordingFrameSource
) {
    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private val eosSignaled = AtomicBoolean(false)

    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var muxer: MediaMuxer? = null
    private var videoTrackIndex: Int = -1
    @Volatile
    private var muxerStarted: Boolean = false
    private var tempOutputFile: File? = null
    private var startedAtElapsedMs: Long = 0L
    private var encWidth: Int = 720
    private var encHeight: Int = 1280

    private var feederThread: Thread? = null
    private var drainThread: Thread? = null
    @Volatile
    private var usingGpuRelay: Boolean = false
    @Volatile
    private var encodedFrameCount: Int = 0
    @Volatile
    private var recorderError: String? = null

    fun start(): Long {
        if (!running.compareAndSet(false, true)) return startedAtElapsedMs
        stopping.set(false)
        eosSignaled.set(false)
        usingGpuRelay = false
        encodedFrameCount = 0
        recorderError = null
        videoTrackIndex = -1
        muxerStarted = false

        val (w, h) = RecordingFormatUtils.computeRecordingDimensions(
            frameSource.screenWidth,
            frameSource.screenHeight
        )
        encWidth = w
        encHeight = h

        val recDir = File(appContext.cacheDir, "recordings").apply { mkdirs() }
        // Clean up any abandoned temp files older than 2 hours
        recDir.listFiles()?.forEach { f ->
            if (f.isFile && System.currentTimeMillis() - f.lastModified() > 2 * 3600_000L) {
                runCatching { f.delete() }
            }
        }
        val outFile = File(recDir, RecordingFormatUtils.buildGalleryDisplayName())
        tempOutputFile = outFile

        try {
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val isSoftwareCodec = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && codec.codecInfo.isSoftwareOnly
            var actualFps = if (isSoftwareCodec) SOFTWARE_FPS else HARDWARE_FPS
            var bitrateBps = if (actualFps >= 60) HIGH_FPS_BITRATE_BPS else DEFAULT_BITRATE_BPS

            fun buildFormat(fps: Int, bps: Int): MediaFormat =
                MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encWidth, encHeight).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, bps)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SEC)
                }

            try {
                codec.configure(buildFormat(actualFps, bitrateBps), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (cfgErr: Throwable) {
                if (actualFps > SOFTWARE_FPS) {
                    Log.w(TAG, "Hardware encoder rejected ${actualFps}fps, falling back to ${SOFTWARE_FPS}fps", cfgErr)
                    actualFps = SOFTWARE_FPS
                    bitrateBps = DEFAULT_BITRATE_BPS
                    codec.configure(buildFormat(actualFps, bitrateBps), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                } else {
                    throw cfgErr
                }
            }
            val surface = codec.createInputSurface()
            codec.start()

            val m = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            encoder = codec
            inputSurface = surface
            muxer = m
            startedAtElapsedMs = SystemClock.elapsedRealtime()

            drainThread = Thread({ runDrainLoop(codec, m) }, "AutoDora-RecDrain").apply {
                isDaemon = true
                start()
            }

            val gpuAttached = frameSource.attachGpuRecorderSurface(
                codecSurface = surface,
                width = encWidth,
                height = encHeight,
                targetFps = actualFps,
                onFrameEncoded = { encodedFrameCount++ },
                onError = { err -> recorderError = err }
            )
            usingGpuRelay = gpuAttached
            if (!gpuAttached) {
                feederThread = Thread({ runFeederLoop(surface) }, "AutoDora-RecFeed").apply {
                    isDaemon = true
                    start()
                }
            }
            val modeTag = if (gpuAttached) "GPU-zero-copy" else "CPU-fallback"
            Log.i(TAG, "Built-in MP4 screen recording started ($modeTag): ${encWidth}x${encHeight} @ ${actualFps}fps (${bitrateBps / 1_000_000}Mbps)")
            return startedAtElapsedMs
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start built-in screen recorder", t)
            running.set(false)
            runCatching { frameSource.detachGpuRecorderSurface() }
            cleanupResources(deleteTemp = true)
            throw t
        }
    }

    private fun runFeederLoop(surface: Surface) {
        var reusable: Bitmap? = null
        var lastSeq = -1L
        var lastContentW = frameSource.screenWidth
        var lastContentH = frameSource.screenHeight
        var lastPostedAtMs = 0L
        val dstRect = Rect(0, 0, encWidth, encHeight)
        val srcRect = Rect()
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        try {
            while (running.get() && !stopping.get()) {
                val loopStart = SystemClock.elapsedRealtime()
                if (!frameSource.isReady.value) {
                    Thread.sleep(30L)
                    continue
                }
                val copy = frameSource.copyLatestFrameForRecording(reusable, lastSeq)
                val bmp = copy.bitmap
                if (bmp != null) {
                    reusable = bmp
                    lastSeq = copy.frameSeq
                    lastContentW = copy.contentWidth
                    lastContentH = copy.contentHeight
                }
                val now = SystemClock.elapsedRealtime()
                val bmpToDraw = reusable
                if (bmpToDraw != null && (copy.changed || (now - lastPostedAtMs) >= STATIC_HEARTBEAT_MS)) {
                    var canvas: Canvas? = null
                    try {
                        canvas = surface.lockCanvas(null)
                        srcRect.set(0, 0, lastContentW.coerceAtMost(bmpToDraw.width), lastContentH.coerceAtMost(bmpToDraw.height))
                        canvas.drawBitmap(bmpToDraw, srcRect, dstRect, paint)
                        encodedFrameCount++
                        lastPostedAtMs = now
                    } catch (e: Throwable) {
                        if (!stopping.get()) {
                            Log.w(TAG, "Surface draw error during recording: ${e.message}")
                        }
                    } finally {
                        if (canvas != null) {
                            try {
                                surface.unlockCanvasAndPost(canvas)
                            } catch (_: Throwable) {
                            }
                        }
                    }
                }
                val spent = SystemClock.elapsedRealtime() - loopStart
                val sleepMs = (FRAME_INTERVAL_MS - spent).coerceAtLeast(4L)
                if (running.get() && !stopping.get()) {
                    Thread.sleep(sleepMs)
                }
            }
        } catch (_: InterruptedException) {
        } catch (t: Throwable) {
            Log.e(TAG, "Feeder loop crashed", t)
            recorderError = t.localizedMessage ?: t.javaClass.simpleName
        } finally {
            try {
                reusable?.let { if (!it.isRecycled) it.recycle() }
            } catch (_: Exception) {
            }
        }
    }

    private fun runDrainLoop(codec: MediaCodec, muxerRef: MediaMuxer) {
        val info = MediaCodec.BufferInfo()
        var firstPtsUs = -1L
        var lastPtsUs = -1L
        var emptyRetriesAfterEos = 0
        try {
            while (true) {
                val outIdx = codec.dequeueOutputBuffer(info, 25_000L)
                when {
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (eosSignaled.get()) {
                            emptyRetriesAfterEos++
                            if (emptyRetriesAfterEos >= 80) break
                        }
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        emptyRetriesAfterEos = 0
                        if (!muxerStarted) {
                            val newFormat = codec.outputFormat
                            videoTrackIndex = muxerRef.addTrack(newFormat)
                            muxerRef.start()
                            muxerStarted = true
                        }
                    }
                    outIdx >= 0 -> {
                        emptyRetriesAfterEos = 0
                        val outBuf = codec.getOutputBuffer(outIdx)
                        val isCodecConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (outBuf != null && info.size > 0 && !isCodecConfig && muxerStarted && videoTrackIndex >= 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            if (firstPtsUs < 0L) {
                                firstPtsUs = info.presentationTimeUs
                            }
                            var relPtsUs = (info.presentationTimeUs - firstPtsUs).coerceAtLeast(0L)
                            if (relPtsUs <= lastPtsUs) {
                                relPtsUs = lastPtsUs + 1_000L
                            }
                            info.presentationTimeUs = relPtsUs
                            lastPtsUs = relPtsUs
                            muxerRef.writeSampleData(videoTrackIndex, outBuf, info)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            break
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Encoder drain loop error", t)
            recorderError = t.localizedMessage ?: t.javaClass.simpleName
        }
    }

    suspend fun stopAndSave(): RecordingSaveOutcome = withContext(Dispatchers.IO) {
        if (!stopping.compareAndSet(false, true)) {
            return@withContext RecordingSaveOutcome.Failed("Recording already stopping")
        }
        val durationMs = (SystemClock.elapsedRealtime() - startedAtElapsedMs).coerceAtLeast(0L)
        running.set(false)

        if (usingGpuRelay) {
            runCatching { frameSource.detachGpuRecorderSurface() }
        }
        try {
            feederThread?.interrupt()
            feederThread?.join(1500L)
        } catch (_: Exception) {
        }
        feederThread = null

        try {
            encoder?.signalEndOfInputStream()
        } catch (e: Throwable) {
            Log.w(TAG, "signalEndOfInputStream warning: ${e.message}")
        } finally {
            eosSignaled.set(true)
        }

        try {
            drainThread?.join(4000L)
        } catch (_: Exception) {
        }
        drainThread = null

        val outFile = tempOutputFile
        val hadTrack = muxerStarted && encodedFrameCount > 0
        cleanupResources(deleteTemp = false)

        if (!hadTrack || outFile == null || !outFile.exists() || outFile.length() < 1024L) {
            runCatching { outFile?.delete() }
            val err = recorderError ?: "No video frames captured"
            return@withContext RecordingSaveOutcome.Failed(err)
        }

        return@withContext try {
            val saved = saveToSystemGallery(outFile, durationMs)
            runCatching { outFile.delete() }
            saved
        } catch (t: Throwable) {
            Log.e(TAG, "Failed saving recorded video to MediaStore gallery", t)
            runCatching { outFile.delete() }
            RecordingSaveOutcome.Failed(t.localizedMessage ?: t.javaClass.simpleName)
        }
    }

    private fun saveToSystemGallery(mp4File: File, durationMs: Long): RecordingSaveOutcome {
        val resolver = appContext.contentResolver
        val displayName = mp4File.name
        val nowMs = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_ADDED, nowMs / 1000L)
            put(MediaStore.Video.Media.DATE_MODIFIED, nowMs / 1000L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.DATE_TAKEN, nowMs)
                put(MediaStore.Video.Media.DURATION, durationMs)
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/AutoDora-NEXT")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: return RecordingSaveOutcome.Failed("MediaStore insert returned null")

        try {
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(mp4File).use { input ->
                    input.copyTo(out, bufferSize = 64 * 1024)
                }
                out.flush()
            } ?: return RecordingSaveOutcome.Failed("Unable to open MediaStore output stream")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val publish = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                resolver.update(uri, publish, null, null)
            }
            Log.i(TAG, "Saved MP4 to gallery: $displayName (${mp4File.length()} bytes, ${durationMs}ms)")
            return RecordingSaveOutcome.Saved(
                displayName = displayName,
                contentUri = uri,
                durationMs = durationMs,
                sizeBytes = mp4File.length()
            )
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    private fun cleanupResources(deleteTemp: Boolean) {
        try {
            encoder?.stop()
        } catch (_: Throwable) {
        }
        try {
            encoder?.release()
        } catch (_: Throwable) {
        }
        encoder = null

        try {
            inputSurface?.release()
        } catch (_: Throwable) {
        }
        inputSurface = null

        try {
            if (muxerStarted) muxer?.stop()
        } catch (_: Throwable) {
        }
        try {
            muxer?.release()
        } catch (_: Throwable) {
        }
        muxer = null
        muxerStarted = false
        videoTrackIndex = -1

        if (deleteTemp) {
            runCatching { tempOutputFile?.delete() }
            tempOutputFile = null
        }
    }

    companion object {
        private const val TAG = "BuiltInRecorder"
        private const val HARDWARE_FPS = 60
        private const val SOFTWARE_FPS = 30
        private const val FRAME_INTERVAL_MS = 33L
        private const val STATIC_HEARTBEAT_MS = 100L
        private const val DEFAULT_BITRATE_BPS = 6_000_000
        private const val HIGH_FPS_BITRATE_BPS = 8_000_000
        private const val I_FRAME_INTERVAL_SEC = 1
    }
}

/**
 * Process-wide screen recording session state shared in real time by Launch Page & Floating Bar.
 */
object ScreenRecordingSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<ScreenRecordingState>(ScreenRecordingState.Idle)
    val state: StateFlow<ScreenRecordingState> = _state.asStateFlow()

    private val _lastOutcome = MutableStateFlow<RecordingSaveOutcome?>(null)
    val lastOutcome: StateFlow<RecordingSaveOutcome?> = _lastOutcome.asStateFlow()

    val isRecording: Boolean get() = _state.value is ScreenRecordingState.Recording

    @Volatile
    private var activeRecorder: BuiltInScreenRecorder? = null

    @Synchronized
    fun start(context: Context, capturer: ScreenCapturer?): Boolean {
        if (_state.value !is ScreenRecordingState.Idle) return false
        val frameSource = capturer as? RecordingFrameSource ?: return false
        if (!frameSource.isReady.value) return false
        return try {
            val rec = BuiltInScreenRecorder(context.applicationContext, frameSource)
            val startedMs = rec.start()
            activeRecorder = rec
            _lastOutcome.value = null
            _state.value = ScreenRecordingState.Recording(startedMs)
            true
        } catch (t: Throwable) {
            Log.e("ScreenRecSession", "Failed to start recording", t)
            _lastOutcome.value = RecordingSaveOutcome.Failed(t.localizedMessage ?: t.javaClass.simpleName)
            _state.value = ScreenRecordingState.Idle
            false
        }
    }

    suspend fun stopAndSave(): RecordingSaveOutcome? {
        val rec: BuiltInScreenRecorder = synchronized(this) {
            val r = activeRecorder ?: return null
            activeRecorder = null
            _state.value = ScreenRecordingState.Saving
            r
        }
        val outcome = try {
            rec.stopAndSave()
        } catch (t: Throwable) {
            RecordingSaveOutcome.Failed(t.localizedMessage ?: t.javaClass.simpleName)
        }
        _lastOutcome.value = outcome
        _state.value = ScreenRecordingState.Idle
        return outcome
    }

    fun stopAndSaveAsync(onFinished: ((RecordingSaveOutcome) -> Unit)? = null) {
        if (activeRecorder == null) return
        scope.launch {
            val res = stopAndSave()
            if (res != null) onFinished?.invoke(res)
        }
    }
}
