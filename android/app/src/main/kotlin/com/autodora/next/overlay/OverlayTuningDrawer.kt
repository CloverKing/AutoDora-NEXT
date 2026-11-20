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
import com.autodora.next.data.*
import com.autodora.next.model.*
import com.autodora.next.ui.LocaleHelper
import com.autodora.next.ui.OverlayPalette

/**
 * In-Game 4-Tab System-Themed & Live-Localized Floating Tuning Drawer:
 *  - Tab 0 (Shield): Auto Skill Engine + Independent Per-Type Combo + Arbitrary Multi-Color Element/Enchanted + Mode/Enchanted/Shape Puzzle + Path/Step
 *  - Tab 1 (Attr): Global Goal Mode + Aligned Per-Element 4-Tuple Matrix (with Column Headers!)
 *  - Tab 2 (ToS): Columns 1..6 Amelioration (2×3 Grid), Start Point, Character Orb Pull, Strategy Switches
 *  - Tab 3 (Plan): Strategy Profile Switcher + Quick Steps + Core Switches + Theme & Live Language Switcher
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

    private var lctx: Context = context
    private var selectedTab = 0

    // Independent per-type Combo parameters (never collide!)
    private var minTotalComboVal = 6
    private var minFirstComboVal = 5
    private var exactTotalComboVal = 6
    private var exactFirstComboVal = 4

    // Element restriction sub-mode: 0 = Anti-1st, 1 = Forbid All, 2 = Clear All 1st
    private var elemLimitMode = 0
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
                dp(344),
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(3)
                marginStart = dp(16)
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
        val tabTitleRes = listOf(
            R.string.ov_tab_shield,
            R.string.ov_tab_elem,
            R.string.ov_tab_special,
            R.string.ov_tab_strategy
        )
        tabTitleRes.forEachIndexed { idx, resId ->
            val btn = TextView(context).apply {
                text = lctx.getString(resId)
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
            text = lctx.getString(R.string.ov_collapse)
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
                dp(288)
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
        lctx = LocaleHelper.localizedContext(context, settings.language)
        currentPalette = palette
        currentSettings = settings
        currentPresets = presets
        currentActivePreset = activePreset
        currentChips = activeChips
        currentOcrSummary = ocrSummary

        // Sync numeric steppers when OCR or Dashboard updates a chip
        val byId = activeChips.associateBy { it.id }
        byId["min_combo"]?.paramInt?.let { if (it in 1..15) minTotalComboVal = it }
        byId["min_first_combo"]?.paramInt?.let { if (it in 1..12) minFirstComboVal = it }
        byId["exact_combo"]?.paramInt?.let { if (it in 1..15) exactTotalComboVal = it }
        byId["exact_first_combo"]?.paramInt?.let { if (it in 1..12) exactFirstComboVal = it }
        byId["digital_orb_count"]?.paramInt?.let { if (it in 6..30) digitalOrbsVal = it }
        byId["step_limit"]?.paramInt?.let { if (it in 2..60) stepLimitVal = it }

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

    private fun s(resId: Int): String = lctx.getString(resId)
    private fun s(resId: Int, vararg args: Any): String = lctx.getString(resId, *args)

    private fun applyContainerChrome(p: OverlayPalette) {
        rootView.background = rounded(p.containerBg, dp(14).toFloat(), p.containerStroke)
        closeBtn.text = s(R.string.ov_collapse)
        styleChip(closeBtn, p.chipOffBg, p.onSurfaceVariant, p.chipOffStroke)
        val tabRes = listOf(
            R.string.ov_tab_shield,
            R.string.ov_tab_elem,
            R.string.ov_tab_special,
            R.string.ov_tab_strategy
        )
        tabBtns.forEachIndexed { idx, btn -> btn.text = s(tabRes[idx]) }
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

    // ==================== Tab 0: Abstract Parameterized Shield Controls ====================
    private fun renderShieldsTab(p: OverlayPalette) {
        val byId = currentChips.associateBy { it.id }
        val activeIds = byId.keys

        // 1. Auto Enemy Skill Bar
        val autoRow = hRow(bottomMarginDp = 5)
        autoRow.addView(makeToggle(s(R.string.ov_auto_skill), currentSettings.autoEnemySkillEnabled, p) {
            onUpdateSettings { it.copy(autoEnemySkillEnabled = !it.autoEnemySkillEnabled) }
        })
        val modeBtn = makeChip(LocaleHelper.scanModeLabel(lctx, currentSettings.enemySkillScanMode), padH = 5) {
            val entries = EnemySkillScanMode.entries
            val next = entries[(currentSettings.enemySkillScanMode.ordinal + 1) % entries.size]
            onUpdateSettings { it.copy(enemySkillScanMode = next) }
        }
        styleChip(modeBtn, p.secondaryContainer, p.onSecondaryContainer, null)
        autoRow.addView(modeBtn)

        val scanNow = makeChip(s(R.string.ov_scan_trigger), padH = 6) { onRunAutoSkillScan() }
        styleChip(scanNow, p.primary, p.onPrimary, null)
        autoRow.addView(scanNow)

        val clearBtn = makeChip(s(R.string.ov_clear_fmt, currentChips.size), padH = 5, marginEndDp = 0) { onClearWaveChips() }
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

        // 2. Combo Shield Controller (Independent Per-Type!)
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
                    listOf("same_combo_elements", "second_wave_combo", "same_elem_combos", "equal_pair_light_dark", "first_wave_full_clear", "digital_orb_count")
                        .forEach { ActiveStrategyStore.removeChip(it) }
                    WaveShieldChip("zero_combo", s(R.string.combo_type_zero), ShieldCategory.COMBO)
                }
                "odd_combo" -> WaveShieldChip("odd_combo", s(R.string.combo_type_odd), ShieldCategory.COMBO, paramInt = 0)
                "even_combo" -> WaveShieldChip("even_combo", s(R.string.combo_type_even), ShieldCategory.COMBO, paramInt = 0)
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
        ).forEachIndexed { idx, (id, lbl) ->
            val btn = makeChip(if (curComboMode == id) "●$lbl" else lbl, padH = 5, marginEndDp = if (idx == 3) 0 else 3) {
                applyCombo(id)
            }
            styleToggle(btn, curComboMode == id, p.primary, p.onPrimary, p)
            cRow1.addView(btn)
        }
        contentHost.addView(cRow1)

        val cRow2 = hRow(bottomMarginDp = 4)
        listOf(
            "exact_first_combo" to s(R.string.combo_type_exact_first),
            "zero_combo" to s(R.string.combo_type_zero),
            "odd_combo" to s(R.string.combo_type_odd),
            "even_combo" to s(R.string.combo_type_even)
        ).forEachIndexed { idx, (id, lbl) ->
            val btn = makeChip(if (curComboMode == id) "●$lbl" else lbl, padH = 5, marginEndDp = if (idx == 3) 0 else 3) {
                applyCombo(id)
            }
            styleToggle(btn, curComboMode == id, p.primary, p.onPrimary, p)
            cRow2.addView(btn)
        }
        contentHost.addView(cRow2)

        // Dedicated stepper for ONLY the selected numeric Combo type + Structural modifier (hidden on Zero Combo)
        if (curComboMode != "zero_combo") {
            val cRow3 = hRow(bottomMarginDp = 5)
            if (curComboMode in setOf("min_combo", "min_first_combo", "exact_combo", "exact_first_combo")) {
                val activeVal = when (curComboMode) {
                    "min_combo" -> minTotalComboVal
                    "min_first_combo" -> minFirstComboVal
                    "exact_combo" -> exactTotalComboVal
                    else -> exactFirstComboVal
                }
                fun bump(delta: Int) {
                    when (curComboMode) {
                        "min_combo" -> minTotalComboVal = (minTotalComboVal + delta).coerceIn(1, 15)
                        "min_first_combo" -> minFirstComboVal = (minFirstComboVal + delta).coerceIn(1, 12)
                        "exact_combo" -> exactTotalComboVal = (exactTotalComboVal + delta).coerceIn(1, 15)
                        "exact_first_combo" -> exactFirstComboVal = (exactFirstComboVal + delta).coerceIn(1, 12)
                    }
                    applyCombo(curComboMode)
                }
                cRow3.addView(makeChip("−", padH = 7) { bump(-1) }.also { styleChip(it, p.chipOffBg, p.onSurface, p.chipOffStroke) })
                cRow3.addView(makeChip(s(R.string.combo_value_exact_fmt, activeVal), padH = 7) {}.also { styleChip(it, p.secondaryContainer, p.onSecondaryContainer, null) })
                cRow3.addView(makeChip("+", padH = 7, marginEndDp = 6) { bump(+1) }.also { styleChip(it, p.chipOffBg, p.onSurface, p.chipOffStroke) })
            }
            cRow3.addView(makeToggle(s(R.string.combo_extra_same_c), "same_combo_elements" in activeIds, p, marginEndDp = 0) {
                onToggleWaveChip(WaveShieldChip("same_combo_elements", s(R.string.combo_extra_same_c), ShieldCategory.COMBO))
            })
            contentHost.addView(cRow3)
        }

        // 3. Element & Count Shield (Cross-validated Min Distinct + Arbitrary Required Colors + Enchanted Toggle)
        addSectionLabel(s(R.string.section_elem_count_shield), p)
        val curMinDistinct = byId["min_distinct_elements"]?.paramInt ?: 0
        val legacyReqIds = buildSet {
            add("require_elements")
            add("three_elements")
            add("five_elements")
            add("five_elements_enchanted")
            for (e in 0..5) {
                add("require_elem_$e")
                add("require_ench_$e")
            }
        }
        val minDistRow = hRow(bottomMarginDp = 4)
        for (cnt in listOf(0, 2, 3, 4, 5, 6)) {
            val lbl = if (cnt == 0) s(R.string.elem_min_distinct_any) else "≥${cnt}"
            val btn = makeChip(if (curMinDistinct == cnt) "●$lbl" else lbl, padH = 5, marginEndDp = if (cnt == 6) 0 else 3) {
                if (curMinDistinct != cnt) ActiveStrategyStore.replaceGroup(legacyReqIds, null)
                if (cnt == 0) ActiveStrategyStore.removeChip("min_distinct_elements")
                else onUpsertWaveChip(
                    WaveShieldChip("min_distinct_elements", s(R.string.elem_min_distinct_fmt, cnt), ShieldCategory.COUNT_AND_ELEMENT, paramInt = cnt)
                )
            }
            styleToggle(btn, curMinDistinct == cnt, p.primary, p.onPrimary, p)
            minDistRow.addView(btn)
        }
        contentHost.addView(minDistRow)

        // Required Attributes Multi-Select (ANY colors) + Independent Enchanted Switch
        var effectiveReqMask = byId["require_elements"]?.paramElementMask ?: 0
        if ("three_elements" in activeIds) effectiveReqMask = effectiveReqMask or 0x07
        if ("five_elements" in activeIds || "five_elements_enchanted" in activeIds) effectiveReqMask = effectiveReqMask or 0x1F
        for (e in 0..5) {
            if ("require_elem_$e" in activeIds || "require_ench_$e" in activeIds) effectiveReqMask = effectiveReqMask or (1 shl e)
        }
        val reqEnch = (byId["require_elements"]?.paramInt ?: 0) == 1 ||
            "five_elements_enchanted" in activeIds ||
            (0..5).any { "require_ench_$it" in activeIds }

        fun saveReqElements(mask: Int, ench: Boolean) {
            val legacyIds = buildSet {
                add("require_elements")
                add("three_elements")
                add("five_elements")
                add("five_elements_enchanted")
                for (e in 0..5) {
                    add("require_elem_$e")
                    add("require_ench_$e")
                }
            }
            if (mask == 0) {
                ActiveStrategyStore.replaceGroup(legacyIds, null)
                return
            }
            val names = (0..5).filter { (mask and (1 shl it)) != 0 }
                .joinToString("") { LocaleHelper.elementLabel(lctx, it) }
            ActiveStrategyStore.replaceGroup(
                legacyIds,
                WaveShieldChip(
                    id = "require_elements",
                    label = if (ench) "$names+" else names,
                    category = ShieldCategory.COUNT_AND_ELEMENT,
                    paramInt = if (ench) 1 else 0,
                    paramElementMask = mask
                )
            )
        }

        val reqRow = hRow(bottomMarginDp = 4)
        for (e in 0 until 6) {
            val isOn = (effectiveReqMask and (1 shl e)) != 0
            val lbl = LocaleHelper.elementLabel(lctx, e) + (if (isOn && reqEnch) "+" else "")
            val btn = makeChip(lbl, padH = 6, marginEndDp = 3) {
                saveReqElements(effectiveReqMask xor (1 shl e), reqEnch)
            }
            if (isOn) {
                btn.setTextColor(Color.WHITE)
                btn.background = rounded(LocaleHelper.elementBadgeArgb(e), dp(8).toFloat(), Color.WHITE)
            } else {
                styleChip(btn, p.chipOffBg, p.chipOffText, p.chipOffStroke)
            }
            reqRow.addView(btn)
        }
        reqRow.addView(makeToggle(s(R.string.ov_req_ench_short), reqEnch && effectiveReqMask != 0, p, marginEndDp = 0) {
            if (effectiveReqMask != 0) saveReqElements(effectiveReqMask, !reqEnch)
        })
        contentHost.addView(reqRow)

        // Attribute Restrictions (Cycle Anti-1st / Forbid All / Clear All + 6 Color Toggles)
        val limitBaseId = when (elemLimitMode) {
            0 -> "anti_first_wave"
            1 -> "forbid_all"
            else -> "clear_all_element"
        }
        val limitLabel = when (elemLimitMode) {
            0 -> s(R.string.elem_limit_anti)
            1 -> s(R.string.elem_limit_forbid)
            else -> s(R.string.elem_limit_clear_all)
        }
        var curLimitMask = byId[limitBaseId]?.paramElementMask ?: 0
        for (e in 0..5) {
            if ("${limitBaseId}_$e" in activeIds) curLimitMask = curLimitMask or (1 shl e)
        }
        val limitRow = hRow(bottomMarginDp = 4)
        val limModeBtn = makeChip(limitLabel, padH = 5) {
            elemLimitMode = (elemLimitMode + 1) % 3
            renderAll()
        }
        styleChip(limModeBtn, p.secondaryContainer, p.onSecondaryContainer, null)
        limitRow.addView(limModeBtn)
        for (e in 0 until 6) {
            val isOn = (curLimitMask and (1 shl e)) != 0
            val btn = makeChip(LocaleHelper.elementLabel(lctx, e), padH = 5, marginEndDp = if (e == 5) 0 else 2) {
                val nextMask = curLimitMask xor (1 shl e)
                val legacySet = buildSet {
                    add(limitBaseId)
                    for (i in 0..5) add("${limitBaseId}_$i")
                }
                if (nextMask == 0) {
                    ActiveStrategyStore.replaceGroup(legacySet, null)
                } else {
                    val names = (0..5).filter { (nextMask and (1 shl it)) != 0 }
                        .joinToString("") { LocaleHelper.elementLabel(lctx, it) }
                    ActiveStrategyStore.replaceGroup(
                        legacySet,
                        WaveShieldChip(limitBaseId, "$limitLabel:$names", ShieldCategory.COUNT_AND_ELEMENT, paramElementMask = nextMask)
                    )
                }
            }
            if (isOn) {
                btn.setTextColor(Color.WHITE)
                btn.background = rounded(LocaleHelper.elementBadgeArgb(e), dp(8).toFloat(), Color.WHITE)
            } else {
                styleChip(btn, p.chipOffBg, p.chipOffText, p.chipOffStroke)
            }
            limitRow.addView(btn)
        }
        contentHost.addView(limitRow)

        // Orb Count & Race Shields
        val countRow = hRow(bottomMarginDp = 5)
        countRow.addView(makeChip("−", padH = 5) {
            digitalOrbsVal = (digitalOrbsVal - 1).coerceIn(6, 30)
            onUpsertWaveChip(WaveShieldChip("digital_orb_count", s(R.string.shield_digital_orbs_fmt, digitalOrbsVal), ShieldCategory.COUNT_AND_ELEMENT, paramInt = digitalOrbsVal))
        }.also { styleChip(it, p.chipOffBg, p.onSurface, p.chipOffStroke) })
        countRow.addView(makeToggle(s(R.string.shield_digital_orbs_fmt, digitalOrbsVal), "digital_orb_count" in activeIds, p) {
            onToggleWaveChip(WaveShieldChip("digital_orb_count", s(R.string.shield_digital_orbs_fmt, digitalOrbsVal), ShieldCategory.COUNT_AND_ELEMENT, paramInt = digitalOrbsVal))
        })
        countRow.addView(makeChip("+", padH = 5, marginEndDp = 5) {
            digitalOrbsVal = (digitalOrbsVal + 1).coerceIn(6, 30)
            onUpsertWaveChip(WaveShieldChip("digital_orb_count", s(R.string.shield_digital_orbs_fmt, digitalOrbsVal), ShieldCategory.COUNT_AND_ELEMENT, paramInt = digitalOrbsVal))
        }.also { styleChip(it, p.chipOffBg, p.onSurface, p.chipOffStroke) })
        countRow.addView(makeToggle(s(R.string.shield_full_clear), "first_wave_full_clear" in activeIds, p) {
            onToggleWaveChip(WaveShieldChip("first_wave_full_clear", s(R.string.shield_full_clear), ShieldCategory.COUNT_AND_ELEMENT))
        })
        countRow.addView(makeToggle(s(R.string.shield_any_enchanted_orb), "any_enchanted_orb_shield" in activeIds, p, marginEndDp = 0) {
            onToggleWaveChip(WaveShieldChip("any_enchanted_orb_shield", s(R.string.shield_any_enchanted_orb), ShieldCategory.COUNT_AND_ELEMENT))
        })
        contentHost.addView(countRow)

        val specialOrbRow = hRow(bottomMarginDp = 5)
        specialOrbRow.addView(makeToggle(s(R.string.shield_race_orb), "race_orb_shield" in activeIds, p) {
            onToggleWaveChip(WaveShieldChip("race_orb_shield", s(R.string.shield_race_orb), ShieldCategory.COUNT_AND_ELEMENT))
        })
        specialOrbRow.addView(makeToggle(s(R.string.shield_char_orb_first), "char_orb_first_wave" in activeIds, p) {
            onToggleWaveChip(WaveShieldChip("char_orb_first_wave", s(R.string.shield_char_orb_first), ShieldCategory.COUNT_AND_ELEMENT))
        })
        specialOrbRow.addView(makeToggle(s(R.string.shield_forbid_weakened), "forbid_weakened_orb" in activeIds, p) {
            onToggleWaveChip(WaveShieldChip("forbid_weakened_orb", s(R.string.shield_forbid_weakened), ShieldCategory.COUNT_AND_ELEMENT))
        })
        specialOrbRow.addView(makeToggle(s(R.string.shield_luminous_orb), "luminous_orb_shield" in activeIds, p, marginEndDp = 0) {
            onToggleWaveChip(WaveShieldChip("luminous_orb_shield", s(R.string.shield_luminous_orb), ShieldCategory.COUNT_AND_ELEMENT))
        })
        contentHost.addView(specialOrbRow)

        // 4. Puzzle Shield (Mode + Independent Enchanted + Shape!) & Standalone Shapes
        addSectionLabel(s(R.string.section_puzzle_shape_shield), p)
        val pChip = byId["puzzle_shield"]
        val puzzleMode = when {
            pChip != null -> if ((pChip.paramInt and 1) != 0) 2 else 1
            "strict_puzzle" in activeIds || "enchanted_puzzle" in activeIds -> 2
            "loose_puzzle" in activeIds -> 1
            else -> 0
        }
        val puzzleEnch = when {
            pChip != null -> (pChip.paramInt and 2) != 0
            "enchanted_puzzle" in activeIds -> true
            else -> false
        }
        val puzzleElemEnc = if (pChip != null) (pChip.paramInt ushr 2) and 0x0F else 0
        val puzzleElem = if (puzzleElemEnc in 1..6) puzzleElemEnc - 1 else -1
        val puzzleShapeId = if (pChip != null) (pChip.paramInt ushr 8) and 0x0F else 0

        fun savePuzzle(mode: Int, ench: Boolean, elem: Int, shapeId: Int) {
            if (mode == 0) {
                ActiveStrategyStore.replaceGroup(WaveShieldCatalog.PUZZLE_EXCLUSIVE_IDS, null)
                return
            }
            val packed = (if (mode == 2) 1 else 0) or
                (if (ench) 2 else 0) or
                (if (elem in 0..5) ((elem + 1) shl 2) else 0) or
                ((shapeId and 0x0F) shl 8)
            val parts = buildList {
                add(s(if (mode == 2) R.string.ov_puzzle_strict else R.string.ov_puzzle_normal))
                if (ench) add(s(R.string.ov_puzzle_ench))
                if (elem in 0..5) add(LocaleHelper.elementLabel(lctx, elem))
                if (shapeId > 0) add(LocaleHelper.puzzleShapeLabel(lctx, shapeId))
            }
            ActiveStrategyStore.replaceGroup(
                WaveShieldCatalog.PUZZLE_EXCLUSIVE_IDS,
                WaveShieldChip("puzzle_shield", parts.joinToString("·"), ShieldCategory.SHAPE_AND_PUZZLE, paramInt = packed)
            )
        }

        val puzRow = hRow(bottomMarginDp = 4)
        val puzModeLbl = when (puzzleMode) {
            1 -> s(R.string.ov_puzzle_normal)
            2 -> s(R.string.ov_puzzle_strict)
            else -> s(R.string.ov_puzzle_off)
        }
        puzRow.addView(makeToggle(puzModeLbl, puzzleMode > 0, p) {
            savePuzzle((puzzleMode + 1) % 3, puzzleEnch, puzzleElem, puzzleShapeId)
        })
        puzRow.addView(makeToggle(s(R.string.ov_puzzle_ench), puzzleMode > 0 && puzzleEnch, p) {
            val nextMode = if (puzzleMode == 0) 1 else puzzleMode
            savePuzzle(nextMode, !puzzleEnch, puzzleElem, puzzleShapeId)
        })
        val shapeBtn = makeChip(LocaleHelper.puzzleShapeLabel(lctx, puzzleShapeId), padH = 5, marginEndDp = 0) {
            val nextMode = if (puzzleMode == 0) 1 else puzzleMode
            savePuzzle(nextMode, puzzleEnch, puzzleElem, (puzzleShapeId + 1) % 7)
        }
        styleToggle(shapeBtn, puzzleMode > 0 && puzzleShapeId > 0, p.secondaryContainer, p.onSecondaryContainer, p)
        puzRow.addView(shapeBtn)
        contentHost.addView(puzRow)

        val shapeRow = hRow(bottomMarginDp = 4)
        val curCross = (byId["cross_shield"]?.paramInt ?: 0) and 0x0F
        shapeRow.addView(makeToggle(if (curCross == 0) s(R.string.ov_cross_off) else s(R.string.ov_cross_fmt, curCross), curCross > 0, p) {
            val next = (curCross + 1) % 4
            val chip = if (next == 0) null else WaveShieldChip("cross_shield", s(R.string.ov_cross_fmt, next), ShieldCategory.SHAPE_AND_PUZZLE, paramInt = next)
            ActiveStrategyStore.replaceGroup(WaveShieldCatalog.CROSS_EXCLUSIVE_IDS, chip)
        })
        listOf(
            WaveShieldChip("l_shape", s(R.string.shield_l_shape), ShieldCategory.SHAPE_AND_PUZZLE),
            WaveShieldChip("t_shape", s(R.string.shield_t_shape), ShieldCategory.SHAPE_AND_PUZZLE),
            WaveShieldChip("square_2x2", s(R.string.shield_sq_2x2), ShieldCategory.SHAPE_AND_PUZZLE)
        ).forEachIndexed { idx, chip ->
            shapeRow.addView(makeToggle(chip.label, chip.id in activeIds, p, marginEndDp = if (idx == 2) 0 else 3) {
                onToggleWaveChip(chip)
            })
        }
        contentHost.addView(shapeRow)

        // 5. Path, Burning Trail & Step Limit
        addSectionLabel(s(R.string.section_path_hazard_shield), p)
        val burnOn = "zero_trail_burning" in activeIds
        val burnRow = hRow(bottomMarginDp = 2)
        fun upsertBurn() {
            val packed = (maxBurnHurtsVal shl 8) or (burnTrailLenVal and 0xFF)
            val lenLbl = if (burnTrailLenVal == 0) s(R.string.ov_burn_len_max) else s(R.string.ov_burn_len_6)
            onUpsertWaveChip(WaveShieldChip("zero_trail_burning", "${s(R.string.ov_burn_path)}($maxBurnHurtsVal/$lenLbl)", ShieldCategory.HAZARD_AND_PATH, paramInt = packed))
        }
        burnRow.addView(makeToggle(s(R.string.ov_burn_path), burnOn, p) {
            if (burnOn) ActiveStrategyStore.removeChip("zero_trail_burning") else upsertBurn()
        })
        burnRow.addView(makeChip(s(R.string.ov_burn_hurts_fmt, maxBurnHurtsVal), padH = 5) {
            maxBurnHurtsVal = (maxBurnHurtsVal + 1) % 6
            upsertBurn()
        }.also { styleChip(it, p.secondaryContainer, p.onSecondaryContainer, null) })
        burnRow.addView(makeChip(if (burnTrailLenVal == 0) s(R.string.ov_burn_len_max) else s(R.string.ov_burn_len_6), padH = 5) {
            burnTrailLenVal = if (burnTrailLenVal == 0) 6 else 0
            upsertBurn()
        }.also { styleChip(it, p.secondaryContainer, p.onSecondaryContainer, null) })
        burnRow.addView(makeToggle(s(R.string.ov_step_limit_short, stepLimitVal), "step_limit" in activeIds, p, marginEndDp = 0) {
            onToggleWaveChip(WaveShieldChip("step_limit", s(R.string.shield_step_limit_fmt, stepLimitVal), ShieldCategory.HAZARD_AND_PATH, paramInt = stepLimitVal))
        })
        contentHost.addView(burnRow)
    }

    // ==================== Tab 1: Global Goal & Aligned Per-Element Matrix ====================
    private fun renderElementsTab(p: OverlayPalette) {
        val cfg = currentActivePreset.baseConfig
        addSectionLabel(s(R.string.section_goal_mode), p)
        val goalRow = hRow(bottomMarginDp = 5)
        listOf(
            StrategyGoalMode.HIGH_COMBO,
            StrategyGoalMode.THREE_COLORS,
            StrategyGoalMode.FOUR_COLORS,
            StrategyGoalMode.FIVE_COLORS,
            StrategyGoalMode.FIVE_ORBS_MULTI
        ).forEachIndexed { idx, mode ->
            goalRow.addView(makeToggle(LocaleHelper.goalModeLabel(lctx, mode), cfg.goalMode == mode, p, marginEndDp = if (idx == 4) 0 else 3) {
                onUpdateActivePresetConfig { it.copy(goalMode = mode) }
            })
        }
        contentHost.addView(goalRow)

        val shapeOrder = listOf(
            TargetShape.ANY, TargetShape.COUNT_2, TargetShape.COUNT_4, TargetShape.COUNT_5,
            TargetShape.ROW, TargetShape.COUNT_6, TargetShape.COUNT_8, TargetShape.CLEAR_ALL
        )

        addSectionLabel(s(R.string.section_elem_matrix), p)

        // Explicit Aligned Table Header Row
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(p.surfaceVariantBg, dp(6).toFloat(), null)
            setPadding(dp(4), dp(3), dp(4), dp(3))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
        }
        fun addHdr(text: String, fixedW: Int? = null, weight: Float = 1f) {
            headerRow.addView(TextView(context).apply {
                this.text = text
                textSize = 9f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(p.onSurfaceVariant)
                gravity = Gravity.CENTER
                setSingleLine(true)
                layoutParams = if (fixedW != null) {
                    LinearLayout.LayoutParams(dp(fixedW), LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(3) }
                } else {
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight).apply { marginEnd = dp(3) }
                }
            })
        }
        addHdr(s(R.string.col_hdr_elem), fixedW = 30)
        addHdr(s(R.string.col_hdr_shape), weight = 1.05f)
        addHdr(s(R.string.col_hdr_priority), weight = 1f)
        addHdr(s(R.string.col_hdr_timing), weight = 1f)
        addHdr(s(R.string.col_hdr_min), weight = 0.85f)
        contentHost.addView(headerRow)

        for (e in 0 until 6) {
            val rule = cfg.elementRules.getOrElse(e) { ElementRule() }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(4) }
            }

            val elTag = TextView(context).apply {
                text = LocaleHelper.elementLabel(lctx, e)
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = rounded(LocaleHelper.elementBadgeArgb(e), dp(7).toFloat(), null)
                layoutParams = LinearLayout.LayoutParams(dp(30), dp(25)).apply { marginEnd = dp(3) }
            }
            row.addView(elTag)

            fun addCell(label: String, weight: Float, isLast: Boolean = false, onClick: () -> Unit): TextView {
                return TextView(context).apply {
                    text = label
                    textSize = 9.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setSingleLine(true)
                    ellipsize = TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                    setPadding(dp(3), dp(2), dp(3), dp(2))
                    layoutParams = LinearLayout.LayoutParams(0, dp(25), weight).apply {
                        marginEnd = if (isLast) 0 else dp(3)
                    }
                    setOnClickListener { onClick() }
                }
            }

            val shapeBtn = addCell(LocaleHelper.targetShapeLabel(lctx, rule.targetShape), 1.05f) {
                val curIdx = shapeOrder.indexOf(rule.targetShape).takeIf { it >= 0 } ?: 0
                val next = shapeOrder[(curIdx + 1) % shapeOrder.size]
                onUpdateActivePresetConfig { c ->
                    val list = c.elementRules.toMutableList()
                    list[e] = list[e].copy(targetShape = next)
                    c.copy(elementRules = list)
                }
            }
            styleToggle(shapeBtn, rule.targetShape != TargetShape.ANY, p.primaryContainer, p.onPrimaryContainer, p, onStroke = p.primary)
            row.addView(shapeBtn)

            val prioBtn = addCell(LocaleHelper.elementPriorityLabel(lctx, rule.priority), 1f) {
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

            val timingBtn = addCell(LocaleHelper.matchTimingLabel(lctx, rule.timing), 1f) {
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
            val tBg = if (rule.timing == MatchTiming.FORBID_ALL) p.errorContainer else p.tertiary
            val tFg = if (rule.timing == MatchTiming.FORBID_ALL) p.onErrorContainer else p.onTertiary
            styleToggle(timingBtn, rule.timing != MatchTiming.ANY, tBg, tFg, p)
            row.addView(timingBtn)

            val minBtn = addCell(s(R.string.min_match_fmt, rule.minMatchCount), 0.85f, isLast = true) {
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

        // Columns 1..6 Amelioration in clean 2×3 Grid (zero wrapping!)
        addSectionLabel(s(R.string.section_columns), p)
        for (rowIdx in 0..1) {
            val colRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(4) }
            }
            for (offset in 0..2) {
                val c = rowIdx * 3 + offset
                val rule = cfg.columnRules.getOrElse(c) { ColumnAmeliorationRule() }
                val lbl = if (!rule.enabled) s(R.string.col_off_fmt, c + 1) else s(R.string.col_on_fmt, c + 1, rule.minOrbs)
                val btn = TextView(context).apply {
                    text = lbl
                    textSize = 9.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setSingleLine(true)
                    gravity = Gravity.CENTER
                    setPadding(dp(4), dp(3), dp(4), dp(3))
                    layoutParams = LinearLayout.LayoutParams(0, dp(26), 1f).apply {
                        marginEnd = if (offset == 2) 0 else dp(4)
                    }
                    setOnClickListener {
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
                }
                styleToggle(btn, rule.enabled, p.primary, p.onPrimary, p)
                colRow.addView(btn)
            }
            contentHost.addView(colRow)
        }

        addSectionLabel(s(R.string.section_start_char), p)
        val startRow = hRow(bottomMarginDp = 4)
        listOf(
            StartPointPreset.ANY to s(R.string.start_any),
            StartPointPreset.BOTTOM_LEFT to s(R.string.start_bl),
            StartPointPreset.BOTTOM_RIGHT to s(R.string.start_br)
        ).forEachIndexed { idx, (sp, lbl) ->
            startRow.addView(makeToggle(lbl, cfg.startPreset == sp, p, marginEndDp = if (idx == 2) 0 else 4) {
                onUpdateActivePresetConfig { it.copy(startPreset = sp) }
            })
        }
        contentHost.addView(startRow)

        val charRow1 = hRow(bottomMarginDp = 3)
        charRow1.addView(makeToggle(s(R.string.char_off), !cfg.useCharacterOrb, p) {
            onUpdateActivePresetConfig { it.copy(useCharacterOrb = false) }
        })
        for (c in 0..2) {
            val sel = cfg.useCharacterOrb && cfg.characterOrbCol == c
            charRow1.addView(makeToggle(s(R.string.char_slot_fmt, c + 1), sel, p, marginEndDp = if (c == 2) 0 else 3) {
                onUpdateActivePresetConfig { it.copy(useCharacterOrb = true, characterOrbCol = c) }
            })
        }
        contentHost.addView(charRow1)

        val charRow2 = hRow(bottomMarginDp = 5)
        for (c in 3..5) {
            val sel = cfg.useCharacterOrb && cfg.characterOrbCol == c
            charRow2.addView(makeToggle(s(R.string.char_slot_fmt, c + 1), sel, p, marginEndDp = if (c == 5) 0 else 3) {
                onUpdateActivePresetConfig { it.copy(useCharacterOrb = true, characterOrbCol = c) }
            })
        }
        contentHost.addView(charRow2)

        addSectionLabel(s(R.string.ov_strategy_toggles_hdr), p)
        val advRow1 = hRow(bottomMarginDp = 4)
        advRow1.addView(makeToggle(s(R.string.tag_split_three), cfg.forceSplitThree, p) {
            onUpdateActivePresetConfig { it.copy(forceSplitThree = !it.forceSplitThree) }
        })
        advRow1.addView(makeToggle(s(R.string.tag_first_result), cfg.earlyStopOnGoal, p) {
            onUpdateActivePresetConfig { it.copy(earlyStopOnGoal = !it.earlyStopOnGoal) }
        })
        advRow1.addView(makeToggle(s(R.string.policy_not_enough_keep), cfg.notEnoughPolicy == NotEnoughPolicy.KEEP, p, marginEndDp = 0) {
            onUpdateActivePresetConfig {
                it.copy(
                    notEnoughPolicy = if (it.notEnoughPolicy == NotEnoughPolicy.KEEP) NotEnoughPolicy.MATCH_ANYWAY else NotEnoughPolicy.KEEP
                )
            }
        })
        contentHost.addView(advRow1)

        val advRow2 = hRow(bottomMarginDp = 4)
        advRow2.addView(makeToggle(s(R.string.tag_diag_match), cfg.allowDiagonalMatch, p) {
            onUpdateActivePresetConfig { it.copy(allowDiagonalMatch = !it.allowDiagonalMatch) }
        })
        advRow2.addView(makeToggle(s(R.string.switch_prefer_ench).take(6), cfg.preferEnchanted, p) {
            onUpdateActivePresetConfig { it.copy(preferEnchanted = !it.preferEnchanted) }
        })
        advRow2.addView(makeToggle(s(R.string.switch_hit_weathered).take(6), cfg.allowHitWeatheredToClear, p, marginEndDp = 0) {
            onUpdateActivePresetConfig { it.copy(allowHitWeatheredToClear = !it.allowHitWeatheredToClear) }
        })
        contentHost.addView(advRow2)
    }

    // ==================== Tab 3: Strategy Profile Switcher, Steps, Theme & Language ====================
    private fun renderStrategiesTab(p: OverlayPalette) {
        addSectionLabel(s(R.string.ov_switch_strategy_hdr), p)
        val presetsToShow = currentPresets.ifEmpty {
            TeamPresetRepository.defaultBuiltInPresets().map { TeamPresetRecord(it, true, 0L) }
        }
        for (rec in presetsToShow) {
            val active = rec.preset.id == currentActivePreset.id
            val displayName = LocaleHelper.displayPresetName(lctx, rec.preset, rec.isBuiltIn)
            val goalLbl = LocaleHelper.goalModeLabel(lctx, rec.preset.baseConfig.goalMode)
            val btn = TextView(context).apply {
                text = (if (active) "● " else "○ ") + "$displayName · $goalLbl"
                textSize = 9.5f
                typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
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

        addSectionLabel(s(R.string.ov_steps_hdr_fmt, currentSettings.maxSteps), p)
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

        addSectionLabel(s(R.string.ov_core_theme_hdr), p)
        val swRow = hRow(bottomMarginDp = 4)
        swRow.addView(makeToggle(s(R.string.ov_preview_confirm), currentSettings.testModeOnlyDraw, p) {
            onUpdateSettings { it.copy(testModeOnlyDraw = !it.testModeOnlyDraw) }
        })
        swRow.addView(makeToggle(s(R.string.ov_diag_move), currentSettings.allowDiagonalMove, p) {
            onUpdateSettings { it.copy(allowDiagonalMove = !it.allowDiagonalMove) }
        })
        swRow.addView(makeToggle(s(R.string.ov_orb_badges), currentSettings.showOrbBadgesOnPath, p, marginEndDp = 0) {
            onUpdateSettings { it.copy(showOrbBadgesOnPath = !it.showOrbBadgesOnPath) }
        })
        contentHost.addView(swRow)

        val themeRow = hRow(bottomMarginDp = 4)
        ThemeMode.entries.forEachIndexed { idx, mode ->
            val sel = currentSettings.themeMode == mode
            val lbl = LocaleHelper.themeLabel(lctx, mode)
            val btn = makeChip(if (sel) "●$lbl" else "○$lbl", padH = 8, marginEndDp = if (idx == 2) 0 else 4) {
                onUpdateSettings { it.copy(themeMode = mode) }
            }
            styleToggle(btn, sel, p.primary, p.onPrimary, p)
            themeRow.addView(btn)
        }
        contentHost.addView(themeRow)

        val langRow = hRow(bottomMarginDp = 2)
        AppLanguage.entries.forEachIndexed { idx, lang ->
            val sel = currentSettings.language == lang
            val lbl = LocaleHelper.languageLabel(lctx, lang)
            val btn = makeChip(if (sel) "●$lbl" else "○$lbl", padH = 7, marginEndDp = if (idx == AppLanguage.entries.lastIndex) 0 else 4) {
                onUpdateSettings { it.copy(language = lang) }
            }
            styleToggle(btn, sel, p.secondaryContainer, p.onSecondaryContainer, p, onStroke = p.primary)
            langRow.addView(btn)
        }
        contentHost.addView(langRow)
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
