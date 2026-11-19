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
import com.autodora.next.touch.TouchExecutionResult
import com.autodora.next.touch.TouchExecutor
import com.autodora.next.vision.BoardRecognizer
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
    private lateinit var controlRoot: LinearLayout
    private lateinit var controlParams: WindowManager.LayoutParams
    private lateinit var statusOrbView: TextView
    private lateinit var toolbarContainer: LinearLayout
    private lateinit var calibrationBar: LinearLayout
    private lateinit var summaryStrip: LinearLayout
    private lateinit var summaryTitle: TextView
    private lateinit var groupsRow: LinearLayout
    private lateinit var pagerText: TextView
    private lateinit var pathToggleBtn: TextView
    private lateinit var executeBtn: TextView
    private lateinit var emergencyBtn: TextView
    private lateinit var emergencyParams: WindowManager.LayoutParams

    // Turn state
    private var currentPhase = OverlayPhase.IDLE
    private var toolbarExpanded = true
    private var showPath = true
    private var calibrationMode = false
    private var currentGeometry: BoardGeometry? = null
    private var lastRecognition: RecognitionResult? = null
    private var lastSolveOutput: SolveOutput? = null
    private var selectedSolutionIdx = 0
    private var activeJob: Job? = null
    private var activeCancelToken: NativeCancelToken? = null

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
                emergencyBtn.visibility = if (latest.showEmergencyStop) View.VISIBLE else View.GONE
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
        // 1. Full-screen click-through PathOverlayView (exact 1:1 coordinate alignment with MediaProjection)
        pathOverlayView = PathOverlayView(this)
        val pathParams = WindowManager.LayoutParams(
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
        }
        windowManager.addView(pathOverlayView, pathParams)

        // 2. Draggable 3-state control pill + compact toolbar + calibration bar + combo summary
        controlRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        statusOrbView = TextView(this).apply {
            text = "D"
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            val size = dp(42)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = dp(6)
            }
            background = circleBg(Color.rgb(25, 118, 210))
        }
        topRow.addView(statusOrbView)

        toolbarContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = roundedBg(Color.argb(225, 22, 26, 36), dp(14).toFloat(), Color.argb(120, 100, 180, 255))
        }

        toolbarContainer.addView(makeChipBtn("识图算路", Color.rgb(0, 150, 136)) { startCaptureAndSolve() })
        toolbarContainer.addView(makeChipBtn("◀", Color.rgb(55, 65, 81)) { switchSolution(-1) })
        pagerText = TextView(this).apply {
            text = "0/0"
            textSize = 11f
            setTextColor(Color.rgb(200, 220, 255))
            setPadding(dp(3), 0, dp(3), 0)
        }
        toolbarContainer.addView(pagerText)
        toolbarContainer.addView(makeChipBtn("▶", Color.rgb(55, 65, 81)) { switchSolution(+1) })

        executeBtn = makeChipBtn("执行", Color.rgb(46, 125, 50)) { executeSelectedSolution() }
        toolbarContainer.addView(executeBtn)

        pathToggleBtn = makeChipBtn("画线", Color.rgb(63, 81, 181)) {
            showPath = !showPath
            pathToggleBtn.alpha = if (showPath) 1.0f else 0.45f
            refreshPathCanvas()
        }
        toolbarContainer.addView(pathToggleBtn)

        toolbarContainer.addView(makeChipBtn("校准", Color.rgb(121, 85, 72)) { toggleCalibrationMode() })
        toolbarContainer.addView(makeChipBtn("✕", Color.rgb(160, 45, 45)) { stopSelf() })

        topRow.addView(toolbarContainer)
        controlRoot.addView(topRow)

        // Live Combo & Elimination Summary Strip
        summaryStrip = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
            background = roundedBg(Color.argb(220, 16, 20, 30), dp(10).toFloat(), Color.argb(90, 255, 255, 255))
        }
        summaryTitle = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        summaryStrip.addView(summaryTitle)

        val groupsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        groupsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, 0)
        }
        groupsScroll.addView(groupsRow)
        summaryStrip.addView(groupsScroll)
        controlRoot.addView(summaryStrip)

        // In-place Calibration Bar (inherited from AutoDora v3.4.3 correctionview!)
        calibrationBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            setPadding(dp(6), dp(5), dp(6), dp(5))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
            background = roundedBg(Color.argb(235, 18, 40, 56), dp(10).toFloat(), Color.rgb(0, 229, 255))
        }
        calibrationBar.addView(makeChipBtn("上移", Color.rgb(0, 120, 150)) { nudgeCalib(bottomDelta = -0.005f) })
        calibrationBar.addView(makeChipBtn("下移", Color.rgb(0, 120, 150)) { nudgeCalib(bottomDelta = +0.005f) })
        calibrationBar.addView(makeChipBtn("窄-", Color.rgb(0, 100, 130)) { nudgeCalib(sideInsetDelta = +0.004f) })
        calibrationBar.addView(makeChipBtn("宽+", Color.rgb(0, 100, 130)) { nudgeCalib(sideInsetDelta = -0.004f) })
        calibrationBar.addView(makeChipBtn("高-", Color.rgb(0, 90, 110)) { nudgeCalib(heightScaleDelta = -0.01f) })
        calibrationBar.addView(makeChipBtn("高+", Color.rgb(0, 90, 110)) { nudgeCalib(heightScaleDelta = +0.01f) })
        calibrationBar.addView(makeChipBtn("自动", Color.rgb(46, 125, 50)) { runAutoCalibration() })
        calibrationBar.addView(makeChipBtn("重置", Color.rgb(160, 60, 50)) { resetCalibration() })
        controlRoot.addView(calibrationBar)

        controlParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(48)
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
                        controlParams.x = (initX + dx).coerceIn(0, screenW - dp(50))
                        controlParams.y = (initY + dy).coerceIn(0, screenH - dp(80))
                        windowManager.updateViewLayout(controlRoot, controlParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        toolbarExpanded = !toolbarExpanded
                        toolbarContainer.visibility = if (toolbarExpanded) View.VISIBLE else View.GONE
                        if (!toolbarExpanded) {
                            calibrationBar.visibility = View.GONE
                            summaryStrip.visibility = View.GONE
                        } else if (lastSolveOutput != null) {
                            summaryStrip.visibility = View.VISIBLE
                        }
                    }
                    true
                }
                else -> false
            }
        }
        windowManager.addView(controlRoot, controlParams)

        // 3. Independent Red Emergency Stop Button (always crisp and immediately tappable during auto-swipe!)
        emergencyBtn = TextView(this).apply {
            text = "急停"
            textSize = 12f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            val size = dp(44)
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = circleBg(Color.argb(235, 211, 47, 47))
            setOnClickListener {
                triggerEmergencyStop()
                toast("已急停中止！")
            }
        }
        emergencyParams = WindowManager.LayoutParams(
            dp(44),
            dp(44),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(10)
            y = dp(48)
        }
        windowManager.addView(emergencyBtn, emergencyParams)
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
            calibrationMode = false
            calibrationBar.visibility = View.GONE
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
                // Stage 1/2 Dynamic Grid Size Auto-Detection (6x5 <-> 6x8 Weijian / Arbitrary!)
                val geom = withContext(Dispatchers.Default) {
                    AutoGridDetector.detect(frames.first(), settings.calibration)
                }
                currentGeometry = geom

                // Multi-frame CV / Hybrid Recognition
                val rec = boardRecognizer.recognize(frames, geom)
                lastRecognition = rec

                // Compose RuleConfig (auto-enables Puzzle shield if golden puzzle borders were detected!)
                val ruleConfig = RuleComposer.compose(
                    base = TeamBasePreset("active", "Active", baseConfig = settings.toRuleConfig()),
                    activeChips = emptyList(),
                    board = rec.board
                )

                // C++20 JNI Solve with cooperative cancellation token
                val token = NativeSolver.createCancelToken()
                activeCancelToken = token
                val solveOut = try {
                    NativeSolver.solve(rec.board, ruleConfig, token)
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

            // Make control bar temporarily non-touchable during swipe so it can NEVER block touches
            setControlBarTouchable(false)
            try {
                delay(35)
                val result = touchExecutor.executePath(sol, geom, settings.toSwipeConfig())
                when (result) {
                    is TouchExecutionResult.Success -> {
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
        calibrationMode = !calibrationMode
        calibrationBar.visibility = if (calibrationMode) View.VISIBLE else View.GONE
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
                s.copy(
                    calibration = c.copy(
                        leftRatio = (c.leftRatio + sideInsetDelta).coerceIn(0f, 0.25f),
                        rightRatio = (c.rightRatio - sideInsetDelta).coerceIn(0.75f, 1f),
                        bottomRatio = (c.bottomRatio + bottomDelta).coerceIn(0.50f, 1f),
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
            val bmp = capturer.captureSingleFrame() ?: return@launch
            try {
                val detected = withContext(Dispatchers.Default) {
                    AutoGridDetector.detectArbitraryGrid(com.autodora.next.capture.BitmapPixelFrame(bmp))
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
                    toast("自动检测到 ${detected.cols}×${detected.rows} 棋盘！")
                } else {
                    toast("未检测到明显棋盘，请进入神魔战斗盘面后重试或手动微调")
                }
            } finally {
                if (!bmp.isRecycled) bmp.recycle()
            }
        }
    }

    private fun resetCalibration() {
        serviceScope.launch {
            settingsRepository.update { it.copy(calibration = BoardCalibration()) }
            toast("已重置为默认 6×5 比例")
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
        val sizeBadge = if (geom.isWeijianExpanded) "${geom.cols}×${geom.rows} 违建" else "${geom.cols}×${geom.rows}"
        val shieldBadge = if (sol.allHardConstraintsMet) "✓" else "!"
        val lowWarn = lastRecognition?.lowConfidenceIndices?.size ?: 0
        val lowStr = if (lowWarn > 0) " · 疑珠$lowWarn" else ""

        summaryTitle.text = "[$sizeBadge] ${sol.totalCombo}C(首${sol.firstWaveCombo}C) · ${sol.stepCount}步 · ${out.elapsedMs.toInt()}ms $shieldBadge ${sol.tag}$lowStr"

        groupsRow.removeAllViews()
        for (grp in sol.groups) {
            val pill = TextView(this).apply {
                val ench = if (grp.hasEnchanted) "+" else ""
                val waveMark = if (grp.wave > 1) "↓" else ""
                text = "${PathOverlayView.elementShortZh(grp.element)}${grp.orbCount}$ench$waveMark"
                textSize = 11f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(6), dp(2), dp(6), dp(2))
                background = roundedBg(PathOverlayView.elementColor(grp.element), dp(6).toFloat(), Color.argb(120, 255, 255, 255))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(4) }
            }
            groupsRow.addView(pill)
        }
        summaryStrip.visibility = if (toolbarExpanded) View.VISIBLE else View.GONE
    }

    private fun refreshPathCanvas(countdown: String? = null) {
        pathOverlayView.updateState(
            geometry = currentGeometry,
            board = lastRecognition?.board,
            lowConfidence = lastRecognition?.lowConfidenceIndices ?: emptyList(),
            solution = currentSolution(),
            showPath = showPath,
            showOrbBadges = settings.showOrbBadgesOnPath,
            calibrationMode = calibrationMode,
            countdownText = countdown
        )
    }

    private fun setPhase(phase: OverlayPhase) {
        currentPhase = phase
        when (phase) {
            OverlayPhase.IDLE -> {
                statusOrbView.text = "D"
                statusOrbView.background = circleBg(Color.rgb(25, 118, 210))
            }
            OverlayPhase.CALCULATING -> {
                statusOrbView.text = "算"
                statusOrbView.background = circleBg(Color.rgb(245, 124, 0))
            }
            OverlayPhase.MOVING -> {
                statusOrbView.text = "转"
                statusOrbView.background = circleBg(Color.rgb(46, 125, 50))
            }
        }
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
        listOf(pathOverlayView, controlRoot, emergencyBtn).forEach { v ->
            try {
                windowManager.removeView(v)
            } catch (_: Exception) {
            }
        }
    }

    private fun makeChipBtn(label: String, bgColor: Int, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 11.5f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(7), dp(5), dp(7), dp(5))
            background = roundedBg(bgColor, dp(8).toFloat(), null)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(4)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun circleBg(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dp(2), Color.WHITE)
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
