package com.autodora.next.overlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import com.autodora.next.touch.SwipeConfig
import com.autodora.next.touch.TouchExecutionResult
import com.autodora.next.touch.TouchExecutor
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

    // Overlay views
    private lateinit var pathOverlayView: PathOverlayView
    private lateinit var pathParams: WindowManager.LayoutParams
    private lateinit var controlRoot: LinearLayout
    private lateinit var controlParams: WindowManager.LayoutParams
    private lateinit var statusOrbView: TextView
    private lateinit var toolbarContainer: LinearLayout
    private lateinit var modeChipRow: LinearLayout
    private lateinit var moreToggleBtn: TextView
    private lateinit var stopChipBtn: TextView
    private lateinit var memoryChipBtn: TextView
    private lateinit var invertChipBtn: TextView
    private lateinit var turboChipBtn: TextView
    private lateinit var calibrationBar: LinearLayout
    private lateinit var summaryStrip: LinearLayout
    private lateinit var summaryTitle: TextView
    private lateinit var groupsScroll: HorizontalScrollView
    private lateinit var groupsRow: LinearLayout
    private lateinit var pagerText: TextView
    private lateinit var pathToggleBtn: TextView
    private lateinit var executeBtn: TextView

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
        resolvePhysicalScreenSize()
        createNotificationChannel()
        startForegroundCompat()
        setupOverlayViews()
        OverlayServiceState.setRunning(true)

        serviceScope.launch {
            settingsRepository.settingsFlow.collectLatest { latest ->
                settings = latest
                stopChipBtn.visibility = if (latest.showEmergencyStop) View.VISIBLE else View.GONE
                updateQuickModeChips()
                syncFlashRevealWatcher()
                if (calibrationMode) {
                    currentGeometry = latest.calibration.toBase6x5Geometry(screenW, screenH)
                    refreshPathCanvas()
                }
            }
        }
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

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        statusOrbView = TextView(this).apply {
            text = "D"
            textSize = 12f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            val size = dp(30)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = dp(4)
            }
            background = circleBg(Color.rgb(25, 118, 210))
        }
        topRow.addView(statusOrbView)

        toolbarContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(3), dp(3), dp(3))
            background = roundedBg(Color.argb(222, 16, 21, 31), dp(14).toFloat(), Color.argb(105, 110, 185, 255))
        }

        toolbarContainer.addView(makeChipBtn("算", Color.rgb(0, 137, 123)) { startCaptureAndSolve() })
        toolbarContainer.addView(makeChipBtn("◀", Color.argb(190, 52, 63, 80), minW = 20, padH = 4) { switchSolution(-1) })
        pagerText = TextView(this).apply {
            text = "0/0"
            textSize = 9.5f
            setSingleLine(true)
            setTextColor(Color.rgb(210, 228, 255))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(2), 0, dp(2), 0)
        }
        toolbarContainer.addView(pagerText)
        toolbarContainer.addView(makeChipBtn("▶", Color.argb(190, 52, 63, 80), minW = 20, padH = 4) { switchSolution(+1) })

        executeBtn = makeChipBtn("行", Color.rgb(46, 125, 50)) { executeSelectedSolution() }
        toolbarContainer.addView(executeBtn)

        // Integrated Emergency Stop right inside main toolbar!
        stopChipBtn = makeChipBtn("停", Color.rgb(165, 36, 36), strokeColor = Color.argb(160, 255, 160, 160)) {
            triggerEmergencyStop()
            toast("已急停中止！")
        }
        toolbarContainer.addView(stopChipBtn)

        pathToggleBtn = makeChipBtn("线", Color.rgb(57, 73, 171)) {
            showPath = !showPath
            pathToggleBtn.alpha = if (showPath) 1.0f else 0.45f
            refreshPathCanvas()
        }
        toolbarContainer.addView(pathToggleBtn)

        toolbarContainer.addView(makeChipBtn("校", Color.rgb(121, 85, 72)) { toggleCalibrationMode() })

        moreToggleBtn = makeChipBtn("⋯", Color.argb(210, 62, 76, 94), minW = 22, padH = 4) {
            modeRowExpanded = !modeRowExpanded
            modeChipRow.visibility = if (modeRowExpanded && toolbarExpanded) View.VISIBLE else View.GONE
            moreToggleBtn.alpha = if (modeRowExpanded) 1.0f else 0.80f
        }
        toolbarContainer.addView(moreToggleBtn)

        toolbarContainer.addView(makeChipBtn("✕", Color.argb(200, 75, 85, 99), minW = 22, padH = 4, marginEndDp = 0) { stopSelf() })

        topRow.addView(toolbarContainer)
        controlRoot.addView(topRow)

        // Collapsible Slim Quick-Mode Row (hidden by default; expand via '⋯' button)
        modeChipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(4), dp(2), dp(4), dp(2))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(2)
                marginStart = dp(34)
            }
            background = roundedBg(Color.argb(208, 16, 21, 31), dp(9).toFloat(), Color.argb(75, 120, 190, 255))
        }
        memoryChipBtn = makeChipBtn("记忆", Color.rgb(38, 120, 95), padH = 5) {
            serviceScope.launch {
                settingsRepository.update { it.copy(flashRevealMemoryEnabled = !it.flashRevealMemoryEnabled) }
            }
        }
        modeChipRow.addView(memoryChipBtn)
        modeChipRow.addView(makeChipBtn("按住转", Color.rgb(142, 36, 170), padH = 5) { startHoldAndReplanExecution() })
        invertChipBtn = makeChipBtn("颠倒:关", Color.rgb(69, 90, 100), padH = 5) {
            serviceScope.launch {
                settingsRepository.update {
                    val next = !(it.invertDragX || it.invertDragY)
                    it.copy(invertDragX = next, invertDragY = next)
                }
            }
        }
        modeChipRow.addView(invertChipBtn)
        turboChipBtn = makeChipBtn("标准", Color.rgb(55, 71, 79), padH = 5, marginEndDp = 0) {
            serviceScope.launch {
                settingsRepository.update { s ->
                    val nextMs = when {
                        s.stepDurationMs > 50L -> SwipeConfig.FAST_3S_STEP_MS
                        s.stepDurationMs > 30L -> SwipeConfig.TURBO_2S_STEP_MS
                        else -> SwipeConfig.NORMAL_STEP_MS
                    }
                    s.copy(stepDurationMs = nextMs)
                }
            }
        }
        modeChipRow.addView(turboChipBtn)
        controlRoot.addView(modeChipRow)

        // Ultra-Compact Frosted Solution HUD Strip (tap to fold/unfold orb pills row!)
        summaryStrip = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(7), dp(3), dp(7), dp(3))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(2)
                marginStart = dp(34)
            }
            background = roundedBg(Color.argb(200, 14, 18, 28), dp(8).toFloat(), Color.argb(70, 160, 205, 255))
            setOnClickListener {
                summaryPillsExpanded = !summaryPillsExpanded
                groupsScroll.visibility = if (summaryPillsExpanded) View.VISIBLE else View.GONE
            }
        }
        summaryTitle = TextView(this).apply {
            textSize = 9.5f
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Color.rgb(235, 243, 255))
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
            visibility = View.GONE
            setPadding(dp(5), dp(3), dp(5), dp(3))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(2)
                marginStart = dp(34)
            }
            background = roundedBg(Color.argb(228, 14, 34, 48), dp(9).toFloat(), Color.argb(200, 0, 229, 255))
        }
        calibrationBar.addView(makeChipBtn("上", Color.rgb(0, 120, 150), minW = 22, padH = 4) { nudgeCalib(bottomDelta = -0.005f) })
        calibrationBar.addView(makeChipBtn("下", Color.rgb(0, 120, 150), minW = 22, padH = 4) { nudgeCalib(bottomDelta = +0.005f) })
        calibrationBar.addView(makeChipBtn("窄", Color.rgb(0, 100, 130), minW = 22, padH = 4) { nudgeCalib(sideInsetDelta = +0.004f) })
        calibrationBar.addView(makeChipBtn("宽", Color.rgb(0, 100, 130), minW = 22, padH = 4) { nudgeCalib(sideInsetDelta = -0.004f) })
        calibrationBar.addView(makeChipBtn("高-", Color.rgb(0, 90, 110), minW = 24, padH = 4) { nudgeCalib(heightScaleDelta = -0.01f) })
        calibrationBar.addView(makeChipBtn("高+", Color.rgb(0, 90, 110), minW = 24, padH = 4) { nudgeCalib(heightScaleDelta = +0.01f) })
        calibrationBar.addView(makeChipBtn("自动", Color.rgb(46, 125, 50), padH = 5) { runAutoCalibration() })
        calibrationBar.addView(makeChipBtn("重置", Color.rgb(160, 60, 50), padH = 5, marginEndDp = 0) { resetCalibration() })
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
                        controlParams.x = (initX + dx).coerceIn(0, screenW - dp(40))
                        controlParams.y = (initY + dy).coerceIn(0, screenH - dp(60))
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
                toast("截屏失败，请确认录屏授权仍有效")
                return@launch
            }

            try {
                // Stage 1/2 Dynamic Grid Size Auto-Detection (HP-bar barrier prevents false Weijian!)
                val geom = withContext(Dispatchers.Default) {
                    AutoGridDetector.detect(frames.first(), settings.calibration)
                }
                currentGeometry = geom

                // Multi-frame CV / Hybrid Recognition
                val liveRec = boardRecognizer.recognize(frames, geom)
                lastRecognition = liveRec

                // Flash-Reveal Memory Resolution (先露后藏全版问号珠自动还原!)
                val boardToSolve = if (settings.flashRevealMemoryEnabled) {
                    when (val res = flashMemoryTracker.resolveForSolve(liveRec)) {
                        is FlashRevealMemoryTracker.MemoryResolution.UseMemorizedSnapshot -> {
                            usedMemorizedSnapshot = true
                            toast("已从先露后藏记忆快照还原 ${res.liveHiddenCount} 颗问号珠！")
                            res.rememberedBoard
                        }
                        is FlashRevealMemoryTracker.MemoryResolution.UseLiveBoard -> {
                            usedMemorizedSnapshot = false
                            liveRec.board
                        }
                    }
                } else {
                    usedMemorizedSnapshot = false
                    liveRec.board
                }
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

                lastSolveOutput = solveOut
                selectedSolutionIdx = 0
                showPath = true
                pathToggleBtn.alpha = 1.0f
                updateSummaryStrip()
                refreshPathCanvas()

                if (solveOut.solutions.isEmpty()) {
                    setPhase(OverlayPhase.IDLE)
                    toast("未找到可行路线，请检查校准框是否对齐盘面")
                    return@launch
                }

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
                        Log.i(TAG, "Hold-and-Replan completed in ${result.elapsedMs}ms (${result.segmentsDispatched} segs)")
                    }
                    is TouchExecutionResult.Cancelled -> toast("按住重算已急停中止")
                    is TouchExecutionResult.Failed -> toast("按住重算失败: ${result.reason}")
                }
            } finally {
                setControlBarTouchable(true)
                setPhase(OverlayPhase.IDLE)
                updateQuickModeChips()
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
                        updateQuickModeChips()
                        Log.i(TAG, "Auto-swipe completed in ${result.elapsedMs}ms (${result.segmentsDispatched} segs)")
                    }
                    is TouchExecutionResult.Cancelled -> {
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
        refreshPathCanvas()
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

    private fun updateQuickModeChips() {
        if (!::memoryChipBtn.isInitialized) return
        val memLocked = flashMemoryTracker.currentLockedSnapshot() != null
        val memFresh = flashMemoryTracker.hasFreshClearMemory()
        when {
            !settings.flashRevealMemoryEnabled -> {
                memoryChipBtn.text = "记忆:关"
                memoryChipBtn.background = roundedBg(Color.rgb(69, 90, 100), dp(8).toFloat(), null)
            }
            memLocked -> {
                memoryChipBtn.text = "透视?"
                memoryChipBtn.background = roundedBg(Color.rgb(230, 145, 0), dp(8).toFloat(), null)
            }
            memFresh -> {
                memoryChipBtn.text = "记忆●"
                memoryChipBtn.background = roundedBg(Color.rgb(46, 125, 50), dp(8).toFloat(), null)
            }
            else -> {
                memoryChipBtn.text = "记忆"
                memoryChipBtn.background = roundedBg(Color.rgb(0, 121, 107), dp(8).toFloat(), null)
            }
        }

        val invOn = settings.invertDragX || settings.invertDragY
        invertChipBtn.text = if (invOn) "颠倒:开" else "颠倒:关"
        invertChipBtn.background = roundedBg(
            if (invOn) Color.rgb(216, 67, 21) else Color.rgb(69, 90, 100),
            dp(8).toFloat(),
            null
        )

        val (spdLabel, spdColor) = when {
            settings.stepDurationMs <= 28L -> "2秒极速" to Color.rgb(198, 40, 40)
            settings.stepDurationMs <= 45L -> "3秒快转" to Color.rgb(239, 108, 0)
            else -> "标准" to Color.rgb(55, 71, 79)
        }
        turboChipBtn.text = spdLabel
        turboChipBtn.background = roundedBg(spdColor, dp(8).toFloat(), null)
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
        when (phase) {
            OverlayPhase.IDLE -> {
                statusOrbView.text = "D"
                statusOrbView.background = circleBg(Color.rgb(25, 118, 210))
                if (::stopChipBtn.isInitialized) {
                    stopChipBtn.background = roundedBg(Color.rgb(138, 36, 36), dp(7).toFloat(), Color.argb(110, 255, 150, 150))
                }
            }
            OverlayPhase.CALCULATING -> {
                statusOrbView.text = "算"
                statusOrbView.background = circleBg(Color.rgb(245, 124, 0))
                if (::stopChipBtn.isInitialized) {
                    stopChipBtn.background = roundedBg(Color.rgb(229, 57, 53), dp(7).toFloat(), Color.WHITE)
                }
            }
            OverlayPhase.MOVING -> {
                statusOrbView.text = "转"
                statusOrbView.background = circleBg(Color.rgb(46, 125, 50))
                if (::stopChipBtn.isInitialized) {
                    stopChipBtn.background = roundedBg(Color.rgb(229, 57, 53), dp(7).toFloat(), Color.WHITE)
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
        bgColor: Int,
        minW: Int = 24,
        padH: Int = 5,
        marginEndDp: Int = 2,
        strokeColor: Int? = null,
        onClick: () -> Unit
    ): TextView {
        return TextView(this).apply {
            text = label
            textSize = 10f
            setSingleLine(true)
            gravity = Gravity.CENTER
            minWidth = dp(minW)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(padH), dp(3), dp(padH), dp(3))
            background = roundedBg(bgColor, dp(7).toFloat(), strokeColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(23)
            ).apply {
                marginEnd = dp(marginEndDp)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun circleBg(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dp(1) + 1, Color.argb(235, 255, 255, 255))
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
