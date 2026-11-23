package com.autodora.next.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.autodora.next.R
import com.autodora.next.data.*
import com.autodora.next.model.*
import com.autodora.next.touch.SwipeSpeedPreset
import com.autodora.next.ui.LocaleHelper
import com.autodora.next.ui.OverlayPalette
import kotlin.math.roundToInt

/**
 * In-Game 3-Tab Unified Floating Drawer mirroring the Launch App's 3-tab layout (快捷 | 策略 | 设置):
 *  - Tab 0: 快捷 (Quick tools: Board Paint, Clear Wave Shields, Hold-Replan, Nudge, Invert, Speed 4-Tier)
 *  - Tab 1: 策略 (100% Unified Strategy & Shields parity organized into a compact Preset Save/Load bar +
 *           6 visually distinct, color-accented, bordered accordion cards with live summary badges & crisp internal dividers!)
 *  - Tab 2: 设置 (System-level defaults: Preview-Confirm, System Steps & Compute slider, Core switches, Theme & Language)
 */
class OverlayTuningDrawerController(
    private val context: Context,
    private val onToggleBoardEditor: () -> Unit,
    private val onToggleWaveChip: (WaveShieldChip) -> Unit,
    private val onUpsertWaveChip: (WaveShieldChip) -> Unit,
    private val onClearWaveChips: () -> Unit,
    private val onSelectPreset: (String) -> Unit,
    private val onSaveActivePreset: () -> Unit,
    private val onDuplicateActivePreset: () -> Unit,
    private val onUpdateActivePresetConfig: ((RuleConfig) -> RuleConfig) -> Unit,
    private val onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    private val onClose: () -> Unit
) {
    val rootView: LinearLayout
    private val tabRow: LinearLayout
    private val scrollView: ScrollView
    private val contentHost: LinearLayout
    private val tabButtons = ArrayList<TextView>()

    private val maxScrollH: Int
    private var activeTab = 1 // Default directly to unified 策略 tab
    private var lctx: Context = context
    private var palette: OverlayPalette? = null
    private var settings: AppSettings = AppSettings()
    private var presets: List<TeamPresetRecord> = emptyList()
    private var activePreset: TeamBasePreset = TeamPresetRepository.defaultBuiltInPresets().first()
    private var activeChips: List<WaveShieldChip> = emptyList()
    private var boardEditorOpen: Boolean = false
    private var hasSavedBoard: Boolean = false

    // Exclusive single-open accordion expansion per tab (expanding one auto-collapses the others in that tab!)
    private var presetPickerExpanded = false
    private val expandedCardByTab = mutableMapOf<Int, Int?>(
        0 to 0, // Tab 0 (Quick): Card 0 open
        1 to 0, // Tab 1 (Strategy): Card 0 open
        2 to 0  // Tab 2 (Settings): Card 0 open
    )

    // Working numeric parameters synchronized with ActiveStrategyStore
    private var minTotalComboVal = 6
    private var minFirstComboVal = 5
    private var minFirstElemVal = 5
    private var exactTotalComboVal = 6
    private var exactFirstComboVal = 4
    private var wave1KindVal = 5
    private var parityFirstOnly = true
    private var secondWaveVal = 1
    private var sameElemGroupsVal = 3
    private var sameElemTarget = -1
    private var equalElemsMask = (1 shl 3) or (1 shl 4)
    private var burstOrbsVal = 5
    private var elemLimitMode = 0
    private var digitalOrbsVal = 18
    private var stepLimitVal = 15
    private var stepRangeMinVal = 20
    private var stepRangeMaxVal = 25

    init {
        val density = context.resources.displayMetrics.density
        val maxDrawerW = (context.resources.displayMetrics.widthPixels * 0.92f).toInt().coerceAtMost((390 * density).toInt())
        maxScrollH = (context.resources.displayMetrics.heightPixels * 0.48f).toInt().coerceIn((240 * density).toInt(), (400 * density).toInt())

        rootView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(8), dp(6), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(maxDrawerW, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            }
        }

        // Top 3-Tab Bar (快捷 | 策略 | 设置) + Close
        tabRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        }
        val tabResIds = listOf(
            R.string.ov_tab_quick,
            R.string.ov_tab_strategy,
            R.string.ov_tab_settings
        )
        tabResIds.forEachIndexed { idx, resId ->
            val tv = TextView(context).apply {
                text = lctx.getString(resId)
                textSize = 11.5f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setSingleLine(true)
                setPadding(dp(8), dp(5), dp(8), dp(5))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(4)
                }
                setOnClickListener {
                    activeTab = idx
                    rebuildContent(preserveScroll = false)
                }
            }
            tabButtons.add(tv)
            tabRow.addView(tv)
        }
        val closeBtn = TextView(context).apply {
            text = "✕"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(5), dp(8), dp(5))
            setOnClickListener { onClose() }
        }
        tabRow.addView(closeBtn)
        rootView.addView(tabRow)

        scrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, maxScrollH)
        }
        contentHost = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        scrollView.addView(contentHost)
        rootView.addView(scrollView)
    }

    private fun s(resId: Int): String = lctx.getString(resId)
    private fun s(resId: Int, vararg args: Any): String = lctx.getString(resId, *args)

    fun setVisible(visible: Boolean) {
        rootView.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) rebuildContent(preserveScroll = true)
    }

    fun setMaxPanelWidth(widthPx: Int, topMarginPx: Int = dp(4)) {
        val lp = (rootView.layoutParams as? LinearLayout.LayoutParams)
            ?: LinearLayout.LayoutParams(widthPx, LinearLayout.LayoutParams.WRAP_CONTENT)
        if (lp.width != widthPx || lp.topMargin != topMarginPx) {
            lp.width = widthPx
            lp.topMargin = topMarginPx
            rootView.layoutParams = lp
        }
    }

    fun updateData(
        palette: OverlayPalette,
        settings: AppSettings,
        presets: List<TeamPresetRecord>,
        activePreset: TeamBasePreset,
        activeChips: List<WaveShieldChip>,
        boardEditorOpen: Boolean = false,
        hasSavedBoard: Boolean = false
    ) {
        this.lctx = LocaleHelper.localizedContext(context, settings.language)
        this.palette = palette
        this.settings = settings
        this.presets = presets
        this.activePreset = activePreset
        this.activeChips = activeChips
        this.boardEditorOpen = boardEditorOpen
        this.hasSavedBoard = hasSavedBoard

        val byId = activeChips.associateBy { it.id }
        byId["min_combo"]?.paramInt?.let { if (it in 1..15) minTotalComboVal = it }
        byId["min_first_combo"]?.paramInt?.let { if (it in 1..12) minFirstComboVal = it }
        byId["min_first_elem_combo"]?.paramInt?.let { if (it in 1..12) minFirstElemVal = it }
        byId["exact_combo"]?.paramInt?.let { if (it in 1..15) exactTotalComboVal = it }
        byId["exact_first_combo"]?.paramInt?.let { if (it in 1..12) exactFirstComboVal = it }
        (byId["wave1_same_kind"] ?: byId["wave1_distinct_kind"])?.paramInt?.let { if (it in 1..10) wave1KindVal = it }
        (byId["odd_combo"] ?: byId["even_combo"])?.let { parityFirstOnly = it.paramInt != 1 }
        byId["second_wave_combo"]?.paramInt?.let { if (it in 1..6) secondWaveVal = it }
        byId["same_elem_combos"]?.let {
            if (it.paramInt in 2..6) sameElemGroupsVal = it.paramInt
            sameElemTarget = if (it.paramElementMask != 0) {
                (0..5).firstOrNull { idx -> (it.paramElementMask and (1 shl idx)) != 0 } ?: -1
            } else -1
        }
        byId["equal_pair_light_dark"]?.paramElementMask?.let { m ->
            if ((m and 0x100) != 0 && Integer.bitCount(m and 0x3F) >= 2) {
                equalElemsMask = m and 0x3F
            } else if (m != 0) {
                val a = (m and 0x0F).coerceIn(0, 5)
                val b = ((m ushr 4) and 0x0F).coerceIn(0, 5)
                equalElemsMask = (1 shl a) or (1 shl b)
            }
        }
        byId["elem_burst_shield"]?.paramInt?.let { if (it in 3..15) burstOrbsVal = it }
        byId["digital_orb_count"]?.paramInt?.let { if (it in 6..30) digitalOrbsVal = it }
        byId["step_limit"]?.paramInt?.let { if (it in 2..60) stepLimitVal = it }
        byId["step_range"]?.paramInt?.let { packed ->
            val lo = packed and 0xFF
            val hi = (packed ushr 8) and 0xFF
            if (lo in 1..200 && hi in lo..200) {
                stepRangeMinVal = lo
                stepRangeMaxVal = hi
            }
        }

        if (rootView.visibility == View.VISIBLE) {
            rebuildContent(preserveScroll = true)
        } else {
            applyChromeTheme()
        }
    }

    private fun applyChromeTheme() {
        val p = palette ?: return
        val opaqueDrawerBg = Color.argb(248, Color.red(p.containerBg), Color.green(p.containerBg), Color.blue(p.containerBg))
        rootView.background = roundedBg(opaqueDrawerBg, dp(14).toFloat(), p.containerStroke)
        val tabTitles = listOf(
            s(R.string.ov_tab_quick),
            if (activeChips.isNotEmpty()) "${s(R.string.ov_tab_strategy)}(${activeChips.size})" else s(R.string.ov_tab_strategy),
            s(R.string.ov_tab_settings)
        )
        tabButtons.forEachIndexed { idx, tv ->
            tv.text = tabTitles[idx]
            if (idx == activeTab) {
                styleChip(tv, p.primary, p.onPrimary, null)
            } else {
                styleChip(tv, p.chipOffBg, p.onSurfaceVariant, p.chipOffStroke)
            }
        }
        val closeView = tabRow.getChildAt(tabRow.childCount - 1) as? TextView
        closeView?.let { styleChip(it, p.chipOffBg, p.onSurfaceVariant, p.chipOffStroke) }
    }

    private fun rebuildContent(preserveScroll: Boolean = true) {
        val p = palette ?: return
        val savedScrollY = if (preserveScroll) scrollView.scrollY else 0
        applyChromeTheme()
        val targetH = if (activeTab == 1 && expandedCardByTab[1] != null) maxScrollH else LinearLayout.LayoutParams.WRAP_CONTENT
        if (scrollView.layoutParams.height != targetH) {
            scrollView.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, targetH)
        }
        contentHost.removeAllViews()
        when (activeTab) {
            0 -> buildQuickTab(p)
            1 -> buildUnifiedStrategyTab(p)
            2 -> buildSettingsTab(p)
        }
        if (preserveScroll && savedScrollY > 0) {
            scrollView.post { scrollView.scrollTo(0, savedScrollY) }
        } else if (!preserveScroll) {
            scrollView.post { scrollView.scrollTo(0, 0) }
        }
    }

    // ==================== TAB 0: 快捷工具 (3 Single-Open Colored Accordions) ====================
    private fun buildQuickTab(p: OverlayPalette) {
        // Card 0 (Indigo): Board Paint & Active Wave Shields
        val q0Summary = buildList {
            if (boardEditorOpen) add(s(R.string.ov_editor))
            else if (hasSavedBoard) add(s(R.string.ov_saved_board_pill))
            if (activeChips.isNotEmpty()) add(s(R.string.ov_clear_enemy_short, activeChips.size))
        }.joinToString(" · ").ifEmpty { s(R.string.ov_card_none_set) }
        addAccordionGroupCard(0, s(R.string.ov_tab_quick), q0Summary, boardEditorOpen || hasSavedBoard || activeChips.isNotEmpty(), ACCENT_INDIGO, p)?.let { quickBody ->
            val editorBtnLabel = if (hasSavedBoard && !boardEditorOpen) {
                "${s(R.string.ov_editor)} (${s(R.string.editor_badge_saved)})"
            } else {
                s(R.string.ov_editor)
            }
            val actRow = hRow(bottomMarginDp = 0)
            actRow.addView(makeToggle(editorBtnLabel, boardEditorOpen || hasSavedBoard, p, weight = 1f) {
                onToggleBoardEditor()
            })
            actRow.addView(makeChip(s(R.string.ov_clear_enemy_short, activeChips.size), weight = 1f, marginEndDp = 0) {
                onClearWaveChips()
            }.also {
                styleChip(it, p.errorContainer, p.onErrorContainer, null)
            })
            quickBody.addView(actRow)
        }

        // Card 1 (Teal): Battle Assist Modes (1:1 parity with Launch App Settings!)
        val invLabel = invertSummaryLabel()
        val q1Summary = buildList {
            if (settings.holdReplanEnabled) {
                val trig = s(if (settings.holdReplanNudge) R.string.mode_hold_trigger_nudge else R.string.mode_hold_trigger_inplace)
                add("${s(R.string.ov_hold)}($trig)")
            }
            if (invLabel != null) add(invLabel)
        }.joinToString(" · ").ifEmpty { s(R.string.ov_card_none_set) }
        addAccordionGroupCard(1, s(R.string.section_modes), q1Summary, settings.holdReplanEnabled || invLabel != null, ACCENT_TEAL, p)?.let { modeBody ->
            populateHoldAndInvertControls(modeBody, p)
        }

        // Card 2 (Amber): Unified 6-Tier Swipe Speed Presets (2s..7s)
        val activeSpeed = SwipeSpeedPreset.fromStepDurationMs(settings.stepDurationMs)
        val q2Summary = "${LocaleHelper.speedLabel(lctx, activeSpeed)} · ${settings.stepDurationMs}${s(R.string.unit_ms)}"
        addAccordionGroupCard(2, s(R.string.section_speed), q2Summary, true, ACCENT_AMBER, p)?.let { spdBody ->
            populateSpeedPresetSection(spdBody, p)
        }
    }

    // ==================== TAB 1: UNIFIED STRATEGY WORKBENCH (6 Distinct Bordered Group Cards!) ====================
    private fun buildUnifiedStrategyTab(p: OverlayPalette) {
        val cfg = activePreset.baseConfig
        val byId = activeChips.associateBy { it.id }
        val activeIds = byId.keys
        val list = presets.ifEmpty {
            TeamPresetRepository.defaultBuiltInPresets().map { TeamPresetRecord(it, true, 0L) }
        }
        val activeRec = list.firstOrNull { it.preset.id == settings.activePresetId } ?: list.first()
        val presetDisplayName = LocaleHelper.displayPresetName(lctx, activeRec.preset, activeRec.isBuiltIn)

        // ---------- Sticky Compact Preset Save/Load & Enemy-Match Header Card ----------
        val topCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(p.primaryContainer, dp(10).toFloat(), p.primary)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        val topBar = hRow(bottomMarginDp = if (presetPickerExpanded || activeChips.isNotEmpty()) 5 else 0)
        val pickerChevron = if (presetPickerExpanded) "▴" else "▾"
        topBar.addView(makeChip("$presetDisplayName $pickerChevron", padH = 7, weight = 1.6f) {
            presetPickerExpanded = !presetPickerExpanded
            rebuildContent(preserveScroll = true)
        }.also {
            it.ellipsize = TextUtils.TruncateAt.END
            styleChip(it, if (p.isDark) Color.rgb(36, 44, 62) else Color.WHITE, p.primary, p.primary)
        })
        topBar.addView(makeChip(s(R.string.ov_preset_save_short), padH = 6, weight = 0.55f) {
            onSaveActivePreset()
        }.also { styleChip(it, p.primary, p.onPrimary, null) })
        topBar.addView(makeChip(s(R.string.ov_preset_save_as_short), padH = 6, weight = 0.65f, marginEndDp = if (activeChips.isNotEmpty()) 4 else 0) {
            onDuplicateActivePreset()
        }.also { styleChip(it, p.secondaryContainer, p.onSecondaryContainer, p.outline) })
        if (activeChips.isNotEmpty()) {
            topBar.addView(makeChip(s(R.string.ov_clear_enemy_short, activeChips.size), padH = 5, weight = 0.85f, marginEndDp = 0) {
                onClearWaveChips()
            }.also { styleChip(it, p.errorContainer, p.onErrorContainer, null) })
        }
        topCard.addView(topBar)

        if (presetPickerExpanded) {
            val presetScroll = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = if (activeChips.isNotEmpty()) dp(4) else 0 }
            }
            val presetRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            list.forEachIndexed { idx, rec ->
                val sel = rec.preset.id == settings.activePresetId
                presetRow.addView(
                    makeToggle(
                        LocaleHelper.displayPresetName(lctx, rec.preset, rec.isBuiltIn),
                        sel,
                        p,
                        marginEndDp = if (idx == list.lastIndex) 0 else 4
                    ) {
                        presetPickerExpanded = false
                        onSelectPreset(rec.preset.id)
                    }
                )
            }
            presetScroll.addView(presetRow)
            topCard.addView(presetScroll)
        }

        if (activeChips.isNotEmpty()) {
            val chipScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
            val chipRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in activeChips) {
                chipRow.addView(makeChip("${LocaleHelper.formatWaveChipLabel(lctx, c)} ×", padH = 6) {
                    onToggleWaveChip(c)
                }.also {
                    styleChip(it, if (p.isDark) Color.rgb(34, 48, 72) else Color.WHITE, p.primary, p.primary)
                })
            }
            chipScroll.addView(chipRow)
            topCard.addView(chipScroll)
        }
        contentHost.addView(topCard)

        // ---------- Compute Live Summary Badges for All 6 Cards ----------
        val comboChipIds = setOf(
            "min_combo", "min_first_combo", "min_first_elem_combo",
            "exact_combo", "exact_first_combo", "wave1_same_kind", "wave1_distinct_kind",
            "zero_combo", "odd_combo", "even_combo", "no_cascade",
            "same_combo_elements", "second_wave_combo", "same_elem_combos", "equal_pair_light_dark"
        )
        val activeComboLabels = activeChips.filter { it.id in comboChipIds }.map { LocaleHelper.formatWaveChipLabel(lctx, it) }
        val card0Summary = buildList {
            add(LocaleHelper.goalSummary(lctx, cfg))
            addAll(activeComboLabels.take(2))
        }.joinToString(" · ")
        val card0Highlight = activeComboLabels.isNotEmpty() || cfg.goalMode != StrategyGoalMode.HIGH_COMBO

        val elemChipIds = setOf(
            "min_distinct_elements", "require_elements", "three_elements", "five_elements", "five_elements_enchanted",
            "elem_burst_shield", "anti_first_wave", "forbid_all", "clear_all_element", "unmatchable_elements",
            "digital_orb_count", "first_wave_full_clear", "any_enchanted_orb_shield", "race_orb_shield",
            "char_orb_first_wave", "forbid_weakened_orb", "luminous_orb_shield"
        )
        val activeElemLabels = activeChips.filter { it.id in elemChipIds }.map { LocaleHelper.formatWaveChipLabel(lctx, it) }
        val customMatrixCount = cfg.elementRules.count { it.targetShape != TargetShape.ANY || it.priority != ElementPriority.NORMAL || it.timing != MatchTiming.ANY || it.minMatchCount != 3 }
        val card1Summary = buildList {
            addAll(activeElemLabels.take(2))
            if (customMatrixCount > 0) add("${s(R.string.tag_custom_shape)}×$customMatrixCount")
        }.joinToString(" · ").ifEmpty { s(R.string.ov_card_none_set) }
        val card1Highlight = activeElemLabels.isNotEmpty() || customMatrixCount > 0

        val shapeChipIds = setOf("puzzle_shield", "strict_puzzle", "loose_puzzle", "enchanted_puzzle", "cross_shield", "cross_shield_2", "cross_shield_3", "l_shape", "t_shape", "square_2x2", "all_columns_ameliorate")
        val activeShapeLabels = activeChips.filter { it.id in shapeChipIds }.map { LocaleHelper.formatWaveChipLabel(lctx, it) }
        val colEnabledCnt = cfg.columnRules.take(6).count { it.enabled }
        val card2Summary = buildList {
            addAll(activeShapeLabels.take(2))
            if (colEnabledCnt > 0) add("${s(R.string.tag_col_amel)}×$colEnabledCnt")
        }.joinToString(" · ").ifEmpty { s(R.string.ov_card_none_set) }
        val card2Highlight = activeShapeLabels.isNotEmpty() || colEnabledCnt > 0

        val pathChipIds = setOf("traffic_light", "zero_trail_burning", "trail_corrosion", "step_hurt_minimize", "char_orb_terrain", "weijian_burst")
        val activePathLabels = activeChips.filter { it.id in pathChipIds }.map { LocaleHelper.formatWaveChipLabel(lctx, it) }
        val card3Summary = buildList {
            addAll(activePathLabels.take(2))
            if (cfg.allowHitWeatheredToClear) add(s(R.string.switch_hit_weathered).take(6))
        }.joinToString(" · ").ifEmpty { s(R.string.ov_card_none_set) }
        val card3Highlight = activePathLabels.isNotEmpty() || cfg.allowHitWeatheredToClear

        val effSteps = if (cfg.useSystemSteps) settings.maxSteps else cfg.maxSteps
        val effBeam = if (cfg.useSystemBeam) settings.beamWidth else cfg.beamWidth
        val stepLimitChip = byId["step_limit"]
        val stepRangeChip = byId["step_range"]
        val card4Summary = buildList {
            if (stepRangeChip != null) add(LocaleHelper.formatWaveChipLabel(lctx, stepRangeChip))
            else if (stepLimitChip != null) add(s(R.string.ov_step_limit_short, stepLimitChip.paramInt))
            else add("$effSteps${s(R.string.unit_steps)}")
            add("${s(R.string.tag_beam_fmt, effBeam)}")
            if ("traffic_light" in activeIds) add(s(R.string.shield_traffic_light))
            if (cfg.useCharacterOrb) add(s(R.string.tag_char_orb_fmt, cfg.characterOrbCol + 1))
        }.joinToString(" · ")
        val card4Highlight = stepRangeChip != null || stepLimitChip != null || "traffic_light" in activeIds || !cfg.useSystemSteps || !cfg.useSystemBeam || cfg.useCharacterOrb || cfg.startPreset != StartPointPreset.ANY

        val isHeartAll = (cfg.compatibleLinkMask.getOrElse(5) { 1 shl 5 } and 0x3F) == 0x3F
        val isLightDark = ((cfg.compatibleLinkMask.getOrElse(3) { 1 shl 3 } and (1 shl 4)) != 0) && !isHeartAll
        val isWfg = ((cfg.compatibleLinkMask.getOrElse(0) { 1 shl 0 } and (1 shl 1)) != 0) && !isHeartAll
        val card5Summary = buildList {
            if (isHeartAll) add(s(R.string.link_heart_all))
            if (isLightDark) add(s(R.string.link_light_dark))
            if (isWfg) add(s(R.string.link_wfg))
            if (cfg.forceSplitThree) add(s(R.string.tag_split_three))
            if (cfg.firstWaveOnlyScoring) add(s(R.string.tag_first_only))
            if (cfg.allowDiagonalMatch) add(s(R.string.tag_diag_match))
        }.joinToString(" · ").ifEmpty { s(R.string.ov_card_none_set) }
        val card5Highlight = isHeartAll || isLightDark || isWfg || cfg.forceSplitThree || cfg.firstWaveOnlyScoring || cfg.allowDiagonalMatch

        // ==================== CARD ①: 目标与连击 (Indigo) ====================
        addAccordionGroupCard(0, s(R.string.ov_card_goal_combo), card0Summary, card0Highlight, ACCENT_INDIGO, p)?.let { body ->
            buildGoalAndComboCardBody(body, cfg, byId, activeIds, p)
        }

        // ==================== CARD ②: 属性与珠数 (Teal) ====================
        addAccordionGroupCard(1, s(R.string.ov_card_elem_count), card1Summary, card1Highlight, ACCENT_TEAL, p)?.let { body ->
            buildElementAndCountCardBody(body, cfg, byId, activeIds, p)
        }

        // ==================== CARD ③: 拼图与形状 (Amber) ====================
        addAccordionGroupCard(2, s(R.string.ov_card_puzzle_shape), card2Summary, card2Highlight, ACCENT_AMBER, p)?.let { body ->
            buildPuzzleAndShapeCardBody(body, cfg, byId, activeIds, p)
        }

        // ==================== CARD ④: 路径与地形 (Crimson) ====================
        addAccordionGroupCard(3, s(R.string.ov_card_path_safety), card3Summary, card3Highlight, ACCENT_CRIMSON, p)?.let { body ->
            buildPathAndSafetyCardBody(body, cfg, byId, activeIds, p)
        }

        // ==================== CARD ⑤: 预算与起手 (Blue) ====================
        addAccordionGroupCard(4, s(R.string.ov_card_budget_start), card4Summary, card4Highlight, ACCENT_BLUE, p)?.let { body ->
            buildBudgetAndStartCardBody(body, cfg, activeIds, p)
        }

        // ==================== CARD ⑥: 队长与特性 (Purple) ====================
        addAccordionGroupCard(5, s(R.string.ov_card_leader_traits), card5Summary, card5Highlight, ACCENT_PURPLE, p)?.let { body ->
            buildLeaderAndTraitsCardBody(body, cfg, isHeartAll, isLightDark, isWfg, p)
        }
    }

    private fun buildGoalAndComboCardBody(
        body: LinearLayout,
        cfg: RuleConfig,
        byId: Map<String, WaveShieldChip>,
        activeIds: Set<String>,
        p: OverlayPalette
    ) {
        // Sub-group A: Global Goal N/X
        body.addView(makeSubLabel(s(R.string.section_goal_mode), p))
        val goalGroup = cfg.goalMode.normalizedGroup()
        val curGoalN = if (cfg.goalMode == goalGroup) cfg.goalParamN else cfg.goalMode.defaultParamN()
        val curGoalX = if (cfg.goalMode == goalGroup) cfg.goalParamX else cfg.goalMode.defaultParamX()
        val gModes = listOf(
            StrategyGoalMode.HIGH_COMBO, StrategyGoalMode.N_COLOR_BALANCE,
            StrategyGoalMode.N_COLOR_ALL, StrategyGoalMode.SINGLE_COLOR_X_ORBS, StrategyGoalMode.MULTI_COLOR_X_ORBS
        )
        val gRow1 = hRow(bottomMarginDp = 4)
        gModes.take(3).forEachIndexed { idx, gm ->
            gRow1.addView(makeToggle(LocaleHelper.goalModeLabel(lctx, gm), goalGroup == gm, p, weight = 1f, marginEndDp = if (idx == 2) 0 else 4) {
                onUpdateActivePresetConfig { it.copy(goalMode = gm, goalParamN = curGoalN.coerceIn(2, 5), goalParamX = curGoalX.coerceIn(4, 9), goalElementsMask = 0) }
            })
        }
        body.addView(gRow1)
        val gRow2 = hRow(bottomMarginDp = 4)
        gModes.drop(3).forEachIndexed { idx, gm ->
            gRow2.addView(makeToggle(LocaleHelper.goalModeLabel(lctx, gm), goalGroup == gm, p, weight = 1f, marginEndDp = if (idx == 1) 0 else 4) {
                onUpdateActivePresetConfig { it.copy(goalMode = gm, goalParamN = curGoalN.coerceIn(2, 5), goalParamX = curGoalX.coerceIn(4, 9), goalElementsMask = 0) }
            })
        }
        body.addView(gRow2)
        if (goalGroup == StrategyGoalMode.N_COLOR_BALANCE || goalGroup == StrategyGoalMode.N_COLOR_ALL || goalGroup == StrategyGoalMode.MULTI_COLOR_X_ORBS) {
            val nRange = if (goalGroup == StrategyGoalMode.N_COLOR_ALL) 2..6 else 2..5
            val nRow = hRow(bottomMarginDp = 4)
            nRange.forEach { n ->
                nRow.addView(makeToggle(s(R.string.goal_unit_colors, n), curGoalN == n, p, weight = 1f, marginEndDp = if (n == nRange.last) 0 else 3) {
                    onUpdateActivePresetConfig { it.copy(goalMode = goalGroup, goalParamN = n, goalElementsMask = 0) }
                })
            }
            body.addView(nRow)
        }
        if (goalGroup == StrategyGoalMode.SINGLE_COLOR_X_ORBS || goalGroup == StrategyGoalMode.MULTI_COLOR_X_ORBS) {
            val xRow = hRow(bottomMarginDp = 4)
            for (x in 4..9) {
                xRow.addView(makeToggle(s(R.string.goal_unit_orbs, x), curGoalX == x, p, weight = 1f, marginEndDp = if (x == 9) 0 else 3) {
                    onUpdateActivePresetConfig { it.copy(goalMode = goalGroup, goalParamX = x) }
                })
            }
            body.addView(xRow)
        }
        if (goalGroup == StrategyGoalMode.SINGLE_COLOR_X_ORBS) {
            val tRow = hRow(bottomMarginDp = 4)
            tRow.addView(makeToggle(s(R.string.puzzle_elem_any), cfg.goalTargetElement < 0, p, weight = 1.45f) {
                onUpdateActivePresetConfig { it.copy(goalMode = goalGroup, goalTargetElement = -1) }
            })
            for (e in 0..5) {
                tRow.addView(makeElementBtn(e, cfg.goalTargetElement == e, p, weight = 1f, marginEndDp = if (e == 5) 0 else 3) {
                    onUpdateActivePresetConfig { it.copy(goalMode = goalGroup, goalTargetElement = e) }
                })
            }
            body.addView(tRow)
        }

        // Sub-group B: Combo Shield Primary Rule
        body.addView(makeSubDivider(p))
        body.addView(makeSubLabel(s(R.string.section_combo_shield), p))
        val curComboMode = when {
            "min_combo" in activeIds -> "min_combo"
            "min_first_combo" in activeIds -> "min_first_combo"
            "min_first_elem_combo" in activeIds -> "min_first_elem_combo"
            "exact_combo" in activeIds -> "exact_combo"
            "exact_first_combo" in activeIds -> "exact_first_combo"
            "wave1_same_kind" in activeIds -> "wave1_same_kind"
            "wave1_distinct_kind" in activeIds -> "wave1_distinct_kind"
            "zero_combo" in activeIds -> "zero_combo"
            "odd_combo" in activeIds -> "odd_combo"
            "even_combo" in activeIds -> "even_combo"
            else -> "none"
        }
        fun applyCombo(mode: String) {
            ActiveStrategyStore.removeChip("first_wave_only")
            val chip = when (mode) {
                "min_combo" -> WaveShieldChip("min_combo", "${s(R.string.combo_type_min_total)} ≥$minTotalComboVal", ShieldCategory.COMBO, paramInt = minTotalComboVal)
                "min_first_combo" -> WaveShieldChip("min_first_combo", "${s(R.string.combo_type_min_first)} ≥$minFirstComboVal", ShieldCategory.COMBO, paramInt = minFirstComboVal)
                "min_first_elem_combo" -> WaveShieldChip("min_first_elem_combo", "${s(R.string.combo_type_min_first_elem)} ≥$minFirstElemVal", ShieldCategory.COMBO, paramInt = minFirstElemVal)
                "exact_combo" -> WaveShieldChip("exact_combo", "${s(R.string.combo_type_exact_total)} $exactTotalComboVal", ShieldCategory.COMBO, paramInt = exactTotalComboVal)
                "exact_first_combo" -> WaveShieldChip("exact_first_combo", "${s(R.string.combo_type_exact_first)} $exactFirstComboVal", ShieldCategory.COMBO, paramInt = exactFirstComboVal)
                "wave1_same_kind" -> WaveShieldChip("wave1_same_kind", "${s(R.string.combo_type_wave1_same_kind)} $wave1KindVal", ShieldCategory.COMBO, paramInt = wave1KindVal)
                "wave1_distinct_kind" -> WaveShieldChip("wave1_distinct_kind", "${s(R.string.combo_type_wave1_distinct_kind)} $wave1KindVal", ShieldCategory.COMBO, paramInt = wave1KindVal)
                "zero_combo" -> {
                    listOf(
                        "same_combo_elements", "second_wave_combo", "same_elem_combos", "equal_pair_light_dark",
                        "first_wave_full_clear", "digital_orb_count", "elem_burst_shield",
                        "any_enchanted_orb_shield", "race_orb_shield", "char_orb_first_wave", "luminous_orb_shield"
                    ).forEach { ActiveStrategyStore.removeChip(it) }
                    WaveShieldChip("zero_combo", s(R.string.combo_type_zero), ShieldCategory.COMBO)
                }
                "odd_combo" -> WaveShieldChip("odd_combo", s(R.string.combo_type_odd), ShieldCategory.COMBO, paramInt = if (parityFirstOnly) 0 else 1)
                "even_combo" -> WaveShieldChip("even_combo", s(R.string.combo_type_even), ShieldCategory.COMBO, paramInt = if (parityFirstOnly) 0 else 1)
                else -> null
            }
            ActiveStrategyStore.replaceGroup(WaveShieldCatalog.COMBO_EXCLUSIVE_IDS, chip)
        }
        val cRow1 = hRow(bottomMarginDp = 4)
        listOf("none" to s(R.string.combo_type_none), "min_combo" to s(R.string.combo_type_min_total), "min_first_combo" to s(R.string.combo_type_min_first), "min_first_elem_combo" to s(R.string.combo_type_min_first_elem))
            .forEachIndexed { i, (id, lbl) -> cRow1.addView(makeToggle(lbl, curComboMode == id, p, weight = 1f, marginEndDp = if (i == 3) 0 else 3) { applyCombo(id) }) }
        body.addView(cRow1)
        val cRow2 = hRow(bottomMarginDp = 4)
        listOf("exact_combo" to s(R.string.combo_type_exact_total), "exact_first_combo" to s(R.string.combo_type_exact_first), "wave1_same_kind" to s(R.string.combo_type_wave1_same_kind), "wave1_distinct_kind" to s(R.string.combo_type_wave1_distinct_kind))
            .forEachIndexed { i, (id, lbl) -> cRow2.addView(makeToggle(lbl, curComboMode == id, p, weight = 1f, marginEndDp = if (i == 3) 0 else 3) { applyCombo(id) }) }
        body.addView(cRow2)
        val cRow3 = hRow(bottomMarginDp = 4)
        listOf("zero_combo" to s(R.string.combo_type_zero), "odd_combo" to s(R.string.combo_type_odd), "even_combo" to s(R.string.combo_type_even))
            .forEachIndexed { i, (id, lbl) -> cRow3.addView(makeToggle(lbl, curComboMode == id, p, weight = 1f, marginEndDp = if (i == 2) 0 else 3) { applyCombo(id) }) }
        body.addView(cRow3)

        if (curComboMode in setOf("min_combo", "min_first_combo", "min_first_elem_combo", "exact_combo", "exact_first_combo", "wave1_same_kind", "wave1_distinct_kind")) {
            val activeVal = when (curComboMode) {
                "min_combo" -> minTotalComboVal; "min_first_combo" -> minFirstComboVal
                "min_first_elem_combo" -> minFirstElemVal; "exact_combo" -> exactTotalComboVal
                "exact_first_combo" -> exactFirstComboVal; else -> wave1KindVal
            }
            fun stepC(delta: Int) {
                when (curComboMode) {
                    "min_combo" -> minTotalComboVal = (minTotalComboVal + delta).coerceIn(1, 15)
                    "min_first_combo" -> minFirstComboVal = (minFirstComboVal + delta).coerceIn(1, 12)
                    "min_first_elem_combo" -> minFirstElemVal = (minFirstElemVal + delta).coerceIn(1, 12)
                    "exact_combo" -> exactTotalComboVal = (exactTotalComboVal + delta).coerceIn(1, 15)
                    "exact_first_combo" -> exactFirstComboVal = (exactFirstComboVal + delta).coerceIn(1, 12)
                    "wave1_same_kind", "wave1_distinct_kind" -> wave1KindVal = (wave1KindVal + delta).coerceIn(1, 10)
                }
                applyCombo(curComboMode)
            }
            val valText = if (curComboMode.startsWith("min")) s(R.string.combo_value_min_fmt, activeVal) else s(R.string.combo_value_exact_fmt, activeVal)
            body.addView(makeStepperRow(valText, p, { stepC(-1) }, { stepC(+1) }))
        } else if (curComboMode == "odd_combo" || curComboMode == "even_combo") {
            val pRow = hRow(bottomMarginDp = 4)
            pRow.addView(makeToggle(s(R.string.combo_parity_scope_first), parityFirstOnly, p, weight = 1f) { parityFirstOnly = true; applyCombo(curComboMode) })
            pRow.addView(makeToggle(s(R.string.combo_parity_scope_total), !parityFirstOnly, p, weight = 1f, marginEndDp = 0) { parityFirstOnly = false; applyCombo(curComboMode) })
            body.addView(pRow)
        }

        if (curComboMode != "zero_combo") {
            body.addView(makeSubLabel(s(R.string.combo_extra_header), p))
            val exRow1 = hRow(bottomMarginDp = 4)
            exRow1.addView(makeToggle(s(R.string.combo_extra_same_c), "same_combo_elements" in activeIds, p, weight = 1f) {
                onToggleWaveChip(WaveShieldChip("same_combo_elements", s(R.string.combo_extra_same_c), ShieldCategory.COMBO))
            })
            exRow1.addView(makeToggle(s(R.string.shield_no_cascade), "no_cascade" in activeIds, p, weight = 1f) {
                onToggleWaveChip(WaveShieldChip("no_cascade", s(R.string.shield_no_cascade), ShieldCategory.COMBO))
            })
            exRow1.addView(makeToggle(s(R.string.combo_extra_second_wave, secondWaveVal), "second_wave_combo" in activeIds, p, weight = 1f, marginEndDp = 0) {
                onToggleWaveChip(WaveShieldChip("second_wave_combo", s(R.string.combo_extra_second_wave, secondWaveVal), ShieldCategory.COMBO, paramInt = secondWaveVal))
            })
            body.addView(exRow1)
            val exRow2 = hRow(bottomMarginDp = 4)
            val sameMask = if (sameElemTarget in 0..5) (1 shl sameElemTarget) else 0
            exRow2.addView(makeToggle(s(R.string.combo_extra_same_elem, sameElemGroupsVal), "same_elem_combos" in activeIds, p, weight = 1f) {
                onToggleWaveChip(WaveShieldChip("same_elem_combos", s(R.string.combo_extra_same_elem, sameElemGroupsVal), ShieldCategory.COMBO, paramInt = sameElemGroupsVal, paramElementMask = sameMask))
            })
            val eqJoined = (0..5).filter { (equalElemsMask and (1 shl it)) != 0 }.joinToString("=") { LocaleHelper.elementLabel(lctx, it) }
            val eqLbl = s(R.string.combo_extra_equal_multi_fmt, eqJoined)
            exRow2.addView(makeToggle(eqLbl, "equal_pair_light_dark" in activeIds, p, weight = 1f, marginEndDp = 0) {
                onToggleWaveChip(WaveShieldChip("equal_pair_light_dark", eqLbl, ShieldCategory.COMBO, paramElementMask = 0x100 or equalElemsMask))
            })
            body.addView(exRow2)
            if ("equal_pair_light_dark" in activeIds) {
                val eqRow = hRow(bottomMarginDp = 4)
                for (e in 0..5) {
                    val bit = 1 shl e
                    eqRow.addView(makeElementBtn(e, (equalElemsMask and bit) != 0, p, weight = 1f, marginEndDp = if (e == 5) 0 else 3) {
                        val next = equalElemsMask xor bit
                        if (Integer.bitCount(next) >= 2) {
                            equalElemsMask = next
                            val joined = (0..5).filter { (next and (1 shl it)) != 0 }.joinToString("=") { LocaleHelper.elementLabel(lctx, it) }
                            val lbl = s(R.string.combo_extra_equal_multi_fmt, joined)
                            onUpsertWaveChip(WaveShieldChip("equal_pair_light_dark", lbl, ShieldCategory.COMBO, paramElementMask = 0x100 or next))
                        }
                    })
                }
                body.addView(eqRow)
            }
        }

        // Sub-group C: Multi-Orb Policy & Search Tendency
        body.addView(makeSubDivider(p))
        body.addView(makeSubLabel(s(R.string.section_match_policy), p))
        val polRow1 = hRow(bottomMarginDp = 4)
        listOf(FourPlusPolicy.MAX_COMBO to s(R.string.policy_max_combo), FourPlusPolicy.MAX_ORBS to s(R.string.policy_max_orbs), FourPlusPolicy.SINGLE_GROUP to s(R.string.policy_single_group))
            .forEachIndexed { idx, (pol, lbl) ->
                polRow1.addView(makeToggle(lbl, cfg.fourPlusPolicy == pol, p, weight = 1f, marginEndDp = if (idx == 2) 0 else 4) {
                    onUpdateActivePresetConfig { it.copy(fourPlusPolicy = pol) }
                })
            }
        body.addView(polRow1)
        val polRow2 = hRow(bottomMarginDp = 0)
        polRow2.addView(makeToggle(s(R.string.policy_not_enough_keep), cfg.notEnoughPolicy == NotEnoughPolicy.KEEP, p, weight = 1.1f) {
            onUpdateActivePresetConfig { it.copy(notEnoughPolicy = if (it.notEnoughPolicy == NotEnoughPolicy.KEEP) NotEnoughPolicy.MATCH_ANYWAY else NotEnoughPolicy.KEEP) }
        })
        polRow2.addView(makeToggle(s(R.string.policy_best_result), !cfg.earlyStopOnGoal, p, weight = 0.85f) {
            onUpdateActivePresetConfig { it.copy(earlyStopOnGoal = false) }
        })
        polRow2.addView(makeToggle(s(R.string.policy_first_result), cfg.earlyStopOnGoal, p, weight = 0.85f, marginEndDp = 0) {
            onUpdateActivePresetConfig { it.copy(earlyStopOnGoal = true) }
        })
        body.addView(polRow2)
    }

    private fun buildElementAndCountCardBody(
        body: LinearLayout,
        cfg: RuleConfig,
        byId: Map<String, WaveShieldChip>,
        activeIds: Set<String>,
        p: OverlayPalette
    ) {
        val legacyReqIds = buildSet {
            add("require_elements"); add("three_elements"); add("five_elements"); add("five_elements_enchanted")
            for (e in 0..5) { add("require_elem_$e"); add("require_ench_$e") }
        }
        fun readMask(id: String): Int {
            var m = byId[id]?.paramElementMask ?: 0
            for (e in 0..5) if ("${id}_$e" in activeIds) m = m or (1 shl e)
            return m
        }
        fun writeLimit(id: String, prefix: String, m: Int) {
            val lset = buildSet { add(id); for (i in 0..5) add("${id}_$i") }
            if (m == 0) ActiveStrategyStore.replaceGroup(lset, null)
            else {
                val names = (0..5).filter { (m and (1 shl it)) != 0 }.joinToString("") { LocaleHelper.elementLabel(lctx, it) }
                ActiveStrategyStore.replaceGroup(lset, WaveShieldChip(id, "$prefix:$names", ShieldCategory.COUNT_AND_ELEMENT, paramElementMask = m))
            }
        }
        fun writeReq(reqMask: Int, enchMask: Int) {
            val cReq = reqMask and 0x3F
            val cEnch = enchMask and cReq
            if (cReq == 0) ActiveStrategyStore.replaceGroup(legacyReqIds, null)
            else {
                val names = (0..5).filter { (cReq and (1 shl it)) != 0 }.joinToString("") { idx ->
                    val n = LocaleHelper.elementLabel(lctx, idx)
                    if ((cEnch and (1 shl idx)) != 0) "$n+" else n
                }
                ActiveStrategyStore.replaceGroup(
                    legacyReqIds,
                    WaveShieldChip("require_elements", names, ShieldCategory.COUNT_AND_ELEMENT, paramInt = if (cEnch == cReq && cEnch != 0) 1 else 0, paramElementMask = cReq or (cEnch shl 8))
                )
            }
        }

        // Sub-group A: Min Distinct + Required + Per-Color Enchanted
        body.addView(makeSubLabel(s(R.string.elem_min_distinct_label), p))
        val curMinDist = byId["min_distinct_elements"]?.paramInt ?: 0
        val distRow = hRow(bottomMarginDp = 4)
        listOf(0, 2, 3, 4, 5, 6).forEachIndexed { idx, cnt ->
            val lbl = if (cnt == 0) s(R.string.elem_min_distinct_any) else s(R.string.elem_min_distinct_fmt, cnt)
            distRow.addView(makeToggle(lbl, curMinDist == cnt, p, weight = 1f, marginEndDp = if (idx == 5) 0 else 3) {
                if (curMinDist != cnt) ActiveStrategyStore.replaceGroup(legacyReqIds, null)
                if (cnt == 0) ActiveStrategyStore.removeChip("min_distinct_elements")
                else onUpsertWaveChip(WaveShieldChip("min_distinct_elements", s(R.string.elem_min_distinct_fmt, cnt), ShieldCategory.COUNT_AND_ELEMENT, paramInt = cnt))
            })
        }
        body.addView(distRow)

        val rawReq = byId["require_elements"]?.paramElementMask ?: 0
        var effReq = rawReq and 0x3F
        if ("three_elements" in activeIds) effReq = effReq or 0x07
        if ("five_elements" in activeIds || "five_elements_enchanted" in activeIds) effReq = effReq or 0x1F
        val perEnch = (rawReq ushr 8) and 0x3F
        var effEnch = if (perEnch != 0) perEnch else if ((byId["require_elements"]?.paramInt ?: 0) == 1) effReq else 0
        if ("five_elements_enchanted" in activeIds) effEnch = effEnch or 0x1F
        effEnch = effEnch and effReq

        val antiMask = readMask("anti_first_wave")
        val forbidMask = readMask("forbid_all")
        val clearMask = readMask("clear_all_element")
        val unmatchMask = readMask("unmatchable_elements")

        body.addView(makeSubLabel("${s(R.string.elem_req_label)} / ${s(R.string.elem_req_ench_row)}", p))
        val reqRow = hRow(bottomMarginDp = 3)
        for (e in 0..5) {
            val bit = 1 shl e
            reqRow.addView(makeElementBtn(e, (effReq and bit) != 0, p, weight = 1f, marginEndDp = if (e == 5) 0 else 3) {
                val nReq = effReq xor bit
                val adding = (nReq and bit) != 0
                val nEnch = if (adding) effEnch else (effEnch and bit.inv())
                if (adding && (forbidMask and bit) != 0) writeLimit("forbid_all", s(R.string.elem_limit_forbid), forbidMask and bit.inv())
                writeReq(nReq, nEnch)
            })
        }
        body.addView(reqRow)
        val enchRow = hRow(bottomMarginDp = 4)
        for (e in 0..5) {
            val bit = 1 shl e
            val on = (effEnch and bit) != 0
            enchRow.addView(makeElementBtn(e, on, p, suffix = "+", weight = 1f, marginEndDp = if (e == 5) 0 else 3) {
                val turningOn = !on
                val nReq = if (turningOn) (effReq or bit) else effReq
                val nEnch = if (turningOn) (effEnch or bit) else (effEnch and bit.inv())
                if (turningOn && (forbidMask and bit) != 0) writeLimit("forbid_all", s(R.string.elem_limit_forbid), forbidMask and bit.inv())
                writeReq(nReq, nEnch)
            })
        }
        body.addView(enchRow)

        // Sub-group B: Single-Color Burst / Cumulative Orb Shield (ICON1255..1259, 1502)
        body.addView(makeSubDivider(p))
        body.addView(makeSubLabel(s(R.string.elem_burst_label), p))
        val burstMask = (byId["elem_burst_shield"]?.paramElementMask ?: 0) and 0x3F
        fun saveBurst(mask: Int, orbs: Int) {
            if (mask == 0) ActiveStrategyStore.removeChip("elem_burst_shield")
            else {
                ActiveStrategyStore.removeChip("zero_combo")
                val names = (0..5).filter { (mask and (1 shl it)) != 0 }.joinToString("") { LocaleHelper.elementLabel(lctx, it) }
                onUpsertWaveChip(WaveShieldChip("elem_burst_shield", s(R.string.elem_burst_fmt, names, orbs), ShieldCategory.COUNT_AND_ELEMENT, paramInt = orbs, paramElementMask = mask))
            }
        }
        val bRow = hRow(bottomMarginDp = if (burstMask != 0) 4 else 2)
        for (e in 0..5) {
            val bit = 1 shl e
            bRow.addView(makeElementBtn(e, (burstMask and bit) != 0, p, weight = 1f, marginEndDp = if (e == 5) 0 else 3) {
                saveBurst(burstMask xor bit, burstOrbsVal)
            })
        }
        body.addView(bRow)
        if (burstMask != 0) {
            body.addView(makeStepperRow("≥ $burstOrbsVal ${s(R.string.unit_orbs)}", p, {
                burstOrbsVal = (burstOrbsVal - 1).coerceIn(3, 15); saveBurst(burstMask, burstOrbsVal)
            }, {
                burstOrbsVal = (burstOrbsVal + 1).coerceIn(3, 15); saveBurst(burstMask, burstOrbsVal)
            }))
        }

        // Sub-group C: Attribute Limits (Anti-1st / Forbid All / Clear All 1st / Unmatchable #23)
        body.addView(makeSubDivider(p))
        body.addView(makeSubLabel(s(R.string.elem_forbid_label), p))
        val limTabs = hRow(bottomMarginDp = 3)
        listOf(s(R.string.elem_limit_anti), s(R.string.elem_limit_forbid), s(R.string.elem_limit_clear_all), s(R.string.elem_limit_unmatchable)).forEachIndexed { idx, lbl ->
            limTabs.addView(makeToggle(lbl, elemLimitMode == idx, p, weight = 1f, marginEndDp = if (idx == 3) 0 else 3) {
                elemLimitMode = idx; rebuildContent(preserveScroll = true)
            })
        }
        body.addView(limTabs)
        val curLimMask = when (elemLimitMode) { 0 -> antiMask; 1 -> forbidMask; 2 -> clearMask; else -> unmatchMask }
        val limRow = hRow(bottomMarginDp = 4)
        for (e in 0..5) {
            val bit = 1 shl e
            limRow.addView(makeElementBtn(e, (curLimMask and bit) != 0, p, weight = 1f, marginEndDp = if (e == 5) 0 else 3) {
                val nxt = curLimMask xor bit
                val on = (nxt and bit) != 0
                when (elemLimitMode) {
                    0 -> {
                        writeLimit("anti_first_wave", s(R.string.elem_limit_anti), nxt)
                        if (on) {
                            if ((forbidMask and bit) != 0) writeLimit("forbid_all", s(R.string.elem_limit_forbid), forbidMask and bit.inv())
                            if ((clearMask and bit) != 0) writeLimit("clear_all_element", s(R.string.elem_limit_clear_all), clearMask and bit.inv())
                        }
                    }
                    1 -> {
                        writeLimit("forbid_all", s(R.string.elem_limit_forbid), nxt)
                        if (on) {
                            if ((antiMask and bit) != 0) writeLimit("anti_first_wave", s(R.string.elem_limit_anti), antiMask and bit.inv())
                            if ((clearMask and bit) != 0) writeLimit("clear_all_element", s(R.string.elem_limit_clear_all), clearMask and bit.inv())
                            if ((effReq and bit) != 0) writeReq(effReq and bit.inv(), effEnch and bit.inv())
                        }
                    }
                    2 -> {
                        writeLimit("clear_all_element", s(R.string.elem_limit_clear_all), nxt)
                        if (on) {
                            if ((antiMask and bit) != 0) writeLimit("anti_first_wave", s(R.string.elem_limit_anti), antiMask and bit.inv())
                            if ((forbidMask and bit) != 0) writeLimit("forbid_all", s(R.string.elem_limit_forbid), forbidMask and bit.inv())
                        }
                    }
                    else -> writeLimit("unmatchable_elements", s(R.string.elem_limit_unmatchable), nxt)
                }
            })
        }
        body.addView(limRow)

        // Sub-group D: Orb Count & Special Runestones
        body.addView(makeSubDivider(p))
        body.addView(makeSubLabel(s(R.string.elem_count_label), p))
        val cntRow1 = hRow(bottomMarginDp = 4)
        cntRow1.addView(makeChip("−", padH = 6) {
            digitalOrbsVal = (digitalOrbsVal - 1).coerceIn(6, 30)
            ActiveStrategyStore.removeChip("first_wave_full_clear"); ActiveStrategyStore.removeChip("zero_combo")
            onUpsertWaveChip(WaveShieldChip("digital_orb_count", s(R.string.shield_digital_orbs_fmt, digitalOrbsVal), ShieldCategory.COUNT_AND_ELEMENT, paramInt = digitalOrbsVal))
        }.also { styleChip(it, p.chipOffBg, p.onSurface, p.chipOffStroke) })
        cntRow1.addView(makeToggle(s(R.string.shield_digital_orbs_fmt, digitalOrbsVal), "digital_orb_count" in activeIds, p, weight = 1f) {
            if ("digital_orb_count" in activeIds) ActiveStrategyStore.removeChip("digital_orb_count")
            else {
                ActiveStrategyStore.removeChip("first_wave_full_clear"); ActiveStrategyStore.removeChip("zero_combo")
                onUpsertWaveChip(WaveShieldChip("digital_orb_count", s(R.string.shield_digital_orbs_fmt, digitalOrbsVal), ShieldCategory.COUNT_AND_ELEMENT, paramInt = digitalOrbsVal))
            }
        })
        cntRow1.addView(makeChip("+", padH = 6) {
            digitalOrbsVal = (digitalOrbsVal + 1).coerceIn(6, 30)
            ActiveStrategyStore.removeChip("first_wave_full_clear"); ActiveStrategyStore.removeChip("zero_combo")
            onUpsertWaveChip(WaveShieldChip("digital_orb_count", s(R.string.shield_digital_orbs_fmt, digitalOrbsVal), ShieldCategory.COUNT_AND_ELEMENT, paramInt = digitalOrbsVal))
        }.also { styleChip(it, p.chipOffBg, p.onSurface, p.chipOffStroke) })
        cntRow1.addView(makeToggle(s(R.string.shield_full_clear), "first_wave_full_clear" in activeIds, p, weight = 1f, marginEndDp = 0) {
            if ("first_wave_full_clear" in activeIds) ActiveStrategyStore.removeChip("first_wave_full_clear")
            else {
                ActiveStrategyStore.removeChip("digital_orb_count"); ActiveStrategyStore.removeChip("zero_combo")
                onUpsertWaveChip(WaveShieldChip("first_wave_full_clear", s(R.string.shield_full_clear), ShieldCategory.COUNT_AND_ELEMENT))
            }
        })
        body.addView(cntRow1)

        val spRow1 = hRow(bottomMarginDp = 4)
        spRow1.addView(makeToggle(s(R.string.shield_any_enchanted_orb), "any_enchanted_orb_shield" in activeIds, p, weight = 1f) {
            onToggleWaveChip(WaveShieldChip("any_enchanted_orb_shield", s(R.string.shield_any_enchanted_orb), ShieldCategory.COUNT_AND_ELEMENT))
        })
        spRow1.addView(makeToggle(s(R.string.shield_race_orb), "race_orb_shield" in activeIds, p, weight = 1f) {
            onToggleWaveChip(WaveShieldChip("race_orb_shield", s(R.string.shield_race_orb), ShieldCategory.COUNT_AND_ELEMENT))
        })
        spRow1.addView(makeToggle(s(R.string.shield_char_orb_first), "char_orb_first_wave" in activeIds, p, weight = 1f, marginEndDp = 0) {
            onToggleWaveChip(WaveShieldChip("char_orb_first_wave", s(R.string.shield_char_orb_first), ShieldCategory.COUNT_AND_ELEMENT))
        })
        body.addView(spRow1)

        val spRow2 = hRow(bottomMarginDp = 4)
        spRow2.addView(makeToggle(s(R.string.shield_forbid_weakened), "forbid_weakened_orb" in activeIds, p, weight = 1f) {
            onToggleWaveChip(WaveShieldChip("forbid_weakened_orb", s(R.string.shield_forbid_weakened), ShieldCategory.COUNT_AND_ELEMENT))
        })
        spRow2.addView(makeToggle(s(R.string.shield_luminous_orb), "luminous_orb_shield" in activeIds, p, weight = 1f, marginEndDp = 0) {
            onToggleWaveChip(WaveShieldChip("luminous_orb_shield", s(R.string.shield_luminous_orb), ShieldCategory.COUNT_AND_ELEMENT))
        })
        body.addView(spRow2)

        if ("race_orb_shield" in activeIds) {
            val curRaceMask = (byId["race_orb_shield"]?.paramInt ?: 0) and 0x7F
            body.addView(makeSubLabel(s(R.string.race_select_label), p))
            val raceRow = hRow(bottomMarginDp = 4)
            raceRow.addView(makeToggle(s(R.string.race_any), curRaceMask == 0, p, weight = 1.25f, marginEndDp = 3) {
                onUpsertWaveChip(WaveShieldChip("race_orb_shield", s(R.string.shield_race_orb), ShieldCategory.COUNT_AND_ELEMENT, paramInt = 0))
            })
            RaceType.ALL_RACES.forEachIndexed { idx, race ->
                val sel = (curRaceMask and race.bitMask) != 0
                raceRow.addView(
                    makeChip(
                        LocaleHelper.raceShortLabel(lctx, race),
                        padH = 2,
                        weight = 1f,
                        marginEndDp = if (idx == RaceType.ALL_RACES.lastIndex) 0 else 3
                    ) {
                        val nextMask = curRaceMask xor race.bitMask
                        onUpsertWaveChip(
                            WaveShieldChip("race_orb_shield", s(R.string.shield_race_orb), ShieldCategory.COUNT_AND_ELEMENT, paramInt = nextMask)
                        )
                    }.also {
                        if (sel) styleChip(it, ACCENT_PURPLE, Color.WHITE, ACCENT_PURPLE)
                        else styleChip(it, p.chipOffBg, p.chipOffText, p.chipOffStroke)
                    }
                )
            }
            body.addView(raceRow)
        }

        // Sub-group E: 6x4 Per-Element Rules Matrix
        body.addView(makeSubDivider(p))
        body.addView(makeSubLabel(s(R.string.section_elem_matrix), p))
        val hdrRow = hRow(bottomMarginDp = 3)
        listOf(
            s(R.string.col_hdr_elem) to 0.65f, s(R.string.col_hdr_shape) to 1.05f,
            s(R.string.col_hdr_priority) to 0.9f, s(R.string.col_hdr_timing) to 0.9f, s(R.string.col_hdr_min) to 0.8f
        ).forEach { (title, w) ->
            hdrRow.addView(TextView(context).apply {
                text = title; textSize = 9f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                setTextColor(p.onSurfaceVariant)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w)
            })
        }
        body.addView(hdrRow)

        val shapeCycle = listOf(
            TargetShape.ANY, TargetShape.COUNT_2, TargetShape.COUNT_4, TargetShape.COUNT_5,
            TargetShape.ROW, TargetShape.COUNT_6, TargetShape.COUNT_7, TargetShape.COUNT_8,
            TargetShape.COUNT_9, TargetShape.CLEAR_ALL
        )
        for (e in 0..5) {
            val er = cfg.elementRules.getOrElse(e) { ElementRule() }
            val row = hRow(bottomMarginDp = if (e == 5) 0 else 3)
            row.addView(makeElementBtn(e, true, p, weight = 0.65f, marginEndDp = 4) {})
            row.addView(makeToggle(LocaleHelper.targetShapeLabel(lctx, er.targetShape), er.targetShape != TargetShape.ANY, p, weight = 1.05f) {
                onUpdateActivePresetConfig { cur ->
                    val nxt = shapeCycle[(shapeCycle.indexOf(er.targetShape) + 1).coerceAtLeast(0) % shapeCycle.size]
                    cur.copy(elementRules = cur.elementRules.toMutableList().also { it[e] = er.copy(targetShape = nxt) })
                }
            })
            row.addView(makeToggle(LocaleHelper.elementPriorityLabel(lctx, er.priority), er.priority != ElementPriority.NORMAL, p, weight = 0.9f) {
                onUpdateActivePresetConfig { cur ->
                    val nxt = when (er.priority) { ElementPriority.NORMAL -> ElementPriority.HIGH; ElementPriority.HIGH -> ElementPriority.RESERVE; ElementPriority.RESERVE -> ElementPriority.NORMAL }
                    cur.copy(elementRules = cur.elementRules.toMutableList().also { it[e] = er.copy(priority = nxt) })
                }
            })
            row.addView(makeToggle(LocaleHelper.matchTimingLabel(lctx, er.timing), er.timing != MatchTiming.ANY, p, weight = 0.9f) {
                onUpdateActivePresetConfig { cur ->
                    val nxt = when (er.timing) { MatchTiming.ANY -> MatchTiming.MUST_FIRST_WAVE; MatchTiming.MUST_FIRST_WAVE -> MatchTiming.FORBID_FIRST_WAVE_ALLOW_CASCADE; MatchTiming.FORBID_FIRST_WAVE_ALLOW_CASCADE -> MatchTiming.FORBID_ALL; MatchTiming.FORBID_ALL -> MatchTiming.ANY }
                    cur.copy(elementRules = cur.elementRules.toMutableList().also { it[e] = er.copy(timing = nxt) })
                }
            })
            row.addView(makeToggle(s(R.string.min_match_fmt, er.minMatchCount), er.minMatchCount != 3, p, weight = 0.8f, marginEndDp = 0) {
                onUpdateActivePresetConfig { cur ->
                    val nxt = when (er.minMatchCount) { 3 -> 2; 2 -> 4; else -> 3 }
                    cur.copy(elementRules = cur.elementRules.toMutableList().also { it[e] = er.copy(minMatchCount = nxt) })
                }
            })
            body.addView(row)
        }
    }

    private fun buildPuzzleAndShapeCardBody(
        body: LinearLayout,
        cfg: RuleConfig,
        byId: Map<String, WaveShieldChip>,
        activeIds: Set<String>,
        p: OverlayPalette
    ) {
        // Sub-group A: Puzzle Shield
        body.addView(makeSubLabel(s(R.string.puzzle_mode_label), p))
        val pChip = byId["puzzle_shield"]
        val pMode = when {
            pChip != null -> if ((pChip.paramInt and 1) != 0) 2 else 1
            "strict_puzzle" in activeIds || "enchanted_puzzle" in activeIds -> 2
            "loose_puzzle" in activeIds -> 1
            else -> 0
        }
        val pEnch = if (pChip != null) (pChip.paramInt and 2) != 0 else ("enchanted_puzzle" in activeIds)
        val pElemEnc = if (pChip != null) (pChip.paramInt ushr 2) and 0x0F else 0
        val pElem = if (pElemEnc in 1..6) pElemEnc - 1 else -1
        val pShape = if (pChip != null) (pChip.paramInt ushr 8) and 0x0F else 0
        fun savePuz(mode: Int, ench: Boolean, elem: Int, shape: Int) {
            if (mode == 0) { ActiveStrategyStore.replaceGroup(WaveShieldCatalog.PUZZLE_EXCLUSIVE_IDS, null); return }
            val packed = (if (mode == 2) 1 else 0) or (if (ench) 2 else 0) or (if (elem in 0..5) ((elem + 1) shl 2) else 0) or ((shape and 0x0F) shl 8)
            ActiveStrategyStore.replaceGroup(WaveShieldCatalog.PUZZLE_EXCLUSIVE_IDS, WaveShieldChip("puzzle_shield", s(if (mode == 2) R.string.puzzle_mode_strict else R.string.puzzle_mode_normal), ShieldCategory.SHAPE_AND_PUZZLE, paramInt = packed))
        }
        val pzRow = hRow(bottomMarginDp = 4)
        pzRow.addView(makeToggle(s(R.string.puzzle_mode_off), pMode == 0, p, weight = 0.8f) { savePuz(0, false, -1, 0) })
        pzRow.addView(makeToggle(s(R.string.puzzle_mode_normal), pMode == 1, p, weight = 1f) { savePuz(1, pEnch, pElem, pShape) })
        pzRow.addView(makeToggle(s(R.string.puzzle_mode_strict), pMode == 2, p, weight = 1f, marginEndDp = if (pMode > 0) 4 else 0) { savePuz(2, pEnch, pElem, pShape) })
        if (pMode > 0) {
            pzRow.addView(makeToggle(s(R.string.ov_puzzle_ench), pEnch, p, weight = 0.9f, marginEndDp = 0) { savePuz(pMode, !pEnch, pElem, pShape) })
        }
        body.addView(pzRow)
        if (pMode > 0) {
            val pzColRow = hRow(bottomMarginDp = 4)
            pzColRow.addView(makeToggle(s(R.string.puzzle_elem_any), pElem < 0, p, weight = 1.45f) { savePuz(pMode, pEnch, -1, pShape) })
            for (e in 0..5) {
                pzColRow.addView(makeElementBtn(e, pElem == e, p, weight = 1f, marginEndDp = if (e == 5) 0 else 3) { savePuz(pMode, pEnch, e, pShape) })
            }
            body.addView(pzColRow)
        }

        // Sub-group B: Independent Shape Shields
        body.addView(makeSubDivider(p))
        body.addView(makeSubLabel(s(R.string.shape_shield_label), p))
        val crossChip = byId["cross_shield"] ?: byId["cross_shield_2"] ?: byId["cross_shield_3"]
        val cCnt = (crossChip?.paramInt ?: 0) and 0x0F
        val cDist = ((crossChip?.paramInt ?: 0) and 0x20) != 0
        val cColors = (crossChip?.paramElementMask ?: 0) and 0x3F
        fun saveCross(cnt: Int, dist: Boolean, cols: Int) {
            if (cnt <= 0) { ActiveStrategyStore.replaceGroup(WaveShieldCatalog.CROSS_EXCLUSIVE_IDS, null); return }
            val safeCols = if (Integer.bitCount(cols) > cnt) 0 else cols
            val safeDist = (dist && cnt >= 2) || Integer.bitCount(safeCols) >= 2
            val packed = cnt or (if (safeDist) 0x20 else 0)
            ActiveStrategyStore.replaceGroup(WaveShieldCatalog.CROSS_EXCLUSIVE_IDS, WaveShieldChip("cross_shield", s(R.string.ov_cross_fmt, cnt), ShieldCategory.SHAPE_AND_PUZZLE, paramInt = packed, paramElementMask = safeCols))
        }
        val crRow = hRow(bottomMarginDp = 4)
        listOf(0 to s(R.string.cross_off), 1 to s(R.string.cross_1), 2 to s(R.string.cross_2), 3 to s(R.string.cross_3)).forEachIndexed { i, (n, lbl) ->
            crRow.addView(makeToggle(lbl, cCnt == n, p, weight = 1f, marginEndDp = if (i == 3) 0 else 3) { saveCross(n, cDist, cColors) })
        }
        body.addView(crRow)
        if (cCnt > 0) {
            if (cCnt >= 2) {
                val cdRow = hRow(bottomMarginDp = 3)
                cdRow.addView(makeToggle(s(R.string.cross_distinct_switch), cDist, p, weight = 1f, marginEndDp = 0) { saveCross(cCnt, !cDist, cColors) })
                body.addView(cdRow)
            }
            val ccRow = hRow(bottomMarginDp = 4)
            for (e in 0..5) {
                val bit = 1 shl e
                ccRow.addView(makeElementBtn(e, (cColors and bit) != 0, p, weight = 1f, marginEndDp = if (e == 5) 0 else 3) {
                    val nxt = cColors xor bit
                    if ((nxt and bit) == 0 || Integer.bitCount(nxt) <= cCnt) saveCross(cCnt, cDist, nxt)
                })
            }
            body.addView(ccRow)
        }
        val shRow = hRow(bottomMarginDp = 4)
        shRow.addView(makeToggle(s(R.string.shield_l_shape), "l_shape" in activeIds, p, weight = 1f) { onToggleWaveChip(WaveShieldChip("l_shape", s(R.string.shield_l_shape), ShieldCategory.SHAPE_AND_PUZZLE)) })
        shRow.addView(makeToggle(s(R.string.shield_t_shape), "t_shape" in activeIds, p, weight = 1f) { onToggleWaveChip(WaveShieldChip("t_shape", s(R.string.shield_t_shape), ShieldCategory.SHAPE_AND_PUZZLE)) })
        shRow.addView(makeToggle(s(R.string.shield_sq_2x2), "square_2x2" in activeIds, p, weight = 1f) { onToggleWaveChip(WaveShieldChip("square_2x2", s(R.string.shield_sq_2x2), ShieldCategory.SHAPE_AND_PUZZLE)) })
        shRow.addView(makeToggle(s(R.string.shield_all_cols), "all_columns_ameliorate" in activeIds, p, weight = 1.2f, marginEndDp = 0) { onToggleWaveChip(WaveShieldChip("all_columns_ameliorate", s(R.string.shield_all_cols), ShieldCategory.SHAPE_AND_PUZZLE)) })
        body.addView(shRow)

        // Sub-group C: Column Amelioration 1..6
        body.addView(makeSubDivider(p))
        body.addView(makeSubLabel(s(R.string.section_columns), p))
        for (rowIdx in 0..1) {
            val cRow = hRow(bottomMarginDp = if (rowIdx == 1) 0 else 4)
            for (offset in 0..2) {
                val c = rowIdx * 3 + offset
                val rule = cfg.columnRules.getOrElse(c) { ColumnAmeliorationRule() }
                val lbl = if (!rule.enabled) s(R.string.col_off_fmt, c + 1) else s(R.string.col_on_fmt, c + 1, rule.minOrbs)
                cRow.addView(makeToggle(lbl, rule.enabled, p, weight = 1f, marginEndDp = if (offset == 2) 0 else 4) {
                    onUpdateActivePresetConfig { cur ->
                        val nxt = when {
                            !rule.enabled -> rule.copy(enabled = true, minOrbs = 3)
                            rule.minOrbs == 3 -> rule.copy(enabled = true, minOrbs = 4)
                            rule.minOrbs == 4 -> rule.copy(enabled = true, minOrbs = 5)
                            else -> rule.copy(enabled = false, minOrbs = 4)
                        }
                        cur.copy(columnRules = cur.columnRules.toMutableList().also { it[c] = nxt })
                    }
                })
            }
            body.addView(cRow)
        }
    }

    private fun buildPathAndSafetyCardBody(
        body: LinearLayout,
        cfg: RuleConfig,
        byId: Map<String, WaveShieldChip>,
        activeIds: Set<String>,
        p: OverlayPalette
    ) {
        val burnChip = byId["zero_trail_burning"]
        val burnOn = burnChip != null
        val burnLen = (burnChip?.paramInt ?: 0) and 0xFF
        val burnHurts = ((burnChip?.paramInt ?: 0) ushr 8) and 0xFF
        val ptRow1 = hRow(bottomMarginDp = 4)
        ptRow1.addView(makeToggle(s(R.string.ov_burn_path), burnOn, p, weight = 1.1f, marginEndDp = if (burnOn) 4 else 0) {
            if (burnOn) ActiveStrategyStore.removeChip("zero_trail_burning")
            else onUpsertWaveChip(WaveShieldChip("zero_trail_burning", s(R.string.burn_path_title), ShieldCategory.HAZARD_AND_PATH, paramInt = 0))
        })
        if (burnOn) {
            ptRow1.addView(makeChip(s(if (burnLen == 6) R.string.ov_burn_len_6 else R.string.ov_burn_len_max), weight = 0.9f) {
                val nLen = if (burnLen == 6) 0 else 6
                onUpsertWaveChip(WaveShieldChip("zero_trail_burning", s(R.string.burn_path_title), ShieldCategory.HAZARD_AND_PATH, paramInt = nLen or (burnHurts shl 8)))
            }.also { styleChip(it, p.secondaryContainer, p.onSecondaryContainer, null) })
            ptRow1.addView(makeChip(s(R.string.ov_burn_hurts_fmt, burnHurts), weight = 0.8f, marginEndDp = 0) {
                val nH = (burnHurts + 1) % 6
                onUpsertWaveChip(WaveShieldChip("zero_trail_burning", s(R.string.burn_path_title), ShieldCategory.HAZARD_AND_PATH, paramInt = burnLen or (nH shl 8)))
            }.also { styleChip(it, p.secondaryContainer, p.onSecondaryContainer, null) })
        }
        body.addView(ptRow1)

        body.addView(makeSubDivider(p))
        val ptRowTl = hRow(bottomMarginDp = 4)
        ptRowTl.addView(makeToggle(s(R.string.shield_traffic_light), "traffic_light" in activeIds, p, weight = 1f, marginEndDp = 0) {
            onToggleWaveChip(WaveShieldChip("traffic_light", s(R.string.shield_traffic_light), ShieldCategory.HAZARD_AND_PATH))
        })
        body.addView(ptRowTl)
        val ptRow2 = hRow(bottomMarginDp = 4)
        ptRow2.addView(makeToggle(s(R.string.shield_trail_corrosion), "trail_corrosion" in activeIds, p, weight = 1f) {
            onToggleWaveChip(WaveShieldChip("trail_corrosion", s(R.string.shield_trail_corrosion), ShieldCategory.HAZARD_AND_PATH, paramInt = 15))
        })
        ptRow2.addView(makeToggle(s(R.string.shield_step_hurt), "step_hurt_minimize" in activeIds, p, weight = 1f, marginEndDp = 0) {
            onToggleWaveChip(WaveShieldChip("step_hurt_minimize", s(R.string.shield_step_hurt), ShieldCategory.HAZARD_AND_PATH))
        })
        body.addView(ptRow2)

        val ptRow3 = hRow(bottomMarginDp = 0)
        ptRow3.addView(makeToggle(s(R.string.shield_char_terrain), "char_orb_terrain" in activeIds, p, weight = 1f) {
            onToggleWaveChip(WaveShieldChip("char_orb_terrain", s(R.string.shield_char_terrain), ShieldCategory.HAZARD_AND_PATH))
        })
        ptRow3.addView(makeToggle(s(R.string.shield_weijian_burst), "weijian_burst" in activeIds, p, weight = 1f) {
            onToggleWaveChip(WaveShieldChip("weijian_burst", s(R.string.shield_weijian_burst), ShieldCategory.HAZARD_AND_PATH))
        })
        ptRow3.addView(makeToggle(s(R.string.switch_hit_weathered), cfg.allowHitWeatheredToClear, p, weight = 1f, marginEndDp = 0) {
            onUpdateActivePresetConfig { it.copy(allowHitWeatheredToClear = !it.allowHitWeatheredToClear) }
        })
        body.addView(ptRow3)
    }

    private fun buildBudgetAndStartCardBody(
        body: LinearLayout,
        cfg: RuleConfig,
        activeIds: Set<String>,
        p: OverlayPalette
    ) {
        val effSteps = if (cfg.useSystemSteps) settings.maxSteps else cfg.maxSteps
        val stepsHdrRow = hRow(bottomMarginDp = 3)
        stepsHdrRow.addView(TextView(context).apply {
            text = "${s(R.string.unit_steps)}: $effSteps"
            textSize = 10f; typeface = Typeface.DEFAULT_BOLD; setTextColor(p.primary)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        stepsHdrRow.addView(makeToggle(s(if (cfg.useSystemSteps) R.string.budget_follow_system else R.string.budget_override_strategy), cfg.useSystemSteps, p, marginEndDp = 0) {
            onUpdateActivePresetConfig { it.copy(useSystemSteps = !it.useSystemSteps) }
        })
        body.addView(stepsHdrRow)

        val stepsRow = hRow(bottomMarginDp = 4)
        listOf(30, 60, 100, 150, 200).forEachIndexed { idx, st ->
            stepsRow.addView(makeToggle("$st", !cfg.useSystemSteps && cfg.maxSteps == st, p, weight = 1f, marginEndDp = if (idx == 4) 0 else 4) {
                onUpdateActivePresetConfig { it.copy(maxSteps = st, useSystemSteps = false) }
            })
        }
        body.addView(stepsRow)

        // Wave Hard Step Limit (限 N 步) & Step Range Shield (#60 步数盾 [min-max])
        val limStepRow = hRow(bottomMarginDp = 4)
        limStepRow.addView(makeChip("−", padH = 6) {
            stepLimitVal = (stepLimitVal - 1).coerceIn(2, 60)
            ActiveStrategyStore.replaceGroup(
                WaveShieldCatalog.STEP_EXCLUSIVE_IDS,
                WaveShieldChip("step_limit", s(R.string.shield_step_limit_fmt, stepLimitVal), ShieldCategory.HAZARD_AND_PATH, paramInt = stepLimitVal)
            )
        }.also { styleChip(it, p.chipOffBg, p.onSurface, p.chipOffStroke) })
        limStepRow.addView(makeToggle(s(R.string.shield_step_limit_fmt, stepLimitVal), "step_limit" in activeIds, p, weight = 1f) {
            if ("step_limit" in activeIds) ActiveStrategyStore.removeChip("step_limit")
            else ActiveStrategyStore.replaceGroup(
                WaveShieldCatalog.STEP_EXCLUSIVE_IDS,
                WaveShieldChip("step_limit", s(R.string.shield_step_limit_fmt, stepLimitVal), ShieldCategory.HAZARD_AND_PATH, paramInt = stepLimitVal)
            )
        })
        limStepRow.addView(makeChip("+", padH = 6, marginEndDp = 4) {
            stepLimitVal = (stepLimitVal + 1).coerceIn(2, 60)
            ActiveStrategyStore.replaceGroup(
                WaveShieldCatalog.STEP_EXCLUSIVE_IDS,
                WaveShieldChip("step_limit", s(R.string.shield_step_limit_fmt, stepLimitVal), ShieldCategory.HAZARD_AND_PATH, paramInt = stepLimitVal)
            )
        }.also { styleChip(it, p.chipOffBg, p.onSurface, p.chipOffStroke) })
        limStepRow.addView(makeToggle(s(R.string.shield_step_range_fmt, stepRangeMinVal, stepRangeMaxVal), "step_range" in activeIds, p, weight = 1.15f, marginEndDp = 0) {
            if ("step_range" in activeIds) ActiveStrategyStore.removeChip("step_range")
            else ActiveStrategyStore.replaceGroup(
                WaveShieldCatalog.STEP_EXCLUSIVE_IDS,
                WaveShieldChip("step_range", s(R.string.shield_step_range_fmt, stepRangeMinVal, stepRangeMaxVal), ShieldCategory.HAZARD_AND_PATH, paramInt = stepRangeMinVal or (stepRangeMaxVal shl 8))
            )
        })
        body.addView(limStepRow)
        if ("step_range" in activeIds) {
            fun saveRange(lo: Int, hi: Int) {
                stepRangeMinVal = lo.coerceIn(1, 180)
                stepRangeMaxVal = hi.coerceIn(stepRangeMinVal, 200)
                onUpsertWaveChip(
                    WaveShieldChip("step_range", s(R.string.shield_step_range_fmt, stepRangeMinVal, stepRangeMaxVal), ShieldCategory.HAZARD_AND_PATH, paramInt = stepRangeMinVal or (stepRangeMaxVal shl 8))
                )
            }
            val rRow = hRow(bottomMarginDp = 4)
            rRow.addView(makeChip("−", padH = 6) { saveRange(stepRangeMinVal - 1, stepRangeMaxVal) }.also { styleChip(it, p.chipOffBg, p.onSurface, p.chipOffStroke) })
            rRow.addView(makeChip("${s(R.string.step_range_min_label)}:$stepRangeMinVal", weight = 1f) {}.also { styleChip(it, p.secondaryContainer, p.onSecondaryContainer, null) })
            rRow.addView(makeChip("+", padH = 6) { saveRange(stepRangeMinVal + 1, maxOf(stepRangeMaxVal, stepRangeMinVal + 1)) }.also { styleChip(it, p.chipOffBg, p.onSurface, p.chipOffStroke) })
            rRow.addView(makeChip("−", padH = 6) { saveRange(minOf(stepRangeMinVal, stepRangeMaxVal - 1), stepRangeMaxVal - 1) }.also { styleChip(it, p.chipOffBg, p.onSurface, p.chipOffStroke) })
            rRow.addView(makeChip("${s(R.string.step_range_max_label)}:$stepRangeMaxVal", weight = 1f) {}.also { styleChip(it, p.secondaryContainer, p.onSecondaryContainer, null) })
            rRow.addView(makeChip("+", padH = 6, marginEndDp = 0) { saveRange(stepRangeMinVal, stepRangeMaxVal + 1) }.also { styleChip(it, p.chipOffBg, p.onSurface, p.chipOffStroke) })
            body.addView(rRow)
        }

        // Unified Compute SeekBar
        body.addView(makeSubDivider(p))
        val effBeam = if (cfg.useSystemBeam) settings.beamWidth else cfg.beamWidth
        val beamHdrRow = hRow(bottomMarginDp = 2)
        val beamLabel = TextView(context).apply {
            text = "${s(R.string.slider_beam)}: $effBeam"
            textSize = 10f; typeface = Typeface.DEFAULT_BOLD; setTextColor(p.primary)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        beamHdrRow.addView(beamLabel)
        beamHdrRow.addView(makeToggle(s(if (cfg.useSystemBeam) R.string.budget_follow_system else R.string.budget_override_strategy), cfg.useSystemBeam, p, marginEndDp = 0) {
            onUpdateActivePresetConfig { it.copy(useSystemBeam = !it.useSystemBeam) }
        })
        body.addView(beamHdrRow)
        body.addView(makeComputeSeekBar(effBeam, p, beamLabel) { newBeam ->
            onUpdateActivePresetConfig { it.copy(beamWidth = newBeam, useSystemBeam = false) }
        })

        // Start Position & Character Orb
        body.addView(makeSubDivider(p))
        body.addView(makeSubLabel(s(R.string.section_start_char), p))
        val stRow = hRow(bottomMarginDp = 4)
        listOf(StartPointPreset.ANY to s(R.string.start_any), StartPointPreset.BOTTOM_LEFT to s(R.string.start_bl), StartPointPreset.BOTTOM_RIGHT to s(R.string.start_br))
            .forEachIndexed { idx, (sp, lbl) ->
                stRow.addView(makeToggle(lbl, cfg.startPreset == sp && "traffic_light" !in activeIds, p, weight = 1f, marginEndDp = if (idx == 2) 0 else 4) {
                    ActiveStrategyStore.removeChip("traffic_light")
                    onUpdateActivePresetConfig { it.copy(startPreset = sp) }
                })
            }
        body.addView(stRow)
        val tlRow = hRow(bottomMarginDp = 4)
        tlRow.addView(makeToggle(s(R.string.shield_traffic_light), "traffic_light" in activeIds, p, weight = 1f, marginEndDp = 0) {
            onToggleWaveChip(WaveShieldChip("traffic_light", s(R.string.shield_traffic_light), ShieldCategory.HAZARD_AND_PATH))
        })
        body.addView(tlRow)
        val chRow = hRow(bottomMarginDp = 0)
        chRow.addView(makeToggle(s(R.string.char_off), !cfg.useCharacterOrb, p, weight = 1f) {
            onUpdateActivePresetConfig { it.copy(useCharacterOrb = false) }
        })
        for (c in 0..5) {
            chRow.addView(makeToggle("${c + 1}", cfg.useCharacterOrb && cfg.characterOrbCol == c, p, weight = 0.8f, marginEndDp = if (c == 5) 0 else 3) {
                onUpdateActivePresetConfig { it.copy(useCharacterOrb = true, characterOrbCol = c) }
            })
        }
        body.addView(chRow)
    }

    private fun buildLeaderAndTraitsCardBody(
        body: LinearLayout,
        cfg: RuleConfig,
        isHeartAll: Boolean,
        isLightDark: Boolean,
        isWfg: Boolean,
        p: OverlayPalette
    ) {
        body.addView(makeSubLabel(s(R.string.section_link_preset), p))
        val lnkRow = hRow(bottomMarginDp = 4)
        lnkRow.addView(makeToggle(s(R.string.link_standard), !isHeartAll && !isLightDark && !isWfg, p, weight = 1f) {
            onUpdateActivePresetConfig { it.copy(compatibleLinkMask = List(6) { idx -> 1 shl idx }) }
        })
        lnkRow.addView(makeToggle(s(R.string.link_heart_all), isHeartAll, p, weight = 1f) {
            onUpdateActivePresetConfig { it.copy(compatibleLinkMask = List(6) { e -> if (e == 5) 0x3F else ((1 shl e) or (1 shl 5)) }) }
        })
        lnkRow.addView(makeToggle(s(R.string.link_light_dark), isLightDark, p, weight = 1f) {
            val ld = (1 shl 3) or (1 shl 4)
            onUpdateActivePresetConfig { it.copy(compatibleLinkMask = List(6) { e -> if (e == 3 || e == 4) ld else (1 shl e) }) }
        })
        lnkRow.addView(makeToggle(s(R.string.link_wfg), isWfg, p, weight = 1f, marginEndDp = 0) {
            onUpdateActivePresetConfig { it.copy(compatibleLinkMask = List(6) { e -> if (e in 0..2) 0x07 else (1 shl e) }) }
        })
        body.addView(lnkRow)

        body.addView(makeSubDivider(p))
        body.addView(makeSubLabel(s(R.string.section_strategy_switches), p))
        val swRow1 = hRow(bottomMarginDp = 4)
        swRow1.addView(makeToggle(s(R.string.tag_split_three), cfg.forceSplitThree, p, weight = 1f) {
            onUpdateActivePresetConfig { it.copy(forceSplitThree = !it.forceSplitThree) }
        })
        swRow1.addView(makeToggle(s(R.string.tag_first_only), cfg.firstWaveOnlyScoring, p, weight = 1f) {
            onUpdateActivePresetConfig { it.copy(firstWaveOnlyScoring = !it.firstWaveOnlyScoring) }
        })
        swRow1.addView(makeToggle(s(R.string.tag_diag_match), cfg.allowDiagonalMatch, p, weight = 1f, marginEndDp = 0) {
            onUpdateActivePresetConfig { it.copy(allowDiagonalMatch = !it.allowDiagonalMatch) }
        })
        body.addView(swRow1)
        val swRow2 = hRow(bottomMarginDp = 0)
        swRow2.addView(makeToggle(s(R.string.switch_prefer_ench), cfg.preferEnchanted, p, weight = 1f) {
            onUpdateActivePresetConfig { it.copy(preferEnchanted = !it.preferEnchanted) }
        })
        swRow2.addView(makeToggle(s(R.string.switch_prefer_race), cfg.preferRace, p, weight = 1f, marginEndDp = 0) {
            onUpdateActivePresetConfig { it.copy(preferRace = !it.preferRace) }
        })
        body.addView(swRow2)
    }

    // ==================== TAB 2: 系统设置 (4 Single-Open Colored Accordions matching Launch App!) ====================
    private fun buildSettingsTab(p: OverlayPalette) {
        addInfoBanner(s(R.string.priority_tier_hint_settings), p)

        // Card 0 (Blue): Solver Engine & System Steps
        val s0Summary = "${settings.maxSteps}${s(R.string.unit_steps)} · ${s(R.string.tag_beam_fmt, settings.beamWidth)}"
        addAccordionGroupCard(0, s(R.string.section_engine_steps), s0Summary, true, ACCENT_BLUE, p)?.let { engBody ->
            engBody.addView(makeSubLabel(s(R.string.ov_steps_hdr_fmt, settings.maxSteps), p))
            val stepRow = hRow(bottomMarginDp = 5)
            listOf(30, 60, 100, 150, 200).forEachIndexed { idx, st ->
                stepRow.addView(makeToggle("$st", settings.maxSteps == st, p, weight = 1f, marginEndDp = if (idx == 4) 0 else 4) {
                    onUpdateSettings { it.copy(maxSteps = st) }
                })
            }
            engBody.addView(stepRow)

            val beamLbl = TextView(context).apply {
                text = "${s(R.string.slider_beam)}: ${settings.beamWidth}"
                textSize = 10f; typeface = Typeface.DEFAULT_BOLD; setTextColor(p.primary)
            }
            engBody.addView(beamLbl)
            engBody.addView(makeComputeSeekBar(settings.beamWidth, p, beamLbl) { nb ->
                onUpdateSettings { it.copy(beamWidth = nb) }
            })

            engBody.addView(makeSubDivider(p))
            val solveSecLabel = String.format(java.util.Locale.US, "%.2fs", settings.holdReplanSolveTimeMs / 1000.0)
            engBody.addView(makeSubLabel(s(R.string.ov_hold_solve_hdr_fmt, solveSecLabel), p))
            val holdSolveRow = hRow(bottomMarginDp = 0)
            val holdPresets = listOf("0.3s" to 300L, "0.6s" to 600L, "1.2s" to 1200L, "2.0s" to 2000L, "3.5s" to 3500L)
            holdPresets.forEachIndexed { idx, (lbl, ms) ->
                holdSolveRow.addView(
                    makeToggle(
                        lbl,
                        settings.holdReplanSolveTimeMs == ms,
                        p,
                        weight = 1f,
                        marginEndDp = if (idx == holdPresets.lastIndex) 0 else 4
                    ) {
                        onUpdateSettings { it.copy(holdReplanSolveTimeMs = ms) }
                    }
                )
            }
            engBody.addView(holdSolveRow)
        }

        // Card 1 (Amber): Unified 6-Tier Swipe Speed Presets (2s..7s, matches Launch App Settings Amber card!)
        val activeSpeed = SwipeSpeedPreset.fromStepDurationMs(settings.stepDurationMs)
        val sSpeedSummary = "${LocaleHelper.speedLabel(lctx, activeSpeed)} · ${settings.stepDurationMs}${s(R.string.unit_ms)}"
        addAccordionGroupCard(1, s(R.string.section_speed), sSpeedSummary, true, ACCENT_AMBER, p)?.let { spdBody ->
            populateSpeedPresetSection(spdBody, p)
        }

        // Card 2 (Teal): Interaction & Core Switches (matches Launch App Settings Teal card 1:1!)
        val sInvLabel = invertSummaryLabel()
        val s1Summary = buildList {
            if (settings.testModeOnlyDraw) add(s(R.string.ov_preview_confirm))
            if (settings.holdReplanEnabled) add(s(R.string.ov_hold))
            if (sInvLabel != null) add(sInvLabel)
            if (settings.allowDiagonalMove) add(s(R.string.ov_diag_move))
            if (settings.humanMode) add(s(R.string.mode_human_title).take(4))
        }.joinToString(" · ").ifEmpty { s(R.string.ov_card_none_set) }
        addAccordionGroupCard(2, s(R.string.section_modes), s1Summary, settings.testModeOnlyDraw || settings.holdReplanEnabled || sInvLabel != null || settings.allowDiagonalMove, ACCENT_TEAL, p)?.let { swBody ->
            val swRow1 = hRow(bottomMarginDp = 4)
            swRow1.addView(makeToggle(s(R.string.ov_preview_confirm), settings.testModeOnlyDraw, p, weight = 1f) {
                onUpdateSettings { it.copy(testModeOnlyDraw = !it.testModeOnlyDraw) }
            })
            swRow1.addView(makeToggle(s(R.string.ov_diag_move), settings.allowDiagonalMove, p, weight = 1f) {
                onUpdateSettings { it.copy(allowDiagonalMove = !it.allowDiagonalMove) }
            })
            swRow1.addView(makeToggle(s(R.string.ov_orb_badges), settings.showOrbBadgesOnPath, p, weight = 1f, marginEndDp = 0) {
                onUpdateSettings { it.copy(showOrbBadgesOnPath = !it.showOrbBadgesOnPath) }
            })
            swBody.addView(swRow1)

            val swRow2 = hRow(bottomMarginDp = 4)
            swRow2.addView(makeToggle(s(R.string.mode_human_title), settings.humanMode, p, weight = 1f) {
                onUpdateSettings { it.copy(humanMode = !it.humanMode) }
            })
            swRow2.addView(makeToggle(s(R.string.mode_stop_title), settings.showEmergencyStop, p, weight = 1f, marginEndDp = 0) {
                onUpdateSettings { it.copy(showEmergencyStop = !it.showEmergencyStop) }
            })
            swBody.addView(swRow2)

            swBody.addView(makeSubDivider(p))
            populateHoldAndInvertControls(swBody, p)
        }

        // Card 3 (Purple): Appearance, Theme, Language & Floating Bar Layout/Dock (matches Launch App Settings Purple card!)
        val barLayoutLbl = s(if (settings.floatingBarVertical) R.string.floating_bar_vertical else R.string.floating_bar_horizontal)
        val barDockLbl = s(if (settings.floatingBarDockRight) R.string.floating_bar_dock_right else R.string.floating_bar_dock_left)
        val s2Summary = "${LocaleHelper.themeLabel(lctx, settings.themeMode)} · ${LocaleHelper.languageLabel(lctx, settings.language)} · $barLayoutLbl$barDockLbl"
        addAccordionGroupCard(3, s(R.string.section_appearance), s2Summary, true, ACCENT_PURPLE, p)?.let { appBody ->
            val themeRow = hRow(bottomMarginDp = 4)
            ThemeMode.entries.forEachIndexed { idx, tm ->
                themeRow.addView(makeToggle(LocaleHelper.themeLabel(lctx, tm), settings.themeMode == tm, p, weight = 1f, marginEndDp = if (idx == ThemeMode.entries.lastIndex) 0 else 4) {
                    onUpdateSettings { it.copy(themeMode = tm) }
                })
            }
            appBody.addView(themeRow)

            val langRow = hRow(bottomMarginDp = 5)
            AppLanguage.entries.forEachIndexed { idx, lang ->
                langRow.addView(makeToggle(LocaleHelper.languageLabel(lctx, lang), settings.language == lang, p, weight = 1f, marginEndDp = if (idx == AppLanguage.entries.lastIndex) 0 else 4) {
                    onUpdateSettings { it.copy(language = lang) }
                })
            }
            appBody.addView(langRow)

            appBody.addView(makeSubDivider(p))
            appBody.addView(makeSubLabel("${s(R.string.floating_bar_layout_title)} · ${s(R.string.floating_bar_dock_title)}", p))
            val barLayoutRow = hRow(bottomMarginDp = 4)
            barLayoutRow.addView(makeToggle(s(R.string.floating_bar_horizontal), !settings.floatingBarVertical, p, weight = 1f, marginEndDp = 4) {
                onUpdateSettings { it.copy(floatingBarVertical = false) }
            })
            barLayoutRow.addView(makeToggle(s(R.string.floating_bar_vertical), settings.floatingBarVertical, p, weight = 1f, marginEndDp = 0) {
                onUpdateSettings { it.copy(floatingBarVertical = true) }
            })
            appBody.addView(barLayoutRow)

            val barDockRow = hRow(bottomMarginDp = 0)
            barDockRow.addView(makeToggle(s(R.string.floating_bar_dock_left), !settings.floatingBarDockRight, p, weight = 1f, marginEndDp = 4) {
                onUpdateSettings { it.copy(floatingBarDockRight = false, floatingBarPosXRatio = -1f) }
            })
            barDockRow.addView(makeToggle(s(R.string.floating_bar_dock_right), settings.floatingBarDockRight, p, weight = 1f, marginEndDp = 0) {
                onUpdateSettings { it.copy(floatingBarDockRight = true, floatingBarPosXRatio = -1f) }
            })
            appBody.addView(barDockRow)
        }
    }

    /**
     * Unified Hold-and-Replan (Master Toggle + Nested Trigger Mode) and 4-State Invert Control Selector
     * shared 1:1 between Tab 0 (Quick), Tab 2 (Settings), and Launch App Settings.
     */
    private fun invertSummaryLabel(): String? = when {
        settings.invertDragX && settings.invertDragY -> s(R.string.invert_xy)
        settings.invertDragX -> s(R.string.invert_x)
        settings.invertDragY -> s(R.string.invert_y)
        else -> null
    }

    private fun populateHoldAndInvertControls(body: LinearLayout, p: OverlayPalette) {
        val holdRow = hRow(bottomMarginDp = 5)
        holdRow.addView(makeToggle(s(R.string.ov_hold), settings.holdReplanEnabled, p, weight = 1.05f) {
            onUpdateSettings { it.copy(holdReplanEnabled = !it.holdReplanEnabled) }
        })
        holdRow.addView(makeToggle(s(R.string.mode_hold_trigger_inplace), !settings.holdReplanNudge, p, weight = 0.95f) {
            onUpdateSettings { it.copy(holdReplanNudge = false) }
        }.also { if (!settings.holdReplanEnabled) it.alpha = 0.62f })
        holdRow.addView(makeToggle(s(R.string.mode_hold_trigger_nudge), settings.holdReplanNudge, p, weight = 1.0f, marginEndDp = 0) {
            onUpdateSettings { it.copy(holdReplanNudge = true) }
        }.also { if (!settings.holdReplanEnabled) it.alpha = 0.62f })
        body.addView(holdRow)

        body.addView(makeSubLabel(s(R.string.mode_invert_title), p))
        val invRow = hRow(bottomMarginDp = 0)
        val invertOpts = listOf(
            Triple(R.string.invert_off, false, false),
            Triple(R.string.invert_x_short, true, false),
            Triple(R.string.invert_y_short, false, true),
            Triple(R.string.invert_xy_short, true, true)
        )
        invertOpts.forEachIndexed { idx, (lblRes, ix, iy) ->
            invRow.addView(
                makeToggle(
                    s(lblRes),
                    settings.invertDragX == ix && settings.invertDragY == iy,
                    p,
                    weight = 1f,
                    marginEndDp = if (idx == invertOpts.lastIndex) 0 else 4
                ) {
                    onUpdateSettings { it.copy(invertDragX = ix, invertDragY = iy) }
                }
            )
        }
        body.addView(invRow)
    }

    /**
     * Unified 6-Tier Swipe Speed Grid (2 rows × 3 columns: 2s, 3s, 4s / 5s, 6s, 7s) + fine-tune SeekBar,
     * shared verbatim across Tab 0 (Quick) and Tab 2 (Settings) to match Launch App Settings 1:1.
     */
    private fun populateSpeedPresetSection(body: LinearLayout, p: OverlayPalette) {
        val activeSpeed = SwipeSpeedPreset.fromStepDurationMs(settings.stepDurationMs)
        val msUnit = s(R.string.unit_ms)
        body.addView(makeSubLabel(s(R.string.speed_desc), p))

        val rows = SwipeSpeedPreset.entries.chunked(3)
        rows.forEachIndexed { rIdx, rowPresets ->
            val spdRow = hRow(bottomMarginDp = if (rIdx == rows.lastIndex) 4 else 4)
            rowPresets.forEachIndexed { idx, preset ->
                val sel = activeSpeed == preset
                val st = p.speedStyle(preset)
                val chip = TextView(context).apply {
                    text = "${LocaleHelper.speedLabel(lctx, preset)}\n${preset.stepDurationMs} $msUnit"
                    textSize = 9.5f
                    maxLines = 2
                    gravity = Gravity.CENTER
                    typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    setLineSpacing(0f, 1.05f)
                    setPadding(dp(3), dp(4), dp(3), dp(4))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = if (idx == rowPresets.lastIndex) 0 else dp(4)
                    }
                    setOnClickListener {
                        onUpdateSettings { it.copy(stepDurationMs = preset.stepDurationMs) }
                    }
                }
                if (sel) styleChip(chip, st.bg, st.fg, st.stroke)
                else styleChip(chip, p.chipOffBg, p.chipOffText, p.chipOffStroke)
                spdRow.addView(chip)
            }
            body.addView(spdRow)
        }

        val fineLbl = TextView(context).apply {
            text = "${settings.stepDurationMs} $msUnit"
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ACCENT_AMBER)
        }
        body.addView(fineLbl)
        body.addView(SeekBar(context).apply {
            max = 100 // 20ms..120ms
            progress = (settings.stepDurationMs.toInt() - 20).coerceIn(0, 100)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(22))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                    fineLbl.text = "${20 + prog} $msUnit"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    val v = (20 + (sb?.progress ?: 0)).toLong().coerceIn(20L, 120L)
                    onUpdateSettings { it.copy(stepDurationMs = v) }
                }
            })
        })
    }

    // ==================== Visually Distinct Bordered Group Card Builders ====================
    private fun cardSurfaceColor(p: OverlayPalette): Int =
        if (p.isDark) Color.rgb(34, 38, 46) else Color.WHITE

    private fun cardHeaderTint(accent: Int, p: OverlayPalette): Int =
        Color.argb(if (p.isDark) 42 else 22, Color.red(accent), Color.green(accent), Color.blue(accent))

    private fun addStaticGroupCard(title: String, accentColor: Int, p: OverlayPalette): LinearLayout {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(cardSurfaceColor(p), dp(10).toFloat(), p.outline)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        val hdr = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = topRoundedBg(cardHeaderTint(accentColor, p), dp(9).toFloat())
            setPadding(dp(8), dp(5), dp(8), dp(5))
        }
        hdr.addView(View(context).apply {
            background = roundedBg(accentColor, dp(2).toFloat(), null)
            layoutParams = LinearLayout.LayoutParams(dp(3), dp(12)).apply { marginEnd = dp(6) }
        })
        hdr.addView(TextView(context).apply {
            text = title
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accentColor)
        })
        card.addView(hdr)

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
        }
        card.addView(body)
        contentHost.addView(card)
        return body
    }

    /**
     * Builds a clearly framed, color-accented, collapsible option group card.
     * Returns the inner body container when expanded, or null when collapsed.
     */
    private fun addAccordionGroupCard(
        cardId: Int,
        title: String,
        summaryText: String,
        highlighted: Boolean,
        accentColor: Int,
        p: OverlayPalette
    ): LinearLayout? {
        val isOpen = expandedCardByTab[activeTab] == cardId
        val borderColor = if (highlighted || isOpen) {
            Color.argb(if (p.isDark) 170 else 145, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
        } else {
            p.outline
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(cardSurfaceColor(p), dp(10).toFloat(), borderColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

        val hdr = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = if (isOpen) topRoundedBg(cardHeaderTint(accentColor, p), dp(9).toFloat())
            else roundedBg(cardHeaderTint(accentColor, p), dp(9).toFloat(), null)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setOnClickListener {
                // Exclusive single-open accordion: opening a drawer automatically collapses all other drawers in this tab!
                expandedCardByTab[activeTab] = if (isOpen) null else cardId
                rebuildContent(preserveScroll = false)
            }
        }
        // Colored vertical left accent strip
        hdr.addView(View(context).apply {
            background = roundedBg(accentColor, dp(2).toFloat(), null)
            layoutParams = LinearLayout.LayoutParams(dp(3), dp(14)).apply { marginEnd = dp(6) }
        })
        // Group title
        hdr.addView(TextView(context).apply {
            text = title
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setSingleLine(true)
            setTextColor(if (p.isDark) Color.WHITE else p.onSurface)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(6)
            }
        })
        // Flexible spacer pushes tight summary pill flush-right
        hdr.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f)
        })
        // Live tight summary badge on the right
        hdr.addView(TextView(context).apply {
            text = summaryText
            textSize = 9.5f
            typeface = if (highlighted) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(170)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            if (highlighted) {
                setTextColor(accentColor)
                background = roundedBg(Color.argb(if (p.isDark) 52 else 28, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)), dp(6).toFloat(), null)
            } else {
                setTextColor(p.onSurfaceVariant)
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(6)
            }
        })
        // Expand/collapse chevron
        hdr.addView(TextView(context).apply {
            text = if (isOpen) "▾" else "▸"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accentColor)
        })
        card.addView(hdr)

        if (!isOpen) {
            contentHost.addView(card)
            return null
        }

        // Thin divider separating header from body
        card.addView(View(context).apply {
            setBackgroundColor(Color.argb(if (p.isDark) 55 else 36, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        })

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
        }
        card.addView(body)
        contentHost.addView(card)
        return body
    }

    private fun makeSubDivider(p: OverlayPalette): View = View(context).apply {
        setBackgroundColor(Color.argb(if (p.isDark) 48 else 28, 128, 128, 128))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
        }
    }

    private fun makeSubLabel(text: String, p: OverlayPalette): TextView = TextView(context).apply {
        this.text = text
        textSize = 9.5f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(p.onSurfaceVariant)
        setPadding(dp(1), 0, dp(1), dp(3))
    }

    // ==================== Shared Native View Helpers ====================
    private fun makeComputeSeekBar(currentValue: Int, p: OverlayPalette, liveLabel: TextView, onCommit: (Int) -> Unit): SeekBar {
        return SeekBar(context).apply {
            max = 28
            progress = ((currentValue - 40) / 20).coerceIn(0, 28)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(24)).apply { bottomMargin = dp(2) }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                    liveLabel.text = "${s(R.string.slider_beam)}: ${40 + prog * 20}"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    onCommit(40 + (sb?.progress ?: 0) * 20)
                }
            })
        }
    }

    private fun makeStepperRow(valueText: String, p: OverlayPalette, onMinus: () -> Unit, onPlus: () -> Unit): LinearLayout {
        return hRow(bottomMarginDp = 4).apply {
            addView(makeChip("−", weight = 0.7f) { onMinus() }.also { styleChip(it, p.chipOffBg, p.onSurface, p.chipOffStroke) })
            addView(TextView(context).apply {
                text = valueText; textSize = 10.5f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(p.primary)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.6f)
            })
            addView(makeChip("+", weight = 0.7f, marginEndDp = 0) { onPlus() }.also { styleChip(it, p.chipOffBg, p.onSurface, p.chipOffStroke) })
        }
    }

    private fun addInfoBanner(text: String, p: OverlayPalette) {
        contentHost.addView(TextView(context).apply {
            this.text = text; textSize = 9.5f; setTextColor(p.onPrimaryContainer)
            background = roundedBg(p.primaryContainer, dp(6).toFloat(), null)
            setPadding(dp(7), dp(3), dp(7), dp(3))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
        })
    }

    private fun hRow(bottomMarginDp: Int = 4): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(bottomMarginDp)
        }
    }

    private fun makeChip(label: String, padH: Int = 5, weight: Float = 0f, marginEndDp: Int = 4, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label; textSize = 10f; setSingleLine(true); gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(padH), dp(4), dp(padH), dp(4))
        layoutParams = if (weight > 0f) {
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight).apply { marginEnd = dp(marginEndDp) }
        } else {
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(marginEndDp) }
        }
        setOnClickListener { onClick() }
    }

    private fun makeToggle(label: String, selected: Boolean, p: OverlayPalette, weight: Float = 0f, marginEndDp: Int = 4, onClick: () -> Unit): TextView =
        makeChip(label, weight = weight, marginEndDp = marginEndDp, onClick = onClick).also {
            if (selected) styleChip(it, p.primaryContainer, p.onPrimaryContainer, p.primary)
            else styleChip(it, p.chipOffBg, p.chipOffText, p.chipOffStroke)
        }

    private fun makeElementBtn(elemIndex: Int, selected: Boolean, p: OverlayPalette, suffix: String = "", weight: Float = 1f, marginEndDp: Int = 3, onClick: () -> Unit): TextView {
        val lbl = LocaleHelper.elementLabel(lctx, elemIndex) + suffix
        val badgeCol = LocaleHelper.elementBadgeArgb(elemIndex)
        return makeChip(lbl, padH = 3, weight = weight, marginEndDp = marginEndDp, onClick = onClick).also {
            if (selected) styleChip(it, badgeCol, Color.WHITE, badgeCol)
            else styleChip(it, p.chipOffBg, p.chipOffText, p.chipOffStroke)
        }
    }

    private fun styleChip(view: TextView, bg: Int, fg: Int, stroke: Int?) {
        view.setTextColor(fg)
        view.background = roundedBg(bg, dp(7).toFloat(), stroke)
    }

    private fun roundedBg(fillColor: Int, radiusPx: Float, strokeColor: Int?) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx
        setColor(fillColor)
        if (strokeColor != null) setStroke(dp(1), strokeColor)
    }

    private fun topRoundedBg(fillColor: Int, radiusPx: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadii = floatArrayOf(radiusPx, radiusPx, radiusPx, radiusPx, 0f, 0f, 0f, 0f)
        setColor(fillColor)
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).roundToInt()

    companion object {
        private val ACCENT_INDIGO = Color.rgb(63, 81, 181)
        private val ACCENT_TEAL = Color.rgb(0, 137, 123)
        private val ACCENT_AMBER = Color.rgb(230, 118, 0)
        private val ACCENT_CRIMSON = Color.rgb(211, 47, 47)
        private val ACCENT_BLUE = Color.rgb(25, 118, 210)
        private val ACCENT_PURPLE = Color.rgb(123, 31, 162)
    }
}
