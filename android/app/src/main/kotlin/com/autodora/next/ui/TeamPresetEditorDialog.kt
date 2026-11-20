package com.autodora.next.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.autodora.next.R
import com.autodora.next.model.*

/**
 * Full Strategy / Goal Profile Editor (作战策略编辑器):
 *  - Wide responsive Dialog (95% width) with zero multi-line chip wrapping.
 *  - Aligned Per-Element 4-Tuple Matrix with clear column headers.
 *  - 2×3 Grid for Columns 1..6 Amelioration + balanced 2-row Character Orb selector.
 *  - Circle (?) contextual help button on top-right of every option group.
 *  - 100% pure single-language localized via parent's live localized Context.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TeamPresetEditorDialog(
    initial: TeamBasePreset,
    isBuiltIn: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (TeamBasePreset) -> Unit
) {
    val ctx = LocalContext.current
    fun t(resId: Int): String = ctx.getString(resId)
    fun t(resId: Int, vararg args: Any): String = ctx.getString(resId, *args)

    var name by remember(initial.id) {
        mutableStateOf(
            if (initial.id.isBlank()) "" else LocaleHelper.displayPresetName(ctx, initial, isBuiltIn)
        )
    }
    var desc by remember(initial.id) {
        mutableStateOf(
            if (initial.id.isBlank()) "" else LocaleHelper.displayPresetDesc(ctx, initial, isBuiltIn)
        )
    }
    var cfg by remember(initial.id) { mutableStateOf(initial.baseConfig) }
    var stepsText by remember(initial.id) { mutableStateOf(initial.baseConfig.maxSteps.toString()) }
    var activeHelp by remember { mutableStateOf<Pair<String, String>?>(null) }

    val shapeOrder = listOf(
        TargetShape.ANY,
        TargetShape.COUNT_2,
        TargetShape.COUNT_4,
        TargetShape.COUNT_5,
        TargetShape.ROW,
        TargetShape.COUNT_6,
        TargetShape.COUNT_7,
        TargetShape.COUNT_8,
        TargetShape.COUNT_9,
        TargetShape.CLEAR_ALL
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        CompositionLocalProvider(
            LocalContext provides ctx,
            LocalConfiguration provides ctx.resources.configuration
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .heightIn(max = 680.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Dialog Title Bar
                    Text(
                        text = t(
                            if (initial.id.isBlank()) R.string.dialog_new_strategy else R.string.dialog_edit_strategy
                        ),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    // Scrollable Body
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // ===== 1. Basic Info, Steps & Complexity =====
                        EditorSectionTitleRow(
                            title = t(R.string.section_basic_info),
                            onHelp = {
                                activeHelp = t(R.string.help_basic_steps_title) to t(R.string.help_basic_steps_body)
                            }
                        )
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text(t(R.string.field_strategy_name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = desc,
                            onValueChange = { desc = it },
                            label = { Text(t(R.string.field_strategy_desc)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = t(R.string.priority_tier_hint_strategy),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )

                        // Strategy Max Steps with Follow-System / Strategy-Override toggle
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
                                        cfg = cfg.copy(maxSteps = v, useSystemSteps = false)
                                    }
                                },
                                label = { Text(t(R.string.field_strategy_steps)) },
                                suffix = { Text(t(R.string.unit_steps)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                            CompactChoicePill(
                                label = t(if (cfg.useSystemSteps) R.string.budget_follow_system else R.string.budget_override_strategy),
                                selected = cfg.useSystemSteps,
                                onClick = { cfg = cfg.copy(useSystemSteps = !cfg.useSystemSteps) }
                            )
                        }

                        // Unified Native Slim Slider for Solver Compute (matching Settings tab 1:1!)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = t(R.string.slider_beam) + " · ${cfg.beamWidth}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            CompactChoicePill(
                                label = t(if (cfg.useSystemBeam) R.string.budget_follow_system else R.string.budget_override_strategy),
                                selected = cfg.useSystemBeam,
                                onClick = { cfg = cfg.copy(useSystemBeam = !cfg.useSystemBeam) }
                            )
                        }
                        SlimNativeSlider(
                            value = cfg.beamWidth.toFloat().coerceIn(40f, 600f),
                            onValueChange = { v ->
                                val stepped = ((v / 20f).toInt() * 20).coerceIn(40, 600)
                                cfg = cfg.copy(beamWidth = stepped, useSystemBeam = false)
                            },
                            valueRange = 40f..600f
                        )

                        // ===== 2. Grouped & Parameterized Global Matching Goal (N / X) =====
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        EditorSectionTitleRow(
                            title = t(R.string.section_goal_mode),
                            onHelp = {
                                activeHelp = t(R.string.help_goal_policy_title) to t(R.string.help_goal_policy_body)
                            }
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
                                        cfg = cfg.copy(
                                            goalMode = grp,
                                            goalParamN = defN,
                                            goalParamX = defX,
                                            goalElementsMask = if (grp != goalGroup) 0 else cfg.goalElementsMask
                                        )
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
                                    // Parameter N (Number of Colors)
                                    if (goalGroup == StrategyGoalMode.N_COLOR_BALANCE ||
                                        goalGroup == StrategyGoalMode.N_COLOR_ALL ||
                                        goalGroup == StrategyGoalMode.MULTI_COLOR_X_ORBS
                                    ) {
                                        val nRange = if (goalGroup == StrategyGoalMode.N_COLOR_ALL) 2..6 else 2..5
                                        Text(
                                            text = t(R.string.goal_param_n_label),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                                        ) {
                                            for (n in nRange) {
                                                CompactChoicePill(
                                                    label = t(R.string.goal_unit_colors, n),
                                                    selected = curGoalN == n,
                                                    onClick = {
                                                        cfg = cfg.copy(
                                                            goalMode = goalGroup,
                                                            goalParamN = n,
                                                            goalElementsMask = 0 // Reset specific mask when N changes
                                                        )
                                                    },
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }
                                        }
                                    }

                                    // Parameter X (Orbs per group: 4..9!)
                                    if (goalGroup == StrategyGoalMode.SINGLE_COLOR_X_ORBS ||
                                        goalGroup == StrategyGoalMode.MULTI_COLOR_X_ORBS
                                    ) {
                                        Text(
                                            text = t(R.string.goal_param_x_label),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            for (x in 4..9) {
                                                CompactChoicePill(
                                                    label = t(R.string.goal_unit_orbs, x),
                                                    selected = curGoalX == x,
                                                    onClick = {
                                                        cfg = cfg.copy(goalMode = goalGroup, goalParamX = x)
                                                    },
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }
                                        }
                                    }

                                    // Optional Target Color for Single-Color X Orbs
                                    if (goalGroup == StrategyGoalMode.SINGLE_COLOR_X_ORBS) {
                                        Text(
                                            text = t(R.string.goal_target_elem_label),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            CompactChoicePill(
                                                label = t(R.string.puzzle_elem_any),
                                                selected = cfg.goalTargetElement < 0,
                                                onClick = { cfg = cfg.copy(goalMode = goalGroup, goalTargetElement = -1) },
                                                modifier = Modifier.weight(1.5f)
                                            )
                                            for (e in 0 until 6) {
                                                val sel = cfg.goalTargetElement == e
                                                val badgeCol = Color(LocaleHelper.elementBadgeArgb(e))
                                                Surface(
                                                    onClick = { cfg = cfg.copy(goalMode = goalGroup, goalTargetElement = e) },
                                                    shape = RoundedCornerShape(10.dp),
                                                    color = if (sel) badgeCol else MaterialTheme.colorScheme.surface,
                                                    border = BorderStroke(
                                                        width = if (sel) 1.5.dp else 1.dp,
                                                        color = if (sel) badgeCol else MaterialTheme.colorScheme.outlineVariant
                                                    ),
                                                    modifier = Modifier.weight(1f).height(32.dp)
                                                ) {
                                                    Box(contentAlignment = Alignment.Center) {
                                                        Text(
                                                            text = LocaleHelper.elementLabel(ctx, e),
                                                            style = MaterialTheme.typography.labelSmall,
                                                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                                                            color = if (sel) Color.White else MaterialTheme.colorScheme.onSurface
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // Optional Specific Colors Mask for N-Color modes
                                    if (goalGroup == StrategyGoalMode.N_COLOR_BALANCE ||
                                        goalGroup == StrategyGoalMode.N_COLOR_ALL ||
                                        goalGroup == StrategyGoalMode.MULTI_COLOR_X_ORBS
                                    ) {
                                        Text(
                                            text = t(R.string.goal_colors_opt_label),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            for (e in 0 until 6) {
                                                val bit = 1 shl e
                                                val sel = (cfg.goalElementsMask and bit) != 0
                                                val badgeCol = Color(LocaleHelper.elementBadgeArgb(e))
                                                Surface(
                                                    onClick = {
                                                        var nextMask = cfg.goalElementsMask xor bit
                                                        if ((nextMask and bit) != 0 && Integer.bitCount(nextMask) > curGoalN) {
                                                            // Cap selection to N colors
                                                            nextMask = cfg.goalElementsMask
                                                        }
                                                        cfg = cfg.copy(goalMode = goalGroup, goalElementsMask = nextMask)
                                                    },
                                                    shape = RoundedCornerShape(10.dp),
                                                    color = if (sel) badgeCol else MaterialTheme.colorScheme.surface,
                                                    border = BorderStroke(
                                                        width = if (sel) 1.5.dp else 1.dp,
                                                        color = if (sel) badgeCol else MaterialTheme.colorScheme.outlineVariant
                                                    ),
                                                    modifier = Modifier.weight(1f).height(32.dp)
                                                ) {
                                                    Box(contentAlignment = Alignment.Center) {
                                                        Text(
                                                            text = LocaleHelper.elementLabel(ctx, e),
                                                            style = MaterialTheme.typography.labelSmall,
                                                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                                                            color = if (sel) Color.White else MaterialTheme.colorScheme.onSurface
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Text(
                            text = t(R.string.section_match_policy),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                FourPlusPolicy.MAX_COMBO to t(R.string.policy_max_combo),
                                FourPlusPolicy.MAX_ORBS to t(R.string.policy_max_orbs),
                                FourPlusPolicy.SINGLE_GROUP to t(R.string.policy_single_group)
                            ).forEach { (pol, lbl) ->
                                CompactChoicePill(
                                    label = lbl,
                                    selected = cfg.fourPlusPolicy == pol,
                                    onClick = { cfg = cfg.copy(fourPlusPolicy = pol) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            CompactChoicePill(
                                label = t(R.string.policy_not_enough_match),
                                selected = cfg.notEnoughPolicy == NotEnoughPolicy.MATCH_ANYWAY,
                                onClick = { cfg = cfg.copy(notEnoughPolicy = NotEnoughPolicy.MATCH_ANYWAY) },
                                modifier = Modifier.weight(1f)
                            )
                            CompactChoicePill(
                                label = t(R.string.policy_not_enough_keep),
                                selected = cfg.notEnoughPolicy == NotEnoughPolicy.KEEP,
                                onClick = { cfg = cfg.copy(notEnoughPolicy = NotEnoughPolicy.KEEP) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            CompactChoicePill(
                                label = t(R.string.policy_best_result),
                                selected = !cfg.earlyStopOnGoal,
                                onClick = { cfg = cfg.copy(earlyStopOnGoal = false) },
                                modifier = Modifier.weight(1f)
                            )
                            CompactChoicePill(
                                label = t(R.string.policy_first_result),
                                selected = cfg.earlyStopOnGoal,
                                onClick = { cfg = cfg.copy(earlyStopOnGoal = true) },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        // ===== 3. Per-Element 4-Tuple Strategy Matrix =====
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        EditorSectionTitleRow(
                            title = t(R.string.section_elem_matrix),
                            onHelp = {
                                activeHelp = t(R.string.help_elem_matrix_title) to t(R.string.help_elem_matrix_body)
                            }
                        )

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 6.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                MatrixHeaderCell(t(R.string.col_hdr_elem), Modifier.width(38.dp))
                                MatrixHeaderCell(t(R.string.col_hdr_shape), Modifier.weight(1.05f))
                                MatrixHeaderCell(t(R.string.col_hdr_priority), Modifier.weight(1f))
                                MatrixHeaderCell(t(R.string.col_hdr_timing), Modifier.weight(1f))
                                MatrixHeaderCell(t(R.string.col_hdr_min), Modifier.weight(0.85f))
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (e in 0 until 6) {
                                val er = cfg.elementRules.getOrElse(e) { ElementRule() }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(9.dp),
                                        color = Color(LocaleHelper.elementBadgeArgb(e)),
                                        modifier = Modifier
                                            .width(38.dp)
                                            .height(34.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = LocaleHelper.elementLabel(ctx, e),
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                maxLines = 1
                                            )
                                        }
                                    }

                                    CompactChoicePill(
                                        label = LocaleHelper.targetShapeLabel(ctx, er.targetShape),
                                        selected = er.targetShape != TargetShape.ANY,
                                        onClick = {
                                            val curIdx = shapeOrder.indexOf(er.targetShape).takeIf { it >= 0 } ?: 0
                                            val nextShape = shapeOrder[(curIdx + 1) % shapeOrder.size]
                                            val list = cfg.elementRules.toMutableList()
                                            list[e] = er.copy(targetShape = nextShape)
                                            cfg = cfg.copy(elementRules = list)
                                        },
                                        modifier = Modifier.weight(1.05f)
                                    )

                                    CompactChoicePill(
                                        label = LocaleHelper.elementPriorityLabel(ctx, er.priority),
                                        selected = er.priority != ElementPriority.NORMAL,
                                        activeContainer = if (er.priority == ElementPriority.HIGH) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.secondaryContainer
                                        },
                                        activeContent = if (er.priority == ElementPriority.HIGH) {
                                            MaterialTheme.colorScheme.onPrimary
                                        } else {
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                        },
                                        onClick = {
                                            val next = when (er.priority) {
                                                ElementPriority.NORMAL -> ElementPriority.HIGH
                                                ElementPriority.HIGH -> ElementPriority.RESERVE
                                                ElementPriority.RESERVE -> ElementPriority.NORMAL
                                            }
                                            val list = cfg.elementRules.toMutableList()
                                            list[e] = er.copy(priority = next)
                                            cfg = cfg.copy(elementRules = list)
                                        },
                                        modifier = Modifier.weight(1f)
                                    )

                                    val timingActiveBg = when (er.timing) {
                                        MatchTiming.FORBID_ALL -> MaterialTheme.colorScheme.errorContainer
                                        MatchTiming.MUST_FIRST_WAVE -> MaterialTheme.colorScheme.tertiaryContainer
                                        MatchTiming.FORBID_FIRST_WAVE_ALLOW_CASCADE -> MaterialTheme.colorScheme.secondaryContainer
                                        MatchTiming.ANY -> MaterialTheme.colorScheme.primaryContainer
                                    }
                                    val timingActiveFg = when (er.timing) {
                                        MatchTiming.FORBID_ALL -> MaterialTheme.colorScheme.onErrorContainer
                                        MatchTiming.MUST_FIRST_WAVE -> MaterialTheme.colorScheme.onTertiaryContainer
                                        MatchTiming.FORBID_FIRST_WAVE_ALLOW_CASCADE -> MaterialTheme.colorScheme.onSecondaryContainer
                                        MatchTiming.ANY -> MaterialTheme.colorScheme.onPrimaryContainer
                                    }
                                    CompactChoicePill(
                                        label = LocaleHelper.matchTimingLabel(ctx, er.timing),
                                        selected = er.timing != MatchTiming.ANY,
                                        activeContainer = timingActiveBg,
                                        activeContent = timingActiveFg,
                                        onClick = {
                                            val next = when (er.timing) {
                                                MatchTiming.ANY -> MatchTiming.MUST_FIRST_WAVE
                                                MatchTiming.MUST_FIRST_WAVE -> MatchTiming.FORBID_FIRST_WAVE_ALLOW_CASCADE
                                                MatchTiming.FORBID_FIRST_WAVE_ALLOW_CASCADE -> MatchTiming.FORBID_ALL
                                                MatchTiming.FORBID_ALL -> MatchTiming.ANY
                                            }
                                            val list = cfg.elementRules.toMutableList()
                                            list[e] = er.copy(timing = next)
                                            cfg = cfg.copy(elementRules = list)
                                        },
                                        modifier = Modifier.weight(1f)
                                    )

                                    CompactChoicePill(
                                        label = t(R.string.min_match_fmt, er.minMatchCount),
                                        selected = er.minMatchCount != 3,
                                        onClick = {
                                            val next = when (er.minMatchCount) {
                                                3 -> 2
                                                2 -> 4
                                                else -> 3
                                            }
                                            val list = cfg.elementRules.toMutableList()
                                            list[e] = er.copy(minMatchCount = next)
                                            cfg = cfg.copy(elementRules = list)
                                        },
                                        modifier = Modifier.weight(0.85f)
                                    )
                                }
                            }
                        }

                        // Leader Attribute Link Preset
                        Text(
                            text = t(R.string.section_link_preset),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val isHeartAll = (cfg.compatibleLinkMask.getOrElse(5) { 1 shl 5 } and 0x3F) == 0x3F
                        val isLightDark = ((cfg.compatibleLinkMask.getOrElse(3) { 1 shl 3 } and (1 shl 4)) != 0) && !isHeartAll
                        val isWfgLink = ((cfg.compatibleLinkMask.getOrElse(0) { 1 shl 0 } and (1 shl 1)) != 0) && !isHeartAll
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            CompactChoicePill(
                                label = t(R.string.link_standard),
                                selected = !isHeartAll && !isLightDark && !isWfgLink,
                                onClick = { cfg = cfg.copy(compatibleLinkMask = List(6) { 1 shl it }) }
                            )
                            CompactChoicePill(
                                label = t(R.string.link_heart_all),
                                selected = isHeartAll,
                                onClick = {
                                    cfg = cfg.copy(
                                        compatibleLinkMask = List(6) { e ->
                                            if (e == 5) 0x3F else ((1 shl e) or (1 shl 5))
                                        }
                                    )
                                }
                            )
                            CompactChoicePill(
                                label = t(R.string.link_light_dark),
                                selected = isLightDark,
                                onClick = {
                                    val ld = (1 shl 3) or (1 shl 4)
                                    cfg = cfg.copy(
                                        compatibleLinkMask = List(6) { e -> if (e == 3 || e == 4) ld else (1 shl e) }
                                    )
                                }
                            )
                            CompactChoicePill(
                                label = t(R.string.link_wfg),
                                selected = isWfgLink,
                                onClick = {
                                    val wfg = (1 shl 0) or (1 shl 1) or (1 shl 2)
                                    cfg = cfg.copy(
                                        compatibleLinkMask = List(6) { e -> if (e in 0..2) wfg else (1 shl e) }
                                    )
                                }
                            )
                        }

                        // ===== 4. Columns 1..6 Amelioration (2×3 Grid) =====
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        EditorSectionTitleRow(
                            title = t(R.string.section_columns),
                            onHelp = {
                                activeHelp = t(R.string.help_columns_title) to t(R.string.help_columns_body)
                            }
                        )
                        for (rowIdx in 0..1) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                for (offset in 0..2) {
                                    val c = rowIdx * 3 + offset
                                    val rule = cfg.columnRules.getOrElse(c) { ColumnAmeliorationRule() }
                                    val label = if (!rule.enabled) {
                                        t(R.string.col_off_fmt, c + 1)
                                    } else {
                                        t(R.string.col_on_fmt, c + 1, rule.minOrbs)
                                    }
                                    CompactChoicePill(
                                        label = label,
                                        selected = rule.enabled,
                                        onClick = {
                                            val next = when {
                                                !rule.enabled -> rule.copy(enabled = true, minOrbs = 3)
                                                rule.minOrbs == 3 -> rule.copy(enabled = true, minOrbs = 4)
                                                rule.minOrbs == 4 -> rule.copy(enabled = true, minOrbs = 5)
                                                else -> rule.copy(enabled = false, minOrbs = 4)
                                            }
                                            val list = cfg.columnRules.toMutableList()
                                            list[c] = next
                                            cfg = cfg.copy(columnRules = list)
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }

                        // ===== 5. Start Position & Character Orb Pull =====
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        EditorSectionTitleRow(
                            title = t(R.string.section_start_char),
                            onHelp = {
                                activeHelp = t(R.string.help_start_char_title) to t(R.string.help_start_char_body)
                            }
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                StartPointPreset.ANY to t(R.string.start_any),
                                StartPointPreset.BOTTOM_LEFT to t(R.string.start_bl),
                                StartPointPreset.BOTTOM_RIGHT to t(R.string.start_br)
                            ).forEach { (preset, lbl) ->
                                CompactChoicePill(
                                    label = lbl,
                                    selected = cfg.startPreset == preset,
                                    onClick = { cfg = cfg.copy(startPreset = preset) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            CompactChoicePill(
                                label = t(R.string.char_off),
                                selected = !cfg.useCharacterOrb,
                                onClick = { cfg = cfg.copy(useCharacterOrb = false) },
                                modifier = Modifier.weight(1f)
                            )
                            for (c in 0..2) {
                                CompactChoicePill(
                                    label = t(R.string.char_slot_fmt, c + 1),
                                    selected = cfg.useCharacterOrb && cfg.characterOrbCol == c,
                                    onClick = { cfg = cfg.copy(useCharacterOrb = true, characterOrbCol = c) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            for (c in 3..5) {
                                CompactChoicePill(
                                    label = t(R.string.char_slot_fmt, c + 1),
                                    selected = cfg.useCharacterOrb && cfg.characterOrbCol == c,
                                    onClick = { cfg = cfg.copy(useCharacterOrb = true, characterOrbCol = c) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Spacer(Modifier.weight(1f))
                        }

                        // ===== 6. Advanced Strategy Switches =====
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        EditorSectionTitleRow(
                            title = t(R.string.section_strategy_switches),
                            onHelp = {
                                activeHelp = t(R.string.help_strategy_switches_title) to t(R.string.help_strategy_switches_body)
                            }
                        )
                        PresetSwitchRow(t(R.string.switch_split_three), cfg.forceSplitThree) {
                            cfg = cfg.copy(forceSplitThree = it)
                        }
                        PresetSwitchRow(t(R.string.switch_first_wave_only), cfg.firstWaveOnlyScoring) {
                            cfg = cfg.copy(firstWaveOnlyScoring = it)
                        }
                        PresetSwitchRow(t(R.string.switch_diag_match), cfg.allowDiagonalMatch) {
                            cfg = cfg.copy(allowDiagonalMatch = it)
                        }
                        PresetSwitchRow(t(R.string.switch_prefer_ench), cfg.preferEnchanted) {
                            cfg = cfg.copy(preferEnchanted = it)
                        }
                        PresetSwitchRow(t(R.string.switch_prefer_race), cfg.preferRace) {
                            cfg = cfg.copy(preferRace = it)
                        }
                        PresetSwitchRow(t(R.string.switch_hit_weathered), cfg.allowHitWeatheredToClear) {
                            cfg = cfg.copy(allowHitWeatheredToClear = it)
                        }
                    }

                    // Bottom Action Bar
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(t(R.string.btn_cancel))
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val safeSteps = (stepsText.toIntOrNull() ?: cfg.maxSteps).coerceIn(8, 300)
                                val fallbackName = if (isBuiltIn) {
                                    LocaleHelper.displayPresetName(ctx, initial, true)
                                } else {
                                    t(R.string.custom_strategy_default)
                                }
                                onConfirm(
                                    initial.copy(
                                        name = name.trim().ifBlank { fallbackName },
                                        description = desc.trim(),
                                        baseConfig = cfg.copy(maxSteps = safeSteps)
                                    )
                                )
                            }
                        ) {
                            Text(t(R.string.btn_save_strategy))
                        }
                    }
                }
            }

            activeHelp?.let { (hTitle, hBody) ->
                LocalizedHelpDialog(
                    title = hTitle,
                    body = hBody,
                    dismissLabel = t(R.string.btn_got_it),
                    onDismiss = { activeHelp = null }
                )
            }
        }
    }
}

@Composable
internal fun LocalizedHelpDialog(
    title: String,
    body: String,
    dismissLabel: String,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    FilledTonalButton(onClick = onDismiss) {
                        Text(dismissLabel)
                    }
                }
            }
        }
    }
}

@Composable
internal fun HelpCircleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(28.dp)
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun EditorSectionTitleRow(title: String, onHelp: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        HelpCircleButton(onClick = onHelp)
    }
}

@Composable
private fun MatrixHeaderCell(text: String, modifier: Modifier = Modifier) {
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
internal fun CompactChoicePill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    activeContainer: Color = MaterialTheme.colorScheme.primaryContainer,
    activeContent: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    activeBorder: Color? = MaterialTheme.colorScheme.primary
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) activeContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            width = if (selected) 1.25.dp else 1.dp,
            color = if (selected) (activeBorder ?: activeContainer) else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = modifier.heightIn(min = 34.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) activeContent else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PresetSwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
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
