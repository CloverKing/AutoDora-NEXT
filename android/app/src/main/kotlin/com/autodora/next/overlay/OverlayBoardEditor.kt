package com.autodora.next.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.autodora.next.model.*
import com.autodora.next.ui.OverlayPalette

sealed class BoardBrush(val id: String, val label: String) {
    data class PaintElement(val element: Element) : BoardBrush("el_${element.code}", PathOverlayView.elementShortZh(element))
    data object ToggleEnchanted : BoardBrush("var_ench", "强化+")
    data object ToggleRace : BoardBrush("var_race", "种族")
    data class ToggleTileFlag(val flag: Int, val tag: String) : BoardBrush("flag_$flag", tag)
    data class ToggleHazard(val hazard: Int, val tag: String) : BoardBrush("haz_$hazard", tag)
    data object ClearMarkers : BoardBrush("clear_cell", "清标记")
}

/**
 * Compact System-Themed Brush Palette Bar for the 1:1 Interactive Board Editor (动态改珠 / 标盾画笔).
 * Supports continuous finger-drag painting directly over the real game board via PathOverlayView.
 */
class OverlayBoardEditorController(
    private val context: Context,
    private val onSolveEditedBoard: () -> Unit,
    private val onClearAllMarkers: () -> Unit,
    private val onCloseEditor: () -> Unit
) {
    val rootView: LinearLayout
    var activeBrush: BoardBrush = BoardBrush.PaintElement(Element.WATER)
        private set

    private val brushes: List<BoardBrush> = listOf(
        BoardBrush.PaintElement(Element.WATER),
        BoardBrush.PaintElement(Element.FIRE),
        BoardBrush.PaintElement(Element.WOOD),
        BoardBrush.PaintElement(Element.LIGHT),
        BoardBrush.PaintElement(Element.DARK),
        BoardBrush.PaintElement(Element.HEART),
        BoardBrush.ToggleEnchanted,
        BoardBrush.ToggleRace,
        BoardBrush.ToggleTileFlag(TileFlagBits.PUZZLE_1, "拼图"),
        BoardBrush.ToggleTileFlag(TileFlagBits.START_ALLOWED, "起手绿"),
        BoardBrush.ToggleTileFlag(TileFlagBits.END_REQUIRED, "停手红"),
        BoardBrush.ToggleHazard(HazardBits.WEATHERED, "风化"),
        BoardBrush.ToggleHazard(HazardBits.ELECTRIFIED, "电击"),
        BoardBrush.ToggleHazard(HazardBits.ICE_SEALED, "冰封"),
        BoardBrush.ToggleHazard(HazardBits.PETRIFIED, "石化"),
        BoardBrush.ToggleTileFlag(TileFlagBits.BURNING_TILE, "燃烧"),
        BoardBrush.ToggleTileFlag(TileFlagBits.SCORCHING_TILE, "灼热"),
        BoardBrush.ToggleTileFlag(TileFlagBits.BLACK_HOLE, "黑洞"),
        BoardBrush.ToggleTileFlag(TileFlagBits.END_FORBIDDEN, "血浴"),
        BoardBrush.ToggleTileFlag(TileFlagBits.SHATTERED, "碎裂"),
        BoardBrush.ToggleTileFlag(TileFlagBits.STORM, "暴风"),
        BoardBrush.ToggleTileFlag(TileFlagBits.CHAR_ORB_PASS, "角通"),
        BoardBrush.ToggleTileFlag(TileFlagBits.CHAR_ORB_END, "角停"),
        BoardBrush.ToggleHazard(HazardBits.PURGATORY_FIRE, "炼狱"),
        BoardBrush.ClearMarkers
    )

    private val brushButtons = LinkedHashMap<String, TextView>()
    private val titleLabel: TextView
    private val solveEditedBtn: TextView
    private val clearAllBtn: TextView
    private val exitBtn: TextView
    private var currentPalette: OverlayPalette? = null

    init {
        rootView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            elevation = dp(3).toFloat()
            setPadding(dp(6), dp(4), dp(6), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(3)
                marginStart = dp(34)
            }
        }

        // Top bar: hint + Solve Edited Board + Clear All Markers + Done
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        titleLabel = TextView(context).apply {
            text = "盘面编绘（点击或划过格子涂抹）"
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            setSingleLine(true)
            setPadding(0, 0, dp(6), 0)
        }
        header.addView(titleLabel)

        solveEditedBtn = makeBtn("算此盘") { onSolveEditedBoard() }
        clearAllBtn = makeBtn("清全标") { onClearAllMarkers() }
        exitBtn = makeBtn("完成", marginEndDp = 0) { onCloseEditor() }
        header.addView(solveEditedBtn)
        header.addView(clearAllBtn)
        header.addView(exitBtn)
        rootView.addView(header)

        // Scrollable horizontal brush palette
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                dp(310),
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
            }
        }
        val brushRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        for (b in brushes) {
            val btn = makeBtn(b.label) {
                activeBrush = b
                refreshBrushStyles()
            }
            brushButtons[b.id] = btn
            brushRow.addView(btn)
        }
        scroll.addView(brushRow)
        rootView.addView(scroll)
    }

    fun applyTheme(palette: OverlayPalette) {
        currentPalette = palette
        rootView.background = rounded(palette.containerBg, dp(12).toFloat(), palette.tertiary)
        titleLabel.setTextColor(palette.onSurface)
        styleBtn(solveEditedBtn, palette.primary, palette.onPrimary, null)
        styleBtn(clearAllBtn, palette.secondaryContainer, palette.onSecondaryContainer, null)
        styleBtn(exitBtn, palette.chipOffBg, palette.onSurfaceVariant, palette.chipOffStroke)
        refreshBrushStyles()
    }

    private fun refreshBrushStyles() {
        val p = currentPalette ?: return
        for (b in brushes) {
            val btn = brushButtons[b.id] ?: continue
            val selected = (b.id == activeBrush.id)
            if (b is BoardBrush.PaintElement) {
                val elemCol = PathOverlayView.elementColor(b.element)
                btn.text = if (selected) "●${b.label}" else b.label
                btn.setTextColor(Color.WHITE)
                btn.background = rounded(
                    elemCol,
                    dp(8).toFloat(),
                    if (selected) Color.WHITE else Color.argb(90, 255, 255, 255),
                    strokePx = if (selected) dp(2) else dp(1)
                )
            } else {
                btn.text = if (selected) "●${b.label}" else b.label
                if (selected) {
                    styleBtn(btn, p.tertiary, p.onTertiary, p.onTertiary)
                } else {
                    styleBtn(btn, p.chipOffBg, p.chipOffText, p.chipOffStroke)
                }
            }
        }
    }

    fun applyActiveBrush(board: BoardState, row: Int, col: Int): BoardState {
        val idx = row * board.cols + col
        if (idx !in board.cells.indices) return board
        val newCells = board.cells.toMutableList()
        val newFlags = board.tileFlags.toMutableList()
        val oldCell = newCells[idx]
        val oldFlags = newFlags[idx]

        when (val b = activeBrush) {
            is BoardBrush.PaintElement -> {
                newCells[idx] = oldCell.copy(
                    element = b.element,
                    hazards = oldCell.hazards and HazardBits.QUESTION.inv(),
                    confidence = 1.0f
                )
            }
            BoardBrush.ToggleEnchanted -> {
                val nextVar = if (oldCell.variant == Variant.ENCHANTED) Variant.NORMAL else Variant.ENCHANTED
                newCells[idx] = oldCell.copy(variant = nextVar)
            }
            BoardBrush.ToggleRace -> {
                val nextVar = if (oldCell.variant == Variant.RACE) Variant.NORMAL else Variant.RACE
                newCells[idx] = oldCell.copy(variant = nextVar)
            }
            is BoardBrush.ToggleTileFlag -> {
                newFlags[idx] = oldFlags xor b.flag
            }
            is BoardBrush.ToggleHazard -> {
                newCells[idx] = oldCell.copy(hazards = oldCell.hazards xor b.hazard)
            }
            BoardBrush.ClearMarkers -> {
                newCells[idx] = oldCell.copy(variant = Variant.NORMAL, hazards = HazardBits.NONE)
                newFlags[idx] = TileFlagBits.NONE
            }
        }
        return board.copy(cells = newCells, tileFlags = newFlags)
    }

    private fun makeBtn(label: String, marginEndDp: Int = 3, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            setSingleLine(true)
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(3), dp(6), dp(3))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(24)
            ).apply { marginEnd = dp(marginEndDp) }
            setOnClickListener { onClick() }
        }
    }

    private fun styleBtn(v: TextView, bg: Int, fg: Int, stroke: Int?) {
        v.setTextColor(fg)
        v.background = rounded(bg, dp(8).toFloat(), stroke)
    }

    private fun rounded(bg: Int, radius: Float, stroke: Int?, strokePx: Int = dp(1)) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(bg)
        if (stroke != null) setStroke(strokePx, stroke)
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}
