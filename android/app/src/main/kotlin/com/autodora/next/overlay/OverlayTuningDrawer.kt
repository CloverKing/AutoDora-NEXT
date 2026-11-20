package com.autodora.next.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.autodora.next.data.*
import com.autodora.next.model.*
import com.autodora.next.ui.OverlayPalette

/**
 * In-Game 4-Tab System-Themed Floating Tuning Drawer (游戏内悬浮 4-Tab 调参抽屉):
 *  - Tab 0 「解盾」: Auto Enemy Skill Toggle + Abstract Parameterized Shield Controllers (Combo / Element Matrix / Shape / Burning Path)
 *  - Tab 1 「属性」: Goal Mode + Per-Element 4-Tuples (Shape / Priority / Timing / Min-Match) + Four-Plus & Not-Enough Policy
 *  - Tab 2 「专属」: Columns 1..6 Amelioration (关/3/4/5珠), Start Point, Character Orb Pull, Split-3, Diagonal Match
 *  - Tab 3 「策略」: Room Strategy Profile Switcher + Quick Steps + Complexity + Theme Switcher
 */
class OverlayTuningDrawerController(
    private val context: Context,
    private val onRunAutoSkillScan: () -> Unit,
    private val onToggleWaveChip: (WaveShieldChip) -> Unit,
    private val onUpsertWaveChip: (WaveShieldChip) -> Unit,
    private val onClearWaveChips: () -> Unit,
    private val onSelectPreset: (String) -> Unit,
    private val onUpdateActivePresetConfig: ((RuleConfig) -> RuleConfig) -> Unit,
    private val onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    private val onClose: () -> Unit
) {
    val rootView: LinearLayout

    private var selectedTab = 0
    private var comboNumVal = 6
    private var digitalOrbsVal = 18
    private var maxBurnHurtsVal = 0
    private var burnTrailLenVal = 0
    private var stepLimitVal = 15

    private var currentPalette: OverlayPalette? = null
    private var currentSettings: AppSettings = AppSettings()
    private var currentPresets: List<TeamPresetRecord> = emptyList()
    private var currentActivePreset: TeamBasePreset = TeamPresetRepository.defaultBuiltInPresets().first()
    private var currentChips: List<WaveShieldChip> = emptyList()
    private var currentOcrSummary: String? = null

    private val tabBtns = ArrayList<TextView>(4)
    private val closeBtn: TextView
    private val contentHost: LinearLayout

    init {
        rootView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            elevation = dp(4).toFloat()
            setPadding(dp(8), dp(6), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                dp(332),
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(3)
                marginStart = dp(20)
            }
        }

        val tabRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val tabTitles = listOf("解盾", "属性", "专属", "策略")
        tabTitles.forEachIndexed { idx, title ->
            val btn = TextView(context).apply {
                text = title
                textSize = 10.5f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setSingleLine(true)
                setPadding(dp(6), dp(4), dp(6), dp(4))
                layoutParams = LinearLayout.LayoutParams(0, dp(26), 1f).apply {
                    marginEnd = dp(3)
                }
                setOnClickListener {
                    selectedTab = idx
                    renderAll()
                }
            }
            tabBtns.add(btn)
            tabRow.addView(btn)
        }
        closeBtn = TextView(context).apply {
            text = "收起"
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setSingleLine(true)
            setPadding(dp(6), dp(4), dp(6), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(26)
            )
            setOnClickListener { onClose() }
        }
        tabRow.addView(closeBtn)
        rootView.addView(tabRow)

        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(264)
            ).apply {
                topMargin = dp(6)
            }
        }
        contentHost = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        scroll.addView(contentHost)
        rootView.addView(scroll)
    }

    fun updateData(
        palette: OverlayPalette,
        settings: AppSettings,
        presets: List<TeamPresetRecord>,
        activePreset: TeamBasePreset,
        activeChips: List<WaveShieldChip>,
        ocrSummary: String?
    ) {
        currentPalette = palette
        currentSettings = settings
        currentPresets = presets
        currentActivePreset = activePreset
        currentChips = activeChips
        currentOcrSummary = ocrSummary
        if (rootView.visibility == View.VISIBLE) {
            renderAll()
        } else {
            applyContainerChrome(palette)
        }
    }

    fun setVisible(visible: Boolean) {
        rootView.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) renderAll()
    }

    private fun applyContainerChrome(p: OverlayPalette) {
        rootView.background = rounded(p.containerBg, dp(14).toFloat(), p.containerStroke)
        styleChip(closeBtn, p.chipOffBg, p.onSurfaceVariant, p.chipOffStroke)
    }

    private fun renderAll() {
        val p = currentPalette ?: return
        applyContainerChrome(p)

        tabBtns.forEachIndexed { idx, btn ->
            if (idx == selectedTab) {
                styleChip(btn, p.primary, p.onPrimary, null)
            } else {
                styleChip(btn, p.chipOffBg, p.chipOffText, p.chipOffStroke)
            }
        }

        contentHost.removeAllViews()
        when (selectedTab) {
            0 -> renderShieldsTab(p)
            1 -> renderElementsTab(p)
            2 -> renderTosSpecialsTab(p)
            3 -> renderStrategiesTab(p)
        }
    }

    // ==================== Tab 0: Abstract Parameterized Shield Controls + Auto Enemy Skill Engine ====================
    private fun renderShieldsTab(p: OverlayPalette) {
        val byId = currentChips.associateBy { it.id }
        val activeIds = byId.keys

        // 1. Auto Enemy Skill Bar
        val autoRow = hRow(bottomMarginDp = 5)
        autoRow.addView(makeToggle("自动识技", currentSettings.autoEnemySkillEnabled, p) {
            onUpdateSettings { it.copy(autoEnemySkillEnabled = !it.autoEnemySkillEnabled) }
        })
        val modeBtn = makeChip(currentSettings.enemySkillScanMode.shortLabel, padH = 6) {
            val entries = EnemySkillScanMode.entries
            val next = entries[(currentSettings.enemySkillScanMode.ordinal + 1) % entries.size]
            onUpdateSettings { it.copy(enemySkillScanMode = next) }
        }
        styleChip(modeBtn, p.secondaryContainer, p.onSecondaryContainer, null)
        autoRow.addView(modeBtn)

        val scanNow = makeChip("立即识技", padH = 7) { onRunAutoSkillScan() }
        styleChip(scanNow, p.primary, p.onPrimary, null)
        autoRow.addView(scanNow)

        val clearBtn = makeChip("清空(${currentChips.size})", padH = 6, marginEndDp = 0) { onClearWaveChips() }
        styleChip(clearBtn, p.errorContainer, p.onErrorContainer, null)
        autoRow.addView(clearBtn)
        contentHost.addView(autoRow)

        currentOcrSummary?.let { summary ->
            contentHost.addView(TextView(context).apply {
                text = summary
                textSize = 9f
                setTextColor(p.primary)
                setPadding(dp(2), 0, dp(2), dp(4))
            })
        }

        // 2. Combo Shield Controller (Mode + Stepper)
        addSectionLabel("连击盾参数器", p)
        val curComboMode = when {
            "min_combo" in activeIds -> "min_combo"
            "exact_combo" in activeIds -> "exact_combo"
            "exact_first_combo" in activeIds -> "exact_first_combo"
            "zero_combo" in activeIds -> "zero_combo"
            "odd_combo" in activeIds -> "odd_combo"
            "even_combo" in activeIds -> "even_combo"
            else -> "none"
        }
        fun applyCombo(mode: String, num: Int) {
            val chip = when (mode) {
                "min_combo" -> WaveShieldChip("min_combo", "≥ ${num}C", ShieldCategory.COMBO, paramInt = num)
                "exact_combo" -> WaveShieldChip("exact_combo", "指定 ${num}C", ShieldCategory.COMBO, paramInt = num)
                "exact_first_combo" -> WaveShieldChip("exact_first_combo", "首消 ${num}C", ShieldCategory.COMBO, paramInt = num)
                "zero_combo" -> WaveShieldChip("zero_combo", "0C空转", ShieldCategory.COMBO)
                "odd_combo" -> WaveShieldChip("odd_combo", "奇数C", ShieldCategory.COMBO)
                "even_combo" -> WaveShieldChip("even_combo", "偶数C", ShieldCategory.COMBO)
                else -> null
            }
            ActiveStrategyStore.replaceGroup(WaveShieldCatalog.COMBO_EXCLUSIVE_IDS, chip)
        }
        val cRow1 = hRow(bottomMarginDp = 4)
        listOf(
            "none" to "不限",
            "min_combo" to "≥${comboNumVal}C",
            "exact_combo" to "指定${comboNumVal}C",
            "exact_first_combo" to "首消${comboNumVal}C",
            "zero_combo" to "0C"
        ).forEachIndexed { idx, (id, lbl) ->
            val btn = makeChip(if (curComboMode == id) "●$lbl" else lbl, padH = 5, marginEndDp = if (idx == 4) 0 else 3) {
                applyCombo(id, comboNumVal)
            }
            styleToggle(btn, curComboMode == id, p.primary, p.onPrimary, p)
            cRow1.addView(btn)
        }
        contentHost.addView(cRow1)

        val cRow2 = hRow(bottomMarginDp = 5)
        cRow2.addView(makeChip("−", padH = 7) {
            comboNumVal = (comboNumVal - 1).coerceIn(1, 15)
            val m = if (curComboMode in setOf("min_combo", "exact_combo", "exact_first_combo")) curComboMode else "exact_first_combo"
            applyCombo(m, comboNumVal)
        }.also { styleChip(it, p.chipOffBg, p.onSurface, p.chipOffStroke) })
        cRow2.addView(makeChip("N=$comboNumVal", padH = 6) {}.also { styleChip(it, p.secondaryContainer, p.onSecondaryContainer, null) })
        cRow2.addView(makeChip("+", padH = 7, marginEndDp = 6) {
            comboNumVal = (comboNumVal + 1).coerceIn(1, 15)
            val m = if (curComboMode in setOf("min_combo", "exact_combo", "exact_first_combo")) curComboMode else "exact_first_combo"
            applyCombo(m, comboNumVal)
        }.also { styleChip(it, p.chipOffBg, p.onSurface, p.chipOffStroke) })
        cRow2.addView(makeToggle("奇C", curComboMode == "odd_combo", p) {
            applyCombo(if (curComboMode == "odd_combo") "none" else "odd_combo", comboNumVal)
        })
        cRow2.addView(makeToggle("偶C", curComboMode == "even_combo", p) {
            applyCombo(if (curComboMode == "even_combo") "none" else "even_combo", comboNumVal)
        })
        cRow2.addView(makeToggle("只首批", "first_wave_only" in activeIds, p, marginEndDp = 0) {
            onToggleWaveChip(WaveShieldChip("first_wave_only", "只计首批", ShieldCategory.COMBO))
        })
        contentHost.addView(cRow2)

        // 3. Element & Count Matrix
        addSectionLabel("属性约束矩阵（点击：不限/必/强/反首/禁/全清）", p)
        val names = listOf("水", "火", "木", "光", "暗", "心")
        val elemRow = hRow(bottomMarginDp = 4)
        for (e in 0 until 6) {
            val m = when {
                "require_elem_$e" in activeIds -> 1
                "require_ench_$e" in activeIds -> 2
                "anti_first_wave_$e" in activeIds -> 3
                "forbid_all_$e" in activeIds -> 4
                "clear_all_element_$e" in activeIds -> 5
                else -> 0
            }
            val tag = when (m) {
                1 -> "必"
                2 -> "强"
                3 -> "反"
                4 -> "禁"
                5 -> "全"
                else -> "·"
            }
            val btn = makeChip("${names[e]}$tag", padH = 6, marginEndDp = if (e == 5) 0 else 3) {
                val next = (m + 1) % 6
                val exclusive = setOf(
                    "require_elem_$e", "require_ench_$e", "anti_first_wave_$e",
                    "forbid_all_$e", "clear_all_element_$e"
                )
                val mask = 1 shl e
                val chip = when (next) {
                    1 -> WaveShieldChip("require_elem_$e", "必消${names[e]}", ShieldCategory.COUNT_AND_ELEMENT, paramElementMask = mask)
                    2 -> WaveShieldChip("require_ench_$e", "强化${names[e]}", ShieldCategory.COUNT_AND_ELEMENT, paramElementMask = mask)
                    3 -> WaveShieldChip("anti_first_wave_$e", "反首消${names[e]}", ShieldCategory.COUNT_AND_ELEMENT, paramElementMask = mask)
                    4 -> WaveShieldChip("forbid_all_$e", "禁消${names[e]}", ShieldCategory.COUNT_AND_ELEMENT, paramElementMask = mask)
                    5 -> WaveShieldChip("clear_all_element_$e", "全清${names[e]}", ShieldCategory.COUNT_AND_ELEMENT, paramElementMask = mask)
                    else -> null
                }
                ActiveStrategyStore.replaceGroup(exclusive, chip)
            }
            if (m != 0) {
                btn.setTextColor(Color.WHITE)
                btn.background = rounded(PathOverlayView.elementColor(Element.MATCHABLE[e]), dp(8).toFloat(), Color.WHITE)
            } else {
                styleChip(btn, p.chipOffBg, p.chipOffText, p.chipOffStroke)
            }
            elemRow.addView(btn)
        }
        contentHost.addView(elemRow)

        val quickElemRow = hRow(bottomMarginDp = 5)
        listOf(
            WaveShieldChip("three_elements", "三属", ShieldCategory.COUNT_AND_ELEMENT),
            WaveShieldChip("five_elements", "五属", ShieldCategory.COUNT_AND_ELEMENT),
            WaveShieldChip("five_elements_enchanted", "五属强", ShieldCategory.COUNT_AND_ELEMENT),
            WaveShieldChip("first_wave_full_clear", "首消全版", ShieldCategory.COUNT_AND_ELEMENT),
            WaveShieldChip("race_orb_shield", "种族盾", ShieldCategory.COUNT_AND_ELEMENT)
        ).forEachIndexed { idx, chip ->
            quickElemRow.addView(makeToggle(chip.label, chip.id in activeIds, p, marginEndDp = if (idx == 4) 0 else 3) {
                onToggleWaveChip(chip)
            })
        }
        contentHost.addView(quickElemRow)

        // Digital Orb Count + Shape / Puzzle
        val numPuzRow = hRow(bottomMarginDp = 4)
        numPuzRow.addView(makeChip("−", padH = 5) {
            digitalOrbsVal = (digitalOrbsVal - 1).coerceIn(6, 30)
            onUpsertWaveChip(WaveShieldChip("digital_orb_count", "数字${digitalOrbsVal}颗", ShieldCategory.COUNT_AND_ELEMENT, paramInt = digitalOrbsVal))
        }.also { styleChip(it, p.chipOffBg, p.onSurface, p.chipOffStroke) })
        numPuzRow.addView(makeToggle("数字${digitalOrbsVal}颗", "digital_orb_count" in activeIds, p) {
            onToggleWaveChip(WaveShieldChip("digital_orb_count", "数字${digitalOrbsVal}颗", ShieldCategory.COUNT_AND_ELEMENT, paramInt = digitalOrbsVal))
        })
        numPuzRow.addView(makeChip("+", padH = 5, marginEndDp = 6) {
            digitalOrbsVal = (digitalOrbsVal + 1).coerceIn(6, 30)
            onUpsertWaveChip(WaveShieldChip("digital_orb_count", "数字${digitalOrbsVal}颗", ShieldCategory.COUNT_AND_ELEMENT, paramInt = digitalOrbsVal))
        }.also { styleChip(it, p.chipOffBg, p.onSurface, p.chipOffStroke) })

        val curPuz = when {
            "strict_puzzle" in activeIds -> 2
            "enchanted_puzzle" in activeIds -> 3
            "loose_puzzle" in activeIds -> 1
            else -> 0
        }
        val puzLbl = when (curPuz) {
            1 -> "普通拼图"
            2 -> "严格拼图"
            3 -> "强化拼图"
            else -> "拼图:关"
        }
        numPuzRow.addView(makeToggle(puzLbl, curPuz != 0, p) {
            val next = (curPuz + 1) % 4
            val chip = when (next) {
                1 -> WaveShieldChip("loose_puzzle", "普通拼图盾", ShieldCategory.SHAPE_AND_PUZZLE)
                2 -> WaveShieldChip("strict_puzzle", "严格拼图盾", ShieldCategory.SHAPE_AND_PUZZLE)
                3 -> WaveShieldChip("enchanted_puzzle", "强化拼图盾", ShieldCategory.SHAPE_AND_PUZZLE)
                else -> null
            }
            ActiveStrategyStore.replaceGroup(WaveShieldCatalog.PUZZLE_EXCLUSIVE_IDS, chip)
        })

        val curCross = byId["cross_shield"]?.paramInt ?: 0
        numPuzRow.addView(makeToggle(if (curCross == 0) "十字:关" else "${curCross}十字", curCross > 0, p, marginEndDp = 0) {
            val next = (curCross + 1) % 4
            val chip = if (next == 0) null else WaveShieldChip("cross_shield", "${next}十字盾", ShieldCategory.SHAPE_AND_PUZZLE, paramInt = next)
            ActiveStrategyStore.replaceGroup(WaveShieldCatalog.CROSS_EXCLUSIVE_IDS, chip)
        })
        contentHost.addView(numPuzRow)

        // Shape + Burning Path + Step Limit
        addSectionLabel("几何形状 · 燃烧路径 · 步数与地形", p)
        val shapeRow = hRow(bottomMarginDp = 4)
        listOf(
            WaveShieldChip("l_shape", "L形", ShieldCategory.SHAPE_AND_PUZZLE),
            WaveShieldChip("t_shape", "T形", ShieldCategory.SHAPE_AND_PUZZLE),
            WaveShieldChip("square_2x2", "田字", ShieldCategory.SHAPE_AND_PUZZLE),
            WaveShieldChip("trail_corrosion", "轨迹腐蚀", ShieldCategory.HAZARD_AND_PATH, paramInt = 15),
            WaveShieldChip("step_hurt_minimize", "省步", ShieldCategory.HAZARD_AND_PATH)
        ).forEachIndexed { idx, chip ->
            shapeRow.addView(makeToggle(chip.label, chip.id in activeIds, p, marginEndDp = if (idx == 4) 0 else 3) {
                onToggleWaveChip(chip)
            })
        }
        contentHost.addView(shapeRow)

        val burnOn = "zero_trail_burning" in activeIds
        val burnRow = hRow(bottomMarginDp = 2)
        fun upsertBurn() {
            val packed = (maxBurnHurtsVal shl 8) or (burnTrailLenVal and 0xFF)
            val lenLbl = if (burnTrailLenVal == 0) "Max" else "6格"
            onUpsertWaveChip(WaveShieldChip("zero_trail_burning", "燃烧(${maxBurnHurtsVal}伤/$lenLbl)", ShieldCategory.HAZARD_AND_PATH, paramInt = packed))
        }
        burnRow.addView(makeToggle("燃烧路径", burnOn, p) {
            if (burnOn) ActiveStrategyStore.removeChip("zero_trail_burning") else upsertBurn()
        })
        burnRow.addView(makeChip("伤:${maxBurnHurtsVal}", padH = 5) {
            maxBurnHurtsVal = (maxBurnHurtsVal + 1) % 6
            upsertBurn()
        }.also { styleChip(it, p.secondaryContainer, p.onSecondaryContainer, null) })
        burnRow.addView(makeChip(if (burnTrailLenVal == 0) "长:Max" else "长:6格", padH = 5) {
            burnTrailLenVal = if (burnTrailLenVal == 0) 6 else 0
            upsertBurn()
        }.also { styleChip(it, p.secondaryContainer, p.onSecondaryContainer, null) })
        burnRow.addView(makeToggle("限${stepLimitVal}步", "step_limit" in activeIds, p, marginEndDp = 0) {
            onToggleWaveChip(WaveShieldChip("step_limit", "限 ${stepLimitVal} 步内", ShieldCategory.HAZARD_AND_PATH, paramInt = stepLimitVal))
        })
        contentHost.addView(burnRow)
    }

    // ==================== Tab 1: Global Goal & Per-Element 4-Tuples ====================
    private fun renderElementsTab(p: OverlayPalette) {
        val cfg = currentActivePreset.baseConfig
        addSectionLabel("全局求解目标（即时写入当前策略）", p)
        val goalRow = hRow(bottomMarginDp = 5)
        listOf(
            StrategyGoalMode.HIGH_COMBO,
            StrategyGoalMode.THREE_COLORS,
            StrategyGoalMode.FOUR_COLORS,
            StrategyGoalMode.FIVE_COLORS,
            StrategyGoalMode.FIVE_ORBS_MULTI
        ).forEachIndexed { idx, mode ->
            goalRow.addView(makeToggle(mode.shortLabel, cfg.goalMode == mode, p, marginEndDp = if (idx == 4) 0 else 3) {
                onUpdateActivePresetConfig { it.copy(goalMode = mode) }
            })
        }
        contentHost.addView(goalRow)

        val shapeOrder = listOf(
            TargetShape.ANY, TargetShape.COUNT_2, TargetShape.COUNT_4, TargetShape.COUNT_5,
            TargetShape.ROW, TargetShape.COUNT_6, TargetShape.COUNT_8, TargetShape.CLEAR_ALL
        )
        fun shapeLbl(s: TargetShape): String = when (s) {
            TargetShape.COUNT_2 -> "2珠"
            TargetShape.COUNT_4 -> "4珠"
            TargetShape.COUNT_5 -> "5珠"
            TargetShape.ROW -> "横排"
            TargetShape.COUNT_6 -> "6珠"
            TargetShape.COUNT_8 -> "8珠"
            TargetShape.CLEAR_ALL -> "全消"
            else -> "一般"
        }

        addSectionLabel("单属性四元组（形态 · 优先 · 时机 · 队长最低消）", p)
        val names = listOf("水", "火", "木", "光", "暗", "心")
        for (e in 0 until 6) {
            val rule = cfg.elementRules.getOrElse(e) { ElementRule() }
            val row = hRow(bottomMarginDp = 4)

            val elTag = makeChip(names[e], padH = 7) {}
            elTag.setTextColor(Color.WHITE)
            elTag.background = rounded(PathOverlayView.elementColor(Element.MATCHABLE[e]), dp(8).toFloat(), null)
            row.addView(elTag)

            val shapeBtn = makeChip(shapeLbl(rule.targetShape), padH = 5) {
                val curIdx = shapeOrder.indexOf(rule.targetShape).takeIf { it >= 0 } ?: 0
                val next = shapeOrder[(curIdx + 1) % shapeOrder.size]
                onUpdateActivePresetConfig { c ->
                    val list = c.elementRules.toMutableList()
                    list[e] = list[e].copy(targetShape = next)
                    c.copy(elementRules = list)
                }
            }
            styleToggle(shapeBtn, rule.targetShape != TargetShape.ANY, p.primaryContainer, p.onPrimaryContainer, p)
            row.addView(shapeBtn)

            val prioLabel = when (rule.priority) {
                ElementPriority.HIGH -> "●优先"
                ElementPriority.RESERVE -> "●保留"
                ElementPriority.NORMAL -> "○一般"
            }
            val prioBtn = makeChip(prioLabel, padH = 5) {
                val nextPrio = when (rule.priority) {
                    ElementPriority.NORMAL -> ElementPriority.HIGH
                    ElementPriority.HIGH -> ElementPriority.RESERVE
                    ElementPriority.RESERVE -> ElementPriority.NORMAL
                }
                onUpdateActivePresetConfig { c ->
                    val list = c.elementRules.toMutableList()
                    list[e] = list[e].copy(priority = nextPrio)
                    c.copy(elementRules = list)
                }
            }
            styleToggle(prioBtn, rule.priority != ElementPriority.NORMAL, p.primary, p.onPrimary, p)
            row.addView(prioBtn)

            val timingLabel = when (rule.timing) {
                MatchTiming.ANY -> "○无"
                MatchTiming.MUST_FIRST_WAVE -> "●首消"
                MatchTiming.FORBID_FIRST_WAVE_ALLOW_CASCADE -> "●叠珠"
                MatchTiming.FORBID_ALL -> "●禁消"
            }
            val timingBtn = makeChip(timingLabel, padH = 5) {
                val nextTiming = when (rule.timing) {
                    MatchTiming.ANY -> MatchTiming.MUST_FIRST_WAVE
                    MatchTiming.MUST_FIRST_WAVE -> MatchTiming.FORBID_FIRST_WAVE_ALLOW_CASCADE
                    MatchTiming.FORBID_FIRST_WAVE_ALLOW_CASCADE -> MatchTiming.FORBID_ALL
                    MatchTiming.FORBID_ALL -> MatchTiming.ANY
                }
                onUpdateActivePresetConfig { c ->
                    val list = c.elementRules.toMutableList()
                    list[e] = list[e].copy(timing = nextTiming)
                    c.copy(elementRules = list)
                }
            }
            styleToggle(timingBtn, rule.timing != MatchTiming.ANY, p.tertiary, p.onTertiary, p)
            row.addView(timingBtn)

            val minBtn = makeChip("${rule.minMatchCount}消", padH = 5, marginEndDp = 0) {
                val nextMin = when (rule.minMatchCount) {
                    3 -> 2
                    2 -> 4
                    else -> 3
                }
                onUpdateActivePresetConfig { c ->
                    val list = c.elementRules.toMutableList()
                    list[e] = list[e].copy(minMatchCount = nextMin)
                    c.copy(elementRules = list)
                }
            }
            styleToggle(minBtn, rule.minMatchCount != 3, p.secondaryContainer, p.onSecondaryContainer, p)
            row.addView(minBtn)

            contentHost.addView(row)
        }
    }

    // ==================== Tab 2: Tower of Saviors Special Mechanics ====================
    private fun renderTosSpecialsTab(p: OverlayPalette) {
        val cfg = currentActivePreset.baseConfig

        addSectionLabel("直排昇华（点击各列循环：关 → 3珠 → 4珠 → 5珠）", p)
        val colRow = hRow(bottomMarginDp = 6)
        for (c in 0 until 6) {
            val rule = cfg.columnRules.getOrElse(c) { ColumnAmeliorationRule() }
            val lbl = if (!rule.enabled) "${c + 1}关" else "${c + 1}:${rule.minOrbs}珠"
            val btn = makeChip(lbl, padH = 5, marginEndDp = if (c == 5) 0 else 3) {
                onUpdateActivePresetConfig { ruleCfg ->
                    val cols = ruleCfg.columnRules.toMutableList()
                    val old = cols[c]
                    cols[c] = when {
                        !old.enabled -> old.copy(enabled = true, minOrbs = 3)
                        old.minOrbs == 3 -> old.copy(enabled = true, minOrbs = 4)
                        old.minOrbs == 4 -> old.copy(enabled = true, minOrbs = 5)
                        else -> old.copy(enabled = false, minOrbs = 4)
                    }
                    ruleCfg.copy(columnRules = cols)
                }
            }
            styleToggle(btn, rule.enabled, p.primary, p.onPrimary, p)
            colRow.addView(btn)
        }
        contentHost.addView(colRow)

        addSectionLabel("常用起手位置 · 角色符石下拉起手", p)
        val startRow = hRow(bottomMarginDp = 4)
        listOf(
            StartPointPreset.ANY to "任意起手",
            StartPointPreset.BOTTOM_LEFT to "固定左下",
            StartPointPreset.BOTTOM_RIGHT to "固定右下"
        ).forEachIndexed { idx, (sp, lbl) ->
            startRow.addView(makeToggle(lbl, cfg.startPreset == sp, p, marginEndDp = if (idx == 2) 0 else 4) {
                onUpdateActivePresetConfig { it.copy(startPreset = sp) }
            })
        }
        contentHost.addView(startRow)

        val charRow = hRow(bottomMarginDp = 5)
        val offBtn = makeChip(if (!cfg.useCharacterOrb) "●关" else "○关", padH = 6) {
            onUpdateActivePresetConfig { it.copy(useCharacterOrb = false) }
        }
        styleToggle(offBtn, !cfg.useCharacterOrb, p.secondaryContainer, p.onSecondaryContainer, p)
        charRow.addView(offBtn)
        for (c in 0 until 6) {
            val sel = cfg.useCharacterOrb && cfg.characterOrbCol == c
            val btn = makeChip(if (sel) "●${c + 1}号" else "${c + 1}号", padH = 5, marginEndDp = if (c == 5) 0 else 2) {
                onUpdateActivePresetConfig { it.copy(useCharacterOrb = true, characterOrbCol = c) }
            }
            styleToggle(btn, sel, p.tertiary, p.onTertiary, p)
            charRow.addView(btn)
        }
        contentHost.addView(charRow)

        addSectionLabel("策略细节开关", p)
        val advRow1 = hRow(bottomMarginDp = 4)
        advRow1.addView(makeToggle("强制切3珠", cfg.forceSplitThree, p) {
            onUpdateActivePresetConfig { it.copy(forceSplitThree = !it.forceSplitThree) }
        })
        advRow1.addView(makeToggle("最快达标", cfg.earlyStopOnGoal, p) {
            onUpdateActivePresetConfig { it.copy(earlyStopOnGoal = !it.earlyStopOnGoal) }
        })
        advRow1.addView(makeToggle("不足保留", cfg.notEnoughPolicy == NotEnoughPolicy.KEEP, p, marginEndDp = 0) {
            onUpdateActivePresetConfig {
                it.copy(
                    notEnoughPolicy = if (it.notEnoughPolicy == NotEnoughPolicy.KEEP) NotEnoughPolicy.MATCH_ANYWAY else NotEnoughPolicy.KEEP
                )
            }
        })
        contentHost.addView(advRow1)

        val advRow2 = hRow(bottomMarginDp = 4)
        advRow2.addView(makeToggle("斜消8向", cfg.allowDiagonalMatch, p) {
            onUpdateActivePresetConfig { it.copy(allowDiagonalMatch = !it.allowDiagonalMatch) }
        })
        advRow2.addView(makeToggle("强化优先", cfg.preferEnchanted, p) {
            onUpdateActivePresetConfig { it.copy(preferEnchanted = !it.preferEnchanted) }
        })
        advRow2.addView(makeToggle("允许撞风化", cfg.allowHitWeatheredToClear, p, marginEndDp = 0) {
            onUpdateActivePresetConfig { it.copy(allowHitWeatheredToClear = !it.allowHitWeatheredToClear) }
        })
        contentHost.addView(advRow2)
    }

    // ==================== Tab 3: Strategy Profile Switcher, Steps & Theme ====================
    private fun renderStrategiesTab(p: OverlayPalette) {
        addSectionLabel("切换作战策略（Room 策略库）", p)
        val presetsToShow = currentPresets.ifEmpty {
            TeamPresetRepository.defaultBuiltInPresets().map { TeamPresetRecord(it, true, 0L) }
        }
        for (rec in presetsToShow) {
            val active = rec.preset.id == currentActivePreset.id
            val btn = TextView(context).apply {
                text = (if (active) "● " else "○ ") + rec.preset.name + " · ${rec.preset.baseConfig.goalMode.shortLabel}"
                textSize = 9.5f
                typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setSingleLine(true)
                setPadding(dp(8), dp(5), dp(8), dp(5))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(4) }
                setOnClickListener { onSelectPreset(rec.preset.id) }
            }
            styleToggle(btn, active, p.primaryContainer, p.onPrimaryContainer, p, onStroke = p.primary)
            contentHost.addView(btn)
        }

        addSectionLabel("最大步数快捷档位（当前 ${currentSettings.maxSteps} 步）", p)
        val stepRow = hRow(bottomMarginDp = 6)
        val steps = listOf(30, 60, 100, 150, 200)
        steps.forEachIndexed { i, st ->
            val sel = currentSettings.maxSteps == st
            val btn = makeChip(if (sel) "●$st" else "$st", padH = 8, marginEndDp = if (i == steps.lastIndex) 0 else 4) {
                onUpdateSettings { it.copy(maxSteps = st) }
            }
            styleToggle(btn, sel, p.primary, p.onPrimary, p)
            stepRow.addView(btn)
        }
        contentHost.addView(stepRow)

        addSectionLabel("核心开关与主题风格", p)
        val swRow = hRow(bottomMarginDp = 5)
        swRow.addView(makeToggle("试跑确认", currentSettings.testModeOnlyDraw, p) {
            onUpdateSettings { it.copy(testModeOnlyDraw = !it.testModeOnlyDraw) }
        })
        swRow.addView(makeToggle("8向斜转", currentSettings.allowDiagonalMove, p) {
            onUpdateSettings { it.copy(allowDiagonalMove = !it.allowDiagonalMove) }
        })
        swRow.addView(makeToggle("珠色角标", currentSettings.showOrbBadgesOnPath, p, marginEndDp = 0) {
            onUpdateSettings { it.copy(showOrbBadgesOnPath = !it.showOrbBadgesOnPath) }
        })
        contentHost.addView(swRow)

        val themeRow = hRow(bottomMarginDp = 2)
        ThemeMode.entries.forEachIndexed { idx, mode ->
            val sel = currentSettings.themeMode == mode
            val btn = makeChip(if (sel) "●${mode.label}" else "○${mode.label}", padH = 9, marginEndDp = if (idx == 2) 0 else 4) {
                onUpdateSettings { it.copy(themeMode = mode) }
            }
            styleToggle(btn, sel, p.primary, p.onPrimary, p)
            themeRow.addView(btn)
        }
        contentHost.addView(themeRow)
    }

    // ==================== Helpers ====================
    private fun addSectionLabel(text: String, p: OverlayPalette) {
        contentHost.addView(TextView(context).apply {
            this.text = text
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(p.primary)
            setPadding(dp(2), dp(2), dp(2), dp(3))
        })
    }

    private fun hRow(bottomMarginDp: Int = 4): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(bottomMarginDp) }
    }

    private fun makeChip(label: String, padH: Int = 6, heightDp: Int = 24, marginEndDp: Int = 3, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            setSingleLine(true)
            gravity = Gravity.CENTER
            setPadding(dp(padH), dp(3), dp(padH), dp(3))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(heightDp)
            ).apply { marginEnd = dp(marginEndDp) }
            setOnClickListener { onClick() }
        }
    }

    private fun makeToggle(label: String, isOn: Boolean, p: OverlayPalette, marginEndDp: Int = 4, onClick: () -> Unit): TextView {
        return makeChip(if (isOn) "●$label" else "○$label", padH = 6, marginEndDp = marginEndDp, onClick = onClick).also {
            styleToggle(it, isOn, p.primary, p.onPrimary, p)
        }
    }

    private fun styleChip(v: TextView, bg: Int, fg: Int, stroke: Int?) {
        v.setTextColor(fg)
        v.background = rounded(bg, dp(8).toFloat(), stroke)
    }

    private fun styleToggle(v: TextView, isOn: Boolean, onBg: Int, onFg: Int, p: OverlayPalette, onStroke: Int? = null) {
        if (isOn) styleChip(v, onBg, onFg, onStroke)
        else styleChip(v, p.chipOffBg, p.chipOffText, p.chipOffStroke)
    }

    private fun rounded(bg: Int, radius: Float, stroke: Int?, strokePx: Int = dp(1)) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(bg)
        if (stroke != null) setStroke(strokePx, stroke)
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}
