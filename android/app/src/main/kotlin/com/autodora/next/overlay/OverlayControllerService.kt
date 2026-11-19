package com.autodora.next.overlay

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.text.TextUtils
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.autodora.next.capture.AutoGridDetector
import com.autodora.next.capture.BoardCalibration
import com.autodora.next.capture.ScreenCaptureSession
import com.autodora.next.data.*
import com.autodora.next.jni.NativeCancelToken
import com.autodora.next.jni.NativeSolver
import com.autodora.next.model.*
import com.autodora.next.touch.HoldReplanTrigger
import com.autodora.next.touch.SwipeSpeedPreset
import com.autodora.next.touch.TouchExecutionResult
import com.autodora.next.touch.TouchExecutor
import com.autodora.next.ui.OverlayPalette
import com.autodora.next.ui.OverlayThemeResolver
import com.autodora.next.vision.BoardRecognizer
import com.autodora.next.vision.EnemySkillOcrEngine
import com.autodora.next.vision.FlashRevealMemoryTracker
import com.autodora.next.vision.RecognitionResult
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import javax.inject.Inject
import kotlin.math.abs

object OverlayServiceState {
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    internal fun setRunning(running: Boolean) {
        _isRunning.value = running
    }
}

private enum class OverlayPhase { IDLE, CALCULATING, MOVING }

