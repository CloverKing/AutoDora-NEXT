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

sealed class BoardBrush(val id: String, val fallbackLabel: String, val labelRes: Int = 0) {
    data class PaintElement(val element: Element) : BoardBrush("el_${element.code}", PathOverlayView.elementShortZh(element))
    data object ToggleEnchanted : BoardBrush("var_ench", "强化+")
    data object ToggleWeakened : BoardBrush("var_weak", "弱化-")
    data class ToggleRaceType(val race: RaceType, val textRes: Int, val fallback: String) :
        BoardBrush("race_${race.code}", fallback, textRes)
    data class ToggleExclusiveTileFlag(
        val flag: Int,
        val conflictFlag: Int,
        val textRes: Int,
        val fallback: String
    ) : BoardBrush("xflag_$flag", fallback, textRes)
    data class ToggleTileFlag(val flag: Int, val tag: String, val textRes: Int = 0) :
        BoardBrush("flag_$flag", tag, textRes)
    data class ToggleHazard(val hazard: Int, val tag: String) : BoardBrush("haz_$hazard", tag)
    data object ClearMarkers : BoardBrush("clear_cell", "清除格标", R.string.editor_btn_clear_cell)

    fun resolveLabel(ctx: Context): String = if (labelRes != 0) ctx.getString(labelRes) else fallbackLabel
}

private data class BrushGroupSpec(
    val titleRes: Int,
    val accentColor: Int,
    val rows: List<List<BoardBrush>>
) {
    val brushIds: Set<String> = rows.flatten().mapTo(HashSet()) { it.id }
}

private data class AccordionGroupViews(
    val index: Int,
    val spec: BrushGroupSpec,
    val box: LinearLayout,
    val headerRow: LinearLayout,
    val titleView: TextView,
    val activeBadge: TextView,
    val chevronView: TextView,
    val dividerView: View,
    val bodyContainer: LinearLayout
)

/**
 * Grouped, Single-Open Exclusive Colored Accordion Brush Palette Panel for the 1:1 Interactive Board Editor (盘面编排).
 *
 * Groups (5 distinct color-coded exclusive accordion drawers — opening one automatically collapses the other 4
 * to minimize vertical screen footprint over the puzzle board, matching Strategy drawers 1:1;
 * plus standalone 「清除格标」 tool outside all colored drawers):
 *  1. Indigo  — 珠色与珠型 (水/火/木/光/暗/心 + 强化+/弱化-)
 *  2. Purple  — 种族符石 (神族/魔族/人类/兽类/龙类/妖精/机械)
 *  3. Amber   — 拼图与起结有序 (拼图, 单点绿色漩涡, 单点红色漩涡, 多格角色通行, 多格角色停放)
 *  4. Teal    — 符石状态 (全量官方符石状态)
 *  5. Crimson — 盘面地形 (燃烧/灼热/黑洞/血浴/碎裂/暴风)
 */
