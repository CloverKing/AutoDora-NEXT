package com.autodora.next.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autodora.next.R
import com.autodora.next.data.*
import com.autodora.next.model.*

/**
 * `v0.9.0` Unified Strategy & Wave-Shield Workbench (1:1 synced with `OverlayTuningDrawer`):
 *  - Identical 6 colored drawers order: ① 目标与连击 (Indigo) → ② 属性与珠数 (Teal) → ③ 拼图与形状 (Amber)
 *    → ④ 路径与地形 (Crimson) → ⑤ 预算与起手 (Blue) → ⑥ 队长与特性 (Purple).
 *  - Zero `...` ellipsis on interactive buttons/pills/matrix cells; inline shape & condition expanders (no dialogs).
 *  - 100% 2-way bidirectional sync between `ActiveStrategyStore` AI/manual capsules and strategy controls + 6×5 matrix.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun UnifiedStrategyTab(
    settings: AppSettings,
    presets: List<TeamPresetRecord>,
    activeWaveChips: List<WaveShieldChip>,
    bypassedGreySkills: List<BypassedEnemySkill> = emptyList(),
    activeVisualHints: BoardVisualHints = BoardVisualHints(),
    onRestoreBypassedSkill: (BypassedEnemySkill) -> Unit = {},
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onSavePreset: (TeamBasePreset, Boolean) -> Unit,
    onResetStrategy: () -> Unit,
    onToggleWaveChip: (WaveShieldChip) -> Unit,
    onClearWaveChips: () -> Unit,
    onShowHelp: (String, String) -> Unit
) {
    val ctx = LocalContext.current
    val items = presets.ifEmpty {
        TeamPresetRepository.defaultBuiltInPresets().map { TeamPresetRecord(it, true, 0L) }
    }
    val activeRecord = items.firstOrNull { it.preset.id == settings.activePresetId } ?: items.first()
    val activePreset = activeRecord.preset
    val cfg = activePreset.baseConfig

    fun updateCfg(transform: (RuleConfig) -> RuleConfig) {
        onSavePreset(activePreset.copy(baseConfig = transform(cfg)), activeRecord.isBuiltIn)
    }

    // Inline expanders for the 6x5 Per-Element Matrix (NO popup dialogs!)
    var inlineShapeElemIdx by remember { mutableStateOf<Int?>(null) }
    var inlineCondElemIdx by remember { mutableStateOf<Int?>(null) }

    var stepsText by remember(activePreset.id) { mutableStateOf(cfg.maxSteps.toString()) }
    LaunchedEffect(cfg.maxSteps) {
        if (stepsText.toIntOrNull() != cfg.maxSteps) stepsText = cfg.maxSteps.toString()
    }

    val byId = remember(activeWaveChips) { activeWaveChips.associateBy { it.id } }
    val activeIds = remember(activeWaveChips) { activeWaveChips.map { it.id }.toSet() }
    val composedCfg = remember(activePreset, activeWaveChips) {
        RuleComposer.compose(
            base = activePreset,
            activeChips = activeWaveChips.map { WaveShieldCatalog.canonicalizeForComposer(it) }
        )
    }

    // Synchronized working numeric parameters for wave shields
    var minTotalComboVal by remember { mutableStateOf(byId["min_combo"]?.paramInt ?: 6) }
    var maxTotalComboVal by remember { mutableStateOf(byId["max_combo"]?.paramInt ?: 6) }
    var minFirstComboVal by remember { mutableStateOf(byId["min_first_combo"]?.paramInt ?: 5) }
    var minFirstElemVal by remember { mutableStateOf(byId["min_first_elem_combo"]?.paramInt ?: 5) }
    var exactTotalComboVal by remember { mutableStateOf(byId["exact_combo"]?.paramInt ?: 6) }
    var exactFirstComboVal by remember { mutableStateOf(byId["exact_first_combo"]?.paramInt ?: 4) }
    var wave1KindVal by remember { mutableStateOf(byId["wave1_same_kind"]?.paramInt ?: byId["wave1_distinct_kind"]?.paramInt ?: 5) }
    var parityFirstOnly by remember {
        mutableStateOf((byId["odd_combo"]?.paramInt ?: byId["even_combo"]?.paramInt ?: 0) != 1)
    }
    var secondWaveVal by remember { mutableStateOf(byId["second_wave_combo"]?.paramInt ?: 1) }
    var sameElemGroupsVal by remember { mutableStateOf(byId["same_elem_combos"]?.paramInt ?: 3) }
    var sameElemTarget by remember {
        val m = byId["same_elem_combos"]?.paramElementMask ?: 0
        mutableStateOf((0..5).firstOrNull { (m and (1 shl it)) != 0 } ?: -1)
    }
    val initialEqMask = run {
        val raw = byId["equal_pair_light_dark"]?.paramElementMask ?: 0
        when {
            (raw and 0x100) != 0 -> raw and 0x3F
            raw != 0 -> (1 shl (raw and 0x0F).coerceIn(0, 5)) or (1 shl ((raw ushr 4) and 0x0F).coerceIn(0, 5))
            else -> (1 shl 3) or (1 shl 4)
        }
    }
    var equalElemsMask by remember { mutableStateOf(initialEqMask) }
    var burstOrbsVal by remember { mutableStateOf(byId["elem_burst_shield"]?.paramInt?.coerceIn(3, 30) ?: 15) }
    var maxElemOrbsVal by remember { mutableStateOf(byId["max_element_orbs"]?.paramInt?.coerceIn(0, 30) ?: 5) }
    var elemLimitMode by remember { mutableStateOf(0) }
    var digitalOrbs by remember { mutableStateOf(byId["digital_orb_count"]?.paramInt ?: 18) }
    var maxBurnHurts by remember { mutableStateOf(((byId["zero_trail_burning"]?.paramInt ?: 0) ushr 8) and 0xFF) }
    var burnTrailLen by remember { mutableStateOf((byId["zero_trail_burning"]?.paramInt ?: 0) and 0xFF) }
    var stepLimitVal by remember { mutableStateOf(byId["step_limit"]?.paramInt ?: 15) }
    var exactStepsVal by remember { mutableStateOf(byId["exact_steps"]?.paramInt ?: 20) }
    var stepRangeMinVal by remember { mutableStateOf((byId["step_range"]?.paramInt?.and(0xFF))?.takeIf { it in 1..200 } ?: 20) }
    var stepRangeMaxVal by remember { mutableStateOf(((byId["step_range"]?.paramInt ?: 0) ushr 8 and 0xFF).takeIf { it in 1..200 } ?: 25) }

    LaunchedEffect(byId["min_combo"]?.paramInt) { byId["min_combo"]?.paramInt?.let { if (it in 1..15) minTotalComboVal = it } }
    LaunchedEffect(byId["max_combo"]?.paramInt) { byId["max_combo"]?.paramInt?.let { if (it in 1..15) maxTotalComboVal = it } }
    LaunchedEffect(byId["min_first_combo"]?.paramInt) { byId["min_first_combo"]?.paramInt?.let { if (it in 1..15) minFirstComboVal = it } }
    LaunchedEffect(byId["min_first_elem_combo"]?.paramInt) { byId["min_first_elem_combo"]?.paramInt?.let { if (it in 1..12) minFirstElemVal = it } }
    LaunchedEffect(byId["exact_combo"]?.paramInt) { byId["exact_combo"]?.paramInt?.let { if (it in 1..15) exactTotalComboVal = it } }
    LaunchedEffect(byId["exact_first_combo"]?.paramInt) { byId["exact_first_combo"]?.paramInt?.let { if (it in 1..15) exactFirstComboVal = it } }
    LaunchedEffect(byId["wave1_same_kind"]?.paramInt, byId["wave1_distinct_kind"]?.paramInt) {
        (byId["wave1_same_kind"] ?: byId["wave1_distinct_kind"])?.paramInt?.let { if (it in 1..10) wave1KindVal = it }
    }
    LaunchedEffect(byId["elem_burst_shield"]?.paramInt) { byId["elem_burst_shield"]?.paramInt?.let { if (it in 3..30) burstOrbsVal = it } }
    LaunchedEffect(byId["max_element_orbs"]?.paramInt) { byId["max_element_orbs"]?.paramInt?.let { if (it in 0..30) maxElemOrbsVal = it } }
    LaunchedEffect(byId["digital_orb_count"]?.paramInt) { byId["digital_orb_count"]?.paramInt?.let { if (it in 6..30) digitalOrbs = it } }
    LaunchedEffect(byId["step_limit"]?.paramInt) { byId["step_limit"]?.paramInt?.let { if (it in 2..60) stepLimitVal = it } }
    LaunchedEffect(byId["exact_steps"]?.paramInt) { byId["exact_steps"]?.paramInt?.let { if (it in 1..100) exactStepsVal = it } }
    LaunchedEffect(byId["step_range"]?.paramInt) {
        byId["step_range"]?.paramInt?.let { packed ->
            val lo = packed and 0xFF
            val hi = (packed ushr 8) and 0xFF
            if (lo in 1..200 && hi in lo..200) {
                stepRangeMinVal = lo
                stepRangeMaxVal = hi
            }
        }
    }

    val legacyReqIds = remember {
        buildSet {
            add("require_elements"); add("three_elements"); add("five_elements"); add("five_elements_enchanted")
            for (e in 0..5) { add("require_elem_$e"); add("require_ench_$e") }
        }
    }

    fun isAiChip(id: String): Boolean = byId[id]?.autoDetected == true

    fun readMaskForLimit(baseId: String): Int {
        var m = byId[baseId]?.paramElementMask ?: 0
        for (e in 0..5) if ("${baseId}_$e" in activeIds) m = m or (1 shl e)
        return m
    }

    fun writeMaskForLimit(baseId: String, prefix: String, newMask: Int) {
        val legacySet = buildSet { add(baseId); for (i in 0..5) add("${baseId}_$i") }
        if (newMask == 0) {
            ActiveStrategyStore.replaceGroup(legacySet, null)
        } else {
            val names = (0..5).filter { (newMask and (1 shl it)) != 0 }
                .joinToString("") { LocaleHelper.elementLabel(ctx, it) }
            ActiveStrategyStore.replaceGroup(
                legacySet,
                WaveShieldChip(baseId, "$prefix:$names", ShieldCategory.COUNT_AND_ELEMENT, paramElementMask = newMask)
            )
        }
    }

    fun writeRequiredElements(reqMask: Int, enchMask: Int) {
        val cleanReq = reqMask and 0x3F
        val cleanEnch = enchMask and cleanReq
        if (cleanReq == 0) {
            ActiveStrategyStore.replaceGroup(legacyReqIds, null)
            return
        }
        val names = (0..5).filter { (cleanReq and (1 shl it)) != 0 }.joinToString("") { idx ->
            val n = LocaleHelper.elementLabel(ctx, idx)
            if ((cleanEnch and (1 shl idx)) != 0) "$n+" else n
        }
        ActiveStrategyStore.replaceGroup(
            legacyReqIds,
            WaveShieldChip(
                id = "require_elements",
                label = names,
                category = ShieldCategory.COUNT_AND_ELEMENT,
                paramInt = if (cleanEnch == cleanReq && cleanEnch != 0) 1 else 0,
                paramElementMask = cleanReq or (cleanEnch shl 8)
            )
        )
    }

    val shapeOrder = remember {
        listOf(
            TargetShape.ANY, TargetShape.SPLIT_3, TargetShape.COUNT_2, TargetShape.COUNT_4,
            TargetShape.COUNT_5, TargetShape.COUNT_6, TargetShape.COUNT_7, TargetShape.COUNT_8,
            TargetShape.COUNT_9, TargetShape.ROW, TargetShape.COLUMN, TargetShape.CROSS,
            TargetShape.L_SHAPE, TargetShape.T_SHAPE, TargetShape.SQUARE_2X2, TargetShape.SQUARE_3X3,
            TargetShape.CLEAR_ALL
        )
    }

    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ==================== Top Compact Active Strategy Header ====================
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (isDark) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
            border = BorderStroke(
                width = 1.dp,
                color = DrawerAccentColors.Indigo.copy(alpha = if (isDark) 0.55f else 0.30f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = stringResource(R.string.unified_strategy_bar_title),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.unified_strategy_bar_sub),
                        fontSize = 10.5.sp,
                        lineHeight = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Surface(
                        onClick = {
                            inlineShapeElemIdx = null
                            inlineCondElemIdx = null
                            onResetStrategy()
                        },
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                        ) {
                            Icon(Icons.Outlined.RestartAlt, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = stringResource(R.string.btn_reset_strategy),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    HelpCircleButton(
                        onClick = {
                            onShowHelp(
                                ctx.getString(R.string.help_unified_strategy_title),
                                ctx.getString(R.string.help_unified_strategy_body)
                            )
                        }
                    )
                }
            }
        }

        // ==================== Visual Prior Pills & Bypassed Grey Skill Restore ====================
        val hasHintPills = activeVisualHints.hasMonochrome || activeVisualHints.hasDisguise ||
            activeVisualHints.hasElectrified || activeVisualHints.hasFrozenOrPetrified
        if (hasHintPills || bypassedGreySkills.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    if (hasHintPills) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (activeVisualHints.hasMonochrome) {
                                CompactDismissibleTag(
                                    label = stringResource(R.string.ov_hint_monochrome_pill),
                                    accent = DrawerAccentColors.Purple,
                                    isDark = isDark,
                                    onDismiss = { ActiveStrategyStore.dismissVisualHint("monochrome") }
                                )
                            }
                            if (activeVisualHints.hasDisguise) {
                                CompactDismissibleTag(
                                    label = stringResource(R.string.ov_hint_disguise_edit_pill),
                                    accent = DrawerAccentColors.Amber,
                                    isDark = isDark,
                                    onDismiss = { ActiveStrategyStore.dismissVisualHint("disguise") }
                                )
                            }
                            if (activeVisualHints.hasElectrified) {
                                CompactDismissibleTag(
                                    label = stringResource(R.string.ov_hint_elec_pill),
                                    accent = DrawerAccentColors.Blue,
                                    isDark = isDark,
                                    onDismiss = { ActiveStrategyStore.dismissVisualHint("electrified") }
                                )
                            }
                            if (activeVisualHints.hasFrozenOrPetrified) {
                                CompactDismissibleTag(
                                    label = stringResource(R.string.ov_hint_frozen_pill),
                                    accent = DrawerAccentColors.Teal,
                                    isDark = isDark,
                                    onDismiss = { ActiveStrategyStore.dismissVisualHint("frozen_petrified") }
                                )
                            }
                        }
                    }
                    if (bypassedGreySkills.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.ov_bypassed_restore_hdr),
                            fontSize = 10.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            for (sk in bypassedGreySkills) {
                                CompactChoicePill(
                                    label = "⊘ ${sk.titleZhTw.ifBlank { sk.chips.joinToString { it.label } }}",
                                    selected = false,
                                    onClick = { onRestoreBypassedSkill(sk) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // ==================== Active Wave Shields Banner (Dismissible + 2-Way Synced) ====================
        if (activeWaveChips.isNotEmpty()) {
            val bannerBg = if (isDark) Color(0xFF221E2E) else Color(0xFFF3EEFF)
            val bannerBorder = if (isDark) Color(0xFF8E74D8).copy(alpha = 0.72f) else DrawerAccentColors.Purple.copy(alpha = 0.42f)
            val bannerTitleColor = if (isDark) Color(0xFFEAE2FF) else Color(0xFF4A148C)
            val clearBg = if (isDark) Color(0xFF5A1D24) else Color(0xFFFFE5E8)
            val clearFg = if (isDark) Color(0xFFFFCFD4) else Color(0xFFC62828)

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = bannerBg,
                border = BorderStroke(1.25.dp, bannerBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.unified_enemy_banner_title, activeWaveChips.size),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = bannerTitleColor
                        )
                        Surface(
                            onClick = onClearWaveChips,
                            shape = RoundedCornerShape(8.dp),
                            color = clearBg
                        ) {
                            Text(
                                text = stringResource(R.string.unified_enemy_banner_clear),
                                color = clearFg,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                            )
                        }
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        for (chip in activeWaveChips) {
                            val isAuto = chip.autoDetected
                            val prefix = when {
                                chip.fromKnowledgeFallback -> stringResource(R.string.ov_kb_chip_prefix)
                                isAuto -> stringResource(R.string.ov_ai_chip_prefix)
                                else -> ""
                            }
                            val chipBg = when {
                                isAuto && isDark -> Color(0xFF1A3A44)
                                isAuto -> Color(0xFFE0F7FA)
                                isDark -> Color(0xFF332D49)
                                else -> Color(0xFFE5DBFF)
                            }
                            val chipFg = when {
                                isAuto && isDark -> Color(0xFF9CEFF8)
                                isAuto -> Color(0xFF005662)
                                isDark -> Color(0xFFF5F1FF)
                                else -> Color(0xFF311B92)
                            }
                            val chipStroke = when {
                                isAuto -> Color(0xFF00ACC1)
                                isDark -> Color(0xFFB39DDB).copy(alpha = 0.65f)
                                else -> Color(0xFF7B1FA2).copy(alpha = 0.45f)
                            }
                            Surface(
                                onClick = { ActiveStrategyStore.removeChip(chip.id) },
                                shape = RoundedCornerShape(8.dp),
                                color = chipBg,
                                border = BorderStroke(1.dp, chipStroke)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "$prefix${LocaleHelper.formatWaveChipLabel(ctx, chip)}",
                                        color = chipFg,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Icon(
                                        Icons.Outlined.Close,
                                        contentDescription = null,
                                        tint = chipFg,
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ==================== Drawer ①: Global Goals & Combo Rules (Indigo) ====================
        val comboIds = setOf(
            "min_combo", "max_combo", "min_first_combo", "min_first_elem_combo",
            "exact_combo", "exact_first_combo", "wave1_same_kind", "wave1_distinct_kind",
            "zero_combo", "odd_combo", "even_combo", "no_cascade",
            "same_combo_elements", "second_wave_combo", "same_elem_combos", "equal_pair_light_dark"
        )
        val comboActiveLabels = activeWaveChips.filter { it.id in comboIds }.map { LocaleHelper.formatWaveChipLabel(ctx, it) }
        val goalComboSummary = buildList {
            add(LocaleHelper.goalSummary(ctx, cfg))
            addAll(comboActiveLabels.take(2))
        }.joinToString(" · ")
        ColoredCollapsibleDrawerCard(
            title = stringResource(R.string.unified_group_goal_combo),
            accentColor = DrawerAccentColors.Indigo,
            summaryText = goalComboSummary,
            highlighted = comboActiveLabels.isNotEmpty() || cfg.goalMode != StrategyGoalMode.HIGH_COMBO,
            initialExpanded = true,
            onHelp = {
                onShowHelp(
                    ctx.getString(R.string.help_combo_shield_title),
                    ctx.getString(R.string.help_goal_combo_full_body)
                )
            }
        ) {
            Text(
                text = stringResource(R.string.section_goal_mode),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val goalGroup = cfg.goalMode.normalizedGroup()
            val curGoalN = if (cfg.goalMode == goalGroup) cfg.goalParamN else cfg.goalMode.defaultParamN()
            val curGoalX = if (cfg.goalMode == goalGroup) cfg.goalParamX else cfg.goalMode.defaultParamX()
            val groupedModes = listOf(
                StrategyGoalMode.HIGH_COMBO,
                StrategyGoalMode.N_COLOR_BALANCE,
                StrategyGoalMode.N_COLOR_ALL,
                StrategyGoalMode.SINGLE_COLOR_X_ORBS,
                StrategyGoalMode.MULTI_COLOR_X_ORBS
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                for (grp in groupedModes) {
                    CompactChoicePill(
                        label = LocaleHelper.goalModeLabel(ctx, grp),
                        selected = goalGroup == grp,
                        onClick = {
                            val defN = when (grp) {
                                StrategyGoalMode.N_COLOR_BALANCE -> curGoalN.coerceIn(2, 5)
                                StrategyGoalMode.N_COLOR_ALL -> curGoalN.coerceIn(2, 6)
                                StrategyGoalMode.MULTI_COLOR_X_ORBS -> curGoalN.coerceIn(2, 5)
                                else -> 3
                            }
                            val defX = curGoalX.coerceIn(4, 9)
                            updateCfg {
                                it.copy(
                                    goalMode = grp,
                                    goalParamN = defN,
                                    goalParamX = defX,
                                    goalElementsMask = if (grp != goalGroup) 0 else it.goalElementsMask
                                )
                            }
                        }
                    )
                }
            }
            if (goalGroup != StrategyGoalMode.HIGH_COMBO) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (goalGroup == StrategyGoalMode.N_COLOR_BALANCE ||
                            goalGroup == StrategyGoalMode.N_COLOR_ALL ||
                            goalGroup == StrategyGoalMode.MULTI_COLOR_X_ORBS
                        ) {
                            val nRange = if (goalGroup == StrategyGoalMode.N_COLOR_ALL) 2..6 else 2..5
                            Text(stringResource(R.string.goal_param_n_label), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                for (n in nRange) {
                                    CompactChoicePill(
                                        label = stringResource(R.string.goal_unit_colors, n),
                                        selected = curGoalN == n,
                                        onClick = { updateCfg { it.copy(goalMode = goalGroup, goalParamN = n, goalElementsMask = 0) } },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                        if (goalGroup == StrategyGoalMode.SINGLE_COLOR_X_ORBS || goalGroup == StrategyGoalMode.MULTI_COLOR_X_ORBS) {
                            Text(stringResource(R.string.goal_param_x_label), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                for (x in 4..9) {
                                    CompactChoicePill(
                                        label = stringResource(R.string.goal_unit_orbs, x),
                                        selected = curGoalX == x,
                                        onClick = { updateCfg { it.copy(goalMode = goalGroup, goalParamX = x) } },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                        if (goalGroup == StrategyGoalMode.SINGLE_COLOR_X_ORBS) {
                            Text(stringResource(R.string.goal_target_elem_label), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                CompactChoicePill(
                                    label = stringResource(R.string.puzzle_elem_any),
                                    selected = cfg.goalTargetElement < 0,
                                    onClick = { updateCfg { it.copy(goalMode = goalGroup, goalTargetElement = -1) } },
                                    modifier = Modifier.weight(1.4f)
                                )
                                for (e in 0 until 6) {
                                    ElementColorTogglePill(
                                        elemIndex = e,
                                        selected = cfg.goalTargetElement == e,
                                        onClick = { updateCfg { it.copy(goalMode = goalGroup, goalTargetElement = e) } },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Multi-Orb Policy & Search Style
            Text(
                text = stringResource(R.string.section_match_policy),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf(
                    FourPlusPolicy.MAX_COMBO to stringResource(R.string.policy_max_combo),
                    FourPlusPolicy.MAX_ORBS to stringResource(R.string.policy_max_orbs),
                    FourPlusPolicy.SINGLE_GROUP to stringResource(R.string.policy_single_group)
                ).forEach { (pol, lbl) ->
                    CompactChoicePill(
                        label = lbl,
                        selected = cfg.fourPlusPolicy == pol,
                        onClick = { updateCfg { it.copy(fourPlusPolicy = pol) } },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                CompactChoicePill(
                    label = stringResource(R.string.policy_not_enough_match),
                    selected = cfg.notEnoughPolicy == NotEnoughPolicy.MATCH_ANYWAY,
                    onClick = { updateCfg { it.copy(notEnoughPolicy = NotEnoughPolicy.MATCH_ANYWAY) } },
                    modifier = Modifier.weight(1f)
                )
                CompactChoicePill(
                    label = stringResource(R.string.policy_not_enough_keep),
                    selected = cfg.notEnoughPolicy == NotEnoughPolicy.KEEP,
                    onClick = { updateCfg { it.copy(notEnoughPolicy = NotEnoughPolicy.KEEP) } },
                    modifier = Modifier.weight(1f)
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                CompactChoicePill(
                    label = stringResource(R.string.policy_best_result),
                    selected = !cfg.earlyStopOnGoal,
                    onClick = { updateCfg { it.copy(earlyStopOnGoal = false) } },
                    modifier = Modifier.weight(1f)
                )
                CompactChoicePill(
                    label = stringResource(R.string.policy_first_result),
                    selected = cfg.earlyStopOnGoal,
                    onClick = { updateCfg { it.copy(earlyStopOnGoal = true) } },
                    modifier = Modifier.weight(1f)
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Combo Shield Primary Exclusive Rule (including max_combo ≤ N!)
            Text(
                text = stringResource(R.string.section_combo_shield),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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

            fun applyComboType(mode: String) {
                ActiveStrategyStore.removeChip("first_wave_only")
                val chip = when (mode) {
                    "min_combo" -> WaveShieldChip("min_combo", "${ctx.getString(R.string.combo_type_min_total)} ≥$minTotalComboVal", ShieldCategory.COMBO, paramInt = minTotalComboVal)
                    "max_combo" -> WaveShieldChip("max_combo", "${ctx.getString(R.string.combo_type_max_total)} ≤$maxTotalComboVal", ShieldCategory.COMBO, paramInt = maxTotalComboVal)
                    "min_first_combo" -> WaveShieldChip("min_first_combo", "${ctx.getString(R.string.combo_type_min_first)} ≥$minFirstComboVal", ShieldCategory.COMBO, paramInt = minFirstComboVal)
                    "min_first_elem_combo" -> WaveShieldChip("min_first_elem_combo", "${ctx.getString(R.string.combo_type_min_first_elem)} ≥$minFirstElemVal", ShieldCategory.COMBO, paramInt = minFirstElemVal)
                    "exact_combo" -> WaveShieldChip("exact_combo", "${ctx.getString(R.string.combo_type_exact_total)} $exactTotalComboVal", ShieldCategory.COMBO, paramInt = exactTotalComboVal)
                    "exact_first_combo" -> WaveShieldChip("exact_first_combo", "${ctx.getString(R.string.combo_type_exact_first)} $exactFirstComboVal", ShieldCategory.COMBO, paramInt = exactFirstComboVal)
                    "wave1_same_kind" -> WaveShieldChip("wave1_same_kind", "${ctx.getString(R.string.combo_type_wave1_same_kind)} $wave1KindVal", ShieldCategory.COMBO, paramInt = wave1KindVal)
                    "wave1_distinct_kind" -> WaveShieldChip("wave1_distinct_kind", "${ctx.getString(R.string.combo_type_wave1_distinct_kind)} $wave1KindVal", ShieldCategory.COMBO, paramInt = wave1KindVal)
                    "zero_combo" -> {
                        listOf(
                            "same_combo_elements", "second_wave_combo", "same_elem_combos",
                            "equal_pair_light_dark", "first_wave_full_clear", "digital_orb_count",
                            "elem_burst_shield", "any_enchanted_orb_shield", "race_orb_shield",
                            "char_orb_first_wave", "starting_buoy_shield", "luminous_orb_shield"
                        ).forEach { ActiveStrategyStore.removeChip(it) }
                        WaveShieldChip("zero_combo", ctx.getString(R.string.combo_type_zero), ShieldCategory.COMBO)
                    }
                    "odd_combo" -> WaveShieldChip("odd_combo", ctx.getString(R.string.combo_type_odd), ShieldCategory.COMBO, paramInt = if (parityFirstOnly) 0 else 1)
                    "even_combo" -> WaveShieldChip("even_combo", ctx.getString(R.string.combo_type_even), ShieldCategory.COMBO, paramInt = if (parityFirstOnly) 0 else 1)
                    else -> null
                }
                ActiveStrategyStore.replaceGroup(WaveShieldCatalog.COMBO_EXCLUSIVE_IDS, chip)
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                listOf(
                    "none" to stringResource(R.string.combo_type_none),
                    "min_combo" to stringResource(R.string.combo_type_min_total),
                    "max_combo" to stringResource(R.string.combo_type_max_total),
                    "min_first_combo" to stringResource(R.string.combo_type_min_first),
                    "min_first_elem_combo" to stringResource(R.string.combo_type_min_first_elem),
                    "exact_combo" to stringResource(R.string.combo_type_exact_total),
                    "exact_first_combo" to stringResource(R.string.combo_type_exact_first),
                    "wave1_same_kind" to stringResource(R.string.combo_type_wave1_same_kind),
                    "wave1_distinct_kind" to stringResource(R.string.combo_type_wave1_distinct_kind),
                    "zero_combo" to stringResource(R.string.combo_type_zero),
                    "odd_combo" to stringResource(R.string.combo_type_odd),
                    "even_combo" to stringResource(R.string.combo_type_even)
                ).forEach { (id, label) ->
                    CompactChoicePill(
                        label = label,
                        selected = curComboMode == id,
                        aiHighlighted = curComboMode == id && id != "none" && isAiChip(id),
                        onClick = {
                            if (curComboMode == id && id != "none") {
                                ActiveStrategyStore.removeChip(id)
                            } else {
                                applyComboType(id)
                            }
                        }
                    )
                }
            }

            if (curComboMode != "none" && curComboMode != "zero_combo") {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        when (curComboMode) {
                            "min_combo" -> NumericStepperRow(
                                label = stringResource(R.string.combo_param_min_total),
                                valueText = stringResource(R.string.combo_value_min_fmt, minTotalComboVal),
                                onMinus = { minTotalComboVal = (minTotalComboVal - 1).coerceIn(1, 15); applyComboType("min_combo") },
                                onPlus = { minTotalComboVal = (minTotalComboVal + 1).coerceIn(1, 15); applyComboType("min_combo") }
                            )
                            "max_combo" -> NumericStepperRow(
                                label = stringResource(R.string.combo_param_max_total),
                                valueText = stringResource(R.string.combo_value_max_fmt, maxTotalComboVal),
                                onMinus = { maxTotalComboVal = (maxTotalComboVal - 1).coerceIn(1, 15); applyComboType("max_combo") },
                                onPlus = { maxTotalComboVal = (maxTotalComboVal + 1).coerceIn(1, 15); applyComboType("max_combo") }
                            )
                            "min_first_combo" -> NumericStepperRow(
                                label = stringResource(R.string.combo_param_min_first),
                                valueText = stringResource(R.string.combo_value_min_fmt, minFirstComboVal),
                                onMinus = { minFirstComboVal = (minFirstComboVal - 1).coerceIn(1, 12); applyComboType("min_first_combo") },
                                onPlus = { minFirstComboVal = (minFirstComboVal + 1).coerceIn(1, 12); applyComboType("min_first_combo") }
                            )
                            "min_first_elem_combo" -> NumericStepperRow(
                                label = stringResource(R.string.combo_param_min_first_elem),
                                valueText = stringResource(R.string.combo_value_min_fmt, minFirstElemVal),
                                onMinus = { minFirstElemVal = (minFirstElemVal - 1).coerceIn(1, 12); applyComboType("min_first_elem_combo") },
                                onPlus = { minFirstElemVal = (minFirstElemVal + 1).coerceIn(1, 12); applyComboType("min_first_elem_combo") }
                            )
                            "exact_combo" -> NumericStepperRow(
                                label = stringResource(R.string.combo_param_exact_total),
                                valueText = stringResource(R.string.combo_value_exact_fmt, exactTotalComboVal),
                                onMinus = { exactTotalComboVal = (exactTotalComboVal - 1).coerceIn(1, 15); applyComboType("exact_combo") },
                                onPlus = { exactTotalComboVal = (exactTotalComboVal + 1).coerceIn(1, 15); applyComboType("exact_combo") }
                            )
                            "exact_first_combo" -> NumericStepperRow(
                                label = stringResource(R.string.combo_param_exact_first),
                                valueText = stringResource(R.string.combo_value_exact_fmt, exactFirstComboVal),
                                onMinus = { exactFirstComboVal = (exactFirstComboVal - 1).coerceIn(1, 12); applyComboType("exact_first_combo") },
                                onPlus = { exactFirstComboVal = (exactFirstComboVal + 1).coerceIn(1, 12); applyComboType("exact_first_combo") }
                            )
                            "wave1_same_kind" -> NumericStepperRow(
                                label = stringResource(R.string.combo_param_wave1_same_kind),
                                valueText = stringResource(R.string.combo_value_exact_fmt, wave1KindVal),
                                onMinus = { wave1KindVal = (wave1KindVal - 1).coerceIn(1, 10); applyComboType("wave1_same_kind") },
                                onPlus = { wave1KindVal = (wave1KindVal + 1).coerceIn(1, 10); applyComboType("wave1_same_kind") }
                            )
                            "wave1_distinct_kind" -> NumericStepperRow(
                                label = stringResource(R.string.combo_param_wave1_distinct_kind),
                                valueText = stringResource(R.string.combo_value_exact_fmt, wave1KindVal),
                                onMinus = { wave1KindVal = (wave1KindVal - 1).coerceIn(1, 10); applyComboType("wave1_distinct_kind") },
                                onPlus = { wave1KindVal = (wave1KindVal + 1).coerceIn(1, 10); applyComboType("wave1_distinct_kind") }
                            )
                            "odd_combo", "even_combo" -> Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                CompactChoicePill(
                                    label = stringResource(R.string.combo_parity_scope_first),
                                    selected = parityFirstOnly,
                                    onClick = { parityFirstOnly = true; applyComboType(curComboMode) },
                                    modifier = Modifier.weight(1f)
                                )
                                CompactChoicePill(
                                    label = stringResource(R.string.combo_parity_scope_total),
                                    selected = !parityFirstOnly,
                                    onClick = { parityFirstOnly = false; applyComboType(curComboMode) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }

            // Structural Combo Modifiers
            AnimatedVisibility(visible = curComboMode != "zero_combo") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.combo_extra_header),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    fun upsertSecondWave(n: Int) {
                        ActiveStrategyStore.upsertChip(WaveShieldChip("second_wave_combo", ctx.getString(R.string.combo_extra_second_wave, n), ShieldCategory.COMBO, paramInt = n))
                    }
                    fun upsertSameElem(n: Int, targetAttr: Int) {
                        val mask = if (targetAttr in 0..5) (1 shl targetAttr) else 0
                        ActiveStrategyStore.upsertChip(WaveShieldChip("same_elem_combos", ctx.getString(R.string.combo_extra_same_elem, n), ShieldCategory.COMBO, paramInt = n, paramElementMask = mask))
                    }
                    fun upsertEqualMulti(mask: Int) {
                        val clean = mask and 0x3F
                        val joined = (0..5).filter { (clean and (1 shl it)) != 0 }.joinToString("=") { LocaleHelper.elementLabel(ctx, it) }
                        ActiveStrategyStore.upsertChip(
                            WaveShieldChip(
                                "equal_pair_light_dark",
                                ctx.getString(R.string.combo_extra_equal_multi_fmt, joined),
                                ShieldCategory.COMBO,
                                paramElementMask = 0x100 or clean
                            )
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        val sameCLabel = stringResource(R.string.combo_extra_same_c)
                        CompactChoicePill(
                            label = sameCLabel,
                            selected = "same_combo_elements" in activeIds,
                            aiHighlighted = isAiChip("same_combo_elements"),
                            onClick = { onToggleWaveChip(WaveShieldChip("same_combo_elements", sameCLabel, ShieldCategory.COMBO)) }
                        )
                        val noCasLabel = stringResource(R.string.shield_no_cascade)
                        CompactChoicePill(
                            label = noCasLabel,
                            selected = "no_cascade" in activeIds,
                            aiHighlighted = isAiChip("no_cascade"),
                            onClick = { onToggleWaveChip(WaveShieldChip("no_cascade", noCasLabel, ShieldCategory.COMBO)) }
                        )
                        CompactChoicePill(
                            label = stringResource(R.string.combo_extra_second_wave, secondWaveVal),
                            selected = "second_wave_combo" in activeIds,
                            aiHighlighted = isAiChip("second_wave_combo"),
                            onClick = {
                                if ("second_wave_combo" in activeIds) ActiveStrategyStore.removeChip("second_wave_combo")
                                else upsertSecondWave(secondWaveVal)
                            }
                        )
                        CompactChoicePill(
                            label = stringResource(R.string.combo_extra_same_elem, sameElemGroupsVal),
                            selected = "same_elem_combos" in activeIds,
                            aiHighlighted = isAiChip("same_elem_combos"),
                            onClick = {
                                if ("same_elem_combos" in activeIds) ActiveStrategyStore.removeChip("same_elem_combos")
                                else upsertSameElem(sameElemGroupsVal, sameElemTarget)
                            }
                        )
                        val eqJoined = (0..5).filter { (equalElemsMask and (1 shl it)) != 0 }.joinToString("=") { LocaleHelper.elementLabel(ctx, it) }
                        CompactChoicePill(
                            label = stringResource(R.string.combo_extra_equal_multi_fmt, eqJoined),
                            selected = "equal_pair_light_dark" in activeIds,
                            aiHighlighted = isAiChip("equal_pair_light_dark"),
                            onClick = {
                                if ("equal_pair_light_dark" in activeIds) ActiveStrategyStore.removeChip("equal_pair_light_dark")
                                else upsertEqualMulti(equalElemsMask)
                            }
                        )
                    }
                    if ("second_wave_combo" in activeIds) {
                        NumericStepperRow(
                            label = stringResource(R.string.combo_second_wave_step),
                            valueText = stringResource(R.string.combo_value_min_fmt, secondWaveVal),
                            onMinus = { secondWaveVal = (secondWaveVal - 1).coerceIn(1, 6); upsertSecondWave(secondWaveVal) },
                            onPlus = { secondWaveVal = (secondWaveVal + 1).coerceIn(1, 6); upsertSecondWave(secondWaveVal) }
                        )
                    }
                    if ("same_elem_combos" in activeIds) {
                        NumericStepperRow(
                            label = stringResource(R.string.combo_same_elem_step),
                            valueText = stringResource(R.string.combo_value_exact_fmt, sameElemGroupsVal),
                            onMinus = { sameElemGroupsVal = (sameElemGroupsVal - 1).coerceIn(2, 6); upsertSameElem(sameElemGroupsVal, sameElemTarget) },
                            onPlus = { sameElemGroupsVal = (sameElemGroupsVal + 1).coerceIn(2, 6); upsertSameElem(sameElemGroupsVal, sameElemTarget) }
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            CompactChoicePill(
                                label = stringResource(R.string.puzzle_elem_any),
                                selected = sameElemTarget < 0,
                                onClick = { sameElemTarget = -1; upsertSameElem(sameElemGroupsVal, -1) },
                                modifier = Modifier.weight(1.5f)
                            )
                            for (e in 0 until 6) {
                                ElementColorTogglePill(
                                    elemIndex = e,
                                    selected = sameElemTarget == e,
                                    onClick = { sameElemTarget = e; upsertSameElem(sameElemGroupsVal, e) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                    if ("equal_pair_light_dark" in activeIds) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (e in 0 until 6) {
                                val bit = 1 shl e
                                val isOn = (equalElemsMask and bit) != 0
                                ElementColorTogglePill(
                                    elemIndex = e,
                                    selected = isOn,
                                    onClick = {
                                        val next = equalElemsMask xor bit
                                        if (Integer.bitCount(next) >= 2) {
                                            equalElemsMask = next
                                            upsertEqualMulti(next)
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }

        // ==================== Drawer ②: Attributes, Orb Counts & Enhanced 6x5 Matrix (Teal) ====================
        val elemIds = setOf(
            "min_distinct_elements", "require_elements", "three_elements", "five_elements", "five_elements_enchanted",
            "elem_burst_shield", "max_element_orbs", "keep_element_at_least_one",
            "anti_first_wave", "forbid_all", "clear_all_element", "unmatchable_elements",
            "digital_orb_count", "first_wave_full_clear", "any_enchanted_orb_shield", "race_orb_shield",
            "char_orb_first_wave", "starting_buoy_shield", "forbid_weakened_orb", "luminous_orb_shield"
        )
        val elemActiveLabels = activeWaveChips.filter { it.id in elemIds }.map { LocaleHelper.formatWaveChipLabel(ctx, it) }
        val customMatrixCount = (0..5).count { e ->
            val r = composedCfg.elementRules.getOrElse(e) { ElementRule() }
            r != ElementRule()
        }
        val elemSummary = buildList {
            addAll(elemActiveLabels.take(2))
            if (customMatrixCount > 0) add("${stringResource(R.string.tag_custom_shape)}×$customMatrixCount")
        }.joinToString(" · ").ifEmpty { stringResource(R.string.ov_card_none_set) }

        ColoredCollapsibleDrawerCard(
            title = stringResource(R.string.unified_group_elem_count),
            accentColor = DrawerAccentColors.Teal,
            summaryText = elemSummary,
            highlighted = elemActiveLabels.isNotEmpty() || customMatrixCount > 0,
            initialExpanded = true,
            onHelp = {
                onShowHelp(
                    ctx.getString(R.string.help_elem_count_title),
                    ctx.getString(R.string.help_elem_matrix_full_body)
                )
            }
        ) {
            Text(stringResource(R.string.elem_min_distinct_label), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val curMinDistinct = byId["min_distinct_elements"]?.paramInt ?: 0
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (cnt in listOf(0, 2, 3, 4, 5, 6)) {
                    CompactChoicePill(
                        label = if (cnt == 0) stringResource(R.string.elem_min_distinct_any) else stringResource(R.string.elem_min_distinct_fmt, cnt),
                        selected = curMinDistinct == cnt,
                        aiHighlighted = cnt > 0 && curMinDistinct == cnt && isAiChip("min_distinct_elements"),
                        onClick = {
                            if (curMinDistinct != cnt) ActiveStrategyStore.replaceGroup(legacyReqIds, null)
                            if (cnt == 0) ActiveStrategyStore.removeChip("min_distinct_elements")
                            else ActiveStrategyStore.upsertChip(WaveShieldChip("min_distinct_elements", ctx.getString(R.string.elem_min_distinct_fmt, cnt), ShieldCategory.COUNT_AND_ELEMENT, paramInt = cnt))
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Required Elements + Independent Per-Color Enchanted Row
            val rawReqParam = byId["require_elements"]?.paramElementMask ?: 0
            val reqMaskFromChip = rawReqParam and 0x3F
            val perColorEnchFromChip = (rawReqParam ushr 8) and 0x3F
            val legacyAllEnch = perColorEnchFromChip == 0 && (byId["require_elements"]?.paramInt ?: 0) == 1
            val effectiveReqMask = remember(activeWaveChips) {
                var m = reqMaskFromChip
                if ("three_elements" in activeIds) m = m or 0x07
                if ("five_elements" in activeIds || "five_elements_enchanted" in activeIds) m = m or 0x1F
                for (e in 0..5) if ("require_elem_$e" in activeIds || "require_ench_$e" in activeIds) m = m or (1 shl e)
                m
            }
            val effectiveEnchMask = remember(activeWaveChips) {
                var em = if (perColorEnchFromChip != 0) perColorEnchFromChip else if (legacyAllEnch) reqMaskFromChip else 0
                if ("five_elements_enchanted" in activeIds) em = em or 0x1F
                for (e in 0..5) if ("require_ench_$e" in activeIds) em = em or (1 shl e)
                em and effectiveReqMask
            }
            val antiMask = readMaskForLimit("anti_first_wave")
            val forbidMask = readMaskForLimit("forbid_all")
            val clearMask = readMaskForLimit("clear_all_element")
            val keepOneMask = readMaskForLimit("keep_element_at_least_one")
            val unmatchMask = readMaskForLimit("unmatchable_elements")

            Text(stringResource(R.string.elem_req_label), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (e in 0 until 6) {
                    val bit = 1 shl e
                    ElementColorTogglePill(
                        elemIndex = e,
                        selected = (effectiveReqMask and bit) != 0,
                        onClick = {
                            val nextReq = effectiveReqMask xor bit
                            val adding = (nextReq and bit) != 0
                            val nextEnch = if (adding) effectiveEnchMask else (effectiveEnchMask and bit.inv())
                            if (adding) {
                                if ((forbidMask and bit) != 0) writeMaskForLimit("forbid_all", ctx.getString(R.string.elem_limit_forbid), forbidMask and bit.inv())
                                val bitCnt = Integer.bitCount(nextReq)
                                if (curMinDistinct in 2..5 && bitCnt > curMinDistinct) {
                                    ActiveStrategyStore.upsertChip(WaveShieldChip("min_distinct_elements", ctx.getString(R.string.elem_min_distinct_fmt, bitCnt), ShieldCategory.COUNT_AND_ELEMENT, paramInt = bitCnt))
                                }
                            }
                            writeRequiredElements(nextReq, nextEnch)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Text(stringResource(R.string.elem_req_ench_row), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (e in 0 until 6) {
                    val bit = 1 shl e
                    val isEnchOn = (effectiveEnchMask and bit) != 0
                    ElementColorTogglePill(
                        elemIndex = e,
                        selected = isEnchOn,
                        suffix = "+",
                        onClick = {
                            val turningOn = !isEnchOn
                            val nextReq = if (turningOn) (effectiveReqMask or bit) else effectiveReqMask
                            val nextEnch = if (turningOn) (effectiveEnchMask or bit) else (effectiveEnchMask and bit.inv())
                            if (turningOn && (forbidMask and bit) != 0) {
                                writeMaskForLimit("forbid_all", ctx.getString(R.string.elem_limit_forbid), forbidMask and bit.inv())
                            }
                            writeRequiredElements(nextReq, nextEnch)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Single-Color Cumulative Orb Shield (3..30 orbs) & Max Element Orbs Shield (≤ N orbs)
            val burstMask = (byId["elem_burst_shield"]?.paramElementMask ?: 0) and 0x3F
            fun saveBurst(mask: Int, orbs: Int) {
                if (mask == 0) {
                    ActiveStrategyStore.removeChip("elem_burst_shield")
                } else {
                    ActiveStrategyStore.removeChip("zero_combo")
                    val names = (0..5).filter { (mask and (1 shl it)) != 0 }.joinToString("") { LocaleHelper.elementLabel(ctx, it) }
                    ActiveStrategyStore.upsertChip(
                        WaveShieldChip("elem_burst_shield", ctx.getString(R.string.elem_burst_fmt, names, orbs), ShieldCategory.COUNT_AND_ELEMENT, paramInt = orbs, paramElementMask = mask)
                    )
                }
            }
            Text(stringResource(R.string.elem_burst_label), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (e in 0 until 6) {
                    val bit = 1 shl e
                    ElementColorTogglePill(
                        elemIndex = e,
                        selected = (burstMask and bit) != 0,
                        onClick = { saveBurst(burstMask xor bit, burstOrbsVal) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            if (burstMask != 0) {
                NumericStepperRow(
                    label = stringResource(R.string.elem_burst_step),
                    valueText = "≥ $burstOrbsVal ${stringResource(R.string.unit_orbs)}",
                    onMinus = { burstOrbsVal = (burstOrbsVal - 1).coerceIn(3, 30); saveBurst(burstMask, burstOrbsVal) },
                    onPlus = { burstOrbsVal = (burstOrbsVal + 1).coerceIn(3, 30); saveBurst(burstMask, burstOrbsVal) }
                )
            }

            val maxOrbsMask = (byId["max_element_orbs"]?.paramElementMask ?: 0) and 0x3F
            fun saveMaxElemOrbs(mask: Int, maxOrbs: Int) {
                if (mask == 0) {
                    ActiveStrategyStore.removeChip("max_element_orbs")
                } else {
                    val names = (0..5).filter { (mask and (1 shl it)) != 0 }.joinToString("") { LocaleHelper.elementLabel(ctx, it) }
                    ActiveStrategyStore.upsertChip(
                        WaveShieldChip("max_element_orbs", ctx.getString(R.string.elem_max_orbs_fmt, names, maxOrbs), ShieldCategory.COUNT_AND_ELEMENT, paramInt = maxOrbs, paramElementMask = mask)
                    )
                }
            }
            Text(stringResource(R.string.elem_max_orbs_label), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (e in 0 until 6) {
                    val bit = 1 shl e
                    ElementColorTogglePill(
                        elemIndex = e,
                        selected = (maxOrbsMask and bit) != 0,
                        onClick = { saveMaxElemOrbs(maxOrbsMask xor bit, maxElemOrbsVal) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            if (maxOrbsMask != 0) {
                NumericStepperRow(
                    label = stringResource(R.string.elem_max_orbs_step),
                    valueText = "≤ $maxElemOrbsVal ${stringResource(R.string.unit_orbs)}",
                    onMinus = { maxElemOrbsVal = (maxElemOrbsVal - 1).coerceIn(0, 30); saveMaxElemOrbs(maxOrbsMask, maxElemOrbsVal) },
                    onPlus = { maxElemOrbsVal = (maxElemOrbsVal + 1).coerceIn(0, 30); saveMaxElemOrbs(maxOrbsMask, maxElemOrbsVal) }
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Attribute Elimination Limits (Anti-1st / Forbid All / Clear All / Keep ≥ 1 / Unmatchable)
            Text(stringResource(R.string.elem_forbid_label), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                listOf(
                    Triple(0, stringResource(R.string.elem_limit_anti), Integer.bitCount(antiMask)),
                    Triple(1, stringResource(R.string.elem_limit_forbid), Integer.bitCount(forbidMask)),
                    Triple(2, stringResource(R.string.elem_limit_clear_all), Integer.bitCount(clearMask)),
                    Triple(3, stringResource(R.string.elem_limit_keep_one), Integer.bitCount(keepOneMask)),
                    Triple(4, stringResource(R.string.elem_limit_unmatchable), Integer.bitCount(unmatchMask))
                ).forEach { (idx, lbl, cnt) ->
                    CompactChoicePill(
                        label = if (cnt > 0) "$lbl($cnt)" else lbl,
                        selected = elemLimitMode == idx,
                        onClick = { elemLimitMode = idx }
                    )
                }
            }
            val curLimitMask = when (elemLimitMode) {
                0 -> antiMask
                1 -> forbidMask
                2 -> clearMask
                3 -> keepOneMask
                else -> unmatchMask
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (e in 0 until 6) {
                    val bit = 1 shl e
                    ElementColorTogglePill(
                        elemIndex = e,
                        selected = (curLimitMask and bit) != 0,
                        onClick = {
                            val nextMask = curLimitMask xor bit
                            val turningOn = (nextMask and bit) != 0
                            val antiLbl = ctx.getString(R.string.elem_limit_anti)
                            val forbidLbl = ctx.getString(R.string.elem_limit_forbid)
                            val clearLbl = ctx.getString(R.string.elem_limit_clear_all)
                            val keepLbl = ctx.getString(R.string.elem_limit_keep_one)
                            val unmatchLbl = ctx.getString(R.string.elem_limit_unmatchable)
                            when (elemLimitMode) {
                                0 -> {
                                    writeMaskForLimit("anti_first_wave", antiLbl, nextMask)
                                    if (turningOn) {
                                        if ((forbidMask and bit) != 0) writeMaskForLimit("forbid_all", forbidLbl, forbidMask and bit.inv())
                                        if ((clearMask and bit) != 0) writeMaskForLimit("clear_all_element", clearLbl, clearMask and bit.inv())
                                    }
                                }
                                1 -> {
                                    writeMaskForLimit("forbid_all", forbidLbl, nextMask)
                                    if (turningOn) {
                                        if ((antiMask and bit) != 0) writeMaskForLimit("anti_first_wave", antiLbl, antiMask and bit.inv())
                                        if ((clearMask and bit) != 0) writeMaskForLimit("clear_all_element", clearLbl, clearMask and bit.inv())
                                        if ((effectiveReqMask and bit) != 0) writeRequiredElements(effectiveReqMask and bit.inv(), effectiveEnchMask and bit.inv())
                                    }
                                }
                                2 -> {
                                    writeMaskForLimit("clear_all_element", clearLbl, nextMask)
                                    if (turningOn) {
                                        if ((antiMask and bit) != 0) writeMaskForLimit("anti_first_wave", antiLbl, antiMask and bit.inv())
                                        if ((forbidMask and bit) != 0) writeMaskForLimit("forbid_all", forbidLbl, forbidMask and bit.inv())
                                        if ((keepOneMask and bit) != 0) writeMaskForLimit("keep_element_at_least_one", keepLbl, keepOneMask and bit.inv())
                                    }
                                }
                                3 -> {
                                    writeMaskForLimit("keep_element_at_least_one", keepLbl, nextMask)
                                    if (turningOn && (clearMask and bit) != 0) {
                                        writeMaskForLimit("clear_all_element", clearLbl, clearMask and bit.inv())
                                    }
                                }
                                else -> writeMaskForLimit("unmatchable_elements", unmatchLbl, nextMask)
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Orb Count & Special Runestones
            Text(stringResource(R.string.elem_count_label), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                val digLabel = stringResource(R.string.shield_digital_orbs_fmt, digitalOrbs)
                CompactChoicePill(
                    label = digLabel,
                    selected = "digital_orb_count" in activeIds,
                    aiHighlighted = isAiChip("digital_orb_count"),
                    onClick = {
                        if ("digital_orb_count" in activeIds) ActiveStrategyStore.removeChip("digital_orb_count")
                        else {
                            ActiveStrategyStore.removeChip("first_wave_full_clear"); ActiveStrategyStore.removeChip("zero_combo")
                            ActiveStrategyStore.upsertChip(WaveShieldChip("digital_orb_count", digLabel, ShieldCategory.COUNT_AND_ELEMENT, paramInt = digitalOrbs))
                        }
                    }
                )
                val fullClearLabel = stringResource(R.string.shield_full_clear)
                CompactChoicePill(
                    label = fullClearLabel,
                    selected = "first_wave_full_clear" in activeIds,
                    aiHighlighted = isAiChip("first_wave_full_clear"),
                    onClick = {
                        if ("first_wave_full_clear" in activeIds) ActiveStrategyStore.removeChip("first_wave_full_clear")
                        else {
                            ActiveStrategyStore.removeChip("digital_orb_count"); ActiveStrategyStore.removeChip("zero_combo")
                            ActiveStrategyStore.upsertChip(WaveShieldChip("first_wave_full_clear", fullClearLabel, ShieldCategory.COUNT_AND_ELEMENT))
                        }
                    }
                )
                listOf(
                    "any_enchanted_orb_shield" to stringResource(R.string.shield_any_enchanted_orb),
                    "race_orb_shield" to stringResource(R.string.shield_race_orb),
                    "char_orb_first_wave" to stringResource(R.string.shield_char_orb_first),
                    "starting_buoy_shield" to stringResource(R.string.shield_starting_buoy),
                    "forbid_weakened_orb" to stringResource(R.string.shield_forbid_weakened),
                    "luminous_orb_shield" to stringResource(R.string.shield_luminous_orb)
                ).forEach { (id, lbl) ->
                    CompactChoicePill(
                        label = lbl,
                        selected = id in activeIds,
                        aiHighlighted = isAiChip(id),
                        onClick = {
                            if (id in activeIds) ActiveStrategyStore.removeChip(id)
                            else {
                                if (id != "forbid_weakened_orb") ActiveStrategyStore.removeChip("zero_combo")
                                ActiveStrategyStore.upsertChip(WaveShieldChip(id, lbl, ShieldCategory.COUNT_AND_ELEMENT))
                            }
                        }
                    )
                }
            }
            if ("race_orb_shield" in activeIds) {
                val curRaceMask = (byId["race_orb_shield"]?.paramInt ?: 0) and 0x7F
                val baseRaceLbl = ctx.getString(R.string.shield_race_orb)
                Text(stringResource(R.string.race_select_label), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    CompactChoicePill(
                        label = stringResource(R.string.race_any),
                        selected = curRaceMask == 0,
                        onClick = { ActiveStrategyStore.upsertChip(WaveShieldChip("race_orb_shield", baseRaceLbl, ShieldCategory.COUNT_AND_ELEMENT, paramInt = 0)) },
                        modifier = Modifier.weight(1.2f)
                    )
                    for (race in RaceType.ALL_RACES) {
                        val on = (curRaceMask and race.bitMask) != 0
                        CompactChoicePill(
                            label = LocaleHelper.raceShortLabel(ctx, race),
                            selected = on,
                            onClick = {
                                val nextMask = curRaceMask xor race.bitMask
                                ActiveStrategyStore.upsertChip(WaveShieldChip("race_orb_shield", baseRaceLbl, ShieldCategory.COUNT_AND_ELEMENT, paramInt = nextMask))
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            if ("digital_orb_count" in activeIds) {
                NumericStepperRow(
                    label = stringResource(R.string.shield_digital_orbs_step),
                    valueText = "$digitalOrbs ${stringResource(R.string.unit_orbs)}",
                    onMinus = {
                        digitalOrbs = (digitalOrbs - 1).coerceIn(6, 30)
                        ActiveStrategyStore.upsertChip(WaveShieldChip("digital_orb_count", ctx.getString(R.string.shield_digital_orbs_fmt, digitalOrbs), ShieldCategory.COUNT_AND_ELEMENT, paramInt = digitalOrbs))
                    },
                    onPlus = {
                        digitalOrbs = (digitalOrbs + 1).coerceIn(6, 30)
                        ActiveStrategyStore.upsertChip(WaveShieldChip("digital_orb_count", ctx.getString(R.string.shield_digital_orbs_fmt, digitalOrbs), ShieldCategory.COUNT_AND_ELEMENT, paramInt = digitalOrbs))
                    }
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // ==================== Enhanced 6x5 Per-Element Rules Matrix (2-Way Synced + Inline Expanders) ====================
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.section_elem_matrix),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    onClick = {
                        if (customMatrixCount > 0) {
                            inlineShapeElemIdx = null
                            inlineCondElemIdx = null
                            listOf("anti_first_wave", "forbid_all", "keep_element_at_least_one", "clear_all_element", "max_element_orbs", "elem_burst_shield").forEach {
                                ActiveStrategyStore.removeChip(it)
                            }
                            updateCfg { cur -> cur.copy(elementRules = List(6) { ElementRule() }) }
                            Toast.makeText(ctx, ctx.getString(R.string.toast_matrix_reset), Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = customMatrixCount > 0,
                    shape = RoundedCornerShape(7.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Outlined.RestartAlt, contentDescription = null, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = stringResource(R.string.btn_reset_matrix),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MatrixHdr(stringResource(R.string.col_hdr_elem), Modifier.width(30.dp))
                MatrixHdr(stringResource(R.string.col_hdr_shape), Modifier.weight(1.08f))
                MatrixHdr(stringResource(R.string.col_hdr_priority), Modifier.weight(0.95f))
                MatrixHdr(stringResource(R.string.col_hdr_timing), Modifier.weight(0.95f))
                MatrixHdr(stringResource(R.string.col_hdr_cond), Modifier.weight(1.02f))
            }

            for (e in 0 until 6) {
                val baseEr = cfg.elementRules.getOrElse(e) { ElementRule() }
                val effEr = composedCfg.elementRules.getOrElse(e) { baseEr }
                val dispShape = if (effEr.clearAllFirstWave) TargetShape.CLEAR_ALL else effEr.targetShape
                val dispTiming = if (effEr.timing == MatchTiming.ANY && (composedCfg.requiredFirstWaveElementsMask and (1 shl e)) != 0) {
                    MatchTiming.MUST_FIRST_WAVE
                } else {
                    effEr.timing
                }
                val shapeAi = dispShape != (if (baseEr.clearAllFirstWave) TargetShape.CLEAR_ALL else baseEr.targetShape)
                val prioAi = effEr.priority != baseEr.priority
                val timingAi = dispTiming != baseEr.timing
                val condSummary = formatMatrixConditionSummary(ctx, effEr)
                val hasCustomCond = effEr.minMatchCount != 3 || effEr.minTotalOrbs > 0 || effEr.minFirstWaveOrbs > 0 ||
                    effEr.exactFirstWaveOrbs >= 0 || effEr.maxTotalOrbs >= 0 || effEr.minFirstWaveCombos > 0 ||
                    effEr.requireEnchanted || effEr.requireRace
                val condAi = effEr.minTotalOrbs != baseEr.minTotalOrbs || effEr.maxTotalOrbs != baseEr.maxTotalOrbs ||
                    effEr.requireEnchanted != baseEr.requireEnchanted
                val badgeColor = Color(LocaleHelper.elementBadgeArgb(e))
                val isShapeOpen = inlineShapeElemIdx == e
                val isCondOpen = inlineCondElemIdx == e

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = badgeColor,
                        modifier = Modifier.width(30.dp).height(30.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(LocaleHelper.elementLabel(ctx, e), color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    CompactChoicePill(
                        label = LocaleHelper.targetShapeLabel(ctx, dispShape),
                        selected = dispShape != TargetShape.ANY || isShapeOpen,
                        aiHighlighted = shapeAi,
                        onClick = {
                            inlineCondElemIdx = null
                            inlineShapeElemIdx = if (isShapeOpen) null else e
                        },
                        modifier = Modifier.weight(1.08f)
                    )
                    CompactChoicePill(
                        label = LocaleHelper.elementPriorityLabel(ctx, effEr.priority),
                        selected = effEr.priority != ElementPriority.NORMAL,
                        aiHighlighted = prioAi,
                        onClick = {
                            val nxt = when (effEr.priority) {
                                ElementPriority.NORMAL -> ElementPriority.HIGH
                                ElementPriority.HIGH -> ElementPriority.KEEP_AT_LEAST_ONE
                                ElementPriority.KEEP_AT_LEAST_ONE -> ElementPriority.RESERVE
                                ElementPriority.RESERVE -> ElementPriority.NORMAL
                            }
                            if (nxt != ElementPriority.KEEP_AT_LEAST_ONE) {
                                ActiveStrategyStore.removeElementFromChip("keep_element_at_least_one", e)
                            }
                            if (nxt != ElementPriority.HIGH) {
                                ActiveStrategyStore.removeElementFromChip("elem_burst_shield", e)
                            }
                            updateCfg { cur ->
                                cur.copy(elementRules = cur.elementRules.toMutableList().also { it[e] = baseEr.copy(priority = nxt) })
                            }
                        },
                        modifier = Modifier.weight(0.95f)
                    )
                    CompactChoicePill(
                        label = LocaleHelper.matchTimingLabel(ctx, dispTiming),
                        selected = dispTiming != MatchTiming.ANY,
                        aiHighlighted = timingAi,
                        onClick = {
                            val nxt = when (dispTiming) {
                                MatchTiming.ANY -> MatchTiming.MUST_FIRST_WAVE
                                MatchTiming.MUST_FIRST_WAVE -> MatchTiming.FORBID_FIRST_WAVE_ALLOW_CASCADE
                                MatchTiming.FORBID_FIRST_WAVE_ALLOW_CASCADE -> MatchTiming.FORBID_ALL
                                MatchTiming.FORBID_ALL -> MatchTiming.ANY
                            }
                            ActiveStrategyStore.removeElementFromChip("anti_first_wave", e)
                            ActiveStrategyStore.removeElementFromChip("forbid_all", e)
                            updateCfg { cur ->
                                cur.copy(elementRules = cur.elementRules.toMutableList().also { it[e] = baseEr.copy(timing = nxt) })
                            }
                        },
                        modifier = Modifier.weight(0.95f)
                    )
                    CompactChoicePill(
                        label = "$condSummary▾",
                        selected = hasCustomCond || isCondOpen,
                        aiHighlighted = condAi,
                        onClick = {
                            inlineShapeElemIdx = null
                            inlineCondElemIdx = if (isCondOpen) null else e
                        },
                        modifier = Modifier.weight(1.02f)
                    )
                }

                // Inline Shape Selector (17 shapes including SQUARE_3X3, NO dialog!)
                AnimatedVisibility(visible = isShapeOpen) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.65f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.matrix_shape_picker_title, LocaleHelper.elementLabel(ctx, e)),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = badgeColor
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                for (sh in shapeOrder) {
                                    CompactChoicePill(
                                        label = LocaleHelper.targetShapeLabel(ctx, sh),
                                        selected = dispShape == sh,
                                        onClick = {
                                            if (sh != TargetShape.CLEAR_ALL) {
                                                ActiveStrategyStore.removeElementFromChip("clear_all_element", e)
                                            }
                                            updateCfg { cur ->
                                                cur.copy(
                                                    elementRules = cur.elementRules.toMutableList().also {
                                                        it[e] = baseEr.copy(
                                                            targetShape = sh,
                                                            clearAllFirstWave = sh == TargetShape.CLEAR_ALL
                                                        )
                                                    }
                                                )
                                            }
                                            inlineShapeElemIdx = null
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // Inline Advanced Condition Micro-Panel (NO dialog!)
                AnimatedVisibility(visible = isCondOpen) {
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
                        updateCfg { cur ->
                            cur.copy(elementRules = cur.elementRules.toMutableList().also { it[e] = updated })
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.65f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.matrix_cond_title, LocaleHelper.elementLabel(ctx, e)),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = badgeColor
                            )
                            Text(stringResource(R.string.matrix_cond_min_match), fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                for (m in 1..5) {
                                    CompactChoicePill(
                                        label = stringResource(R.string.min_match_fmt, m),
                                        selected = effEr.minMatchCount == m,
                                        onClick = { commitRule(baseEr.copy(minMatchCount = m)) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                            Text(stringResource(R.string.matrix_cond_orb_thresh), fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(
                                    0 to stringResource(R.string.matrix_cond_any),
                                    1 to stringResource(R.string.matrix_cond_min_orbs_fmt, curThreshNum),
                                    2 to stringResource(R.string.matrix_cond_exact_orbs_fmt, curThreshNum),
                                    3 to stringResource(R.string.matrix_cond_max_orbs_fmt, curThreshNum)
                                ).forEach { (modeIdx, mLbl) ->
                                    CompactChoicePill(
                                        label = mLbl,
                                        selected = curThreshMode == modeIdx,
                                        onClick = {
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
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                            if (curThreshMode != 0) {
                                NumericStepperRow(
                                    label = stringResource(R.string.matrix_cond_orb_thresh),
                                    valueText = "$curThreshNum ${stringResource(R.string.unit_orbs)}",
                                    onMinus = {
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
                                    },
                                    onPlus = {
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
                                    }
                                )
                            }
                            Text(stringResource(R.string.matrix_cond_w1_groups), fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                for (g in 0..4) {
                                    CompactChoicePill(
                                        label = if (g == 0) stringResource(R.string.matrix_cond_any) else stringResource(R.string.matrix_cond_groups_fmt, g),
                                        selected = baseEr.minFirstWaveCombos == g,
                                        onClick = { commitRule(baseEr.copy(minFirstWaveCombos = g)) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                CompactChoicePill(
                                    label = stringResource(R.string.matrix_cond_req_ench),
                                    selected = effEr.requireEnchanted,
                                    onClick = { commitRule(baseEr.copy(requireEnchanted = !effEr.requireEnchanted)) },
                                    modifier = Modifier.weight(1f)
                                )
                                CompactChoicePill(
                                    label = stringResource(R.string.matrix_cond_req_race),
                                    selected = effEr.requireRace,
                                    onClick = { commitRule(baseEr.copy(requireRace = !effEr.requireRace)) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }

        // ==================== Drawer ③: Puzzle, Shapes, Columns & Rows (Amber) ====================
        val shapeIds = setOf(
            "puzzle_shield", "strict_puzzle", "loose_puzzle", "enchanted_puzzle",
            "cross_shield", "cross_shield_2", "cross_shield_3", "forbid_cross",
            "l_shape", "t_shape", "square_2x2", "square_3x3", "all_columns_ameliorate"
        )
        val shapeActiveLabels = activeWaveChips.filter { it.id in shapeIds }.map { LocaleHelper.formatWaveChipLabel(ctx, it) }
        val colEnabledCnt = cfg.columnRules.take(6).count { it.enabled }
        val rowEnabledCnt = cfg.rowRules.take(5).count { it.enabled }
        val shapeSummary = buildList {
            addAll(shapeActiveLabels.take(2))
            if (colEnabledCnt > 0) add("${stringResource(R.string.tag_col_amel)}×$colEnabledCnt")
            if (rowEnabledCnt > 0) add("横×$rowEnabledCnt")
        }.joinToString(" · ").ifEmpty { stringResource(R.string.ov_card_none_set) }

        ColoredCollapsibleDrawerCard(
            title = stringResource(R.string.unified_group_puzzle_shape),
            accentColor = DrawerAccentColors.Amber,
            summaryText = shapeSummary,
            highlighted = shapeActiveLabels.isNotEmpty() || colEnabledCnt > 0 || rowEnabledCnt > 0,
            initialExpanded = true,
            onHelp = {
                onShowHelp(
                    ctx.getString(R.string.help_puzzle_shape_title),
                    ctx.getString(R.string.help_puzzle_shape_full_body)
                )
            }
        ) {
            val pChip = byId["puzzle_shield"]
            val puzzleMode = when {
                pChip != null -> if ((pChip.paramInt and 1) != 0) 2 else 1
                "strict_puzzle" in activeIds || "enchanted_puzzle" in activeIds -> 2
                "loose_puzzle" in activeIds -> 1
                else -> 0
            }
            val puzzleEnch = if (pChip != null) (pChip.paramInt and 2) != 0 else ("enchanted_puzzle" in activeIds)
            val puzzleElemEnc = if (pChip != null) (pChip.paramInt ushr 2) and 0x0F else 0
            val puzzleElem = if (puzzleElemEnc in 1..6) puzzleElemEnc - 1 else -1
            val puzzleShapeId = if (pChip != null) (pChip.paramInt ushr 8) and 0x0F else 0
            val puzzleAi = WaveShieldCatalog.PUZZLE_EXCLUSIVE_IDS.any { isAiChip(it) }

            fun savePuzzle(mode: Int, ench: Boolean, elem: Int, shapeId: Int) {
                if (mode == 0) { ActiveStrategyStore.replaceGroup(WaveShieldCatalog.PUZZLE_EXCLUSIVE_IDS, null); return }
                val packed = (if (mode == 2) 1 else 0) or (if (ench) 2 else 0) or (if (elem in 0..5) ((elem + 1) shl 2) else 0) or ((shapeId and 0x0F) shl 8)
                ActiveStrategyStore.replaceGroup(
                    WaveShieldCatalog.PUZZLE_EXCLUSIVE_IDS,
                    WaveShieldChip("puzzle_shield", ctx.getString(if (mode == 2) R.string.puzzle_mode_strict else R.string.puzzle_mode_normal), ShieldCategory.SHAPE_AND_PUZZLE, paramInt = packed)
                )
            }

            Text(stringResource(R.string.puzzle_mode_label), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                CompactChoicePill(stringResource(R.string.puzzle_mode_off), puzzleMode == 0, { savePuzzle(0, false, -1, 0) }, Modifier.weight(1f))
                CompactChoicePill(stringResource(R.string.puzzle_mode_normal), puzzleMode == 1, { savePuzzle(1, puzzleEnch, puzzleElem, puzzleShapeId) }, Modifier.weight(1f), aiHighlighted = puzzleMode == 1 && puzzleAi)
                CompactChoicePill(stringResource(R.string.puzzle_mode_strict), puzzleMode == 2, { savePuzzle(2, puzzleEnch, puzzleElem, puzzleShapeId) }, Modifier.weight(1f), aiHighlighted = puzzleMode == 2 && puzzleAi)
            }
            AnimatedVisibility(visible = puzzleMode > 0) {
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        CompactSwitchLine(
                            title = stringResource(R.string.puzzle_ench_switch),
                            checked = puzzleEnch,
                            onCheckedChange = { savePuzzle(puzzleMode, it, puzzleElem, puzzleShapeId) }
                        )
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            CompactChoicePill(stringResource(R.string.puzzle_elem_any), puzzleElem < 0, { savePuzzle(puzzleMode, puzzleEnch, -1, puzzleShapeId) }, Modifier.weight(1.4f))
                            for (e in 0 until 6) {
                                ElementColorTogglePill(e, puzzleElem == e, { savePuzzle(puzzleMode, puzzleEnch, e, puzzleShapeId) }, Modifier.weight(1f))
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            for (shapeId in 0..6) {
                                CompactChoicePill(LocaleHelper.puzzleShapeLabel(ctx, shapeId), puzzleShapeId == shapeId, { savePuzzle(puzzleMode, puzzleEnch, puzzleElem, shapeId) })
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Cross Shield (1..3 Crosses + Forbid Cross 十之诅咒!)
            Text(stringResource(R.string.shape_shield_label), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val forbidCrossOn = "forbid_cross" in activeIds
            val crossChip = byId["cross_shield"] ?: byId["cross_shield_2"] ?: byId["cross_shield_3"]
            val curCrossCount = if (forbidCrossOn) 0 else ((crossChip?.paramInt ?: 0) and 0x0F)
            val curCrossDistinct = ((crossChip?.paramInt ?: 0) and 0x20) != 0
            val curCrossColorsMask = (crossChip?.paramElementMask ?: 0) and 0x3F
            fun saveCross(count: Int, distinct: Boolean, colorsMask: Int) {
                if (count <= 0) { ActiveStrategyStore.replaceGroup(WaveShieldCatalog.CROSS_EXCLUSIVE_IDS, null); return }
                val safeDistinct = (distinct && count >= 2) || Integer.bitCount(colorsMask) >= 2
                val packed = (count and 0x0F) or (if (safeDistinct) 0x20 else 0)
                ActiveStrategyStore.replaceGroup(
                    WaveShieldCatalog.CROSS_EXCLUSIVE_IDS,
                    WaveShieldChip("cross_shield", ctx.getString(R.string.ov_cross_fmt, count), ShieldCategory.SHAPE_AND_PUZZLE, paramInt = packed, paramElementMask = colorsMask)
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf(
                    0 to stringResource(R.string.cross_off),
                    1 to stringResource(R.string.cross_1),
                    2 to stringResource(R.string.cross_2),
                    3 to stringResource(R.string.cross_3)
                ).forEach { (cnt, lbl) ->
                    CompactChoicePill(
                        label = lbl,
                        selected = !forbidCrossOn && curCrossCount == cnt,
                        aiHighlighted = cnt > 0 && curCrossCount == cnt && WaveShieldCatalog.CROSS_EXCLUSIVE_IDS.any { isAiChip(it) },
                        onClick = {
                            val nextColors = if (cnt != curCrossCount && Integer.bitCount(curCrossColorsMask) > cnt) 0 else curCrossColorsMask
                            saveCross(cnt, curCrossDistinct && cnt >= 2, nextColors)
                        }
                    )
                }
                val forbidCrossLbl = stringResource(R.string.shield_forbid_cross)
                CompactChoicePill(
                    label = forbidCrossLbl,
                    selected = forbidCrossOn,
                    aiHighlighted = isAiChip("forbid_cross"),
                    onClick = {
                        if (forbidCrossOn) ActiveStrategyStore.removeChip("forbid_cross")
                        else ActiveStrategyStore.replaceGroup(
                            WaveShieldCatalog.CROSS_EXCLUSIVE_IDS,
                            WaveShieldChip("forbid_cross", forbidCrossLbl, ShieldCategory.SHAPE_AND_PUZZLE)
                        )
                    }
                )
            }
            AnimatedVisibility(visible = curCrossCount > 0) {
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (curCrossCount >= 2) {
                            CompactSwitchLine(
                                title = stringResource(R.string.cross_distinct_switch),
                                checked = curCrossDistinct,
                                onCheckedChange = {
                                    val nextColors = if (!it && Integer.bitCount(curCrossColorsMask) >= 2) 0 else curCrossColorsMask
                                    saveCross(curCrossCount, it, nextColors)
                                }
                            )
                        }
                        Text(stringResource(R.string.cross_colors_label, curCrossCount), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (e in 0 until 6) {
                                val bit = 1 shl e
                                val isOn = (curCrossColorsMask and bit) != 0
                                ElementColorTogglePill(
                                    elemIndex = e,
                                    selected = isOn,
                                    onClick = {
                                        val nextColors = if (isOn) curCrossColorsMask and bit.inv()
                                        else if (Integer.bitCount(curCrossColorsMask) < curCrossCount) curCrossColorsMask or bit
                                        else { val lb = curCrossColorsMask and -curCrossColorsMask; (curCrossColorsMask and lb.inv()) or bit }
                                        saveCross(curCrossCount, curCrossDistinct || Integer.bitCount(nextColors) >= 2, nextColors)
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }

            // L / T / 2x2 / 3x3 / All-Columns + Optional Shape Element Mask
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf(
                    WaveShieldChip("l_shape", stringResource(R.string.shield_l_shape), ShieldCategory.SHAPE_AND_PUZZLE, paramInt = 1),
                    WaveShieldChip("t_shape", stringResource(R.string.shield_t_shape), ShieldCategory.SHAPE_AND_PUZZLE, paramInt = 1),
                    WaveShieldChip("square_2x2", stringResource(R.string.shield_sq_2x2), ShieldCategory.SHAPE_AND_PUZZLE, paramInt = 1),
                    WaveShieldChip("square_3x3", stringResource(R.string.shield_sq_3x3), ShieldCategory.SHAPE_AND_PUZZLE, paramInt = 1),
                    WaveShieldChip("all_columns_ameliorate", stringResource(R.string.shield_all_cols), ShieldCategory.SHAPE_AND_PUZZLE)
                ).forEach { chip ->
                    CompactChoicePill(
                        label = chip.label,
                        selected = chip.id in activeIds,
                        aiHighlighted = isAiChip(chip.id),
                        onClick = { onToggleWaveChip(chip) }
                    )
                }
            }
            val activeGeoChip = listOf("l_shape", "t_shape", "square_2x2", "square_3x3").firstOrNull { it in activeIds }?.let { byId[it] }
            if (activeGeoChip != null) {
                val geoMask = activeGeoChip.paramElementMask and 0x3F
                Text(stringResource(R.string.shape_elem_opt_label), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    CompactChoicePill(
                        label = stringResource(R.string.puzzle_elem_any),
                        selected = geoMask == 0,
                        onClick = { ActiveStrategyStore.upsertChip(activeGeoChip.copy(paramElementMask = 0, autoDetected = false)) },
                        modifier = Modifier.weight(1.4f)
                    )
                    for (e in 0 until 6) {
                        val bit = 1 shl e
                        ElementColorTogglePill(
                            elemIndex = e,
                            selected = (geoMask and bit) != 0,
                            onClick = { ActiveStrategyStore.upsertChip(activeGeoChip.copy(paramElementMask = geoMask xor bit, autoDetected = false)) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Column Amelioration 1..6 (2x3 Grid)
            Text(stringResource(R.string.section_columns), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (rowIdx in 0..1) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    for (offset in 0..2) {
                        val c = rowIdx * 3 + offset
                        val rule = cfg.columnRules.getOrElse(c) { ColumnAmeliorationRule() }
                        val lbl = if (!rule.enabled) stringResource(R.string.col_off_fmt, c + 1) else stringResource(R.string.col_on_fmt, c + 1, rule.minOrbs)
                        CompactChoicePill(
                            label = lbl,
                            selected = rule.enabled,
                            onClick = {
                                val nxt = when {
                                    !rule.enabled -> rule.copy(enabled = true, minOrbs = 3)
                                    rule.minOrbs == 3 -> rule.copy(enabled = true, minOrbs = 4)
                                    rule.minOrbs == 4 -> rule.copy(enabled = true, minOrbs = 5)
                                    else -> rule.copy(enabled = false, minOrbs = 4)
                                }
                                updateCfg { cur -> cur.copy(columnRules = cur.columnRules.toMutableList().also { it[c] = nxt }) }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Horizontal Row Elimination 1..5
            Text(stringResource(R.string.section_rows_amel), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                for (r in 0 until 5) {
                    val rule = cfg.rowRules.getOrElse(r) { RowBonusRule() }
                    val lbl = if (!rule.enabled) stringResource(R.string.row_off_fmt, r + 1) else stringResource(R.string.row_on_fmt, r + 1, 6)
                    CompactChoicePill(
                        label = lbl,
                        selected = rule.enabled,
                        onClick = {
                            updateCfg { cur ->
                                cur.copy(rowRules = cur.rowRules.toMutableList().also { it[r] = rule.copy(enabled = !rule.enabled) })
                            }
                        }
                    )
                }
            }
        }

        // ==================== Drawer ④: Path, Terrain Tolerance & Hazard Policies (Crimson) ====================
        val pathIds = setOf("traffic_light", "zero_trail_burning", "trail_corrosion", "step_hurt_minimize", "char_orb_terrain", "weijian_burst")
        val pathActiveLabels = activeWaveChips.filter { it.id in pathIds }.map { LocaleHelper.formatWaveChipLabel(ctx, it) }
        val pathSummary = buildList {
            addAll(pathActiveLabels.take(2))
            if (cfg.maxBurningTileHurts > 0) add("燃≤${cfg.maxBurningTileHurts}")
            if (cfg.allowHitWeatheredToClear) add(stringResource(R.string.switch_hit_weathered).take(6))
        }.joinToString(" · ").ifEmpty { stringResource(R.string.ov_card_none_set) }

        ColoredCollapsibleDrawerCard(
            title = stringResource(R.string.unified_group_path_safety),
            accentColor = DrawerAccentColors.Crimson,
            summaryText = pathSummary,
            highlighted = pathActiveLabels.isNotEmpty() || cfg.allowHitWeatheredToClear || cfg.maxBurningTileHurts > 0 || cfg.maxMiasmaHits > 0,
            initialExpanded = true,
            onHelp = {
                onShowHelp(
                    ctx.getString(R.string.help_path_hazard_title),
                    ctx.getString(R.string.help_path_hazard_full_body)
                )
            }
        ) {
            val burnOn = "zero_trail_burning" in activeIds
            fun upsertBurn(hurts: Int, len: Int) {
                val packed = (hurts shl 8) or (len and 0xFF)
                ActiveStrategyStore.upsertChip(WaveShieldChip("zero_trail_burning", ctx.getString(R.string.burn_path_title), ShieldCategory.HAZARD_AND_PATH, paramInt = packed))
            }
            SwitchSettingRow(
                title = stringResource(R.string.burn_path_title),
                subtitle = stringResource(R.string.burn_path_sub),
                checked = burnOn,
                onCheckedChange = { if (it) upsertBurn(maxBurnHurts, burnTrailLen) else ActiveStrategyStore.removeChip("zero_trail_burning") }
            )
            if (burnOn) {
                NumericStepperRow(
                    label = stringResource(R.string.burn_max_hurts),
                    valueText = "$maxBurnHurts ${stringResource(R.string.unit_times)}",
                    onMinus = { maxBurnHurts = (maxBurnHurts - 1).coerceIn(0, 10); upsertBurn(maxBurnHurts, burnTrailLen) },
                    onPlus = { maxBurnHurts = (maxBurnHurts + 1).coerceIn(0, 10); upsertBurn(maxBurnHurts, burnTrailLen) }
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    CompactChoicePill(stringResource(R.string.burn_len_max), burnTrailLen == 0, { burnTrailLen = 0; upsertBurn(maxBurnHurts, 0) }, Modifier.weight(1f))
                    CompactChoicePill(stringResource(R.string.burn_len_6), burnTrailLen == 6, { burnTrailLen = 6; upsertBurn(maxBurnHurts, 6) }, Modifier.weight(1f))
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            NumericStepperRow(
                label = stringResource(R.string.burn_tile_hurts_label),
                valueText = "${cfg.maxBurningTileHurts} ${stringResource(R.string.unit_times)}",
                onMinus = { updateCfg { it.copy(maxBurningTileHurts = (it.maxBurningTileHurts - 1).coerceIn(0, 10)) } },
                onPlus = { updateCfg { it.copy(maxBurningTileHurts = (it.maxBurningTileHurts + 1).coerceIn(0, 10)) } }
            )
            NumericStepperRow(
                label = stringResource(R.string.miasma_hurts_label),
                valueText = "${cfg.maxMiasmaHits} ${stringResource(R.string.unit_times)}",
                onMinus = { updateCfg { it.copy(maxMiasmaHits = (it.maxMiasmaHits - 1).coerceIn(0, 5)) } },
                onPlus = { updateCfg { it.copy(maxMiasmaHits = (it.maxMiasmaHits + 1).coerceIn(0, 5)) } }
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf(
                    WaveShieldChip("traffic_light", stringResource(R.string.shield_traffic_light), ShieldCategory.HAZARD_AND_PATH),
                    WaveShieldChip("trail_corrosion", stringResource(R.string.shield_trail_corrosion), ShieldCategory.HAZARD_AND_PATH, paramInt = 15),
                    WaveShieldChip("step_hurt_minimize", stringResource(R.string.shield_step_hurt), ShieldCategory.HAZARD_AND_PATH),
                    WaveShieldChip("char_orb_terrain", stringResource(R.string.shield_char_terrain), ShieldCategory.HAZARD_AND_PATH),
                    WaveShieldChip("weijian_burst", stringResource(R.string.shield_weijian_burst), ShieldCategory.HAZARD_AND_PATH)
                ).forEach { chip ->
                    CompactChoicePill(chip.label, chip.id in activeIds, { onToggleWaveChip(chip) }, aiHighlighted = isAiChip(chip.id))
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Text(stringResource(R.string.hazard_policy_section), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CompactSwitchLine(stringResource(R.string.switch_must_clear_elec), cfg.mustClearElectrified) { v -> updateCfg { it.copy(mustClearElectrified = v) } }
            CompactSwitchLine(stringResource(R.string.switch_must_clear_blood), cfg.mustClearBloodDrain) { v -> updateCfg { it.copy(mustClearBloodDrain = v) } }
            CompactSwitchLine(stringResource(R.string.switch_must_clear_bomb), cfg.mustClearBomb) { v -> updateCfg { it.copy(mustClearBomb = v) } }
            CompactSwitchLine(stringResource(R.string.switch_must_clear_foam), cfg.mustClearFoam) { v -> updateCfg { it.copy(mustClearFoam = v) } }
            CompactSwitchLine(stringResource(R.string.switch_prio_frozen), cfg.prioritizeFrozen) { v -> updateCfg { it.copy(prioritizeFrozen = v) } }
            CompactSwitchLine(stringResource(R.string.switch_prio_petrified), cfg.prioritizePetrifiedClear) { v -> updateCfg { it.copy(prioritizePetrifiedClear = v) } }
            CompactSwitchLine(stringResource(R.string.switch_hit_weathered), cfg.allowHitWeatheredToClear) { v -> updateCfg { it.copy(allowHitWeatheredToClear = v) } }
        }

        // ==================== Drawer ⑤: Budget, 4-Mode Steps, Diagonal Override & Start (Blue) ====================
        val budgetStepsEff = if (cfg.useSystemSteps) settings.maxSteps else cfg.maxSteps
        val budgetBeamEff = if (cfg.useSystemBeam) settings.beamWidth else cfg.beamWidth
        val budgetSummary = buildList {
            byId["exact_steps"]?.let { add(LocaleHelper.formatWaveChipLabel(ctx, it)) }
                ?: byId["step_range"]?.let { add(LocaleHelper.formatWaveChipLabel(ctx, it)) }
                ?: byId["step_limit"]?.let { add(stringResource(R.string.ov_step_limit_short, it.paramInt)) }
                ?: add("$budgetStepsEff${stringResource(R.string.unit_steps)}")
            add(stringResource(R.string.tag_beam_fmt, budgetBeamEff))
        }.joinToString(" · ")

        ColoredCollapsibleDrawerCard(
            title = stringResource(R.string.unified_group_budget),
            accentColor = DrawerAccentColors.Blue,
            summaryText = budgetSummary,
            highlighted = WaveShieldCatalog.STEP_EXCLUSIVE_IDS.any { it in activeIds } || "traffic_light" in activeIds ||
                !cfg.useSystemSteps || !cfg.useSystemBeam || !cfg.useSystemDiagonal || cfg.useCharacterOrb || cfg.startPreset != StartPointPreset.ANY,
            initialExpanded = true,
            onHelp = {
                onShowHelp(
                    ctx.getString(R.string.help_basic_steps_title),
                    ctx.getString(R.string.help_budget_start_full_body)
                )
            }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CompactInputField(
                    value = stepsText,
                    onValueChange = { raw ->
                        val d = raw.filter { it.isDigit() }.take(3)
                        stepsText = d
                        val v = d.toIntOrNull()
                        if (v != null && v in 8..300) {
                            updateCfg { it.copy(maxSteps = v, useSystemSteps = false) }
                        }
                    },
                    placeholder = stringResource(R.string.field_strategy_steps),
                    numeric = true,
                    suffixText = stringResource(R.string.unit_steps),
                    modifier = Modifier.weight(1f)
                )
                CompactChoicePill(
                    label = stringResource(if (cfg.useSystemSteps) R.string.budget_follow_system else R.string.budget_override_strategy),
                    selected = cfg.useSystemSteps,
                    onClick = { updateCfg { it.copy(useSystemSteps = !it.useSystemSteps) } }
                )
            }

            // 4-Mode Wave Hard Step Controller (不限 | ≤N步 | A~B步 | 恰好=N步)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                val stepLbl = stringResource(R.string.shield_step_limit_fmt, stepLimitVal)
                CompactChoicePill(
                    label = stepLbl,
                    selected = "step_limit" in activeIds,
                    aiHighlighted = isAiChip("step_limit"),
                    onClick = {
                        if ("step_limit" in activeIds) ActiveStrategyStore.removeChip("step_limit")
                        else ActiveStrategyStore.replaceGroup(
                            WaveShieldCatalog.STEP_EXCLUSIVE_IDS,
                            WaveShieldChip("step_limit", stepLbl, ShieldCategory.HAZARD_AND_PATH, paramInt = stepLimitVal)
                        )
                    }
                )
                val rangeLbl = stringResource(R.string.shield_step_range_fmt, stepRangeMinVal, stepRangeMaxVal)
                CompactChoicePill(
                    label = rangeLbl,
                    selected = "step_range" in activeIds,
                    aiHighlighted = isAiChip("step_range"),
                    onClick = {
                        if ("step_range" in activeIds) ActiveStrategyStore.removeChip("step_range")
                        else ActiveStrategyStore.replaceGroup(
                            WaveShieldCatalog.STEP_EXCLUSIVE_IDS,
                            WaveShieldChip("step_range", rangeLbl, ShieldCategory.HAZARD_AND_PATH, paramInt = stepRangeMinVal or (stepRangeMaxVal shl 8))
                        )
                    }
                )
                val exactStepLbl = stringResource(R.string.shield_exact_steps_fmt, exactStepsVal)
                CompactChoicePill(
                    label = exactStepLbl,
                    selected = "exact_steps" in activeIds,
                    aiHighlighted = isAiChip("exact_steps"),
                    onClick = {
                        if ("exact_steps" in activeIds) ActiveStrategyStore.removeChip("exact_steps")
                        else ActiveStrategyStore.replaceGroup(
                            WaveShieldCatalog.STEP_EXCLUSIVE_IDS,
                            WaveShieldChip("exact_steps", exactStepLbl, ShieldCategory.HAZARD_AND_PATH, paramInt = exactStepsVal)
                        )
                    }
                )
            }
            if ("step_limit" in activeIds) {
                NumericStepperRow(
                    label = stringResource(R.string.shield_step_limit_fmt, stepLimitVal),
                    valueText = "≤$stepLimitVal",
                    onMinus = {
                        stepLimitVal = (stepLimitVal - 1).coerceIn(2, 60)
                        ActiveStrategyStore.upsertChip(WaveShieldChip("step_limit", ctx.getString(R.string.shield_step_limit_fmt, stepLimitVal), ShieldCategory.HAZARD_AND_PATH, paramInt = stepLimitVal))
                    },
                    onPlus = {
                        stepLimitVal = (stepLimitVal + 1).coerceIn(2, 60)
                        ActiveStrategyStore.upsertChip(WaveShieldChip("step_limit", ctx.getString(R.string.shield_step_limit_fmt, stepLimitVal), ShieldCategory.HAZARD_AND_PATH, paramInt = stepLimitVal))
                    }
                )
            }
            if ("exact_steps" in activeIds) {
                NumericStepperRow(
                    label = stringResource(R.string.shield_exact_steps_step),
                    valueText = "=$exactStepsVal",
                    onMinus = {
                        exactStepsVal = (exactStepsVal - 1).coerceIn(1, 100)
                        ActiveStrategyStore.upsertChip(WaveShieldChip("exact_steps", ctx.getString(R.string.shield_exact_steps_fmt, exactStepsVal), ShieldCategory.HAZARD_AND_PATH, paramInt = exactStepsVal))
                    },
                    onPlus = {
                        exactStepsVal = (exactStepsVal + 1).coerceIn(1, 100)
                        ActiveStrategyStore.upsertChip(WaveShieldChip("exact_steps", ctx.getString(R.string.shield_exact_steps_fmt, exactStepsVal), ShieldCategory.HAZARD_AND_PATH, paramInt = exactStepsVal))
                    }
                )
            }
            if ("step_range" in activeIds) {
                fun upsertStepRange(lo: Int, hi: Int) {
                    val cLo = lo.coerceIn(1, 180)
                    val cHi = hi.coerceIn(cLo, 200)
                    stepRangeMinVal = cLo
                    stepRangeMaxVal = cHi
                    ActiveStrategyStore.upsertChip(
                        WaveShieldChip("step_range", ctx.getString(R.string.shield_step_range_fmt, cLo, cHi), ShieldCategory.HAZARD_AND_PATH, paramInt = cLo or (cHi shl 8))
                    )
                }
                NumericStepperRow(
                    label = stringResource(R.string.step_range_min_label),
                    valueText = "$stepRangeMinVal",
                    onMinus = { upsertStepRange(stepRangeMinVal - 1, stepRangeMaxVal) },
                    onPlus = { upsertStepRange(stepRangeMinVal + 1, maxOf(stepRangeMaxVal, stepRangeMinVal + 1)) }
                )
                NumericStepperRow(
                    label = stringResource(R.string.step_range_max_label),
                    valueText = "$stepRangeMaxVal",
                    onMinus = { upsertStepRange(minOf(stepRangeMinVal, stepRangeMaxVal - 1), stepRangeMaxVal - 1) },
                    onPlus = { upsertStepRange(stepRangeMinVal, stepRangeMaxVal + 1) }
                )
            }

            // Compute Slider + Follow System
            val effBeam = if (cfg.useSystemBeam) settings.beamWidth else cfg.beamWidth
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${stringResource(R.string.slider_beam)} · $effBeam",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                CompactChoicePill(
                    label = stringResource(if (cfg.useSystemBeam) R.string.budget_follow_system else R.string.budget_override_strategy),
                    selected = cfg.useSystemBeam,
                    onClick = { updateCfg { it.copy(useSystemBeam = !it.useSystemBeam) } }
                )
            }
            SlimNativeSlider(
                value = effBeam.toFloat().coerceIn(40f, 600f),
                onValueChange = { v ->
                    val stepped = ((v / 20f).toInt() * 20).coerceIn(40, 600)
                    updateCfg { it.copy(beamWidth = stepped, useSystemBeam = false) }
                },
                valueRange = 40f..600f
            )

            // Strategy-Level Diagonal Move Override
            Text(stringResource(R.string.diag_move_strategy_label), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                CompactChoicePill(
                    label = stringResource(R.string.diag_follow_system),
                    selected = cfg.useSystemDiagonal,
                    onClick = { updateCfg { it.copy(useSystemDiagonal = true) } },
                    modifier = Modifier.weight(1f)
                )
                CompactChoicePill(
                    label = stringResource(R.string.diag_force_on),
                    selected = !cfg.useSystemDiagonal && cfg.allowDiagonalMove,
                    onClick = { updateCfg { it.copy(useSystemDiagonal = false, allowDiagonalMove = true) } },
                    modifier = Modifier.weight(1f)
                )
                CompactChoicePill(
                    label = stringResource(R.string.diag_force_off),
                    selected = !cfg.useSystemDiagonal && !cfg.allowDiagonalMove,
                    onClick = { updateCfg { it.copy(useSystemDiagonal = false, allowDiagonalMove = false) } },
                    modifier = Modifier.weight(1f)
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Start Position & Character Orb Pull
            Text(stringResource(R.string.section_start_char), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf(
                    StartPointPreset.ANY to stringResource(R.string.start_any),
                    StartPointPreset.BOTTOM_LEFT to stringResource(R.string.start_bl),
                    StartPointPreset.BOTTOM_RIGHT to stringResource(R.string.start_br)
                ).forEach { (sp, lbl) ->
                    CompactChoicePill(
                        label = lbl,
                        selected = cfg.startPreset == sp && "traffic_light" !in activeIds,
                        onClick = {
                            ActiveStrategyStore.removeChip("traffic_light")
                            updateCfg { it.copy(startPreset = sp) }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            val tlLabel = stringResource(R.string.shield_traffic_light)
            val tlActive = "traffic_light" in activeIds
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                CompactChoicePill(
                    label = tlLabel,
                    selected = tlActive,
                    aiHighlighted = isAiChip("traffic_light"),
                    onClick = { onToggleWaveChip(WaveShieldChip("traffic_light", tlLabel, ShieldCategory.HAZARD_AND_PATH)) },
                    modifier = Modifier.weight(1f)
                )
                CompactChoicePill(
                    label = stringResource(R.string.char_auto_buoy),
                    selected = cfg.autoDetectStartingBuoy,
                    onClick = { updateCfg { it.copy(autoDetectStartingBuoy = !it.autoDetectStartingBuoy) } },
                    modifier = Modifier.weight(1f)
                )
            }
            AnimatedVisibility(visible = tlActive) {
                Text(stringResource(R.string.traffic_light_hint), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                CompactChoicePill(
                    label = stringResource(R.string.char_off),
                    selected = !cfg.useCharacterOrb,
                    onClick = { updateCfg { it.copy(useCharacterOrb = false) } },
                    modifier = Modifier.weight(1.25f)
                )
                for (slot in 0 until 6) {
                    CompactChoicePill(
                        label = "${slot + 1}",
                        selected = cfg.useCharacterOrb && cfg.characterOrbCol == slot,
                        onClick = { updateCfg { it.copy(useCharacterOrb = true, characterOrbCol = slot) } },
                        modifier = Modifier.weight(0.9f)
                    )
                }
            }
            AnimatedVisibility(visible = cfg.useCharacterOrb) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    CompactChoicePill(
                        label = stringResource(R.string.char_elem_auto),
                        selected = cfg.characterOrbAutoElement,
                        onClick = { updateCfg { it.copy(characterOrbAutoElement = true) } },
                        modifier = Modifier.weight(1.25f)
                    )
                    listOf(Element.WATER, Element.FIRE, Element.WOOD, Element.LIGHT, Element.DARK).forEach { el ->
                        CompactChoicePill(
                            label = LocaleHelper.elementLabel(ctx, el.code),
                            selected = !cfg.characterOrbAutoElement && cfg.characterOrbElement == el,
                            onClick = { updateCfg { it.copy(characterOrbAutoElement = false, characterOrbElement = el) } },
                            modifier = Modifier.weight(0.9f)
                        )
                    }
                }
            }
        }

        // ==================== Drawer ⑥: Leader Skills & Solver Traits (Purple) ====================
        val isHeartAll = (cfg.compatibleLinkMask.getOrElse(5) { 1 shl 5 } and 0x3F) == 0x3F
        val isLightDark = ((cfg.compatibleLinkMask.getOrElse(3) { 1 shl 3 } and (1 shl 4)) != 0) && !isHeartAll
        val isWfg = ((cfg.compatibleLinkMask.getOrElse(0) { 1 shl 0 } and (1 shl 1)) != 0) && !isHeartAll
        val traitsSummary = buildList {
            when {
                isHeartAll -> add(stringResource(R.string.link_heart_all))
                isLightDark -> add(stringResource(R.string.link_light_dark))
                isWfg -> add(stringResource(R.string.link_wfg))
            }
            if (cfg.forceSplitThree) add(stringResource(R.string.tag_split_three))
            if (cfg.firstWaveOnlyScoring) add(stringResource(R.string.tag_first_only))
            if (cfg.allowDiagonalMatch) add(stringResource(R.string.tag_diag_match))
        }.joinToString(" · ").ifEmpty { stringResource(R.string.link_standard) }

        ColoredCollapsibleDrawerCard(
            title = stringResource(R.string.unified_group_leader_traits),
            accentColor = DrawerAccentColors.Purple,
            summaryText = traitsSummary,
            highlighted = isHeartAll || isLightDark || isWfg || cfg.forceSplitThree || cfg.firstWaveOnlyScoring || cfg.allowDiagonalMatch || cfg.preferEnchanted || cfg.preferRace,
            initialExpanded = true,
            onHelp = {
                onShowHelp(
                    ctx.getString(R.string.help_strategy_switches_title),
                    ctx.getString(R.string.help_leader_traits_full_body)
                )
            }
        ) {
            Text(stringResource(R.string.section_link_preset), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                CompactChoicePill(stringResource(R.string.link_standard), !isHeartAll && !isLightDark && !isWfg, {
                    updateCfg { it.copy(compatibleLinkMask = List(6) { idx -> 1 shl idx }) }
                })
                CompactChoicePill(stringResource(R.string.link_heart_all), isHeartAll, {
                    updateCfg { it.copy(compatibleLinkMask = List(6) { e -> if (e == 5) 0x3F else ((1 shl e) or (1 shl 5)) }) }
                })
                CompactChoicePill(stringResource(R.string.link_light_dark), isLightDark, {
                    val ld = (1 shl 3) or (1 shl 4)
                    updateCfg { it.copy(compatibleLinkMask = List(6) { e -> if (e == 3 || e == 4) ld else (1 shl e) }) }
                })
                CompactChoicePill(stringResource(R.string.link_wfg), isWfg, {
                    updateCfg { it.copy(compatibleLinkMask = List(6) { e -> if (e in 0..2) 0x07 else (1 shl e) }) }
                })
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            CompactSwitchLine(stringResource(R.string.switch_split_three), cfg.forceSplitThree) { v -> updateCfg { it.copy(forceSplitThree = v) } }
            CompactSwitchLine(stringResource(R.string.switch_first_wave_only), cfg.firstWaveOnlyScoring) { v -> updateCfg { it.copy(firstWaveOnlyScoring = v) } }
            CompactSwitchLine(stringResource(R.string.switch_diag_match), cfg.allowDiagonalMatch) { v -> updateCfg { it.copy(allowDiagonalMatch = v) } }
            CompactSwitchLine(stringResource(R.string.switch_prefer_ench), cfg.preferEnchanted) { v -> updateCfg { it.copy(preferEnchanted = v) } }
            CompactSwitchLine(stringResource(R.string.switch_prefer_race), cfg.preferRace) { v -> updateCfg { it.copy(preferRace = v) } }
        }

        Spacer(Modifier.height(6.dp))
    }
}

internal fun formatMatrixConditionSummary(ctx: android.content.Context, er: ElementRule): String {
    val parts = ArrayList<String>(3)
    parts.add(ctx.getString(R.string.min_match_fmt, er.minMatchCount))
    when {
        er.exactFirstWaveOrbs >= 0 -> parts.add("=${er.exactFirstWaveOrbs}")
        er.maxTotalOrbs >= 0 -> parts.add("≤${er.maxTotalOrbs}")
        er.minTotalOrbs > 0 || er.minFirstWaveOrbs > 0 -> parts.add("≥${maxOf(er.minTotalOrbs, er.minFirstWaveOrbs)}")
    }
    if (er.minFirstWaveCombos > 0) parts.add("${er.minFirstWaveCombos}C")
    if (er.requireEnchanted) parts.add("+")
    if (er.requireRace) parts.add(ctx.getString(R.string.race_short_god).take(1).let { "族" })
    return parts.joinToString("·")
}

@Composable
private fun MatrixHdr(text: String, modifier: Modifier) {
    Text(
        text = text,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier
    )
}

@Composable
private fun CompactDismissibleTag(
    label: String,
    accent: Color,
    isDark: Boolean,
    onDismiss: () -> Unit
) {
    val fg = DrawerAccentColors.foreground(accent, isDark)
    Surface(
        onClick = onDismiss,
        shape = RoundedCornerShape(8.dp),
        color = accent.copy(alpha = if (isDark) 0.22f else 0.12f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.55f))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text(text = label, fontSize = 11.sp, color = fg, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Outlined.Close, contentDescription = null, tint = fg, modifier = Modifier.size(12.dp))
        }
    }
}