@AndroidEntryPoint
class OverlayControllerService : Service() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var teamPresetRepository: TeamPresetRepository
    @Inject lateinit var boardRecognizer: BoardRecognizer
    @Inject lateinit var enemySkillOcrEngine: EnemySkillOcrEngine
    @Inject lateinit var touchExecutor: TouchExecutor

    private lateinit var windowManager: WindowManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var screenW = 1080
    private var screenH = 2400
    private var settings = AppSettings()
    private var presetRecords: List<TeamPresetRecord> = emptyList()
    private var activeWaveChips: List<WaveShieldChip> = emptyList()
    private var lastOcrSummary: String? = null
    private lateinit var palette: OverlayPalette

    // Overlay views
    private lateinit var pathOverlayView: PathOverlayView
    private lateinit var pathParams: WindowManager.LayoutParams
    private lateinit var controlRoot: LinearLayout
    private lateinit var controlParams: WindowManager.LayoutParams
    private lateinit var statusOrbView: TextView
    private lateinit var toolbarContainer: LinearLayout

    // Main bar controls
    private lateinit var solveBtn: TextView
    private lateinit var prevBtn: TextView
    private lateinit var pagerText: TextView
    private lateinit var nextBtn: TextView
    private lateinit var executeBtn: TextView
    private lateinit var stopChipBtn: TextView
    private lateinit var pathToggleBtn: TextView
    private lateinit var calibToggleBtn: TextView
    private lateinit var moreToggleBtn: TextView
    private lateinit var closeBtn: TextView

    // Collapsible quick-controls card (2 tidy rows under '⋯')
    private lateinit var quickPanel: LinearLayout
    private lateinit var holdReplanToggleBtn: TextView
    private lateinit var memoryChipBtn: TextView
    private lateinit var invertChipBtn: TextView
    private lateinit var speedChipBtn: TextView
    private lateinit var tuningDrawerToggleBtn: TextView
    private lateinit var boardEditorToggleBtn: TextView
    private lateinit var ocrScanBtn: TextView

    // Active wave shields strip
    private lateinit var activeShieldScroll: HorizontalScrollView
    private lateinit var activeShieldRow: LinearLayout

    // Solution summary strip
    private lateinit var summaryStrip: LinearLayout
    private lateinit var summaryTitle: TextView
    private lateinit var groupsScroll: HorizontalScrollView
    private lateinit var groupsRow: LinearLayout

    // Calibration bar
    private lateinit var calibrationBar: LinearLayout
    private val calibNudgeBtns = ArrayList<TextView>()
    private lateinit var calibAutoBtn: TextView
    private lateinit var calibResetBtn: TextView

    // Phase 3 in-game drawers
    private lateinit var tuningDrawer: OverlayTuningDrawerController
    private lateinit var boardEditor: OverlayBoardEditorController

    // Interactive state
    private var currentPhase = OverlayPhase.IDLE
    private var toolbarExpanded = true
    private var modeRowExpanded = false
    private var tuningDrawerOpen = false
    private var boardEditorMode = false
    private var summaryPillsExpanded = true
    private var showPath = true
    private var calibrationMode = false
    private var currentGeometry: BoardGeometry? = null
    private var lastRecognition: RecognitionResult? = null
    private var editedBoard: BoardState? = null
    private var lastSolveOutput: SolveOutput? = null
    private var usedMemorizedSnapshot = false
    private var selectedSolutionIdx = 0
    private var activeJob: Job? = null
    private var flashWatchJob: Job? = null
    private var activeCancelToken: NativeCancelToken? = null
    private val flashMemoryTracker = FlashRevealMemoryTracker()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        palette = OverlayThemeResolver.resolve(this, settings.themeMode)
        resolvePhysicalScreenSize()
        createNotificationChannel()
        startForegroundCompat()
        setupOverlayViews()
        applyOverlayTheme()
        OverlayServiceState.setRunning(true)

        serviceScope.launch {
            settingsRepository.settingsFlow.collectLatest { latest ->
                settings = latest
                stopChipBtn.visibility = if (latest.showEmergencyStop) View.VISIBLE else View.GONE
                applyOverlayTheme()
                syncFlashRevealWatcher()
                if (calibrationMode) {
                    currentGeometry = latest.calibration.toBase6x5Geometry(screenW, screenH)
                    refreshPathCanvas()
                }
            }
        }
        serviceScope.launch {
            teamPresetRepository.observePresets().collectLatest { list ->
                presetRecords = list
                syncDrawerData()
            }
        }
        serviceScope.launch {
            ActiveStrategyStore.activeChips.collectLatest { chips ->
                activeWaveChips = chips
                refreshActiveShieldStrip()
                syncDrawerData()
            }
        }
        serviceScope.launch {
            ActiveStrategyStore.lastOcrSummary.collectLatest { summary ->
                lastOcrSummary = summary
                syncDrawerData()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        resolvePhysicalScreenSize()
        applyOverlayTheme()
        clampControlWindowToScreen()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        flashWatchJob?.cancel()
        triggerEmergencyStop()
        serviceScope.cancel()
        removeOverlayViews()
        OverlayServiceState.setRunning(false)
        super.onDestroy()
    }

    private fun resolvePhysicalScreenSize() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            screenW = bounds.width()
            screenH = bounds.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            screenW = dm.widthPixels
            screenH = dm.heightPixels
        }
    }

    private fun startForegroundCompat() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("AutoDora-NEXT 悬浮助手运行中")
            .setContentText("点击悬浮胶囊即可识盘解盾或自动转珠")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun activeTeamPreset(): TeamBasePreset {
        return presetRecords.firstOrNull { it.preset.id == settings.activePresetId }?.preset
            ?: presetRecords.firstOrNull()?.preset
            ?: TeamPresetRepository.defaultBuiltInPresets().first()
    }

    private fun composeEffectiveRuleConfig(
        board: BoardState,
        fixedStartCell: Int = -1,
        fastMode: Boolean = false
    ): RuleConfig {
        val preset = activeTeamPreset()
        val global = settings.toRuleConfig()
        val mergedBase = preset.baseConfig.copy(
            allowDiagonalMove = settings.allowDiagonalMove,
            maxSteps = maxOf(global.maxSteps, preset.baseConfig.maxSteps).coerceIn(8, 300),
            beamWidth = if (fastMode) minOf(settings.beamWidth, 140) else maxOf(settings.beamWidth, preset.baseConfig.beamWidth),
            maxTimeMs = if (fastMode) 170 else maxOf(global.maxTimeMs, preset.baseConfig.maxTimeMs),
            topK = if (fastMode) 2 else 5,
            fixedStartCell = fixedStartCell
        )
        val canonicalChips = activeWaveChips.map { WaveShieldCatalog.canonicalizeForComposer(it) }
        return RuleComposer.compose(
            base = preset.copy(baseConfig = mergedBase),
            activeChips = canonicalChips,
            board = board
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupOverlayViews() {
        // 1. Full-screen PathOverlayView (click-through normally; touchable in Calibration or Board Editor Mode!)
        pathOverlayView = PathOverlayView(this)
        pathOverlayView.onCalibrationBoundsChanged = { l, t, r, b, finished ->
            val bw = (r - l).coerceAtLeast(120f)
            val bh = (b - t).coerceAtLeast(100f)
            val cellW = bw / 6f
            val cellH = bh / 5f
            currentGeometry = BoardGeometry(l, t, r, b, 6, 5, false)
            if (finished) {
                serviceScope.launch {
                    settingsRepository.update { s ->
                        s.copy(
                            calibration = s.calibration.copy(
                                leftRatio = (l / screenW).coerceIn(0f, 0.35f),
                                rightRatio = (r / screenW).coerceIn(0.65f, 1f),
                                bottomRatio = (b / screenH).coerceIn(0.45f, 1f),
                                heightScale = (cellH / cellW).coerceIn(0.75f, 1.25f)
                            )
                        )
                    }
                }
            }
        }
        pathOverlayView.onBoardEditorCellTouched = { row, col ->
            val current = editedBoard
            if (current != null) {
                editedBoard = boardEditor.applyActiveBrush(current, row, col)
                refreshPathCanvas()
            }
        }
        pathParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = 0.80f // Android 12+ clean touch passthrough
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
            }
        }
        windowManager.addView(pathOverlayView, pathParams)

        // 2. System-Themed Floating Control Stack
        controlRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        statusOrbView = TextView(this).apply {
            text = "D"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            elevation = dp(3).toFloat()
            val size = dp(30)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = dp(4)
            }
        }
        topRow.addView(statusOrbView)

        toolbarContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            elevation = dp(3).toFloat()
            setPadding(dp(4), dp(3), dp(3), dp(3))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        solveBtn = makeChipBtn("算", minW = 24, padH = 5) { startCaptureAndSolve() }
        toolbarContainer.addView(solveBtn)

        prevBtn = makeChipBtn("◀", minW = 18, padH = 3) { switchSolution(-1) }
        toolbarContainer.addView(prevBtn)

        pagerText = TextView(this).apply {
            text = "0/0"
            textSize = 9f
            setSingleLine(true)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(2), 0, dp(2), 0)
        }
        toolbarContainer.addView(pagerText)

        nextBtn = makeChipBtn("▶", minW = 18, padH = 3) { switchSolution(+1) }
        toolbarContainer.addView(nextBtn)

        // Unified Execution Button: runs Hold-and-Replan when '按住转' toggle is ON, otherwise executes solved path!
        executeBtn = makeChipBtn("行", minW = 24, padH = 5) { handleUnifiedExecute() }
        toolbarContainer.addView(executeBtn)

        stopChipBtn = makeChipBtn("停", minW = 24, padH = 5) {
            triggerEmergencyStop()
            toast("已急停中止")
        }
        toolbarContainer.addView(stopChipBtn)

        // '线' controls ONLY trajectory lines (NOT the board grid!)
        pathToggleBtn = makeChipBtn("●线", minW = 28, padH = 4) {
            showPath = !showPath
            refreshMainToggleChips()
            refreshPathCanvas()
        }
        toolbarContainer.addView(pathToggleBtn)

        // '校' controls ONLY the board alignment grid & crosshairs
        calibToggleBtn = makeChipBtn("○校", minW = 28, padH = 4) { toggleCalibrationMode() }
        toolbarContainer.addView(calibToggleBtn)

        moreToggleBtn = makeChipBtn("⋯", minW = 20, padH = 3) {
            modeRowExpanded = !modeRowExpanded
            quickPanel.visibility = if (modeRowExpanded && toolbarExpanded) View.VISIBLE else View.GONE
            refreshMainToggleChips()
            clampControlWindowToScreen()
        }
        toolbarContainer.addView(moreToggleBtn)

        closeBtn = makeChipBtn("✕", minW = 20, padH = 3, marginEndDp = 0) { stopSelf() }
        toolbarContainer.addView(closeBtn)

        topRow.addView(toolbarContainer)
        controlRoot.addView(topRow)

        // Collapsible Quick Controls Card (2 clean rows under '⋯')
        quickPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            elevation = dp(2).toFloat()
            visibility = View.GONE
            setPadding(dp(5), dp(4), dp(5), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(3)
                marginStart = dp(34)
            }
        }

        // Row A: Mode Toggles + 1:1 Thermal Speed Preset
        val rowA = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        holdReplanToggleBtn = makeChipBtn("○按住转", padH = 5) {
            serviceScope.launch {
                val next = !settings.holdReplanEnabled
                settingsRepository.update { it.copy(holdReplanEnabled = next) }
                toast(if (next) "已开启「按住转」：点「行」将按住重算续滑" else "已关闭「按住转」：恢复常规解盘执行")
            }
        }
        rowA.addView(holdReplanToggleBtn)

        memoryChipBtn = makeChipBtn("○记忆", padH = 5) {
            serviceScope.launch {
                settingsRepository.update { it.copy(flashRevealMemoryEnabled = !it.flashRevealMemoryEnabled) }
            }
        }
        rowA.addView(memoryChipBtn)

        invertChipBtn = makeChipBtn("○颠倒", padH = 5) {
            serviceScope.launch {
                settingsRepository.update {
                    val next = !(it.invertDragX || it.invertDragY)
                    it.copy(invertDragX = next, invertDragY = next)
                }
            }
        }
        rowA.addView(invertChipBtn)

        speedChipBtn = makeChipBtn(SwipeSpeedPreset.STANDARD.compactLabel, padH = 6, marginEndDp = 0) {
            serviceScope.launch {
                settingsRepository.update { s ->
                    val nextPreset = SwipeSpeedPreset.fromStepDurationMs(s.stepDurationMs).next()
                    s.copy(stepDurationMs = nextPreset.stepDurationMs)
                }
            }
        }
        rowA.addView(speedChipBtn)
        quickPanel.addView(rowA)

        // Row B: Phase 3 Power Tools (4-Tab Tuning Drawer + Interactive Board Editor + Offline OCR)
        val rowB = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(3) }
        }
        tuningDrawerToggleBtn = makeChipBtn("○解盾调参", padH = 6) { toggleTuningDrawer() }
        rowB.addView(tuningDrawerToggleBtn)

        boardEditorToggleBtn = makeChipBtn("○盘面编绘", padH = 6) { toggleBoardEditorMode() }
        rowB.addView(boardEditorToggleBtn)

        ocrScanBtn = makeChipBtn("OCR 识敌技", padH = 6, marginEndDp = 0) { runOfflineEnemySkillOcr() }
        rowB.addView(ocrScanBtn)
        quickPanel.addView(rowB)
        controlRoot.addView(quickPanel)

        // Active Wave Shield Pills Strip (auto-visible when >=1 shield is active)
        activeShieldScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(2)
                marginStart = dp(34)
            }
        }
        activeShieldRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        activeShieldScroll.addView(activeShieldRow)
        controlRoot.addView(activeShieldScroll)

        // Ultra-Compact Solution Summary Strip (tap to fold/unfold orb pills!)
        summaryStrip = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            elevation = dp(2).toFloat()
            visibility = View.GONE
            setPadding(dp(7), dp(3), dp(7), dp(3))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(2)
                marginStart = dp(34)
            }
            setOnClickListener {
                summaryPillsExpanded = !summaryPillsExpanded
                groupsScroll.visibility = if (summaryPillsExpanded) View.VISIBLE else View.GONE
            }
        }
        summaryTitle = TextView(this).apply {
            textSize = 9.5f
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            typeface = Typeface.DEFAULT_BOLD
            maxWidth = screenW - dp(54)
        }
        summaryStrip.addView(summaryTitle)

        groupsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
        }
        groupsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        groupsScroll.addView(groupsRow)
        summaryStrip.addView(groupsScroll)
        controlRoot.addView(summaryStrip)

        // Slim Calibration Bar (visible only when '校' is ON)
        calibrationBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            elevation = dp(2).toFloat()
            visibility = View.GONE
            setPadding(dp(5), dp(3), dp(5), dp(3))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(3)
                marginStart = dp(34)
            }
        }
        calibNudgeBtns.clear()
        val nudgeSpecs = listOf(
            Triple("上", 22, { nudgeCalib(bottomDelta = -0.005f) }),
            Triple("下", 22, { nudgeCalib(bottomDelta = +0.005f) }),
            Triple("窄", 22, { nudgeCalib(sideInsetDelta = +0.004f) }),
            Triple("宽", 22, { nudgeCalib(sideInsetDelta = -0.004f) }),
            Triple("高-", 24, { nudgeCalib(heightScaleDelta = -0.01f) }),
            Triple("高+", 24, { nudgeCalib(heightScaleDelta = +0.01f) })
        )
        for ((lbl, mw, action) in nudgeSpecs) {
            val btn = makeChipBtn(lbl, minW = mw, padH = 4, onClick = action)
            calibNudgeBtns.add(btn)
            calibrationBar.addView(btn)
        }
        calibAutoBtn = makeChipBtn("自动", padH = 5) { runAutoCalibration() }
        calibrationBar.addView(calibAutoBtn)
        calibResetBtn = makeChipBtn("重置", padH = 5, marginEndDp = 0) { resetCalibration() }
        calibrationBar.addView(calibResetBtn)
        controlRoot.addView(calibrationBar)

        // Phase 3 Interactive Board Editor Brush Bar
        boardEditor = OverlayBoardEditorController(
            context = this,
            onSolveEditedBoard = { solveEditedBoardNow() },
            onClearAllMarkers = {
                val cur = editedBoard ?: return@OverlayBoardEditorController
                editedBoard = cur.copy(
                    cells = cur.cells.map { it.copy(variant = Variant.NORMAL, hazards = HazardBits.NONE) },
                    tileFlags = List(cur.cols * cur.rows) { TileFlagBits.NONE }
                )
                refreshPathCanvas()
            },
            onCloseEditor = { setBoardEditorMode(false) }
        )
        controlRoot.addView(boardEditor.rootView)

        // Phase 3 In-Game 4-Tab Tuning Drawer
        tuningDrawer = OverlayTuningDrawerController(
            context = this,
            onRunOcrScan = { runOfflineEnemySkillOcr() },
            onToggleWaveChip = { chip -> ActiveStrategyStore.toggleChip(chip) },
            onUpsertWaveChip = { chip -> ActiveStrategyStore.upsertChip(chip) },
            onClearWaveChips = {
                ActiveStrategyStore.clearAll()
                toast("已清空本波全部敌技盾")
            },
            onSelectPreset = { id ->
                serviceScope.launch {
                    settingsRepository.update { it.copy(activePresetId = id) }
                    val name = presetRecords.firstOrNull { it.preset.id == id }?.preset?.name ?: "已切换"
                    toast("当前队伍预设：$name")
                }
            },
            onUpdateActivePresetConfig = { transform ->
                serviceScope.launch {
                    val cur = activeTeamPreset()
                    val rec = presetRecords.firstOrNull { it.preset.id == cur.id }
                    teamPresetRepository.savePreset(
                        cur.copy(baseConfig = transform(cur.baseConfig)),
                        isBuiltIn = rec?.isBuiltIn ?: false
                    )
                }
            },
            onUpdateSettings = { transform ->
                serviceScope.launch { settingsRepository.update(transform) }
            },
            onClose = { setTuningDrawerOpen(false) }
        )
        controlRoot.addView(tuningDrawer.rootView)

        controlParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(6)
            y = dp(38)
        }

        // Drag & Tap handler on 3-state status orb
        var initX = 0
        var initY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        statusOrbView.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = controlParams.x
                    initY = controlParams.y
                    touchX = ev.rawX
                    touchY = ev.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - touchX).toInt()
                    val dy = (ev.rawY - touchY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) {
                        moved = true
                        val maxX = (screenW - controlRoot.width.coerceAtLeast(dp(40))).coerceAtLeast(0)
                        val maxY = (screenH - controlRoot.height.coerceAtLeast(dp(48))).coerceAtLeast(0)
                        controlParams.x = (initX + dx).coerceIn(0, maxX)
                        controlParams.y = (initY + dy).coerceIn(0, maxY)
                        windowManager.updateViewLayout(controlRoot, controlParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        toolbarExpanded = !toolbarExpanded
                        toolbarContainer.visibility = if (toolbarExpanded) View.VISIBLE else View.GONE
                        quickPanel.visibility = if (toolbarExpanded && modeRowExpanded) View.VISIBLE else View.GONE
                        tuningDrawer.setVisible(toolbarExpanded && tuningDrawerOpen)
                        boardEditor.rootView.visibility = if (toolbarExpanded && boardEditorMode) View.VISIBLE else View.GONE
                        if (!toolbarExpanded) {
                            calibrationBar.visibility = View.GONE
                            summaryStrip.visibility = View.GONE
                            activeShieldScroll.visibility = View.GONE
                        } else {
                            if (calibrationMode) calibrationBar.visibility = View.VISIBLE
                            if (lastSolveOutput != null) summaryStrip.visibility = View.VISIBLE
                            refreshActiveShieldStrip()
                        }
                        clampControlWindowToScreen()
                    }
                    true
                }
                else -> false
            }
        }
        windowManager.addView(controlRoot, controlParams)
    }

    // ==================== Unified Execution ('行') ====================
    private fun handleUnifiedExecute() {
        if (settings.holdReplanEnabled) {
            startHoldAndReplanExecution()
        } else {
            executeSelectedSolution()
        }
    }

    // ==================== Capture & Solve ('算') ====================
    private fun startCaptureAndSolve() {
        if (currentPhase != OverlayPhase.IDLE) {
            toast("任务进行中，可点「停」立即中止")
            return
        }
        val capturer = ScreenCaptureSession.capturer.value
        if (capturer == null || !capturer.isReady.value) {
            toast("请先在主界面授权「屏幕采集」")
            return
        }

        if (calibrationMode) setCalibrationMode(false)
        if (boardEditorMode) setBoardEditorMode(false)
        if (tuningDrawerOpen) setTuningDrawerOpen(false)

        activeJob = serviceScope.launch {
            setPhase(OverlayPhase.CALCULATING)
            pathOverlayView.updateState(null, null, emptyList(), null, false, false, false)
            delay(45)

            val frames = capturer.captureMultiFrames(count = settings.multiFrameCount, intervalMs = 50L)
            if (frames.isEmpty()) {
                setPhase(OverlayPhase.IDLE)
                refreshPathCanvas()
                toast("截屏失败，请确认屏幕采集授权仍有效")
                return@launch
            }

            try {
                val geom = withContext(Dispatchers.Default) {
                    AutoGridDetector.detect(frames.first(), settings.calibration)
                }
                val liveRec = boardRecognizer.recognize(frames, geom)

                var resolvedFromMemory = false
                val boardToSolve = if (settings.flashRevealMemoryEnabled) {
                    when (val res = flashMemoryTracker.resolveForSolve(liveRec)) {
                        is FlashRevealMemoryTracker.MemoryResolution.UseMemorizedSnapshot -> {
                            resolvedFromMemory = true
                            toast("已从先露后藏记忆还原 ${res.liveHiddenCount} 颗问号珠")
                            res.rememberedBoard
                        }
                        is FlashRevealMemoryTracker.MemoryResolution.UseLiveBoard -> liveRec.board
                    }
                } else {
                    liveRec.board
                }

                val totalCells = (boardToSolve.cols * boardToSolve.rows).coerceAtLeast(1)
                val unknownCells = boardToSolve.cells.count {
                    it.element == Element.UNKNOWN || it.element == Element.EMPTY
                }
                val isGenuineQuestionBoard = resolvedFromMemory || liveRec.questionOrbsDetected >= 6
                val notInBattleBoard = !isGenuineQuestionBoard && (
                    unknownCells >= (totalCells * 0.40f).toInt() ||
                        liveRec.lowConfidenceIndices.size >= (totalCells * 0.65f).toInt() ||
                        liveRec.averageConfidence < 0.38f
                    )
                if (notInBattleBoard) {
                    hidePathOverlay()
                    setPhase(OverlayPhase.IDLE)
                    toast("未检测到战斗符石盘面，请进入战斗后再点「算」")
                    return@launch
                }

                currentGeometry = geom
                lastRecognition = liveRec
                editedBoard = boardToSolve
                usedMemorizedSnapshot = resolvedFromMemory
                updateQuickModeChips()

                val ruleConfig = composeEffectiveRuleConfig(boardToSolve)
                val token = NativeSolver.createCancelToken()
                activeCancelToken = token
                val solveOut = try {
                    NativeSolver.solve(boardToSolve, ruleConfig, token)
                } finally {
                    activeCancelToken = null
                    token.close()
                }

                val bestSol = solveOut.solutions.firstOrNull()
                if (bestSol == null || (bestSol.totalCombo == 0 && bestSol.stepCount == 0)) {
                    hidePathOverlay()
                    setPhase(OverlayPhase.IDLE)
                    toast("当前盘面无可消除路线，请确认战斗状态或检查校准框")
                    return@launch
                }

                lastSolveOutput = solveOut
                selectedSolutionIdx = 0
                showPath = true
                refreshMainToggleChips()
                updateSummaryStrip()
                refreshPathCanvas()
                setPhase(OverlayPhase.IDLE)

                if (!settings.testModeOnlyDraw) {
                    handleUnifiedExecute()
                }
            } catch (e: CancellationException) {
                setPhase(OverlayPhase.IDLE)
            } catch (e: Exception) {
                Log.e(TAG, "Capture/Solve failed", e)
                setPhase(OverlayPhase.IDLE)
                toast("识盘算路异常：${e.message}")
            } finally {
                frames.forEach { if (!it.isRecycled) it.recycle() }
            }
        }
    }

    // ==================== Solve Manually Painted Board ('算此盘') ====================
    private fun solveEditedBoardNow() {
        val board = editedBoard ?: run {
            toast("当前无盘面数据，请先点「算」或进入战斗")
            return
        }
        val geom = currentGeometry ?: settings.calibration.toBase6x5Geometry(screenW, screenH)
        setBoardEditorMode(false)
        activeJob = serviceScope.launch {
            setPhase(OverlayPhase.CALCULATING)
            try {
                val ruleConfig = composeEffectiveRuleConfig(board)
                val token = NativeSolver.createCancelToken()
                activeCancelToken = token
                val solveOut = try {
                    NativeSolver.solve(board, ruleConfig, token)
                } finally {
                    activeCancelToken = null
                    token.close()
                }
                if (solveOut.solutions.isEmpty()) {
                    hidePathOverlay()
                    toast("编绘盘面未找到可行解，请检查冲突规则或标记")
                } else {
                    currentGeometry = geom
                    lastSolveOutput = solveOut
                    selectedSolutionIdx = 0
                    showPath = true
                    refreshMainToggleChips()
                    updateSummaryStrip()
                    refreshPathCanvas()
                }
            } finally {
                setPhase(OverlayPhase.IDLE)
            }
        }
    }

    // ==================== Offline Chinese OCR Enemy Skill Scan ('OCR 识敌技') ====================
    private fun runOfflineEnemySkillOcr() {
        if (currentPhase != OverlayPhase.IDLE) {
            toast("任务进行中，请稍候")
            return
        }
        val capturer = ScreenCaptureSession.capturer.value
        if (capturer == null || !capturer.isReady.value) {
            toast("请先在主界面授权「屏幕采集」")
            return
        }
        activeJob = serviceScope.launch {
            setPhase(OverlayPhase.CALCULATING)
            val wasDrawerOpen = tuningDrawerOpen
            if (wasDrawerOpen) tuningDrawer.setVisible(false)
            delay(50)

            val bmp = capturer.captureSingleFrame()
            if (bmp == null) {
                if (wasDrawerOpen) tuningDrawer.setVisible(true)
                setPhase(OverlayPhase.IDLE)
                toast("截屏失败，请重试")
                return@launch
            }
            try {
                val parsed = withContext(Dispatchers.Default) {
                    enemySkillOcrEngine.recognizeEnemySkills(bmp)
                }
                val greyNote = if (parsed.skippedGreyLines > 0) "（已自动过滤 ${parsed.skippedGreyLines} 条灰字已无视敌技）" else ""
                if (parsed.chips.isEmpty()) {
                    ActiveStrategyStore.applyOcrChips(emptyList(), "未匹配到已知敌技词条$greyNote")
                    toast("未识别到已知敌技盾，请先长按敌人打开技能弹窗$greyNote")
                } else {
                    val names = parsed.chips.joinToString(" · ") { it.label }
                    ActiveStrategyStore.applyOcrChips(parsed.chips, "OCR 已启用：${names}$greyNote")
                    toast("识别并启用 ${parsed.chips.size} 项敌技盾：$names")
                }
            } catch (e: Exception) {
                Log.e(TAG, "OCR failed", e)
                toast("离线 OCR 识别异常：${e.message}")
            } finally {
                if (!bmp.isRecycled) bmp.recycle()
                if (wasDrawerOpen) tuningDrawer.setVisible(true)
                setPhase(OverlayPhase.IDLE)
            }
        }
    }

    // ==================== No-Root Hold-and-Replan Execution ====================
    private fun startHoldAndReplanExecution() {
        if (currentPhase != OverlayPhase.IDLE) {
            toast("任务进行中，可点「停」立即中止")
            return
        }
        val capturer = ScreenCaptureSession.capturer.value
        if (capturer == null || !capturer.isReady.value) {
            toast("请先在主界面授权「屏幕采集」")
            return
        }
        if (!touchExecutor.isAvailable.value) {
            toast("请先开启 AutoDora-NEXT 无障碍服务")
            return
        }
        if (calibrationMode) setCalibrationMode(false)
        if (boardEditorMode) setBoardEditorMode(false)
        if (tuningDrawerOpen) setTuningDrawerOpen(false)

        activeJob = serviceScope.launch {
            setPhase(OverlayPhase.MOVING)
            pathOverlayView.updateState(null, null, emptyList(), null, false, false, false)
            delay(35)

            val preBmp = capturer.captureSingleFrame()
            val geom = if (preBmp != null) {
                try {
                    withContext(Dispatchers.Default) {
                        AutoGridDetector.detect(preBmp, settings.calibration)
                    }
                } finally {
                    if (!preBmp.isRecycled) preBmp.recycle()
                }
            } else {
                settings.calibration.toBase6x5Geometry(screenW, screenH)
            }
            currentGeometry = geom

            val startRow = (geom.rows / 2).coerceIn(0, geom.rows - 1)
            val startCol = ((geom.cols - 1) / 2).coerceIn(0, geom.cols - 1)
            val startCell = CellCoord(startRow, startCol)
            val nudgeCell = if (settings.holdReplanNudge && startCol + 1 < geom.cols) {
                CellCoord(startRow, startCol + 1)
            } else null
            val trigger = if (nudgeCell != null) {
                HoldReplanTrigger.NUDGE_ONE_CELL_HOLD
            } else {
                HoldReplanTrigger.HOLD_IN_PLACE
            }

            setControlBarTouchable(!overlapsBoard(geom))
            try {
                val result = touchExecutor.executeHoldAndReplan(
                    startCell = startCell,
                    geometry = geom,
                    trigger = trigger,
                    nudgeTarget = nudgeCell,
                    revealDelayMs = settings.holdReplanDelayMs,
                    config = settings.toSwipeConfig()
                ) { heldCell ->
                    val revealedBmp = capturer.captureSingleFrame() ?: return@executeHoldAndReplan null
                    try {
                        val rec = boardRecognizer.recognize(listOf(revealedBmp), geom)
                        lastRecognition = rec
                        editedBoard = rec.board
                        usedMemorizedSnapshot = false

                        val heldIdx = heldCell.row * geom.cols + heldCell.col
                        val composed = composeEffectiveRuleConfig(rec.board, fixedStartCell = heldIdx, fastMode = true)
                        val token = NativeSolver.createCancelToken()
                        activeCancelToken = token
                        val solveOut = try {
                            NativeSolver.solve(rec.board, composed, token)
                        } finally {
                            activeCancelToken = null
                            token.close()
                        }
                        lastSolveOutput = solveOut
                        selectedSolutionIdx = 0
                        withContext(Dispatchers.Main) {
                            showPath = true
                            refreshMainToggleChips()
                            updateSummaryStrip()
                            refreshPathCanvas()
                        }
                        solveOut.solutions.firstOrNull()
                    } finally {
                        if (!revealedBmp.isRecycled) revealedBmp.recycle()
                    }
                }
                when (result) {
                    is TouchExecutionResult.Success -> {
                        flashMemoryTracker.clearLock()
                        usedMemorizedSnapshot = false
                        hidePathOverlay()
                        Log.i(TAG, "Hold-and-Replan completed in ${result.elapsedMs}ms (${result.segmentsDispatched} segs)")
                    }
                    is TouchExecutionResult.Cancelled -> {
                        hidePathOverlay()
                        toast("按住重算已中止")
                    }
                    is TouchExecutionResult.Failed -> toast("按住重算失败：${result.reason}")
                }
            } finally {
                setControlBarTouchable(true)
                setPhase(OverlayPhase.IDLE)
                updateQuickModeChips()
                refreshPathCanvas()
            }
        }
    }

    // ==================== Standard Path Execution ====================
    private fun executeSelectedSolution() {
        if (currentPhase != OverlayPhase.IDLE) return
        val geom = currentGeometry ?: return
        val sol = currentSolution() ?: run {
            toast("请先点击「算」生成路线")
            return
        }
        if (!touchExecutor.isAvailable.value) {
            toast("请先开启 AutoDora-NEXT 无障碍服务")
            return
        }

        activeJob = serviceScope.launch {
            setPhase(OverlayPhase.MOVING)

            if (settings.countdownSeconds > 0) {
                for (sec in settings.countdownSeconds downTo 1) {
                    refreshPathCanvas(countdown = "$sec")
                    delay(1000)
                }
                refreshPathCanvas(countdown = null)
            }

            setControlBarTouchable(!overlapsBoard(geom))
            try {
                delay(35)
                val result = touchExecutor.executePath(sol, geom, settings.toSwipeConfig())
                when (result) {
                    is TouchExecutionResult.Success -> {
                        flashMemoryTracker.clearLock()
                        usedMemorizedSnapshot = false
                        hidePathOverlay()
                        updateQuickModeChips()
                        Log.i(TAG, "Auto-swipe completed in ${result.elapsedMs}ms (${result.segmentsDispatched} segs)")
                    }
                    is TouchExecutionResult.Cancelled -> {
                        hidePathOverlay()
                        toast("自动转珠已中止")
                    }
                    is TouchExecutionResult.Failed -> toast("自动转珠失败：${result.reason}")
                }
            } finally {
                setControlBarTouchable(true)
                setPhase(OverlayPhase.IDLE)
            }
        }
    }

    private fun triggerEmergencyStop() {
        activeCancelToken?.cancel()
        touchExecutor.emergencyStop()
        activeJob?.cancel()
        activeJob = null
        setControlBarTouchable(true)
        setPhase(OverlayPhase.IDLE)
        hidePathOverlay()
    }

    private fun hidePathOverlay() {
        showPath = false
        refreshMainToggleChips()
        refreshPathCanvas(countdown = null)
    }

    private fun switchSolution(delta: Int) {
        val list = lastSolveOutput?.solutions ?: return
        if (list.isEmpty()) return
        selectedSolutionIdx = (selectedSolutionIdx + delta + list.size) % list.size
        updateSummaryStrip()
        refreshPathCanvas()
    }

    // ==================== Modes: Calibration ('校') / Board Editor ('盘面编绘') / Tuning Drawer ('解盾调参') ====================
    private fun toggleCalibrationMode() {
        setCalibrationMode(!calibrationMode)
    }

    private fun setCalibrationMode(enabled: Boolean) {
        if (enabled && boardEditorMode) setBoardEditorMode(false)
        if (enabled && tuningDrawerOpen) setTuningDrawerOpen(false)
        calibrationMode = enabled
        calibrationBar.visibility = if (calibrationMode) View.VISIBLE else View.GONE
        updatePathOverlayTouchability()
        if (calibrationMode) {
            currentGeometry = settings.calibration.toBase6x5Geometry(screenW, screenH)
        }
        refreshMainToggleChips()
        refreshPathCanvas()
        clampControlWindowToScreen()
    }

    private fun toggleBoardEditorMode() {
        setBoardEditorMode(!boardEditorMode)
    }

    private fun setBoardEditorMode(enabled: Boolean) {
        if (enabled && calibrationMode) setCalibrationMode(false)
        if (enabled && tuningDrawerOpen) setTuningDrawerOpen(false)
        boardEditorMode = enabled
        boardEditor.rootView.visibility = if (boardEditorMode && toolbarExpanded) View.VISIBLE else View.GONE
        if (boardEditorMode) {
            val geom = currentGeometry ?: settings.calibration.toBase6x5Geometry(screenW, screenH)
            currentGeometry = geom
            if (editedBoard == null || editedBoard!!.cols != geom.cols || editedBoard!!.rows != geom.rows) {
                editedBoard = lastRecognition?.board ?: BoardState(
                    cols = geom.cols,
                    rows = geom.rows,
                    cells = List(geom.cols * geom.rows) { i -> CellState(Element.MATCHABLE[i % 6]) }
                )
            }
        }
        updatePathOverlayTouchability()
        updateQuickModeChips()
        refreshPathCanvas()
        clampControlWindowToScreen()
    }

    private fun toggleTuningDrawer() {
        setTuningDrawerOpen(!tuningDrawerOpen)
    }

    private fun setTuningDrawerOpen(open: Boolean) {
        if (open && calibrationMode) setCalibrationMode(false)
        if (open && boardEditorMode) setBoardEditorMode(false)
        tuningDrawerOpen = open
        tuningDrawer.setVisible(tuningDrawerOpen && toolbarExpanded)
        updateQuickModeChips()
        syncDrawerData()
        clampControlWindowToScreen()
    }

    private fun updatePathOverlayTouchability() {
        val wantTouchable = calibrationMode || boardEditorMode
        val nextFlags = if (wantTouchable) {
            pathParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            pathParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        if (nextFlags != pathParams.flags) {
            pathParams.flags = nextFlags
            try {
                windowManager.updateViewLayout(pathOverlayView, pathParams)
            } catch (_: Exception) {
            }
        }
    }

    private fun nudgeCalib(
        bottomDelta: Float = 0f,
        sideInsetDelta: Float = 0f,
        heightScaleDelta: Float = 0f
    ) {
        serviceScope.launch {
            settingsRepository.update { s ->
                val c = s.calibration
                val curBottom = c.effectiveBottomRatio(screenW, screenH)
                s.copy(
                    calibration = c.copy(
                        leftRatio = (c.leftRatio + sideInsetDelta).coerceIn(0f, 0.25f),
                        rightRatio = (c.rightRatio - sideInsetDelta).coerceIn(0.75f, 1f),
                        bottomRatio = (curBottom + bottomDelta).coerceIn(0.50f, 1f),
                        heightScale = (c.heightScale + heightScaleDelta).coerceIn(0.80f, 1.20f)
                    )
                )
            }
        }
    }

    private fun runAutoCalibration() {
        val capturer = ScreenCaptureSession.capturer.value
        if (capturer == null || !capturer.isReady.value) {
            toast("自动校准需要先授权屏幕采集")
            return
        }
        serviceScope.launch {
            pathOverlayView.updateState(null, null, emptyList(), null, false, false, false)
            delay(40)
            val bmp = capturer.captureSingleFrame() ?: run {
                refreshPathCanvas()
                return@launch
            }
            try {
                val detected = withContext(Dispatchers.Default) {
                    val frame = com.autodora.next.capture.BitmapPixelFrame(bmp)
                    AutoGridDetector.autoCalibrateFromRealScreen(frame)
                        ?: AutoGridDetector.detectArbitraryGrid(frame)
                }
                if (detected != null) {
                    currentGeometry = detected
                    settingsRepository.update { s ->
                        s.copy(
                            calibration = s.calibration.copy(
                                leftRatio = detected.leftPx / bmp.width,
                                rightRatio = detected.rightPx / bmp.width,
                                bottomRatio = detected.bottomPx / bmp.height,
                                heightScale = detected.cellHeight / detected.cellWidth
                            )
                        )
                    }
                    refreshPathCanvas()
                    toast("血条锚定成功：${detected.cols}×${detected.rows} 棋盘")
                } else {
                    refreshPathCanvas()
                    toast("未识别到战斗盘面，请进入战斗后重试或直接拖拽四角校准")
                }
            } finally {
                if (!bmp.isRecycled) bmp.recycle()
            }
        }
    }

    private fun resetCalibration() {
        serviceScope.launch {
            settingsRepository.update { it.copy(calibration = BoardCalibration()) }
            toast("已恢复自动 9:16 比例校准")
        }
    }

    // ==================== Background Flash-Reveal Watcher ====================
    private fun syncFlashRevealWatcher() {
        if (!settings.flashRevealMemoryEnabled) {
            flashWatchJob?.cancel()
            flashWatchJob = null
            flashMemoryTracker.resetAll()
            if (usedMemorizedSnapshot) {
                usedMemorizedSnapshot = false
                updateSummaryStrip()
                refreshPathCanvas()
            }
            updateQuickModeChips()
            return
        }
        if (flashWatchJob?.isActive == true) return
        flashWatchJob = serviceScope.launch {
            while (isActive) {
                delay(420L)
                if (currentPhase != OverlayPhase.IDLE || calibrationMode || boardEditorMode) continue
                val capturer = ScreenCaptureSession.capturer.value ?: continue
                if (!capturer.isReady.value) continue
                if (lastSolveOutput != null && showPath) continue

                val bmp = capturer.captureSingleFrame() ?: continue
                try {
                    val (geom, rec) = withContext(Dispatchers.Default) {
                        val g = AutoGridDetector.detect(bmp, settings.calibration)
                        g to boardRecognizer.recognize(listOf(bmp), g)
                    }
                    val lockedNow = flashMemoryTracker.onWatchFrame(rec)
                    if (lockedNow) {
                        currentGeometry = geom
                        refreshPathCanvas()
                        toast("已捕捉先露后藏盘面，点「算」即可解全版问号珠")
                    }
                    updateQuickModeChips()
                } catch (_: Exception) {
                } finally {
                    if (!bmp.isRecycled) bmp.recycle()
                }
            }
        }
    }

    // ==================== System Material 3 Theme & Unified Toggle Styling ====================
    private fun applyOverlayTheme() {
        if (!::toolbarContainer.isInitialized) return
        palette = OverlayThemeResolver.resolve(this, settings.themeMode)
        val p = palette

        toolbarContainer.background = roundedBg(p.containerBg, dp(16).toFloat(), p.containerStroke)
        quickPanel.background = roundedBg(p.containerBg, dp(12).toFloat(), p.containerStroke)
        summaryStrip.background = roundedBg(p.containerBg, dp(10).toFloat(), p.containerStroke)
        calibrationBar.background = roundedBg(p.containerBg, dp(11).toFloat(), p.tertiary)

        pagerText.setTextColor(p.onSurface)
        summaryTitle.setTextColor(p.onSurface)

        styleChip(solveBtn, p.primary, p.onPrimary, null)
        styleChip(prevBtn, p.chipOffBg, p.onSurface, p.chipOffStroke)
        styleChip(nextBtn, p.chipOffBg, p.onSurface, p.chipOffStroke)
        styleChip(closeBtn, p.chipOffBg, p.onSurfaceVariant, p.chipOffStroke)
        styleChip(ocrScanBtn, p.primaryContainer, p.onPrimaryContainer, p.primary)

        for (btn in calibNudgeBtns) {
            styleChip(btn, p.secondaryContainer, p.onSecondaryContainer, null)
        }
        styleChip(calibAutoBtn, p.primary, p.onPrimary, null)
        styleChip(calibResetBtn, p.errorContainer, p.onErrorContainer, null)

        boardEditor.applyTheme(p)
        setPhase(currentPhase)
        refreshMainToggleChips()
        updateQuickModeChips()
        refreshActiveShieldStrip()
        syncDrawerData()
    }

    private fun refreshMainToggleChips() {
        if (!::pathToggleBtn.isInitialized || !::palette.isInitialized) return
        val p = palette

        // '行' reflects unified execution mode (Hold-and-Replan highlight vs Standard execute)
        if (settings.holdReplanEnabled) {
            executeBtn.text = "行"
            styleChip(executeBtn, p.tertiaryContainer, p.onTertiaryContainer, p.tertiary)
        } else {
            executeBtn.text = "行"
            styleChip(executeBtn, p.primaryContainer, p.onPrimaryContainer, p.primary)
        }

        // '线' controls swipe trajectory path ONLY
        pathToggleBtn.text = if (showPath) "●线" else "○线"
        styleToggleChip(pathToggleBtn, isOn = showPath, onBg = p.primary, onText = p.onPrimary)

        // '校' controls board alignment grid ONLY
        calibToggleBtn.text = if (calibrationMode) "●校" else "○校"
        styleToggleChip(calibToggleBtn, isOn = calibrationMode, onBg = p.tertiary, onText = p.onTertiary)

        // '⋯' / '▾' quick panel drawer toggle
        moreToggleBtn.text = if (modeRowExpanded) "▾" else "⋯"
        if (modeRowExpanded) {
            styleChip(moreToggleBtn, p.primaryContainer, p.onPrimaryContainer, p.primary)
        } else {
            styleChip(moreToggleBtn, p.chipOffBg, p.chipOffText, p.chipOffStroke)
        }
    }

    private fun updateQuickModeChips() {
        if (!::memoryChipBtn.isInitialized || !::palette.isInitialized) return
        val p = palette

        // 1. Hold-and-Replan Toggle (按住转 -> unified via '行')
        holdReplanToggleBtn.text = if (settings.holdReplanEnabled) "●按住转" else "○按住转"
        styleToggleChip(holdReplanToggleBtn, isOn = settings.holdReplanEnabled, onBg = p.tertiary, onText = p.onTertiary)

        // 2. Flash-Reveal Memory Toggle (先露后藏记忆)
        val memLocked = flashMemoryTracker.currentLockedSnapshot() != null
        val memFresh = flashMemoryTracker.hasFreshClearMemory()
        when {
            !settings.flashRevealMemoryEnabled -> {
                memoryChipBtn.text = "○记忆"
                styleToggleChip(memoryChipBtn, isOn = false, onBg = p.primary, onText = p.onPrimary)
            }
            memLocked -> {
                memoryChipBtn.text = "●透视?"
                styleToggleChip(memoryChipBtn, isOn = true, onBg = p.tertiary, onText = p.onTertiary, onStroke = p.onTertiary)
            }
            memFresh -> {
                memoryChipBtn.text = "●记忆✓"
                styleToggleChip(memoryChipBtn, isOn = true, onBg = p.primary, onText = p.onPrimary, onStroke = p.onPrimary)
            }
            else -> {
                memoryChipBtn.text = "●记忆"
                styleToggleChip(memoryChipBtn, isOn = true, onBg = p.primary, onText = p.onPrimary)
            }
        }

        // 3. Inverted Drag Controls Toggle (颠倒操控)
        val invOn = settings.invertDragX || settings.invertDragY
        invertChipBtn.text = if (invOn) "●颠倒" else "○颠倒"
        styleToggleChip(invertChipBtn, isOn = invOn, onBg = p.tertiary, onText = p.onTertiary)

        // 4. Unified 1:1 Thermal Speed Preset (Red -> Orange -> Primary Blue -> Emerald, uniform 1dp border!)
        val preset = SwipeSpeedPreset.fromStepDurationMs(settings.stepDurationMs)
        val spdStyle = p.speedStyle(preset)
        speedChipBtn.text = preset.compactLabel
        styleChip(speedChipBtn, spdStyle.bg, spdStyle.fg, spdStyle.stroke)

        // 5. Phase 3 Drawer & Board Editor Toggles
        tuningDrawerToggleBtn.text = if (tuningDrawerOpen) "●解盾调参" else "○解盾调参"
        styleToggleChip(tuningDrawerToggleBtn, isOn = tuningDrawerOpen, onBg = p.primary, onText = p.onPrimary)

        boardEditorToggleBtn.text = if (boardEditorMode) "●盘面编绘" else "○盘面编绘"
        styleToggleChip(boardEditorToggleBtn, isOn = boardEditorMode, onBg = p.tertiary, onText = p.onTertiary)

        refreshMainToggleChips()
    }

    private fun refreshActiveShieldStrip() {
        if (!::activeShieldRow.isInitialized || !::palette.isInitialized) return
        activeShieldRow.removeAllViews()
        if (activeWaveChips.isEmpty() || !toolbarExpanded) {
            activeShieldScroll.visibility = View.GONE
            return
        }
        val p = palette
        for (chip in activeWaveChips) {
            val pill = TextView(this).apply {
                text = "${chip.label} ×"
                textSize = 9f
                typeface = Typeface.DEFAULT_BOLD
                setSingleLine(true)
                setTextColor(p.onPrimaryContainer)
                setPadding(dp(6), dp(2), dp(6), dp(2))
                background = roundedBg(p.primaryContainer, dp(6).toFloat(), p.primary)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(3) }
                setOnClickListener { ActiveStrategyStore.removeChip(chip.id) }
            }
            activeShieldRow.addView(pill)
        }
        val clearAll = TextView(this).apply {
            text = "清空"
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            setSingleLine(true)
            setTextColor(p.onErrorContainer)
            setPadding(dp(5), dp(2), dp(5), dp(2))
            background = roundedBg(p.errorContainer, dp(6).toFloat(), null)
            setOnClickListener { ActiveStrategyStore.clearAll() }
        }
        activeShieldRow.addView(clearAll)
        activeShieldScroll.visibility = View.VISIBLE
        clampControlWindowToScreen()
    }

    private fun syncDrawerData() {
        if (!::tuningDrawer.isInitialized || !::palette.isInitialized) return
        tuningDrawer.updateData(
            palette = palette,
            settings = settings,
            presets = presetRecords,
            activePreset = activeTeamPreset(),
            activeChips = activeWaveChips,
            ocrSummary = lastOcrSummary
        )
    }

    private fun currentSolution(): SolutionPath? {
        val list = lastSolveOutput?.solutions ?: return null
        return list.getOrNull(selectedSolutionIdx)
    }

    private fun updateSummaryStrip() {
        val out = lastSolveOutput
        val sol = currentSolution()
        val geom = currentGeometry
        if (out == null || sol == null || geom == null) {
            pagerText.text = "0/0"
            summaryStrip.visibility = View.GONE
            return
        }

        pagerText.text = "${selectedSolutionIdx + 1}/${out.solutions.size}"
        val sizeBadge = if (geom.isWeijianExpanded) "${geom.cols}×${geom.rows} 违建" else "${geom.cols}×${geom.rows}"
        val shieldBadge = if (sol.allHardConstraintsMet) "✓" else "!"
        val memTag = if (usedMemorizedSnapshot) " · 记忆" else ""
        val lowWarn = if (usedMemorizedSnapshot) 0 else (lastRecognition?.lowConfidenceIndices?.size ?: 0)
        val lowStr = if (lowWarn > 0) " · 低置信 $lowWarn" else ""

        summaryTitle.text = "$sizeBadge · ${sol.totalCombo}C(首${sol.firstWaveCombo}) · ${sol.stepCount}步 · ${out.elapsedMs.toInt()}ms $shieldBadge$memTag$lowStr"

        groupsRow.removeAllViews()
        for (grp in sol.groups) {
            val pill = TextView(this).apply {
                val ench = if (grp.hasEnchanted) "+" else ""
                val waveMark = if (grp.wave > 1) "↓" else ""
                text = "${PathOverlayView.elementShortZh(grp.element)}${grp.orbCount}$ench$waveMark"
                textSize = 9f
                setSingleLine(true)
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(4), dp(1), dp(4), dp(1))
                background = roundedBg(PathOverlayView.elementColor(grp.element), dp(4).toFloat(), Color.argb(95, 255, 255, 255))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(2) }
            }
            groupsRow.addView(pill)
        }
        groupsScroll.visibility = if (summaryPillsExpanded && sol.groups.isNotEmpty()) View.VISIBLE else View.GONE
        summaryStrip.visibility = if (toolbarExpanded) View.VISIBLE else View.GONE
        summaryTitle.requestLayout()
        summaryStrip.requestLayout()
        clampControlWindowToScreen()
    }

    private fun refreshPathCanvas(countdown: String? = null) {
        val memBoard = flashMemoryTracker.currentLockedSnapshot()
        val displayBoard = when {
            boardEditorMode -> editedBoard ?: lastRecognition?.board
            usedMemorizedSnapshot && memBoard != null -> memBoard
            else -> lastRecognition?.board
        }
        pathOverlayView.updateState(
            geometry = currentGeometry,
            board = displayBoard,
            lowConfidence = if (usedMemorizedSnapshot || boardEditorMode) emptyList() else (lastRecognition?.lowConfidenceIndices ?: emptyList()),
            solution = currentSolution(),
            showPath = showPath,
            showOrbBadges = settings.showOrbBadgesOnPath,
            calibrationMode = calibrationMode,
            countdownText = countdown,
            memorizedOverlayBoard = memBoard,
            boardEditorMode = boardEditorMode
        )
    }

    private fun setPhase(phase: OverlayPhase) {
        currentPhase = phase
        if (!::palette.isInitialized || !::statusOrbView.isInitialized) return
        val p = palette
        when (phase) {
            OverlayPhase.IDLE -> {
                statusOrbView.text = "D"
                statusOrbView.setTextColor(p.onPrimary)
                statusOrbView.background = circleBg(p.primary, p.orbStroke)
                if (::stopChipBtn.isInitialized) {
                    styleChip(stopChipBtn, p.errorContainer, p.onErrorContainer, null)
                }
            }
            OverlayPhase.CALCULATING -> {
                statusOrbView.text = "算"
                statusOrbView.setTextColor(p.onTertiary)
                statusOrbView.background = circleBg(p.tertiary, p.orbStroke)
                if (::stopChipBtn.isInitialized) {
                    styleChip(stopChipBtn, p.error, p.onError, p.onError)
                }
            }
            OverlayPhase.MOVING -> {
                statusOrbView.text = "转"
                statusOrbView.setTextColor(p.onPrimaryContainer)
                statusOrbView.background = circleBg(p.primaryContainer, p.primary)
                if (::stopChipBtn.isInitialized) {
                    styleChip(stopChipBtn, p.error, p.onError, p.onError)
                }
            }
        }
    }

    private fun styleChip(view: TextView, bgColor: Int, textColor: Int, strokeColor: Int?) {
        view.setTextColor(textColor)
        view.background = roundedBg(bgColor, dp(8).toFloat(), strokeColor)
        view.alpha = 1.0f
    }

    private fun styleToggleChip(
        view: TextView,
        isOn: Boolean,
        onBg: Int,
        onText: Int,
        onStroke: Int? = null
    ) {
        val p = palette
        if (isOn) styleChip(view, onBg, onText, onStroke)
        else styleChip(view, p.chipOffBg, p.chipOffText, p.chipOffStroke)
    }

    private fun clampControlWindowToScreen() {
        controlRoot.post {
            val w = controlRoot.width
            val h = controlRoot.height
            if (w <= 0 || h <= 0) return@post
            val maxX = (screenW - w).coerceAtLeast(0)
            val maxY = (screenH - h).coerceAtLeast(0)
            val clampedX = controlParams.x.coerceIn(0, maxX)
            val clampedY = controlParams.y.coerceIn(0, maxY)
            if (clampedX != controlParams.x || clampedY != controlParams.y) {
                controlParams.x = clampedX
                controlParams.y = clampedY
                try {
                    windowManager.updateViewLayout(controlRoot, controlParams)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun overlapsBoard(geom: BoardGeometry): Boolean {
        val barBottom = controlParams.y + controlRoot.height.coerceAtLeast(dp(56))
        return barBottom > (geom.topPx - dp(16))
    }

    private fun setControlBarTouchable(touchable: Boolean) {
        val nextFlags = if (touchable) {
            controlParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            controlParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        if (nextFlags != controlParams.flags) {
            controlParams.flags = nextFlags
            try {
                windowManager.updateViewLayout(controlRoot, controlParams)
            } catch (_: Exception) {
            }
        }
    }

    private fun removeOverlayViews() {
        listOf(pathOverlayView, controlRoot).forEach { v ->
            try {
                windowManager.removeView(v)
            } catch (_: Exception) {
            }
        }
    }

    private fun makeChipBtn(
        label: String,
        minW: Int = 24,
        padH: Int = 5,
        marginEndDp: Int = 2,
        onClick: () -> Unit
    ): TextView {
        return TextView(this).apply {
            text = label
            textSize = 10f
            setSingleLine(true)
            gravity = Gravity.CENTER
            minWidth = dp(minW)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(padH), dp(3), dp(padH), dp(3))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(24)
            ).apply {
                marginEnd = dp(marginEndDp)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun circleBg(color: Int, strokeColor: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dp(1) + 1, strokeColor)
    }

    private fun roundedBg(color: Int, radius: Float, strokeColor: Int?) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(color)
        if (strokeColor != null) setStroke(dp(1), strokeColor)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AutoDora-NEXT 悬浮助手服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "OverlayService"
        private const val CHANNEL_ID = "autodora_overlay_service"
        private const val NOTIFICATION_ID = 20262
        const val ACTION_START = "com.autodora.next.overlay.START"
        const val ACTION_STOP = "com.autodora.next.overlay.STOP"

        fun start(context: Context) {
            val intent = Intent(context, OverlayControllerService::class.java).apply { action = ACTION_START }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, OverlayControllerService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }
}
