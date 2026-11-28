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
import com.autodora.next.capture.ScreenCaptureSession
import com.autodora.next.data.*
import com.autodora.next.jni.NativeSolver
import com.autodora.next.model.*
import com.autodora.next.overlay.OverlayControllerService
import com.autodora.next.overlay.OverlayServiceState
import com.autodora.next.touch.AccessibilityServiceBridge
import com.autodora.next.ui.AiModelsStatus
import com.autodora.next.ui.AutoDoraTheme
import com.autodora.next.ui.DashboardScreen
import com.autodora.next.ui.LocaleHelper
import com.autodora.next.ui.PermissionHealthState
import com.autodora.next.vision.BoardRecognizer
import com.autodora.next.vision.EnemyIconOnnxClassifier
import com.autodora.next.vision.EnemySkillKnowledgeMatcher
import com.autodora.next.vision.EnemySkillParseStatus
import com.autodora.next.vision.HotReloadEnemySkillParser
import com.autodora.next.vision.HybridBoardRecognizer
import com.autodora.next.vision.LocalCapturedSkillRecord
import com.autodora.next.vision.StageWaveScriptParser
import com.autodora.next.vision.TosEnemySkillParserV2
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
    @Inject lateinit var trainingDataRepository: TrainingDataRepository
    @Inject lateinit var enemySkillParserHolder: HotReloadEnemySkillParser
    @Inject lateinit var stageWaveScriptRepository: StageWaveScriptRepository
    @Inject lateinit var boardRecognizer: BoardRecognizer
    @Inject lateinit var enemyIconOnnxClassifier: EnemyIconOnnxClassifier
    @Inject lateinit var knowledgeMatcher: EnemySkillKnowledgeMatcher

    private val overlayGrantedState = MutableStateFlow(false)
    private val notificationsGrantedState = MutableStateFlow(true)
    private val benchmarkReportState = MutableStateFlow<String?>(null)
    private val isBenchmarkingState = MutableStateFlow(false)
    private val isExportingTrainingState = MutableStateFlow(false)
    private val customRulesTagState = MutableStateFlow("")
    private val hasCustomRulesState = MutableStateFlow(false)
    private val updateCheckState = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle)
    private var updateDownloadJob: Job? = null
    private var trainingExportJob: Job? = null
    private var pendingInstallApk: File? = null
    private var currentLanguage: AppLanguage = AppLanguage.SYSTEM

    private val importRulesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importCustomRulesFromUri(uri)
    }

    private val importStageWikiLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importStageScriptsFromUri(uri)
    }

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
                        Toast.makeText(this@MainActivity, lctx().getString(R.string.toast_capture_ready), Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    val err = ScreenCaptureSession.lastInitError.value
                    if (!err.isNullOrBlank()) {
                        Toast.makeText(this@MainActivity, "${lctx().getString(R.string.toast_capture_denied)} ($err)", Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    kotlinx.coroutines.delay(40)
                    waited++
                }
                Toast.makeText(this@MainActivity, lctx().getString(R.string.toast_capture_denied), Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, lctx().getString(R.string.toast_capture_denied), Toast.LENGTH_SHORT).show()
        }
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        refreshPermissions()
        syncCustomRulesStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshPermissions()
        syncCustomRulesStatus()

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
                    val presets by teamPresetRepository.observePresets().collectAsState(initial = emptyList())
                    val activeWaveChips by ActiveStrategyStore.activeChips.collectAsState()
                    val benchReport by benchmarkReportState.collectAsState()
                    val isBench by isBenchmarkingState.collectAsState()
                    val trainingStats by trainingDataRepository.observeStats().collectAsState(initial = TrainingStorageStats())
                    val isExportingTraining by isExportingTrainingState.collectAsState()
                    val capturedEnemySkills by trainingDataRepository.observeCapturedEnemySkills().collectAsState(initial = emptyList())
                    val savedStageScripts by stageWaveScriptRepository.observeScripts().collectAsState(initial = emptyList())
                    val activeStageScript by ActiveStageScriptStore.activeScript.collectAsState()
                    val activeStageWaveIndex by ActiveStageScriptStore.currentWaveIndex.collectAsState()
                    val customRulesVersionTag by customRulesTagState.collectAsState()
                    val hasCustomRules by hasCustomRulesState.collectAsState()
                    val updateState by updateCheckState.collectAsState()
                    val bypassedGreySkills by ActiveStrategyStore.bypassedGreySkills.collectAsState()
                    val activeVisualHints by ActiveStrategyStore.activeVisualHints.collectAsState()

                    LaunchedEffect(settings.useAiRecognizer) {
                        (boardRecognizer as? HybridBoardRecognizer)?.useAiModel = settings.useAiRecognizer
                    }
                    LaunchedEffect(capturedEnemySkills) {
                        knowledgeMatcher.updateLocalRecords(
                            capturedEnemySkills.map { sk ->
                                LocalCapturedSkillRecord(
                                    skillHash = sk.skillHash,
                                    titleZhTw = sk.titleZhTw,
                                    rawBodyZhTw = sk.rawBodyZhTw,
                                    sourceType = sk.sourceType,
                                    parseStatus = sk.parseStatus,
                                    hitCount = sk.hitCount,
                                    mappedChips = TrainingDataRepository.decodeMappedChips(sk.mappedChipsJson)
                                )
                            }
                        )
                    }
                    LaunchedEffect(Unit) {
                        autoSeedBuiltInStageScriptsOnce()
                    }
                    val aiModelsStatus = remember(settings.useAiRecognizer) {
                        val hasBm = runCatching {
                            assets.list("models")?.contains("board_master_v2.onnx") == true
                        }.getOrDefault(false)
                        val hasIcon = enemyIconOnnxClassifier.hasModel()
                        val hasOcr = runCatching {
                            assets.list("ocr")?.contains("ch_PP-OCRv5_rec_mobile.onnx") == true
                        }.getOrDefault(false)
                        val engineLbl = (boardRecognizer as? HybridBoardRecognizer)?.modelDisplayStatus()
                            ?: boardRecognizer.name
                        AiModelsStatus(
                            boardEngineLabel = engineLbl,
                            hasBoardMasterOnnx = hasBm,
                            hasEnemyIconOnnx = hasIcon,
                            hasOcrOnnx = hasOcr
                        )
                    }

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
                        bypassedGreySkills = bypassedGreySkills,
                        activeVisualHints = activeVisualHints,
                        aiModelsStatus = aiModelsStatus,
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
                        onRunNativeBenchmark = { runOnDeviceBenchmark() },
                        trainingStats = trainingStats,
                        isExportingTrainingData = isExportingTraining,
                        onExportTrainingData = { exportAndShareTrainingData() },
                        onClearTrainingData = { clearTrainingData() },
                        capturedEnemySkills = capturedEnemySkills,
                        savedStageScripts = savedStageScripts,
                        activeStageScript = activeStageScript,
                        activeStageWaveIndex = activeStageWaveIndex,
                        rulesVersionLabel = customRulesVersionTag,
                        hasCustomRulesOverride = hasCustomRules,
                        onEvaluateRuleTestText = { raw ->
                            val parser = enemySkillParserHolder.current()
                            knowledgeMatcher.enrichParseResult(parser.parsePlainText(raw), parser)
                        },
                        onImportCustomRulesJson = {
                            importRulesLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                        },
                        onResetCustomRulesJson = {
                            val cnt = enemySkillParserHolder.resetToBundled()
                            syncCustomRulesStatus()
                            reparseAllCapturedSkillsQuietly()
                            Toast.makeText(
                                this@MainActivity,
                                lctx().getString(R.string.knowledge_rules_reset_ok_fmt, cnt),
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        onImportStageWikiJson = {
                            importStageWikiLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                        },
                        onImportBuiltInStageBundle = { importBuiltIn140StageScripts(showToast = true) },
                        onImportBuiltInAuthoritativeSkills = { importBuiltIn499EnemySkills() },
                        onActivateStageScript = { script ->
                            ActiveStageScriptStore.activateScript(script, script.waves.firstOrNull()?.waveIndex ?: 1)
                            Toast.makeText(
                                this@MainActivity,
                                lctx().getString(R.string.knowledge_stage_activated_fmt, script.stageName, script.waves.size),
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        onDeactivateStageScript = { ActiveStageScriptStore.clearActiveScript() },
                        onStepStageWave = { delta -> ActiveStageScriptStore.stepWave(delta) },
                        onDeleteStageScript = { key ->
                            lifecycleScope.launch {
                                if (activeStageScript?.stageKey == key) ActiveStageScriptStore.clearActiveScript()
                                stageWaveScriptRepository.deleteScript(key)
                            }
                        },
                        onClearAllStageScripts = { clearAllStageScripts() },
                        onRestoreBypassedSkill = { sk -> ActiveStrategyStore.restoreBypassedSkill(sk) },
                        onUpsertEnemySkill = { oldHash, title, body, stageName, waveIdx ->
                            upsertManualEnemySkill(oldHash, title, body, stageName, waveIdx)
                        },
                        onDeleteEnemySkill = { hash -> deleteEnemySkill(hash) },
                        onCleanNoiseEnemySkills = { cleanNoiseEnemySkills() },
                        onReparseAllEnemySkills = { reparseAllEnemySkillsWithToast() },
                        onClearAllEnemySkills = { clearAllEnemySkills() },
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
        syncCustomRulesStatus()
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

    private fun exportAndShareTrainingData() {
        if (isExportingTrainingState.value) return
        trainingExportJob?.cancel()
        trainingExportJob = lifecycleScope.launch {
            isExportingTrainingState.value = true
            try {
                val res = trainingDataRepository.exportDatasetZip(BuildConfig.VERSION_NAME)
                val destLabel = res.publicDownloadsPath ?: res.zipFile.name
                Toast.makeText(
                    this@MainActivity,
                    lctx().getString(R.string.training_collect_export_ok_fmt, res.sampleCount, destLabel),
                    Toast.LENGTH_LONG
                ).show()
                val zipUri = FileProvider.getUriForFile(
                    this@MainActivity,
                    "$packageName.fileprovider",
                    res.zipFile
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, zipUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(
                    Intent.createChooser(shareIntent, lctx().getString(R.string.training_collect_share_chooser))
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    lctx().getString(
                        R.string.training_collect_export_err_fmt,
                        e.localizedMessage ?: e.javaClass.simpleName
                    ),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                isExportingTrainingState.value = false
                trainingExportJob = null
            }
        }
    }

    private fun clearTrainingData() {
        lifecycleScope.launch {
            trainingDataRepository.clearAllCollectedSamples()
            Toast.makeText(
                this@MainActivity,
                lctx().getString(R.string.training_collect_cleared),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun syncCustomRulesStatus() {
        val p = enemySkillParserHolder.current()
        customRulesTagState.value = "${p.version} (${p.ruleCount})"
        hasCustomRulesState.value = enemySkillParserHolder.hasCustomOverride()
    }

    private fun importCustomRulesFromUri(uri: Uri) {
        lifecycleScope.launch {
            val text = runCatching {
                contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            }.getOrNull()
            if (text.isNullOrBlank()) {
                Toast.makeText(this@MainActivity, lctx().getString(R.string.knowledge_import_failed), Toast.LENGTH_SHORT).show()
                return@launch
            }
            val res = enemySkillParserHolder.validateAndSaveRulesJson(text)
            res.onSuccess { count ->
                syncCustomRulesStatus()
                reparseAllCapturedSkillsQuietly()
                Toast.makeText(
                    this@MainActivity,
                    lctx().getString(R.string.knowledge_rules_imported_ok_fmt, count),
                    Toast.LENGTH_LONG
                ).show()
            }.onFailure { err ->
                Toast.makeText(
                    this@MainActivity,
                    "${lctx().getString(R.string.knowledge_import_failed)}: ${err.localizedMessage ?: ""}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun importStageScriptsFromUri(uri: Uri) {
        lifecycleScope.launch {
            val text = runCatching {
                contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            }.getOrNull()
            if (text.isNullOrBlank()) {
                Toast.makeText(this@MainActivity, lctx().getString(R.string.knowledge_import_failed), Toast.LENGTH_SHORT).show()
                return@launch
            }
            val parsed = runCatching {
                StageWaveScriptParser.parseStageWikiJson(text, enemySkillParserHolder.current())
            }.getOrDefault(emptyList())
            if (parsed.isEmpty()) {
                Toast.makeText(this@MainActivity, lctx().getString(R.string.knowledge_stage_import_empty), Toast.LENGTH_SHORT).show()
                return@launch
            }
            val saved = stageWaveScriptRepository.saveScripts(parsed)
            Toast.makeText(
                this@MainActivity,
                lctx().getString(R.string.knowledge_stage_imported_ok_fmt, saved),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private suspend fun autoSeedBuiltInStageScriptsOnce() {
        val prefs = getSharedPreferences("autodora_seed_flags", Context.MODE_PRIVATE)
        if (prefs.getBoolean("seeded_v090_stage_bundle_140", false)) return
        val parsed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val raw = assets.open("tos_stage_wiki_bundle_140.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
                StageWaveScriptParser.parseStageWikiJson(raw, enemySkillParserHolder.current())
            }.getOrDefault(emptyList())
        }
        if (parsed.isNotEmpty()) {
            stageWaveScriptRepository.saveScripts(parsed)
            prefs.edit().putBoolean("seeded_v090_stage_bundle_140", true).apply()
        }
    }

    private fun importBuiltIn140StageScripts(showToast: Boolean) {
        lifecycleScope.launch {
            val parsed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val raw = assets.open("tos_stage_wiki_bundle_140.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
                    StageWaveScriptParser.parseStageWikiJson(raw, enemySkillParserHolder.current())
                }.getOrDefault(emptyList())
            }
            if (parsed.isEmpty()) {
                if (showToast) Toast.makeText(this@MainActivity, lctx().getString(R.string.knowledge_import_failed), Toast.LENGTH_SHORT).show()
                return@launch
            }
            val saved = stageWaveScriptRepository.saveScripts(parsed)
            if (showToast) {
                Toast.makeText(
                    this@MainActivity,
                    lctx().getString(R.string.toast_builtin_stages_imported_fmt, saved),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun importBuiltIn499EnemySkills() {
        lifecycleScope.launch {
            val imported = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val parser = enemySkillParserHolder.current()
                val entries = knowledgeMatcher.authoritativeList()
                val inputs = entries.map { auth ->
                    val fullText = if (auth.titleZhTw.isNotBlank() && !auth.cleanBodyZhTw.contains(auth.titleZhTw)) {
                        "${auth.titleZhTw}\n${auth.cleanBodyZhTw}"
                    } else {
                        auth.cleanBodyZhTw
                    }
                    val parsed = parser.parsePlainText(fullText)
                    val firstBlk = parsed.allBlocks.firstOrNull()
                    val cleanTitle = auth.titleZhTw.removePrefix("【").removeSuffix("】").trim()
                        .ifBlank { firstBlk?.titleZhTw.orEmpty() }
                    val status = when {
                        parsed.activeChips.isNotEmpty() || parsed.combinedHints.hasAny() -> EnemySkillParseStatus.PARSED
                        parsed.allBlocks.any { it.parseStatus == EnemySkillParseStatus.NON_BOARD_KNOWN } -> EnemySkillParseStatus.NON_BOARD_KNOWN
                        else -> firstBlk?.parseStatus ?: EnemySkillParseStatus.UNPARSED
                    }
                    CapturedEnemySkillInput(
                        skillHash = auth.hash,
                        titleZhTw = cleanTitle,
                        rawBodyZhTw = auth.cleanBodyZhTw,
                        sourceType = "AUTHORITATIVE_499",
                        stageName = auth.sampleStages.firstOrNull().orEmpty(),
                        waveIndex = 0,
                        parseStatus = status,
                        mappedChips = parsed.activeChips
                    )
                }
                trainingDataRepository.importAuthoritativeEnemySkills(inputs)
            }
            Toast.makeText(
                this@MainActivity,
                lctx().getString(R.string.toast_builtin_skills_imported_fmt, imported),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun upsertManualEnemySkill(
        oldHash: String?,
        title: String,
        body: String,
        stageName: String,
        waveIdx: Int
    ) {
        lifecycleScope.launch {
            val cleanBody = body.trim()
            if (cleanBody.isBlank()) return@launch
            val parser = enemySkillParserHolder.current()
            val parsed = parser.parsePlainText(cleanBody)
            val firstBlk = parsed.allBlocks.firstOrNull()
            val effTitle = title.trim()
                .ifBlank { firstBlk?.titleZhTw.orEmpty() }
                .ifBlank { cleanBody.lineSequence().firstOrNull().orEmpty().trim().take(36) }
            val norm = TosEnemySkillParserV2.normalizeText(cleanBody)
            val newHash = TosEnemySkillParserV2.computeSkillHash(effTitle, norm)
            val status = firstBlk?.parseStatus ?: EnemySkillParseStatus.UNPARSED
            val chips = parsed.activeChips.ifEmpty { firstBlk?.chips.orEmpty() }
            trainingDataRepository.upsertManualEnemySkill(
                oldHash = oldHash,
                newHash = newHash,
                titleZhTw = effTitle,
                rawBodyZhTw = cleanBody,
                stageName = stageName,
                waveIndex = waveIdx,
                parseStatus = status,
                mappedChips = chips
            )
            Toast.makeText(this@MainActivity, lctx().getString(R.string.knowledge_saved_ok), Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteEnemySkill(hash: String) {
        lifecycleScope.launch {
            trainingDataRepository.deleteEnemySkill(hash)
            Toast.makeText(this@MainActivity, lctx().getString(R.string.knowledge_deleted_ok), Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearAllEnemySkills() {
        lifecycleScope.launch {
            trainingDataRepository.clearAllEnemySkills()
            Toast.makeText(this@MainActivity, lctx().getString(R.string.knowledge_cleared_ok), Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearAllStageScripts() {
        lifecycleScope.launch {
            ActiveStageScriptStore.clearActiveScript()
            stageWaveScriptRepository.clearAll()
            Toast.makeText(this@MainActivity, lctx().getString(R.string.stage_scripts_cleared_ok), Toast.LENGTH_SHORT).show()
        }
    }

    private fun cleanNoiseEnemySkills() {
        lifecycleScope.launch {
            val removed = trainingDataRepository.pruneEnemySkills { sk ->
                TosEnemySkillParserV2.isLikelyNoiseEntry(sk.titleZhTw, sk.rawBodyZhTw)
            }
            val msg = if (removed > 0) {
                lctx().getString(R.string.knowledge_cleaned_noise_fmt, removed)
            } else {
                lctx().getString(R.string.knowledge_no_noise_found)
            }
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun reparseAllEnemySkillsWithToast() {
        lifecycleScope.launch {
            val parser = enemySkillParserHolder.current()
            val (total, updated) = trainingDataRepository.reparseAllEnemySkills { sk ->
                val parsed = parser.parsePlainText(sk.rawBodyZhTw)
                val firstBlk = parsed.allBlocks.firstOrNull()
                ReparsedEnemySkillFields(
                    titleZhTw = sk.titleZhTw.ifBlank { firstBlk?.titleZhTw.orEmpty() },
                    parseStatus = firstBlk?.parseStatus ?: EnemySkillParseStatus.UNPARSED,
                    mappedChips = parsed.activeChips.ifEmpty { firstBlk?.chips.orEmpty() }
                )
            }
            Toast.makeText(
                this@MainActivity,
                lctx().getString(R.string.knowledge_reparsed_fmt, total, updated),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun reparseAllCapturedSkillsQuietly() {
        lifecycleScope.launch {
            val parser = enemySkillParserHolder.current()
            trainingDataRepository.reparseAllEnemySkills { sk ->
                val parsed = parser.parsePlainText(sk.rawBodyZhTw)
                val firstBlk = parsed.allBlocks.firstOrNull()
                ReparsedEnemySkillFields(
                    titleZhTw = sk.titleZhTw.ifBlank { firstBlk?.titleZhTw.orEmpty() },
                    parseStatus = firstBlk?.parseStatus ?: EnemySkillParseStatus.UNPARSED,
                    mappedChips = parsed.activeChips.ifEmpty { firstBlk?.chips.orEmpty() }
                )
            }
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
