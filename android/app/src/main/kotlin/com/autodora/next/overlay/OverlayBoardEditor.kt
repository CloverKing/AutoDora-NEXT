package com.autodora.next.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.autodora.next.R
import com.autodora.next.model.*
import com.autodora.next.ui.OverlayPalette

sealed class BoardBrush(val id: String, val label: String) {
    data class PaintElement(val element: Element) : BoardBrush("el_${element.code}", PathOverlayView.elementShortZh(element))
    data object ToggleEnchanted : BoardBrush("var_ench", "强化+")
    data object ToggleRace : BoardBrush("var_race", "种族")
    data object ToggleWeakened : BoardBrush("var_weak", "弱化-")
    data class ToggleTileFlag(val flag: Int, val tag: String) : BoardBrush("flag_$flag", tag)
    data class ToggleHazard(val hazard: Int, val tag: String) : BoardBrush("haz_$hazard", tag)
    data object ClearMarkers : BoardBrush("clear_cell", "清格标")
}

private data class BrushGroupSpec(
    val titleRes: Int,
    val accentColor: Int,
    val rows: List<List<BoardBrush>>
)

/**
 * Grouped, Multi-Row Wrapped Brush Palette Panel for the 1:1 Interactive Board Editor (盘面编排).
 *
 * Workflow:
 *  1. Tap/drag directly over the game board below using color-coded grouped brushes.
 *  2. Tap 「保存版面」 to save the edited board (then use the main floating toolbar's Solve/Play buttons),
 *     or tap 「清除标记」 to strip all markers and revert to live screen capture.
 */
