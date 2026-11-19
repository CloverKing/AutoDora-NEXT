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
 *  - Tab 0 「盾牌」: Offline Chinese OCR Enemy Skill Scan + Categorized WaveShieldChips + Numeric Adjusters
 *  - Tab 1 「属性」: Per-Element Triplets (Water..Heart Priority / Timing / Min-Match)
 *  - Tab 2 「专属」: ToS Mechanics (Columns 1..6 Amelioration, Character Orb Pull, Diagonal Match, Weathered Hit, Split-3)
 *  - Tab 3 「队伍」: Room TeamBasePreset Switcher + Quick Steps + Theme Switcher
 */
class OverlayTuningDrawerController(
    private val context: Context,
    private val onRunOcrScan: () -> Unit,
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
    private var exactComboVal = 6
    private var digitalOrbsVal = 18

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
                dp(324),
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(3)
                marginStart = dp(24)
            }
        }

        // Top 4-Tab Segmented Header
        val tabRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val tabTitles = listOf("盾牌", "属性", "专属", "队伍")
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
                dp(248)
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
            3 -> renderTeamsTab(p)
        }
    }

    // ==================== Tab 0: Wave Shields + Offline Chinese OCR ====================
    private fun renderShieldsTab(p: OverlayPalette) {
        // OCR scan action bar
        val ocrRow = hRow(bottomMarginDp = 6)
        val ocrBtn = makeChip("📷 离线 OCR 扫描敌技弹窗", padH = 8, heightDp = 28) { onRunOcrScan() }
        styleChip(ocrBtn, p.primary, p.onPrimary, null)
        ocrRow.addView(ocrBtn)

        val clearBtn = makeChip("清空本波 (${currentChips.size})", padH = 7, heightDp = 28, marginEndDp = 0) {
            onClearWaveChips()
        }
        styleChip(clearBtn, p.errorContainer, p.onErrorContainer, null)
        ocrRow.addView(clearBtn)
        contentHost.addView(ocrRow)

        currentOcrSummary?.let { summary ->
            contentHost.addView(TextView(context).apply {
                text = summary
                textSize = 9.5f
                setTextColor(p.primary)
                setPadding(dp(2), 0, dp(2), dp(5))
            })
        }

        // Quick numeric shield adjusters (Exact Combo & Digital Orb Count)
        val activeIds = currentChips.map { it.id }.toSet()
        val numRow = hRow(bottomMarginDp = 6)
        numRow.addView(makeChip("−", padH = 6) {
            exactComboVal = (exactComboVal - 1).coerceIn(1, 15)
            onUpsertWaveChip(WaveShieldChip("exact_first_combo", "首消 ${exactComboVal}C", ShieldCategory.COMBO, paramInt = exactComboVal))
        }.also { styleChip(it, p.chipOffBg, p.onSurface, p.chipOffStroke) })

        val exactOn = "exact_first_combo" in activeIds
        val exactChip = makeChip(if (exactOn) "●首消${exactComboVal}C" else "○首消${exactComboVal}C", padH = 6) {
            onToggleWaveChip(WaveShieldChip("exact_first_combo", "首消 ${exactComboVal}C", ShieldCategory.COMBO, paramInt = exactComboVal))
        }
        styleToggle(exactChip, exactOn, p.primary, p.onPrimary, p)
        numRow.addView(exactChip)

        numRow.addView(makeChip("+", padH = 6, marginEndDp = 8) {
            exactComboVal = (exactComboVal + 1).coerceIn(1, 15)
            onUpsertWaveChip(WaveShieldChip("exact_first_combo", "首消 ${exactComboVal}C", ShieldCategory.COMBO, paramInt = exactComboVal))
        }.also { styleChip(it, p.chipOffBg, p.onSurface, p.chipOffStroke) })

        numRow.addView(makeChip("−", padH = 6) {
            digitalOrbsVal = (digitalOrbsVal - 1).coerceIn(6, 30)
            onUpsertWaveChip(WaveShieldChip("digital_orb_count", "数字盾 ${digitalOrbsVal} 颗", ShieldCategory.COUNT_AND_ELEMENT, paramInt = digitalOrbsVal))
        }.also { styleChip(it, p.chipOffBg, p.onSurface, p.chipOffStroke) })

        val digOn = "digital_orb_count" in activeIds
        val digChip = makeChip(if (digOn) "●数字${digitalOrbsVal}颗" else "○数字${digitalOrbsVal}颗", padH = 6) {
            onToggleWaveChip(WaveShieldChip("digital_orb_count", "数字盾 ${digitalOrbsVal} 颗", ShieldCategory.COUNT_AND_ELEMENT, paramInt = digitalOrbsVal))
        }
        styleToggle(digChip, digOn, p.primary, p.onPrimary, p)
        numRow.addView(digChip)

        numRow.addView(makeChip("+", padH = 6, marginEndDp = 0) {
            digitalOrbsVal = (digitalOrbsVal + 1).coerceIn(6, 30)
            onUpsertWaveChip(WaveShieldChip("digital_orb_count", "数字盾 ${digitalOrbsVal} 颗", ShieldCategory.COUNT_AND_ELEMENT, paramInt = digitalOrbsVal))
        }.also { styleChip(it, p.chipOffBg, p.onSurface, p.chipOffStroke) })
        contentHost.addView(numRow)

        val grouped = listOf(
            "连击盾" to ShieldCategory.COMBO,
            "属性与数量盾" to ShieldCategory.COUNT_AND_ELEMENT,
            "形状与拼图盾" to ShieldCategory.SHAPE_AND_PUZZLE,
            "路径与特殊障碍" to ShieldCategory.HAZARD_AND_PATH
        )
        val catalog = WaveShieldCatalog.allChips()
        for ((title, cat) in grouped) {
            addSectionLabel(title, p)
            val items = catalog.filter { it.category == cat && it.id != "exact_first_combo" && it.id != "digital_orb_count" }
            addWrappedChipGrid(items, activeIds, p)
        }
    }

    private fun addWrappedChipGrid(items: List<WaveShieldChip>, activeIds: Set<String>, p: OverlayPalette) {
        var row = hRow(bottomMarginDp = 4)
        var countInRow = 0
        for (chip in items) {
            val isOn = chip.id in activeIds
            val btn = makeChip(if (isOn) "●${chip.label}" else "○${chip.label}", padH = 6) {
                onToggleWaveChip(chip)
            }
            styleToggle(btn, isOn, p.primary, p.onPrimary, p)
            row.addView(btn)
            countInRow++
            if (countInRow >= 3) {
                contentHost.addView(row)
                row = hRow(bottomMarginDp = 4)
                countInRow = 0
            }
        }
        if (countInRow > 0) contentHost.addView(row)
    }

    // ==================== Tab 1: Per-Element Triplets ====================
    private fun renderElementsTab(p: OverlayPalette) {
        addSectionLabel("单属性规则（点击循环切换，即时生效于当前队伍）", p)
        val cfg = currentActivePreset.baseConfig
        val names = listOf("水", "火", "木", "光", "暗", "心")

        for (e in 0 until 6) {
            val rule = cfg.elementRules.getOrElse(e) { ElementRule() }
            val row = hRow(bottomMarginDp = 5)

            // Element badge
            val elTag = makeChip(names[e], padH = 8) {}
            elTag.setTextColor(Color.WHITE)
            elTag.background = rounded(PathOverlayView.elementColor(Element.MATCHABLE[e]), dp(8).toFloat(), null)
            row.addView(elTag)

            // Priority toggle
            val prioLabel = when (rule.priority) {
                ElementPriority.HIGH -> "●优先消"
                ElementPriority.RESERVE -> "●保留"
                ElementPriority.NORMAL -> "○一般"
            }
            val prioBtn = makeChip(prioLabel, padH = 6) {
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

            // Timing toggle
            val timingLabel = when (rule.timing) {
                MatchTiming.ANY -> "○常规"
                MatchTiming.MUST_FIRST_WAVE -> "●必首消"
                MatchTiming.FORBID_FIRST_WAVE_ALLOW_CASCADE -> "●反首消"
                MatchTiming.FORBID_ALL -> "●全禁消"
            }
            val timingBtn = makeChip(timingLabel, padH = 6) {
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

            // Min match count (3 / 2 / 4)
            val minBtn = makeChip("${rule.minMatchCount} 消", padH = 6, marginEndDp = 0) {
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

        addSectionLabel("直排昇华（≥ 4 消加权）", p)
        val colRow = hRow(bottomMarginDp = 6)
        for (c in 0 until 6) {
            val enabled = cfg.columnRules.getOrNull(c)?.enabled == true
            val btn = makeChip(if (enabled) "●列${c + 1}" else "○列${c + 1}", padH = 6, marginEndDp = if (c == 5) 0 else 3) {
                onUpdateActivePresetConfig { ruleCfg ->
                    val cols = ruleCfg.columnRules.toMutableList()
                    val old = cols[c]
                    cols[c] = old.copy(enabled = !old.enabled, minOrbs = 4)
                    ruleCfg.copy(columnRules = cols)
                }
            }
            styleToggle(btn, enabled, p.primary, p.onPrimary, p)
            colRow.addView(btn)
        }
        contentHost.addView(colRow)

        addSectionLabel("角色符石下拉起手（不计首步）", p)
        val charRow = hRow(bottomMarginDp = 4)
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

        if (cfg.useCharacterOrb) {
            val elemRow = hRow(bottomMarginDp = 6)
            for (el in Element.MATCHABLE) {
                val sel = cfg.characterOrbElement == el
                val btn = makeChip(if (sel) "●${PathOverlayView.elementShortZh(el)}" else PathOverlayView.elementShortZh(el), padH = 7) {
                    onUpdateActivePresetConfig { it.copy(characterOrbElement = el) }
                }
                btn.setTextColor(Color.WHITE)
                btn.background = rounded(
                    PathOverlayView.elementColor(el),
                    dp(8).toFloat(),
                    if (sel) Color.WHITE else null,
                    strokePx = dp(2)
                )
                elemRow.addView(btn)
            }
            contentHost.addView(elemRow)
        }

        addSectionLabel("进阶规则开关", p)
        val advRow1 = hRow(bottomMarginDp = 4)
        advRow1.addView(makeToggle("斜消(8向相连)", cfg.allowDiagonalMatch, p) {
            onUpdateActivePresetConfig { it.copy(allowDiagonalMatch = !it.allowDiagonalMatch) }
        })
        advRow1.addView(makeToggle("允许撞风化", cfg.allowHitWeatheredToClear, p) {
            onUpdateActivePresetConfig { it.copy(allowHitWeatheredToClear = !it.allowHitWeatheredToClear) }
        })
        advRow1.addView(makeToggle("强制三珠切珠", cfg.forceSplitThree, p, marginEndDp = 0) {
            onUpdateActivePresetConfig { it.copy(forceSplitThree = !it.forceSplitThree) }
        })
        contentHost.addView(advRow1)

        val advRow2 = hRow(bottomMarginDp = 4)
        advRow2.addView(makeToggle("强化珠优先", cfg.preferEnchanted, p) {
            onUpdateActivePresetConfig { it.copy(preferEnchanted = !it.preferEnchanted) }
        })
        advRow2.addView(makeToggle("种族珠优先", cfg.preferRace, p) {
            onUpdateActivePresetConfig { it.copy(preferRace = !it.preferRace) }
        })
        advRow2.addView(makeToggle("角色地形必达", cfg.requireCharOrbPass || cfg.requireCharOrbEnd, p, marginEndDp = 0) {
            onUpdateActivePresetConfig {
                val next = !(it.requireCharOrbPass || it.requireCharOrbEnd)
                it.copy(requireCharOrbPass = next, requireCharOrbEnd = next)
            }
        })
        contentHost.addView(advRow2)
    }

    // ==================== Tab 3: Team Presets, Steps & Theme ====================
    private fun renderTeamsTab(p: OverlayPalette) {
        addSectionLabel("切换队伍基底预设（Room 预设库）", p)
        val presetsToShow = currentPresets.ifEmpty {
            TeamPresetRepository.defaultBuiltInPresets().map { TeamPresetRecord(it, true, 0L) }
        }
        for (rec in presetsToShow) {
            val active = rec.preset.id == currentActivePreset.id
            val btn = TextView(context).apply {
                text = (if (active) "● " else "○ ") + rec.preset.name + if (rec.preset.description.isNotBlank()) " — ${rec.preset.description}" else ""
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
        swRow.addView(makeToggle("自动违建", currentSettings.calibration.autoDetectWeijian, p, marginEndDp = 0) {
            onUpdateSettings { it.copy(calibration = it.calibration.copy(autoDetectWeijian = !it.calibration.autoDetectWeijian)) }
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
