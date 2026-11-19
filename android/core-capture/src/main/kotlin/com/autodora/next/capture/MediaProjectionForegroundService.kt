package com.autodora.next.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ScreenCaptureSession {
    private val _capturer = MutableStateFlow<ScreenCapturer?>(null)
    val capturer: StateFlow<ScreenCapturer?> = _capturer.asStateFlow()

    val isActive: Boolean get() = _capturer.value?.isReady?.value == true

    internal fun setCapturer(cap: ScreenCapturer?) {
        _capturer.value?.release()
        _capturer.value = cap
    }

    fun clear() {
        _capturer.value?.release()
        _capturer.value = null
    }
}

/**
 * Foreground service hosting MediaProjection on Android 10–15+.
 * Must declare foregroundServiceType="mediaProjection" in AndroidManifest.
 */
class MediaProjectionForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ScreenCaptureSession.clear()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val dataIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA_INTENT, Intent::class.java)
        } else {
            intent?.getParcelableExtra(EXTRA_DATA_INTENT)
        }

        if (resultCode == Activity.RESULT_OK && dataIntent != null) {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mpm.getMediaProjection(resultCode, dataIntent)
            if (projection != null) {
                val capturer = MediaProjectionCapturer(this, projection) {
                    ScreenCaptureSession.clear()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                ScreenCaptureSession.setCapturer(capturer)
                Log.i(TAG, "MediaProjection session initialized successfully")
            } else {
                Log.e(TAG, "getMediaProjection returned null")
                stopSelf()
            }
        } else {
            Log.e(TAG, "Invalid MediaProjection resultCode=$resultCode")
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ScreenCaptureSession.clear()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AutoDora-NEXT 屏幕采集服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "免 Root 识图所需的屏幕画面采集前台服务"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("AutoDora-NEXT 识图服务运行中")
            .setContentText("免 Root 屏幕采集已就绪，可随时点击悬浮球解盘")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "MediaProjService"
        private const val CHANNEL_ID = "autodora_media_projection"
        private const val NOTIFICATION_ID = 20261
        const val ACTION_START = "com.autodora.next.capture.START"
        const val ACTION_STOP = "com.autodora.next.capture.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA_INTENT = "data_intent"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, MediaProjectionForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA_INTENT, data)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MediaProjectionForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
