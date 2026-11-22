package com.autodora.next.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import com.autodora.next.model.*
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Full-screen overlay canvas aligned 1:1 with physical display pixels (including cutout/nav areas).
 * Renders:
 *  - Dynamic Cols x Rows grid (highlights cyan border on 6x8 Weijian auto-expansion)
 *  - Recognized orb color corner badges + enchanted '+' star + race '族' tag + golden puzzle borders
 *  - Flash-Reveal memorized translucent board overlay (sees straight through '?' Question Orbs!)
 *  - High-contrast gradient swipe trajectory with start ring, arrows, diagonal cues, character pull & end ring
 *  - Interactive direct touch-drag Calibration Mode (drag corners, edges, or pan box directly with finger!)
 */
class PathOverlayView(context: Context) : View(context) {

    enum class DragHandle { NONE, MOVE, LEFT, RIGHT, TOP, BOTTOM, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    var onCalibrationBoundsChanged: ((leftPx: Float, topPx: Float, rightPx: Float, bottomPx: Float, finished: Boolean) -> Unit)? = null
    var onBoardEditorCellTouched: ((row: Int, col: Int) -> Unit)? = null

    private var geometry: BoardGeometry? = null
    private var boardState: BoardState? = null
    private var memorizedOverlayBoard: BoardState? = null
    private var lowConfidenceIndices: Set<Int> = emptySet()
    private var solution: SolutionPath? = null
    private var showPath: Boolean = true
    private var showOrbBadges: Boolean = false
    private var calibrationMode: Boolean = false
    private var boardEditorMode: Boolean = false
    private var countdownText: String? = null
    private var lastPaintedIdx: Int = -1

    // Touch-drag calibration state
    private var activeHandle: DragHandle = DragHandle.NONE
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragInitLeft = 0f
    private var dragInitTop = 0f
    private var dragInitRight = 0f
    private var dragInitBottom = 0f

