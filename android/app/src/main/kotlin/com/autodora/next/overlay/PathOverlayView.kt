package com.autodora.next.overlay

import android.content.Context
import android.graphics.*
import android.view.View
import com.autodora.next.model.*
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Full-screen click-through overlay canvas aligned 1:1 with physical display pixels (including cutout/nav areas).
 * Renders:
 *  - Dynamic Cols x Rows grid (highlights cyan border on 6x8 Weijian auto-expansion)
 *  - Recognized orb color corner badges + enchanted '+' star + golden puzzle borders + low-confidence yellow alert
 *  - High-contrast gradient swipe trajectory with start ring, arrows, diagonal cues, character pull & end ring
 *  - Calibration mode interactive alignment guide
 */
class PathOverlayView(context: Context) : View(context) {

    private var geometry: BoardGeometry? = null
    private var boardState: BoardState? = null
    private var lowConfidenceIndices: Set<Int> = emptySet()
    private var solution: SolutionPath? = null
    private var showPath: Boolean = true
    private var showOrbBadges: Boolean = true
    private var calibrationMode: Boolean = false
    private var countdownText: String? = null

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
        color = Color.argb(220, 255, 215, 40)
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

    fun updateState(
        geometry: BoardGeometry?,
        board: BoardState?,
        lowConfidence: List<Int>,
        solution: SolutionPath?,
        showPath: Boolean,
        showOrbBadges: Boolean,
        calibrationMode: Boolean,
        countdownText: String? = null
    ) {
        this.geometry = geometry
        this.boardState = board
        this.lowConfidenceIndices = lowConfidence.toSet()
        this.solution = solution
        this.showPath = showPath
        this.showOrbBadges = showOrbBadges
        this.calibrationMode = calibrationMode
        this.countdownText = countdownText
        invalidate()
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

        // 1. Calibration or subtle active grid frame
        if (calibrationMode || (showPath && (boardState != null || solution != null))) {
            drawGrid(canvas, g)
        }

        // 2. Recognized orb badges & detected puzzle / low-confidence outlines
        val b = boardState
        if (showPath && b != null && b.cols == g.cols && b.rows == g.rows) {
            drawBoardAnnotations(canvas, g, b)
        }

        // 3. Trajectory overlay
        val sol = solution
        if (showPath && !calibrationMode && sol != null && sol.waypoints.isNotEmpty()) {
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
            // Banner label above grid
            val label = "校准模式 ${g.cols}×${g.rows} (${g.cellWidth.toInt()}px)"
            fillPaint.color = Color.argb(200, 0, 30, 45)
            val bannerY = (g.topPx - 56f).coerceAtLeast(10f)
            canvas.drawRoundRect(g.leftPx + 20f, bannerY, g.rightPx - 20f, bannerY + 46f, 12f, 12f, fillPaint)
            textPaint.textSize = 28f
            canvas.drawText(label, (g.leftPx + g.rightPx) * 0.5f, bannerY + 33f, textPaint)
        }
    }

    private fun drawBoardAnnotations(canvas: Canvas, g: BoardGeometry, b: BoardState) {
        val badgeR = (g.cellWidth * 0.12f).coerceIn(8f, 18f)
        textPaint.textSize = badgeR * 1.45f

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
                    canvas.drawRect(left + 4f, top + 4f, right - 4f, bottom - 4f, puzzlePaint)
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
