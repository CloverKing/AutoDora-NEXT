package com.autodora.next.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.autodora.next.R
import com.autodora.next.data.*
import com.autodora.next.model.*
import java.util.UUID

/**
 * Unified Strategy & Enemy-Shield Workbench (统一策略面板):
 *  - Replaces the separate Strategy List tab & Shields tab with ONE cohesive, deduplicated configuration page.
 *  - Top compact Preset Bar supports Load / Manage, Save, and Save As (named presets).
 *  - Organizes all team & shield parameters cleanly into 6 grouped cards below.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun UnifiedStrategyTab(
    settings: AppSettings,
    presets: List<TeamPresetRecord>,
    activeWaveChips: List<WaveShieldChip>,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onSelectPreset: (String) -> Unit,
    onSavePreset: (TeamBasePreset, Boolean) -> Unit,
    onSaveAsNewPreset: (String, String, RuleConfig) -> Unit,
    onDuplicatePreset: (TeamBasePreset) -> Unit,
    onDeletePreset: (String) -> Unit,
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
    val displayName = LocaleHelper.displayPresetName(ctx, activePreset, activeRecord.isBuiltIn)
    val displayDesc = LocaleHelper.displayPresetDesc(ctx, activePreset, activeRecord.isBuiltIn)

    fun updateCfg(transform: (RuleConfig) -> RuleConfig) {
        onSavePreset(activePreset.copy(baseConfig = transform(cfg)), activeRecord.isBuiltIn)
    }

    var showPresetManager by remember { mutableStateOf(false) }
    var showSaveAsDialog by remember { mutableStateOf(false) }

    var stepsText by remember(activePreset.id) { mutableStateOf(cfg.maxSteps.toString()) }
    LaunchedEffect(cfg.maxSteps) {
        if (stepsText.toIntOrNull() != cfg.maxSteps) stepsText = cfg.maxSteps.toString()
    }

    val byId = remember(activeWaveChips) { activeWaveChips.associateBy { it.id } }
    val activeIds = remember(activeWaveChips) { activeWaveChips.map { it.id }.toSet() }

    // Synchronized working numeric parameters for wave shields
    var minTotalComboVal by remember { mutableStateOf(byId["min_combo"]?.paramInt ?: 6) }
    var minFirstComboVal by remember { mutableStateOf(byId["min_first_combo"]?.paramInt ?: 5) }
    var exactTotalComboVal by remember { mutableStateOf(byId["exact_combo"]?.paramInt ?: 6) }
    var exactFirstComboVal by remember { mutableStateOf(byId["exact_first_combo"]?.paramInt ?: 4) }
    var parityFirstOnly by remember {
        mutableStateOf((byId["odd_combo"]?.paramInt ?: byId["even_combo"]?.paramInt ?: 0) != 1)
    }
    var secondWaveVal by remember { mutableStateOf(byId["second_wave_combo"]?.paramInt ?: 1) }
    var sameElemGroupsVal by remember { mutableStateOf(byId["same_elem_combos"]?.paramInt ?: 3) }
    var sameElemTarget by remember {
        val m = byId["same_elem_combos"]?.paramElementMask ?: 0
        mutableStateOf((0..5).firstOrNull { (m and (1 shl it)) != 0 } ?: -1)
    }
    val initialPairPacked = byId["equal_pair_light_dark"]?.paramElementMask ?: ((4 shl 4) or 3)
    var pairElemA by remember { mutableStateOf((initialPairPacked and 0x0F).coerceIn(0, 5)) }
    var pairElemB by remember { mutableStateOf(((initialPairPacked ushr 4) and 0x0F).coerceIn(0, 5)) }
    var burstOrbsVal by remember { mutableStateOf(byId["elem_burst_shield"]?.paramInt?.coerceIn(3, 15) ?: 5) }
    var elemLimitMode by remember { mutableStateOf(0) }
    var digitalOrbs by remember { mutableStateOf(byId["digital_orb_count"]?.paramInt ?: 18) }
    var maxBurnHurts by remember { mutableStateOf(((byId["zero_trail_burning"]?.paramInt ?: 0) ushr 8) and 0xFF) }
    var burnTrailLen by remember { mutableStateOf((byId["zero_trail_burning"]?.paramInt ?: 0) and 0xFF) }
    var stepLimitVal by remember { mutableStateOf(byId["step_limit"]?.paramInt ?: 15) }

    LaunchedEffect(byId["min_combo"]?.paramInt) { byId["min_combo"]?.paramInt?.let { if (it in 1..15) minTotalComboVal = it } }
    LaunchedEffect(byId["min_first_combo"]?.paramInt) { byId["min_first_combo"]?.paramInt?.let { if (it in 1..15) minFirstComboVal = it } }
    LaunchedEffect(byId["exact_combo"]?.paramInt) { byId["exact_combo"]?.paramInt?.let { if (it in 1..15) exactTotalComboVal = it } }
    LaunchedEffect(byId["exact_first_combo"]?.paramInt) { byId["exact_first_combo"]?.paramInt?.let { if (it in 1..15) exactFirstComboVal = it } }
    LaunchedEffect(byId["elem_burst_shield"]?.paramInt) { byId["elem_burst_shield"]?.paramInt?.let { if (it in 3..15) burstOrbsVal = it } }
    LaunchedEffect(byId["digital_orb_count"]?.paramInt) { byId["digital_orb_count"]?.paramInt?.let { if (it in 6..30) digitalOrbs = it } }
    LaunchedEffect(byId["step_limit"]?.paramInt) { byId["step_limit"]?.paramInt?.let { if (it in 2..60) stepLimitVal = it } }

    val legacyReqIds = remember {
        buildSet {
            add("require_elements"); add("three_elements"); add("five_elements"); add("five_elements_enchanted")
            for (e in 0..5) { add("require_elem_$e"); add("require_ench_$e") }
        }
    }

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
            TargetShape.ANY, TargetShape.COUNT_2, TargetShape.COUNT_4, TargetShape.COUNT_5,
            TargetShape.ROW, TargetShape.COUNT_6, TargetShape.COUNT_7, TargetShape.COUNT_8,
            TargetShape.COUNT_9, TargetShape.CLEAR_ALL
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ==================== Top Compact Preset Save / Load Bar ====================
        ElevatedCard(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 6.dp)) {
                        Text(
                            text = stringResource(R.string.overview_current_strategy),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (displayDesc.isNotBlank()) {
                            Text(
                                text = displayDesc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { showPresetManager = true },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.weight(1.3f).height(34.dp)
                    ) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.preset_bar_load), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                    FilledTonalButton(
                        onClick = {
                            onSavePreset(activePreset, activeRecord.isBuiltIn)
                            Toast.makeText(ctx, ctx.getString(R.string.toast_preset_saved), Toast.LENGTH_SHORT).show()
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.weight(0.9f).height(34.dp)
                    ) {
                        Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(stringResource(R.string.preset_bar_save), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { showSaveAsDialog = true },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.weight(0.9f).height(34.dp)
                    ) {
                        Icon(Icons.Outlined.SaveAs, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(stringResource(R.string.preset_bar_save_as), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }
            }
        }

        // ==================== Active Wave Shields Banner ====================
        if (activeWaveChips.isNotEmpty()) {
            ElevatedCard(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.unified_enemy_banner_title, activeWaveChips.size),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        TextButton(
                            onClick = onClearWaveChips,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text(
                                stringResource(R.string.unified_enemy_banner_clear),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (chip in activeWaveChips) {
                            InputChip(
                                selected = true,
                                onClick = { onToggleWaveChip(chip) },
                                label = {
                                    Text(
                                        LocaleHelper.formatWaveChipLabel(ctx, chip),
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                },
                                trailingIcon = {
                                    Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                                }
                            )
                        }
                    }
                }
            }
        }

        // ==================== Group 1: Budget, Compute & Start ====================
        SectionHeaderWithHelp(
            text = stringResource(R.string.unified_group_budget),
            onHelp = {
                onShowHelp(
                    ctx.getString(R.string.help_basic_steps_title),
                    ctx.getString(R.string.help_basic_steps_body) + "\n\n" + ctx.getString(R.string.help_start_char_body)
                )
            }
        )
        ElevatedCard(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Strategy Steps + Follow System
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = stepsText,
                        onValueChange = { raw ->
                            val d = raw.filter { it.isDigit() }.take(3)
                            stepsText = d
                            val v = d.toIntOrNull()
                            if (v != null && v in 8..300) {
                                updateCfg { it.copy(maxSteps = v, useSystemSteps = false) }
                            }
                        },
                        label = { Text(stringResource(R.string.field_strategy_steps)) },
                        suffix = { Text(stringResource(R.string.unit_steps)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    CompactChoicePill(
                        label = stringResource(if (cfg.useSystemSteps) R.string.budget_follow_system else R.string.budget_override_strategy),
                        selected = cfg.useSystemSteps,
                        onClick = { updateCfg { it.copy(useSystemSteps = !it.useSystemSteps) } }
                    )
                }

                // Temporary Wave Hard Step Limit (限 N 步内)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val stepLbl = stringResource(R.string.shield_step_limit_fmt, stepLimitVal)
                    CompactChoicePill(
                        label = stepLbl,
                        selected = "step_limit" in activeIds,
                        onClick = {
                            onToggleWaveChip(WaveShieldChip("step_limit", stepLbl, ShieldCategory.HAZARD_AND_PATH, paramInt = stepLimitVal))
                        }
                    )
                    if ("step_limit" in activeIds) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilledTonalButton(
                                onClick = {
                                    stepLimitVal = (stepLimitVal - 1).coerceIn(2, 60)
                                    ActiveStrategyStore.upsertChip(
                                        WaveShieldChip("step_limit", ctx.getString(R.string.shield_step_limit_fmt, stepLimitVal), ShieldCategory.HAZARD_AND_PATH, paramInt = stepLimitVal)
                                    )
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                modifier = Modifier.height(28.dp)
                            ) { Text("−") }
                            Text("≤$stepLimitVal", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            FilledTonalButton(
                                onClick = {
                                    stepLimitVal = (stepLimitVal + 1).coerceIn(2, 60)
                                    ActiveStrategyStore.upsertChip(
                                        WaveShieldChip("step_limit", ctx.getString(R.string.shield_step_limit_fmt, stepLimitVal), ShieldCategory.HAZARD_AND_PATH, paramInt = stepLimitVal)
                                    )
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                modifier = Modifier.height(28.dp)
                            ) { Text("+") }
                        }
                    }
                }

                // Unified Compute Slider (40..600) + Follow System
                val effBeam = if (cfg.useSystemBeam) settings.beamWidth else cfg.beamWidth
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${stringResource(R.string.slider_beam)} · $effBeam",
                        style = MaterialTheme.typography.labelMedium,
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

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Start Position & Character Orb Pull
                Text(
                    text = stringResource(R.string.section_start_char),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
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
                CompactChoicePill(
                    label = tlLabel,
                    selected = tlActive,
                    onClick = {
                        onToggleWaveChip(WaveShieldChip("traffic_light", tlLabel, ShieldCategory.HAZARD_AND_PATH))
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                AnimatedVisibility(visible = tlActive) {
                    Text(
                        text = stringResource(R.string.traffic_light_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    CompactChoicePill(
                        label = stringResource(R.string.char_off),
                        selected = !cfg.useCharacterOrb,
                        onClick = { updateCfg { it.copy(useCharacterOrb = false) } },
                        modifier = Modifier.weight(1.3f)
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
            }
        }

        // ==================== Group 2: Global Goals & Combo Rules ====================
        SectionHeaderWithHelp(
            text = stringResource(R.string.unified_group_goal_combo),
            onHelp = {
                onShowHelp(
                    ctx.getString(R.string.help_combo_shield_title),
                    ctx.getString(R.string.help_goal_policy_body) + "\n\n" + ctx.getString(R.string.help_combo_shield_body)
                )
            }
        )
        ElevatedCard(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 2a. Grouped Global Matching Goal (N / X)
                Text(
                    text = stringResource(R.string.section_goal_mode),
                    style = MaterialTheme.typography.labelMedium,
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
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
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
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (goalGroup == StrategyGoalMode.N_COLOR_BALANCE ||
                                goalGroup == StrategyGoalMode.N_COLOR_ALL ||
                                goalGroup == StrategyGoalMode.MULTI_COLOR_X_ORBS
                            ) {
                                val nRange = if (goalGroup == StrategyGoalMode.N_COLOR_ALL) 2..6 else 2..5
                                Text(stringResource(R.string.goal_param_n_label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
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
                                Text(stringResource(R.string.goal_param_x_label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                Text(stringResource(R.string.goal_target_elem_label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    CompactChoicePill(
                                        label = stringResource(R.string.puzzle_elem_any),
                                        selected = cfg.goalTargetElement < 0,
                                        onClick = { updateCfg { it.copy(goalMode = goalGroup, goalTargetElement = -1) } },
                                        modifier = Modifier.weight(1.65f).height(34.dp)
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

                // 2b. Multi-Orb Policy & Search Style
                Text(
                    text = stringResource(R.string.section_match_policy),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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

                // 2c. Combo Shield Primary Exclusive Rule
                Text(
                    text = stringResource(R.string.section_combo_shield),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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

                fun applyComboType(mode: String) {
                    ActiveStrategyStore.removeChip("first_wave_only")
                    val chip = when (mode) {
                        "min_combo" -> WaveShieldChip("min_combo", "${ctx.getString(R.string.combo_type_min_total)} ≥$minTotalComboVal", ShieldCategory.COMBO, paramInt = minTotalComboVal)
                        "min_first_combo" -> WaveShieldChip("min_first_combo", "${ctx.getString(R.string.combo_type_min_first)} ≥$minFirstComboVal", ShieldCategory.COMBO, paramInt = minFirstComboVal)
                        "exact_combo" -> WaveShieldChip("exact_combo", "${ctx.getString(R.string.combo_type_exact_total)} $exactTotalComboVal", ShieldCategory.COMBO, paramInt = exactTotalComboVal)
                        "exact_first_combo" -> WaveShieldChip("exact_first_combo", "${ctx.getString(R.string.combo_type_exact_first)} $exactFirstComboVal", ShieldCategory.COMBO, paramInt = exactFirstComboVal)
                        "zero_combo" -> {
                            listOf(
                                "same_combo_elements", "second_wave_combo", "same_elem_combos",
                                "equal_pair_light_dark", "first_wave_full_clear", "digital_orb_count",
                                "elem_burst_shield", "any_enchanted_orb_shield", "race_orb_shield",
                                "char_orb_first_wave", "luminous_orb_shield"
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
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "none" to stringResource(R.string.combo_type_none),
                        "min_combo" to stringResource(R.string.combo_type_min_total),
                        "min_first_combo" to stringResource(R.string.combo_type_min_first),
                        "exact_combo" to stringResource(R.string.combo_type_exact_total),
                        "exact_first_combo" to stringResource(R.string.combo_type_exact_first),
                        "zero_combo" to stringResource(R.string.combo_type_zero),
                        "odd_combo" to stringResource(R.string.combo_type_odd),
                        "even_combo" to stringResource(R.string.combo_type_even)
                    ).forEach { (id, label) ->
                        CompactChoicePill(
                            label = label,
                            selected = curComboMode == id,
                            onClick = { applyComboType(id) }
                        )
                    }
                }

                if (curComboMode != "none" && curComboMode != "zero_combo") {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            when (curComboMode) {
                                "min_combo" -> NumericStepperRow(
                                    label = stringResource(R.string.combo_param_min_total),
                                    valueText = stringResource(R.string.combo_value_min_fmt, minTotalComboVal),
                                    onMinus = { minTotalComboVal = (minTotalComboVal - 1).coerceIn(1, 15); applyComboType("min_combo") },
                                    onPlus = { minTotalComboVal = (minTotalComboVal + 1).coerceIn(1, 15); applyComboType("min_combo") }
                                )
                                "min_first_combo" -> NumericStepperRow(
                                    label = stringResource(R.string.combo_param_min_first),
                                    valueText = stringResource(R.string.combo_value_min_fmt, minFirstComboVal),
                                    onMinus = { minFirstComboVal = (minFirstComboVal - 1).coerceIn(1, 12); applyComboType("min_first_combo") },
                                    onPlus = { minFirstComboVal = (minFirstComboVal + 1).coerceIn(1, 12); applyComboType("min_first_combo") }
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
                                "odd_combo", "even_combo" -> Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
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

                // 2d. Structural Combo Modifiers
                AnimatedVisibility(visible = curComboMode != "zero_combo") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.combo_extra_header),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        fun upsertSecondWave(n: Int) {
                            ActiveStrategyStore.upsertChip(WaveShieldChip("second_wave_combo", ctx.getString(R.string.combo_extra_second_wave, n), ShieldCategory.COMBO, paramInt = n))
                        }
                        fun upsertSameElem(n: Int, targetAttr: Int) {
                            val mask = if (targetAttr in 0..5) (1 shl targetAttr) else 0
                            ActiveStrategyStore.upsertChip(WaveShieldChip("same_elem_combos", ctx.getString(R.string.combo_extra_same_elem, n), ShieldCategory.COMBO, paramInt = n, paramElementMask = mask))
                        }
                        fun upsertEqualPair(a: Int, b: Int) {
                            val packed = (a and 0x0F) or ((b and 0x0F) shl 4)
                            ActiveStrategyStore.upsertChip(WaveShieldChip("equal_pair_light_dark", ctx.getString(R.string.combo_extra_equal_pair, LocaleHelper.elementLabel(ctx, a), LocaleHelper.elementLabel(ctx, b)), ShieldCategory.COMBO, paramElementMask = packed))
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val sameCLabel = stringResource(R.string.combo_extra_same_c)
                            CompactChoicePill(
                                label = sameCLabel,
                                selected = "same_combo_elements" in activeIds,
                                onClick = { onToggleWaveChip(WaveShieldChip("same_combo_elements", sameCLabel, ShieldCategory.COMBO)) }
                            )
                            CompactChoicePill(
                                label = stringResource(R.string.combo_extra_second_wave, secondWaveVal),
                                selected = "second_wave_combo" in activeIds,
                                onClick = {
                                    if ("second_wave_combo" in activeIds) ActiveStrategyStore.removeChip("second_wave_combo")
                                    else upsertSecondWave(secondWaveVal)
                                }
                            )
                            CompactChoicePill(
                                label = stringResource(R.string.combo_extra_same_elem, sameElemGroupsVal),
                                selected = "same_elem_combos" in activeIds,
                                onClick = {
                                    if ("same_elem_combos" in activeIds) ActiveStrategyStore.removeChip("same_elem_combos")
                                    else upsertSameElem(sameElemGroupsVal, sameElemTarget)
                                }
                            )
                            CompactChoicePill(
                                label = stringResource(R.string.combo_extra_equal_pair, LocaleHelper.elementLabel(ctx, pairElemA), LocaleHelper.elementLabel(ctx, pairElemB)),
                                selected = "equal_pair_light_dark" in activeIds,
                                onClick = {
                                    if ("equal_pair_light_dark" in activeIds) ActiveStrategyStore.removeChip("equal_pair_light_dark")
                                    else upsertEqualPair(pairElemA, pairElemB)
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
                                    modifier = Modifier.weight(1.65f).height(34.dp)
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
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                for (e in 0 until 6) {
                                    ElementColorTogglePill(
                                        elemIndex = e,
                                        selected = e == pairElemA || e == pairElemB,
                                        onClick = {
                                            if (e != pairElemA && e != pairElemB) {
                                                pairElemA = pairElemB; pairElemB = e
                                                upsertEqualPair(pairElemA, pairElemB)
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
        }

        // ==================== Group 3: Attributes, Orb Counts & 6x4 Per-Element Matrix ====================
        SectionHeaderWithHelp(
            text = stringResource(R.string.unified_group_elem_count),
            onHelp = {
                onShowHelp(
                    ctx.getString(R.string.help_elem_count_title),
                    ctx.getString(R.string.help_elem_count_body) + "\n\n" + ctx.getString(R.string.help_elem_matrix_body)
                )
            }
        )
        ElevatedCard(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 3a. Min Distinct Elements
                Text(stringResource(R.string.elem_min_distinct_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val curMinDistinct = byId["min_distinct_elements"]?.paramInt ?: 0
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (cnt in listOf(0, 2, 3, 4, 5, 6)) {
                        CompactChoicePill(
                            label = if (cnt == 0) stringResource(R.string.elem_min_distinct_any) else stringResource(R.string.elem_min_distinct_fmt, cnt),
                            selected = curMinDistinct == cnt,
                            onClick = {
                                if (curMinDistinct != cnt) ActiveStrategyStore.replaceGroup(legacyReqIds, null)
                                if (cnt == 0) ActiveStrategyStore.removeChip("min_distinct_elements")
                                else ActiveStrategyStore.upsertChip(WaveShieldChip("min_distinct_elements", ctx.getString(R.string.elem_min_distinct_fmt, cnt), ShieldCategory.COUNT_AND_ELEMENT, paramInt = cnt))
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // 3b. Required Elements + Independent Per-Color Enchanted Row
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

                Text(stringResource(R.string.elem_req_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
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
                Text(stringResource(R.string.elem_req_ench_row), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
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

                // 3c. Single-Color Burst / Cumulative Orb Shield (ICON1255..1259, 1502 auto-mapped here!)
                val burstChip = byId["elem_burst_shield"]
                val burstMask = (burstChip?.paramElementMask ?: 0) and 0x3F
                fun saveBurst(mask: Int, orbs: Int) {
                    if (mask == 0) {
                        ActiveStrategyStore.removeChip("elem_burst_shield")
                    } else {
                        ActiveStrategyStore.removeChip("zero_combo")
                        val names = (0..5).filter { (mask and (1 shl it)) != 0 }.joinToString("") { LocaleHelper.elementLabel(ctx, it) }
                        ActiveStrategyStore.upsertChip(
                            WaveShieldChip(
                                id = "elem_burst_shield",
                                label = ctx.getString(R.string.elem_burst_fmt, names, orbs),
                                category = ShieldCategory.COUNT_AND_ELEMENT,
                                paramInt = orbs,
                                paramElementMask = mask
                            )
                        )
                    }
                }
                Text(stringResource(R.string.elem_burst_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
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
                        onMinus = { burstOrbsVal = (burstOrbsVal - 1).coerceIn(3, 15); saveBurst(burstMask, burstOrbsVal) },
                        onPlus = { burstOrbsVal = (burstOrbsVal + 1).coerceIn(3, 15); saveBurst(burstMask, burstOrbsVal) }
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // 3d. Attribute Elimination Limits (Anti-1st / Forbid All / Clear All 1st)
                Text(stringResource(R.string.elem_forbid_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        Triple(0, stringResource(R.string.elem_limit_anti), Integer.bitCount(antiMask)),
                        Triple(1, stringResource(R.string.elem_limit_forbid), Integer.bitCount(forbidMask)),
                        Triple(2, stringResource(R.string.elem_limit_clear_all), Integer.bitCount(clearMask))
                    ).forEach { (idx, lbl, cnt) ->
                        CompactChoicePill(
                            label = if (cnt > 0) "$lbl ($cnt)" else lbl,
                            selected = elemLimitMode == idx,
                            onClick = { elemLimitMode = idx },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                val curLimitMask = when (elemLimitMode) { 0 -> antiMask; 1 -> forbidMask; else -> clearMask }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
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
                                    else -> {
                                        writeMaskForLimit("clear_all_element", clearLbl, nextMask)
                                        if (turningOn) {
                                            if ((antiMask and bit) != 0) writeMaskForLimit("anti_first_wave", antiLbl, antiMask and bit.inv())
                                            if ((forbidMask and bit) != 0) writeMaskForLimit("forbid_all", forbidLbl, forbidMask and bit.inv())
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // 3e. Orb Count & 5 Special Runestone Shields
                Text(stringResource(R.string.elem_count_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val digLabel = stringResource(R.string.shield_digital_orbs_fmt, digitalOrbs)
                    CompactChoicePill(
                        label = digLabel,
                        selected = "digital_orb_count" in activeIds,
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
                        "forbid_weakened_orb" to stringResource(R.string.shield_forbid_weakened),
                        "luminous_orb_shield" to stringResource(R.string.shield_luminous_orb)
                    ).forEach { (id, lbl) ->
                        CompactChoicePill(
                            label = lbl,
                            selected = id in activeIds,
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

                // 3f. 6x4 Per-Element Strategy Rules Matrix
                Text(stringResource(R.string.section_elem_matrix), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MatrixHdr(stringResource(R.string.col_hdr_elem), Modifier.width(34.dp))
                    MatrixHdr(stringResource(R.string.col_hdr_shape), Modifier.weight(1.1f))
                    MatrixHdr(stringResource(R.string.col_hdr_priority), Modifier.weight(0.95f))
                    MatrixHdr(stringResource(R.string.col_hdr_timing), Modifier.weight(0.95f))
                    MatrixHdr(stringResource(R.string.col_hdr_min), Modifier.weight(0.85f))
                }
                for (e in 0 until 6) {
                    val er = cfg.elementRules.getOrElse(e) { ElementRule() }
                    val badgeColor = Color(LocaleHelper.elementBadgeArgb(e))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = badgeColor,
                            modifier = Modifier.width(34.dp).height(34.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(LocaleHelper.elementLabel(ctx, e), color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            }
                        }
                        CompactChoicePill(
                            label = LocaleHelper.targetShapeLabel(ctx, er.targetShape),
                            selected = er.targetShape != TargetShape.ANY,
                            onClick = {
                                val nxt = shapeOrder[(shapeOrder.indexOf(er.targetShape) + 1).coerceAtLeast(0) % shapeOrder.size]
                                updateCfg { cur -> cur.copy(elementRules = cur.elementRules.toMutableList().also { it[e] = er.copy(targetShape = nxt) }) }
                            },
                            modifier = Modifier.weight(1.1f)
                        )
                        CompactChoicePill(
                            label = LocaleHelper.elementPriorityLabel(ctx, er.priority),
                            selected = er.priority != ElementPriority.NORMAL,
                            onClick = {
                                val nxt = when (er.priority) {
                                    ElementPriority.NORMAL -> ElementPriority.HIGH
                                    ElementPriority.HIGH -> ElementPriority.RESERVE
                                    ElementPriority.RESERVE -> ElementPriority.NORMAL
                                }
                                updateCfg { cur -> cur.copy(elementRules = cur.elementRules.toMutableList().also { it[e] = er.copy(priority = nxt) }) }
                            },
                            modifier = Modifier.weight(0.95f)
                        )
                        CompactChoicePill(
                            label = LocaleHelper.matchTimingLabel(ctx, er.timing),
                            selected = er.timing != MatchTiming.ANY,
                            onClick = {
                                val nxt = when (er.timing) {
                                    MatchTiming.ANY -> MatchTiming.MUST_FIRST_WAVE
                                    MatchTiming.MUST_FIRST_WAVE -> MatchTiming.FORBID_FIRST_WAVE_ALLOW_CASCADE
                                    MatchTiming.FORBID_FIRST_WAVE_ALLOW_CASCADE -> MatchTiming.FORBID_ALL
                                    MatchTiming.FORBID_ALL -> MatchTiming.ANY
                                }
                                updateCfg { cur -> cur.copy(elementRules = cur.elementRules.toMutableList().also { it[e] = er.copy(timing = nxt) }) }
                            },
                            modifier = Modifier.weight(0.95f)
                        )
                        CompactChoicePill(
                            label = stringResource(R.string.min_match_fmt, er.minMatchCount),
                            selected = er.minMatchCount != 3,
                            onClick = {
                                val nxt = when (er.minMatchCount) { 3 -> 2; 2 -> 4; else -> 3 }
                                updateCfg { cur -> cur.copy(elementRules = cur.elementRules.toMutableList().also { it[e] = er.copy(minMatchCount = nxt) }) }
                            },
                            modifier = Modifier.weight(0.85f)
                        )
                    }
                }
            }
        }

        // ==================== Group 4: Puzzle, Shapes & Column Amelioration ====================
        SectionHeaderWithHelp(
            text = stringResource(R.string.unified_group_puzzle_shape),
            onHelp = {
                onShowHelp(
                    ctx.getString(R.string.help_puzzle_shape_title),
                    ctx.getString(R.string.help_puzzle_shape_body) + "\n\n" + ctx.getString(R.string.help_columns_body)
                )
            }
        )
        ElevatedCard(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
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

                fun savePuzzle(mode: Int, ench: Boolean, elem: Int, shapeId: Int) {
                    if (mode == 0) { ActiveStrategyStore.replaceGroup(WaveShieldCatalog.PUZZLE_EXCLUSIVE_IDS, null); return }
                    val packed = (if (mode == 2) 1 else 0) or (if (ench) 2 else 0) or (if (elem in 0..5) ((elem + 1) shl 2) else 0) or ((shapeId and 0x0F) shl 8)
                    ActiveStrategyStore.replaceGroup(
                        WaveShieldCatalog.PUZZLE_EXCLUSIVE_IDS,
                        WaveShieldChip("puzzle_shield", ctx.getString(if (mode == 2) R.string.puzzle_mode_strict else R.string.puzzle_mode_normal), ShieldCategory.SHAPE_AND_PUZZLE, paramInt = packed)
                    )
                }

                Text(stringResource(R.string.puzzle_mode_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CompactChoicePill(stringResource(R.string.puzzle_mode_off), puzzleMode == 0, { savePuzzle(0, false, -1, 0) }, Modifier.weight(1f))
                    CompactChoicePill(stringResource(R.string.puzzle_mode_normal), puzzleMode == 1, { savePuzzle(1, puzzleEnch, puzzleElem, puzzleShapeId) }, Modifier.weight(1f))
                    CompactChoicePill(stringResource(R.string.puzzle_mode_strict), puzzleMode == 2, { savePuzzle(2, puzzleEnch, puzzleElem, puzzleShapeId) }, Modifier.weight(1f))
                }
                AnimatedVisibility(visible = puzzleMode > 0) {
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(stringResource(R.string.puzzle_ench_switch), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                Switch(checked = puzzleEnch, onCheckedChange = { savePuzzle(puzzleMode, it, puzzleElem, puzzleShapeId) })
                            }
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                CompactChoicePill(stringResource(R.string.puzzle_elem_any), puzzleElem < 0, { savePuzzle(puzzleMode, puzzleEnch, -1, puzzleShapeId) }, Modifier.weight(1.65f).height(34.dp))
                                for (e in 0 until 6) {
                                    ElementColorTogglePill(e, puzzleElem == e, { savePuzzle(puzzleMode, puzzleEnch, e, puzzleShapeId) }, Modifier.weight(1f))
                                }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (shapeId in 0..6) {
                                    CompactChoicePill(LocaleHelper.puzzleShapeLabel(ctx, shapeId), puzzleShapeId == shapeId, { savePuzzle(puzzleMode, puzzleEnch, puzzleElem, shapeId) })
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Independent Shape Shields
                Text(stringResource(R.string.shape_shield_label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val crossChip = byId["cross_shield"] ?: byId["cross_shield_2"] ?: byId["cross_shield_3"]
                val curCrossCount = (crossChip?.paramInt ?: 0) and 0x0F
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
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf(0 to stringResource(R.string.cross_off), 1 to stringResource(R.string.cross_1), 2 to stringResource(R.string.cross_2), 3 to stringResource(R.string.cross_3)).forEach { (cnt, lbl) ->
                        CompactChoicePill(
                            label = lbl,
                            selected = curCrossCount == cnt,
                            onClick = {
                                val nextColors = if (cnt != curCrossCount && Integer.bitCount(curCrossColorsMask) > cnt) 0 else curCrossColorsMask
                                saveCross(cnt, curCrossDistinct && cnt >= 2, nextColors)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                AnimatedVisibility(visible = curCrossCount > 0) {
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (curCrossCount >= 2) {
                                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(stringResource(R.string.cross_distinct_switch), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                    Switch(checked = curCrossDistinct, onCheckedChange = {
                                        val nextColors = if (!it && Integer.bitCount(curCrossColorsMask) >= 2) 0 else curCrossColorsMask
                                        saveCross(curCrossCount, it, nextColors)
                                    })
                                }
                            }
                            Text(stringResource(R.string.cross_colors_label, curCrossCount), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
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
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        WaveShieldChip("l_shape", stringResource(R.string.shield_l_shape), ShieldCategory.SHAPE_AND_PUZZLE),
                        WaveShieldChip("t_shape", stringResource(R.string.shield_t_shape), ShieldCategory.SHAPE_AND_PUZZLE),
                        WaveShieldChip("square_2x2", stringResource(R.string.shield_sq_2x2), ShieldCategory.SHAPE_AND_PUZZLE),
                        WaveShieldChip("all_columns_ameliorate", stringResource(R.string.shield_all_cols), ShieldCategory.SHAPE_AND_PUZZLE)
                    ).forEach { chip ->
                        CompactChoicePill(chip.label, chip.id in activeIds, { onToggleWaveChip(chip) })
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Column Amelioration 1..6 (2x3 Grid)
                Text(stringResource(R.string.section_columns), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                for (rowIdx in 0..1) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
            }
        }

        // ==================== Group 5: Path, Terrain & Safety ====================
        SectionHeaderWithHelp(
            text = stringResource(R.string.unified_group_path_safety),
            onHelp = {
                onShowHelp(
                    ctx.getString(R.string.help_path_hazard_title),
                    ctx.getString(R.string.help_path_hazard_body)
                )
            }
        )
        ElevatedCard(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
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
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CompactChoicePill(stringResource(R.string.burn_len_max), burnTrailLen == 0, { burnTrailLen = 0; upsertBurn(maxBurnHurts, 0) }, Modifier.weight(1f))
                        CompactChoicePill(stringResource(R.string.burn_len_6), burnTrailLen == 6, { burnTrailLen = 6; upsertBurn(maxBurnHurts, 6) }, Modifier.weight(1f))
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        WaveShieldChip("traffic_light", stringResource(R.string.shield_traffic_light), ShieldCategory.HAZARD_AND_PATH),
                        WaveShieldChip("trail_corrosion", stringResource(R.string.shield_trail_corrosion), ShieldCategory.HAZARD_AND_PATH, paramInt = 15),
                        WaveShieldChip("step_hurt_minimize", stringResource(R.string.shield_step_hurt), ShieldCategory.HAZARD_AND_PATH),
                        WaveShieldChip("char_orb_terrain", stringResource(R.string.shield_char_terrain), ShieldCategory.HAZARD_AND_PATH),
                        WaveShieldChip("weijian_burst", stringResource(R.string.shield_weijian_burst), ShieldCategory.HAZARD_AND_PATH)
                    ).forEach { chip ->
                        CompactChoicePill(chip.label, chip.id in activeIds, { onToggleWaveChip(chip) })
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                CompactSwitchLine(
                    title = stringResource(R.string.switch_hit_weathered),
                    checked = cfg.allowHitWeatheredToClear,
                    onCheckedChange = { v -> updateCfg { it.copy(allowHitWeatheredToClear = v) } }
                )
            }
        }

        // ==================== Group 6: Leader Skills & Solver Traits ====================
        SectionHeaderWithHelp(
            text = stringResource(R.string.unified_group_leader_traits),
            onHelp = {
                onShowHelp(
                    ctx.getString(R.string.help_strategy_switches_title),
                    ctx.getString(R.string.help_strategy_switches_body)
                )
            }
        )
        ElevatedCard(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(stringResource(R.string.section_link_preset), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val isHeartAll = (cfg.compatibleLinkMask.getOrElse(5) { 1 shl 5 } and 0x3F) == 0x3F
                val isLightDark = ((cfg.compatibleLinkMask.getOrElse(3) { 1 shl 3 } and (1 shl 4)) != 0) && !isHeartAll
                val isWfg = ((cfg.compatibleLinkMask.getOrElse(0) { 1 shl 0 } and (1 shl 1)) != 0) && !isHeartAll
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    CompactChoicePill(stringResource(R.string.link_standard), !isHeartAll && !isLightDark && !isWfg, {
                        updateCfg { it.copy(compatibleLinkMask = List(6) { idx -> 1 shl idx }) }
                    }, Modifier.weight(1f))
                    CompactChoicePill(stringResource(R.string.link_heart_all), isHeartAll, {
                        updateCfg { it.copy(compatibleLinkMask = List(6) { e -> if (e == 5) 0x3F else ((1 shl e) or (1 shl 5)) }) }
                    }, Modifier.weight(1f))
                    CompactChoicePill(stringResource(R.string.link_light_dark), isLightDark, {
                        val ld = (1 shl 3) or (1 shl 4)
                        updateCfg { it.copy(compatibleLinkMask = List(6) { e -> if (e == 3 || e == 4) ld else (1 shl e) }) }
                    }, Modifier.weight(1f))
                    CompactChoicePill(stringResource(R.string.link_wfg), isWfg, {
                        updateCfg { it.copy(compatibleLinkMask = List(6) { e -> if (e in 0..2) 0x07 else (1 shl e) }) }
                    }, Modifier.weight(1f))
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                CompactSwitchLine(stringResource(R.string.switch_split_three), cfg.forceSplitThree) { v -> updateCfg { it.copy(forceSplitThree = v) } }
                CompactSwitchLine(stringResource(R.string.switch_first_wave_only), cfg.firstWaveOnlyScoring) { v -> updateCfg { it.copy(firstWaveOnlyScoring = v) } }
                CompactSwitchLine(stringResource(R.string.switch_diag_match), cfg.allowDiagonalMatch) { v -> updateCfg { it.copy(allowDiagonalMatch = v) } }
                CompactSwitchLine(stringResource(R.string.switch_prefer_ench), cfg.preferEnchanted) { v -> updateCfg { it.copy(preferEnchanted = v) } }
                CompactSwitchLine(stringResource(R.string.switch_prefer_race), cfg.preferRace) { v -> updateCfg { it.copy(preferRace = v) } }
            }
        }

        Spacer(Modifier.height(8.dp))
    }

    // ==================== Preset Load / Manage Dialog ====================
    if (showPresetManager) {
        StrategyPresetManagerDialog(
            presets = items,
            activePresetId = settings.activePresetId,
            onDismiss = { showPresetManager = false },
            onSelect = { id, label ->
                onSelectPreset(id)
                Toast.makeText(ctx, ctx.getString(R.string.toast_preset_loaded, label), Toast.LENGTH_SHORT).show()
                showPresetManager = false
            },
            onDuplicate = { src ->
                onDuplicatePreset(src)
                showPresetManager = false
            },
            onDelete = onDeletePreset
        )
    }

    // ==================== Save As New Named Preset Dialog ====================
    if (showSaveAsDialog) {
        SaveAsPresetDialog(
            initialName = "$displayName 2",
            initialDesc = displayDesc,
            onDismiss = { showSaveAsDialog = false },
            onConfirm = { newName, newDesc ->
                onSaveAsNewPreset(newName, newDesc, cfg)
                Toast.makeText(ctx, ctx.getString(R.string.toast_preset_saved_as), Toast.LENGTH_SHORT).show()
                showSaveAsDialog = false
            }
        )
    }
}

@Composable
private fun MatrixHdr(text: String, modifier: Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

@Composable
private fun CompactSwitchLine(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f).padding(end = 8.dp)
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun StrategyPresetManagerDialog(
    presets: List<TeamPresetRecord>,
    activePresetId: String,
    onDismiss: () -> Unit,
    onSelect: (String, String) -> Unit,
    onDuplicate: (TeamBasePreset) -> Unit,
    onDelete: (String) -> Unit
) {
    val ctx = LocalContext.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        CompositionLocalProvider(
            LocalContext provides ctx,
            LocalConfiguration provides ctx.resources.configuration
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.92f).heightIn(max = 580.dp),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = ctx.getString(R.string.preset_dialog_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        TextButton(onClick = onDismiss) { Text(ctx.getString(R.string.btn_close)) }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (rec in presets) {
                            val p = rec.preset
                            val isActive = p.id == activePresetId
                            val name = LocaleHelper.displayPresetName(ctx, p, rec.isBuiltIn)
                            val desc = LocaleHelper.displayPresetDesc(ctx, p, rec.isBuiltIn)
                            ElevatedCard(
                                onClick = { onSelect(p.id, name) },
                                shape = RoundedCornerShape(14.dp),
                                colors = if (isActive) {
                                    CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                                } else {
                                    CardDefaults.elevatedCardColors()
                                }
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = name,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (isActive) {
                                            Badge(containerColor = MaterialTheme.colorScheme.primary) {
                                                Text(ctx.getString(R.string.badge_active), color = MaterialTheme.colorScheme.onPrimary)
                                            }
                                        }
                                    }
                                    if (desc.isNotBlank()) {
                                        Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (!isActive) {
                                            Button(
                                                onClick = { onSelect(p.id, name) },
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                                modifier = Modifier.height(30.dp)
                                            ) {
                                                Text(ctx.getString(R.string.btn_enable), style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                        OutlinedButton(
                                            onClick = { onDuplicate(p.copy(name = name, description = desc)) },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                            modifier = Modifier.height(30.dp)
                                        ) {
                                            Text(ctx.getString(R.string.btn_duplicate), style = MaterialTheme.typography.labelSmall)
                                        }
                                        if (!rec.isBuiltIn) {
                                            Spacer(Modifier.weight(1f))
                                            TextButton(
                                                onClick = { onDelete(p.id) },
                                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                            ) {
                                                Text(ctx.getString(R.string.btn_delete), style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SaveAsPresetDialog(
    initialName: String,
    initialDesc: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(initialName) }
    var desc by remember { mutableStateOf(initialDesc) }

    Dialog(onDismissRequest = onDismiss) {
        CompositionLocalProvider(
            LocalContext provides ctx,
            LocalConfiguration provides ctx.resources.configuration
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = ctx.getString(R.string.preset_save_as_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(ctx.getString(R.string.field_strategy_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = desc,
                        onValueChange = { desc = it },
                        label = { Text(ctx.getString(R.string.field_strategy_desc)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) { Text(ctx.getString(R.string.btn_cancel)) }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val finalName = name.trim().ifBlank { ctx.getString(R.string.custom_strategy_default) }
                                onConfirm(finalName, desc.trim())
                            }
                        ) {
                            Text(ctx.getString(R.string.btn_save_strategy))
                        }
                    }
                }
            }
        }
    }
}
