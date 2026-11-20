package com.autodora.next.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
 * In-Game 4-Tab Full-Feature Floating Drawer mirroring the main launch app's 4 sections:
 *  - Tab 0: 快捷 (Quick tools: Hold-Replan, Auto Skill Scan & mode, Invert, Speed, Board Paint, Clear)
 *  - Tab 1: 策略 (100% Strategy parity: switch preset, Steps + Compute SeekBar with Follow-System,
 *           Grouped Global Goals N/X, Multi-orb policy, Per-Element 4-col Matrix, Leader Link,
 *           Columns 1..6, Start/Char Orb, Advanced switches)
 *  - Tab 2: 解盾 (100% Shields parity: Active mounted chips, Combo Shield + structural extras,
 *           Attribute & Count Shield with independent per-color Enchanted row & 5 special rune shields,
 *           Puzzle & Multi-Cross color picker, Path/Terrain/Step shields)
 *  - Tab 3: 设置 (System defaults: Preview-Confirm linked to top bar, System Steps & Compute SeekBar,
 *           Core switches, Theme & Language)
 */
class OverlayTuningDrawerController(
    private val context: Context,
    private val onRunAutoSkillScan: () -> Unit,
    private val onToggleBoardEditor: () -> Unit,
    private val onToggleWaveChip: (WaveShieldChip) -> Unit,
    private val onUpsertWaveChip: (WaveShieldChip) -> Unit,
    private val onClearWaveChips: () -> Unit,
    private val onSelectPreset: (String) -> Unit,
    private val onUpdateActivePresetConfig: ((RuleConfig) -> RuleConfig) -> Unit,
    private val onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    private val onClose: () -> Unit
) {
    val rootView: LinearLayout
    private val tabRow: LinearLayout
    private val scrollView: ScrollView
    private val contentHost: LinearLayout
    private val tabButtons = ArrayList<TextView>()

    private var activeTab = 2 // Default directly to 解盾 (Shields) for fastest battle tuning
    private var lctx: Context = context
    private var palette: OverlayPalette? = null
    private var settings: AppSettings = AppSettings()
    private var presets: List<TeamPresetRecord> = emptyList()
    private var activePreset: TeamBasePreset = TeamPresetRepository.defaultBuiltInPresets().first()
    private var activeChips: List<WaveShieldChip> = emptyList()
    private var ocrSummary: String? = null
    private var boardEditorOpen: Boolean = false

    // Independent per-shield working parameters (synced with ActiveStrategyStore)
    private var minTotalComboVal = 6
    private var minFirstComboVal = 5
    private var exactTotalComboVal = 6
    private var exactFirstComboVal = 4
    private var parityFirstOnly = true
    private var secondWaveVal = 1
    private var sameElemGroupsVal = 3
    private var sameElemTarget = -1
    private var pairElemA = 3
    private var pairElemB = 4
    private var elemLimitMode = 0
    private var digitalOrbsVal = 18
    private var stepLimitVal = 15

    init {
        val density = context.resources.displayMetrics.density
        val maxDrawerW = (context.resources.displayMetrics.widthPixels * 0.90f).toInt().coerceAtMost((380 * density).toInt())
        val maxScrollH = (context.resources.displayMetrics.heightPixels * 0.42f).toInt().coerceIn((220 * density).toInt(), (350 * density).toInt())

        rootView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            elevation = dp(4).toFloat()
            visibility = View.GONE
            setPadding(dp(8), dp(6), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(maxDrawerW, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            }
        }

        // Top 4-Tab Bar + Close
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
            R.string.ov_tab_shield,
            R.string.ov_tab_settings
        )
        tabResIds.forEachIndexed { idx, resId ->
            val tv = TextView(context).apply {
                text = lctx.getString(resId)
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setSingleLine(true)
                setPadding(dp(6), dp(5), dp(6), dp(5))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(4)
                }
                setOnClickListener {
                    activeTab = idx
                    rebuildContent()
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
        if (visible) rebuildContent()
    }

    fun updateData(
        palette: OverlayPalette,
        settings: AppSettings,
        presets: List<TeamPresetRecord>,
        activePreset: TeamBasePreset,
        activeChips: List<WaveShieldChip>,
        ocrSummary: String?,
        boardEditorOpen: Boolean = false
    ) {
        this.lctx = LocaleHelper.localizedContext(context, settings.language)
        this.palette = palette
        this.settings = settings
        this.presets = presets
        this.activePreset = activePreset
        this.activeChips = activeChips
        this.ocrSummary = ocrSummary
        this.boardEditorOpen = boardEditorOpen

        val byId = activeChips.associateBy { it.id }
        byId["min_combo"]?.paramInt?.let { if (it in 1..15) minTotalComboVal = it }
        byId["min_first_combo"]?.paramInt?.let { if (it in 1..12) minFirstComboVal = it }
        byId["exact_combo"]?.paramInt?.let { if (it in 1..15) exactTotalComboVal = it }
        byId["exact_first_combo"]?.paramInt?.let { if (it in 1..12) exactFirstComboVal = it }
        (byId["odd_combo"] ?: byId["even_combo"])?.let { parityFirstOnly = it.paramInt != 1 }
        byId["second_wave_combo"]?.paramInt?.let { if (it in 1..6) secondWaveVal = it }
        byId["same_elem_combos"]?.let {
            if (it.paramInt in 2..6) sameElemGroupsVal = it.paramInt
            sameElemTarget = if (it.paramElementMask != 0) {
                (0..5).firstOrNull { idx -> (it.paramElementMask and (1 shl idx)) != 0 } ?: -1
            } else -1
        }
        byId["equal_pair_light_dark"]?.paramElementMask?.let { m ->
            if (m != 0) {
                pairElemA = (m and 0x0F).coerceIn(0, 5)
                pairElemB = ((m ushr 4) and 0x0F).coerceIn(0, 5)
            }
        }
        byId["digital_orb_count"]?.paramInt?.let { if (it in 6..30) digitalOrbsVal = it }
        byId["step_limit"]?.paramInt?.let { if (it in 5..60) stepLimitVal = it }

        if (rootView.visibility == View.VISIBLE) {
            rebuildContent()
        } else {
            applyChromeTheme()
        }
    }

    private fun applyChromeTheme() {
        val p = palette ?: return
        rootView.background = roundedBg(p.containerBg, dp(14).toFloat(), p.containerStroke)
        val tabTitles = listOf(
            s(R.string.ov_tab_quick),
            s(R.string.ov_tab_strategy),
            if (activeChips.isNotEmpty()) "${s(R.string.ov_tab_shield)}(${activeChips.size})" else s(R.string.ov_tab_shield),
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

    private fun rebuildContent() {
        val p = palette ?: return
        applyChromeTheme()
        contentHost.removeAllViews()
        when (activeTab) {
            0 -> buildQuickTab(p)
            1 -> buildStrategyTab(p)
            2 -> buildShieldsTab(p)
            3 -> buildSettingsTab(p)
        }
    }

    // ==================== TAB 0: 快捷工具 (Quick Battle Controls) ====================
    private fun buildQuickTab(p: OverlayPalette) {
        // 1. Primary Action Row: Scan Skills, Board Paint, Clear All Shields
        addSectionLabel(s(R.string.section_auto_skill), p)
        val actRow = hRow(bottomMarginDp = 6)
        actRow.addView(makeChip(s(R.string.ov_scan_trigger), weight = 1f) { onRunAutoSkillScan() }.also {
            styleChip(it, p.primary, p.onPrimary, null)
        })
        actRow.addView(makeToggle(s(R.string.ov_editor), boardEditorOpen, p, weight = 1f) {
            onToggleBoardEditor()
        })
        actRow.addView(makeChip(s(R.string.ov_clear_fmt, activeChips.size), weight = 1f, marginEndDp = 0) {
            onClearWaveChips()
        }.also {
            styleChip(it, p.errorContainer, p.onErrorContainer, null)
        })
        contentHost.addView(actRow)

        // 2. Auto Skill Toggle + Mode Selector
        val autoRow = hRow(bottomMarginDp = 6)
        autoRow.addView(makeToggle(s(R.string.ov_auto_skill), settings.autoEnemySkillEnabled, p, weight = 1.15f) {
            onUpdateSettings { it.copy(autoEnemySkillEnabled = !it.autoEnemySkillEnabled) }
        })
        EnemySkillScanMode.entries.forEachIndexed { idx, mode ->
            autoRow.addView(
                makeToggle(
                    LocaleHelper.scanModeLabel(lctx, mode),
                    settings.enemySkillScanMode == mode,
                    p,
                    weight = 1f,
                    marginEndDp = if (idx == EnemySkillScanMode.entries.lastIndex) 0 else 4
                ) {
                    onUpdateSettings { it.copy(enemySkillScanMode = mode) }
                }
            )
        }
        contentHost.addView(autoRow)

        // 3. Battle Mode Switches (Hold-Replan & Invert Drag)
        addSectionLabel(s(R.string.section_modes), p)
        val modeRow = hRow(bottomMarginDp = 6)
        modeRow.addView(makeToggle(s(R.string.ov_hold), settings.holdReplanEnabled, p, weight = 1f) {
            onUpdateSettings { it.copy(holdReplanEnabled = !it.holdReplanEnabled) }
        })
        modeRow.addView(makeToggle(s(R.string.mode_nudge_title), settings.holdReplanNudge, p, weight = 1.2f) {
            onUpdateSettings { it.copy(holdReplanNudge = !it.holdReplanNudge) }
        })
        val invOn = settings.invertDragX || settings.invertDragY
        modeRow.addView(makeToggle(s(R.string.ov_invert), invOn, p, weight = 0.85f, marginEndDp = 0) {
            onUpdateSettings {
                val next = !(it.invertDragX || it.invertDragY)
                it.copy(invertDragX = next, invertDragY = next)
            }
        })
        contentHost.addView(modeRow)

        // 4. Swipe Speed 4-Tier Presets
        addSectionLabel(s(R.string.section_speed), p)
        val activeSpeed = SwipeSpeedPreset.fromStepDurationMs(settings.stepDurationMs)
        val spdRow = hRow(bottomMarginDp = 4)
        SwipeSpeedPreset.entries.forEachIndexed { idx, preset ->
            val sel = activeSpeed == preset
            val st = p.speedStyle(preset)
            spdRow.addView(
                makeChip(
                    LocaleHelper.speedLabel(lctx, preset),
                    weight = 1f,
                    marginEndDp = if (idx == SwipeSpeedPreset.entries.lastIndex) 0 else 4
                ) {
                    onUpdateSettings { it.copy(stepDurationMs = preset.stepDurationMs) }
                }.also {
                    if (sel) styleChip(it, st.bg, st.fg, st.stroke)
                    else styleChip(it, p.chipOffBg, p.chipOffText, p.chipOffStroke)
                }
            )
        }
        contentHost.addView(spdRow)

        if (!ocrSummary.isNullOrBlank()) {
            contentHost.addView(TextView(context).apply {
                text = ocrSummary
                textSize = 9.5f
                setTextColor(p.primary)
                setPadding(dp(2), dp(2), dp(2), dp(2))
            })
        }
    }

    // ==================== TAB 1: 策略 (100% Parity with Strategy Editor!) ====================
    private fun buildStrategyTab(p: OverlayPalette) {
        val cfg = activePreset.baseConfig
        addInfoBanner(s(R.string.priority_tier_hint_strategy), p)

        // 1. Switch Active Strategy Profile
        addSectionLabel(s(R.string.ov_switch_strategy_hdr), p)
        val list = presets.ifEmpty {
            TeamPresetRepository.defaultBuiltInPresets().map { TeamPresetRecord(it, true, 0L) }
        }
        val presetScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        }
        val presetRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        list.forEachIndexed { idx, rec ->
            val selected = rec.preset.id == settings.activePresetId
            presetRow.addView(
                makeToggle(
                    LocaleHelper.displayPresetName(lctx, rec.preset, rec.isBuiltIn),
                    selected,
                    p,
                    marginEndDp = if (idx == list.lastIndex) 0 else 4
                ) { onSelectPreset(rec.preset.id) }
            )
        }
        presetScroll.addView(presetRow)
        contentHost.addView(presetScroll)

        // 2. Strategy Steps & Compute Slider (with Follow-System toggle!)
        val effSteps = if (cfg.useSystemSteps) settings.maxSteps else cfg.maxSteps
        val stepsHdrRow = hRow(bottomMarginDp = 3)
        stepsHdrRow.addView(TextView(context).apply {
            text = "${s(R.string.unit_steps)}: $effSteps"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(p.primary)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        stepsHdrRow.addView(
            makeToggle(
                s(if (cfg.useSystemSteps) R.string.budget_follow_system else R.string.budget_override_strategy),
                cfg.useSystemSteps,
                p,
                marginEndDp = 0
            ) {
                onUpdateActivePresetConfig { it.copy(useSystemSteps = !it.useSystemSteps) }
            }
        )
        contentHost.addView(stepsHdrRow)

        val stepsRow = hRow(bottomMarginDp = 6)
        listOf(30, 60, 100, 150, 200).forEachIndexed { idx, st ->
            stepsRow.addView(
                makeToggle(
                    "$st",
                    !cfg.useSystemSteps && cfg.maxSteps == st,
                    p,
                    weight = 1f,
                    marginEndDp = if (idx == 4) 0 else 4
                ) {
                    onUpdateActivePresetConfig { it.copy(maxSteps = st, useSystemSteps = false) }
                }
            )
        }
        contentHost.addView(stepsRow)

        // Unified Compute SeekBar Slider (40..600)
        val effBeam = if (cfg.useSystemBeam) settings.beamWidth else cfg.beamWidth
        val beamHdrRow = hRow(bottomMarginDp = 2)
        val beamLabel = TextView(context).apply {
            text = "${s(R.string.slider_beam)}: $effBeam"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(p.primary)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        beamHdrRow.addView(beamLabel)
        beamHdrRow.addView(
            makeToggle(
                s(if (cfg.useSystemBeam) R.string.budget_follow_system else R.string.budget_override_strategy),
                cfg.useSystemBeam,
                p,
                marginEndDp = 0
            ) {
                onUpdateActivePresetConfig { it.copy(useSystemBeam = !it.useSystemBeam) }
            }
        )
        contentHost.addView(beamHdrRow)
        contentHost.addView(makeComputeSeekBar(effBeam, p, beamLabel) { newBeam ->
            onUpdateActivePresetConfig { it.copy(beamWidth = newBeam, useSystemBeam = false) }
        })

        // 3. Grouped Global Matching Goal (N / X)
        addSectionLabel(s(R.string.section_goal_mode), p)
        val goalGroup = cfg.goalMode.normalizedGroup()
        val curGoalN = if (cfg.goalMode == goalGroup) cfg.goalParamN else cfg.goalMode.defaultParamN()
        val curGoalX = if (cfg.goalMode == goalGroup) cfg.goalParamX else cfg.goalMode.defaultParamX()
        val gModes = listOf(
            StrategyGoalMode.HIGH_COMBO,
            StrategyGoalMode.N_COLOR_BALANCE,
            StrategyGoalMode.N_COLOR_ALL,
            StrategyGoalMode.SINGLE_COLOR_X_ORBS,
            StrategyGoalMode.MULTI_COLOR_X_ORBS
        )
        val gRow1 = hRow(bottomMarginDp = 4)
        gModes.take(3).forEachIndexed { idx, gm ->
            gRow1.addView(makeToggle(LocaleHelper.goalModeLabel(lctx, gm), goalGroup == gm, p, weight = 1f, marginEndDp = if (idx == 2) 0 else 4) {
                onUpdateActivePresetConfig {
                    it.copy(goalMode = gm, goalParamN = curGoalN.coerceIn(2, 5), goalParamX = curGoalX.coerceIn(4, 9), goalElementsMask = 0)
                }
            })
        }
        contentHost.addView(gRow1)
        val gRow2 = hRow(bottomMarginDp = 5)
        gModes.drop(3).forEachIndexed { idx, gm ->
            gRow2.addView(makeToggle(LocaleHelper.goalModeLabel(lctx, gm), goalGroup == gm, p, weight = 1f, marginEndDp = if (idx == 1) 0 else 4) {
                onUpdateActivePresetConfig {
                    it.copy(goalMode = gm, goalParamN = curGoalN.coerceIn(2, 5), goalParamX = curGoalX.coerceIn(4, 9), goalElementsMask = 0)
                }
            })
        }
        contentHost.addView(gRow2)

        if (goalGroup == StrategyGoalMode.N_COLOR_BALANCE || goalGroup == StrategyGoalMode.N_COLOR_ALL || goalGroup == StrategyGoalMode.MULTI_COLOR_X_ORBS) {
            val nRange = if (goalGroup == StrategyGoalMode.N_COLOR_ALL) 2..6 else 2..5
            val nRow = hRow(bottomMarginDp = 4)
            nRange.forEach { n ->
                nRow.addView(makeToggle(s(R.string.goal_unit_colors, n), curGoalN == n, p, weight = 1f, marginEndDp = if (n == nRange.last) 0 else 3) {
                    onUpdateActivePresetConfig { it.copy(goalMode = goalGroup, goalParamN = n, goalElementsMask = 0) }
                })
            }
            contentHost.addView(nRow)
        }
        if (goalGroup == StrategyGoalMode.SINGLE_COLOR_X_ORBS || goalGroup == StrategyGoalMode.MULTI_COLOR_X_ORBS) {
            val xRow = hRow(bottomMarginDp = 4)
            for (x in 4..9) {
                xRow.addView(makeToggle(s(R.string.goal_unit_orbs, x), curGoalX == x, p, weight = 1f, marginEndDp = if (x == 9) 0 else 3) {
                    onUpdateActivePresetConfig { it.copy(goalMode = goalGroup, goalParamX = x) }
                })
            }
            contentHost.addView(xRow)
        }
        if (goalGroup == StrategyGoalMode.SINGLE_COLOR_X_ORBS) {
            val tRow = hRow(bottomMarginDp = 5)
            tRow.addView(makeToggle(s(R.string.puzzle_elem_any), cfg.goalTargetElement < 0, p, weight = 1.4f) {
                onUpdateActivePresetConfig { it.copy(goalMode = goalGroup, goalTargetElement = -1) }
            })
            for (e in 0..5) {
                tRow.addView(makeElementBtn(e, cfg.goalTargetElement == e, p, weight = 1f, marginEndDp = if (e == 5) 0 else 3) {
                    onUpdateActivePresetConfig { it.copy(goalMode = goalGroup, goalTargetElement = e) }
                })
            }
            contentHost.addView(tRow)
        }

        // 4. Multi-Orb Policy & Search Style
        addSectionLabel(s(R.string.section_match_policy), p)
        val polRow1 = hRow(bottomMarginDp = 4)
        listOf(
            FourPlusPolicy.MAX_COMBO to s(R.string.policy_max_combo),
            FourPlusPolicy.MAX_ORBS to s(R.string.policy_max_orbs),
            FourPlusPolicy.SINGLE_GROUP to s(R.string.policy_single_group)
        ).forEachIndexed { idx, (pol, lbl) ->
            polRow1.addView(makeToggle(lbl, cfg.fourPlusPolicy == pol, p, weight = 1f, marginEndDp = if (idx == 2) 0 else 4) {
                onUpdateActivePresetConfig { it.copy(fourPlusPolicy = pol) }
            })
        }
        contentHost.addView(polRow1)
        val polRow2 = hRow(bottomMarginDp = 5)
        polRow2.addView(makeToggle(s(R.string.policy_not_enough_keep), cfg.notEnoughPolicy == NotEnoughPolicy.KEEP, p, weight = 1f) {
            onUpdateActivePresetConfig {
                it.copy(notEnoughPolicy = if (it.notEnoughPolicy == NotEnoughPolicy.KEEP) NotEnoughPolicy.MATCH_ANYWAY else NotEnoughPolicy.KEEP)
            }
        })
        polRow2.addView(makeToggle(s(R.string.policy_best_result), !cfg.earlyStopOnGoal, p, weight = 0.85f) {
            onUpdateActivePresetConfig { it.copy(earlyStopOnGoal = false) }
        })
        polRow2.addView(makeToggle(s(R.string.policy_first_result), cfg.earlyStopOnGoal, p, weight = 0.85f, marginEndDp = 0) {
            onUpdateActivePresetConfig { it.copy(earlyStopOnGoal = true) }
        })
        contentHost.addView(polRow2)

        // 5. Per-Element 4-Column Rules Matrix
        addSectionLabel(s(R.string.section_elem_matrix), p)
        val shapeCycle = listOf(
            TargetShape.ANY, TargetShape.COUNT_2, TargetShape.COUNT_4, TargetShape.COUNT_5,
            TargetShape.ROW, TargetShape.COUNT_6, TargetShape.COUNT_7, TargetShape.COUNT_8,
            TargetShape.COUNT_9, TargetShape.CLEAR_ALL
        )
        for (e in 0..5) {
            val er = cfg.elementRules.getOrElse(e) { ElementRule() }
            val row = hRow(bottomMarginDp = 3)
            row.addView(makeElementBtn(e, true, p, weight = 0.65f, marginEndDp = 4) {})
            row.addView(makeToggle(LocaleHelper.targetShapeLabel(lctx, er.targetShape), er.targetShape != TargetShape.ANY, p, weight = 1.05f) {
                onUpdateActivePresetConfig { cur ->
                    val listR = cur.elementRules.toMutableList()
                    val nxt = shapeCycle[(shapeCycle.indexOf(er.targetShape) + 1).coerceAtLeast(0) % shapeCycle.size]
                    listR[e] = er.copy(targetShape = nxt)
                    cur.copy(elementRules = listR)
                }
            })
            row.addView(makeToggle(LocaleHelper.elementPriorityLabel(lctx, er.priority), er.priority != ElementPriority.NORMAL, p, weight = 0.9f) {
                onUpdateActivePresetConfig { cur ->
                    val listR = cur.elementRules.toMutableList()
                    val nxt = when (er.priority) {
                        ElementPriority.NORMAL -> ElementPriority.HIGH
                        ElementPriority.HIGH -> ElementPriority.RESERVE
                        ElementPriority.RESERVE -> ElementPriority.NORMAL
                    }
                    listR[e] = er.copy(priority = nxt)
                    cur.copy(elementRules = listR)
                }
            })
            row.addView(makeToggle(LocaleHelper.matchTimingLabel(lctx, er.timing), er.timing != MatchTiming.ANY, p, weight = 0.9f) {
                onUpdateActivePresetConfig { cur ->
                    val listR = cur.elementRules.toMutableList()
                    val nxt = when (er.timing) {
                        MatchTiming.ANY -> MatchTiming.MUST_FIRST_WAVE
                        MatchTiming.MUST_FIRST_WAVE -> MatchTiming.FORBID_FIRST_WAVE_ALLOW_CASCADE
                        MatchTiming.FORBID_FIRST_WAVE_ALLOW_CASCADE -> MatchTiming.FORBID_ALL
                        MatchTiming.FORBID_ALL -> MatchTiming.ANY
                    }
                    listR[e] = er.copy(timing = nxt)
                    cur.copy(elementRules = listR)
                }
            })
            row.addView(makeToggle(s(R.string.min_match_fmt, er.minMatchCount), er.minMatchCount != 3, p, weight = 0.8f, marginEndDp = 0) {
                onUpdateActivePresetConfig { cur ->
                    val listR = cur.elementRules.toMutableList()
                    val nxt = when (er.minMatchCount) { 3 -> 2; 2 -> 4; else -> 3 }
                    listR[e] = er.copy(minMatchCount = nxt)
                    cur.copy(elementRules = listR)
                }
            })
            contentHost.addView(row)
        }

        // Leader Attribute Link Presets
        addSectionLabel(s(R.string.section_link_preset), p)
        val isHeartAll = (cfg.compatibleLinkMask.getOrElse(5) { 1 shl 5 } and 0x3F) == 0x3F
        val isLightDark = ((cfg.compatibleLinkMask.getOrElse(3) { 1 shl 3 } and (1 shl 4)) != 0) && !isHeartAll
        val isWfg = ((cfg.compatibleLinkMask.getOrElse(0) { 1 shl 0 } and (1 shl 1)) != 0) && !isHeartAll
        val lnkRow = hRow(bottomMarginDp = 5)
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
            val wfg = 0x07
            onUpdateActivePresetConfig { it.copy(compatibleLinkMask = List(6) { e -> if (e in 0..2) wfg else (1 shl e) }) }
        })
        contentHost.addView(lnkRow)

        // 6. Columns 1..6 Amelioration (2×3)
        addSectionLabel(s(R.string.section_columns), p)
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
                        val colList = cur.columnRules.toMutableList()
                        colList[c] = nxt
                        cur.copy(columnRules = colList)
                    }
                })
            }
            contentHost.addView(cRow)
        }

        // 7. Start Position & Character Orb Slot
        addSectionLabel(s(R.string.section_start_char), p)
        val stRow = hRow(bottomMarginDp = 4)
        listOf(
            StartPointPreset.ANY to s(R.string.start_any),
            StartPointPreset.BOTTOM_LEFT to s(R.string.start_bl),
            StartPointPreset.BOTTOM_RIGHT to s(R.string.start_br)
        ).forEachIndexed { idx, (sp, lbl) ->
            stRow.addView(makeToggle(lbl, cfg.startPreset == sp, p, weight = 1f, marginEndDp = if (idx == 2) 0 else 4) {
                onUpdateActivePresetConfig { it.copy(startPreset = sp) }
            })
        }
        contentHost.addView(stRow)
        val chRow = hRow(bottomMarginDp = 5)
        chRow.addView(makeToggle(s(R.string.char_off), !cfg.useCharacterOrb, p, weight = 1f) {
            onUpdateActivePresetConfig { it.copy(useCharacterOrb = false) }
        })
        for (c in 0..5) {
            chRow.addView(makeToggle("${c + 1}", cfg.useCharacterOrb && cfg.characterOrbCol == c, p, weight = 0.8f, marginEndDp = if (c == 5) 0 else 3) {
                onUpdateActivePresetConfig { it.copy(useCharacterOrb = true, characterOrbCol = c) }
            })
        }
        contentHost.addView(chRow)

        // 8. Advanced Strategy Switches
        addSectionLabel(s(R.string.section_strategy_switches), p)
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
        contentHost.addView(swRow1)
        val swRow2 = hRow(bottomMarginDp = 4)
        swRow2.addView(makeToggle(s(R.string.switch_prefer_ench), cfg.preferEnchanted, p, weight = 1f) {
            onUpdateActivePresetConfig { it.copy(preferEnchanted = !it.preferEnchanted) }
        })
        swRow2.addView(makeToggle(s(R.string.switch_prefer_race), cfg.preferRace, p, weight = 1f) {
            onUpdateActivePresetConfig { it.copy(preferRace = !it.preferRace) }
        })
        swRow2.addView(makeToggle(s(R.string.switch_hit_weathered), cfg.allowHitWeatheredToClear, p, weight = 1f, marginEndDp = 0) {
            onUpdateActivePresetConfig { it.copy(allowHitWeatheredToClear = !it.allowHitWeatheredToClear) }
        })
        contentHost.addView(swRow2)
    }

    // ==================== TAB 2: 解盾 (100% Parity with Launch App Shields Tab!) ====================
    private fun buildShieldsTab(p: OverlayPalette) {
        val byId = activeChips.associateBy { it.id }
        val activeIds = byId.keys
        addInfoBanner(s(R.string.priority_tier_hint_shields), p)

        // 0. Active Wave Shields Strip + Clear
        if (activeChips.isNotEmpty()) {
            val chipScroll = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
            }
            val chipRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in activeChips) {
                chipRow.addView(makeChip("${LocaleHelper.formatWaveChipLabel(lctx, c)} ×", padH = 6) {
                    onToggleWaveChip(c)
                }.also { styleChip(it, p.primaryContainer, p.onPrimaryContainer, p.primary) })
            }
            chipRow.addView(makeChip(s(R.string.btn_clear), padH = 6, marginEndDp = 0) { onClearWaveChips() }.also {
                styleChip(it, p.errorContainer, p.onErrorContainer, null)
            })
            chipScroll.addView(chipRow)
            contentHost.addView(chipScroll)
        }

        // 1. Combo Shield (Full Game-Accurate Rules & Validation)
        addSectionLabel(s(R.string.section_combo_shield), p)
        val curComboMode = when {
            "min_combo" in activeIds -> "min_combo"
            "min_first_combo" in activeIds -> "min_first_combo"
            "exact_combo" in activeIds -> "exact_combo"
            "exact_first_combo" in activeIds -> "exact_first_combo"
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
                "exact_combo" -> WaveShieldChip("exact_combo", "${s(R.string.combo_type_exact_total)} $exactTotalComboVal", ShieldCategory.COMBO, paramInt = exactTotalComboVal)
                "exact_first_combo" -> WaveShieldChip("exact_first_combo", "${s(R.string.combo_type_exact_first)} $exactFirstComboVal", ShieldCategory.COMBO, paramInt = exactFirstComboVal)
                "zero_combo" -> {
                    listOf(
                        "same_combo_elements", "second_wave_combo", "same_elem_combos",
                        "equal_pair_light_dark", "first_wave_full_clear", "digital_orb_count",
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
        listOf(
            "none" to s(R.string.combo_type_none),
            "min_combo" to s(R.string.combo_type_min_total),
            "min_first_combo" to s(R.string.combo_type_min_first),
            "exact_combo" to s(R.string.combo_type_exact_total)
        ).forEachIndexed { i, (id, lbl) ->
            cRow1.addView(makeToggle(lbl, curComboMode == id, p, weight = 1f, marginEndDp = if (i == 3) 0 else 3) { applyCombo(id) })
        }
        contentHost.addView(cRow1)
        val cRow2 = hRow(bottomMarginDp = 4)
        listOf(
            "exact_first_combo" to s(R.string.combo_type_exact_first),
            "zero_combo" to s(R.string.combo_type_zero),
            "odd_combo" to s(R.string.combo_type_odd),
            "even_combo" to s(R.string.combo_type_even)
        ).forEachIndexed { i, (id, lbl) ->
            cRow2.addView(makeToggle(lbl, curComboMode == id, p, weight = 1f, marginEndDp = if (i == 3) 0 else 3) { applyCombo(id) })
        }
        contentHost.addView(cRow2)

        if (curComboMode in setOf("min_combo", "min_first_combo", "exact_combo", "exact_first_combo")) {
            val activeVal = when (curComboMode) {
                "min_combo" -> minTotalComboVal
                "min_first_combo" -> minFirstComboVal
                "exact_combo" -> exactTotalComboVal
                else -> exactFirstComboVal
            }
            fun stepC(delta: Int) {
                when (curComboMode) {
                    "min_combo" -> minTotalComboVal = (minTotalComboVal + delta).coerceIn(1, 15)
                    "min_first_combo" -> minFirstComboVal = (minFirstComboVal + delta).coerceIn(1, 12)
                    "exact_combo" -> exactTotalComboVal = (exactTotalComboVal + delta).coerceIn(1, 15)
                    "exact_first_combo" -> exactFirstComboVal = (exactFirstComboVal + delta).coerceIn(1, 12)
                }
                applyCombo(curComboMode)
            }
            val valText = if (curComboMode.startsWith("min")) s(R.string.combo_value_min_fmt, activeVal) else s(R.string.combo_value_exact_fmt, activeVal)
            contentHost.addView(makeStepperRow(valText, p, { stepC(-1) }, { stepC(+1) }))
        } else if (curComboMode == "odd_combo" || curComboMode == "even_combo") {
            val pRow = hRow(bottomMarginDp = 4)
            pRow.addView(makeToggle(s(R.string.combo_parity_scope_first), parityFirstOnly, p, weight = 1f) {
                parityFirstOnly = true; applyCombo(curComboMode)
            })
            pRow.addView(makeToggle(s(R.string.combo_parity_scope_total), !parityFirstOnly, p, weight = 1f, marginEndDp = 0) {
                parityFirstOnly = false; applyCombo(curComboMode)
            })
            contentHost.addView(pRow)
        }

        if (curComboMode != "zero_combo") {
            val exRow1 = hRow(bottomMarginDp = 4)
            exRow1.addView(makeToggle(s(R.string.combo_extra_same_c), "same_combo_elements" in activeIds, p, weight = 1f) {
                onToggleWaveChip(WaveShieldChip("same_combo_elements", s(R.string.combo_extra_same_c), ShieldCategory.COMBO))
            })
            exRow1.addView(makeToggle(s(R.string.combo_extra_second_wave, secondWaveVal), "second_wave_combo" in activeIds, p, weight = 1f, marginEndDp = 0) {
                onToggleWaveChip(WaveShieldChip("second_wave_combo", s(R.string.combo_extra_second_wave, secondWaveVal), ShieldCategory.COMBO, paramInt = secondWaveVal))
            })
            contentHost.addView(exRow1)
            val exRow2 = hRow(bottomMarginDp = 5)
            val sameMask = if (sameElemTarget in 0..5) (1 shl sameElemTarget) else 0
            exRow2.addView(makeToggle(s(R.string.combo_extra_same_elem, sameElemGroupsVal), "same_elem_combos" in activeIds, p, weight = 1f) {
                onToggleWaveChip(WaveShieldChip("same_elem_combos", s(R.string.combo_extra_same_elem, sameElemGroupsVal), ShieldCategory.COMBO, paramInt = sameElemGroupsVal, paramElementMask = sameMask))
            })
            val pairLbl = s(R.string.combo_extra_equal_pair, LocaleHelper.elementLabel(lctx, pairElemA), LocaleHelper.elementLabel(lctx, pairElemB))
            exRow2.addView(makeToggle(pairLbl, "equal_pair_light_dark" in activeIds, p, weight = 1f, marginEndDp = 0) {
                onToggleWaveChip(WaveShieldChip("equal_pair_light_dark", pairLbl, ShieldCategory.COMBO, paramElementMask = pairElemA or (pairElemB shl 4)))
            })
            contentHost.addView(exRow2)
        }

        // 2. Attribute & Count Shield (With Per-Color Independent Enchanted Row!)
        addSectionLabel(s(R.string.section_elem_count_shield), p)
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
            if (cReq == 0) {
                ActiveStrategyStore.replaceGroup(legacyReqIds, null)
            } else {
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
        contentHost.addView(distRow)

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
        contentHost.addView(reqRow)

        val enchRow = hRow(bottomMarginDp = 5)
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
        contentHost.addView(enchRow)

        val limTabs = hRow(bottomMarginDp = 3)
        listOf(s(R.string.elem_limit_anti), s(R.string.elem_limit_forbid), s(R.string.elem_limit_clear_all)).forEachIndexed { idx, lbl ->
            limTabs.addView(makeToggle(lbl, elemLimitMode == idx, p, weight = 1f, marginEndDp = if (idx == 2) 0 else 4) {
                elemLimitMode = idx; rebuildContent()
            })
        }
        contentHost.addView(limTabs)
        val curLimMask = when (elemLimitMode) { 0 -> antiMask; 1 -> forbidMask; else -> clearMask }
        val limRow = hRow(bottomMarginDp = 5)
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
                    else -> {
                        writeLimit("clear_all_element", s(R.string.elem_limit_clear_all), nxt)
                        if (on) {
                            if ((antiMask and bit) != 0) writeLimit("anti_first_wave", s(R.string.elem_limit_anti), antiMask and bit.inv())
                            if ((forbidMask and bit) != 0) writeLimit("forbid_all", s(R.string.elem_limit_forbid), forbidMask and bit.inv())
                        }
                    }
                }
            })
        }
        contentHost.addView(limRow)

        // Orb Count & All 5 Special Runestone Shields
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
        contentHost.addView(cntRow1)

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
        contentHost.addView(spRow1)

        val spRow2 = hRow(bottomMarginDp = 5)
        spRow2.addView(makeToggle(s(R.string.shield_forbid_weakened), "forbid_weakened_orb" in activeIds, p, weight = 1f) {
            onToggleWaveChip(WaveShieldChip("forbid_weakened_orb", s(R.string.shield_forbid_weakened), ShieldCategory.COUNT_AND_ELEMENT))
        })
        spRow2.addView(makeToggle(s(R.string.shield_luminous_orb), "luminous_orb_shield" in activeIds, p, weight = 1f, marginEndDp = 0) {
            onToggleWaveChip(WaveShieldChip("luminous_orb_shield", s(R.string.shield_luminous_orb), ShieldCategory.COUNT_AND_ELEMENT))
        })
        contentHost.addView(spRow2)

        // 3. Puzzle & Shape Shields (including Multi-Cross Color Picker!)
        addSectionLabel(s(R.string.section_puzzle_shape_shield), p)
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
        pzRow.addView(makeToggle(s(R.string.puzzle_mode_strict), pMode == 2, p, weight = 1f) { savePuz(2, pEnch, pElem, pShape) })
        if (pMode > 0) {
            pzRow.addView(makeToggle(s(R.string.ov_puzzle_ench), pEnch, p, weight = 0.9f, marginEndDp = 0) { savePuz(pMode, !pEnch, pElem, pShape) })
        }
        contentHost.addView(pzRow)
        if (pMode > 0) {
            val pzColRow = hRow(bottomMarginDp = 4)
            pzColRow.addView(makeToggle(s(R.string.puzzle_elem_any), pElem < 0, p, weight = 1.4f) { savePuz(pMode, pEnch, -1, pShape) })
            for (e in 0..5) {
                pzColRow.addView(makeElementBtn(e, pElem == e, p, weight = 1f, marginEndDp = if (e == 5) 0 else 3) { savePuz(pMode, pEnch, e, pShape) })
            }
            contentHost.addView(pzColRow)
        }

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
        contentHost.addView(crRow)
        if (cCnt > 0) {
            if (cCnt >= 2) {
                val cdRow = hRow(bottomMarginDp = 3)
                cdRow.addView(makeToggle(s(R.string.cross_distinct_switch), cDist, p, weight = 1f, marginEndDp = 0) { saveCross(cCnt, !cDist, cColors) })
                contentHost.addView(cdRow)
            }
            val ccRow = hRow(bottomMarginDp = 4)
            for (e in 0..5) {
                val bit = 1 shl e
                ccRow.addView(makeElementBtn(e, (cColors and bit) != 0, p, weight = 1f, marginEndDp = if (e == 5) 0 else 3) {
                    val nxt = cColors xor bit
                    if ((nxt and bit) == 0 || Integer.bitCount(nxt) <= cCnt) saveCross(cCnt, cDist, nxt)
                })
            }
            contentHost.addView(ccRow)
        }

        val shRow = hRow(bottomMarginDp = 5)
        shRow.addView(makeToggle(s(R.string.shield_l_shape), "l_shape" in activeIds, p, weight = 1f) {
            onToggleWaveChip(WaveShieldChip("l_shape", s(R.string.shield_l_shape), ShieldCategory.SHAPE_AND_PUZZLE))
        })
        shRow.addView(makeToggle(s(R.string.shield_t_shape), "t_shape" in activeIds, p, weight = 1f) {
            onToggleWaveChip(WaveShieldChip("t_shape", s(R.string.shield_t_shape), ShieldCategory.SHAPE_AND_PUZZLE))
        })
        shRow.addView(makeToggle(s(R.string.shield_sq_2x2), "square_2x2" in activeIds, p, weight = 1f) {
            onToggleWaveChip(WaveShieldChip("square_2x2", s(R.string.shield_sq_2x2), ShieldCategory.SHAPE_AND_PUZZLE))
        })
        shRow.addView(makeToggle(s(R.string.shield_all_cols), "all_columns_ameliorate" in activeIds, p, weight = 1.2f, marginEndDp = 0) {
            onToggleWaveChip(WaveShieldChip("all_columns_ameliorate", s(R.string.shield_all_cols), ShieldCategory.SHAPE_AND_PUZZLE))
        })
        contentHost.addView(shRow)

        // 4. Path, Terrain & Step Shields
        addSectionLabel(s(R.string.section_path_hazard_shield), p)
        val burnChip = byId["zero_trail_burning"]
        val burnOn = burnChip != null
        val burnLen = (burnChip?.paramInt ?: 0) and 0xFF
        val burnHurts = ((burnChip?.paramInt ?: 0) ushr 8) and 0xFF
        val ptRow1 = hRow(bottomMarginDp = 4)
        ptRow1.addView(makeToggle(s(R.string.ov_burn_path), burnOn, p, weight = 1f) {
            if (burnOn) ActiveStrategyStore.removeChip("zero_trail_burning")
            else onUpsertWaveChip(WaveShieldChip("zero_trail_burning", s(R.string.burn_path_title), ShieldCategory.HAZARD_AND_PATH, paramInt = 0))
        })
        if (burnOn) {
            ptRow1.addView(makeChip(s(if (burnLen == 6) R.string.ov_burn_len_6 else R.string.ov_burn_len_max), weight = 0.9f) {
                val nLen = if (burnLen == 6) 0 else 6
                onUpsertWaveChip(WaveShieldChip("zero_trail_burning", s(R.string.burn_path_title), ShieldCategory.HAZARD_AND_PATH, paramInt = nLen or (burnHurts shl 8)))
            }.also { styleChip(it, p.secondaryContainer, p.onSecondaryContainer, null) })
            ptRow1.addView(makeChip(s(R.string.ov_burn_hurts_fmt, burnHurts), weight = 0.8f) {
                val nH = (burnHurts + 1) % 6
                onUpsertWaveChip(WaveShieldChip("zero_trail_burning", s(R.string.burn_path_title), ShieldCategory.HAZARD_AND_PATH, paramInt = burnLen or (nH shl 8)))
            }.also { styleChip(it, p.secondaryContainer, p.onSecondaryContainer, null) })
        }
        ptRow1.addView(makeToggle(s(R.string.ov_step_limit_short, stepLimitVal), "step_limit" in activeIds, p, weight = 1f, marginEndDp = 0) {
            onToggleWaveChip(WaveShieldChip("step_limit", s(R.string.shield_step_limit_fmt, stepLimitVal), ShieldCategory.HAZARD_AND_PATH, paramInt = stepLimitVal))
        })
        contentHost.addView(ptRow1)

        val ptRow2 = hRow(bottomMarginDp = 4)
        ptRow2.addView(makeToggle(s(R.string.shield_trail_corrosion), "trail_corrosion" in activeIds, p, weight = 1f) {
            onToggleWaveChip(WaveShieldChip("trail_corrosion", s(R.string.shield_trail_corrosion), ShieldCategory.HAZARD_AND_PATH, paramInt = 15))
        })
        ptRow2.addView(makeToggle(s(R.string.shield_step_hurt), "step_hurt_minimize" in activeIds, p, weight = 1f) {
            onToggleWaveChip(WaveShieldChip("step_hurt_minimize", s(R.string.shield_step_hurt), ShieldCategory.HAZARD_AND_PATH))
        })
        ptRow2.addView(makeToggle(s(R.string.shield_char_terrain), "char_orb_terrain" in activeIds, p, weight = 1f) {
            onToggleWaveChip(WaveShieldChip("char_orb_terrain", s(R.string.shield_char_terrain), ShieldCategory.HAZARD_AND_PATH))
        })
        ptRow2.addView(makeToggle(s(R.string.shield_weijian_burst), "weijian_burst" in activeIds, p, weight = 1f, marginEndDp = 0) {
            onToggleWaveChip(WaveShieldChip("weijian_burst", s(R.string.shield_weijian_burst), ShieldCategory.COMPOSITE))
        })
        contentHost.addView(ptRow2)
    }

    // ==================== TAB 3: 设置 (System-Level Settings & Live Preview/Theme/Language) ====================
    private fun buildSettingsTab(p: OverlayPalette) {
        addInfoBanner(s(R.string.priority_tier_hint_settings), p)

        addSectionLabel(s(R.string.ov_steps_hdr_fmt, settings.maxSteps), p)
        val stepRow = hRow(bottomMarginDp = 5)
        listOf(30, 60, 100, 150, 200).forEachIndexed { idx, st ->
            stepRow.addView(makeToggle("$st", settings.maxSteps == st, p, weight = 1f, marginEndDp = if (idx == 4) 0 else 4) {
                onUpdateSettings { it.copy(maxSteps = st) }
            })
        }
        contentHost.addView(stepRow)

        val beamLbl = TextView(context).apply {
            text = "${s(R.string.slider_beam)}: ${settings.beamWidth}"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(p.primary)
        }
        contentHost.addView(beamLbl)
        contentHost.addView(makeComputeSeekBar(settings.beamWidth, p, beamLbl) { nb ->
            onUpdateSettings { it.copy(beamWidth = nb) }
        })

        addSectionLabel(s(R.string.ov_core_theme_hdr), p)
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
        contentHost.addView(swRow1)

        val swRow2 = hRow(bottomMarginDp = 5)
        swRow2.addView(makeToggle(s(R.string.mode_human_title), settings.humanMode, p, weight = 1f) {
            onUpdateSettings { it.copy(humanMode = !it.humanMode) }
        })
        swRow2.addView(makeToggle(s(R.string.mode_stop_title), settings.showEmergencyStop, p, weight = 1f, marginEndDp = 0) {
            onUpdateSettings { it.copy(showEmergencyStop = !it.showEmergencyStop) }
        })
        contentHost.addView(swRow2)

        val themeRow = hRow(bottomMarginDp = 4)
        ThemeMode.entries.forEachIndexed { idx, tm ->
            themeRow.addView(makeToggle(LocaleHelper.themeLabel(lctx, tm), settings.themeMode == tm, p, weight = 1f, marginEndDp = if (idx == ThemeMode.entries.lastIndex) 0 else 4) {
                onUpdateSettings { it.copy(themeMode = tm) }
            })
        }
        contentHost.addView(themeRow)

        val langRow = hRow(bottomMarginDp = 2)
        AppLanguage.entries.forEachIndexed { idx, lang ->
            langRow.addView(makeToggle(LocaleHelper.languageLabel(lctx, lang), settings.language == lang, p, weight = 1f, marginEndDp = if (idx == AppLanguage.entries.lastIndex) 0 else 4) {
                onUpdateSettings { it.copy(language = lang) }
            })
        }
        contentHost.addView(langRow)
    }

    // ==================== Shared Native View Helpers ====================
    private fun makeComputeSeekBar(
        currentValue: Int,
        p: OverlayPalette,
        liveLabel: TextView,
        onCommit: (Int) -> Unit
    ): SeekBar {
        // Range 40..600 step 20 => 0..28
        return SeekBar(context).apply {
            max = 28
            progress = ((currentValue - 40) / 20).coerceIn(0, 28)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(26)
            ).apply { bottomMargin = dp(4) }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                    val v = 40 + prog * 20
                    liveLabel.text = "${s(R.string.slider_beam)}: $v"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    val v = 40 + (sb?.progress ?: 0) * 20
                    onCommit(v)
                }
            })
        }
    }

    private fun makeStepperRow(valueText: String, p: OverlayPalette, onMinus: () -> Unit, onPlus: () -> Unit): LinearLayout {
        return hRow(bottomMarginDp = 4).apply {
            addView(makeChip("−", weight = 0.7f) { onMinus() }.also { styleChip(it, p.chipOffBg, p.onSurface, p.chipOffStroke) })
            addView(TextView(context).apply {
                text = valueText
                textSize = 10.5f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(p.primary)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.6f)
            })
            addView(makeChip("+", weight = 0.7f, marginEndDp = 0) { onPlus() }.also { styleChip(it, p.chipOffBg, p.onSurface, p.chipOffStroke) })
        }
    }

    private fun addInfoBanner(text: String, p: OverlayPalette) {
        contentHost.addView(TextView(context).apply {
            this.text = text
            textSize = 9.5f
            setTextColor(p.onPrimaryContainer)
            background = roundedBg(p.primaryContainer, dp(6).toFloat(), null)
            setPadding(dp(7), dp(3), dp(7), dp(3))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(5) }
        })
    }

    private fun addSectionLabel(text: String, p: OverlayPalette) {
        contentHost.addView(TextView(context).apply {
            this.text = text
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(p.primary)
            setPadding(dp(2), dp(4), dp(2), dp(3))
        })
    }

    private fun hRow(bottomMarginDp: Int = 4): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(bottomMarginDp) }
    }

    private fun makeChip(
        label: String,
        padH: Int = 5,
        weight: Float = 0f,
        marginEndDp: Int = 4,
        onClick: () -> Unit
    ): TextView = TextView(context).apply {
        text = label
        textSize = 10f
        setSingleLine(true)
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(padH), dp(4), dp(padH), dp(4))
        layoutParams = if (weight > 0f) {
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight).apply {
                marginEnd = dp(marginEndDp)
            }
        } else {
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(marginEndDp)
            }
        }
        setOnClickListener { onClick() }
    }

    private fun makeToggle(
        label: String,
        selected: Boolean,
        p: OverlayPalette,
        weight: Float = 0f,
        marginEndDp: Int = 4,
        onClick: () -> Unit
    ): TextView = makeChip(label, weight = weight, marginEndDp = marginEndDp, onClick = onClick).also {
        if (selected) styleChip(it, p.primaryContainer, p.onPrimaryContainer, p.primary)
        else styleChip(it, p.chipOffBg, p.chipOffText, p.chipOffStroke)
    }

    private fun makeElementBtn(
        elemIndex: Int,
        selected: Boolean,
        p: OverlayPalette,
        suffix: String = "",
        weight: Float = 1f,
        marginEndDp: Int = 3,
        onClick: () -> Unit
    ): TextView {
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

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).roundToInt()
}
