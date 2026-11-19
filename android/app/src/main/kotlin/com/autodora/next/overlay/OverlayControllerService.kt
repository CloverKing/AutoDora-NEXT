package com.autodora.next.overlay

import android.annotation.SuppressLint
import android.app.Notification
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
import com.autodora.next.data.AppSettings
import com.autodora.next.data.SettingsRepository
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
    @Inject lateinit var boardRecognizer: BoardRecognizer
    @Inject lateinit var touchExecutor: TouchExecutor

    private lateinit var windowManager: WindowManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var screenW = 1200
    private var screenH = 2670
    private var settings = AppSettings()
    private lateinit var palette: OverlayPalette

    // Overlay views
    private lateinit var pathOverlayView: PathOverlayView
    private lateinit var pathParams: WindowManager.LayoutParams
    private lateinit var controlRoot: LinearLayout
    private lateinit var controlParams: WindowManager.LayoutParams
    private lateinit var statusOrbView: TextView
    private lateinit var toolbarContainer: LinearLayout
    private lateinit var modeChipRow: LinearLayout
    private lateinit var solveBtn: TextView
    private lateinit var prevBtn: TextView
    private lateinit var nextBtn: TextView
    private lateinit var executeBtn: TextView
    private lateinit var stopChipBtn: TextView
    private lateinit var pathToggleBtn: TextView
    private lateinit var calibToggleBtn: TextView
    private lateinit var moreToggleBtn: TextView
    private lateinit var closeBtn: TextView
    private lateinit var memoryChipBtn: TextView
    private lateinit var holdReplanBtn: TextView
    private lateinit var invertChipBtn: TextView
    private lateinit var speedChipBtn: TextView
    private lateinit var calibrationBar: LinearLayout
    private val calibNudgeBtns = ArrayList<TextView>()
    private lateinit var calibAutoBtn: TextView
    private lateinit var calibResetBtn: TextView
    private lateinit var summaryStrip: LinearLayout
    private lateinit var summaryTitle: TextView
    private lateinit var groupsScroll: HorizontalScrollView
    private lateinit var groupsRow: LinearLayout
    private lateinit var pagerText: TextView

    // Turn state
    private var currentPhase = OverlayPhase.IDLE
    private var toolbarExpanded = true
    private var modeRowExpanded = false
    private var summaryPillsExpanded = true
    private var showPath = true
    private var calibrationMode = false
    private var currentGeometry: BoardGeometry? = null
    private var lastRecognition: RecognitionResult? = null
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
            .setContentTitle("AutoDora-NEXT 悬浮窗运行中")
            .setContentText("点击悬浮球即可识图解盘或自动转珠")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupOverlayViews() {
        // 1. Full-screen PathOverlayView (click-through normally; touch-draggable in Calibration Mode!)
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
            // Android 12+ requires <= 0.80 alpha for FLAG_NOT_TOUCHABLE overlays so game touches always pass cleanly
            alpha = 0.80f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
            }
        }
        windowManager.addView(pathOverlayView, pathParams)

        // 2. Refined compact floating toolbar with integrated Emergency Stop + collapsible quick-modes + slim HUD summary
        controlRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        // CRITICAL: Must explicitly use WRAP_CONTENT width! Default in vertical LinearLayout is MATCH_PARENT,
        // which causes Android measureVertical() to crush topRow to the narrower modeChipRow width and clip right buttons!
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

        executeBtn = makeChipBtn("行", minW = 24, padH = 5) { executeSelectedSolution() }
        toolbarContainer.addView(executeBtn)

        // Integrated Emergency Stop right inside main toolbar!
        stopChipBtn = makeChipBtn("停", minW = 24, padH = 5) {
            triggerEmergencyStop()
            toast("已急停中止！")
        }
        toolbarContainer.addView(stopChipBtn)

        pathToggleBtn = makeChipBtn("●线", minW = 28, padH = 4) {
            showPath = !showPath
            refreshMainToggleChips()
            refreshPathCanvas()
        }
        toolbarContainer.addView(pathToggleBtn)

        calibToggleBtn = makeChipBtn("○校", minW = 28, padH = 4) { toggleCalibrationMode() }
        toolbarContainer.addView(calibToggleBtn)

        moreToggleBtn = makeChipBtn("⋯", minW = 20, padH = 3) {
            modeRowExpanded = !modeRowExpanded
            modeChipRow.visibility = if (modeRowExpanded && toolbarExpanded) View.VISIBLE else View.GONE
            refreshMainToggleChips()
            clampControlWindowToScreen()
        }
        toolbarContainer.addView(moreToggleBtn)

        closeBtn = makeChipBtn("✕", minW = 20, padH = 3, marginEndDp = 0) { stopSelf() }
        toolbarContainer.addView(closeBtn)

        topRow.addView(toolbarContainer)
        controlRoot.addView(topRow)

        // Collapsible Slim Quick-Mode Row (hidden by default; expand via '⋯' button)
        modeChipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            elevation = dp(2).toFloat()
            visibility = View.GONE
            setPadding(dp(4), dp(3), dp(4), dp(3))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(3)
                marginStart = dp(34)
            }
        }
        memoryChipBtn = makeChipBtn("○记忆", padH = 5) {
            serviceScope.launch {
                settingsRepository.update { it.copy(flashRevealMemoryEnabled = !it.flashRevealMemoryEnabled) }
            }
        }
        modeChipRow.addView(memoryChipBtn)

        holdReplanBtn = makeChipBtn("按住转", padH = 5) { startHoldAndReplanExecution() }
        modeChipRow.addView(holdReplanBtn)

        invertChipBtn = makeChipBtn("○颠倒", padH = 5) {
            serviceScope.launch {
                settingsRepository.update {
                    val next = !(it.invertDragX || it.invertDragY)
                    it.copy(invertDragX = next, invertDragY = next)
                }
            }
        }
        modeChipRow.addView(invertChipBtn)

        // 1-to-1 synced with DashboardScreen SwipeSpeedPreset!
        speedChipBtn = makeChipBtn("标准", padH = 5, marginEndDp = 0) {
            serviceScope.launch {
                settingsRepository.update { s ->
                    val nextPreset = SwipeSpeedPreset.fromStepDurationMs(s.stepDurationMs).next()
                    s.copy(stepDurationMs = nextPreset.stepDurationMs)
                }
            }
        }
        modeChipRow.addView(speedChipBtn)
        controlRoot.addView(modeChipRow)

        // Ultra-Compact System-Themed Solution HUD Strip (tap to fold/unfold orb pills row!)
        summaryStrip = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            elevation = dp(2).toFloat()
            visibility = View.GONE
            setPadding(dp(7), dp(3), dp(7), dp(3))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(3)
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
            ).apply {
                topMargin = dp(2)
            }
        }
        groupsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        groupsScroll.addView(groupsRow)
        summaryStrip.addView(groupsScroll)
        controlRoot.addView(summaryStrip)

        // Slim In-place Calibration Bar
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
                        modeChipRow.visibility = if (toolbarExpanded && modeRowExpanded) View.VISIBLE else View.GONE
                        if (!toolbarExpanded) {
                            calibrationBar.visibility = View.GONE
                            summaryStrip.visibility = View.GONE
                        } else {
                            if (calibrationMode) calibrationBar.visibility = View.VISIBLE
                            if (lastSolveOutput != null) summaryStrip.visibility = View.VISIBLE
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

    private fun startCaptureAndSolve() {
        if (currentPhase != OverlayPhase.IDLE) {
            toast("当前任务执行中，可点击红色急停中止")
            return
        }
        val capturer = ScreenCaptureSession.capturer.value
        if (capturer == null || !capturer.isReady.value) {
            toast("请先在主界面授权「免 Root 录屏采集」")
            return
        }

        if (calibrationMode) {
            setCalibrationMode(false)
        }

        activeJob = serviceScope.launch {
            setPhase(OverlayPhase.CALCULATING)
            // Hide path lines cleanly before taking screenshot so our own lines never pollute CV recognition!
            pathOverlayView.updateState(null, null, emptyList(), null, false, false, false)
            delay(45)

            val frames = capturer.captureMultiFrames(count = settings.multiFrameCount, intervalMs = 50L)
            if (frames.isEmpty()) {
                setPhase(OverlayPhase.IDLE)
                refreshPathCanvas()
                toast("截屏失败，请确认录屏授权仍有效")
                return@launch
            }

            try {
                // Stage 1/2 Dynamic Grid Size Auto-Detection (HP-bar barrier prevents false Weijian!)
                val geom = withContext(Dispatchers.Default) {
                    AutoGridDetector.detect(frames.first(), settings.calibration)
                }

                // Multi-frame CV / Hybrid Recognition
                val liveRec = boardRecognizer.recognize(frames, geom)

                // Flash-Reveal Memory Resolution (先露后藏全版问号珠自动还原!)
                var resolvedFromMemory = false
                val boardToSolve = if (settings.flashRevealMemoryEnabled) {
                    when (val res = flashMemoryTracker.resolveForSolve(liveRec)) {
                        is FlashRevealMemoryTracker.MemoryResolution.UseMemorizedSnapshot -> {
                            resolvedFromMemory = true
                            toast("已从先露后藏记忆快照还原 ${res.liveHiddenCount} 颗问号珠！")
                            res.rememberedBoard
                        }
                        is FlashRevealMemoryTracker.MemoryResolution.UseLiveBoard -> {
                            liveRec.board
                        }
                    }
                } else {
                    liveRec.board
                }

                // Non-Battle Screen Guard: never draw an empty grid or solve bogus 0C paths on victory/lobby/menu screens!
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
                usedMemorizedSnapshot = resolvedFromMemory
                updateQuickModeChips()

                // Compose RuleConfig (auto-enables Puzzle shield if golden puzzle borders were detected!)
                val ruleConfig = RuleComposer.compose(
                    base = TeamBasePreset("active", "Active", baseConfig = settings.toRuleConfig()),
                    activeChips = emptyList(),
                    board = boardToSolve
                )

                // C++20 JNI Solve with cooperative cancellation token
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
                    toast("当前盘面无可消除路线，请确认处于战斗盘面或检查校准框")
                    return@launch
                }

                lastSolveOutput = solveOut
                selectedSolutionIdx = 0
                showPath = true
                refreshMainToggleChips()
                updateSummaryStrip()
                refreshPathCanvas()

                setPhase(OverlayPhase.IDLE)

                // If Test Mode (draw-only confirmation) is OFF, auto-execute immediately!
                if (!settings.testModeOnlyDraw) {
                    executeSelectedSolution()
                }
            } catch (e: CancellationException) {
                setPhase(OverlayPhase.IDLE)
            } catch (e: Exception) {
                Log.e(TAG, "Capture/Solve failed", e)
                setPhase(OverlayPhase.IDLE)
                toast("识图或算路异常: ${e.message}")
            } finally {
                frames.forEach { if (!it.isRecycled) it.recycle() }
            }
        }
    }

    /**
     * No-Root Hold-and-Replan Mode (问号‧转 / 风化‧转 / 烟雾):
     * Presses and holds a safe orb continuously via AccessibilityService willContinue=true heartbeat,
     * waits [settings.holdReplanDelayMs] for the board to transform/reveal, captures + fast-solves with
     * fixedStartCell (~70ms), and seamlessly continues swiping without lifting the finger!
     */
    private fun startHoldAndReplanExecution() {
        if (currentPhase != OverlayPhase.IDLE) {
            toast("当前任务执行中，可点击红色急停中止")
            return
        }
        val capturer = ScreenCaptureSession.capturer.value
        if (capturer == null || !capturer.isReady.value) {
            toast("请先在主界面授权「免 Root 录屏采集」")
            return
        }
        if (!touchExecutor.isAvailable.value) {
            toast("按住重算需要开启 AutoDora-NEXT 无障碍服务")
            return
        }
        if (calibrationMode) setCalibrationMode(false)

        activeJob = serviceScope.launch {
            setPhase(OverlayPhase.MOVING)
            pathOverlayView.updateState(null, null, emptyList(), null, false, false, false)
            delay(35)

            // Estimate current board geometry cleanly before grabbing
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
                    // Captured mid-hold while virtual finger stays pressed!
                    val revealedBmp = capturer.captureSingleFrame() ?: return@executeHoldAndReplan null
                    try {
                        val rec = boardRecognizer.recognize(listOf(revealedBmp), geom)
                        lastRecognition = rec
                        usedMemorizedSnapshot = false

                        val heldIdx = heldCell.row * geom.cols + heldCell.col
                        val fastRules = settings.toRuleConfig().copy(
                            fixedStartCell = heldIdx,
                            beamWidth = minOf(settings.beamWidth, 140),
                            maxTimeMs = 170,
                            topK = 2
                        )
                        val composed = RuleComposer.compose(
                            base = TeamBasePreset("hold_replan", "HoldReplan", baseConfig = fastRules),
                            activeChips = emptyList(),
                            board = rec.board
                        )
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
                        toast("按住重算已急停中止")
                    }
                    is TouchExecutionResult.Failed -> toast("按住重算失败: ${result.reason}")
                }
            } finally {
                setControlBarTouchable(true)
                setPhase(OverlayPhase.IDLE)
                updateQuickModeChips()
                refreshPathCanvas()
            }
        }
    }

    private fun executeSelectedSolution() {
        if (currentPhase != OverlayPhase.IDLE) return
        val geom = currentGeometry ?: return
        val sol = currentSolution() ?: run {
            toast("请先点击「识图算路」")
            return
        }

        if (!touchExecutor.isAvailable.value) {
            toast("无障碍服务未启用！请前往系统设置开启 AutoDora-NEXT 无障碍服务")
            return
        }

        activeJob = serviceScope.launch {
            setPhase(OverlayPhase.MOVING)

            // Optional countdown before auto-swipe (inherited from old AutoDora)
            if (settings.countdownSeconds > 0) {
                for (sec in settings.countdownSeconds downTo 1) {
                    refreshPathCanvas(countdown = "$sec")
                    delay(1000)
                }
                refreshPathCanvas(countdown = null)
            }

            // Only make control bar non-touchable if the user dragged it down over the board;
            // when docked cleanly at top of screen, keep it touchable so integrated '停' works mid-swipe!
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
                        toast("转珠已急停中止")
                    }
                    is TouchExecutionResult.Failed -> {
                        toast("转珠失败: ${result.reason}")
                    }
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

    private fun toggleCalibrationMode() {
        setCalibrationMode(!calibrationMode)
    }

    private fun setCalibrationMode(enabled: Boolean) {
        calibrationMode = enabled
        calibrationBar.visibility = if (calibrationMode) View.VISIBLE else View.GONE
        // Allow direct finger-drag of calibration corners/edges while in Calibration Mode!
        val nextFlags = if (calibrationMode) {
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
        if (calibrationMode) {
            currentGeometry = settings.calibration.toBase6x5Geometry(screenW, screenH)
            showPath = true
        }
        refreshMainToggleChips()
        refreshPathCanvas()
        clampControlWindowToScreen()
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
            toast("自动探测需要已授权录屏采集")
            return
        }
        serviceScope.launch {
            // Hide overlay frame momentarily so cyan border doesn't affect HP bar / edge detection
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
                    toast("真实血条锚定成功：${detected.cols}×${detected.rows} 棋盘！")
                } else {
                    refreshPathCanvas()
                    toast("未检测到神魔战斗盘面，请进关卡后重试或直接拖拽四角校准")
                }
            } finally {
                if (!bmp.isRecycled) bmp.recycle()
            }
        }
    }

    private fun resetCalibration() {
        serviceScope.launch {
            settingsRepository.update { it.copy(calibration = BoardCalibration()) }
            toast("已重置为自动 9:16 适配 6×5 比例")
        }
    }

    /**
     * Lightweight background Flash-Reveal Memory Watcher (先露后藏问号珠实时监视):
     * Samples every 420ms while idle; caches clear boards and automatically locks & displays
     * translucent real-orb overlay the instant a stage flips to mass '?' orbs!
     */
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
                if (currentPhase != OverlayPhase.IDLE || calibrationMode) continue
                val capturer = ScreenCaptureSession.capturer.value ?: continue
                if (!capturer.isReady.value) continue
                // Skip polling while our own path overlay is covering the board unless waiting for flip
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
                        toast("已捕捉先露后藏盘面！点「算路」可直接解全版问号珠")
                    }
                    updateQuickModeChips()
                } catch (_: Exception) {
                } finally {
                    if (!bmp.isRecycled) bmp.recycle()
                }
            }
        }
    }

    /**
     * Re-applies Android System Material 3 palette (Light / Dark / Follow System + Android 12+ Material You)
     * across every floating container, button, toggle chip, and text element live without restart.
     */
    private fun applyOverlayTheme() {
        if (!::toolbarContainer.isInitialized) return
        palette = OverlayThemeResolver.resolve(this, settings.themeMode)
        val p = palette

        // Material 3 containers
        toolbarContainer.background = roundedBg(p.containerBg, dp(16).toFloat(), p.containerStroke)
        modeChipRow.background = roundedBg(p.containerBg, dp(11).toFloat(), p.containerStroke)
        summaryStrip.background = roundedBg(p.containerBg, dp(10).toFloat(), p.containerStroke)
        calibrationBar.background = roundedBg(p.containerBg, dp(11).toFloat(), p.tertiary)

        // Typography
        pagerText.setTextColor(p.onSurface)
        summaryTitle.setTextColor(p.onSurface)

        // Main bar action buttons (distinct Material 3 emphasis hierarchy)
        styleChip(solveBtn, p.primary, p.onPrimary, null)
        styleChip(prevBtn, p.chipOffBg, p.onSurface, p.chipOffStroke)
        styleChip(nextBtn, p.chipOffBg, p.onSurface, p.chipOffStroke)
        styleChip(executeBtn, p.primaryContainer, p.onPrimaryContainer, p.primary)
        styleChip(closeBtn, p.chipOffBg, p.onSurfaceVariant, p.chipOffStroke)

        // Quick-mode action button
        styleChip(holdReplanBtn, p.secondaryContainer, p.onSecondaryContainer, null)

        // Calibration bar buttons
        for (btn in calibNudgeBtns) {
            styleChip(btn, p.secondaryContainer, p.onSecondaryContainer, null)
        }
        styleChip(calibAutoBtn, p.primary, p.onPrimary, null)
        styleChip(calibResetBtn, p.errorContainer, p.onErrorContainer, null)

        // Sync phase orb + stop button + all ON/OFF toggle chips
        setPhase(currentPhase)
        refreshMainToggleChips()
        updateQuickModeChips()
    }

    /**
     * Clearly distinguishes ON vs OFF for every toggle button in the main toolbar using BOTH
     * Material 3 solid-vs-tonal contrast AND explicit '●' / '○' state indicators.
     */
    private fun refreshMainToggleChips() {
        if (!::pathToggleBtn.isInitialized || !::palette.isInitialized) return
        val p = palette

        // Path overlay toggle (线)
        pathToggleBtn.text = if (showPath) "●线" else "○线"
        styleToggleChip(pathToggleBtn, isOn = showPath, onBg = p.primary, onText = p.onPrimary)

        // Calibration mode toggle (校)
        calibToggleBtn.text = if (calibrationMode) "●校" else "○校"
        styleToggleChip(calibToggleBtn, isOn = calibrationMode, onBg = p.tertiary, onText = p.onTertiary)

        // Quick-mode drawer expand toggle (⋯ / ▾)
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

        // Flash-Reveal Memory toggle (记忆) — unmistakable ON vs OFF + live status!
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

        // Inverted Drag Controls toggle (颠倒)
        val invOn = settings.invertDragX || settings.invertDragY
        invertChipBtn.text = if (invOn) "●颠倒" else "○颠倒"
        styleToggleChip(invertChipBtn, isOn = invOn, onBg = p.tertiary, onText = p.onTertiary)

        // Swipe speed selector — 1:1 synchronized with DashboardScreen SwipeSpeedPreset!
        val preset = SwipeSpeedPreset.fromStepDurationMs(settings.stepDurationMs)
        speedChipBtn.text = preset.label
        when (preset) {
            SwipeSpeedPreset.TURBO_2S -> styleChip(speedChipBtn, p.errorContainer, p.onErrorContainer, p.error)
            SwipeSpeedPreset.FAST_3S -> styleChip(speedChipBtn, p.tertiaryContainer, p.onTertiaryContainer, p.tertiary)
            SwipeSpeedPreset.STANDARD -> styleChip(speedChipBtn, p.primaryContainer, p.onPrimaryContainer, null)
            SwipeSpeedPreset.STEADY -> styleChip(speedChipBtn, p.secondaryContainer, p.onSecondaryContainer, null)
        }
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
        val sizeBadge = if (geom.isWeijianExpanded) "${geom.cols}×${geom.rows}违建" else "${geom.cols}×${geom.rows}"
        val shieldBadge = if (sol.allHardConstraintsMet) "✓" else "!"
        val memTag = if (usedMemorizedSnapshot) " · 记忆" else ""
        val lowWarn = if (usedMemorizedSnapshot) 0 else (lastRecognition?.lowConfidenceIndices?.size ?: 0)
        val lowStr = if (lowWarn > 0) " · 疑$lowWarn" else ""

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
        pathOverlayView.updateState(
            geometry = currentGeometry,
            board = if (usedMemorizedSnapshot && memBoard != null) memBoard else lastRecognition?.board,
            lowConfidence = if (usedMemorizedSnapshot) emptyList() else (lastRecognition?.lowConfidenceIndices ?: emptyList()),
            solution = currentSolution(),
            showPath = showPath,
            showOrbBadges = settings.showOrbBadgesOnPath,
            calibrationMode = calibrationMode,
            countdownText = countdown,
            memorizedOverlayBoard = memBoard
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
        if (isOn) {
            styleChip(view, onBg, onText, onStroke)
        } else {
            styleChip(view, p.chipOffBg, p.chipOffText, p.chipOffStroke)
        }
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
            "AutoDora-NEXT 悬浮窗控制服务",
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
