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
    private val onResetStrategy: () -> Unit,
    private val onUpdateActivePresetConfig: ((RuleConfig) -> RuleConfig) -> Unit,
    private val onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    private val onManualScanEnemyNow: () -> Unit = {},
    private val onActivateStageScript: (StageWaveScript) -> Unit = {},
    private val onStepStageWave: (Int) -> Unit = {},
    private val onDeactivateStageScript: () -> Unit = {},
    private val onRestoreBypassedSkill: (BypassedEnemySkill) -> Unit = {},
    private val onShowDrawerHelp: ((String, String) -> Unit)? = null,
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
    private var activePreset: TeamBasePreset = TeamPresetRepository.defaultBuiltInPresets().first()
    private var activeChips: List<WaveShieldChip> = emptyList()
    private var boardEditorOpen: Boolean = false
    private var hasSavedBoard: Boolean = false
    private var savedStageScripts: List<StageWaveScript> = emptyList()
    private var activeStageScript: StageWaveScript? = null
    private var activeStageWaveIndex: Int = 1
    private var bypassedGreySkills: List<BypassedEnemySkill> = emptyList()
    private var activeVisualHints: BoardVisualHints = BoardVisualHints()
    private var aiEngineStatusLabel: String = ""
    private var collectedTrainingSamples: Int = 0
    private var stagePickerPage: Int = 0
    private var stageL1Filter: String = "ALL"

    // Exclusive single-open accordion expansion per tab (expanding one auto-collapses the others in that tab!)
    private var expandedShapeElemIdx: Int? = null
    private var expandedCondElemIdx: Int? = null
    private val expandedCardByTab = mutableMapOf<Int, Int?>(
        0 to 0, // Tab 0 (Quick): Card 0 open
        1 to 0, // Tab 1 (Strategy): Card 0 open
        2 to 0  // Tab 2 (Settings): Card 0 open
    )

    // Working numeric parameters synchronized with ActiveStrategyStore
    private var minTotalComboVal = 6
    private var maxTotalComboVal = 6
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
    private var burstOrbsVal = 15
    private var maxElemOrbsVal = 5
    private var elemLimitMode = 0
    private var lastAutoExpandedSig: String = ""
    private var digitalOrbsVal = 18
    private var stepLimitVal = 15
    private var exactStepsVal = 20
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
        activePreset: TeamBasePreset,
        activeChips: List<WaveShieldChip>,
        boardEditorOpen: Boolean = false,
        hasSavedBoard: Boolean = false,
        savedStageScripts: List<StageWaveScript> = emptyList(),
        activeStageScript: StageWaveScript? = null,
        activeStageWaveIndex: Int = 1,
        bypassedGreySkills: List<BypassedEnemySkill> = emptyList(),
        activeVisualHints: BoardVisualHints = BoardVisualHints(),
        aiEngineStatusLabel: String = "",
        collectedTrainingSamples: Int = 0
    ) {
        this.lctx = LocaleHelper.localizedContext(context, settings.language)
        this.palette = palette
        this.settings = settings
        this.activePreset = activePreset
        this.activeChips = activeChips
        this.boardEditorOpen = boardEditorOpen
        this.hasSavedBoard = hasSavedBoard
        this.savedStageScripts = savedStageScripts
        this.activeStageScript = activeStageScript
        this.activeStageWaveIndex = activeStageWaveIndex
        this.bypassedGreySkills = bypassedGreySkills
        this.activeVisualHints = activeVisualHints
        this.aiEngineStatusLabel = aiEngineStatusLabel
        this.collectedTrainingSamples = collectedTrainingSamples

        val byId = activeChips.associateBy { it.id }
        byId["min_combo"]?.paramInt?.let { if (it in 1..15) minTotalComboVal = it }
        byId["max_combo"]?.paramInt?.let { if (it in 1..15) maxTotalComboVal = it }
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
        byId["elem_burst_shield"]?.paramInt?.let { if (it in 3..30) burstOrbsVal = it }
        byId["max_element_orbs"]?.paramInt?.let { if (it in 0..30) maxElemOrbsVal = it }
        byId["digital_orb_count"]?.paramInt?.let { if (it in 6..30) digitalOrbsVal = it }
        byId["step_limit"]?.paramInt?.let { if (it in 2..60) stepLimitVal = it }
        byId["exact_steps"]?.paramInt?.let { if (it in 1..100) exactStepsVal = it }
        byId["step_range"]?.paramInt?.let { packed ->
            val lo = packed and 0xFF
            val hi = (packed ushr 8) and 0xFF
            if (lo in 1..200 && hi in lo..200) {
                stepRangeMinVal = lo
                stepRangeMaxVal = hi
            }
        }

        fun limitMaskOf(baseId: String): Int {
            var m = byId[baseId]?.paramElementMask ?: 0
            for (e in 0..5) if (byId.containsKey("${baseId}_$e")) m = m or (1 shl e)
            return m
        }
        val limitBaseIds = listOf(
            "anti_first_wave",
            "forbid_all",
            "clear_all_element",
            "keep_element_at_least_one",
            "unmatchable_elements"
        )
        val limitMasks = limitBaseIds.map { limitMaskOf(it) }
        if (limitMasks.getOrElse(elemLimitMode) { 0 } == 0) {
            val nextMode = limitBaseIds.indices.firstOrNull { idx ->
                limitMasks[idx] != 0 && byId[limitBaseIds[idx]]?.autoDetected == true
            } ?: limitMasks.indexOfFirst { it != 0 }.takeIf { it >= 0 }
            if (nextMode != null) {
                elemLimitMode = nextMode
            }
        }

        val autoChips = activeChips.filter { it.autoDetected }
        val autoSig = autoChips.sortedBy { it.id }
            .joinToString("|") { "${it.id}:${it.paramInt}:${it.paramElementMask}" }
        if (autoSig.isNotEmpty() && autoSig != lastAutoExpandedSig) {
            lastAutoExpandedSig = autoSig
            val autoIdSet = autoChips.map { it.id }.toSet()
            val targetCard = resolveStrategyCardForIds(autoIdSet)
            if (targetCard != null) {
                expandedCardByTab[1] = targetCard
            }
        } else if (autoSig.isEmpty()) {
            lastAutoExpandedSig = ""
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

    // ==================== TAB 0: 快捷工具 (4 Single-Open Colored Accordions) ====================
    private fun buildQuickTab(p: OverlayPalette) {
        // Card 0 (Indigo): Board Paint, Clear Wave Shields & Manual Enemy Scan
        val q0Summary = buildList {
            if (boardEditorOpen) add(s(R.string.ov_editor))
            else if (hasSavedBoard) add(s(R.string.ov_saved_board_pill))
            if (activeChips.isNotEmpty()) add(s(R.string.ov_clear_enemy_short, activeChips.size))
            if (activeVisualHints.hasMonochrome) add(s(R.string.ov_hint_monochrome_pill))
        }.joinToString(" · ").ifEmpty { s(R.string.ov_card_none_set) }
        addAccordionGroupCard(0, s(R.string.ov_tab_quick), q0Summary, boardEditorOpen || hasSavedBoard || activeChips.isNotEmpty() || activeVisualHints.hasAny(), ACCENT_INDIGO, p, R.string.help_quick_tools_title, R.string.help_quick_tools_body)?.let { quickBody ->
            val editorBtnLabel = if (hasSavedBoard && !boardEditorOpen) {
                "${s(R.string.ov_editor)} (${s(R.string.editor_badge_saved)})"
            } else {
                s(R.string.ov_editor)
            }
            val actRow = hRow(bottomMarginDp = 4)
            actRow.addView(makeToggle(editorBtnLabel, boardEditorOpen || hasSavedBoard, p, weight = 1f) {
                onToggleBoardEditor()
            })
            actRow.addView(makeChip(s(R.string.ov_clear_enemy_short, activeChips.size), weight = 1f, marginEndDp = 0) {
                onClearWaveChips()
            }.also {
                styleChip(it, p.errorContainer, p.onErrorContainer, null)
            })
            quickBody.addView(actRow)

            val scanRow = hRow(bottomMarginDp = 0)
            scanRow.addView(makeChip("✦ ${s(R.string.ov_btn_scan_enemy_now)}", weight = 1f, marginEndDp = 0) {
                onManualScanEnemyNow()
            }.also {
                styleChip(it, p.primaryContainer, p.onPrimaryContainer, p.primary)
            })
            quickBody.addView(scanRow)
        }

        // Card 1 (Purple): Stage Wave Scripts (L1 Filter + 6/Page), Stepper & Bypassed Grey Skill Restore
        val curScript = activeStageScript
        val q1ScriptSummary = if (curScript != null) {
            "${curScript.stageName} · W$activeStageWaveIndex/${curScript.waves.size}"
        } else {
            s(R.string.stage_scripts_badge_fmt, savedStageScripts.size)
        }
        addAccordionGroupCard(1, s(R.string.ov_card_stage_scripts), q1ScriptSummary, curScript != null || bypassedGreySkills.isNotEmpty(), ACCENT_PURPLE, p, R.string.help_stage_scripts_title, R.string.help_stage_scripts_body)?.let { stBody ->
            if (curScript != null && curScript.waves.isNotEmpty()) {
                val totalW = curScript.waves.lastOrNull()?.waveIndex ?: curScript.waves.size
                val stepRow = hRow(bottomMarginDp = 4)
                stepRow.addView(TextView(context).apply {
                    text = s(R.string.stage_scripts_active_banner_fmt, curScript.stageName, activeStageWaveIndex, totalW)
                    textSize = 9.5f
                    typeface = Typeface.DEFAULT_BOLD
                    maxLines = 2
                    setTextColor(p.primary)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }
                })
                stepRow.addView(makeChip("‹", padH = 6) { onStepStageWave(-1) }.also { styleChip(it, p.chipOffBg, p.onSurface, p.chipOffStroke) })
                stepRow.addView(makeChip("›", padH = 6) { onStepStageWave(+1) }.also { styleChip(it, p.chipOffBg, p.onSurface, p.chipOffStroke) })
                stepRow.addView(makeChip("×", padH = 6, marginEndDp = 0) { onDeactivateStageScript() }.also { styleChip(it, p.errorContainer, p.onErrorContainer, null) })
                stBody.addView(stepRow)
            }

            if (bypassedGreySkills.isNotEmpty()) {
                stBody.addView(makeSubLabel(s(R.string.ov_bypassed_restore_hdr), p))
                val greyScroll = HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = false
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
                }
                val greyRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                for (sk in bypassedGreySkills) {
                    val lbl = "⊘ ${sk.titleZhTw.ifBlank { sk.chips.joinToString { it.label } }}"
                    greyRow.addView(makeChip(lbl, padH = 6) { onRestoreBypassedSkill(sk) }.also {
                        styleChip(it, p.chipOffBg, p.onSurfaceVariant, p.outline)
                    })
                }
                greyScroll.addView(greyRow)
                stBody.addView(greyScroll)
            }

            if (savedStageScripts.isEmpty()) {
                stBody.addView(makeSubLabel(s(R.string.ov_stage_empty_hint), p))
            } else {
                // Level-1 Game Portal Filter Strip
                val l1Scroll = HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = false
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
                }
                val l1Row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                l1Row.addView(makeToggle("${s(R.string.stage_filter_all)}(${savedStageScripts.size})", stageL1Filter == "ALL", p) {
                    stageL1Filter = "ALL"; stagePickerPage = 0; rebuildContent(preserveScroll = false)
                })
                for (entry in StageHierarchyHelper.ENTRY_ORDER) {
                    val cnt = savedStageScripts.count { it.entryCategory.ifBlank { "大型活動" } == entry }
                    if (cnt > 0 || stageL1Filter == entry) {
                        l1Row.addView(makeToggle("$entry($cnt)", stageL1Filter == entry, p) {
                            stageL1Filter = entry; stagePickerPage = 0; rebuildContent(preserveScroll = false)
                        })
                    }
                }
                l1Scroll.addView(l1Row)
                stBody.addView(l1Scroll)

                val filteredScripts = if (stageL1Filter == "ALL") savedStageScripts
                else savedStageScripts.filter { it.entryCategory.ifBlank { "大型活動" } == stageL1Filter }

                val pageSize = 6
                val totalPages = ((filteredScripts.size + pageSize - 1) / pageSize).coerceAtLeast(1)
                stagePickerPage = stagePickerPage.coerceIn(0, totalPages - 1)
                val slice = filteredScripts.drop(stagePickerPage * pageSize).take(pageSize)
                for ((rowIdx, pair) in slice.chunked(2).withIndex()) {
                    val sRow = hRow(bottomMarginDp = if (totalPages > 1 || rowIdx < slice.chunked(2).lastIndex) 4 else 0)
                    pair.forEachIndexed { idx, sc ->
                        val isAct = curScript?.stageKey == sc.stageKey
                        sRow.addView(makeToggle("${sc.stageName} (${sc.waves.size})", isAct, p, weight = 1f, marginEndDp = if (idx == 1 || pair.size == 1) 0 else 4) {
                            if (isAct) onDeactivateStageScript() else onActivateStageScript(sc)
                        })
                    }
                    if (pair.size == 1) {
                        sRow.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f) })
                    }
                    stBody.addView(sRow)
                }
                if (totalPages > 1) {
                    val pgRow = hRow(bottomMarginDp = 0)
                    pgRow.addView(makeChip("‹", weight = 0.7f) {
                        stagePickerPage = (stagePickerPage - 1).coerceAtLeast(0)
                        rebuildContent(preserveScroll = false)
                    }.also { styleChip(it, p.chipOffBg, p.onSurface, p.chipOffStroke) })
                    pgRow.addView(TextView(context).apply {
                        text = "${s(R.string.ov_stage_page_fmt, stagePickerPage + 1, totalPages)} (${filteredScripts.size})"
                        textSize = 9.5f
                        typeface = Typeface.DEFAULT_BOLD
                        gravity = Gravity.CENTER
                        setTextColor(p.onSurfaceVariant)
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.6f)
                    })
                    pgRow.addView(makeChip("›", weight = 0.7f, marginEndDp = 0) {
                        stagePickerPage = (stagePickerPage + 1).coerceAtMost(totalPages - 1)
                        rebuildContent(preserveScroll = false)
                    }.also { styleChip(it, p.chipOffBg, p.onSurface, p.chipOffStroke) })
                    stBody.addView(pgRow)
                }
            }
        }

        // Card 2 (Teal): Battle Assist Modes (1:1 parity with Launch App Settings!)
        val invLabel = invertSummaryLabel()
        val q2ModeSummary = buildList {
            if (settings.holdReplanEnabled) {
                val trig = s(if (settings.holdReplanNudge) R.string.mode_hold_trigger_nudge else R.string.mode_hold_trigger_inplace)
                add("${s(R.string.ov_hold)}($trig)")
            }
            if (invLabel != null) add(invLabel)
        }.joinToString(" · ").ifEmpty { s(R.string.ov_card_none_set) }
        addAccordionGroupCard(2, s(R.string.section_modes), q2ModeSummary, settings.holdReplanEnabled || invLabel != null, ACCENT_TEAL, p, R.string.help_modes_title, R.string.help_modes_body)?.let { modeBody ->
            populateHoldAndInvertControls(modeBody, p)
        }

        // Card 3 (Amber): Unified 6-Tier Swipe Speed Presets (2s..7s)
        val activeSpeed = SwipeSpeedPreset.fromStepDurationMs(settings.stepDurationMs)
        val q3Summary = "${LocaleHelper.speedLabel(lctx, activeSpeed)} · ${settings.stepDurationMs}${s(R.string.unit_ms)}"
        addAccordionGroupCard(3, s(R.string.section_speed), q3Summary, true, ACCENT_AMBER, p, R.string.help_speed_title, R.string.help_speed_body)?.let { spdBody ->
            populateSpeedPresetSection(spdBody, p)
        }
    }

    // ==================== TAB 1: UNIFIED STRATEGY WORKBENCH (6 Distinct Bordered Group Cards!) ====================
    private fun buildUnifiedStrategyTab(p: OverlayPalette) {
        val cfg = activePreset.baseConfig
        val byId = activeChips.associateBy { it.id }
        val activeIds = byId.keys
        val goalLabel = LocaleHelper.goalSummary(lctx, cfg)

        // ---------- Sticky Compact Active Strategy & Wave-Shield Header Card (Dark & Light High-Contrast) ----------
        val topBg = if (p.isDark) Color.rgb(34, 30, 48) else Color.rgb(242, 236, 255)
        val topStroke = if (p.isDark) Color.argb(185, 148, 124, 224) else Color.argb(120, 123, 31, 162)
        val topTitleFg = if (p.isDark) Color.rgb(234, 226, 255) else Color.rgb(52, 24, 118)
        val topCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(topBg, dp(10).toFloat(), topStroke)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        val topBar = hRow(bottomMarginDp = if (activeChips.isNotEmpty()) 5 else 0)
        val activeBarSummary = if (activeChips.isNotEmpty()) {
            val shieldBrief = activeChips.take(2).joinToString(" / ") { LocaleHelper.formatWaveChipLabel(lctx, it) }
            "${s(R.string.unified_strategy_bar_title)} · $goalLabel · $shieldBrief"
        } else {
            "${s(R.string.unified_strategy_bar_title)} · $goalLabel"
        }
        topBar.addView(TextView(context).apply {
            text = activeBarSummary
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            setTextColor(topTitleFg)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
        })
        topBar.addView(makeChip(s(R.string.btn_reset_strategy), padH = 7, marginEndDp = 4) {
            expandedShapeElemIdx = null
            expandedCondElemIdx = null
            onResetStrategy()
        }.also { styleChip(it, p.chipOffBg, p.onSurface, p.outline) })
        if (activeChips.isNotEmpty()) {
            val clrBg = if (p.isDark) Color.rgb(92, 30, 38) else Color.rgb(255, 228, 232)
            val clrFg = if (p.isDark) Color.rgb(255, 206, 212) else Color.rgb(198, 40, 40)
            topBar.addView(makeChip(s(R.string.ov_clear_enemy_short, activeChips.size), padH = 6, marginEndDp = 4) {
                onClearWaveChips()
            }.also { styleChip(it, clrBg, clrFg, null) })
        }
        topBar.addView(makeHelpCircleView(p, ACCENT_PURPLE) {
            onShowDrawerHelp?.invoke(s(R.string.help_unified_strategy_title), s(R.string.help_unified_strategy_body))
        })
        topCard.addView(topBar)

        val hasAnyHint = activeVisualHints.hasMonochrome || activeVisualHints.hasDisguise
        if (activeChips.isNotEmpty() || bypassedGreySkills.isNotEmpty() || hasAnyHint) {
            if (activeChips.isNotEmpty()) {
                val chipScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
                val chipRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                for (c in activeChips) {
                    val pfx = when {
                        c.fromKnowledgeFallback -> s(R.string.ov_kb_chip_prefix)
                        c.autoDetected -> s(R.string.ov_ai_chip_prefix)
                        else -> ""
                    }
                    val pillBg = when {
                        c.autoDetected && p.isDark -> Color.rgb(24, 58, 68)
                        c.autoDetected -> Color.rgb(224, 247, 250)
                        p.isDark -> Color.rgb(52, 46, 76)
                        else -> Color.WHITE
                    }
                    val pillFg = when {
                        c.autoDetected && p.isDark -> Color.rgb(156, 239, 248)
                        c.autoDetected -> Color.rgb(0, 86, 98)
                        p.isDark -> Color.rgb(242, 238, 255)
                        else -> Color.rgb(49, 27, 146)
                    }
                    val pillStroke = if (c.autoDetected) Color.rgb(0, 172, 193) else if (p.isDark) Color.argb(170, 179, 157, 219) else ACCENT_PURPLE
                    chipRow.addView(makeChip("$pfx${LocaleHelper.formatWaveChipLabel(lctx, c)} ×", padH = 6) {
                        ActiveStrategyStore.removeChip(c.id)
                    }.also {
                        styleChip(it, pillBg, pillFg, pillStroke)
                    })
                }
                chipScroll.addView(chipRow)
                topCard.addView(chipScroll)
            }
            if (bypassedGreySkills.isNotEmpty() || hasAnyHint) {
                val extraScroll = HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = false
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
                }
                val extraRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                if (activeVisualHints.hasMonochrome) {
                    extraRow.addView(makeChip("${s(R.string.ov_hint_monochrome_pill)} ×", padH = 6) {
                        ActiveStrategyStore.dismissVisualHint("monochrome")
                    }.also { styleChip(it, p.secondaryContainer, p.onSecondaryContainer, ACCENT_PURPLE) })
                }
                if (activeVisualHints.hasDisguise) {
                    extraRow.addView(makeChip(s(R.string.ov_hint_disguise_edit_pill), padH = 6, marginEndDp = 2) {
                        onToggleBoardEditor()
                    }.also { styleChip(it, p.secondaryContainer, p.onSecondaryContainer, ACCENT_AMBER) })
                    extraRow.addView(makeChip("×", padH = 5) {
                        ActiveStrategyStore.dismissVisualHint("disguise")
                    }.also { styleChip(it, p.secondaryContainer, p.onSecondaryContainer, ACCENT_AMBER) })
                }
                for (sk in bypassedGreySkills) {
                    extraRow.addView(makeChip("⊘ ${sk.titleZhTw.ifBlank { sk.chips.joinToString { it.label } }}", padH = 6) {
                        onRestoreBypassedSkill(sk)
                    }.also {
                        styleChip(it, p.chipOffBg, p.onSurfaceVariant, p.outline)
                    })
                }
                extraScroll.addView(extraRow)
                topCard.addView(extraScroll)
            }
        }
        contentHost.addView(topCard)

        // ---------- Compute Live Summary Badges for All 6 Cards ----------
        val composedCfg = RuleComposer.compose(
            activePreset,
            activeChips.map { WaveShieldCatalog.canonicalizeForComposer(it) }
        )
        val comboChipIds = setOf(
            "min_combo", "max_combo", "min_first_combo", "min_first_elem_combo",
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
            "elem_burst_shield", "max_element_orbs", "keep_element_at_least_one",
            "anti_first_wave", "forbid_all", "clear_all_element", "unmatchable_elements",
            "digital_orb_count", "first_wave_full_clear", "any_enchanted_orb_shield", "race_orb_shield",
            "char_orb_first_wave", "starting_buoy_shield", "forbid_weakened_orb", "luminous_orb_shield"
        )
        val activeElemLabels = activeChips.filter { it.id in elemChipIds }.map { LocaleHelper.formatWaveChipLabel(lctx, it) }
        val customMatrixCount = (0..5).count { e ->
            composedCfg.elementRules.getOrElse(e) { ElementRule() } != ElementRule()
        }
        val card1Summary = buildList {
            addAll(activeElemLabels.take(2))
            if (customMatrixCount > 0) add("${s(R.string.tag_custom_shape)}×$customMatrixCount")
        }.joinToString(" · ").ifEmpty { s(R.string.ov_card_none_set) }
        val card1Highlight = activeElemLabels.isNotEmpty() || customMatrixCount > 0

        val shapeChipIds = setOf(
            "puzzle_shield", "strict_puzzle", "loose_puzzle", "enchanted_puzzle",
            "cross_shield", "cross_shield_2", "cross_shield_3", "forbid_cross",
            "l_shape", "t_shape", "square_2x2", "square_3x3", "all_columns_ameliorate"
        )
        val activeShapeLabels = activeChips.filter { it.id in shapeChipIds }.map { LocaleHelper.formatWaveChipLabel(lctx, it) }
        val colEnabledCnt = cfg.columnRules.take(6).count { it.enabled }
        val rowEnabledCnt = cfg.rowRules.take(5).count { it.enabled }
        val card2Summary = buildList {
            addAll(activeShapeLabels.take(2))
            if (colEnabledCnt > 0) add("${s(R.string.tag_col_amel)}×$colEnabledCnt")
            if (rowEnabledCnt > 0) add("横×$rowEnabledCnt")
        }.joinToString(" · ").ifEmpty { s(R.string.ov_card_none_set) }
        val card2Highlight = activeShapeLabels.isNotEmpty() || colEnabledCnt > 0 || rowEnabledCnt > 0

        val pathChipIds = setOf("traffic_light", "zero_trail_burning", "trail_corrosion", "step_hurt_minimize", "char_orb_terrain", "weijian_burst")
        val activePathLabels = activeChips.filter { it.id in pathChipIds }.map { LocaleHelper.formatWaveChipLabel(lctx, it) }
        val card3Summary = buildList {
            addAll(activePathLabels.take(2))
            if (cfg.maxBurningTileHurts > 0) add("燃≤${cfg.maxBurningTileHurts}")
            if (cfg.allowHitWeatheredToClear) add(s(R.string.switch_hit_weathered).take(6))
        }.joinToString(" · ").ifEmpty { s(R.string.ov_card_none_set) }
        val card3Highlight = activePathLabels.isNotEmpty() || cfg.allowHitWeatheredToClear || cfg.maxBurningTileHurts > 0 || cfg.maxMiasmaHits > 0

        val effSteps = if (cfg.useSystemSteps) settings.maxSteps else cfg.maxSteps
        val effBeam = if (cfg.useSystemBeam) settings.beamWidth else cfg.beamWidth
        val exactStepsChip = byId["exact_steps"]
        val stepLimitChip = byId["step_limit"]
        val stepRangeChip = byId["step_range"]
        val card4Summary = buildList {
            if (exactStepsChip != null) add(LocaleHelper.formatWaveChipLabel(lctx, exactStepsChip))
            else if (stepRangeChip != null) add(LocaleHelper.formatWaveChipLabel(lctx, stepRangeChip))
            else if (stepLimitChip != null) add(s(R.string.ov_step_limit_short, stepLimitChip.paramInt))
            else add("$effSteps${s(R.string.unit_steps)}")
            add(s(R.string.tag_beam_fmt, effBeam))
            if ("traffic_light" in activeIds) add(s(R.string.shield_traffic_light))
            if (cfg.useCharacterOrb) add(s(R.string.tag_char_orb_fmt, cfg.characterOrbCol + 1))
        }.joinToString(" · ")
        val card4Highlight = exactStepsChip != null || stepRangeChip != null || stepLimitChip != null || "traffic_light" in activeIds ||
            !cfg.useSystemSteps || !cfg.useSystemBeam || !cfg.useSystemDiagonal || cfg.useCharacterOrb || cfg.startPreset != StartPointPreset.ANY

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
        addAccordionGroupCard(0, s(R.string.ov_card_goal_combo), card0Summary, card0Highlight, ACCENT_INDIGO, p, R.string.help_combo_shield_title, R.string.help_goal_combo_full_body)?.let { body ->
            buildGoalAndComboCardBody(body, cfg, byId, activeIds, p)
        }

        // ==================== CARD ②: 属性与珠数 (Teal) ====================
        addAccordionGroupCard(1, s(R.string.ov_card_elem_count), card1Summary, card1Highlight, ACCENT_TEAL, p, R.string.help_elem_count_title, R.string.help_elem_matrix_full_body)?.let { body ->
            buildElementAndCountCardBody(body, cfg, composedCfg, byId, activeIds, p)
        }

        // ==================== CARD ③: 拼图与形状 (Amber) ====================
        addAccordionGroupCard(2, s(R.string.ov_card_puzzle_shape), card2Summary, card2Highlight, ACCENT_AMBER, p, R.string.help_puzzle_shape_title, R.string.help_puzzle_shape_full_body)?.let { body ->
            buildPuzzleAndShapeCardBody(body, cfg, byId, activeIds, p)
        }

        // ==================== CARD ④: 路径与地形 (Crimson) ====================
        addAccordionGroupCard(3, s(R.string.ov_card_path_safety), card3Summary, card3Highlight, ACCENT_CRIMSON, p, R.string.help_path_hazard_title, R.string.help_path_hazard_full_body)?.let { body ->
            buildPathAndSafetyCardBody(body, cfg, byId, activeIds, p)
        }

        // ==================== CARD ⑤: 预算与起手 (Blue) ====================
        addAccordionGroupCard(4, s(R.string.ov_card_budget_start), card4Summary, card4Highlight, ACCENT_BLUE, p, R.string.help_basic_steps_title, R.string.help_budget_start_full_body)?.let { body ->
            buildBudgetAndStartCardBody(body, cfg, byId, activeIds, p)
        }

        // ==================== CARD ⑥: 队长与特性 (Purple) ====================
        addAccordionGroupCard(5, s(R.string.ov_card_leader_traits), card5Summary, card5Highlight, ACCENT_PURPLE, p, R.string.help_strategy_switches_title, R.string.help_leader_traits_full_body)?.let { body ->
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
            "max_combo" in activeIds -> "max_combo"
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
                "max_combo" -> WaveShieldChip("max_combo", "${s(R.string.combo_type_max_total)} ≤$maxTotalComboVal", ShieldCategory.COMBO, paramInt = maxTotalComboVal)
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
                        "any_enchanted_orb_shield", "race_orb_shield", "char_orb_first_wave", "starting_buoy_shield", "luminous_orb_shield"
                    ).forEach { ActiveStrategyStore.removeChip(it) }
                    WaveShieldChip("zero_combo", s(R.string.combo_type_zero), ShieldCategory.COMBO)
                }
                "odd_combo" -> WaveShieldChip("odd_combo", s(R.string.combo_type_odd), ShieldCategory.COMBO, paramInt = if (parityFirstOnly) 0 else 1)
                "even_combo" -> WaveShieldChip("even_combo", s(R.string.combo_type_even), ShieldCategory.COMBO, paramInt = if (parityFirstOnly) 0 else 1)
                else -> null
            }
            ActiveStrategyStore.replaceGroup(WaveShieldCatalog.COMBO_EXCLUSIVE_IDS, chip)
        }
        fun isAi(id: String): Boolean = byId[id]?.autoDetected == true
        val cRow1 = hRow(bottomMarginDp = 4)
        listOf("none" to s(R.string.combo_type_none), "min_combo" to s(R.string.combo_type_min_total), "min_first_combo" to s(R.string.combo_type_min_first), "min_first_elem_combo" to s(R.string.combo_type_min_first_elem))
            .forEachIndexed { i, (id, lbl) -> cRow1.addView(makeToggle(lbl, curComboMode == id, p, weight = 1f, marginEndDp = if (i == 3) 0 else 3, aiHighlighted = id != "none" && curComboMode == id && isAi(id)) { applyCombo(id) }) }
        body.addView(cRow1)
        val cRow2 = hRow(bottomMarginDp = 4)
        listOf("max_combo" to s(R.string.combo_type_max_total), "exact_combo" to s(R.string.combo_type_exact_total), "exact_first_combo" to s(R.string.combo_type_exact_first), "zero_combo" to s(R.string.combo_type_zero))
            .forEachIndexed { i, (id, lbl) -> cRow2.addView(makeToggle(lbl, curComboMode == id, p, weight = 1f, marginEndDp = if (i == 3) 0 else 3, aiHighlighted = curComboMode == id && isAi(id)) { applyCombo(id) }) }
        body.addView(cRow2)
        val cRow3 = hRow(bottomMarginDp = 4)
        listOf("wave1_same_kind" to s(R.string.combo_type_wave1_same_kind), "wave1_distinct_kind" to s(R.string.combo_type_wave1_distinct_kind), "odd_combo" to s(R.string.combo_type_odd), "even_combo" to s(R.string.combo_type_even))
            .forEachIndexed { i, (id, lbl) -> cRow3.addView(makeToggle(lbl, curComboMode == id, p, weight = 1f, marginEndDp = if (i == 3) 0 else 3, aiHighlighted = curComboMode == id && isAi(id)) { applyCombo(id) }) }
        body.addView(cRow3)

        if (curComboMode in setOf("min_combo", "max_combo", "min_first_combo", "min_first_elem_combo", "exact_combo", "exact_first_combo", "wave1_same_kind", "wave1_distinct_kind")) {
            val activeVal = when (curComboMode) {
                "min_combo" -> minTotalComboVal
                "max_combo" -> maxTotalComboVal
                "min_first_combo" -> minFirstComboVal
                "min_first_elem_combo" -> minFirstElemVal
                "exact_combo" -> exactTotalComboVal
                "exact_first_combo" -> exactFirstComboVal
                else -> wave1KindVal
            }
            fun stepC(delta: Int) {
                when (curComboMode) {
                    "min_combo" -> minTotalComboVal = (minTotalComboVal + delta).coerceIn(1, 15)
                    "max_combo" -> maxTotalComboVal = (maxTotalComboVal + delta).coerceIn(1, 20)
                    "min_first_combo" -> minFirstComboVal = (minFirstComboVal + delta).coerceIn(1, 12)
                    "min_first_elem_combo" -> minFirstElemVal = (minFirstElemVal + delta).coerceIn(1, 12)
                    "exact_combo" -> exactTotalComboVal = (exactTotalComboVal + delta).coerceIn(1, 15)
                    "exact_first_combo" -> exactFirstComboVal = (exactFirstComboVal + delta).coerceIn(1, 12)
                    "wave1_same_kind", "wave1_distinct_kind" -> wave1KindVal = (wave1KindVal + delta).coerceIn(1, 10)
                }
                applyCombo(curComboMode)
            }
            val valText = when {
                curComboMode == "max_combo" -> s(R.string.combo_value_max_fmt, activeVal)
                curComboMode.startsWith("min") -> s(R.string.combo_value_min_fmt, activeVal)
                else -> s(R.string.combo_value_exact_fmt, activeVal)
            }
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
            exRow1.addView(makeToggle(s(R.string.combo_extra_same_c), "same_combo_elements" in activeIds, p, weight = 1f, aiHighlighted = isAi("same_combo_elements")) {
                onToggleWaveChip(WaveShieldChip("same_combo_elements", s(R.string.combo_extra_same_c), ShieldCategory.COMBO))
            })
            exRow1.addView(makeToggle(s(R.string.shield_no_cascade), "no_cascade" in activeIds, p, weight = 1f, aiHighlighted = isAi("no_cascade")) {
                onToggleWaveChip(WaveShieldChip("no_cascade", s(R.string.shield_no_cascade), ShieldCategory.COMBO))
            })
            exRow1.addView(makeToggle(s(R.string.combo_extra_second_wave, secondWaveVal), "second_wave_combo" in activeIds, p, weight = 1f, marginEndDp = 0, aiHighlighted = isAi("second_wave_combo")) {
                onToggleWaveChip(WaveShieldChip("second_wave_combo", s(R.string.combo_extra_second_wave, secondWaveVal), ShieldCategory.COMBO, paramInt = secondWaveVal))
            })
            body.addView(exRow1)
            val exRow2 = hRow(bottomMarginDp = 4)
            val sameMask = if (sameElemTarget in 0..5) (1 shl sameElemTarget) else 0
            exRow2.addView(makeToggle(s(R.string.combo_extra_same_elem, sameElemGroupsVal), "same_elem_combos" in activeIds, p, weight = 1f, aiHighlighted = isAi("same_elem_combos")) {
                onToggleWaveChip(WaveShieldChip("same_elem_combos", s(R.string.combo_extra_same_elem, sameElemGroupsVal), ShieldCategory.COMBO, paramInt = sameElemGroupsVal, paramElementMask = sameMask))
            })
            val eqJoined = (0..5).filter { (equalElemsMask and (1 shl it)) != 0 }.joinToString("=") { LocaleHelper.elementLabel(lctx, it) }
            val eqLbl = s(R.string.combo_extra_equal_multi_fmt, eqJoined)
            exRow2.addView(makeToggle(eqLbl, "equal_pair_light_dark" in activeIds, p, weight = 1f, marginEndDp = 0, aiHighlighted = isAi("equal_pair_light_dark")) {
                onToggleWaveChip(WaveShieldChip("equal_pair_light_dark", eqLbl, ShieldCategory.COMBO, paramElementMask = 0x100 or equalElemsMask))
            })
            body.addView(exRow2)
            if ("equal_pair_light_dark" in activeIds) {
                val eqRow = hRow(bottomMarginDp = 4)
                for (e in 0..5) {
                    val bit = 1 shl e
                    eqRow.addView(makeElementBtn(e, (equalElemsMask and bit) != 0, p, weight = 1f, marginEndDp = if (e == 5) 0 else 3, aiHighlighted = isAi("equal_pair_light_dark")) {
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
        composedCfg: RuleConfig,
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

        fun isAi(id: String): Boolean = byId[id]?.autoDetected == true
        val reqAi = listOf("require_elements", "three_elements", "five_elements", "five_elements_enchanted").any { isAi(it) }

        // Sub-group A: Min Distinct + Required + Per-Color Enchanted
        body.addView(makeSubLabel(s(R.string.elem_min_distinct_label), p))
        val curMinDist = byId["min_distinct_elements"]?.paramInt ?: 0
        val distRow = hRow(bottomMarginDp = 4)
        listOf(0, 2, 3, 4, 5, 6).forEachIndexed { idx, cnt ->
            val lbl = if (cnt == 0) s(R.string.elem_min_distinct_any) else s(R.string.elem_min_distinct_fmt, cnt)
            distRow.addView(makeToggle(lbl, curMinDist == cnt, p, weight = 1f, marginEndDp = if (idx == 5) 0 else 3, aiHighlighted = cnt > 0 && curMinDist == cnt && isAi("min_distinct_elements")) {
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
        val keepOneMask = readMask("keep_element_at_least_one")
        val unmatchMask = readMask("unmatchable_elements")

        body.addView(makeSubLabel("${s(R.string.elem_req_label)} / ${s(R.string.elem_req_ench_row)}", p))
        val reqRow = hRow(bottomMarginDp = 3)
        for (e in 0..5) {
            val bit = 1 shl e
            reqRow.addView(makeElementBtn(e, (effReq and bit) != 0, p, weight = 1f, marginEndDp = if (e == 5) 0 else 3, aiHighlighted = reqAi) {
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
            enchRow.addView(makeElementBtn(e, on, p, suffix = "+", weight = 1f, marginEndDp = if (e == 5) 0 else 3, aiHighlighted = reqAi) {
                val turningOn = !on
                val nReq = if (turningOn) (effReq or bit) else effReq
                val nEnch = if (turningOn) (effEnch or bit) else (effEnch and bit.inv())
                if (turningOn && (forbidMask and bit) != 0) writeLimit("forbid_all", s(R.string.elem_limit_forbid), forbidMask and bit.inv())
                writeReq(nReq, nEnch)
            })
        }
        body.addView(enchRow)

        // Sub-group B: Single-Color Burst (3..30 orbs) & Max Element Orbs Shield (≤N orbs)
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
        val bRow = hRow(bottomMarginDp = if (burstMask != 0) 4 else 3)
        for (e in 0..5) {
            val bit = 1 shl e
            bRow.addView(makeElementBtn(e, (burstMask and bit) != 0, p, weight = 1f, marginEndDp = if (e == 5) 0 else 3, aiHighlighted = isAi("elem_burst_shield")) {
                saveBurst(burstMask xor bit, burstOrbsVal)
            })
        }
        body.addView(bRow)
        if (burstMask != 0) {
            body.addView(makeStepperRow("≥ $burstOrbsVal ${s(R.string.unit_orbs)}", p, {
                burstOrbsVal = (burstOrbsVal - 1).coerceIn(3, 30); saveBurst(burstMask, burstOrbsVal)
            }, {
                burstOrbsVal = (burstOrbsVal + 1).coerceIn(3, 30); saveBurst(burstMask, burstOrbsVal)
            }))
        }

        body.addView(makeSubLabel(s(R.string.elem_max_orbs_label), p))
        val maxOrbsMask = (byId["max_element_orbs"]?.paramElementMask ?: 0) and 0x3F
        fun saveMaxElemOrbs(mask: Int, maxOrbs: Int) {
            if (mask == 0) ActiveStrategyStore.removeChip("max_element_orbs")
            else {
                val names = (0..5).filter { (mask and (1 shl it)) != 0 }.joinToString("") { LocaleHelper.elementLabel(lctx, it) }
                onUpsertWaveChip(WaveShieldChip("max_element_orbs", s(R.string.elem_max_orbs_fmt, names, maxOrbs), ShieldCategory.COUNT_AND_ELEMENT, paramInt = maxOrbs, paramElementMask = mask))
            }
        }
        val mxRow = hRow(bottomMarginDp = if (maxOrbsMask != 0) 4 else 2)
        for (e in 0..5) {
            val bit = 1 shl e
            mxRow.addView(makeElementBtn(e, (maxOrbsMask and bit) != 0, p, weight = 1f, marginEndDp = if (e == 5) 0 else 3, aiHighlighted = isAi("max_element_orbs")) {
                saveMaxElemOrbs(maxOrbsMask xor bit, maxElemOrbsVal)
            })
        }
        body.addView(mxRow)
        if (maxOrbsMask != 0) {
            body.addView(makeStepperRow("≤ $maxElemOrbsVal ${s(R.string.unit_orbs)}", p, {
                maxElemOrbsVal = (maxElemOrbsVal - 1).coerceIn(0, 30); saveMaxElemOrbs(maxOrbsMask, maxElemOrbsVal)
            }, {
                maxElemOrbsVal = (maxElemOrbsVal + 1).coerceIn(0, 30); saveMaxElemOrbs(maxOrbsMask, maxElemOrbsVal)
            }))
        }

        // Sub-group C: Attribute Limits (Anti-1st / Forbid All / Clear All 1st / Keep ≥1 / Unmatchable)
        body.addView(makeSubDivider(p))
        body.addView(makeSubLabel(s(R.string.elem_forbid_label), p))
        fun fmtLimTab(baseLbl: String, mask: Int): String {
            val cnt = Integer.bitCount(mask and 0x3F)
            return if (cnt > 0) "$baseLbl($cnt)" else baseLbl
        }
        val limTabs1 = hRow(bottomMarginDp = 3)
        listOf(
            Triple(0, fmtLimTab(s(R.string.elem_limit_anti), antiMask), antiMask != 0 && isAi("anti_first_wave")),
            Triple(1, fmtLimTab(s(R.string.elem_limit_forbid), forbidMask), forbidMask != 0 && isAi("forbid_all")),
            Triple(2, fmtLimTab(s(R.string.elem_limit_clear_all), clearMask), clearMask != 0 && isAi("clear_all_element"))
        ).forEachIndexed { idx, (modeId, lbl, modeAi) ->
            limTabs1.addView(makeToggle(lbl, elemLimitMode == modeId, p, weight = 1f, marginEndDp = if (idx == 2) 0 else 3, aiHighlighted = modeAi) {
                elemLimitMode = modeId; rebuildContent(preserveScroll = true)
            })
        }
        body.addView(limTabs1)
        val limTabs2 = hRow(bottomMarginDp = 3)
        listOf(
            Triple(3, fmtLimTab(s(R.string.elem_limit_keep_one), keepOneMask), keepOneMask != 0 && isAi("keep_element_at_least_one")),
            Triple(4, fmtLimTab(s(R.string.elem_limit_unmatchable), unmatchMask), unmatchMask != 0 && isAi("unmatchable_elements"))
        ).forEachIndexed { idx, (modeId, lbl, modeAi) ->
            limTabs2.addView(makeToggle(lbl, elemLimitMode == modeId, p, weight = 1f, marginEndDp = if (idx == 1) 0 else 3, aiHighlighted = modeAi) {
                elemLimitMode = modeId; rebuildContent(preserveScroll = true)
            })
        }
        body.addView(limTabs2)
        val curLimMask = when (elemLimitMode) {
            0 -> antiMask; 1 -> forbidMask; 2 -> clearMask; 3 -> keepOneMask; else -> unmatchMask
        }
        val curLimAi = when (elemLimitMode) {
            0 -> isAi("anti_first_wave")
            1 -> isAi("forbid_all")
            2 -> isAi("clear_all_element")
            3 -> isAi("keep_element_at_least_one")
            else -> isAi("unmatchable_elements")
        }
        val limRow = hRow(bottomMarginDp = 4)
        for (e in 0..5) {
            val bit = 1 shl e
            limRow.addView(makeElementBtn(e, (curLimMask and bit) != 0, p, weight = 1f, marginEndDp = if (e == 5) 0 else 3, aiHighlighted = curLimAi) {
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
                            if ((keepOneMask and bit) != 0) writeLimit("keep_element_at_least_one", s(R.string.elem_limit_keep_one), keepOneMask and bit.inv())
                        }
                    }
                    3 -> {
                        writeLimit("keep_element_at_least_one", s(R.string.elem_limit_keep_one), nxt)
                        if (on && (clearMask and bit) != 0) {
                            writeLimit("clear_all_element", s(R.string.elem_limit_clear_all), clearMask and bit.inv())
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
        cntRow1.addView(makeToggle(s(R.string.shield_digital_orbs_fmt, digitalOrbsVal), "digital_orb_count" in activeIds, p, weight = 1f, aiHighlighted = isAi("digital_orb_count")) {
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
        cntRow1.addView(makeToggle(s(R.string.shield_full_clear), "first_wave_full_clear" in activeIds, p, weight = 1f, marginEndDp = 0, aiHighlighted = isAi("first_wave_full_clear")) {
            if ("first_wave_full_clear" in activeIds) ActiveStrategyStore.removeChip("first_wave_full_clear")
            else {
                ActiveStrategyStore.removeChip("digital_orb_count"); ActiveStrategyStore.removeChip("zero_combo")
                onUpsertWaveChip(WaveShieldChip("first_wave_full_clear", s(R.string.shield_full_clear), ShieldCategory.COUNT_AND_ELEMENT))
            }
        })
        body.addView(cntRow1)

        val spRow1 = hRow(bottomMarginDp = 4)
        spRow1.addView(makeToggle(s(R.string.shield_any_enchanted_orb), "any_enchanted_orb_shield" in activeIds, p, weight = 1f, aiHighlighted = isAi("any_enchanted_orb_shield")) {
            onToggleWaveChip(WaveShieldChip("any_enchanted_orb_shield", s(R.string.shield_any_enchanted_orb), ShieldCategory.COUNT_AND_ELEMENT))
        })
        spRow1.addView(makeToggle(s(R.string.shield_race_orb), "race_orb_shield" in activeIds, p, weight = 1f, aiHighlighted = isAi("race_orb_shield")) {
            onToggleWaveChip(WaveShieldChip("race_orb_shield", s(R.string.shield_race_orb), ShieldCategory.COUNT_AND_ELEMENT))
        })
        spRow1.addView(makeToggle(s(R.string.shield_char_orb_first), "char_orb_first_wave" in activeIds, p, weight = 1f, marginEndDp = 0, aiHighlighted = isAi("char_orb_first_wave")) {
            onToggleWaveChip(WaveShieldChip("char_orb_first_wave", s(R.string.shield_char_orb_first), ShieldCategory.COUNT_AND_ELEMENT))
        })
        body.addView(spRow1)

        val spRow2 = hRow(bottomMarginDp = 4)
        spRow2.addView(makeToggle(s(R.string.shield_starting_buoy), "starting_buoy_shield" in activeIds, p, weight = 1f, aiHighlighted = isAi("starting_buoy_shield")) {
            onToggleWaveChip(WaveShieldChip("starting_buoy_shield", s(R.string.shield_starting_buoy), ShieldCategory.COUNT_AND_ELEMENT))
        })
        spRow2.addView(makeToggle(s(R.string.shield_forbid_weakened), "forbid_weakened_orb" in activeIds, p, weight = 1f, aiHighlighted = isAi("forbid_weakened_orb")) {
            onToggleWaveChip(WaveShieldChip("forbid_weakened_orb", s(R.string.shield_forbid_weakened), ShieldCategory.COUNT_AND_ELEMENT))
        })
        spRow2.addView(makeToggle(s(R.string.shield_luminous_orb), "luminous_orb_shield" in activeIds, p, weight = 1f, marginEndDp = 0, aiHighlighted = isAi("luminous_orb_shield")) {
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

        // Sub-group E: Enhanced 6x5 Per-Element Rules Matrix (2-Way Synced + Inline 17-Shape & Condition Panels)
        body.addView(makeSubDivider(p))
        val customMatrixCnt = (0..5).count { e ->
            composedCfg.elementRules.getOrElse(e) { ElementRule() } != ElementRule()
        }
        val matrixHdrRow = hRow(bottomMarginDp = 3)
        matrixHdrRow.addView(TextView(context).apply {
            text = s(R.string.section_elem_matrix)
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(p.onSurfaceVariant)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        matrixHdrRow.addView(makeChip(s(R.string.btn_reset_matrix), padH = 6, marginEndDp = 0) {
            if (customMatrixCnt > 0) {
                expandedShapeElemIdx = null
                expandedCondElemIdx = null
                listOf("anti_first_wave", "forbid_all", "keep_element_at_least_one", "clear_all_element", "max_element_orbs", "elem_burst_shield").forEach {
                    ActiveStrategyStore.removeChip(it)
                }
                onUpdateActivePresetConfig { cur -> cur.copy(elementRules = List(6) { ElementRule() }) }
            }
        }.also {
            it.textSize = 9f
            it.alpha = if (customMatrixCnt > 0) 1f else 0.48f
            styleChip(it, p.chipOffBg, if (customMatrixCnt > 0) p.primary else p.onSurfaceVariant, p.outline)
        })
        body.addView(matrixHdrRow)

        val hdrRow = hRow(bottomMarginDp = 3)
        listOf(
            s(R.string.col_hdr_elem) to 0.62f, s(R.string.col_hdr_shape) to 1.05f,
            s(R.string.col_hdr_priority) to 0.92f, s(R.string.col_hdr_timing) to 0.92f, s(R.string.col_hdr_cond) to 0.98f
        ).forEach { (title, w) ->
            hdrRow.addView(TextView(context).apply {
                text = title; textSize = 9f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                setTextColor(p.onSurfaceVariant)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w)
            })
        }
        body.addView(hdrRow)

        val shapeOrder = listOf(
            TargetShape.ANY, TargetShape.SPLIT_3, TargetShape.COUNT_2, TargetShape.COUNT_4,
            TargetShape.COUNT_5, TargetShape.COUNT_6, TargetShape.COUNT_7, TargetShape.COUNT_8,
            TargetShape.COUNT_9, TargetShape.ROW, TargetShape.COLUMN, TargetShape.CROSS,
            TargetShape.L_SHAPE, TargetShape.T_SHAPE, TargetShape.SQUARE_2X2, TargetShape.SQUARE_3X3,
            TargetShape.CLEAR_ALL
        )
        for (e in 0..5) {
            val baseEr = cfg.elementRules.getOrElse(e) { ElementRule() }
            val effEr = composedCfg.elementRules.getOrElse(e) { baseEr }
            val dispShape = if (effEr.clearAllFirstWave) TargetShape.CLEAR_ALL else effEr.targetShape
            val dispTiming = if (effEr.timing == MatchTiming.ANY && (composedCfg.requiredFirstWaveElementsMask and (1 shl e)) != 0) {
                MatchTiming.MUST_FIRST_WAVE
            } else {
                effEr.timing
            }
            val condParts = buildList {
                if (effEr.minMatchCount != 3) add(s(R.string.min_match_fmt, effEr.minMatchCount))
                when {
                    effEr.exactFirstWaveOrbs >= 0 -> add("=${effEr.exactFirstWaveOrbs}")
                    effEr.maxTotalOrbs >= 0 -> add("≤${effEr.maxTotalOrbs}")
                    effEr.minTotalOrbs > 0 || effEr.minFirstWaveOrbs > 0 -> add("≥${maxOf(effEr.minTotalOrbs, effEr.minFirstWaveOrbs)}")
                }
                if (effEr.minFirstWaveCombos > 0) add("≥${effEr.minFirstWaveCombos}组")
                if (effEr.requireEnchanted) add("+")
                if (effEr.requireRace) add("族")
            }
            val condSummary = if (condParts.isEmpty()) s(R.string.min_match_fmt, 3) else condParts.joinToString("·")
            val hasCustomCond = condParts.isNotEmpty()
            val shapeAi = dispShape != (if (baseEr.clearAllFirstWave) TargetShape.CLEAR_ALL else baseEr.targetShape)
            val prioAi = effEr.priority != baseEr.priority
            val timingAi = dispTiming != baseEr.timing
            val condAi = effEr.minTotalOrbs != baseEr.minTotalOrbs || effEr.maxTotalOrbs != baseEr.maxTotalOrbs ||
                effEr.requireEnchanted != baseEr.requireEnchanted
            val isShapeOpen = expandedShapeElemIdx == e
            val isCondOpen = expandedCondElemIdx == e
            val row = hRow(bottomMarginDp = if (e == 5 && !isShapeOpen && !isCondOpen) 0 else 3)
            row.addView(makeElementBtn(e, true, p, weight = 0.62f, marginEndDp = 3) {})
            row.addView(makeToggle(LocaleHelper.targetShapeLabel(lctx, dispShape), dispShape != TargetShape.ANY || isShapeOpen, p, weight = 1.05f, marginEndDp = 3, aiHighlighted = shapeAi) {
                expandedCondElemIdx = null
                expandedShapeElemIdx = if (isShapeOpen) null else e
                rebuildContent(preserveScroll = true)
            })
            row.addView(makeToggle(LocaleHelper.elementPriorityLabel(lctx, effEr.priority), effEr.priority != ElementPriority.NORMAL, p, weight = 0.92f, marginEndDp = 3, aiHighlighted = prioAi) {
                val nxt = when (effEr.priority) {
                    ElementPriority.NORMAL -> ElementPriority.HIGH
                    ElementPriority.HIGH -> ElementPriority.KEEP_AT_LEAST_ONE
                    ElementPriority.KEEP_AT_LEAST_ONE -> ElementPriority.RESERVE
                    ElementPriority.RESERVE -> ElementPriority.NORMAL
                }
                if (nxt != ElementPriority.KEEP_AT_LEAST_ONE) ActiveStrategyStore.removeElementFromChip("keep_element_at_least_one", e)
                if (nxt != ElementPriority.HIGH) ActiveStrategyStore.removeElementFromChip("elem_burst_shield", e)
                onUpdateActivePresetConfig { cur ->
                    cur.copy(elementRules = cur.elementRules.toMutableList().also { it[e] = baseEr.copy(priority = nxt) })
                }
            })
            row.addView(makeToggle(LocaleHelper.matchTimingLabel(lctx, dispTiming), dispTiming != MatchTiming.ANY, p, weight = 0.92f, marginEndDp = 3, aiHighlighted = timingAi) {
                val nxt = when (dispTiming) {
                    MatchTiming.ANY -> MatchTiming.MUST_FIRST_WAVE
                    MatchTiming.MUST_FIRST_WAVE -> MatchTiming.FORBID_FIRST_WAVE_ALLOW_CASCADE
                    MatchTiming.FORBID_FIRST_WAVE_ALLOW_CASCADE -> MatchTiming.FORBID_ALL
                    MatchTiming.FORBID_ALL -> MatchTiming.ANY
                }
                ActiveStrategyStore.removeElementFromChip("anti_first_wave", e)
                ActiveStrategyStore.removeElementFromChip("forbid_all", e)
                onUpdateActivePresetConfig { cur ->
                    cur.copy(elementRules = cur.elementRules.toMutableList().also { it[e] = baseEr.copy(timing = nxt) })
                }
            })
            row.addView(makeToggle("$condSummary▾", hasCustomCond || isCondOpen, p, weight = 0.98f, marginEndDp = 0, aiHighlighted = condAi) {
                expandedShapeElemIdx = null
                expandedCondElemIdx = if (isCondOpen) null else e
                rebuildContent(preserveScroll = true)
            })
            body.addView(row)

            // Inline 17-Shape Picker expanded directly under the clicked row (NO dialog!)
            if (isShapeOpen) {
                val pickerBox = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    val boxBg = if (p.isDark) Color.rgb(26, 31, 40) else Color.rgb(245, 248, 252)
                    background = roundedBg(boxBg, dp(8).toFloat(), LocaleHelper.elementBadgeArgb(e))
                    setPadding(dp(5), dp(5), dp(5), dp(5))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = if (e == 5) 0 else dp(4) }
                }
                val rows4 = shapeOrder.chunked(4)
                rows4.forEachIndexed { rIdx, rowShapes ->
                    val shRow = hRow(bottomMarginDp = if (rIdx == rows4.lastIndex) 0 else 3)
                    rowShapes.forEachIndexed { cIdx, sh ->
                        shRow.addView(makeToggle(LocaleHelper.targetShapeLabel(lctx, sh), sh == dispShape, p, weight = 1f, marginEndDp = if (cIdx == rowShapes.lastIndex) 0 else 3) {
                            if (sh != TargetShape.CLEAR_ALL) ActiveStrategyStore.removeElementFromChip("clear_all_element", e)
                            expandedShapeElemIdx = null
                            onUpdateActivePresetConfig { cur ->
                                cur.copy(
                                    elementRules = cur.elementRules.toMutableList().also {
                                        it[e] = baseEr.copy(targetShape = sh, clearAllFirstWave = sh == TargetShape.CLEAR_ALL)
                                    }
                                )
                            }
                        })
                    }
                    if (rowShapes.size < 4) {
                        repeat(4 - rowShapes.size) {
                            shRow.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f) })
                        }
                    }
                    pickerBox.addView(shRow)
                }
                body.addView(pickerBox)
            }

            // Inline Advanced Condition Micro-Panel (NO dialog!)
            if (isCondOpen) {
                val curThreshMode = when {
                    effEr.exactFirstWaveOrbs >= 0 -> 2
                    effEr.maxTotalOrbs >= 0 -> 3
                    effEr.minTotalOrbs > 0 || effEr.minFirstWaveOrbs > 0 -> 1
                    else -> 0
                }
                val curThreshNum = when (curThreshMode) {
                    1 -> maxOf(effEr.minTotalOrbs, effEr.minFirstWaveOrbs).coerceIn(1, 30)
                    2 -> effEr.exactFirstWaveOrbs.coerceIn(1, 30)
                    3 -> effEr.maxTotalOrbs.coerceIn(0, 30)
                    else -> 6
                }
                fun commitRule(updated: ElementRule) {
                    onUpdateActivePresetConfig { cur ->
                        cur.copy(elementRules = cur.elementRules.toMutableList().also { it[e] = updated })
                    }
                }
                val condBox = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    val boxBg = if (p.isDark) Color.rgb(26, 31, 40) else Color.rgb(245, 248, 252)
                    background = roundedBg(boxBg, dp(8).toFloat(), LocaleHelper.elementBadgeArgb(e))
                    setPadding(dp(6), dp(5), dp(6), dp(6))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = if (e == 5) 0 else dp(4) }
                }
                condBox.addView(makeSubLabel(s(R.string.matrix_cond_title, LocaleHelper.elementLabel(lctx, e)), p))
                val mmRow = hRow(bottomMarginDp = 3)
                for (m in 1..5) {
                    mmRow.addView(makeToggle(s(R.string.min_match_fmt, m), effEr.minMatchCount == m, p, weight = 1f, marginEndDp = if (m == 5) 0 else 3) {
                        commitRule(baseEr.copy(minMatchCount = m))
                    })
                }
                condBox.addView(mmRow)

                val thRow = hRow(bottomMarginDp = if (curThreshMode != 0) 3 else 3)
                listOf(
                    0 to s(R.string.matrix_cond_any),
                    1 to s(R.string.matrix_cond_min_orbs_fmt, curThreshNum),
                    2 to s(R.string.matrix_cond_exact_orbs_fmt, curThreshNum),
                    3 to s(R.string.matrix_cond_max_orbs_fmt, curThreshNum)
                ).forEachIndexed { idx, (modeIdx, mLbl) ->
                    thRow.addView(makeToggle(mLbl, curThreshMode == modeIdx, p, weight = 1f, marginEndDp = if (idx == 3) 0 else 3) {
                        ActiveStrategyStore.removeElementFromChip("elem_burst_shield", e)
                        ActiveStrategyStore.removeElementFromChip("max_element_orbs", e)
                        val n = curThreshNum.coerceIn(if (modeIdx == 3) 0 else 1, 30)
                        commitRule(
                            baseEr.copy(
                                minFirstWaveOrbs = if (modeIdx == 1) n.coerceAtMost(15) else 0,
                                minTotalOrbs = if (modeIdx == 1) n else 0,
                                exactFirstWaveOrbs = if (modeIdx == 2) n else -1,
                                maxTotalOrbs = if (modeIdx == 3) n else -1
                            )
                        )
                    })
                }
                condBox.addView(thRow)
                if (curThreshMode != 0) {
                    condBox.addView(makeStepperRow("$curThreshNum ${s(R.string.unit_orbs)}", p, {
                        val nextN = (curThreshNum - 1).coerceIn(if (curThreshMode == 3) 0 else 1, 30)
                        ActiveStrategyStore.removeElementFromChip("elem_burst_shield", e)
                        ActiveStrategyStore.removeElementFromChip("max_element_orbs", e)
                        commitRule(
                            baseEr.copy(
                                minFirstWaveOrbs = if (curThreshMode == 1) nextN.coerceAtMost(15) else 0,
                                minTotalOrbs = if (curThreshMode == 1) nextN else 0,
                                exactFirstWaveOrbs = if (curThreshMode == 2) nextN else -1,
                                maxTotalOrbs = if (curThreshMode == 3) nextN else -1
                            )
                        )
                    }, {
                        val nextN = (curThreshNum + 1).coerceIn(1, 30)
                        ActiveStrategyStore.removeElementFromChip("elem_burst_shield", e)
                        ActiveStrategyStore.removeElementFromChip("max_element_orbs", e)
                        commitRule(
                            baseEr.copy(
                                minFirstWaveOrbs = if (curThreshMode == 1) nextN.coerceAtMost(15) else 0,
                                minTotalOrbs = if (curThreshMode == 1) nextN else 0,
                                exactFirstWaveOrbs = if (curThreshMode == 2) nextN else -1,
                                maxTotalOrbs = if (curThreshMode == 3) nextN else -1
                            )
                        )
                    }))
                }

                val grpRow = hRow(bottomMarginDp = 3)
                for (g in 0..4) {
                    val gLbl = if (g == 0) s(R.string.matrix_cond_any) else s(R.string.matrix_cond_groups_fmt, g)
                    grpRow.addView(makeToggle(gLbl, baseEr.minFirstWaveCombos == g, p, weight = 1f, marginEndDp = if (g == 4) 0 else 3) {
                        commitRule(baseEr.copy(minFirstWaveCombos = g))
                    })
                }
                condBox.addView(grpRow)

                val spCondRow = hRow(bottomMarginDp = 0)
                spCondRow.addView(makeToggle(s(R.string.matrix_cond_req_ench), effEr.requireEnchanted, p, weight = 1f) {
                    commitRule(baseEr.copy(requireEnchanted = !effEr.requireEnchanted))
                })
                spCondRow.addView(makeToggle(s(R.string.matrix_cond_req_race), effEr.requireRace, p, weight = 1f, marginEndDp = 0) {
                    commitRule(baseEr.copy(requireRace = !effEr.requireRace))
                })
                condBox.addView(spCondRow)
                body.addView(condBox)
            }
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
        fun isAi(id: String): Boolean = byId[id]?.autoDetected == true
        val puzAi = WaveShieldCatalog.PUZZLE_EXCLUSIVE_IDS.any { isAi(it) }
        val pzRow = hRow(bottomMarginDp = 4)
        pzRow.addView(makeToggle(s(R.string.puzzle_mode_off), pMode == 0, p, weight = 0.8f) { savePuz(0, false, -1, 0) })
        pzRow.addView(makeToggle(s(R.string.puzzle_mode_normal), pMode == 1, p, weight = 1f, aiHighlighted = pMode == 1 && puzAi) { savePuz(1, pEnch, pElem, pShape) })
        pzRow.addView(makeToggle(s(R.string.puzzle_mode_strict), pMode == 2, p, weight = 1f, marginEndDp = if (pMode > 0) 4 else 0, aiHighlighted = pMode == 2 && puzAi) { savePuz(2, pEnch, pElem, pShape) })
        if (pMode > 0) {
            pzRow.addView(makeToggle(s(R.string.ov_puzzle_ench), pEnch, p, weight = 0.9f, marginEndDp = 0, aiHighlighted = pEnch && puzAi) { savePuz(pMode, !pEnch, pElem, pShape) })
        }
        body.addView(pzRow)
        if (pMode > 0) {
            val pzColRow = hRow(bottomMarginDp = 4)
            pzColRow.addView(makeToggle(s(R.string.puzzle_elem_any), pElem < 0, p, weight = 1.45f) { savePuz(pMode, pEnch, -1, pShape) })
            for (e in 0..5) {
                pzColRow.addView(makeElementBtn(e, pElem == e, p, weight = 1f, marginEndDp = if (e == 5) 0 else 3, aiHighlighted = puzAi) { savePuz(pMode, pEnch, e, pShape) })
            }
            body.addView(pzColRow)
        }

        // Sub-group B: Independent Shape Shields (Cross 1..3 + Forbid Cross 十之诅咒 + L/T/2x2/3x3 + Color)
        body.addView(makeSubDivider(p))
        body.addView(makeSubLabel(s(R.string.shape_shield_label), p))
        val forbidCrossOn = "forbid_cross" in activeIds
        val crossChip = byId["cross_shield"] ?: byId["cross_shield_2"] ?: byId["cross_shield_3"]
        val crossAi = WaveShieldCatalog.CROSS_EXCLUSIVE_IDS.any { isAi(it) }
        val cCnt = if (forbidCrossOn) 0 else ((crossChip?.paramInt ?: 0) and 0x0F)
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
            crRow.addView(makeToggle(lbl, !forbidCrossOn && cCnt == n, p, weight = 1f, marginEndDp = if (i == 3) 0 else 3, aiHighlighted = n > 0 && !forbidCrossOn && cCnt == n && crossAi) { saveCross(n, cDist, cColors) })
        }
        body.addView(crRow)
        val fcRow = hRow(bottomMarginDp = 4)
        val forbidCrossLbl = s(R.string.shield_forbid_cross)
        fcRow.addView(makeToggle(forbidCrossLbl, forbidCrossOn, p, weight = 1f, marginEndDp = 0, aiHighlighted = isAi("forbid_cross")) {
            if (forbidCrossOn) ActiveStrategyStore.removeChip("forbid_cross")
            else ActiveStrategyStore.replaceGroup(
                WaveShieldCatalog.CROSS_EXCLUSIVE_IDS,
                WaveShieldChip("forbid_cross", forbidCrossLbl, ShieldCategory.SHAPE_AND_PUZZLE)
            )
        })
        body.addView(fcRow)
        if (cCnt > 0) {
            if (cCnt >= 2) {
                val cdRow = hRow(bottomMarginDp = 3)
                cdRow.addView(makeToggle(s(R.string.cross_distinct_switch), cDist, p, weight = 1f, marginEndDp = 0, aiHighlighted = cDist && crossAi) { saveCross(cCnt, !cDist, cColors) })
                body.addView(cdRow)
            }
            val ccRow = hRow(bottomMarginDp = 4)
            for (e in 0..5) {
                val bit = 1 shl e
                ccRow.addView(makeElementBtn(e, (cColors and bit) != 0, p, weight = 1f, marginEndDp = if (e == 5) 0 else 3, aiHighlighted = crossAi) {
                    val nxt = cColors xor bit
                    if ((nxt and bit) == 0 || Integer.bitCount(nxt) <= cCnt) saveCross(cCnt, cDist, nxt)
                })
            }
            body.addView(ccRow)
        }
        val shRow1 = hRow(bottomMarginDp = 4)
        shRow1.addView(makeToggle(s(R.string.shield_l_shape), "l_shape" in activeIds, p, weight = 1f, aiHighlighted = isAi("l_shape")) { onToggleWaveChip(WaveShieldChip("l_shape", s(R.string.shield_l_shape), ShieldCategory.SHAPE_AND_PUZZLE, paramInt = 1)) })
        shRow1.addView(makeToggle(s(R.string.shield_t_shape), "t_shape" in activeIds, p, weight = 1f, aiHighlighted = isAi("t_shape")) { onToggleWaveChip(WaveShieldChip("t_shape", s(R.string.shield_t_shape), ShieldCategory.SHAPE_AND_PUZZLE, paramInt = 1)) })
        shRow1.addView(makeToggle(s(R.string.shield_sq_2x2), "square_2x2" in activeIds, p, weight = 1f, marginEndDp = 0, aiHighlighted = isAi("square_2x2")) { onToggleWaveChip(WaveShieldChip("square_2x2", s(R.string.shield_sq_2x2), ShieldCategory.SHAPE_AND_PUZZLE, paramInt = 1)) })
        body.addView(shRow1)
        val shRow2 = hRow(bottomMarginDp = 4)
        shRow2.addView(makeToggle(s(R.string.shield_sq_3x3), "square_3x3" in activeIds, p, weight = 1f, aiHighlighted = isAi("square_3x3")) { onToggleWaveChip(WaveShieldChip("square_3x3", s(R.string.shield_sq_3x3), ShieldCategory.SHAPE_AND_PUZZLE, paramInt = 1)) })
        shRow2.addView(makeToggle(s(R.string.shield_all_cols), "all_columns_ameliorate" in activeIds, p, weight = 1.15f, marginEndDp = 0, aiHighlighted = isAi("all_columns_ameliorate")) { onToggleWaveChip(WaveShieldChip("all_columns_ameliorate", s(R.string.shield_all_cols), ShieldCategory.SHAPE_AND_PUZZLE)) })
        body.addView(shRow2)

        val activeGeoChip = listOf("l_shape", "t_shape", "square_2x2", "square_3x3").firstOrNull { it in activeIds }?.let { byId[it] }
        if (activeGeoChip != null) {
            val geoMask = activeGeoChip.paramElementMask and 0x3F
            body.addView(makeSubLabel(s(R.string.shape_elem_opt_label), p))
            val geoRow = hRow(bottomMarginDp = 4)
            geoRow.addView(makeToggle(s(R.string.puzzle_elem_any), geoMask == 0, p, weight = 1.4f) {
                onUpsertWaveChip(activeGeoChip.copy(paramElementMask = 0, autoDetected = false))
            })
            for (e in 0..5) {
                val bit = 1 shl e
                geoRow.addView(makeElementBtn(e, (geoMask and bit) != 0, p, weight = 1f, marginEndDp = if (e == 5) 0 else 3, aiHighlighted = activeGeoChip.autoDetected) {
                    onUpsertWaveChip(activeGeoChip.copy(paramElementMask = geoMask xor bit, autoDetected = false))
                })
            }
            body.addView(geoRow)
        }

        // Sub-group C: Column Amelioration 1..6 & Row Elimination 1..5
        body.addView(makeSubDivider(p))
        body.addView(makeSubLabel(s(R.string.section_columns), p))
        for (rowIdx in 0..1) {
            val cRow = hRow(bottomMarginDp = 4)
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

        body.addView(makeSubLabel(s(R.string.section_rows_amel), p))
        val rwRow1 = hRow(bottomMarginDp = 4)
        for (r in 0..2) {
            val rule = cfg.rowRules.getOrElse(r) { RowBonusRule() }
            val lbl = if (!rule.enabled) s(R.string.row_off_fmt, r + 1) else s(R.string.row_on_fmt, r + 1, 6)
            rwRow1.addView(makeToggle(lbl, rule.enabled, p, weight = 1f, marginEndDp = if (r == 2) 0 else 4) {
                onUpdateActivePresetConfig { cur ->
                    cur.copy(rowRules = cur.rowRules.toMutableList().also { it[r] = rule.copy(enabled = !rule.enabled) })
                }
            })
        }
        body.addView(rwRow1)
        val rwRow2 = hRow(bottomMarginDp = 0)
        for (r in 3..4) {
            val rule = cfg.rowRules.getOrElse(r) { RowBonusRule() }
            val lbl = if (!rule.enabled) s(R.string.row_off_fmt, r + 1) else s(R.string.row_on_fmt, r + 1, 6)
            rwRow2.addView(makeToggle(lbl, rule.enabled, p, weight = 1f, marginEndDp = if (r == 4) 0 else 4) {
                onUpdateActivePresetConfig { cur ->
                    cur.copy(rowRules = cur.rowRules.toMutableList().also { it[r] = rule.copy(enabled = !rule.enabled) })
                }
            })
        }
        body.addView(rwRow2)
    }

    private fun buildPathAndSafetyCardBody(
        body: LinearLayout,
        cfg: RuleConfig,
        byId: Map<String, WaveShieldChip>,
        activeIds: Set<String>,
        p: OverlayPalette
    ) {
        fun isAi(id: String): Boolean = byId[id]?.autoDetected == true
        val burnChip = byId["zero_trail_burning"]
        val burnOn = burnChip != null
        val burnLen = (burnChip?.paramInt ?: 0) and 0xFF
        val burnHurts = ((burnChip?.paramInt ?: 0) ushr 8) and 0xFF
        val ptRow1 = hRow(bottomMarginDp = 4)
        ptRow1.addView(makeToggle(s(R.string.ov_burn_path), burnOn, p, weight = 1.1f, marginEndDp = if (burnOn) 4 else 0, aiHighlighted = isAi("zero_trail_burning")) {
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

        body.addView(makeSubLabel(s(R.string.burn_tile_hurts_label), p))
        body.addView(makeStepperRow("${cfg.maxBurningTileHurts} ${s(R.string.unit_times)}", p, {
            onUpdateActivePresetConfig { it.copy(maxBurningTileHurts = (it.maxBurningTileHurts - 1).coerceIn(0, 10)) }
        }, {
            onUpdateActivePresetConfig { it.copy(maxBurningTileHurts = (it.maxBurningTileHurts + 1).coerceIn(0, 10)) }
        }))
        body.addView(makeSubLabel(s(R.string.miasma_hurts_label), p))
        body.addView(makeStepperRow("${cfg.maxMiasmaHits} ${s(R.string.unit_times)}", p, {
            onUpdateActivePresetConfig { it.copy(maxMiasmaHits = (it.maxMiasmaHits - 1).coerceIn(0, 5)) }
        }, {
            onUpdateActivePresetConfig { it.copy(maxMiasmaHits = (it.maxMiasmaHits + 1).coerceIn(0, 5)) }
        }))

        body.addView(makeSubDivider(p))
        val ptRowTl = hRow(bottomMarginDp = 4)
        ptRowTl.addView(makeToggle(s(R.string.shield_traffic_light), "traffic_light" in activeIds, p, weight = 1f, aiHighlighted = isAi("traffic_light")) {
            onToggleWaveChip(WaveShieldChip("traffic_light", s(R.string.shield_traffic_light), ShieldCategory.HAZARD_AND_PATH))
        })
        ptRowTl.addView(makeToggle(s(R.string.shield_trail_corrosion), "trail_corrosion" in activeIds, p, weight = 1f, marginEndDp = 0, aiHighlighted = isAi("trail_corrosion")) {
            onToggleWaveChip(WaveShieldChip("trail_corrosion", s(R.string.shield_trail_corrosion), ShieldCategory.HAZARD_AND_PATH, paramInt = 15))
        })
        body.addView(ptRowTl)
        val ptRow2 = hRow(bottomMarginDp = 4)
        ptRow2.addView(makeToggle(s(R.string.shield_step_hurt), "step_hurt_minimize" in activeIds, p, weight = 1f, aiHighlighted = isAi("step_hurt_minimize")) {
            onToggleWaveChip(WaveShieldChip("step_hurt_minimize", s(R.string.shield_step_hurt), ShieldCategory.HAZARD_AND_PATH))
        })
        ptRow2.addView(makeToggle(s(R.string.shield_char_terrain), "char_orb_terrain" in activeIds, p, weight = 1f, aiHighlighted = isAi("char_orb_terrain")) {
            onToggleWaveChip(WaveShieldChip("char_orb_terrain", s(R.string.shield_char_terrain), ShieldCategory.HAZARD_AND_PATH))
        })
        ptRow2.addView(makeToggle(s(R.string.shield_weijian_burst), "weijian_burst" in activeIds, p, weight = 1f, marginEndDp = 0, aiHighlighted = isAi("weijian_burst")) {
            onToggleWaveChip(WaveShieldChip("weijian_burst", s(R.string.shield_weijian_burst), ShieldCategory.HAZARD_AND_PATH))
        })
        body.addView(ptRow2)

        body.addView(makeSubDivider(p))
        body.addView(makeSubLabel(s(R.string.hazard_policy_section), p))
        val hzRow1 = hRow(bottomMarginDp = 4)
        hzRow1.addView(makeToggle(s(R.string.switch_must_clear_elec), cfg.mustClearElectrified, p, weight = 1f) {
            onUpdateActivePresetConfig { it.copy(mustClearElectrified = !it.mustClearElectrified) }
        })
        hzRow1.addView(makeToggle(s(R.string.switch_must_clear_blood), cfg.mustClearBloodDrain, p, weight = 1f, marginEndDp = 0) {
            onUpdateActivePresetConfig { it.copy(mustClearBloodDrain = !it.mustClearBloodDrain) }
        })
        body.addView(hzRow1)
        val hzRow2 = hRow(bottomMarginDp = 4)
        hzRow2.addView(makeToggle(s(R.string.switch_must_clear_bomb), cfg.mustClearBomb, p, weight = 1f) {
            onUpdateActivePresetConfig { it.copy(mustClearBomb = !it.mustClearBomb) }
        })
        hzRow2.addView(makeToggle(s(R.string.switch_must_clear_foam), cfg.mustClearFoam, p, weight = 1f, marginEndDp = 0) {
            onUpdateActivePresetConfig { it.copy(mustClearFoam = !it.mustClearFoam) }
        })
        body.addView(hzRow2)
        val hzRow3 = hRow(bottomMarginDp = 4)
        hzRow3.addView(makeToggle(s(R.string.switch_prio_frozen), cfg.prioritizeFrozen, p, weight = 1f) {
            onUpdateActivePresetConfig { it.copy(prioritizeFrozen = !it.prioritizeFrozen) }
        })
        hzRow3.addView(makeToggle(s(R.string.switch_prio_petrified), cfg.prioritizePetrifiedClear, p, weight = 1f, marginEndDp = 0) {
            onUpdateActivePresetConfig { it.copy(prioritizePetrifiedClear = !it.prioritizePetrifiedClear) }
        })
        body.addView(hzRow3)
        val hzRow4 = hRow(bottomMarginDp = 0)
        hzRow4.addView(makeToggle(s(R.string.switch_hit_weathered), cfg.allowHitWeatheredToClear, p, weight = 1f, marginEndDp = 0) {
            onUpdateActivePresetConfig { it.copy(allowHitWeatheredToClear = !it.allowHitWeatheredToClear) }
        })
        body.addView(hzRow4)
    }

    private fun buildBudgetAndStartCardBody(
        body: LinearLayout,
        cfg: RuleConfig,
        byId: Map<String, WaveShieldChip>,
        activeIds: Set<String>,
        p: OverlayPalette
    ) {
        fun isAi(id: String): Boolean = byId[id]?.autoDetected == true
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

        // 4-Mode Wave Hard Step Controller (≤N步 | A~B步 | 恰好=N步)
        val limStepRow = hRow(bottomMarginDp = 4)
        limStepRow.addView(makeToggle(s(R.string.shield_step_limit_fmt, stepLimitVal), "step_limit" in activeIds, p, weight = 1f, aiHighlighted = isAi("step_limit")) {
            if ("step_limit" in activeIds) ActiveStrategyStore.removeChip("step_limit")
            else ActiveStrategyStore.replaceGroup(
                WaveShieldCatalog.STEP_EXCLUSIVE_IDS,
                WaveShieldChip("step_limit", s(R.string.shield_step_limit_fmt, stepLimitVal), ShieldCategory.HAZARD_AND_PATH, paramInt = stepLimitVal)
            )
        })
        limStepRow.addView(makeToggle(s(R.string.shield_step_range_fmt, stepRangeMinVal, stepRangeMaxVal), "step_range" in activeIds, p, weight = 1.1f, aiHighlighted = isAi("step_range")) {
            if ("step_range" in activeIds) ActiveStrategyStore.removeChip("step_range")
            else ActiveStrategyStore.replaceGroup(
                WaveShieldCatalog.STEP_EXCLUSIVE_IDS,
                WaveShieldChip("step_range", s(R.string.shield_step_range_fmt, stepRangeMinVal, stepRangeMaxVal), ShieldCategory.HAZARD_AND_PATH, paramInt = stepRangeMinVal or (stepRangeMaxVal shl 8))
            )
        })
        limStepRow.addView(makeToggle(s(R.string.shield_exact_steps_fmt, exactStepsVal), "exact_steps" in activeIds, p, weight = 1f, marginEndDp = 0, aiHighlighted = isAi("exact_steps")) {
            if ("exact_steps" in activeIds) ActiveStrategyStore.removeChip("exact_steps")
            else ActiveStrategyStore.replaceGroup(
                WaveShieldCatalog.STEP_EXCLUSIVE_IDS,
                WaveShieldChip("exact_steps", s(R.string.shield_exact_steps_fmt, exactStepsVal), ShieldCategory.HAZARD_AND_PATH, paramInt = exactStepsVal)
            )
        })
        body.addView(limStepRow)
        if ("step_limit" in activeIds) {
            body.addView(makeStepperRow("≤ $stepLimitVal ${s(R.string.unit_steps)}", p, {
                stepLimitVal = (stepLimitVal - 1).coerceIn(2, 60)
                onUpsertWaveChip(WaveShieldChip("step_limit", s(R.string.shield_step_limit_fmt, stepLimitVal), ShieldCategory.HAZARD_AND_PATH, paramInt = stepLimitVal))
            }, {
                stepLimitVal = (stepLimitVal + 1).coerceIn(2, 60)
                onUpsertWaveChip(WaveShieldChip("step_limit", s(R.string.shield_step_limit_fmt, stepLimitVal), ShieldCategory.HAZARD_AND_PATH, paramInt = stepLimitVal))
            }))
        }
        if ("exact_steps" in activeIds) {
            body.addView(makeStepperRow("= $exactStepsVal ${s(R.string.unit_steps)}", p, {
                exactStepsVal = (exactStepsVal - 1).coerceIn(1, 100)
                onUpsertWaveChip(WaveShieldChip("exact_steps", s(R.string.shield_exact_steps_fmt, exactStepsVal), ShieldCategory.HAZARD_AND_PATH, paramInt = exactStepsVal))
            }, {
                exactStepsVal = (exactStepsVal + 1).coerceIn(1, 100)
                onUpsertWaveChip(WaveShieldChip("exact_steps", s(R.string.shield_exact_steps_fmt, exactStepsVal), ShieldCategory.HAZARD_AND_PATH, paramInt = exactStepsVal))
            }))
        }
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

        // Unified Compute SeekBar + Strategy Diagonal Override
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

        body.addView(makeSubLabel(s(R.string.diag_move_strategy_label), p))
        val diagRow = hRow(bottomMarginDp = 4)
        diagRow.addView(makeToggle(s(R.string.diag_follow_system), cfg.useSystemDiagonal, p, weight = 1f) {
            onUpdateActivePresetConfig { it.copy(useSystemDiagonal = true) }
        })
        diagRow.addView(makeToggle(s(R.string.diag_force_on), !cfg.useSystemDiagonal && cfg.allowDiagonalMove, p, weight = 1f) {
            onUpdateActivePresetConfig { it.copy(useSystemDiagonal = false, allowDiagonalMove = true) }
        })
        diagRow.addView(makeToggle(s(R.string.diag_force_off), !cfg.useSystemDiagonal && !cfg.allowDiagonalMove, p, weight = 1f, marginEndDp = 0) {
            onUpdateActivePresetConfig { it.copy(useSystemDiagonal = false, allowDiagonalMove = false) }
        })
        body.addView(diagRow)

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
        tlRow.addView(makeToggle(s(R.string.shield_traffic_light), "traffic_light" in activeIds, p, weight = 1f, aiHighlighted = isAi("traffic_light")) {
            onToggleWaveChip(WaveShieldChip("traffic_light", s(R.string.shield_traffic_light), ShieldCategory.HAZARD_AND_PATH))
        })
        tlRow.addView(makeToggle(s(R.string.char_auto_buoy), cfg.autoDetectStartingBuoy, p, weight = 1f, marginEndDp = 0) {
            onUpdateActivePresetConfig { it.copy(autoDetectStartingBuoy = !it.autoDetectStartingBuoy) }
        })
        body.addView(tlRow)
        val chRow = hRow(bottomMarginDp = if (cfg.useCharacterOrb) 4 else 0)
        chRow.addView(makeToggle(s(R.string.char_off), !cfg.useCharacterOrb, p, weight = 1f) {
            onUpdateActivePresetConfig { it.copy(useCharacterOrb = false) }
        })
        for (c in 0..5) {
            chRow.addView(makeToggle("${c + 1}", cfg.useCharacterOrb && cfg.characterOrbCol == c, p, weight = 0.8f, marginEndDp = if (c == 5) 0 else 3) {
                onUpdateActivePresetConfig { it.copy(useCharacterOrb = true, characterOrbCol = c) }
            })
        }
        body.addView(chRow)
        if (cfg.useCharacterOrb) {
            val chElRow = hRow(bottomMarginDp = 0)
            chElRow.addView(makeToggle(s(R.string.char_elem_auto), cfg.characterOrbAutoElement, p, weight = 1.25f) {
                onUpdateActivePresetConfig { it.copy(characterOrbAutoElement = true) }
            })
            val elems = listOf(Element.WATER, Element.FIRE, Element.WOOD, Element.LIGHT, Element.DARK)
            elems.forEachIndexed { idx, el ->
                val lbl = LocaleHelper.elementLabel(lctx, el.code)
                chElRow.addView(makeToggle(lbl, !cfg.characterOrbAutoElement && cfg.characterOrbElement == el, p, weight = 0.85f, marginEndDp = if (idx == elems.lastIndex) 0 else 3) {
                    onUpdateActivePresetConfig { it.copy(characterOrbAutoElement = false, characterOrbElement = el) }
                })
            }
            body.addView(chElRow)
        }
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

    // ==================== TAB 2: 系统设置 (6 Single-Open Colored Accordions matching Launch App!) ====================
    private fun buildSettingsTab(p: OverlayPalette) {
        addInfoBanner(s(R.string.priority_tier_hint_settings), p)

        // Card 0 (Blue): Solver Engine & System Steps
        val s0Summary = "${settings.maxSteps}${s(R.string.unit_steps)} · ${s(R.string.tag_beam_fmt, settings.beamWidth)}"
        addAccordionGroupCard(0, s(R.string.section_engine_steps), s0Summary, true, ACCENT_BLUE, p, R.string.help_engine_settings_title, R.string.help_engine_settings_body)?.let { engBody ->
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

        // Card 1 (Indigo): AI Vision & Split Pre/In-Battle Enemy Skill Recognition (1:1 synced with Launch App Settings!)
        val aiOnCnt = listOf(
            settings.useAiRecognizer,
            settings.autoCapturePreBattleStageInfo,
            settings.autoCaptureInBattleEnemyInfo,
            settings.autoEnemyIconDetectEnabled,
            settings.calibration.autoDetectWeijian,
            settings.showOrbBadgesOnPath
        ).count { it }
        addAccordionGroupCard(1, s(R.string.section_ai_vision), "$aiOnCnt/6", aiOnCnt > 0, ACCENT_INDIGO, p, R.string.help_ai_vision_title, R.string.help_ai_vision_body)?.let { aiBody ->
            if (aiEngineStatusLabel.isNotBlank()) {
                aiBody.addView(makeSubLabel(s(R.string.ai_engine_active_fmt, aiEngineStatusLabel), p))
            }
            val aiRow1 = hRow(bottomMarginDp = 4)
            aiRow1.addView(makeToggle(s(R.string.ov_ai_board_short), settings.useAiRecognizer, p, weight = 1f) {
                onUpdateSettings { it.copy(useAiRecognizer = !it.useAiRecognizer) }
            })
            aiRow1.addView(makeToggle(s(R.string.ov_ai_pre_battle_short), settings.autoCapturePreBattleStageInfo, p, weight = 1f) {
                onUpdateSettings { it.copy(autoCapturePreBattleStageInfo = !it.autoCapturePreBattleStageInfo) }
            })
            aiRow1.addView(makeToggle(s(R.string.ov_ai_in_battle_short), settings.autoCaptureInBattleEnemyInfo, p, weight = 1f, marginEndDp = 0) {
                onUpdateSettings { it.copy(autoCaptureInBattleEnemyInfo = !it.autoCaptureInBattleEnemyInfo) }
            })
            aiBody.addView(aiRow1)

            val aiRow2 = hRow(bottomMarginDp = 0)
            aiRow2.addView(makeToggle(s(R.string.ov_ai_icon_short), settings.autoEnemyIconDetectEnabled, p, weight = 1f) {
                onUpdateSettings { it.copy(autoEnemyIconDetectEnabled = !it.autoEnemyIconDetectEnabled) }
            })
            aiRow2.addView(makeToggle(s(R.string.ov_ai_weijian_short), settings.calibration.autoDetectWeijian, p, weight = 1f) {
                onUpdateSettings { it.copy(calibration = it.calibration.copy(autoDetectWeijian = !it.calibration.autoDetectWeijian)) }
            })
            aiRow2.addView(makeToggle(s(R.string.ov_orb_badges), settings.showOrbBadgesOnPath, p, weight = 1f, marginEndDp = 0) {
                onUpdateSettings { it.copy(showOrbBadgesOnPath = !it.showOrbBadgesOnPath) }
            })
            aiBody.addView(aiRow2)
        }

        // Card 2 (Amber): Unified 6-Tier Swipe Speed Presets (2s..7s, matches Launch App Settings Amber card!)
        val activeSpeed = SwipeSpeedPreset.fromStepDurationMs(settings.stepDurationMs)
        val sSpeedSummary = "${LocaleHelper.speedLabel(lctx, activeSpeed)} · ${settings.stepDurationMs}${s(R.string.unit_ms)}"
        addAccordionGroupCard(2, s(R.string.section_speed), sSpeedSummary, true, ACCENT_AMBER, p, R.string.help_speed_title, R.string.help_speed_body)?.let { spdBody ->
            populateSpeedPresetSection(spdBody, p)
        }

        // Card 3 (Teal): Interaction & Core Switches (matches Launch App Settings Teal card 1:1!)
        val sInvLabel = invertSummaryLabel()
        val s1Summary = buildList {
            if (settings.testModeOnlyDraw) add(s(R.string.ov_preview_confirm))
            if (settings.holdReplanEnabled) {
                val trig = s(if (settings.holdReplanNudge) R.string.mode_hold_trigger_nudge else R.string.mode_hold_trigger_inplace)
                add("${s(R.string.ov_hold)}($trig)")
            }
            if (sInvLabel != null) add(sInvLabel)
            if (settings.allowDiagonalMove) add(s(R.string.ov_diag_move))
            if (settings.humanMode) add(s(R.string.mode_human_title).take(4))
        }.joinToString(" · ").ifEmpty { s(R.string.ov_card_none_set) }
        addAccordionGroupCard(3, s(R.string.section_modes), s1Summary, settings.testModeOnlyDraw || settings.holdReplanEnabled || sInvLabel != null || settings.allowDiagonalMove, ACCENT_TEAL, p, R.string.help_modes_title, R.string.help_modes_body)?.let { swBody ->
            val swRow1 = hRow(bottomMarginDp = 4)
            swRow1.addView(makeToggle(s(R.string.ov_preview_confirm), settings.testModeOnlyDraw, p, weight = 1f) {
                onUpdateSettings { it.copy(testModeOnlyDraw = !it.testModeOnlyDraw) }
            })
            swRow1.addView(makeToggle(s(R.string.ov_diag_move), settings.allowDiagonalMove, p, weight = 1f) {
                onUpdateSettings { it.copy(allowDiagonalMove = !it.allowDiagonalMove) }
            })
            swRow1.addView(makeToggle(s(R.string.mode_human_title), settings.humanMode, p, weight = 1f, marginEndDp = 0) {
                onUpdateSettings { it.copy(humanMode = !it.humanMode) }
            })
            swBody.addView(swRow1)

            val swRow2 = hRow(bottomMarginDp = 4)
            swRow2.addView(makeToggle(s(R.string.mode_stop_title), settings.showEmergencyStop, p, weight = 1f) {
                onUpdateSettings { it.copy(showEmergencyStop = !it.showEmergencyStop) }
            })
            swRow2.addView(makeToggle(s(R.string.mode_floating_record_title), settings.showFloatingRecordButton, p, weight = 1f, marginEndDp = 0) {
                onUpdateSettings { it.copy(showFloatingRecordButton = !it.showFloatingRecordButton) }
            })
            swBody.addView(swRow2)

            swBody.addView(makeSubDivider(p))
            populateHoldAndInvertControls(swBody, p)

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                swBody.addView(makeSubDivider(p))
                swBody.addView(makeSubLabel(s(R.string.capture_mode_title), p))
                val capRow = hRow(bottomMarginDp = 0)
                capRow.addView(
                    makeToggle(
                        s(R.string.capture_mode_a11y),
                        settings.captureMode == CaptureMode.ACCESSIBILITY,
                        p,
                        weight = 1.25f,
                        marginEndDp = 4
                    ) {
                        onUpdateSettings { it.copy(captureMode = CaptureMode.ACCESSIBILITY) }
                    }
                )
                capRow.addView(
                    makeToggle(
                        s(R.string.capture_mode_media),
                        settings.captureMode == CaptureMode.MEDIA_PROJECTION,
                        p,
                        weight = 0.85f,
                        marginEndDp = 0
                    ) {
                        onUpdateSettings { it.copy(captureMode = CaptureMode.MEDIA_PROJECTION) }
                    }
                )
                swBody.addView(capRow)
            }
        }

        // Card 4 (Rose): On-Device Training Collector Toggle (matches Launch App Settings Rose card!)
        val colStateText = s(if (settings.trainingDataCollectionEnabled) R.string.ov_toggle_on else R.string.ov_toggle_off)
        val colSummary = s(R.string.ov_collector_toggle_fmt, colStateText, collectedTrainingSamples)
        addAccordionGroupCard(4, s(R.string.section_training_collector), colSummary, settings.trainingDataCollectionEnabled, ACCENT_ROSE, p, R.string.help_training_collector_title, R.string.help_training_collector_body)?.let { colBody ->
            val cRow = hRow(bottomMarginDp = 0)
            cRow.addView(makeToggle(colSummary, settings.trainingDataCollectionEnabled, p, weight = 1f, marginEndDp = 0) {
                onUpdateSettings { it.copy(trainingDataCollectionEnabled = !it.trainingDataCollectionEnabled) }
            })
            colBody.addView(cRow)
        }

        // Card 5 (Purple): Appearance, Theme, Language & Floating Bar Layout/Dock (matches Launch App Settings Purple card!)
        val barLayoutLbl = s(if (settings.floatingBarVertical) R.string.floating_bar_vertical else R.string.floating_bar_horizontal)
        val barDockLbl = s(if (settings.floatingBarDockRight) R.string.floating_bar_dock_right else R.string.floating_bar_dock_left)
        val s2Summary = "${LocaleHelper.themeLabel(lctx, settings.themeMode)} · ${LocaleHelper.languageLabel(lctx, settings.language)} · $barLayoutLbl$barDockLbl"
        addAccordionGroupCard(5, s(R.string.section_appearance), s2Summary, true, ACCENT_PURPLE, p, R.string.help_appearance_title, R.string.help_appearance_body)?.let { appBody ->
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
        // Parent Master Toggle (1:1 hierarchy parity with Launch App Settings)
        val holdMasterRow = hRow(bottomMarginDp = 4)
        holdMasterRow.addView(
            makeToggle(s(R.string.mode_hold_title), settings.holdReplanEnabled, p, weight = 1f, marginEndDp = 0) {
                onUpdateSettings { it.copy(holdReplanEnabled = !it.holdReplanEnabled) }
            }
        )
        body.addView(holdMasterRow)

        // Nested Indented Bordered Child Card: Hold-Replan Trigger Mode (matches DashboardScreen Surface 1:1!)
        val subBg = if (p.isDark) Color.rgb(26, 30, 38) else Color.rgb(245, 247, 250)
        val subBorder = if (settings.holdReplanEnabled) {
            Color.argb(if (p.isDark) 132 else 115, Color.red(ACCENT_TEAL), Color.green(ACCENT_TEAL), Color.blue(ACCENT_TEAL))
        } else {
            p.outline
        }
        val holdSubBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(subBg, dp(8).toFloat(), subBorder)
            setPadding(dp(7), dp(5), dp(7), dp(6))
            alpha = if (settings.holdReplanEnabled) 1.0f else 0.55f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(10)
                bottomMargin = dp(4)
            }
        }
        holdSubBox.addView(
            TextView(context).apply {
                text = s(R.string.mode_hold_trigger_label)
                textSize = 9.2f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (settings.holdReplanEnabled) p.onSurface else p.onSurfaceVariant)
                setPadding(dp(1), 0, dp(1), dp(4))
            }
        )
        val triggerRow = hRow(bottomMarginDp = 0)
        triggerRow.addView(
            makeToggle(s(R.string.mode_hold_trigger_inplace), !settings.holdReplanNudge, p, weight = 1f, marginEndDp = 4) {
                onUpdateSettings { it.copy(holdReplanNudge = false) }
            }
        )
        triggerRow.addView(
            makeToggle(s(R.string.mode_hold_trigger_nudge), settings.holdReplanNudge, p, weight = 1f, marginEndDp = 0) {
                onUpdateSettings { it.copy(holdReplanNudge = true) }
            }
        )
        holdSubBox.addView(triggerRow)
        body.addView(holdSubBox)

        body.addView(makeSubDivider(p))
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

    private fun makeHelpCircleView(p: OverlayPalette, accent: Int, onClick: () -> Unit): TextView {
        val fg = accentFgFor(accent, p.isDark)
        val bg = Color.argb(if (p.isDark) 56 else 26, Color.red(accent), Color.green(accent), Color.blue(accent))
        return TextView(context).apply {
            text = "?"
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(fg)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(bg)
                setStroke(dp(1), Color.argb(140, Color.red(fg), Color.green(fg), Color.blue(fg)))
            }
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(5) }
            setOnClickListener { onClick() }
        }
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
        p: OverlayPalette,
        helpTitleRes: Int = 0,
        helpBodyRes: Int = 0
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
            setPadding(dp(8), dp(5), dp(8), dp(5))
            setOnClickListener {
                // Exclusive single-open accordion: opening a drawer automatically collapses all other drawers in this tab!
                expandedCardByTab[activeTab] = if (isOpen) null else cardId
                rebuildContent(preserveScroll = false)
            }
        }
        // Colored vertical left accent strip
        val accentFg = accentFgFor(accentColor, p.isDark)
        hdr.addView(View(context).apply {
            background = roundedBg(accentFg, dp(2).toFloat(), null)
            layoutParams = LinearLayout.LayoutParams(dp(3), dp(14)).apply { marginEnd = dp(6) }
        })
        // Group title (wraps cleanly up to 2 lines, zero ellipsis)
        hdr.addView(TextView(context).apply {
            text = title
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
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
            textSize = 9.2f
            typeface = if (highlighted) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            maxLines = 2
            maxWidth = dp(156)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            if (highlighted) {
                setTextColor(accentFg)
                background = roundedBg(Color.argb(if (p.isDark) 64 else 28, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)), dp(6).toFloat(), null)
            } else {
                setTextColor(p.onSurfaceVariant)
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(5)
            }
        })
        // Circular (?) help trigger button on every single drawer!
        if (helpTitleRes != 0 && helpBodyRes != 0 && onShowDrawerHelp != null) {
            hdr.addView(makeHelpCircleView(p, accentColor) {
                onShowDrawerHelp.invoke(s(helpTitleRes), s(helpBodyRes))
            })
        }
        // Expand/collapse chevron
        hdr.addView(TextView(context).apply {
            text = if (isOpen) "▾" else "▸"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accentFg)
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
        text = label; textSize = 9.8f; maxLines = 2; setLineSpacing(0f, 1.04f); gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(padH), dp(4), dp(padH), dp(4))
        layoutParams = if (weight > 0f) {
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight).apply { marginEnd = dp(marginEndDp) }
        } else {
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(marginEndDp) }
        }
        setOnClickListener { onClick() }
    }

    private fun resolveStrategyCardForIds(ids: Set<String>): Int? {
        val card1Ids = setOf(
            "min_distinct_elements", "require_elements", "three_elements", "five_elements", "five_elements_enchanted",
            "elem_burst_shield", "max_element_orbs", "keep_element_at_least_one",
            "anti_first_wave", "forbid_all", "clear_all_element", "unmatchable_elements",
            "digital_orb_count", "first_wave_full_clear", "any_enchanted_orb_shield", "race_orb_shield",
            "char_orb_first_wave", "starting_buoy_shield", "forbid_weakened_orb", "luminous_orb_shield"
        )
        val card2Ids = setOf(
            "puzzle_shield", "strict_puzzle", "loose_puzzle", "enchanted_puzzle",
            "cross_shield", "cross_shield_2", "cross_shield_3", "forbid_cross",
            "l_shape", "t_shape", "square_2x2", "square_3x3", "all_columns_ameliorate"
        )
        val card3Ids = setOf(
            "traffic_light", "zero_trail_burning", "trail_corrosion", "step_hurt_minimize", "char_orb_terrain", "weijian_burst"
        )
        val card4Ids = setOf("step_limit", "step_range", "exact_steps")
        val card0Ids = setOf(
            "min_combo", "max_combo", "min_first_combo", "min_first_elem_combo",
            "exact_combo", "exact_first_combo", "wave1_same_kind", "wave1_distinct_kind",
            "zero_combo", "odd_combo", "even_combo", "no_cascade",
            "same_combo_elements", "second_wave_combo", "same_elem_combos", "equal_pair_light_dark"
        )
        return when {
            ids.any { it in card1Ids } -> 1
            ids.any { it in card2Ids } -> 2
            ids.any { it in card3Ids } -> 3
            ids.any { it in card4Ids } -> 4
            ids.any { it in card0Ids } -> 0
            else -> null
        }
    }

    private fun makeToggle(
        label: String,
        selected: Boolean,
        p: OverlayPalette,
        weight: Float = 0f,
        marginEndDp: Int = 4,
        aiHighlighted: Boolean = false,
        onClick: () -> Unit
    ): TextView {
        val dispLabel = if (selected && aiHighlighted && !label.startsWith("✦")) "✦ $label" else label
        return makeChip(dispLabel, weight = weight, marginEndDp = marginEndDp, onClick = onClick).also {
            when {
                selected && aiHighlighted -> {
                    val aiBg = if (p.isDark) Color.rgb(20, 58, 68) else Color.rgb(224, 247, 250)
                    val aiFg = if (p.isDark) Color.rgb(156, 239, 248) else Color.rgb(0, 86, 98)
                    styleChip(it, aiBg, aiFg, Color.rgb(0, 188, 212))
                }
                selected -> styleChip(it, p.primaryContainer, p.onPrimaryContainer, p.primary)
                else -> styleChip(it, p.chipOffBg, p.chipOffText, p.chipOffStroke)
            }
        }
    }

    private fun makeElementBtn(
        elemIndex: Int,
        selected: Boolean,
        p: OverlayPalette,
        suffix: String = "",
        weight: Float = 1f,
        marginEndDp: Int = 3,
        aiHighlighted: Boolean = false,
        onClick: () -> Unit
    ): TextView {
        val baseLbl = LocaleHelper.elementLabel(lctx, elemIndex) + suffix
        val lbl = if (selected && aiHighlighted) "✦$baseLbl" else baseLbl
        val badgeCol = LocaleHelper.elementBadgeArgb(elemIndex)
        return makeChip(lbl, padH = 3, weight = weight, marginEndDp = marginEndDp, onClick = onClick).also {
            if (selected) {
                val stroke = if (aiHighlighted) Color.rgb(128, 236, 255) else badgeCol
                styleChip(it, badgeCol, Color.WHITE, stroke)
            } else {
                styleChip(it, p.chipOffBg, p.chipOffText, p.chipOffStroke)
            }
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
        private val ACCENT_ROSE = Color.rgb(194, 24, 91)

        private fun accentFgFor(accent: Int, isDark: Boolean): Int {
            if (!isDark) return accent
            return when (accent) {
                ACCENT_INDIGO -> Color.rgb(174, 184, 255)
                ACCENT_TEAL -> Color.rgb(128, 203, 196)
                ACCENT_AMBER -> Color.rgb(255, 204, 128)
                ACCENT_CRIMSON -> Color.rgb(255, 171, 145)
                ACCENT_BLUE -> Color.rgb(144, 202, 249)
                ACCENT_PURPLE -> Color.rgb(223, 176, 255)
                ACCENT_ROSE -> Color.rgb(244, 143, 177)
                else -> Color.rgb(224, 230, 255)
            }
        }
    }
}
