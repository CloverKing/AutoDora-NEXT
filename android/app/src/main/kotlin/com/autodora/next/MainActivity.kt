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
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.autodora.next.capture.CaptureRouter
import com.autodora.next.capture.MediaProjectionForegroundService
import com.autodora.next.capture.RecordingSaveOutcome
import com.autodora.next.capture.ScreenCaptureSession
import com.autodora.next.capture.ScreenRecordingSession
import com.autodora.next.capture.ScreenRecordingState
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
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var teamPresetRepository: TeamPresetRepository

    private val overlayGrantedState = MutableStateFlow(false)
    private val notificationsGrantedState = MutableStateFlow(true)
    private val benchmarkReportState = MutableStateFlow<String?>(null)
    private val isBenchmarkingState = MutableStateFlow(false)
    private val updateCheckState = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle)
    private var updateDownloadJob: Job? = null
    private var pendingInstallApk: File? = null
    private var pendingStartRecordingAfterGrant: Boolean = false
    private var currentLanguage: AppLanguage = AppLanguage.SYSTEM

    private fun lctx(): Context = LocaleHelper.localizedContext(this, currentLanguage)

    private val unknownAppSourcesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val apk = pendingInstallApk
        if (apk != null && apk.isFile && packageManager.canRequestPackageInstalls()) {
            pendingInstallApk = null
            launchApkInstaller(apk)
        }
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val prevCapturer = ScreenCaptureSession.capturer.value
            ScreenCaptureSession.clearInitError()
            MediaProjectionForegroundService.start(this, result.resultCode, data)
            lifecycleScope.launch {
                // Wait for MediaProjectionForegroundService to initialize the BRAND-NEW VirtualDisplay
                // before showing honest ready/error feedback!
                var waited = 0
                while (waited < 40) {
                    val cur = ScreenCaptureSession.capturer.value
                    if (cur != null && cur !== prevCapturer && cur.isReady.value) {
                        val shouldStartRec = pendingStartRecordingAfterGrant
                        pendingStartRecordingAfterGrant = false
                        if (shouldStartRec && ScreenRecordingSession.start(this@MainActivity, cur)) {
                            Toast.makeText(this@MainActivity, lctx().getString(R.string.toast_record_started), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MainActivity, lctx().getString(R.string.toast_capture_ready), Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }
                    val err = ScreenCaptureSession.lastInitError.value
                    if (!err.isNullOrBlank()) {
                        pendingStartRecordingAfterGrant = false
                        Toast.makeText(this@MainActivity, "${lctx().getString(R.string.toast_capture_denied)} ($err)", Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    kotlinx.coroutines.delay(40)
                    waited++
                }
                pendingStartRecordingAfterGrant = false
                Toast.makeText(this@MainActivity, lctx().getString(R.string.toast_capture_denied), Toast.LENGTH_SHORT).show()
            }
        } else {
            pendingStartRecordingAfterGrant = false
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
                    val effectiveCaptureMode = CaptureRouter.effectiveMode(settings.captureMode)
                    val captureReady = if (effectiveCaptureMode == CaptureMode.ACCESSIBILITY) {
                        a11yConnected
                    } else {
                        mediaReady
                    }
                    LaunchedEffect(effectiveCaptureMode) {
                        if (effectiveCaptureMode == CaptureMode.ACCESSIBILITY && ScreenCaptureSession.capturer.value != null) {
                            CaptureRouter.releaseMediaProjectionIfRunning(this@MainActivity)
                        }
                    }
                    val overlayRunning by OverlayServiceState.isRunning.collectAsState()
                    val recordingState by ScreenRecordingSession.state.collectAsState()
                    val presets by teamPresetRepository.observePresets().collectAsState(initial = emptyList())
                    val activeWaveChips by ActiveStrategyStore.activeChips.collectAsState()
                    val benchReport by benchmarkReportState.collectAsState()
                    val isBench by isBenchmarkingState.collectAsState()
                    val updateState by updateCheckState.collectAsState()

                    DashboardScreen(
                        health = PermissionHealthState(
                            overlayGranted = overlayGranted,
                            accessibilityConnected = a11yConnected,
                            mediaProjectionReady = captureReady,
                            notificationsGranted = notifGranted,
                            overlayRunning = overlayRunning
                        ),
                        settings = settings,
                        presets = presets,
                        activeWaveChips = activeWaveChips,
                        recordingState = recordingState,
                        benchmarkReport = benchReport,
                        isBenchmarking = isBench,
                        updateCheckState = updateState,
                        onRequestOverlay = { requestOverlayPermission() },
                        onOpenAccessibility = { openAccessibilitySettings() },
                        onOpenAppDetails = { openAppDetailsSettings() },
                        onRequestMediaProjection = {
                            if (effectiveCaptureMode == CaptureMode.ACCESSIBILITY) {
                                openAccessibilitySettings()
                            } else {
                                requestMediaProjectionPermission()
                            }
                        },
                        onRequestNotifications = { requestNotificationPermission() },
                        onToggleOverlayService = { toggleOverlayService() },
                        onToggleScreenRecording = { toggleScreenRecording() },
                        onRunNativeBenchmark = { runOnDeviceBenchmark() },
                        onOpenGitHub = { openExternalUrl(UpdateChecker.GITHUB_REPO_URL) },
                        onCheckForUpdates = { checkForUpdates() },
                        onDownloadUpdate = { avail -> downloadAndInstallUpdate(avail) },
                        onCancelUpdateDownload = { cancelUpdateDownload() },
                        onInstallReadyApk = { path -> installApkOrRequestPermission(File(path)) },
                        onOpenUpdateUrl = { url -> openExternalUrl(url) },
                        onUpdateSettings = { transform ->
                            val wasMediaRunning = ScreenCaptureSession.capturer.value != null
                            lifecycleScope.launch {
                                val next = transform(settings)
                                settingsRepository.update(transform)
                                if (CaptureRouter.effectiveMode(next.captureMode) == CaptureMode.ACCESSIBILITY && wasMediaRunning) {
                                    CaptureRouter.releaseMediaProjectionIfRunning(this@MainActivity)
                                    Toast.makeText(
                                        this@MainActivity,
                                        lctx().getString(R.string.ov_toast_media_released),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        },
                        onSavePreset = { preset, isBuiltIn ->
                            lifecycleScope.launch {
                                teamPresetRepository.savePreset(preset, isBuiltIn = isBuiltIn)
                            }
                        },
                        onResetStrategy = {
                            lifecycleScope.launch {
                                val current = presets.firstOrNull { it.preset.id == settings.activePresetId }
                                    ?: presets.firstOrNull()
                                    ?: TeamPresetRecord(TeamPresetRepository.defaultBuiltInPresets().first(), true, 0L)
                                teamPresetRepository.savePreset(
                                    current.preset.copy(baseConfig = RuleConfig()),
                                    isBuiltIn = current.isBuiltIn
                                )
                                Toast.makeText(
                                    this@MainActivity,
                                    lctx().getString(R.string.toast_strategy_reset),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onToggleWaveChip = { chip -> ActiveStrategyStore.toggleChip(chip) },
                        onClearWaveChips = { ActiveStrategyStore.clearAll() }
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

    private fun toggleScreenRecording() {
        val ctx = lctx()
        when (ScreenRecordingSession.state.value) {
            is ScreenRecordingState.Saving -> return
            is ScreenRecordingState.Recording -> {
                lifecycleScope.launch {
                    when (val outcome = ScreenRecordingSession.stopAndSave()) {
                        is RecordingSaveOutcome.Saved -> {
                            Toast.makeText(
                                this@MainActivity,
                                ctx.getString(R.string.toast_record_saved_fmt, outcome.displayName),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        is RecordingSaveOutcome.Failed -> {
                            Toast.makeText(
                                this@MainActivity,
                                ctx.getString(R.string.toast_record_failed_fmt, outcome.reason),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        null -> Unit
                    }
                }
            }
            is ScreenRecordingState.Idle -> {
                val cap = ScreenCaptureSession.capturer.value
                if (cap == null || !cap.isReady.value) {
                    pendingStartRecordingAfterGrant = true
                    Toast.makeText(this, ctx.getString(R.string.toast_record_need_perm), Toast.LENGTH_SHORT).show()
                    requestMediaProjectionPermission()
                    return
                }
                if (ScreenRecordingSession.start(this, cap)) {
                    Toast.makeText(this, ctx.getString(R.string.toast_record_started), Toast.LENGTH_SHORT).show()
                } else {
                    val reason = (ScreenRecordingSession.lastOutcome.value as? RecordingSaveOutcome.Failed)?.reason ?: "unknown"
                    Toast.makeText(this, ctx.getString(R.string.toast_record_failed_fmt, reason), Toast.LENGTH_LONG).show()
                }
            }
        }
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
                            out65.elapsedMs.toInt()
                        )
                    )
                    append(
                        ctx.getString(
                            R.string.bench_line_68,
                            s68?.totalCombo ?: 0,
                            s68?.stepCount ?: 0,
                            out68.elapsedMs.toInt()
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

    private fun openExternalUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, lctx().getString(R.string.about_open_link_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkForUpdates() {
        val cur = updateCheckState.value
        if (cur is UpdateCheckState.Checking || cur is UpdateCheckState.Downloading) return
        lifecycleScope.launch {
            updateCheckState.value = UpdateCheckState.Checking
            updateCheckState.value = UpdateChecker.checkLatestRelease(BuildConfig.VERSION_NAME)
        }
    }

    private fun downloadAndInstallUpdate(avail: UpdateCheckState.UpdateAvailable) {
        val apkUrl = avail.apkDownloadUrl
        if (apkUrl.isNullOrBlank()) {
            openExternalUrl(avail.releaseUrl)
            return
        }
        updateDownloadJob?.cancel()
        pendingInstallApk = null
        updateDownloadJob = lifecycleScope.launch {
            updateCheckState.value = UpdateCheckState.Downloading(
                latestVersion = avail.latestVersion,
                downloadedBytes = 0L,
                totalBytes = -1L,
                releaseUrl = avail.releaseUrl
            )
            try {
                val updatesDir = File(cacheDir, "updates")
                val apkFile = UpdateChecker.downloadApk(
                    downloadUrl = apkUrl,
                    version = avail.latestVersion,
                    updatesDir = updatesDir,
                    userAgentVersion = BuildConfig.VERSION_NAME
                ) { downloaded, total ->
                    updateCheckState.value = UpdateCheckState.Downloading(
                        latestVersion = avail.latestVersion,
                        downloadedBytes = downloaded,
                        totalBytes = total,
                        releaseUrl = avail.releaseUrl
                    )
                }
                updateCheckState.value = UpdateCheckState.ReadyToInstall(
                    latestVersion = avail.latestVersion,
                    apkPath = apkFile.absolutePath,
                    releaseUrl = avail.releaseUrl
                )
                installApkOrRequestPermission(apkFile)
            } catch (e: CancellationException) {
                updateCheckState.value = avail
            } catch (e: Exception) {
                updateCheckState.value = UpdateCheckState.Error(e.localizedMessage ?: e.javaClass.simpleName)
            } finally {
                updateDownloadJob = null
            }
        }
    }

    private fun cancelUpdateDownload() {
        updateDownloadJob?.cancel()
        updateDownloadJob = null
    }

    private fun installApkOrRequestPermission(apkFile: File) {
        if (!apkFile.isFile) {
            updateCheckState.value = UpdateCheckState.Error("APK file missing")
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            pendingInstallApk = apkFile
            Toast.makeText(
                this,
                lctx().getString(R.string.about_update_need_install_perm),
                Toast.LENGTH_LONG
            ).show()
            try {
                val permIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
                unknownAppSourcesLauncher.launch(permIntent)
            } catch (e: Exception) {
                Toast.makeText(
                    this,
                    lctx().getString(R.string.about_update_install_failed, e.localizedMessage ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }
        launchApkInstaller(apkFile)
    }

    private fun launchApkInstaller(apkFile: File) {
        try {
            val apkUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apkFile)
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(installIntent)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                lctx().getString(R.string.about_update_install_failed, e.localizedMessage ?: e.javaClass.simpleName),
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