class OverlayBoardEditorController(
    private val context: Context,
    private val onSaveEditedBoard: () -> Unit,
    private val onClearAllMarkers: () -> Unit,
    private val onCloseEditor: () -> Unit
) {
    val rootView: LinearLayout
    var activeBrush: BoardBrush = BoardBrush.PaintElement(Element.WATER)
        private set

    private var isSavedState: Boolean = false
    private var lctx: Context = context
    private var currentPalette: OverlayPalette? = null

    private val brushGroups: List<BrushGroupSpec> = listOf(
        BrushGroupSpec(
            titleRes = R.string.editor_grp_orb,
            accentColor = ACCENT_INDIGO,
            rows = listOf(
                listOf(
                    BoardBrush.PaintElement(Element.WATER),
                    BoardBrush.PaintElement(Element.FIRE),
                    BoardBrush.PaintElement(Element.WOOD),
                    BoardBrush.PaintElement(Element.LIGHT),
                    BoardBrush.PaintElement(Element.DARK),
                    BoardBrush.PaintElement(Element.HEART)
                ),
                listOf(
                    BoardBrush.ToggleEnchanted,
                    BoardBrush.ToggleRace,
                    BoardBrush.ToggleWeakened,
                    BoardBrush.ClearMarkers
                )
            )
        ),
        BrushGroupSpec(
            titleRes = R.string.editor_grp_shape,
            accentColor = ACCENT_AMBER,
            rows = listOf(
                listOf(
                    BoardBrush.ToggleTileFlag(TileFlagBits.PUZZLE_1, "拼图"),
                    BoardBrush.ToggleTileFlag(TileFlagBits.START_ALLOWED, "起手绿"),
                    BoardBrush.ToggleTileFlag(TileFlagBits.END_REQUIRED, "停手红"),
                    BoardBrush.ToggleTileFlag(TileFlagBits.CHAR_ORB_PASS, "角通"),
                    BoardBrush.ToggleTileFlag(TileFlagBits.CHAR_ORB_END, "角停")
                )
            )
        ),
        BrushGroupSpec(
            titleRes = R.string.editor_grp_status,
            accentColor = ACCENT_TEAL,
            rows = listOf(
                listOf(
                    BoardBrush.ToggleHazard(HazardBits.WEATHERED, "风化"),
                    BoardBrush.ToggleHazard(HazardBits.ELECTRIFIED, "电击"),
                    BoardBrush.ToggleHazard(HazardBits.FROZEN, "冻结"),
                    BoardBrush.ToggleHazard(HazardBits.ICE_SEALED, "冰封"),
                    BoardBrush.ToggleHazard(HazardBits.PETRIFIED, "石化"),
                    BoardBrush.ToggleHazard(HazardBits.COAGULATED, "凝结")
                ),
                listOf(
                    BoardBrush.ToggleHazard(HazardBits.BLOOD_DRAIN, "化血"),
                    BoardBrush.ToggleHazard(HazardBits.BOMB, "炸弹"),
                    BoardBrush.ToggleHazard(HazardBits.FOAM, "泡沫"),
                    BoardBrush.ToggleHazard(HazardBits.EVIL_ENERGY, "邪能"),
                    BoardBrush.ToggleHazard(HazardBits.BLOOD_THORN, "刺血"),
                    BoardBrush.ToggleHazard(HazardBits.MAGIC_ORB, "魔力")
                ),
                listOf(
                    BoardBrush.ToggleHazard(HazardBits.LUMINOUS, "流光"),
                    BoardBrush.ToggleHazard(HazardBits.PURGATORY_FIRE, "炼狱"),
                    BoardBrush.ToggleHazard(HazardBits.PRIORITY, "印记必消"),
                    BoardBrush.ToggleHazard(HazardBits.RESERVE, "保留不消")
                )
            )
        ),
        BrushGroupSpec(
            titleRes = R.string.editor_grp_terrain,
            accentColor = ACCENT_CRIMSON,
            rows = listOf(
                listOf(
                    BoardBrush.ToggleTileFlag(TileFlagBits.BURNING_TILE, "燃烧"),
                    BoardBrush.ToggleTileFlag(TileFlagBits.SCORCHING_TILE, "灼热"),
                    BoardBrush.ToggleTileFlag(TileFlagBits.BLACK_HOLE, "黑洞"),
                    BoardBrush.ToggleTileFlag(TileFlagBits.END_FORBIDDEN, "血浴"),
                    BoardBrush.ToggleTileFlag(TileFlagBits.SHATTERED, "碎裂"),
                    BoardBrush.ToggleTileFlag(TileFlagBits.STORM, "暴风")
                )
            )
        )
    )

    private val allBrushes: List<Pair<BoardBrush, Int>> = brushGroups.flatMap { grp ->
        grp.rows.flatten().map { brush -> brush to grp.accentColor }
    }

    private val brushButtons = LinkedHashMap<String, TextView>()
    private val groupTitleViews = ArrayList<Pair<TextView, BrushGroupSpec>>()
    private val groupContainers = ArrayList<Pair<LinearLayout, BrushGroupSpec>>()
    private val titleLabel: TextView
    private val statusBadge: TextView
    private val hintLabel: TextView
    private val saveBoardBtn: TextView
    private val clearAllBtn: TextView
    private val exitBtn: TextView

    init {
        val dm = context.resources.displayMetrics
        val panelWidth = minOf(dm.widthPixels - dp(16), dp(356))
        val maxScrollH = (dm.heightPixels * 0.37f).toInt().coerceIn(dp(230), dp(320))

        rootView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            elevation = dp(4).toFloat()
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                panelWidth,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
                marginStart = dp(4)
            }
        }

        // Top action bar: Title + State Badge + [保存版面] + [清除标记] + [✕]
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        titleLabel = TextView(context).apply {
            text = context.getString(R.string.editor_title)
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setSingleLine(true)
            setPadding(0, 0, dp(5), 0)
        }
        header.addView(titleLabel)

        statusBadge = TextView(context).apply {
            text = context.getString(R.string.editor_badge_editing)
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            setSingleLine(true)
            setPadding(dp(5), dp(1), dp(5), dp(1))
        }
        header.addView(statusBadge)

        header.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f)
        })

        saveBoardBtn = makeHeaderBtn(context.getString(R.string.editor_btn_save)) { onSaveEditedBoard() }
        clearAllBtn = makeHeaderBtn(context.getString(R.string.editor_btn_clear)) { onClearAllMarkers() }
        exitBtn = makeHeaderBtn("✕", marginEndDp = 0, padH = 7) { onCloseEditor() }
        header.addView(saveBoardBtn)
        header.addView(clearAllBtn)
        header.addView(exitBtn)
        rootView.addView(header)

        // Live workflow hint line
        hintLabel = TextView(context).apply {
            text = context.getString(R.string.editor_hint_unsaved)
            textSize = 9f
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(2)
                bottomMargin = dp(5)
            }
        }
        rootView.addView(hintLabel)

        // Bounded vertical ScrollView containing 4 clean color-coded multi-row brush groups
        val scroll = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val boundedH = MeasureSpec.makeMeasureSpec(maxScrollH, MeasureSpec.AT_MOST)
                super.onMeasure(widthMeasureSpec, boundedH)
            }
        }.apply {
            isVerticalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val groupsHost = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        brushGroups.forEachIndexed { gIdx, group ->
            val groupBox = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(6), dp(4), dp(6), dp(5))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = if (gIdx == brushGroups.lastIndex) 0 else dp(5)
                }
            }

            // Group title with vertical colored bar
            val gHdr = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(3) }
            }
            gHdr.addView(View(context).apply {
                background = rounded(group.accentColor, dp(2).toFloat(), null)
                layoutParams = LinearLayout.LayoutParams(dp(3), dp(10)).apply { marginEnd = dp(5) }
            })
            val gTitle = TextView(context).apply {
                text = context.getString(group.titleRes)
                textSize = 9.5f
                typeface = Typeface.DEFAULT_BOLD
                setSingleLine(true)
            }
            gHdr.addView(gTitle)
            groupBox.addView(gHdr)

            groupTitleViews.add(gTitle to group)
            groupContainers.add(groupBox to group)

            // Wrapped multi-row equal-width grid rows
            group.rows.forEachIndexed { rIdx, rowBrushes ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = if (rIdx == group.rows.lastIndex) 0 else dp(3)
                    }
                }
                rowBrushes.forEachIndexed { bIdx, brush ->
                    val btn = makeGridBrushBtn(
                        label = brush.label,
                        marginEndDp = if (bIdx == rowBrushes.lastIndex) 0 else 3
                    ) {
                        activeBrush = brush
                        refreshBrushStyles()
                    }
                    brushButtons[brush.id] = btn
                    row.addView(btn)
                }
                groupBox.addView(row)
            }
            groupsHost.addView(groupBox)
        }

        scroll.addView(groupsHost)
        rootView.addView(scroll)
    }

    fun applyTheme(palette: OverlayPalette, localizedContext: Context = context) {
        currentPalette = palette
        lctx = localizedContext
        val opaqueBg = Color.argb(
            248,
            Color.red(palette.containerBg),
            Color.green(palette.containerBg),
            Color.blue(palette.containerBg)
        )
        rootView.background = rounded(opaqueBg, dp(12).toFloat(), palette.containerStroke)
        titleLabel.text = lctx.getString(R.string.editor_title)
        titleLabel.setTextColor(palette.onSurface)
        hintLabel.setTextColor(palette.onSurfaceVariant)
        clearAllBtn.text = lctx.getString(R.string.editor_btn_clear)
        styleBtn(clearAllBtn, palette.errorContainer, palette.onErrorContainer, null)
        styleBtn(exitBtn, palette.chipOffBg, palette.onSurfaceVariant, palette.chipOffStroke)

        for ((titleView, spec) in groupTitleViews) {
            titleView.text = lctx.getString(spec.titleRes)
            titleView.setTextColor(spec.accentColor)
        }
        for ((box, spec) in groupContainers) {
            val tintBg = Color.argb(
                if (palette.isDark) 26 else 14,
                Color.red(spec.accentColor),
                Color.green(spec.accentColor),
                Color.blue(spec.accentColor)
            )
            val tintStroke = Color.argb(
                if (palette.isDark) 96 else 72,
                Color.red(spec.accentColor),
                Color.green(spec.accentColor),
                Color.blue(spec.accentColor)
            )
            box.background = rounded(tintBg, dp(8).toFloat(), tintStroke)
        }

        refreshSaveStateChrome()
        refreshBrushStyles()
    }

    fun setSavedState(saved: Boolean) {
        isSavedState = saved
        refreshSaveStateChrome()
    }

    fun markDirty() {
        if (isSavedState) {
            isSavedState = false
            refreshSaveStateChrome()
        }
    }

    private fun refreshSaveStateChrome() {
        val p = currentPalette ?: return
        if (isSavedState) {
            statusBadge.text = "✓ ${lctx.getString(R.string.editor_badge_saved)}"
            val savedBg = if (p.isDark) Color.rgb(22, 62, 42) else Color.rgb(232, 245, 233)
            val savedFg = if (p.isDark) Color.rgb(129, 236, 172) else Color.rgb(27, 94, 32)
            val savedStroke = Color.rgb(46, 125, 50)
            styleBtn(statusBadge, savedBg, savedFg, savedStroke)
            saveBoardBtn.text = "✓ ${lctx.getString(R.string.editor_btn_save)}"
            styleBtn(saveBoardBtn, p.primaryContainer, p.onPrimaryContainer, p.primary)
            hintLabel.text = lctx.getString(R.string.editor_hint_saved)
        } else {
            statusBadge.text = lctx.getString(R.string.editor_badge_editing)
            val editBg = if (p.isDark) Color.rgb(66, 48, 16) else Color.rgb(255, 243, 224)
            val editFg = if (p.isDark) Color.rgb(255, 213, 79) else Color.rgb(230, 81, 0)
            val editStroke = Color.rgb(230, 118, 0)
            styleBtn(statusBadge, editBg, editFg, editStroke)
            saveBoardBtn.text = lctx.getString(R.string.editor_btn_save)
            styleBtn(saveBoardBtn, p.primary, p.onPrimary, null)
            hintLabel.text = lctx.getString(R.string.editor_hint_unsaved)
        }
    }

    private fun refreshBrushStyles() {
        val p = currentPalette ?: return
        for ((b, accent) in allBrushes) {
            val btn = brushButtons[b.id] ?: continue
            val selected = (b.id == activeBrush.id)
            if (b is BoardBrush.PaintElement) {
                val elemCol = PathOverlayView.elementColor(b.element)
                btn.text = if (selected) "●${b.label}" else b.label
                btn.setTextColor(Color.WHITE)
                btn.background = rounded(
                    elemCol,
                    dp(7).toFloat(),
                    if (selected) Color.WHITE else Color.argb(95, 255, 255, 255),
                    strokePx = if (selected) dp(2) else dp(1)
                )
            } else {
                btn.text = if (selected) "●${b.label}" else b.label
                if (selected) {
                    styleBtn(btn, accent, Color.WHITE, Color.WHITE)
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
            BoardBrush.ToggleWeakened -> {
                val nextVar = if (oldCell.variant == Variant.WEAKENED) Variant.NORMAL else Variant.WEAKENED
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

    private fun makeHeaderBtn(label: String, marginEndDp: Int = 4, padH: Int = 8, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            setSingleLine(true)
            gravity = Gravity.CENTER
            setPadding(dp(padH), dp(3), dp(padH), dp(3))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(24)
            ).apply { marginEnd = dp(marginEndDp) }
            setOnClickListener { onClick() }
        }
    }

    private fun makeGridBrushBtn(label: String, marginEndDp: Int = 3, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(dp(2), dp(3), dp(2), dp(3))
            layoutParams = LinearLayout.LayoutParams(
                0,
                dp(23),
                1f
            ).apply { marginEnd = dp(marginEndDp) }
            setOnClickListener { onClick() }
        }
    }

    private fun styleBtn(v: TextView, bg: Int, fg: Int, stroke: Int?) {
        v.setTextColor(fg)
        v.background = rounded(bg, dp(7).toFloat(), stroke)
    }

    private fun rounded(bg: Int, radius: Float, stroke: Int?, strokePx: Int = dp(1)) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(bg)
        if (stroke != null) setStroke(strokePx, stroke)
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    companion object {
        private val ACCENT_INDIGO = Color.rgb(63, 81, 181)
        private val ACCENT_AMBER = Color.rgb(230, 118, 0)
        private val ACCENT_TEAL = Color.rgb(0, 137, 123)
        private val ACCENT_CRIMSON = Color.rgb(211, 47, 47)
    }
}
