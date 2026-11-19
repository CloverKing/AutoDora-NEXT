package com.autodora.next

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.autodora.next.capture.MediaProjectionForegroundService
import com.autodora.next.capture.ScreenCaptureSession
import com.autodora.next.data.AppSettings
import com.autodora.next.data.SettingsRepository
import com.autodora.next.jni.NativeSolver
import com.autodora.next.model.*
import com.autodora.next.overlay.OverlayControllerService
import com.autodora.next.overlay.OverlayServiceState
import com.autodora.next.touch.AccessibilityServiceBridge
import com.autodora.next.ui.AutoDoraTheme
import com.autodora.next.ui.DashboardScreen
import com.autodora.next.ui.PermissionHealthState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    private val overlayGrantedState = MutableStateFlow(false)
    private val notificationsGrantedState = MutableStateFlow(true)
    private val benchmarkReportState = MutableStateFlow<String?>(null)
    private val isBenchmarkingState = MutableStateFlow(false)

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            MediaProjectionForegroundService.start(this, result.resultCode, data)
            Toast.makeText(this, "免 Root 屏幕采集服务已启动！", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "未授予录屏权限", Toast.LENGTH_SHORT).show()
        }
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        refreshPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshPermissions()

        setContent {
            val settings by settingsRepository.settingsFlow.collectAsState(initial = AppSettings())
            AutoDoraTheme(themeMode = settings.themeMode) {
                val overlayGranted by overlayGrantedState.collectAsState()
                val notifGranted by notificationsGrantedState.collectAsState()
                val a11yConnected by AccessibilityServiceBridge.isConnected.collectAsState()
                val capturer by ScreenCaptureSession.capturer.collectAsState()
                val mediaReady by (capturer?.isReady ?: remember { MutableStateFlow(false) }).collectAsState()
                val overlayRunning by OverlayServiceState.isRunning.collectAsState()
                val benchReport by benchmarkReportState.collectAsState()
                val isBench by isBenchmarkingState.collectAsState()

                DashboardScreen(
                    health = PermissionHealthState(
                        overlayGranted = overlayGranted,
                        accessibilityConnected = a11yConnected,
                        mediaProjectionReady = mediaReady,
                        notificationsGranted = notifGranted,
                        overlayRunning = overlayRunning
                    ),
                    settings = settings,
                    benchmarkReport = benchReport,
                    isBenchmarking = isBench,
                    onRequestOverlay = { requestOverlayPermission() },
                    onOpenAccessibility = { openAccessibilitySettings() },
                    onOpenAppDetails = { openAppDetailsSettings() },
                    onRequestMediaProjection = { requestMediaProjectionPermission() },
                    onRequestNotifications = { requestNotificationPermission() },
                    onToggleOverlayService = { toggleOverlayService() },
                    onRunNativeBenchmark = { runOnDeviceBenchmark() },
                    onUpdateSettings = { transform ->
                        lifecycleScope.launch { settingsRepository.update(transform) }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    private fun refreshPermissions() {
        overlayGrantedState.value = Settings.canDrawOverlays(this)
        notificationsGrantedState.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openAppDetailsSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestMediaProjectionPermission() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        mediaProjectionLauncher.launch(intent)
    }

    private fun toggleOverlayService() {
        if (OverlayServiceState.isRunning.value) {
            OverlayControllerService.stop(this)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予「悬浮窗权限」", Toast.LENGTH_SHORT).show()
            requestOverlayPermission()
            return
        }
        OverlayControllerService.start(this)
        Toast.makeText(this, "悬浮窗已启动！请切换到《神魔之塔》使用", Toast.LENGTH_SHORT).show()
    }

    private fun runOnDeviceBenchmark() {
        if (isBenchmarkingState.value) return
        lifecycleScope.launch {
            isBenchmarkingState.value = true
            try {
                // 6x5 test board
                val str65 = "WFGLDHFGLWFGLWDFGLGWFLHWFLDGWH"
                val board65 = BoardState(
                    cols = 6,
                    rows = 5,
                    cells = str65.map { ch ->
                        val el = Element.entries.first { it.symbol == ch }
                        CellState(element = el)
                    }
                )
                val out65 = NativeSolver.solve(board65, RuleConfig(maxSteps = 26, beamWidth = 180, maxTimeMs = 500))

                // 6x8 Weijian test board
                val str68 = "WFGLDHFGLDHWGLDHWFLDHWFGDHWFGLHWFGLDWFLGDHGHDWFL"
                val board68 = BoardState(
                    cols = 6,
                    rows = 8,
                    cells = str68.map { ch ->
                        val el = Element.entries.first { it.symbol == ch }
                        CellState(element = el)
                    }
                )
                val out68 = NativeSolver.solve(board68, RuleConfig(maxSteps = 30, beamWidth = 140, maxTimeMs = 700))

                val s65 = out65.solutions.firstOrNull()
                val s68 = out68.solutions.firstOrNull()
                benchmarkReportState.value = buildString {
                    appendLine("✓ 6×5 盘面：${s65?.totalCombo ?: 0}C (${s65?.stepCount ?: 0}步) · 耗时 ${out65.elapsedMs.toInt()} ms · 评估 ${out65.statesEvaluated} 局面")
                    append("✓ 6×8 违建：${s68?.totalCombo ?: 0}C (${s68?.stepCount ?: 0}步) · 耗时 ${out68.elapsedMs.toInt()} ms · 评估 ${out68.statesEvaluated} 局面")
                }
            } catch (e: Exception) {
                benchmarkReportState.value = "压测异常: ${e.message}"
            } finally {
                isBenchmarkingState.value = false
            }
        }
    }
}
