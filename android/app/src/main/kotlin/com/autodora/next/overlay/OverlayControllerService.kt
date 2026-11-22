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
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
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
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.autodora.next.R
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
import com.autodora.next.ui.LocaleHelper
import com.autodora.next.ui.OverlayPalette
import com.autodora.next.ui.OverlayThemeResolver
import com.autodora.next.vision.BoardRecognizer
import com.autodora.next.vision.DirectBitmapVisionFrame
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
    @Inject lateinit var touchExecutor: TouchExecutor

    private lateinit var windowManager: WindowManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var screenW = 1080
    private var screenH = 2400
    private var settings = AppSettings()
    private var lctx: Context = this
    private var presetRecords: List<TeamPresetRecord> = emptyList()
    private var activeWaveChips: List<WaveShieldChip> = emptyList()
    private lateinit var palette: OverlayPalette

    // Overlay views
    private lateinit var pathOverlayView: PathOverlayView
    private lateinit var pathParams: WindowManager.LayoutParams
    private lateinit var controlRoot: LinearLayout
    private lateinit var controlParams: WindowManager.LayoutParams
    private lateinit var statusOrbView: FrameLayout
    private lateinit var statusOrbBadge: TextView
    private lateinit var toolbarContainer: LinearLayout

    // Compact All-Icon Main Bar Controls
    private lateinit var solveIconBtn: ImageView
    private lateinit var executeIconBtn: ImageView
    private lateinit var stopIconBtn: ImageView
    private lateinit var pathIconBtn: ImageView
    private lateinit var resultIconBtn: ImageView
    private lateinit var calibIconBtn: ImageView
    private lateinit var tuneIconBtn: ImageView
    private lateinit var closeIconBtn: ImageView

    // Active wave shields strip
    private lateinit var activeShieldScroll: HorizontalScrollView
    private lateinit var activeShieldRow: LinearLayout

    // Compact Solution Result Card (with inline ‹ 1/5 › alternative switcher)
    private lateinit var summaryStrip: LinearLayout
    private lateinit var summaryTitle: TextView
    private lateinit var solutionSwitcherBox: LinearLayout
    private lateinit var prevBtn: TextView
    private lateinit var pagerText: TextView
    private lateinit var nextBtn: TextView
    private lateinit var shieldStatusScroll: HorizontalScrollView
    private lateinit var shieldStatusRow: LinearLayout
    private lateinit var groupsScroll: HorizontalScrollView
    private lateinit var groupsRow: LinearLayout

    // Calibration bar
    private lateinit var calibrationBar: LinearLayout
    private val calibNudgeBtns = ArrayList<TextView>()
    private lateinit var calibAutoBtn: TextView
    private lateinit var calibResetBtn: TextView

    // In-game 4-Tab Drawer & Board Editor
    private lateinit var tuningDrawer: OverlayTuningDrawerController
    private lateinit var boardEditor: OverlayBoardEditorController

    // Interactive state
    private var currentPhase = OverlayPhase.IDLE
    private var toolbarExpanded = true
    private var tuningDrawerOpen = false
    private var boardEditorMode = false
    private var showResultCard = true
    private var summaryPillsExpanded = true
    private var showPath = true
    private var calibrationMode = false
    private var enemyPopupCoveringBoard = false
    private var lastPresetConfigHash: Int = 0
    private var currentGeometry: BoardGeometry? = null
    private var lastRecognition: RecognitionResult? = null
    private var editedBoard: BoardState? = null
    private var savedEditedBoard: BoardState? = null
    private var lastSolveOutput: SolveOutput? = null
    private var lastPuzzleMaskMissing = false
    private var lastTrafficLightMissing = false
    private var usedMemorizedSnapshot = false
    private var selectedSolutionIdx = 0
    private var activeJob: Job? = null
    private var backgroundWatchJob: Job? = null
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
                val presetSwitched = settings.activePresetId != latest.activePresetId
                val previewModeChanged = settings.testModeOnlyDraw != latest.testModeOnlyDraw
                val solverSettingChanged = settings.allowDiagonalMove != latest.allowDiagonalMove ||
                    settings.maxSteps != latest.maxSteps ||
                    settings.beamWidth != latest.beamWidth
                settings = latest
                if (presetSwitched || previewModeChanged || solverSettingChanged) {
                    invalidateLastSolution()
                }
                stopIconBtn.visibility = if (latest.showEmergencyStop) View.VISIBLE else View.GONE
                applyOverlayTheme()
                ensureAlwaysOnBackgroundWatcher()
                if (calibrationMode) {
                    currentGeometry = latest.calibration.toBase6x5Geometry(screenW, screenH)
                    refreshPathCanvas()
                }
            }
        }
        serviceScope.launch {
            teamPresetRepository.observePresets().collectLatest { list ->
                presetRecords = list
                val cfgHash = activeTeamPreset().baseConfig.hashCode()
                if (lastPresetConfigHash != 0 && cfgHash != lastPresetConfigHash) {
                    invalidateLastSolution()
                }
                lastPresetConfigHash = cfgHash
                syncDrawerData()
            }
        }
        serviceScope.launch {
            ActiveStrategyStore.activeChips.collectLatest { chips ->
                val changed = chips != activeWaveChips
                activeWaveChips = chips
                if (changed && lastSolveOutput != null) {
                    invalidateLastSolution()
                }
                refreshActiveShieldStrip()
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
        backgroundWatchJob?.cancel()
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
            .setSmallIcon(R.drawable.ic_stat_autodora)
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

    private fun applyPuzzleShapeTemplateIfNeeded(board: BoardState): BoardState {
        val pChip = activeWaveChips.firstOrNull { it.id == "puzzle_shield" } ?: return board
        val shapeId = (pChip.paramInt ushr 8) and 0x0F
        if (shapeId <= 0) return board
        if (board.tileFlags.any { (it and TileFlagBits.PUZZLE_1) != 0 }) return board
        val templateCells = WaveShieldCatalog.puzzleShapeTemplateCells(shapeId, board.cols, board.rows)
        if (templateCells.isEmpty()) return board
        val updatedFlags = board.tileFlags.toMutableList()
        for (idx in templateCells) {
            if (idx in updatedFlags.indices) {
                updatedFlags[idx] = updatedFlags[idx] or TileFlagBits.PUZZLE_1
            }
        }
        return board.copy(tileFlags = updatedFlags)
    }

    private fun composeEffectiveRuleConfig(
        board: BoardState,
        fixedStartCell: Int = -1,
        fastMode: Boolean = false,
        fastMaxStepsCap: Int = 30
    ): RuleConfig {
        val preset = activeTeamPreset()
        val global = settings.toRuleConfig()
        // Strict 3-tier precedence: System Defaults < Strategy Overrides < Wave Shield Overrides
        val effectiveSteps = if (preset.baseConfig.useSystemSteps) {
            settings.maxSteps
        } else {
            preset.baseConfig.maxSteps
        }.coerceIn(8, 300)
        val effectiveBeam = if (preset.baseConfig.useSystemBeam) {
            settings.beamWidth
        } else {
            preset.baseConfig.beamWidth
        }.coerceIn(40, 600)
        val mergedBase = preset.baseConfig.copy(
            allowDiagonalMove = settings.allowDiagonalMove,
            maxSteps = effectiveSteps,
            beamWidth = effectiveBeam,
            maxTimeMs = maxOf(global.maxTimeMs, preset.baseConfig.maxTimeMs),
            topK = 5,
            fixedStartCell = fixedStartCell
        )
        val canonicalChips = activeWaveChips.map { WaveShieldCatalog.canonicalizeForComposer(it) }
        val composed = RuleComposer.compose(
            base = preset.copy(baseConfig = mergedBase),
            activeChips = canonicalChips,
            board = board
        )
        if (!fastMode) return composed

        // Hold-and-Replan real-time hard budget: MUST apply AFTER RuleComposer.compose so highDifficultyShield
        // never inflates maxTimeMs back to 800ms or beamWidth to 260+ while the game's turn timer is ticking!
        val hasHardShield = activeWaveChips.isNotEmpty() ||
            composed.puzzle1.enabled || composed.puzzle2.enabled ||
            composed.crossShield.requiredCount > 0 ||
            composed.exactTotalCombo >= 0 || composed.exactFirstWaveCombo >= 0
        val cappedSteps = if (composed.exactSteps > 0) {
            composed.maxSteps
        } else {
            minOf(composed.maxSteps, fastMaxStepsCap.coerceIn(16, 36))
        }
        return composed.copy(
            maxSteps = cappedSteps,
            beamWidth = minOf(composed.beamWidth, if (hasHardShield) 200 else 180),
            maxTimeMs = if (hasHardShield) 180 else 135,
            topK = 1,
            fixedStartCell = fixedStartCell
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
                boardEditor.markDirty()
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

        val orbSize = dp(32)
        val orbRingPad = dp(2)
        val circleOutline = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        statusOrbView = FrameLayout(this).apply {
            elevation = dp(3).toFloat()
            setPadding(orbRingPad, orbRingPad, orbRingPad, orbRingPad)
            layoutParams = LinearLayout.LayoutParams(orbSize, orbSize).apply {
                marginEnd = dp(4)
            }
        }
        val appIconView = ImageView(this).apply {
            setImageResource(R.drawable.ic_overlay_app_orb)
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = circleOutline
            clipToOutline = true
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        statusOrbView.addView(appIconView)

        statusOrbBadge = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            visibility = View.GONE
            outlineProvider = circleOutline
            clipToOutline = true
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        statusOrbView.addView(statusOrbBadge)
        topRow.addView(statusOrbView)

        toolbarContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            elevation = dp(3).toFloat()
            setPadding(dp(4), dp(3), dp(4), dp(3))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Permanent High-Frequency Vector Icon Bar (compact & touch-friendly, zero cluttered text pills!)
        solveIconBtn = makeIconBtn(R.drawable.ic_ov_solve) { startCaptureAndSolve(autoExecuteAfterSolve = false) }
        toolbarContainer.addView(solveIconBtn)

        executeIconBtn = makeIconBtn(R.drawable.ic_ov_play) { handleUnifiedExecute() }
        toolbarContainer.addView(executeIconBtn)

        stopIconBtn = makeIconBtn(R.drawable.ic_ov_stop) {
            triggerEmergencyStop()
            toast(lctx.getString(R.string.ov_toast_stopped))
        }
        toolbarContainer.addView(stopIconBtn)

        // Trajectory path overlay toggle ('线')
        pathIconBtn = makeIconBtn(R.drawable.ic_ov_path) {
            showPath = !showPath
            refreshMainToggleChips()
            refreshPathCanvas()
        }
        toolbarContainer.addView(pathIconBtn)

        // Solution result card visibility toggle ('结')
        resultIconBtn = makeIconBtn(R.drawable.ic_ov_result) {
            showResultCard = !showResultCard
            refreshMainToggleChips()
            updateSummaryStrip()
        }
        toolbarContainer.addView(resultIconBtn)

        // Board alignment calibration grid toggle ('校')
        calibIconBtn = makeIconBtn(R.drawable.ic_ov_grid) { toggleCalibrationMode() }
        toolbarContainer.addView(calibIconBtn)

        // 4-Tab Full-Feature Floating Drawer toggle (快捷 | 策略 | 解盾 | 设置)
        tuneIconBtn = makeIconBtn(R.drawable.ic_ov_tune) { toggleTuningDrawer() }
        toolbarContainer.addView(tuneIconBtn)

        closeIconBtn = makeIconBtn(R.drawable.ic_ov_close, marginEndDp = 0) { stopSelf() }
        toolbarContainer.addView(closeIconBtn)

        topRow.addView(toolbarContainer)
        controlRoot.addView(topRow)

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

        // Compact Solution Result Card (controlled by '结' icon; inline ‹ 1/5 › alternative switcher!)
        summaryStrip = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            elevation = dp(2).toFloat()
            visibility = View.GONE
            setPadding(dp(7), dp(4), dp(7), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(3)
                marginStart = dp(4)
            }
        }

        val summaryHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        summaryTitle = TextView(this).apply {
            textSize = 10f
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            typeface = Typeface.DEFAULT_BOLD
            maxWidth = (screenW * 0.72f).toInt()
            setOnClickListener {
                summaryPillsExpanded = !summaryPillsExpanded
                shieldStatusScroll.visibility = if (summaryPillsExpanded && shieldStatusRow.childCount > 0) View.VISIBLE else View.GONE
                groupsScroll.visibility = if (summaryPillsExpanded && groupsRow.childCount > 0) View.VISIBLE else View.GONE
            }
        }
        summaryHeaderRow.addView(summaryTitle)

        // Compact Inline Solution Switcher (‹ 1/5 ›) nested neatly inside the result card
        solutionSwitcherBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(1), dp(2), dp(1))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(6) }
        }
        prevBtn = makeChipBtn("‹", minW = 18, padH = 4, marginEndDp = 1) { switchSolution(-1) }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(1) }
            setPadding(0, 0, 0, 0)
            textSize = 12f
        }
        pagerText = TextView(this).apply {
            text = "1/1"
            textSize = 9.5f
            setSingleLine(true)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(3), 0, dp(3), 0)
        }
        nextBtn = makeChipBtn("›", minW = 18, padH = 4, marginEndDp = 0) { switchSolution(+1) }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(1) }
            setPadding(0, 0, 0, 0)
            textSize = 12f
        }
        solutionSwitcherBox.addView(prevBtn)
        solutionSwitcherBox.addView(pagerText)
        solutionSwitcherBox.addView(nextBtn)
        summaryHeaderRow.addView(solutionSwitcherBox)
        summaryStrip.addView(summaryHeaderRow)

        shieldStatusScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(3) }
        }
        shieldStatusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        shieldStatusScroll.addView(shieldStatusRow)
        summaryStrip.addView(shieldStatusScroll)

        groupsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(3) }
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
                marginStart = dp(4)
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

        // Interactive Board Editor Brush Bar (Grouped & Wrapped; Save Board -> Solve via Main Floating Bar)
        boardEditor = OverlayBoardEditorController(
            context = this,
            onSaveEditedBoard = {
                val cur = editedBoard ?: return@OverlayBoardEditorController
                savedEditedBoard = cur
                boardEditor.setSavedState(true)
                setBoardEditorMode(false)
                invalidateLastSolution()
                showPath = true
                refreshMainToggleChips()
                refreshActiveShieldStrip()
                syncDrawerData()
                refreshPathCanvas()
                toast(lctx.getString(R.string.ov_toast_board_saved))
            },
            onClearAllMarkers = {
                val cur = editedBoard
                if (cur != null) {
                    editedBoard = cur.copy(
                        cells = cur.cells.map { it.copy(variant = Variant.NORMAL, hazards = HazardBits.NONE) },
                        tileFlags = List(cur.cols * cur.rows) { TileFlagBits.NONE }
                    )
                }
                savedEditedBoard = null
                boardEditor.setSavedState(false)
                invalidateLastSolution()
                refreshActiveShieldStrip()
                syncDrawerData()
                refreshPathCanvas()
                toast(lctx.getString(R.string.ov_toast_board_cleared))
            },
            onCloseEditor = { setBoardEditorMode(false) }
        )
        controlRoot.addView(boardEditor.rootView)

        // Full-Feature 3-Tab Unified Floating Drawer (快捷 | 策略 | 设置)
        tuningDrawer = OverlayTuningDrawerController(
            context = this,
            onToggleBoardEditor = { toggleBoardEditorMode() },
            onToggleWaveChip = { chip -> ActiveStrategyStore.toggleChip(chip) },
            onUpsertWaveChip = { chip -> ActiveStrategyStore.upsertChip(chip) },
            onClearWaveChips = {
                ActiveStrategyStore.clearAll()
                toast(lctx.getString(R.string.btn_clear))
            },
            onSelectPreset = { id ->
                serviceScope.launch {
                    settingsRepository.update { it.copy(activePresetId = id) }
                    val rec = presetRecords.firstOrNull { it.preset.id == id }
                    if (rec != null) toast(LocaleHelper.displayPresetName(lctx, rec.preset, rec.isBuiltIn))
                }
            },
            onSaveActivePreset = {
                serviceScope.launch {
                    val cur = activeTeamPreset()
                    val rec = presetRecords.firstOrNull { it.preset.id == cur.id }
                    teamPresetRepository.savePreset(cur, isBuiltIn = rec?.isBuiltIn ?: false)
                    toast(lctx.getString(R.string.toast_preset_saved))
                }
            },
            onDuplicateActivePreset = {
                serviceScope.launch {
                    val cur = activeTeamPreset()
                    val rec = presetRecords.firstOrNull { it.preset.id == cur.id }
                    val displayName = LocaleHelper.displayPresetName(lctx, cur, rec?.isBuiltIn ?: false)
                    val displayDesc = LocaleHelper.displayPresetDesc(lctx, cur, rec?.isBuiltIn ?: false)
                    val newId = teamPresetRepository.duplicatePreset(cur.copy(name = displayName, description = displayDesc))
                    settingsRepository.update { it.copy(activePresetId = newId) }
                    toast(lctx.getString(R.string.toast_preset_saved_as))
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
                        tuningDrawer.setVisible(toolbarExpanded && tuningDrawerOpen)
                        boardEditor.rootView.visibility = if (toolbarExpanded && boardEditorMode) View.VISIBLE else View.GONE
                        if (!toolbarExpanded) {
                            calibrationBar.visibility = View.GONE
                            summaryStrip.visibility = View.GONE
                            activeShieldScroll.visibility = View.GONE
                        } else {
                            if (calibrationMode) calibrationBar.visibility = View.VISIBLE
                            updateSummaryStrip()
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
            return
        }
        if (!settings.testModeOnlyDraw) {
            // Preview-Confirm OFF: 1-tap Play directly captures, solves, and auto-swipes!
            startCaptureAndSolve(autoExecuteAfterSolve = true)
            return
        }
        // Preview-Confirm ON: strictly require a freshly calculated solution first!
        if (currentSolution() == null) {
            toast(lctx.getString(R.string.ov_toast_need_solve))
            return
        }
        executeSelectedSolution()
    }

    // ==================== Capture & Solve ('算') ====================
    private fun startCaptureAndSolve(autoExecuteAfterSolve: Boolean = false) {
        if (currentPhase != OverlayPhase.IDLE) {
            toast(lctx.getString(R.string.ov_toast_stopped))
            return
        }

        if (calibrationMode) setCalibrationMode(false)
        if (boardEditorMode) setBoardEditorMode(false)
        if (tuningDrawerOpen) setTuningDrawerOpen(false)

        // When the user saved a manually edited board via 「保存版面」, route the main floating toolbar's
        // Calculate / Play buttons directly to solve (and optionally execute) that saved board!
        val customBoard = savedEditedBoard
        if (customBoard != null) {
            solveSavedEditedBoard(customBoard, autoExecuteAfterSolve)
            return
        }

        val capturer = ScreenCaptureSession.capturer.value
        if (capturer == null || !capturer.isReady.value) {
            toast(lctx.getString(R.string.toast_capture_denied))
            return
        }

        activeJob = serviceScope.launch {
            setPhase(OverlayPhase.CALCULATING)
            // Completely hide PathOverlayView & flush any stale MediaProjection cached frame BEFORE capture
            // so previously rendered yellow puzzle outlines or path arrows NEVER pollute Frame 0 seam scoring!
            val hadVisibleOverlay = pathOverlayView.visibility == View.VISIBLE && pathOverlayView.hasVisibleDrawing()
            pathOverlayView.updateState(null, null, emptyList(), null, false, false, false)
            pathOverlayView.visibility = View.INVISIBLE
            if (hadVisibleOverlay) {
                capturer.clearCachedFrame()
                delay(95)
            } else {
                delay(40)
            }

            val trafficLightChipActive = activeWaveChips.any { it.id == "traffic_light" }
            val puzzleChipActive = activeWaveChips.any { it.id in WaveShieldCatalog.PUZZLE_EXCLUSIVE_IDS }
            val burstCount = if (trafficLightChipActive || puzzleChipActive) maxOf(settings.multiFrameCount, 4) else settings.multiFrameCount
            val burstInterval = if (trafficLightChipActive) 85L else 50L
            val frames = capturer.captureMultiFrames(count = burstCount, intervalMs = burstInterval).toMutableList()
            if (frames.isEmpty()) {
                pathOverlayView.visibility = View.VISIBLE
                setPhase(OverlayPhase.IDLE)
                refreshPathCanvas()
                toast(lctx.getString(R.string.toast_capture_denied))
                return@launch
            }

            try {
                val (geom, isBattleBoard) = withContext(Dispatchers.Default) {
                    val firstBmp = frames.first()
                    val g = AutoGridDetector.detect(firstBmp, settings.calibration)
                    val inBattle = !FlashRevealMemoryTracker.isDarkModalCoveringBoard(DirectBitmapVisionFrame(firstBmp)) &&
                        AutoGridDetector.isBattleBoardPresent(firstBmp, g)
                    g to inBattle
                }
                if (!isBattleBoard) {
                    flashMemoryTracker.clearLock()
                    usedMemorizedSnapshot = false
                    lastRecognition = null
                    invalidateLastSolution()
                    setPhase(OverlayPhase.IDLE)
                    toast(lctx.getString(R.string.ov_toast_no_board))
                    return@launch
                }

                var liveRec = boardRecognizer.recognize(frames, geom)

                // Pulsing Red-Burst / Corner GO Rescue:
                // The ToS red 8-ray starburst pulses on a ~0.6s cycle. If only Start (or neither/partial while
                // traffic_light chip is active) was caught in the initial burst, grab 2 extra frames +105ms apart
                // and fuse across the wider ~450ms window to guarantee catching a bright pulse peak!
                val needTlBonusFrames = (liveRec.trafficLightStartCount > 0 && liveRec.trafficLightEndCount == 0) ||
                    (trafficLightChipActive && (liveRec.trafficLightStartCount == 0 || liveRec.trafficLightEndCount == 0))
                if (needTlBonusFrames) {
                    for (bonus in 0 until 2) {
                        delay(105L)
                        capturer.captureSingleFrame()?.let { frames.add(it) }
                    }
                    liveRec = boardRecognizer.recognize(frames, geom)
                }

                // Always-On Full-Screen Question Orb Memory + Legacy AutoDora 'FullscreenQuestion' Auto-Fallback!
                var resolvedFromMemory = false
                val boardToSolve = when (val res = flashMemoryTracker.resolveForSolve(liveRec)) {
                    is FlashRevealMemoryTracker.MemoryResolution.UseMemorizedSnapshot -> {
                        resolvedFromMemory = true
                        res.rememberedBoard
                    }
                    is FlashRevealMemoryTracker.MemoryResolution.NeedHoldAndNudgeReveal -> {
                        setPhase(OverlayPhase.IDLE)
                        startHoldAndReplanExecution(forceNudge = true)
                        return@launch
                    }
                    is FlashRevealMemoryTracker.MemoryResolution.UseLiveBoard -> liveRec.board
                }

                val totalCells = (boardToSolve.cols * boardToSolve.rows).coerceAtLeast(1)
                val unknownCells = boardToSolve.cells.indices.count { i ->
                    val el = boardToSolve.cells[i].element
                    val isStorm = (boardToSolve.tileFlags[i] and TileFlagBits.STORM) != 0
                    !isStorm && (el == Element.UNKNOWN || el == Element.EMPTY)
                }
                val isGenuineQuestionBoard = resolvedFromMemory || liveRec.questionOrbsDetected >= 6
                val notInBattleBoard = !isGenuineQuestionBoard && (
                    unknownCells >= (totalCells * 0.40f).toInt() ||
                        liveRec.lowConfidenceIndices.size >= (totalCells * 0.65f).toInt() ||
                        liveRec.averageConfidence < 0.38f
                    )
                if (notInBattleBoard) {
                    invalidateLastSolution()
                    setPhase(OverlayPhase.IDLE)
                    toast(lctx.getString(R.string.ov_toast_no_board))
                    return@launch
                }

                val effectiveBoard = applyPuzzleShapeTemplateIfNeeded(boardToSolve)
                currentGeometry = geom
                lastRecognition = liveRec.copy(board = effectiveBoard)
                editedBoard = effectiveBoard
                usedMemorizedSnapshot = resolvedFromMemory

                val ruleConfig = composeEffectiveRuleConfig(effectiveBoard)
                val puzzleMaskMissing = ruleConfig.puzzle1.enabled &&
                    effectiveBoard.tileFlags.none { (it and TileFlagBits.PUZZLE_1) != 0 }
                lastPuzzleMaskMissing = puzzleMaskMissing
                if (puzzleMaskMissing) {
                    toast(lctx.getString(R.string.ov_toast_puzzle_not_detected))
                }
                val hasTlStart = effectiveBoard.tileFlags.any { (it and TileFlagBits.START_ALLOWED) != 0 }
                val hasTlEnd = effectiveBoard.tileFlags.any { (it and TileFlagBits.END_REQUIRED) != 0 }
                val trafficLightMissing = activeWaveChips.any { it.id == "traffic_light" } && (!hasTlStart || !hasTlEnd)
                lastTrafficLightMissing = trafficLightMissing
                if (trafficLightMissing && !puzzleMaskMissing) {
                    if (!hasTlStart && !hasTlEnd) {
                        toast(lctx.getString(R.string.ov_toast_traffic_light_not_detected))
                    } else {
                        val partLabel = lctx.getString(if (hasTlStart) R.string.ov_tl_badge_start_only else R.string.ov_tl_badge_end_only)
                        toast(lctx.getString(R.string.ov_toast_traffic_light_partial, partLabel))
                    }
                }
                val token = NativeSolver.createCancelToken()
                activeCancelToken = token
                val solveOut = try {
                    NativeSolver.solve(effectiveBoard, ruleConfig, token)
                } finally {
                    activeCancelToken = null
                    token.close()
                }

                val bestSol = solveOut.solutions.firstOrNull()
                if (bestSol == null || (bestSol.totalCombo == 0 && bestSol.stepCount == 0)) {
                    invalidateLastSolution()
                    setPhase(OverlayPhase.IDLE)
                    return@launch
                }

                lastSolveOutput = solveOut
                selectedSolutionIdx = 0
                showPath = true
                pathOverlayView.visibility = View.VISIBLE
                refreshMainToggleChips()
                updateSummaryStrip()
                refreshPathCanvas()
                setPhase(OverlayPhase.IDLE)

                if (autoExecuteAfterSolve || !settings.testModeOnlyDraw) {
                    executeSelectedSolution()
                }
            } catch (e: CancellationException) {
                setPhase(OverlayPhase.IDLE)
            } catch (e: Exception) {
                Log.e(TAG, "Capture/Solve failed", e)
                setPhase(OverlayPhase.IDLE)
            } finally {
                pathOverlayView.visibility = View.VISIBLE
                frames.forEach { if (!it.isRecycled) it.recycle() }
            }
        }
    }

    // ==================== Solve Saved Edited Board via Main Toolbar ('算' / '行') ====================
    private fun solveSavedEditedBoard(board: BoardState, autoExecuteAfterSolve: Boolean) {
        val geom = currentGeometry ?: settings.calibration.toBase6x5Geometry(screenW, screenH)
        activeJob = serviceScope.launch {
            setPhase(OverlayPhase.CALCULATING)
            var solvedReady = false
            try {
                val effectiveBoard = applyPuzzleShapeTemplateIfNeeded(board)
                savedEditedBoard = effectiveBoard
                editedBoard = effectiveBoard
                val ruleConfig = composeEffectiveRuleConfig(effectiveBoard)
                val puzzleMaskMissing = ruleConfig.puzzle1.enabled &&
                    effectiveBoard.tileFlags.none { (it and TileFlagBits.PUZZLE_1) != 0 }
                lastPuzzleMaskMissing = puzzleMaskMissing
                if (puzzleMaskMissing) {
                    toast(lctx.getString(R.string.ov_toast_puzzle_not_detected))
                }
                val hasTlStart = effectiveBoard.tileFlags.any { (it and TileFlagBits.START_ALLOWED) != 0 }
                val hasTlEnd = effectiveBoard.tileFlags.any { (it and TileFlagBits.END_REQUIRED) != 0 }
                val trafficLightMissing = activeWaveChips.any { it.id == "traffic_light" } && (!hasTlStart || !hasTlEnd)
                lastTrafficLightMissing = trafficLightMissing
                if (trafficLightMissing && !puzzleMaskMissing) {
                    if (!hasTlStart && !hasTlEnd) {
                        toast(lctx.getString(R.string.ov_toast_traffic_light_not_detected))
                    } else {
                        val partLabel = lctx.getString(if (hasTlStart) R.string.ov_tl_badge_start_only else R.string.ov_tl_badge_end_only)
                        toast(lctx.getString(R.string.ov_toast_traffic_light_partial, partLabel))
                    }
                }
                val token = NativeSolver.createCancelToken()
                activeCancelToken = token
                val solveOut = try {
                    NativeSolver.solve(effectiveBoard, ruleConfig, token)
                } finally {
                    activeCancelToken = null
                    token.close()
                }
                val bestSol = solveOut.solutions.firstOrNull()
                if (bestSol == null || (bestSol.totalCombo == 0 && bestSol.stepCount == 0)) {
                    invalidateLastSolution()
                    showPath = true
                    refreshPathCanvas()
                    toast(lctx.getString(R.string.ov_toast_edited_no_solution))
                } else {
                    currentGeometry = geom
                    lastSolveOutput = solveOut
                    selectedSolutionIdx = 0
                    showPath = true
                    pathOverlayView.visibility = View.VISIBLE
                    refreshMainToggleChips()
                    updateSummaryStrip()
                    refreshPathCanvas()
                    solvedReady = true
                }
            } catch (e: CancellationException) {
                // Handled by finally
            } catch (e: Exception) {
                Log.e(TAG, "Solve saved board failed", e)
            } finally {
                setPhase(OverlayPhase.IDLE)
            }
            if (solvedReady && (autoExecuteAfterSolve || !settings.testModeOnlyDraw)) {
                executeSelectedSolution()
            }
        }
    }

    // ==================== Ultra-Fast No-Root Hold-and-Replan Execution (按住重算 / 全屏问号珠) ====================
    private fun startHoldAndReplanExecution(forceNudge: Boolean = false) {
        if (currentPhase != OverlayPhase.IDLE) return
        val capturer = ScreenCaptureSession.capturer.value
        if (capturer == null || !capturer.isReady.value) {
            toast(lctx.getString(R.string.toast_capture_denied))
            return
        }
        if (!touchExecutor.isAvailable.value) {
            toast(lctx.getString(R.string.ov_toast_need_a11y))
            return
        }
        if (calibrationMode) setCalibrationMode(false)
        if (boardEditorMode) setBoardEditorMode(false)
        if (tuningDrawerOpen) setTuningDrawerOpen(false)

        activeJob = serviceScope.launch {
            setPhase(OverlayPhase.MOVING)
            // Completely hide PathOverlayView & clear memorized overlay BEFORE pre-touch capture
            // so translucent memorized orb badges NEVER pollute preBmp or reveal polling frames!
            flashMemoryTracker.clearLock()
            usedMemorizedSnapshot = false
            pathOverlayView.visibility = View.INVISIBLE
            pathOverlayView.updateState(null, null, emptyList(), null, false, false, false, null, null, false)
            capturer.clearCachedFrame()
            delay(85)

            // Pre-touch geometry & baseline board scan (costs 0ms on the game's turn timer!)
            val preBmp = capturer.captureSingleFrame()
            var preQuestionCount = 0
            var preElements: List<Element> = emptyList()
            var preInBattle = true
            val geom = if (preBmp != null) {
                try {
                    withContext(Dispatchers.Default) {
                        val g = AutoGridDetector.detect(preBmp, settings.calibration)
                        preInBattle = !FlashRevealMemoryTracker.isDarkModalCoveringBoard(DirectBitmapVisionFrame(preBmp)) &&
                            AutoGridDetector.isBattleBoardPresent(preBmp, g)
                        if (preInBattle) {
                            val preRec = boardRecognizer.recognize(listOf(preBmp), g)
                            preQuestionCount = preRec.questionOrbsDetected
                            preElements = preRec.board.cells.map { it.element }
                        }
                        g
                    }
                } finally {
                    if (!preBmp.isRecycled) preBmp.recycle()
                }
            } else {
                settings.calibration.toBase6x5Geometry(screenW, screenH)
            }
            if (!preInBattle) {
                pathOverlayView.visibility = View.VISIBLE
                setPhase(OverlayPhase.IDLE)
                invalidateLastSolution()
                toast(lctx.getString(R.string.ov_toast_no_board))
                return@launch
            }
            currentGeometry = geom

            // Empirically verified on real ToS full-board '?' stages: holding in place without crossing a cell
            // boundary does NOT flip '?' orbs open, so auto-enable 1-cell nudge whenever >= 6 '?' orbs are detected!
            val shouldNudge = forceNudge || settings.holdReplanNudge || preQuestionCount >= 6
            val startPreset = activeTeamPreset().baseConfig.startPreset
            val (startRow, startCol, nudgeCell) = when (startPreset) {
                StartPointPreset.BOTTOM_LEFT -> {
                    val r = (geom.rows - 1).coerceAtLeast(0)
                    val target = if (shouldNudge && r > 0) CellCoord(r - 1, 0) else null
                    Triple(r, 0, target)
                }
                StartPointPreset.BOTTOM_RIGHT -> {
                    val r = (geom.rows - 1).coerceAtLeast(0)
                    val c = (geom.cols - 1).coerceAtLeast(0)
                    val target = if (shouldNudge && r > 0) CellCoord(r - 1, c) else null
                    Triple(r, c, target)
                }
                StartPointPreset.ANY -> {
                    val r = (geom.rows / 2).coerceIn(0, geom.rows - 1)
                    val c = (geom.cols / 2).coerceIn(0, geom.cols - 1)
                    val target = if (shouldNudge && c > 0) CellCoord(r, c - 1) else null
                    Triple(r, c, target)
                }
            }
            val startCell = CellCoord(startRow, startCol)
            val trigger = if (nudgeCell != null) {
                HoldReplanTrigger.NUDGE_ONE_CELL_HOLD
            } else {
                HoldReplanTrigger.HOLD_IN_PLACE
            }

            // Turn-time-safe continuation speed & step cap:
            // Clamp Hold-and-Replan stepDurationMs <= 38ms (Fast 3s tier, where PathInterpolator packs orthogonal +
            // diagonal moves into just 2..3 continuous segments, saving ~1.2-1.5s of Android Binder IPC pauses!).
            val baseSwipe = settings.toSwipeConfig()
            val holdSwipeConfig = baseSwipe.copy(
                stepDurationMs = minOf(baseSwipe.stepDurationMs, 38L),
                endHoldMs = 28L,
                maxStepsPerSegment = 18
            )
            val maxSwipeStepsForBudget = (1800L / holdSwipeConfig.stepDurationMs.coerceAtLeast(24L))
                .toInt()
                .coerceIn(24, 30)
            val maxRevealWaitMs = settings.holdReplanDelayMs.coerceIn(240L, 900L)
            val bandTop = (geom.topPx - geom.cellHeight * 0.15f).toInt().coerceAtLeast(0)
            val bandBot = (geom.bottomPx + geom.cellHeight * 0.15f).toInt().coerceAtMost(screenH)
            val startIdx = startCell.row * geom.cols + startCell.col

            setControlBarTouchable(!overlapsBoard(geom))
            try {
                val result = touchExecutor.executeHoldAndReplan(
                    startCell = startCell,
                    geometry = geom,
                    trigger = trigger,
                    nudgeTarget = nudgeCell,
                    revealDelayMs = 0L, // Smart early-ready poller below replaces blind sleep!
                    config = holdSwipeConfig
                ) { heldCell ->
                    val tReplanStart = System.currentTimeMillis()
                    val heldIdx = heldCell.row * geom.cols + heldCell.col

                    // Phase 1: Ultra-fast board-band smart early-ready polling (exits the instant '?' flips open!)
                    delay(45L)
                    var bestRec: RecognitionResult? = null
                    var settledCleanPolls = 0
                    var sawQuestionOrbs = preQuestionCount >= 4

                    while (true) {
                        val band = capturer.captureBandBitmap(bandTop, bandBot)
                        if (band != null) {
                            val rec = try {
                                boardRecognizer.recognizeBand(
                                    bandBitmap = band.bitmap,
                                    fullWidth = band.fullWidth,
                                    fullHeight = band.fullHeight,
                                    yOffset = band.yOffset,
                                    geometry = geom
                                )
                            } finally {
                                if (!band.bitmap.isRecycled) band.bitmap.recycle()
                            }

                            if (rec.questionOrbsDetected >= 4) {
                                sawQuestionOrbs = true
                            }

                            val prevBest = bestRec
                            if (prevBest == null ||
                                rec.questionOrbsDetected < prevBest.questionOrbsDetected ||
                                (rec.questionOrbsDetected == prevBest.questionOrbsDetected &&
                                    rec.averageConfidence > prevBest.averageConfidence)
                            ) {
                                bestRec = rec
                            }

                            val elapsed = System.currentTimeMillis() - tReplanStart
                            Log.d(TAG, "Hold-Replan poll t=${elapsed}ms q=${rec.questionOrbsDetected} conf=${"%.2f".format(rec.averageConfidence)}")
                            if (sawQuestionOrbs) {
                                // Instant exit on crystal-clear full reveal; 1 extra 22ms settle poll if 1-2 cells dim under finger
                                if (rec.questionOrbsDetected == 0 && rec.averageConfidence >= 0.70f) {
                                    bestRec = rec
                                    break
                                }
                                if (rec.questionOrbsDetected <= 2 && rec.averageConfidence >= 0.62f) {
                                    settledCleanPolls++
                                    if (settledCleanPolls >= 2 || elapsed >= 220L) {
                                        bestRec = rec
                                        break
                                    }
                                } else {
                                    settledCleanPolls = 0
                                }
                            } else {
                                // Non-question hold-replan (e.g., weathered/smoke transform): exit as soon as non-swap cells change
                                var changedNonSwap = 0
                                if (preElements.size == rec.board.cells.size) {
                                    for (i in preElements.indices) {
                                        if (i != startIdx && i != heldIdx && rec.board.cells[i].element != preElements[i]) {
                                            changedNonSwap++
                                        }
                                    }
                                }
                                if (changedNonSwap >= 2 && rec.averageConfidence >= 0.65f) {
                                    bestRec = rec
                                    break
                                }
                            }
                        }

                        val effectiveCeilingMs = if (sawQuestionOrbs) maxOf(maxRevealWaitMs, 560L) else minOf(maxRevealWaitMs, 220L)
                        if (System.currentTimeMillis() - tReplanStart >= effectiveCeilingMs) break
                        delay(22L)
                    }

                    val finalRec = bestRec ?: return@executeHoldAndReplan null
                    val revealMs = System.currentTimeMillis() - tReplanStart
                    val effectiveBoard = applyPuzzleShapeTemplateIfNeeded(finalRec.board)
                    lastRecognition = finalRec.copy(board = effectiveBoard)
                    editedBoard = effectiveBoard
                    usedMemorizedSnapshot = false

                    // Phase 2: Tight real-time C++20 single-start solve (strictly <= 135..180ms!)
                    val tSolveStart = System.currentTimeMillis()
                    val composed = composeEffectiveRuleConfig(
                        board = effectiveBoard,
                        fixedStartCell = heldIdx,
                        fastMode = true,
                        fastMaxStepsCap = maxSwipeStepsForBudget
                    )
                    val token = NativeSolver.createCancelToken()
                    activeCancelToken = token
                    val solveOut = try {
                        NativeSolver.solve(effectiveBoard, composed, token)
                    } finally {
                        activeCancelToken = null
                        token.close()
                    }
                    val solveMs = System.currentTimeMillis() - tSolveStart
                    lastSolveOutput = solveOut
                    selectedSolutionIdx = 0

                    val bestSol = solveOut.solutions.firstOrNull()
                    Log.i(
                        TAG,
                        "Hold-Replan ready: reveal=${revealMs}ms (q:${preQuestionCount}->${finalRec.questionOrbsDetected}), " +
                            "solve=${solveMs}ms (native=${solveOut.elapsedMs.toInt()}ms, steps=${bestSol?.stepCount ?: 0}, " +
                            "combo=${bestSol?.totalCombo ?: 0}, stepDur=${holdSwipeConfig.stepDurationMs}ms)"
                    )

                    // Update path preview & result card asynchronously so continuation swipe begins with 0ms UI wait!
                    serviceScope.launch(Dispatchers.Main) {
                        pathOverlayView.visibility = View.VISIBLE
                        if (currentPhase == OverlayPhase.MOVING) {
                            showPath = true
                            refreshMainToggleChips()
                            updateSummaryStrip()
                            refreshPathCanvas()
                        }
                    }
                    bestSol
                }
                when (result) {
                    is TouchExecutionResult.Success -> {
                        flashMemoryTracker.clearLock()
                        usedMemorizedSnapshot = false
                        savedEditedBoard = null
                        boardEditor.setSavedState(false)
                        refreshActiveShieldStrip()
                        syncDrawerData()
                        invalidateLastSolution()
                        Log.i(TAG, "Hold-and-Replan completed in ${result.elapsedMs}ms (${result.segmentsDispatched} segs)")
                    }
                    is TouchExecutionResult.Cancelled -> {
                        hidePathOverlay()
                        toast(lctx.getString(R.string.ov_toast_stopped))
                    }
                    is TouchExecutionResult.Failed -> Log.w(TAG, "Hold-and-Replan failed: ${result.reason}")
                }
            } finally {
                pathOverlayView.visibility = View.VISIBLE
                setControlBarTouchable(true)
                setPhase(OverlayPhase.IDLE)
                refreshMainToggleChips()
                refreshPathCanvas()
            }
        }
    }

    // ==================== Standard Path Execution ====================
    private fun executeSelectedSolution() {
        if (currentPhase != OverlayPhase.IDLE) return
        val geom = currentGeometry ?: return
        val sol = currentSolution() ?: run {
            toast(lctx.getString(R.string.ov_toast_need_solve))
            return
        }
        if (!touchExecutor.isAvailable.value) {
            toast(lctx.getString(R.string.ov_toast_need_a11y))
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
                        savedEditedBoard = null
                        boardEditor.setSavedState(false)
                        refreshActiveShieldStrip()
                        syncDrawerData()
                        invalidateLastSolution()
                        Log.i(TAG, "Auto-swipe completed in ${result.elapsedMs}ms (${result.segmentsDispatched} segs)")
                    }
                    is TouchExecutionResult.Cancelled -> {
                        hidePathOverlay()
                        toast(lctx.getString(R.string.ov_toast_stopped))
                    }
                    is TouchExecutionResult.Failed -> Log.w(TAG, "Auto-swipe failed: ${result.reason}")
                }
            } finally {
                setControlBarTouchable(true)
                setPhase(OverlayPhase.IDLE)
                refreshMainToggleChips()
            }
        }
    }

    private fun invalidateLastSolution() {
        lastSolveOutput = null
        lastPuzzleMaskMissing = false
        lastTrafficLightMissing = false
        selectedSolutionIdx = 0
        showPath = false
        if (::summaryStrip.isInitialized) {
            updateSummaryStrip()
            refreshMainToggleChips()
            refreshPathCanvas(countdown = null)
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

    // ==================== Modes: Calibration / Board Editor / 4-Tab Tuning Drawer ====================
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
            val saved = savedEditedBoard
            if (saved != null && saved.cols == geom.cols && saved.rows == geom.rows) {
                editedBoard = saved
                boardEditor.setSavedState(true)
            } else {
                if (editedBoard == null || editedBoard!!.cols != geom.cols || editedBoard!!.rows != geom.rows) {
                    editedBoard = lastRecognition?.board
                        ?: flashMemoryTracker.currentLockedSnapshot()
                        ?: BoardState(
                            cols = geom.cols,
                            rows = geom.rows,
                            cells = List(geom.cols * geom.rows) { i -> CellState(Element.MATCHABLE[i % 6]) }
                        )
                }
                boardEditor.setSavedState(false)
            }
        }
        updatePathOverlayTouchability()
        refreshMainToggleChips()
        syncDrawerData()
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
        refreshMainToggleChips()
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
            pathOverlayView.visibility = View.INVISIBLE
            capturer.clearCachedFrame()
            delay(80)
            val bmp = capturer.captureSingleFrame() ?: run {
                pathOverlayView.visibility = View.VISIBLE
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
                    pathOverlayView.visibility = View.VISIBLE
                    refreshPathCanvas()
                    toast("血条锚定成功：${detected.cols}×${detected.rows} 棋盘")
                } else {
                    pathOverlayView.visibility = View.VISIBLE
                    refreshPathCanvas()
                    toast("未识别到战斗盘面，请进入战斗后重试或直接拖拽四角校准")
                }
            } finally {
                pathOverlayView.visibility = View.VISIBLE
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

    // ==================== Always-On 6Hz Background Watcher (Full-Board Question Orb Memory) ====================
    private fun ensureAlwaysOnBackgroundWatcher() {
        if (backgroundWatchJob?.isActive == true) return
        backgroundWatchJob = serviceScope.launch {
            var consecutiveNonBattleTicks = 0
            var consecutiveMassQuestionTicks = 0
            while (isActive) {
                delay(165L)
                if (currentPhase != OverlayPhase.IDLE || calibrationMode || boardEditorMode || tuningDrawerOpen) continue
                val capturer = ScreenCaptureSession.capturer.value ?: continue
                if (!capturer.isReady.value) continue

                val bmp = capturer.captureSingleFrame() ?: continue
                try {
                    val (geom, rec, watchStatus) = withContext(Dispatchers.Default) {
                        val frame = DirectBitmapVisionFrame(bmp)
                        if (FlashRevealMemoryTracker.isDarkModalCoveringBoard(frame)) {
                            Triple(null, null, 1) // 1 = dark modal covering battle board
                        } else {
                            val g = AutoGridDetector.detect(bmp, settings.calibration)
                            if (!AutoGridDetector.isBattleBoardPresent(bmp, g)) {
                                Triple(null, null, 2) // 2 = non-battle screen (Home Map, menus, etc.)
                            } else {
                                val r = boardRecognizer.recognize(listOf(bmp), g)
                                if (AutoGridDetector.isDimmedTransitionBoard(bmp, g, r.questionOrbsDetected)) {
                                    Triple(null, null, 3) // 3 = dimmed VICTORY / wave-walk transition veil
                                } else {
                                    Triple(g, r, 0) // 0 = genuine active battle board
                                }
                            }
                        }
                    }

                    when (watchStatus) {
                        1 -> {
                            consecutiveNonBattleTicks = 0
                            consecutiveMassQuestionTicks = 0
                            if (!enemyPopupCoveringBoard) {
                                enemyPopupCoveringBoard = true
                                refreshPathCanvas()
                            }
                        }
                        2, 3 -> {
                            consecutiveMassQuestionTicks = 0
                            consecutiveNonBattleTicks++
                            // On a dimmed VICTORY / wave transition frame, immediately hide any memorized '?' overlay
                            // on tick 1 so the translucent orb badges never linger over VICTORY or stage transitions!
                            if (watchStatus == 3 && flashMemoryTracker.currentLockedSnapshot() != null) {
                                flashMemoryTracker.clearLock()
                                usedMemorizedSnapshot = false
                                refreshPathCanvas()
                            }
                            // After 2 consecutive non-battle/transition ticks (~330ms), wipe memory & sweep overlays clean!
                            // Preserve an explicitly saved edited board outside battle unless a battle VICTORY/wave transition occurs.
                            if (consecutiveNonBattleTicks >= 2) {
                                if (watchStatus == 3 && savedEditedBoard != null) {
                                    savedEditedBoard = null
                                    boardEditor.setSavedState(false)
                                    refreshActiveShieldStrip()
                                    syncDrawerData()
                                }
                                val hadOverlay = flashMemoryTracker.currentLockedSnapshot() != null ||
                                    usedMemorizedSnapshot ||
                                    (savedEditedBoard == null && (lastSolveOutput != null || showPath || lastRecognition != null))
                                flashMemoryTracker.resetAll()
                                usedMemorizedSnapshot = false
                                enemyPopupCoveringBoard = false
                                if (hadOverlay) {
                                    if (savedEditedBoard == null) {
                                        lastRecognition = null
                                        invalidateLastSolution()
                                    } else {
                                        refreshPathCanvas()
                                    }
                                }
                            }
                        }
                        else -> {
                            consecutiveNonBattleTicks = 0
                            if (geom != null && rec != null) {
                                if (enemyPopupCoveringBoard) {
                                    enemyPopupCoveringBoard = false
                                    refreshPathCanvas()
                                }

                                // Always-On Full-Screen Question Orb Memory:
                                // Require >= 2 consecutive sustained mass-'?' ticks (~330ms) before popping the
                                // translucent orb overlay, eliminating 1-frame wave-change / attack-flash false triggers!
                                val totalCells = rec.board.cols * rec.board.rows
                                val hiddenCount = FlashRevealMemoryTracker.countHiddenOrQuestion(rec.board)
                                val massThreshold = maxOf(18, (totalCells * 0.60f).toInt())
                                if (hiddenCount >= massThreshold) {
                                    consecutiveMassQuestionTicks++
                                    if (consecutiveMassQuestionTicks >= 2) {
                                        val lockedNow = flashMemoryTracker.onWatchFrame(rec)
                                        if (lockedNow) {
                                            currentGeometry = geom
                                            refreshPathCanvas()
                                        }
                                    }
                                } else {
                                    consecutiveMassQuestionTicks = 0
                                    val hadLocked = flashMemoryTracker.currentLockedSnapshot() != null
                                    flashMemoryTracker.onWatchFrame(rec)
                                    if (hadLocked && flashMemoryTracker.currentLockedSnapshot() == null) {
                                        refreshPathCanvas()
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    if (!bmp.isRecycled) bmp.recycle()
                }
            }
        }
    }

    // ==================== System Material 3 Theme & Unified Icon Styling ====================
    private fun applyOverlayTheme() {
        if (!::toolbarContainer.isInitialized) return
        lctx = LocaleHelper.localizedContext(this, settings.language)
        palette = OverlayThemeResolver.resolve(this, settings.themeMode)
        val p = palette

        toolbarContainer.background = roundedBg(p.containerBg, dp(16).toFloat(), p.containerStroke)
        summaryStrip.background = roundedBg(p.containerBg, dp(11).toFloat(), p.containerStroke)
        solutionSwitcherBox.background = roundedBg(p.chipOffBg, dp(8).toFloat(), p.chipOffStroke)
        calibrationBar.background = roundedBg(p.containerBg, dp(11).toFloat(), p.tertiary)

        pagerText.setTextColor(p.onSurface)
        summaryTitle.setTextColor(p.onSurface)
        styleChip(prevBtn, Color.TRANSPARENT, p.onSurface, null)
        styleChip(nextBtn, Color.TRANSPARENT, p.onSurface, null)

        // Localized Accessibility Labels for every permanent icon
        solveIconBtn.contentDescription = lctx.getString(R.string.ov_solve)
        executeIconBtn.contentDescription = lctx.getString(R.string.ov_exec)
        stopIconBtn.contentDescription = lctx.getString(R.string.ov_stop)
        pathIconBtn.contentDescription = lctx.getString(R.string.ov_line)
        resultIconBtn.contentDescription = lctx.getString(R.string.ov_result_btn)
        calibIconBtn.contentDescription = lctx.getString(R.string.ov_calib)
        tuneIconBtn.contentDescription = lctx.getString(R.string.ov_tuning)
        closeIconBtn.contentDescription = lctx.getString(R.string.btn_close)

        val calibLabels = listOf(
            R.string.ov_calib_up, R.string.ov_calib_down, R.string.ov_calib_narrow,
            R.string.ov_calib_wide, R.string.ov_calib_hminus, R.string.ov_calib_hplus
        )
        calibNudgeBtns.forEachIndexed { idx, btn ->
            if (idx in calibLabels.indices) btn.text = lctx.getString(calibLabels[idx])
            styleChip(btn, p.secondaryContainer, p.onSecondaryContainer, null)
        }
        calibAutoBtn.text = lctx.getString(R.string.ov_calib_auto)
        calibResetBtn.text = lctx.getString(R.string.ov_calib_reset)
        styleChip(calibAutoBtn, p.primary, p.onPrimary, null)
        styleChip(calibResetBtn, p.errorContainer, p.onErrorContainer, null)

        boardEditor.applyTheme(p, lctx)
        setPhase(currentPhase)
        refreshMainToggleChips()
        refreshActiveShieldStrip()
        updateSummaryStrip()
        syncDrawerData()
    }

    private fun refreshMainToggleChips() {
        if (!::solveIconBtn.isInitialized || !::palette.isInitialized) return
        val p = palette

        // Preview-Confirm State Machine:
        //  - Preview OFF: hide '算' icon; clicking '行' calculates & auto-swipes in 1 tap!
        //  - Preview ON:  show '算' icon; '行' stays disabled until '算' produces a solution (or Hold-Replan is ON)!
        solveIconBtn.visibility = if (settings.testModeOnlyDraw) View.VISIBLE else View.GONE
        styleIconBtn(solveIconBtn, bgColor = p.primary, iconTint = p.onPrimary, strokeColor = null, enabled = true)

        val hasSol = currentSolution() != null
        val canExecute = !settings.testModeOnlyDraw || settings.holdReplanEnabled || hasSol
        executeIconBtn.setImageResource(
            if (settings.holdReplanEnabled) R.drawable.ic_ov_hold else R.drawable.ic_ov_play
        )
        executeIconBtn.isEnabled = canExecute
        executeIconBtn.isClickable = canExecute
        if (!canExecute) {
            styleIconBtn(executeIconBtn, bgColor = p.chipOffBg, iconTint = p.onSurfaceVariant, strokeColor = p.chipOffStroke, enabled = false)
        } else if (settings.holdReplanEnabled) {
            styleIconBtn(executeIconBtn, bgColor = p.tertiaryContainer, iconTint = p.onTertiaryContainer, strokeColor = p.tertiary, enabled = true)
        } else {
            styleIconBtn(executeIconBtn, bgColor = p.primaryContainer, iconTint = p.onPrimaryContainer, strokeColor = p.primary, enabled = true)
        }

        // Trajectory line toggle ('线')
        if (showPath) {
            styleIconBtn(pathIconBtn, bgColor = p.primary, iconTint = p.onPrimary, strokeColor = null)
        } else {
            styleIconBtn(pathIconBtn, bgColor = p.chipOffBg, iconTint = p.chipOffText, strokeColor = p.chipOffStroke)
        }

        // Result card toggle ('结')
        if (showResultCard) {
            styleIconBtn(
                resultIconBtn,
                bgColor = if (hasSol) p.primaryContainer else p.secondaryContainer,
                iconTint = if (hasSol) p.onPrimaryContainer else p.onSecondaryContainer,
                strokeColor = if (hasSol) p.primary else null
            )
        } else {
            styleIconBtn(resultIconBtn, bgColor = p.chipOffBg, iconTint = p.chipOffText, strokeColor = p.chipOffStroke)
        }

        // Alignment Grid Calibration toggle ('校')
        if (calibrationMode) {
            styleIconBtn(calibIconBtn, bgColor = p.tertiary, iconTint = p.onTertiary, strokeColor = null)
        } else {
            styleIconBtn(calibIconBtn, bgColor = p.chipOffBg, iconTint = p.chipOffText, strokeColor = p.chipOffStroke)
        }

        // 4-Tab Drawer toggle
        if (tuningDrawerOpen) {
            styleIconBtn(tuneIconBtn, bgColor = p.primary, iconTint = p.onPrimary, strokeColor = null)
        } else {
            styleIconBtn(tuneIconBtn, bgColor = p.chipOffBg, iconTint = p.chipOffText, strokeColor = p.chipOffStroke)
        }

        styleIconBtn(closeIconBtn, bgColor = p.chipOffBg, iconTint = p.onSurfaceVariant, strokeColor = p.chipOffStroke)
    }

    private fun refreshActiveShieldStrip() {
        if (!::activeShieldRow.isInitialized || !::palette.isInitialized) return
        activeShieldRow.removeAllViews()
        val hasSaved = savedEditedBoard != null
        if ((activeWaveChips.isEmpty() && !hasSaved) || !toolbarExpanded) {
            activeShieldScroll.visibility = View.GONE
            return
        }
        val p = palette
        if (hasSaved) {
            val savedBg = if (p.isDark) Color.rgb(72, 46, 12) else Color.rgb(255, 243, 224)
            val savedFg = if (p.isDark) Color.rgb(255, 213, 79) else Color.rgb(191, 54, 12)
            val savedStroke = Color.rgb(230, 118, 0)
            val savedPill = TextView(this).apply {
                text = "✎ ${lctx.getString(R.string.ov_saved_board_pill)} ×"
                textSize = 9f
                typeface = Typeface.DEFAULT_BOLD
                setSingleLine(true)
                setTextColor(savedFg)
                setPadding(dp(6), dp(2), dp(6), dp(2))
                background = roundedBg(savedBg, dp(6).toFloat(), savedStroke)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(3) }
                setOnClickListener {
                    savedEditedBoard = null
                    boardEditor.setSavedState(false)
                    invalidateLastSolution()
                    refreshActiveShieldStrip()
                    syncDrawerData()
                    refreshPathCanvas()
                    toast(lctx.getString(R.string.ov_toast_board_cleared))
                }
            }
            activeShieldRow.addView(savedPill)
        }
        for (chip in activeWaveChips) {
            val pill = TextView(this).apply {
                text = "${LocaleHelper.formatWaveChipLabel(lctx, chip)} ×"
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
        if (activeWaveChips.isNotEmpty()) {
            val clearAll = TextView(this).apply {
                text = lctx.getString(R.string.btn_clear)
                textSize = 9f
                typeface = Typeface.DEFAULT_BOLD
                setSingleLine(true)
                setTextColor(p.onErrorContainer)
                setPadding(dp(5), dp(2), dp(5), dp(2))
                background = roundedBg(p.errorContainer, dp(6).toFloat(), null)
                setOnClickListener { ActiveStrategyStore.clearAll() }
            }
            activeShieldRow.addView(clearAll)
        }
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
            boardEditorOpen = boardEditorMode,
            hasSavedBoard = savedEditedBoard != null
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
        if (out == null || sol == null || geom == null || !showResultCard || !toolbarExpanded) {
            summaryStrip.visibility = View.GONE
            return
        }

        pagerText.text = "${selectedSolutionIdx + 1}/${out.solutions.size}"
        solutionSwitcherBox.visibility = if (out.solutions.size > 1) View.VISIBLE else View.GONE

        val activeBoard = savedEditedBoard ?: editedBoard ?: lastRecognition?.board
        val verdicts = if (activeBoard != null) {
            ShieldVerdictEvaluator.evaluate(activeWaveChips, activeBoard, sol)
        } else {
            emptyList()
        }
        val passedCnt = verdicts.count { it.status == ShieldVerdictStatus.PASSED }
        val allVerdictsPassed = verdicts.all { it.status == ShieldVerdictStatus.PASSED }
        val shieldBadge = when {
            verdicts.isNotEmpty() && sol.allHardConstraintsMet && allVerdictsPassed -> "✓${verdicts.size}/${verdicts.size}"
            verdicts.isNotEmpty() -> "⚠$passedCnt/${verdicts.size}"
            sol.allHardConstraintsMet && !lastPuzzleMaskMissing && !lastTrafficLightMissing -> "✓"
            else -> "!"
        }
        val coreSummary = lctx.getString(
            R.string.ov_summary_fmt,
            geom.cols,
            geom.rows,
            sol.firstWaveCombo,
            sol.totalCombo,
            sol.stepCount,
            out.elapsedMs.toInt()
        )
        val extra = buildString {
            append(" ").append(shieldBadge)
            if (usedMemorizedSnapshot) append(" · ★")
            val puzzleCnt = activeBoard?.tileFlags?.count {
                (it and (TileFlagBits.PUZZLE_1 or TileFlagBits.PUZZLE_2)) != 0
            } ?: 0
            if (puzzleCnt > 0) append(" · 🧩").append(puzzleCnt)
            val hasTlStart = activeBoard?.tileFlags?.any { (it and TileFlagBits.START_ALLOWED) != 0 } == true
            val hasTlEnd = activeBoard?.tileFlags?.any { (it and TileFlagBits.END_REQUIRED) != 0 } == true
            if (hasTlStart || hasTlEnd) {
                val tlTag = when {
                    hasTlStart && hasTlEnd -> lctx.getString(R.string.ov_tl_badge_both)
                    hasTlStart -> lctx.getString(R.string.ov_tl_badge_start_only)
                    else -> lctx.getString(R.string.ov_tl_badge_end_only)
                }
                append(" · 🚦").append(tlTag)
            }
            val stormCnt = lastRecognition?.stormCellsDetected ?: 0
            if (stormCnt > 0) append(" · 🌀").append(stormCnt)
        }
        summaryTitle.text = coreSummary + extra

        // Per-Shield Satisfaction Verdict Pills (✓ passed / ✗ failed / ? marker undetected)
        shieldStatusRow.removeAllViews()
        val isDark = ::palette.isInitialized && palette.isDark
        for (v in verdicts) {
            val (bgCol, fgCol, strokeCol) = when (v.status) {
                ShieldVerdictStatus.PASSED -> Triple(
                    if (isDark) Color.rgb(22, 56, 38) else Color.rgb(232, 245, 233),
                    if (isDark) Color.rgb(129, 236, 172) else Color.rgb(27, 94, 32),
                    Color.rgb(46, 125, 50)
                )
                ShieldVerdictStatus.FAILED -> Triple(
                    if (isDark) Color.rgb(72, 24, 28) else Color.rgb(255, 235, 238),
                    if (isDark) Color.rgb(255, 138, 128) else Color.rgb(183, 28, 28),
                    Color.rgb(211, 47, 47)
                )
                ShieldVerdictStatus.UNDETECTED -> Triple(
                    if (isDark) Color.rgb(66, 48, 16) else Color.rgb(255, 248, 225),
                    if (isDark) Color.rgb(255, 213, 79) else Color.rgb(230, 81, 0),
                    Color.rgb(245, 124, 0)
                )
            }
            val pill = TextView(this).apply {
                text = LocaleHelper.formatShieldVerdictLabel(lctx, v)
                textSize = 9f
                setSingleLine(true)
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(fgCol)
                setPadding(dp(5), dp(1), dp(5), dp(1))
                background = roundedBg(bgCol, dp(5).toFloat(), strokeCol)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(3) }
            }
            shieldStatusRow.addView(pill)
        }
        shieldStatusScroll.visibility = if (summaryPillsExpanded && verdicts.isNotEmpty()) View.VISIBLE else View.GONE

        groupsRow.removeAllViews()
        for (grp in sol.groups) {
            val pill = TextView(this).apply {
                val ench = if (grp.hasEnchanted) "+" else ""
                val waveMark = if (grp.wave > 1) "↓" else ""
                text = "${LocaleHelper.elementLabel(lctx, grp.element.code)}${grp.orbCount}$ench$waveMark"
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
        summaryStrip.visibility = View.VISIBLE
        summaryTitle.requestLayout()
        summaryStrip.requestLayout()
        clampControlWindowToScreen()
    }

    private fun refreshPathCanvas(countdown: String? = null) {
        if (enemyPopupCoveringBoard && !calibrationMode && !boardEditorMode) {
            // Never paint memorized colored orb circles or swipe arrows on top of Enemy Long-Press / Stage Info modals!
            pathOverlayView.updateState(null, null, emptyList(), null, false, false, false, null, null, false)
            return
        }
        val memBoard = if (currentPhase == OverlayPhase.MOVING) null else flashMemoryTracker.currentLockedSnapshot()
        val displayBoard = when {
            boardEditorMode -> editedBoard ?: savedEditedBoard ?: lastRecognition?.board
            savedEditedBoard != null -> savedEditedBoard
            usedMemorizedSnapshot && memBoard != null -> memBoard
            else -> lastRecognition?.board
        }
        pathOverlayView.updateState(
            geometry = currentGeometry,
            board = displayBoard,
            lowConfidence = if (usedMemorizedSnapshot || boardEditorMode || savedEditedBoard != null) emptyList() else (lastRecognition?.lowConfidenceIndices ?: emptyList()),
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
                statusOrbBadge.visibility = View.GONE
                statusOrbView.background = circleBg(Color.TRANSPARENT, p.orbStroke)
                if (::stopIconBtn.isInitialized) {
                    styleIconBtn(stopIconBtn, p.errorContainer, p.onErrorContainer, null)
                }
            }
            OverlayPhase.CALCULATING -> {
                statusOrbBadge.visibility = View.VISIBLE
                statusOrbBadge.text = "…"
                statusOrbBadge.setTextColor(p.onTertiary)
                statusOrbBadge.setBackgroundColor(
                    Color.argb(195, Color.red(p.tertiary), Color.green(p.tertiary), Color.blue(p.tertiary))
                )
                statusOrbView.background = circleBg(p.tertiary, p.orbStroke)
                if (::stopIconBtn.isInitialized) {
                    styleIconBtn(stopIconBtn, p.error, p.onError, p.onError)
                }
            }
            OverlayPhase.MOVING -> {
                statusOrbBadge.visibility = View.VISIBLE
                statusOrbBadge.text = "▶"
                statusOrbBadge.setTextColor(p.onPrimary)
                statusOrbBadge.setBackgroundColor(
                    Color.argb(195, Color.red(p.primary), Color.green(p.primary), Color.blue(p.primary))
                )
                statusOrbView.background = circleBg(p.primary, p.orbStroke)
                if (::stopIconBtn.isInitialized) {
                    styleIconBtn(stopIconBtn, p.error, p.onError, p.onError)
                }
            }
        }
    }

    private fun makeIconBtn(
        drawableRes: Int,
        marginEndDp: Int = 3,
        onClick: () -> Unit
    ): ImageView {
        return ImageView(this).apply {
            setImageResource(drawableRes)
            scaleType = ImageView.ScaleType.FIT_CENTER
            val size = dp(28)
            val pad = dp(5)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = dp(marginEndDp)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun styleIconBtn(
        view: ImageView,
        bgColor: Int,
        iconTint: Int,
        strokeColor: Int?,
        enabled: Boolean = true
    ) {
        view.background = roundedBg(bgColor, dp(9).toFloat(), strokeColor)
        view.colorFilter = PorterDuffColorFilter(iconTint, PorterDuff.Mode.SRC_IN)
        view.alpha = if (enabled) 1.0f else 0.30f
    }

    private fun styleChip(view: TextView, bgColor: Int, textColor: Int, strokeColor: Int?) {
        view.setTextColor(textColor)
        view.background = roundedBg(bgColor, dp(8).toFloat(), strokeColor)
        view.alpha = 1.0f
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
