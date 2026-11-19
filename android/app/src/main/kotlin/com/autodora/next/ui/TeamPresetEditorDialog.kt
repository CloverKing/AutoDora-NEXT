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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (initial.id.isBlank()) "新建队伍预设" else "编辑队伍预设",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("队伍名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("队伍说明（可选）") },
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
                    label = { Text("预设步数上限 (8 ~ 300)") },
                    suffix = { Text("步") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Text(
                    text = "直排昇华（≥ 4 消加权）",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (c in 0 until 6) {
                        val enabled = cfg.columnRules.getOrNull(c)?.enabled == true
                        FilterChip(
                            selected = enabled,
                            onClick = {
                                val list = cfg.columnRules.toMutableList()
                                list[c] = list[c].copy(enabled = !enabled, minOrbs = 4)
                                cfg = cfg.copy(columnRules = list)
                            },
                            label = { Text("${c + 1}", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Text(
                    text = "角色符石下拉起手",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
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

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Text(
                    text = "单属性规则（点击切换优先 / 时机 / 最低消）",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                for (e in 0 until 6) {
                    val er = cfg.elementRules.getOrElse(e) { ElementRule() }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = elemLabels[e],
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
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
                                        MatchTiming.ANY -> "常规"
                                        MatchTiming.MUST_FIRST_WAVE -> "必首消"
                                        MatchTiming.FORBID_FIRST_WAVE_ALLOW_CASCADE -> "反首消"
                                        MatchTiming.FORBID_ALL -> "禁消"
                                    },
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        )
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

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                PresetSwitchRow("允许斜消（8 向相连消除）", cfg.allowDiagonalMatch) {
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
                            name = name.trim().ifBlank { if (isBuiltIn) initial.name else "自定义队伍" },
                            description = desc.trim(),
                            baseConfig = cfg.copy(maxSteps = safeSteps)
                        )
                    )
                }
            ) {
                Text("保存预设")
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