    private val gridBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val gridLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.argb(110, 255, 255, 255)
    }
    private val puzzlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7f
        color = Color.argb(235, 255, 215, 40)
    }
    private val puzzle2Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7f
        color = Color.argb(235, 64, 224, 255)
    }
    private val lowConfPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.argb(220, 255, 145, 0)
    }
    private val pathShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 14f
        color = Color.argb(170, 0, 0, 0)
    }
    private val pathSegmentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 8.5f
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val countdownPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        textSize = 140f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(12f, 0f, 4f, Color.BLACK)
    }

    fun hasVisibleDrawing(): Boolean {
        if (geometry == null) return false
        return calibrationMode ||
            boardEditorMode ||
            memorizedOverlayBoard != null ||
            countdownText != null ||
            (showPath && (solution != null || boardState != null))
    }

    fun updateState(
        geometry: BoardGeometry?,
        board: BoardState?,
        lowConfidence: List<Int>,
        solution: SolutionPath?,
        showPath: Boolean,
        showOrbBadges: Boolean,
        calibrationMode: Boolean,
        countdownText: String? = null,
        memorizedOverlayBoard: BoardState? = null,
        boardEditorMode: Boolean = false
    ) {
        this.geometry = geometry
        this.boardState = board
        this.memorizedOverlayBoard = memorizedOverlayBoard
        this.lowConfidenceIndices = lowConfidence.toSet()
        this.solution = solution
        this.showPath = showPath
        this.showOrbBadges = showOrbBadges
        this.calibrationMode = calibrationMode
        this.boardEditorMode = boardEditorMode
        this.countdownText = countdownText
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val g = geometry ?: return false
        val x = event.rawX
        val y = event.rawY

        // Interactive Board Editor continuous drag-painting mode (改珠 / 标盾画笔)
        if (boardEditorMode) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    if (x in g.leftPx..g.rightPx && y in g.topPx..g.bottomPx) {
                        val col = ((x - g.leftPx) / g.cellWidth).toInt().coerceIn(0, g.cols - 1)
                        val row = ((y - g.topPx) / g.cellHeight).toInt().coerceIn(0, g.rows - 1)
                        val idx = row * g.cols + col
                        if (event.actionMasked == MotionEvent.ACTION_DOWN || idx != lastPaintedIdx) {
                            lastPaintedIdx = idx
                            onBoardEditorCellTouched?.invoke(row, col)
                        }
                        return true
                    }
                    return false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    lastPaintedIdx = -1
                    return true
                }
            }
            return false
        }

        if (!calibrationMode) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeHandle = hitTestHandle(x, y, g)
                if (activeHandle == DragHandle.NONE) return false
                dragStartX = x
                dragStartY = y
                dragInitLeft = g.leftPx
                dragInitTop = g.topPx
                dragInitRight = g.rightPx
                dragInitBottom = g.bottomPx
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeHandle == DragHandle.NONE) return false
                applyDragDelta(x - dragStartX, y - dragStartY, finished = false)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (activeHandle == DragHandle.NONE) return false
                applyDragDelta(x - dragStartX, y - dragStartY, finished = true)
                activeHandle = DragHandle.NONE
                return true
            }
        }
        return false
    }

    private fun hitTestHandle(x: Float, y: Float, g: BoardGeometry): DragHandle {
        val cornerSlop = 64f
        val edgeSlop = 44f
        if (hypot(x - g.leftPx, y - g.topPx) <= cornerSlop) return DragHandle.TOP_LEFT
        if (hypot(x - g.rightPx, y - g.topPx) <= cornerSlop) return DragHandle.TOP_RIGHT
        if (hypot(x - g.leftPx, y - g.bottomPx) <= cornerSlop) return DragHandle.BOTTOM_LEFT
        if (hypot(x - g.rightPx, y - g.bottomPx) <= cornerSlop) return DragHandle.BOTTOM_RIGHT
        if (y in (g.topPx - edgeSlop)..(g.bottomPx + edgeSlop)) {
            if (abs(x - g.leftPx) <= edgeSlop) return DragHandle.LEFT
            if (abs(x - g.rightPx) <= edgeSlop) return DragHandle.RIGHT
        }
        if (x in (g.leftPx - edgeSlop)..(g.rightPx + edgeSlop)) {
            if (abs(y - g.topPx) <= edgeSlop) return DragHandle.TOP
            if (abs(y - g.bottomPx) <= edgeSlop) return DragHandle.BOTTOM
        }
        if (x in g.leftPx..g.rightPx && y in g.topPx..g.bottomPx) return DragHandle.MOVE
        return DragHandle.NONE
    }

    private fun applyDragDelta(dx: Float, dy: Float, finished: Boolean) {
        val maxW = width.toFloat().coerceAtLeast(300f)
        val maxH = height.toFloat().coerceAtLeast(500f)
        val minBoardW = maxW * 0.45f
        val minBoardH = minBoardW * (5f / 6f) * 0.75f

        var l = dragInitLeft
        var t = dragInitTop
        var r = dragInitRight
        var b = dragInitBottom

        when (activeHandle) {
            DragHandle.MOVE -> {
                val w = r - l
                val h = b - t
                l = (l + dx).coerceIn(0f, maxW - w)
                r = l + w
                b = (b + dy).coerceIn(h, maxH)
                t = b - h
            }
            DragHandle.LEFT -> l = (l + dx).coerceIn(0f, r - minBoardW)
            DragHandle.RIGHT -> r = (r + dx).coerceIn(l + minBoardW, maxW)
            DragHandle.TOP -> t = (t + dy).coerceIn(0f, b - minBoardH)
            DragHandle.BOTTOM -> b = (b + dy).coerceIn(t + minBoardH, maxH)
            DragHandle.TOP_LEFT -> {
                l = (l + dx).coerceIn(0f, r - minBoardW)
                t = (t + dy).coerceIn(0f, b - minBoardH)
            }
            DragHandle.TOP_RIGHT -> {
                r = (r + dx).coerceIn(l + minBoardW, maxW)
                t = (t + dy).coerceIn(0f, b - minBoardH)
            }
            DragHandle.BOTTOM_LEFT -> {
                l = (l + dx).coerceIn(0f, r - minBoardW)
                b = (b + dy).coerceIn(t + minBoardH, maxH)
            }
            DragHandle.BOTTOM_RIGHT -> {
                r = (r + dx).coerceIn(l + minBoardW, maxW)
                b = (b + dy).coerceIn(t + minBoardH, maxH)
            }
            DragHandle.NONE -> return
        }

        geometry = BoardGeometry(
            leftPx = l,
            topPx = t,
            rightPx = r,
            bottomPx = b,
            cols = 6,
            rows = 5,
            isWeijianExpanded = false
        )
        invalidate()
        onCalibrationBoundsChanged?.invoke(l, t, r, b, finished)
    }

    private val screenLoc = IntArray(2)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val g = geometry ?: return

        // Bulletproof 1:1 physical screen coordinate alignment across Android 15 / HyperOS / notch devices:
        // counter-translate if the window manager applied any non-zero origin inset.
        getLocationOnScreen(screenLoc)
        canvas.save()
        if (screenLoc[0] != 0 || screenLoc[1] != 0) {
            canvas.translate(-screenLoc[0].toFloat(), -screenLoc[1].toFloat())
        }

        // 1. Calibration Grid ('校') or Board Editor Grid ('改盘') — NEVER drawn by '线'!
        if (calibrationMode || boardEditorMode) {
            drawGrid(canvas, g)
        }

        // 1.5 Flash-Reveal Memorized Snapshot translucent overlay (透视全版问号珠!)
        val mem = memorizedOverlayBoard
        if (!calibrationMode && !boardEditorMode && mem != null && mem.cols == g.cols && mem.rows == g.rows) {
            drawMemorizedOverlay(canvas, g, mem)
        }

        // 2. Board annotations: full editable orb & hazard badges in Board Editor; only puzzle/low-conf outlines during path preview
        val b = boardState
        if (b != null && b.cols == g.cols && b.rows == g.rows) {
            if (boardEditorMode) {
                drawBoardEditorOverlay(canvas, g, b)
            } else if (showPath && !calibrationMode) {
                drawBoardAnnotations(canvas, g, b)
            }
        }

        // 3. Trajectory overlay ('线' controls swipe path ONLY!)
        val sol = solution
        if (showPath && !calibrationMode && !boardEditorMode && sol != null && sol.waypoints.isNotEmpty()) {
            drawSolutionTrajectory(canvas, g, sol)
        }

        // 4. Optional countdown before swipe
        val cd = countdownText
        if (cd != null) {
            val cx = (g.leftPx + g.rightPx) * 0.5f
            val cy = (g.topPx + g.bottomPx) * 0.5f
            canvas.drawText(cd, cx, cy, countdownPaint)
        }

        canvas.restore()
    }

    private fun drawGrid(canvas: Canvas, g: BoardGeometry) {
        gridBorderPaint.color = when {
            calibrationMode -> Color.argb(245, 0, 235, 255)
            g.isWeijianExpanded -> Color.argb(220, 0, 229, 255)
            else -> Color.argb(140, 255, 255, 255)
        }
        gridBorderPaint.strokeWidth = if (calibrationMode) 6f else 3f
        canvas.drawRect(g.leftPx, g.topPx, g.rightPx, g.bottomPx, gridBorderPaint)

        gridLinePaint.color = if (calibrationMode) Color.argb(190, 0, 235, 255) else Color.argb(75, 255, 255, 255)
        for (c in 1 until g.cols) {
            val x = g.leftPx + c * g.cellWidth
            canvas.drawLine(x, g.topPx, x, g.bottomPx, gridLinePaint)
        }
        for (r in 1 until g.rows) {
            val y = g.topPx + r * g.cellHeight
            canvas.drawLine(g.leftPx, y, g.rightPx, y, gridLinePaint)
        }

        if (calibrationMode) {
            // Draw center crosshairs inside every cell so user can align orbs pixel-perfectly on Xiaomi 15!
            val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(190, 255, 230, 0)
                strokeWidth = 3f
            }
            val arm = g.cellWidth * 0.14f
            for (r in 0 until g.rows) {
                for (c in 0 until g.cols) {
                    val cx = g.cellCenterX(c)
                    val cy = g.cellCenterY(r)
                    canvas.drawLine(cx - arm, cy, cx + arm, cy, crossPaint)
                    canvas.drawLine(cx, cy - arm, cx, cy + arm, crossPaint)
                }
            }
            // Draw draggable corner handles & edge midpoint bars!
            val handleR = 18f
            val corners = arrayOf(
                g.leftPx to g.topPx,
                g.rightPx to g.topPx,
                g.leftPx to g.bottomPx,
                g.rightPx to g.bottomPx
            )
            for ((hx, hy) in corners) {
                fillPaint.color = Color.argb(240, 0, 235, 255)
                canvas.drawCircle(hx, hy, handleR, fillPaint)
                gridBorderPaint.color = Color.WHITE
                gridBorderPaint.strokeWidth = 4f
                canvas.drawCircle(hx, hy, handleR, gridBorderPaint)
            }
            // Banner label above grid
            val label = "拖四角/边线对齐 ${g.cols}×${g.rows} (${g.cellWidth.toInt()}×${g.cellHeight.toInt()}px)"
            fillPaint.color = Color.argb(215, 0, 30, 45)
            val bannerY = (g.topPx - 56f).coerceAtLeast(10f)
            canvas.drawRoundRect(g.leftPx + 12f, bannerY, g.rightPx - 12f, bannerY + 46f, 12f, 12f, fillPaint)
            textPaint.color = Color.WHITE
            textPaint.textSize = 26f
            canvas.drawText(label, (g.leftPx + g.rightPx) * 0.5f, bannerY + 33f, textPaint)
        }
    }

    private fun drawMemorizedOverlay(canvas: Canvas, g: BoardGeometry, mem: BoardState) {
        val orbR = (minOf(g.cellWidth, g.cellHeight) * 0.34f).coerceIn(18f, 64f)
        for (r in 0 until mem.rows) {
            for (c in 0 until mem.cols) {
                val cell = mem.cellAt(r, c)
                if (!cell.element.isMatchable) continue
                val cx = g.cellCenterX(c)
                val cy = g.cellCenterY(r)
                val baseColor = elementColor(cell.element)
                fillPaint.color = Color.argb(135, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
                canvas.drawCircle(cx, cy, orbR, fillPaint)
                gridBorderPaint.color = Color.argb(220, 255, 255, 255)
                gridBorderPaint.strokeWidth = 3f
                canvas.drawCircle(cx, cy, orbR, gridBorderPaint)
                textPaint.color = Color.WHITE
                textPaint.textSize = orbR * 0.92f
                val sym = elementShortZh(cell.element) + if (cell.variant == Variant.ENCHANTED) "+" else ""
                canvas.drawText(sym, cx, cy + orbR * 0.32f, textPaint)
            }
        }
    }

    private fun drawBoardAnnotations(canvas: Canvas, g: BoardGeometry, b: BoardState) {
        val badgeR = (g.cellWidth * 0.12f).coerceIn(8f, 18f)
        textPaint.textSize = badgeR * 1.45f

        fun hasTileFlag(rr: Int, cc: Int, bit: Int): Boolean =
            rr in 0 until b.rows && cc in 0 until b.cols && (b.tileFlags[rr * b.cols + cc] and bit) != 0

        fun drawOuterPerimeterEdges(r: Int, c: Int, left: Float, top: Float, right: Float, bottom: Float, bit: Int, paint: Paint) {
            val inset = 4f
            if (!hasTileFlag(r - 1, c, bit)) canvas.drawLine(left + inset, top + inset, right - inset, top + inset, paint)
            if (!hasTileFlag(r + 1, c, bit)) canvas.drawLine(left + inset, bottom - inset, right - inset, bottom - inset, paint)
            if (!hasTileFlag(r, c - 1, bit)) canvas.drawLine(left + inset, top + inset, left + inset, bottom - inset, paint)
            if (!hasTileFlag(r, c + 1, bit)) canvas.drawLine(right - inset, top + inset, right - inset, bottom - inset, paint)
        }

        puzzlePaint.strokeCap = Paint.Cap.ROUND
        puzzle2Paint.strokeCap = Paint.Cap.ROUND

        for (r in 0 until b.rows) {
            for (c in 0 until b.cols) {
                val idx = r * b.cols + c
                val cell = b.cells[idx]
                val flags = b.tileFlags[idx]
                val left = g.leftPx + c * g.cellWidth
                val top = g.topPx + r * g.cellHeight
                val right = left + g.cellWidth
                val bottom = top + g.cellHeight

                if ((flags and TileFlagBits.PUZZLE_1) != 0) {
                    fillPaint.color = Color.argb(38, 255, 215, 40)
                    canvas.drawRect(left + 3f, top + 3f, right - 3f, bottom - 3f, fillPaint)
                    drawOuterPerimeterEdges(r, c, left, top, right, bottom, TileFlagBits.PUZZLE_1, puzzlePaint)
                }
                if ((flags and TileFlagBits.PUZZLE_2) != 0) {
                    fillPaint.color = Color.argb(36, 64, 224, 255)
                    canvas.drawRect(left + 3f, top + 3f, right - 3f, bottom - 3f, fillPaint)
                    drawOuterPerimeterEdges(r, c, left, top, right, bottom, TileFlagBits.PUZZLE_2, puzzle2Paint)
                }
                if ((flags and TileFlagBits.START_ALLOWED) != 0) {
                    fillPaint.color = Color.argb(42, 0, 230, 118)
                    canvas.drawRoundRect(left + 5f, top + 5f, right - 5f, bottom - 5f, 10f, 10f, fillPaint)
                    gridBorderPaint.color = Color.argb(240, 0, 230, 118)
                    gridBorderPaint.strokeWidth = 6f
                    canvas.drawRoundRect(left + 5f, top + 5f, right - 5f, bottom - 5f, 10f, 10f, gridBorderPaint)
                    val tagH = (g.cellWidth * 0.22f).coerceIn(16f, 28f)
                    val tagW = tagH * 1.45f
                    fillPaint.color = Color.argb(230, 0, 180, 85)
                    canvas.drawRoundRect(right - tagW - 6f, top + 6f, right - 6f, top + tagH + 6f, 6f, 6f, fillPaint)
                    textPaint.color = Color.WHITE
                    textPaint.textSize = tagH * 0.68f
                    canvas.drawText("GO", right - tagW * 0.5f - 6f, top + tagH * 0.78f + 6f, textPaint)
                }
                if ((flags and TileFlagBits.END_REQUIRED) != 0) {
                    fillPaint.color = Color.argb(44, 255, 82, 82)
                    canvas.drawRoundRect(left + 5f, top + 5f, right - 5f, bottom - 5f, 10f, 10f, fillPaint)
                    gridBorderPaint.color = Color.argb(240, 255, 82, 82)
                    gridBorderPaint.strokeWidth = 6f
                    canvas.drawRoundRect(left + 5f, top + 5f, right - 5f, bottom - 5f, 10f, 10f, gridBorderPaint)
                    val tagH = (g.cellWidth * 0.22f).coerceIn(16f, 28f)
                    val tagW = tagH * 1.15f
                    fillPaint.color = Color.argb(230, 229, 57, 53)
                    canvas.drawRoundRect(right - tagW - 6f, top + 6f, right - 6f, top + tagH + 6f, 6f, 6f, fillPaint)
                    textPaint.color = Color.WHITE
                    textPaint.textSize = tagH * 0.68f
                    canvas.drawText("终", right - tagW * 0.5f - 6f, top + tagH * 0.78f + 6f, textPaint)
                }
                if ((flags and TileFlagBits.STORM) != 0) {
                    fillPaint.color = Color.argb(82, 120, 144, 156)
                    canvas.drawRect(left + 4f, top + 4f, right - 4f, bottom - 4f, fillPaint)
                    gridBorderPaint.color = Color.argb(230, 176, 190, 197)
                    gridBorderPaint.strokeWidth = 5f
                    canvas.drawRect(left + 4f, top + 4f, right - 4f, bottom - 4f, gridBorderPaint)
                    val tagR = (g.cellWidth * 0.16f).coerceIn(12f, 24f)
                    textPaint.color = Color.WHITE
                    textPaint.textSize = tagR
                    canvas.drawText("暴风", (left + right) * 0.5f, top + tagR + 8f, textPaint)
                }
                if (idx in lowConfidenceIndices) {
                    canvas.drawRect(left + 7f, top + 7f, right - 7f, bottom - 7f, lowConfPaint)
                }

                if (showOrbBadges) {
                    val bx = left + badgeR + 6f
                    val by = top + badgeR + 6f
                    fillPaint.color = Color.argb(215, 0, 0, 0)
                    canvas.drawCircle(bx, by, badgeR + 2.5f, fillPaint)
                    fillPaint.color = elementColor(cell.element)
                    canvas.drawCircle(bx, by, badgeR, fillPaint)

                    if (cell.variant == Variant.ENCHANTED) {
                        textPaint.color = Color.WHITE
                        canvas.drawText("+", bx, by + badgeR * 0.5f, textPaint)
                    } else if (cell.element == Element.UNKNOWN || cell.element == Element.HIDDEN) {
                        textPaint.color = Color.WHITE
                        canvas.drawText("?", bx, by + badgeR * 0.5f, textPaint)
                    }
                }
            }
        }
    }

    /**
     * Full interactive 1:1 Board Editor overlay: displays orb element pills, enchanted/race markers,
     * and every painted shield/hazard/terrain marker directly over the live game board.
     */
    private fun drawBoardEditorOverlay(canvas: Canvas, g: BoardGeometry, b: BoardState) {
        val orbR = (minOf(g.cellWidth, g.cellHeight) * 0.28f).coerceIn(16f, 48f)
        for (r in 0 until b.rows) {
            for (c in 0 until b.cols) {
                val idx = r * b.cols + c
                val cell = b.cells[idx]
                val flags = b.tileFlags[idx]
                val left = g.leftPx + c * g.cellWidth
                val top = g.topPx + r * g.cellHeight
                val right = left + g.cellWidth
                val bottom = top + g.cellHeight
                val cx = g.cellCenterX(c)
                val cy = g.cellCenterY(r)

                // Terrain & Shield border highlights
                if ((flags and TileFlagBits.PUZZLE_1) != 0) {
                    canvas.drawRect(left + 5f, top + 5f, right - 5f, bottom - 5f, puzzlePaint)
                }
                if ((flags and TileFlagBits.START_ALLOWED) != 0) {
                    gridBorderPaint.color = Color.argb(240, 0, 230, 118)
                    gridBorderPaint.strokeWidth = 6f
                    canvas.drawRect(left + 6f, top + 6f, right - 6f, bottom - 6f, gridBorderPaint)
                }
                if ((flags and TileFlagBits.END_REQUIRED) != 0) {
                    gridBorderPaint.color = Color.argb(240, 255, 82, 82)
                    gridBorderPaint.strokeWidth = 6f
                    canvas.drawRect(left + 6f, top + 6f, right - 6f, bottom - 6f, gridBorderPaint)
                }

                // Translucent element badge at center
                val colr = elementColor(cell.element)
                fillPaint.color = Color.argb(165, Color.red(colr), Color.green(colr), Color.blue(colr))
                canvas.drawCircle(cx, cy, orbR, fillPaint)
                gridBorderPaint.color = Color.argb(230, 255, 255, 255)
                gridBorderPaint.strokeWidth = 3f
                canvas.drawCircle(cx, cy, orbR, gridBorderPaint)

                val suffix = when (cell.variant) {
                    Variant.ENCHANTED -> "+"
                    Variant.RACE -> "族"
                    Variant.WEAKENED -> "-"
                    else -> ""
                }
                textPaint.color = Color.WHITE
                textPaint.textSize = orbR * 0.88f
                canvas.drawText("${elementShortZh(cell.element)}$suffix", cx, cy + orbR * 0.30f, textPaint)

                // Compact bottom hazard/terrain tag
                val tag = buildCellTag(cell.hazards, flags)
                if (tag.isNotEmpty()) {
                    fillPaint.color = Color.argb(215, 20, 20, 26)
                    val tagW = g.cellWidth * 0.84f
                    val tagH = orbR * 0.78f
                    canvas.drawRoundRect(
                        cx - tagW * 0.5f,
                        bottom - tagH - 6f,
                        cx + tagW * 0.5f,
                        bottom - 6f,
                        8f,
                        8f,
                        fillPaint
                    )
                    textPaint.color = Color.rgb(255, 230, 128)
                    textPaint.textSize = tagH * 0.72f
                    canvas.drawText(tag, cx, bottom - tagH * 0.22f - 6f, textPaint)
                }
            }
        }
    }

    private fun buildCellTag(hazards: Int, flags: Int): String {
        val parts = ArrayList<String>(3)
        if ((flags and TileFlagBits.PUZZLE_1) != 0) parts.add("拼")
        if ((flags and TileFlagBits.START_ALLOWED) != 0) parts.add("起")
        if ((flags and TileFlagBits.END_REQUIRED) != 0) parts.add("终")
        if ((flags and TileFlagBits.BURNING_TILE) != 0) parts.add("燃")
        if ((flags and TileFlagBits.SCORCHING_TILE) != 0) parts.add("灼")
        if ((flags and TileFlagBits.BLACK_HOLE) != 0) parts.add("洞")
        if ((flags and TileFlagBits.END_FORBIDDEN) != 0) parts.add("浴")
        if ((flags and TileFlagBits.SHATTERED) != 0) parts.add("碎")
        if ((flags and TileFlagBits.STORM) != 0) parts.add("暴")
        if ((flags and TileFlagBits.CHAR_ORB_PASS) != 0) parts.add("通")
        if ((flags and TileFlagBits.CHAR_ORB_END) != 0) parts.add("停")
        if ((hazards and HazardBits.WEATHERED) != 0) parts.add("风")
        if ((hazards and HazardBits.ELECTRIFIED) != 0) parts.add("电")
        if ((hazards and HazardBits.FROZEN) != 0) parts.add("冻")
        if ((hazards and HazardBits.ICE_SEALED) != 0) parts.add("冰")
        if ((hazards and HazardBits.PETRIFIED) != 0) parts.add("石")
        if ((hazards and HazardBits.COAGULATED) != 0) parts.add("凝")
        if ((hazards and HazardBits.BLOOD_DRAIN) != 0) parts.add("血")
        if ((hazards and HazardBits.BOMB) != 0) parts.add("炸")
        if ((hazards and HazardBits.PURGATORY_FIRE) != 0) parts.add("狱")
        if ((hazards and HazardBits.FOAM) != 0) parts.add("泡")
        if ((hazards and HazardBits.EVIL_ENERGY) != 0) parts.add("邪")
        if ((hazards and HazardBits.BLOOD_THORN) != 0) parts.add("刺")
        if ((hazards and HazardBits.MAGIC_ORB) != 0) parts.add("魔")
        if ((hazards and HazardBits.LUMINOUS) != 0) parts.add("光")
        if ((hazards and HazardBits.PRIORITY) != 0) parts.add("必")
        if ((hazards and HazardBits.RESERVE) != 0) parts.add("留")
        if ((hazards and HazardBits.LOCKED) != 0) parts.add("锁")
        return parts.take(3).joinToString("")
    }

    private fun drawSolutionTrajectory(canvas: Canvas, g: BoardGeometry, sol: SolutionPath) {
        val pts = sol.waypoints.map { PointF(g.cellCenterX(it.col), g.cellCenterY(it.row)) }
        val totalSegs = (pts.size - 1).coerceAtLeast(1)

        // Optional Character Orb vertical pull indicator from portrait bar
        if (sol.usedCharacterOrb) {
            val ax = g.cellCenterX(sol.characterOrbCol)
            val ay = g.characterAvatarCenterY().coerceAtLeast(24f)
            val firstPt = pts.first()
            canvas.drawLine(ax, ay, firstPt.x, firstPt.y, pathShadowPaint)
            pathSegmentPaint.color = Color.rgb(180, 90, 255)
            canvas.drawLine(ax, ay, firstPt.x, firstPt.y, pathSegmentPaint)
            fillPaint.color = Color.rgb(180, 90, 255)
            canvas.drawCircle(ax, ay, 22f, fillPaint)
            textPaint.color = Color.WHITE
            textPaint.textSize = 24f
            canvas.drawText("角", ax, ay + 8f, textPaint)
        }

        // Slight spiral micro-offset when revisiting same edge so overlapping strokes remain legible
        for (i in 0 until pts.lastIndex) {
            val t = i.toFloat() / totalSegs
            val p1 = pts[i]
            val p2 = pts[i + 1]
            // Subtle progressive offset to keep back-and-forth paths visually distinguishable
            val shift = (t - 0.5f) * (g.cellWidth * 0.14f)
            val x1 = p1.x + shift
            val y1 = p1.y + shift
            val x2 = p2.x + shift
            val y2 = p2.y + shift

            canvas.drawLine(x1, y1, x2, y2, pathShadowPaint)
            pathSegmentPaint.color = gradientPathColor(t)
            canvas.drawLine(x1, y1, x2, y2, pathSegmentPaint)

            // Draw directional arrow head at midpoint
            val mx = (x1 + x2) * 0.5f
            val my = (y1 + y2) * 0.5f
            val angle = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
            drawArrowHead(canvas, mx, my, angle, pathSegmentPaint.color)

            // Highlight diagonal moves with bright white diamond at corner crossing
            if (i < sol.moves.size && sol.moves[i].isDiagonal) {
                fillPaint.color = Color.WHITE
                canvas.drawCircle(mx, my, 6.5f, fillPaint)
            }
        }

        // Start marker (vivid green circle with "起")
        val startPt = pts.first()
        val markerR = (g.cellWidth * 0.24f).coerceIn(22f, 38f)
        fillPaint.color = Color.argb(235, 0, 200, 83)
        canvas.drawCircle(startPt.x, startPt.y, markerR, fillPaint)
        gridBorderPaint.color = Color.WHITE
        gridBorderPaint.strokeWidth = 4f
        canvas.drawCircle(startPt.x, startPt.y, markerR, gridBorderPaint)
        textPaint.color = Color.WHITE
        textPaint.textSize = markerR * 0.95f
        canvas.drawText("起", startPt.x, startPt.y + markerR * 0.34f, textPaint)

        // End marker (vivid magenta ring with "终")
        val endPt = pts.last()
        fillPaint.color = Color.argb(225, 233, 30, 99)
        canvas.drawCircle(endPt.x, endPt.y, markerR * 0.85f, fillPaint)
        canvas.drawCircle(endPt.x, endPt.y, markerR * 0.85f, gridBorderPaint)
        textPaint.textSize = markerR * 0.82f
        canvas.drawText("终", endPt.x, endPt.y + markerR * 0.30f, textPaint)
    }

    private fun drawArrowHead(canvas: Canvas, x: Float, y: Float, angleRad: Double, color: Int) {
        val len = 18f
        val wing = 0.52
        val ax1 = (x - len * cos(angleRad - wing)).toFloat()
        val ay1 = (y - len * sin(angleRad - wing)).toFloat()
        val ax2 = (x - len * cos(angleRad + wing)).toFloat()
        val ay2 = (y - len * sin(angleRad + wing)).toFloat()

        val arrowPath = Path().apply {
            moveTo(x, y)
            lineTo(ax1, ay1)
            lineTo(ax2, ay2)
            close()
        }
        fillPaint.color = color
        canvas.drawPath(arrowPath, fillPaint)
    }

    private fun gradientPathColor(t: Float): Int {
        // Smooth high-contrast spectrum: Green (130°) -> Cyan (190°) -> Yellow (50°) -> Magenta (320°)
        val hue = (130f + t * 210f) % 360f
        return Color.HSVToColor(235, floatArrayOf(hue, 0.92f, 1.0f))
    }

    companion object {
        fun elementColor(el: Element): Int = when (el) {
            Element.WATER -> Color.rgb(33, 150, 243)
            Element.FIRE -> Color.rgb(244, 67, 54)
            Element.WOOD -> Color.rgb(76, 175, 80)
            Element.LIGHT -> Color.rgb(255, 214, 0)
            Element.DARK -> Color.rgb(156, 39, 176)
            Element.HEART -> Color.rgb(236, 64, 122)
            Element.HIDDEN, Element.UNKNOWN -> Color.rgb(120, 144, 156)
            Element.EMPTY -> Color.DKGRAY
        }

        fun elementShortZh(el: Element): String = when (el) {
            Element.WATER -> "水"
            Element.FIRE -> "火"
            Element.WOOD -> "木"
            Element.LIGHT -> "光"
            Element.DARK -> "暗"
            Element.HEART -> "心"
            else -> "?"
        }
    }
}