class OverlayBoardEditorController(
    private val context: Context,
    private val onSaveEditedBoard: () -> Unit,
    private val onClearAllMarkers: () -> Unit,
    private val onCloseEditor: () -> Unit,
    private val onPanelHeightChanged: (() -> Unit)? = null
) {
    val rootView: LinearLayout
    var activeBrush: BoardBrush = BoardBrush.PaintElement(Element.WATER)
        private set

    private var isSavedState: Boolean = false
    private var expandedGroupIdx: Int? = 0 // Single-open exclusive accordion: default Group 0 (珠色与珠型) open
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
                    BoardBrush.ToggleWeakened
                )
            )
        ),
        BrushGroupSpec(
            titleRes = R.string.editor_grp_race,
            accentColor = ACCENT_PURPLE,
            rows = listOf(
                listOf(
                    BoardBrush.ToggleRaceType(RaceType.GOD, R.string.race_god, "神族"),
                    BoardBrush.ToggleRaceType(RaceType.DEMON, R.string.race_demon, "魔族"),
                    BoardBrush.ToggleRaceType(RaceType.HUMAN, R.string.race_human, "人类"),
                    BoardBrush.ToggleRaceType(RaceType.BEAST, R.string.race_beast, "兽类"),
                    BoardBrush.ToggleRaceType(RaceType.DRAGON, R.string.race_dragon, "龙类"),
                    BoardBrush.ToggleRaceType(RaceType.ELF, R.string.race_elf, "妖精"),
                    BoardBrush.ToggleRaceType(RaceType.MACHINA, R.string.race_machina, "机械")
                )
            )
        ),
        BrushGroupSpec(
            titleRes = R.string.editor_grp_shape,
            accentColor = ACCENT_AMBER,
            rows = listOf(
                listOf(
                    BoardBrush.ToggleTileFlag(TileFlagBits.PUZZLE_1, "拼图", R.string.editor_brush_puzzle),
                    BoardBrush.ToggleExclusiveTileFlag(
                        flag = TileFlagBits.START_ALLOWED,
                        conflictFlag = TileFlagBits.END_REQUIRED,
                        textRes = R.string.editor_brush_green_vortex,
                        fallback = "绿色漩涡"
                    ),
                    BoardBrush.ToggleExclusiveTileFlag(
                        flag = TileFlagBits.END_REQUIRED,
                        conflictFlag = TileFlagBits.START_ALLOWED,
                        textRes = R.string.editor_brush_red_vortex,
                        fallback = "红色漩涡"
                    ),
                    BoardBrush.ToggleTileFlag(TileFlagBits.CHAR_ORB_PASS, "角色通行", R.string.editor_brush_char_pass),
                    BoardBrush.ToggleTileFlag(TileFlagBits.CHAR_ORB_END, "角色停放", R.string.editor_brush_char_end)
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
                    BoardBrush.ToggleHazard(HazardBits.THORN, "荆棘"),
                    BoardBrush.ToggleHazard(HazardBits.LOCKED_HURT, "锁定"),
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

    private val allGroupBrushes: List<Pair<BoardBrush, Int>> = brushGroups.flatMap { grp ->
        grp.rows.flatten().map { brush -> brush to grp.accentColor }
    }

    private val brushButtons = LinkedHashMap<String, TextView>()
    private val accordionGroups = ArrayList<AccordionGroupViews>()
    private val titleLabel: TextView
    private val statusBadge: TextView
    private val hintLabel: TextView
    private val clearCellBrushBtn: TextView
    private val saveBoardBtn: TextView
    private val clearAllBtn: TextView
    private val exitBtn: TextView

    init {
        val dm = context.resources.displayMetrics
        val panelWidth = minOf(dm.widthPixels - dp(16), dp(356))
        val maxScrollH = (dm.heightPixels * 0.38f).toInt().coerceIn(dp(230), dp(310))

        rootView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            elevation = dp(4).toFloat()
            setPadding(dp(8), dp(5), dp(8), dp(5))
            layoutParams = LinearLayout.LayoutParams(
                panelWidth,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
                marginStart = dp(4)
            }
        }

        // Row 1 — Header: Title + State Badge + [保存版面] + [清除标记] + [✕]
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

        // Row 2 — Standalone Tool Bar OUTSIDE all colored group boxes: Workflow hint + 「清除格标」 brush!
        val toolBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(3)
                bottomMargin = dp(4)
            }
        }
        hintLabel = TextView(context).apply {
            text = context.getString(R.string.editor_hint_unsaved)
            textSize = 8.8f
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
        }
        toolBar.addView(hintLabel)

        clearCellBrushBtn = makeHeaderBtn(
            label = context.getString(R.string.editor_btn_clear_cell),
            marginEndDp = 0,
            padH = 8
        ) {
            activeBrush = BoardBrush.ClearMarkers
            refreshBrushStyles()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(22)
            )
        }
        toolBar.addView(clearCellBrushBtn)
        rootView.addView(toolBar)

        // Bounded vertical ScrollView containing 5 clean color-coded multi-row brush groups
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
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = if (gIdx == brushGroups.lastIndex) 0 else dp(4)
                }
            }

            // Clickable Colored Accordion Header (matches Strategy drawers 1:1!)
            val gHdr = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(7), dp(5), dp(7), dp(5))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener {
                    // Exclusive single-open accordion: opening one drawer auto-collapses all other drawers!
                    expandedGroupIdx = if (expandedGroupIdx == gIdx) null else gIdx
                    refreshAccordionState()
                    onPanelHeightChanged?.invoke()
                }
            }
            gHdr.addView(View(context).apply {
                background = rounded(group.accentColor, dp(2).toFloat(), null)
                layoutParams = LinearLayout.LayoutParams(dp(3), dp(12)).apply { marginEnd = dp(6) }
            })
            val gTitle = TextView(context).apply {
                text = context.getString(group.titleRes)
                textSize = 9.8f
                typeface = Typeface.DEFAULT_BOLD
                setSingleLine(true)
            }
            gHdr.addView(gTitle)
            gHdr.addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f)
            })
            val activeBadge = TextView(context).apply {
                textSize = 8.8f
                typeface = Typeface.DEFAULT_BOLD
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = dp(96)
                setPadding(dp(5), dp(1), dp(5), dp(1))
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(5) }
            }
            gHdr.addView(activeBadge)
            val chevron = TextView(context).apply {
                text = if (expandedGroupIdx == gIdx) "▾" else "▸"
                textSize = 10.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(group.accentColor)
            }
            gHdr.addView(chevron)
            groupBox.addView(gHdr)

            val divider = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                visibility = if (expandedGroupIdx == gIdx) View.VISIBLE else View.GONE
            }
            groupBox.addView(divider)

            val bodyContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(6), dp(4), dp(6), dp(5))
                visibility = if (expandedGroupIdx == gIdx) View.VISIBLE else View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            // Wrapped multi-row equal-width grid rows inside collapsible body
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
                        label = brush.resolveLabel(context),
                        marginEndDp = if (bIdx == rowBrushes.lastIndex) 0 else 3
                    ) {
                        activeBrush = brush
                        refreshBrushStyles()
                    }
                    brushButtons[brush.id] = btn
                    row.addView(btn)
                }
                bodyContainer.addView(row)
            }
            groupBox.addView(bodyContainer)
            groupsHost.addView(groupBox)

            accordionGroups.add(
                AccordionGroupViews(
                    index = gIdx,
                    spec = group,
                    box = groupBox,
                    headerRow = gHdr,
                    titleView = gTitle,
                    activeBadge = activeBadge,
                    chevronView = chevron,
                    dividerView = divider,
                    bodyContainer = bodyContainer
                )
            )
        }

        scroll.addView(groupsHost)
        rootView.addView(scroll)
    }

    fun setMaxPanelWidth(widthPx: Int, topMarginPx: Int = dp(4), marginStartPx: Int = dp(4)) {
        val lp = (rootView.layoutParams as? LinearLayout.LayoutParams)
            ?: LinearLayout.LayoutParams(widthPx, LinearLayout.LayoutParams.WRAP_CONTENT)
        if (lp.width != widthPx || lp.topMargin != topMarginPx || lp.marginStart != marginStartPx) {
            lp.width = widthPx
            lp.topMargin = topMarginPx
            lp.marginStart = marginStartPx
            rootView.layoutParams = lp
        }
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

        refreshSaveStateChrome()
        refreshBrushStyles()
    }

    private fun refreshAccordionState() {
        val p = currentPalette ?: return
        val cardSurface = if (p.isDark) Color.rgb(34, 38, 46) else Color.WHITE
        for (grp in accordionGroups) {
            val accent = grp.spec.accentColor
            val r = Color.red(accent)
            val g = Color.green(accent)
            val b = Color.blue(accent)
            val isOpen = expandedGroupIdx == grp.index
            val hasActiveBrush = activeBrush.id in grp.spec.brushIds

            grp.titleView.text = lctx.getString(grp.spec.titleRes)
            grp.titleView.setTextColor(if (p.isDark) Color.WHITE else p.onSurface)
            grp.chevronView.text = if (isOpen) "▾" else "▸"
            grp.chevronView.setTextColor(accent)

            if (hasActiveBrush) {
                val activeLbl = activeBrush.resolveLabel(lctx)
                grp.activeBadge.text = "●$activeLbl"
                grp.activeBadge.visibility = View.VISIBLE
                val badgeBg = Color.argb(if (p.isDark) 52 else 28, r, g, b)
                grp.activeBadge.setTextColor(accent)
                grp.activeBadge.background = rounded(badgeBg, dp(6).toFloat(), null)
            } else {
                grp.activeBadge.visibility = View.GONE
            }

            val borderAlpha = when {
                isOpen || hasActiveBrush -> if (p.isDark) 172 else 148
                else -> if (p.isDark) 84 else 64
            }
            val headerTint = Color.argb(if (p.isDark) 42 else 22, r, g, b)
            grp.box.background = rounded(cardSurface, dp(9).toFloat(), Color.argb(borderAlpha, r, g, b))
            grp.headerRow.background = if (isOpen) {
                topRounded(headerTint, dp(8).toFloat())
            } else {
                rounded(headerTint, dp(8).toFloat(), null)
            }
            grp.dividerView.setBackgroundColor(Color.argb(if (p.isDark) 56 else 36, r, g, b))
            grp.dividerView.visibility = if (isOpen) View.VISIBLE else View.GONE
            grp.bodyContainer.visibility = if (isOpen) View.VISIBLE else View.GONE
        }
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
        // Standalone 「清除格标」 button outside all colored groups
        val clearSel = activeBrush == BoardBrush.ClearMarkers
        val clearLbl = BoardBrush.ClearMarkers.resolveLabel(lctx)
        clearCellBrushBtn.text = if (clearSel) "●$clearLbl" else clearLbl
        if (clearSel) {
            styleBtn(clearCellBrushBtn, ACCENT_SLATE, Color.WHITE, Color.WHITE)
        } else {
            styleBtn(clearCellBrushBtn, p.chipOffBg, p.onSurface, p.outline)
        }

        for ((b, accent) in allGroupBrushes) {
            val btn = brushButtons[b.id] ?: continue
            val selected = (b.id == activeBrush.id)
            val lbl = b.resolveLabel(lctx)
            if (b is BoardBrush.PaintElement) {
                val elemCol = PathOverlayView.elementColor(b.element)
                btn.text = if (selected) "●$lbl" else lbl
                btn.setTextColor(Color.WHITE)
                btn.background = rounded(
                    elemCol,
                    dp(7).toFloat(),
                    if (selected) Color.WHITE else Color.argb(95, 255, 255, 255),
                    strokePx = if (selected) dp(2) else dp(1)
                )
            } else {
                btn.text = if (selected) "●$lbl" else lbl
                if (selected) {
                    styleBtn(btn, accent, Color.WHITE, Color.WHITE)
                } else {
                    styleBtn(btn, p.chipOffBg, p.chipOffText, p.chipOffStroke)
                }
            }
        }
        refreshAccordionState()
    }

    fun applyActiveBrush(board: BoardState, row: Int, col: Int): BoardState =
        applyBrushToBoard(board, activeBrush, row, col)

    private fun makeHeaderBtn(label: String, marginEndDp: Int = 4, padH: Int = 8, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            setSingleLine(true)
            gravity = Gravity.CENTER
            setPadding(dp(padH), dp(2), dp(padH), dp(2))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(23)
            ).apply { marginEnd = dp(marginEndDp) }
            setOnClickListener { onClick() }
        }
    }

    private fun makeGridBrushBtn(label: String, marginEndDp: Int = 3, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            textSize = 9.0f
            typeface = Typeface.DEFAULT_BOLD
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(dp(1), dp(2), dp(1), dp(2))
            layoutParams = LinearLayout.LayoutParams(
                0,
                dp(21),
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

    private fun topRounded(bg: Int, radius: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
        setColor(bg)
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    companion object {
        private const val ACCENT_INDIGO = 0xFF3F51B5.toInt()
        private const val ACCENT_PURPLE = 0xFF7B1FA2.toInt()
        private const val ACCENT_AMBER = 0xFFE67600.toInt()
        private const val ACCENT_TEAL = 0xFF00897B.toInt()
        private const val ACCENT_CRIMSON = 0xFFD32F2F.toInt()
        private const val ACCENT_SLATE = 0xFF475569.toInt()

        /**
         * Pure board-mutation helper shared by the interactive Board Editor and JVM unit tests.
         * Enforces strict single-point exclusivity for Start/End vortexes (起结有序: 绿色漩涡 / 红色漩涡),
         * multi-cell painting for Character Runestone pass/end terrains (#54 / #78), and exact 7-race selection.
         */
        fun applyBrushToBoard(board: BoardState, brush: BoardBrush, row: Int, col: Int): BoardState {
            val idx = row * board.cols + col
            if (idx !in board.cells.indices) return board
            val newCells = board.cells.toMutableList()
            val newFlags = board.tileFlags.toMutableList()
            val oldCell = newCells[idx]
            val oldFlags = newFlags[idx]

            when (brush) {
                is BoardBrush.PaintElement -> {
                    newCells[idx] = oldCell.copy(
                        element = brush.element,
                        hazards = oldCell.hazards and HazardBits.QUESTION.inv(),
                        confidence = 1.0f
                    )
                }
                BoardBrush.ToggleEnchanted -> {
                    val nextVar = if (oldCell.variant == Variant.ENCHANTED) Variant.NORMAL else Variant.ENCHANTED
                    newCells[idx] = oldCell.copy(variant = nextVar, race = RaceType.NONE)
                }
                BoardBrush.ToggleWeakened -> {
                    val nextVar = if (oldCell.variant == Variant.WEAKENED) Variant.NORMAL else Variant.WEAKENED
                    newCells[idx] = oldCell.copy(variant = nextVar, race = RaceType.NONE)
                }
                is BoardBrush.ToggleRaceType -> {
                    if (oldCell.variant == Variant.RACE && oldCell.race == brush.race) {
                        newCells[idx] = oldCell.copy(variant = Variant.NORMAL, race = RaceType.NONE)
                    } else {
                        newCells[idx] = oldCell.copy(variant = Variant.RACE, race = brush.race)
                    }
                }
                is BoardBrush.ToggleExclusiveTileFlag -> {
                    val alreadySet = (oldFlags and brush.flag) != 0
                    // Clear this exclusive flag from every cell on the board first (strictly <= 1 cell!)
                    for (i in newFlags.indices) {
                        newFlags[i] = newFlags[i] and brush.flag.inv()
                    }
                    if (!alreadySet) {
                        // Also clear conflicting start/end flag on this same cell (start != end)
                        newFlags[idx] = (newFlags[idx] and brush.conflictFlag.inv()) or brush.flag
                    }
                }
                is BoardBrush.ToggleTileFlag -> {
                    newFlags[idx] = oldFlags xor brush.flag
                }
                is BoardBrush.ToggleHazard -> {
                    newCells[idx] = oldCell.copy(hazards = oldCell.hazards xor brush.hazard)
                }
                BoardBrush.ClearMarkers -> {
                    newCells[idx] = oldCell.copy(
                        variant = Variant.NORMAL,
                        race = RaceType.NONE,
                        hazards = HazardBits.NONE
                    )
                    newFlags[idx] = TileFlagBits.NONE
                }
            }
            return board.copy(cells = newCells, tileFlags = newFlags)
        }
    }
}
