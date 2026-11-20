package com.autodora.next.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.autodora.next.model.*

/**
 * Full Strategy / Goal Profile Editor (作战策略编辑器), faithfully inheriting and upgrading
 * Legacy AutoDora's complete strategy/goal configuration matrix + 2026 ToS team mechanics:
 *  1) Basic Info, Step Budget, Beam Complexity Tier, Best-vs-First Search Policy
 *  2) Global Goal Mode (高连击 / 三色 / 四色 / 五色 / 消5/6/8珠) + Four-Plus / Not-Enough / Split-3 / First-Wave-Only
 *  3) Per-Element 4-Tuple Matrix (Shape / Priority / Timing / Min-Match) + Element Link Preset (兼属相连)
 *  4) Columns 1..6 Amelioration (直排昇华 关/3/4/5珠)
 *  5) Start Point Preset, Character Orb Pull, Diagonal Match, Enchanted/Race/Weathered Preferences
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TeamPresetEditorDialog(
    initial: TeamBasePreset,
    isBuiltIn: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (TeamBasePreset) -> Unit
) {
    var name by remember { mutableStateOf(initial.name) }
    var desc by remember { mutableStateOf(initial.description) }
    var cfg by remember { mutableStateOf(initial.baseConfig) }
    var stepsText by remember { mutableStateOf(initial.baseConfig.maxSteps.toString()) }

    val elemLabels = listOf("水", "火", "木", "光", "暗", "心")
    val complexityTiers = listOf(
        "微" to 40,
        "低" to 90,
        "中" to 180,
        "高" to 280,
        "超" to 420,
        "极" to 600
    )

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

    fun shapeShortLabel(shape: TargetShape): String = when (shape) {
        TargetShape.ANY -> "一般"
        TargetShape.SPLIT_3 -> "切3"
        TargetShape.COUNT_2 -> "2珠"
        TargetShape.COUNT_4 -> "4珠"
        TargetShape.COUNT_5 -> "5珠"
        TargetShape.ROW -> "横排"
        TargetShape.COUNT_6 -> "6珠"
        TargetShape.COUNT_7 -> "7珠"
        TargetShape.COUNT_8 -> "8珠"
        TargetShape.COUNT_9 -> "9珠"
        TargetShape.CLEAR_ALL -> "全消"
        else -> "一般"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (initial.id.isBlank()) "新建作战策略" else "编辑作战策略",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // ===== 1. 基础信息与搜索预算 =====
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("策略名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("策略目标说明（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = stepsText,
                    onValueChange = { raw ->
                        val d = raw.filter { it.isDigit() }.take(3)
                        stepsText = d
                        val v = d.toIntOrNull()
                        if (v != null && v in 8..300) {
                            cfg = cfg.copy(maxSteps = v)
                        }
                    },
                    label = { Text("策略步数上限 (8 ~ 300)") },
                    suffix = { Text("步") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "搜索算力档位（旧版复杂度：${cfg.beamWidth}）",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val nearest = complexityTiers.minByOrNull { kotlin.math.abs(it.second - cfg.beamWidth) }?.second
                    for ((label, width) in complexityTiers) {
                        FilterChip(
                            selected = nearest == width,
                            onClick = { cfg = cfg.copy(beamWidth = width) },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // ===== 2. 全局消珠目标与切珠策略（继承旧版 page_orbs） =====
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = "全局求解目标模式",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (mode in StrategyGoalMode.entries) {
                        FilterChip(
                            selected = cfg.goalMode == mode,
                            onClick = { cfg = cfg.copy(goalMode = mode) },
                            label = { Text(mode.shortLabel, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                Text(
                    text = "四颗以上消除方式 · 珠数不足处理 · 求解倾向",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(
                        FourPlusPolicy.MAX_COMBO to "最高C",
                        FourPlusPolicy.MAX_ORBS to "最多珠",
                        FourPlusPolicy.SINGLE_GROUP to "消一组"
                    ).forEach { (pol, lbl) ->
                        FilterChip(
                            selected = cfg.fourPlusPolicy == pol,
                            onClick = { cfg = cfg.copy(fourPlusPolicy = pol) },
                            label = { Text(lbl, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = cfg.notEnoughPolicy == NotEnoughPolicy.MATCH_ANYWAY,
                        onClick = { cfg = cfg.copy(notEnoughPolicy = NotEnoughPolicy.MATCH_ANYWAY) },
                        label = { Text("不足照常消", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = cfg.notEnoughPolicy == NotEnoughPolicy.KEEP,
                        onClick = { cfg = cfg.copy(notEnoughPolicy = NotEnoughPolicy.KEEP) },
                        label = { Text("不足保留存珠", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = !cfg.earlyStopOnGoal,
                        onClick = { cfg = cfg.copy(earlyStopOnGoal = false) },
                        label = { Text("最佳解 (Best)", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = cfg.earlyStopOnGoal,
                        onClick = { cfg = cfg.copy(earlyStopOnGoal = true) },
                        label = { Text("最快达标 (First)", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.weight(1f)
                    )
                }

                // ===== 3. 单属性精细四元组（消珠形态 / 优先权 / 时机 / 队长最低消） =====
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = "单属性策略（形态 · 优先 · 时机 · 最低消）",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                for (e in 0 until 6) {
                    val er = cfg.elementRules.getOrElse(e) { ElementRule() }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = elemLabels[e],
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                            )
                        }
                        // Target shape (旧版 orbs_combo_type: 一般/2/4/5/横排/6/7/8/9/全消)
                        FilterChip(
                            selected = er.targetShape != TargetShape.ANY,
                            onClick = {
                                val curIdx = shapeOrder.indexOf(er.targetShape).takeIf { it >= 0 } ?: 0
                                val nextShape = shapeOrder[(curIdx + 1) % shapeOrder.size]
                                val list = cfg.elementRules.toMutableList()
                                list[e] = er.copy(targetShape = nextShape)
                                cfg = cfg.copy(elementRules = list)
                            },
                            label = { Text(shapeShortLabel(er.targetShape), style = MaterialTheme.typography.labelSmall) }
                        )
                        // Priority (旧版 PriorityButton: 一般/优先/保留)
                        FilterChip(
                            selected = er.priority != ElementPriority.NORMAL,
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
                            label = {
                                Text(
                                    when (er.priority) {
                                        ElementPriority.HIGH -> "优先"
                                        ElementPriority.RESERVE -> "保留"
                                        ElementPriority.NORMAL -> "一般"
                                    },
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        )
                        // Timing (旧版 orbs_combo_flag: 常规/首消/叠珠/全禁)
                        FilterChip(
                            selected = er.timing != MatchTiming.ANY,
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
                            label = {
                                Text(
                                    when (er.timing) {
                                        MatchTiming.ANY -> "无"
                                        MatchTiming.MUST_FIRST_WAVE -> "首消"
                                        MatchTiming.FORBID_FIRST_WAVE_ALLOW_CASCADE -> "叠珠"
                                        MatchTiming.FORBID_ALL -> "禁消"
                                    },
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        )
                        // Min match count (队长技: 3消 / 2消 / 4消)
                        FilterChip(
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
                            label = { Text("${er.minMatchCount}消", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                // Element Link Preset (队长技兼属/相连消除，防止与实战首消C数偏差！)
                Text(
                    text = "队长技兼属相连（相连即可消除）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val isHeartAll = (cfg.compatibleLinkMask.getOrElse(5) { 1 shl 5 } and 0x3F) == 0x3F
                val isLightDark = ((cfg.compatibleLinkMask.getOrElse(3) { 1 shl 3 } and (1 shl 4)) != 0) && !isHeartAll
                val isWfgLink = ((cfg.compatibleLinkMask.getOrElse(0) { 1 shl 0 } and (1 shl 1)) != 0) && !isHeartAll
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = !isHeartAll && !isLightDark && !isWfgLink,
                        onClick = { cfg = cfg.copy(compatibleLinkMask = List(6) { 1 shl it }) },
                        label = { Text("标准独立", style = MaterialTheme.typography.labelSmall) }
                    )
                    FilterChip(
                        selected = isHeartAll,
                        onClick = {
                            cfg = cfg.copy(
                                compatibleLinkMask = List(6) { e ->
                                    if (e == 5) 0x3F else ((1 shl e) or (1 shl 5))
                                }
                            )
                        },
                        label = { Text("心兼全属", style = MaterialTheme.typography.labelSmall) }
                    )
                    FilterChip(
                        selected = isLightDark,
                        onClick = {
                            val ld = (1 shl 3) or (1 shl 4)
                            cfg = cfg.copy(
                                compatibleLinkMask = List(6) { e -> if (e == 3 || e == 4) ld else (1 shl e) }
                            )
                        },
                        label = { Text("光暗互连", style = MaterialTheme.typography.labelSmall) }
                    )
                    FilterChip(
                        selected = isWfgLink,
                        onClick = {
                            val wfg = (1 shl 0) or (1 shl 1) or (1 shl 2)
                            cfg = cfg.copy(
                                compatibleLinkMask = List(6) { e -> if (e in 0..2) wfg else (1 shl e) }
                            )
                        },
                        label = { Text("水火木互连", style = MaterialTheme.typography.labelSmall) }
                    )
                }

                // ===== 4. 直排昇华（第 1~6 列，每列 关 / 3 / 4 / 5 珠） =====
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = "直排昇华（点击各列循环：关 → 3珠 → 4珠 → 5珠）",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (c in 0 until 6) {
                        val rule = cfg.columnRules.getOrElse(c) { ColumnAmeliorationRule() }
                        val label = if (!rule.enabled) "${c + 1}关" else "${c + 1}:${rule.minOrbs}珠"
                        FilterChip(
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
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // ===== 5. 起手点、角色符石与队伍专属开关 =====
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = "常用起手位置 · 角色符石下拉起手",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        StartPointPreset.ANY to "任意起手",
                        StartPointPreset.BOTTOM_LEFT to "固定左下",
                        StartPointPreset.BOTTOM_RIGHT to "固定右下"
                    ).forEach { (preset, lbl) ->
                        FilterChip(
                            selected = cfg.startPreset == preset,
                            onClick = { cfg = cfg.copy(startPreset = preset) },
                            label = { Text(lbl, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilterChip(
                        selected = !cfg.useCharacterOrb,
                        onClick = { cfg = cfg.copy(useCharacterOrb = false) },
                        label = { Text("关", style = MaterialTheme.typography.labelSmall) }
                    )
                    for (c in 0 until 6) {
                        FilterChip(
                            selected = cfg.useCharacterOrb && cfg.characterOrbCol == c,
                            onClick = { cfg = cfg.copy(useCharacterOrb = true, characterOrbCol = c) },
                            label = { Text("${c + 1}", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                PresetSwitchRow("强制切珠（每组尽量 3 颗冲高 C）", cfg.forceSplitThree) {
                    cfg = cfg.copy(forceSplitThree = it)
                }
                PresetSwitchRow("只计首批 Combo（不依赖天降叠珠）", cfg.firstWaveOnlyScoring) {
                    cfg = cfg.copy(firstWaveOnlyScoring = it)
                }
                PresetSwitchRow("允许斜消（8 向相连消除队长技）", cfg.allowDiagonalMatch) {
                    cfg = cfg.copy(allowDiagonalMatch = it)
                }
                PresetSwitchRow("强化符石优先消除", cfg.preferEnchanted) {
                    cfg = cfg.copy(preferEnchanted = it)
                }
                PresetSwitchRow("种族符石优先消除", cfg.preferRace) {
                    cfg = cfg.copy(preferRace = it)
                }
                PresetSwitchRow("允许撞击风化珠解状态", cfg.allowHitWeatheredToClear) {
                    cfg = cfg.copy(allowHitWeatheredToClear = it)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val safeSteps = (stepsText.toIntOrNull() ?: cfg.maxSteps).coerceIn(8, 300)
                    onConfirm(
                        initial.copy(
                            name = name.trim().ifBlank { if (isBuiltIn) initial.name else "自定义策略" },
                            description = desc.trim(),
                            baseConfig = cfg.copy(maxSteps = safeSteps)
                        )
                    )
                }
            ) {
                Text("保存策略")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun PresetSwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, style = MaterialTheme.typography.bodySmall)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
