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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.autodora.next.capture.MediaProjectionForegroundService
import com.autodora.next.capture.ScreenCaptureSession
import com.autodora.next.data.*
import com.autodora.next.jni.NativeSolver
import com.autodora.next.model.*
import com.autodora.next.overlay.OverlayControllerService
import com.autodora.next.overlay.OverlayServiceState
import com.autodora.next.touch.AccessibilityServiceBridge
import com.autodora.next.ui.AutoDoraTheme
import com.autodora.next.ui.DashboardScreen
import com.autodora.next.ui.LocaleHelper
import com.autodora.next.ui.PermissionHealthState
import com.autodora.next.vision.EnemySkillOcrEngine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var teamPresetRepository: TeamPresetRepository
    @Inject lateinit var enemySkillOcrEngine: EnemySkillOcrEngine

    private val overlayGrantedState = MutableStateFlow(false)
    private val notificationsGrantedState = MutableStateFlow(true)
    private val benchmarkReportState = MutableStateFlow<String?>(null)
    private val isBenchmarkingState = MutableStateFlow(false)
    private var currentLanguage: AppLanguage = AppLanguage.SYSTEM

    private fun lctx(): Context = LocaleHelper.localizedContext(this, currentLanguage)

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            MediaProjectionForegroundService.start(this, result.resultCode, data)
            Toast.makeText(this, lctx().getString(R.string.toast_capture_ready), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, lctx().getString(R.string.toast_capture_denied), Toast.LENGTH_SHORT).show()
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
            SideEffect { currentLanguage = settings.language }
            val baseCtx = LocalContext.current
            val localizedCtx = remember(settings.language, baseCtx) {
                LocaleHelper.localizedContext(baseCtx, settings.language)
            }
            AutoDoraTheme(themeMode = settings.themeMode) {
                CompositionLocalProvider(
                    LocalContext provides localizedCtx,
                    LocalConfiguration provides localizedCtx.resources.configuration
                ) {
                    val overlayGranted by overlayGrantedState.collectAsState()
                    val notifGranted by notificationsGrantedState.collectAsState()
                    val a11yConnected by AccessibilityServiceBridge.isConnected.collectAsState()
                    val capturer by ScreenCaptureSession.capturer.collectAsState()
                    val mediaReady by (capturer?.isReady ?: remember { MutableStateFlow(false) }).collectAsState()
                    val overlayRunning by OverlayServiceState.isRunning.collectAsState()
                    val presets by teamPresetRepository.observePresets().collectAsState(initial = emptyList())
                    val activeWaveChips by ActiveStrategyStore.activeChips.collectAsState()
                    val ocrSummary by ActiveStrategyStore.lastOcrSummary.collectAsState()
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
                        presets = presets,
                        activeWaveChips = activeWaveChips,
                        lastOcrSummary = ocrSummary,
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
                        },
                        onSelectPreset = { id ->
                            lifecycleScope.launch {
                                settingsRepository.update { it.copy(activePresetId = id) }
                            }
                        },
                        onSavePreset = { preset, isBuiltIn ->
                            lifecycleScope.launch {
                                teamPresetRepository.savePreset(preset, isBuiltIn = isBuiltIn)
                            }
                        },
                        onSaveAsNewPreset = { name, desc, cfg ->
                            lifecycleScope.launch {
                                val newId = "custom_${java.util.UUID.randomUUID().toString().take(8)}"
                                teamPresetRepository.savePreset(
                                    TeamBasePreset(id = newId, name = name, description = desc, baseConfig = cfg),
                                    isBuiltIn = false
                                )
                                settingsRepository.update { it.copy(activePresetId = newId) }
                            }
                        },
                        onDuplicatePreset = { source ->
                            lifecycleScope.launch {
                                val newId = teamPresetRepository.duplicatePreset(source)
                                settingsRepository.update { it.copy(activePresetId = newId) }
                                Toast.makeText(
                                    this@MainActivity,
                                    lctx().getString(R.string.toast_preset_duplicated),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onDeletePreset = { id ->
                            lifecycleScope.launch {
                                teamPresetRepository.deleteCustomPreset(id)
                                if (settings.activePresetId == id) {
                                    settingsRepository.update { it.copy(activePresetId = TeamPresetRepository.ID_GENERAL) }
                                }
                            }
                        },
                        onToggleWaveChip = { chip -> ActiveStrategyStore.toggleChip(chip) },
                        onClearWaveChips = { ActiveStrategyStore.clearAll() },
                        onParseSkillText = { text ->
                            val parsed = enemySkillOcrEngine.parseText(text)
                            val ctx = lctx()
                            if (parsed.chips.isEmpty()) {
                                Toast.makeText(this@MainActivity, ctx.getString(R.string.toast_ocr_none), Toast.LENGTH_SHORT).show()
                            } else {
                                val labels = parsed.chips.joinToString(" · ") { it.label }
                                ActiveStrategyStore.applyOcrChips(
                                    parsed.chips,
                                    ctx.getString(R.string.ocr_text_applied_fmt, labels)
                                )
                                Toast.makeText(
                                    this@MainActivity,
                                    ctx.getString(R.string.toast_ocr_imported, parsed.chips.size),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            parsed.chips
                        }
                    )
                }
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
        val ctx = lctx()
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, ctx.getString(R.string.toast_overlay_perm_needed), Toast.LENGTH_SHORT).show()
            requestOverlayPermission()
            return
        }
        OverlayControllerService.start(this)
        Toast.makeText(this, ctx.getString(R.string.toast_overlay_started), Toast.LENGTH_SHORT).show()
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
                val ctx = lctx()
                benchmarkReportState.value = buildString {
                    appendLine(
                        ctx.getString(
                            R.string.bench_line_65,
                            s65?.totalCombo ?: 0,
                            s65?.stepCount ?: 0,
                            out65.elapsedMs.toInt(),
                            out65.statesEvaluated
                        )
                    )
                    append(
                        ctx.getString(
                            R.string.bench_line_68,
                            s68?.totalCombo ?: 0,
                            s68?.stepCount ?: 0,
                            out68.elapsedMs.toInt(),
                            out68.statesEvaluated
                        )
                    )
                }
            } catch (e: Exception) {
                benchmarkReportState.value = lctx().getString(R.string.bench_error_fmt, e.message ?: "")
            } finally {
                isBenchmarkingState.value = false
            }
        }
    }
}
