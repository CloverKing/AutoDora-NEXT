package com.autodora.next.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.autodora.next.data.AppSettings
import com.autodora.next.data.ThemeMode
import com.autodora.next.touch.SwipeSpeedPreset
import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class PermissionHealthState(
    val overlayGranted: Boolean,
    val accessibilityConnected: Boolean,
    val mediaProjectionReady: Boolean,
    val notificationsGranted: Boolean,
    val overlayRunning: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    health: PermissionHealthState,
    settings: AppSettings,
    benchmarkReport: String?,
    isBenchmarking: Boolean,
    onRequestOverlay: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenAppDetails: () -> Unit,
    onRequestMediaProjection: () -> Unit,
    onRequestNotifications: () -> Unit,
    onToggleOverlayService: () -> Unit,
    onRunNativeBenchmark: () -> Unit,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit
) {
    var showHyperOsGuide by remember { mutableStateOf(false) }

    // Synchronized text state for Max Steps numeric input (8..300)
    var maxStepsInput by remember { mutableStateOf(settings.maxSteps.toString()) }
    LaunchedEffect(settings.maxSteps) {
        val parsed = maxStepsInput.toIntOrNull()
        if (parsed != settings.maxSteps) {
            maxStepsInput = settings.maxSteps.toString()
        }
    }
    val parsedSteps = maxStepsInput.toIntOrNull()
    val maxStepsError = maxStepsInput.isNotEmpty() && (parsedSteps == null || parsedSteps !in 8..300)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "AutoDora-NEXT",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "免 Root 智能转珠 · C++20 · 动态 6×5/6×8",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    AssistChip(
                        onClick = onToggleOverlayService,
                        label = {
                            Text(
                                text = if (health.overlayRunning) "运行中" else "未启动",
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (health.overlayRunning) Icons.Outlined.CheckCircle else Icons.Outlined.PauseCircle,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (health.overlayRunning) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            labelColor = if (health.overlayRunning) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            leadingIconContentColor = if (health.overlayRunning) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        ),
                        border = null,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 2.dp
            ) {
                Button(
                    onClick = onToggleOverlayService,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .height(46.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = if (health.overlayRunning) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    } else {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                ) {
                    Icon(
                        imageVector = if (health.overlayRunning) Icons.Outlined.StopCircle else Icons.Outlined.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (health.overlayRunning) "停止悬浮窗助手" else "启动悬浮窗助手",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Permission Health Card
            SectionHeader("核心权限状态")
            ElevatedCard(shape = RoundedCornerShape(16.dp)) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    PermissionItemRow(
                        title = "1. 悬浮窗权限",
                        subtitle = "显示系统配色悬浮栏、路径预览与内嵌急停",
                        granted = health.overlayGranted,
                        actionLabel = "去授权",
                        onAction = onRequestOverlay
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    PermissionItemRow(
                        title = "2. 无障碍自动转珠",
                        subtitle = "免 Root 连续滑动、斜转与按住重算",
                        granted = health.accessibilityConnected,
                        actionLabel = "去开启",
                        onAction = onOpenAccessibility
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    PermissionItemRow(
                        title = "3. 屏幕采集 (MediaProjection)",
                        subtitle = "授权弹窗请保持选择「整个屏幕」",
                        granted = health.mediaProjectionReady,
                        actionLabel = "授权",
                        onAction = onRequestMediaProjection
                    )
                    if (!health.notificationsGranted) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        PermissionItemRow(
                            title = "4. 前台通知权限",
                            subtitle = "防止系统后台回收悬浮窗服务",
                            granted = false,
                            actionLabel = "允许",
                            onAction = onRequestNotifications
                        )
                    }
                }
            }

            // 2. Collapsible HyperOS / Android 15 Tip
            OutlinedCard(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.outlinedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "权限受限 / HyperOS 配置指引",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            TextButton(
                                onClick = onOpenAppDetails,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Text("应用详情", style = MaterialTheme.typography.labelMedium)
                            }
                            TextButton(
                                onClick = { showHyperOsGuide = !showHyperOsGuide },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                            ) {
                                Text(
                                    text = if (showHyperOsGuide) "收起" else "展开",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                    AnimatedVisibility(visible = showHyperOsGuide) {
                        Column(
                            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                "• 无障碍灰显受限：点右上「应用详情」→ 允许受限设置后再开无障碍。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "• 权限管理中开启「显示悬浮窗」与「后台弹出界面」，省电设为「无限制」。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "• 录屏授权选「整个屏幕」；进神魔战斗后点悬浮条「校」可随时微调盘面框。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 3. Appearance & System Theme (Synced 1:1 to Floating Overlay!)
            SectionHeader("外观与主题风格")
            ElevatedCard(shape = RoundedCornerShape(16.dp)) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "系统配色与明暗模式",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "启动页与悬浮栏同步应用系统 Material You 配色",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        ThemeMode.entries.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = settings.themeMode == mode,
                                onClick = { onUpdateSettings { it.copy(themeMode = mode) } },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = ThemeMode.entries.size
                                ),
                                icon = {
                                    Icon(
                                        imageVector = when (mode) {
                                            ThemeMode.SYSTEM -> Icons.Outlined.BrightnessAuto
                                            ThemeMode.LIGHT -> Icons.Outlined.LightMode
                                            ThemeMode.DARK -> Icons.Outlined.DarkMode
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            ) {
                                Text(mode.label, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }

            // 4. Core Solver & Touch Parameters
            SectionHeader("算路与触控参数")
            ElevatedCard(shape = RoundedCornerShape(16.dp)) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Max Steps Numeric Input (8..300) + Quick Presets
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "最大步数上限（支持长手转 / 违建 / 排珠）",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        OutlinedTextField(
                            value = maxStepsInput,
                            onValueChange = { raw ->
                                val digits = raw.filter { it.isDigit() }.take(3)
                                maxStepsInput = digits
                                val v = digits.toIntOrNull()
                                if (v != null && v in 8..300) {
                                    onUpdateSettings { it.copy(maxSteps = v) }
                                }
                            },
                            singleLine = true,
                            isError = maxStepsError,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            label = { Text("输入步数 (8 ~ 300)") },
                            suffix = { Text("步") },
                            supportingText = {
                                Text(
                                    if (maxStepsError) "请输入 8 ~ 300 之间的整数"
                                    else "当前生效：${settings.maxSteps} 步（>60 步时自动延长算路预算，随时可点「停」中止）"
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val quickSteps = listOf(30, 60, 100, 150, 200)
                            for (stepVal in quickSteps) {
                                FilterChip(
                                    selected = settings.maxSteps == stepVal,
                                    onClick = {
                                        maxStepsInput = stepVal.toString()
                                        onUpdateSettings { it.copy(maxSteps = stepVal) }
                                    },
                                    label = {
                                        Text(
                                            text = "$stepVal",
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    // Swipe Speed — 1:1 Single Source of Truth with Floating Toolbar!
                    val activeSpeedPreset = SwipeSpeedPreset.fromStepDurationMs(settings.stepDurationMs)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "划动速度档位（与悬浮栏 1:1 同步）",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "${activeSpeedPreset.label} · ${settings.stepDurationMs}ms/格",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            SwipeSpeedPreset.entries.forEachIndexed { idx, preset ->
                                SegmentedButton(
                                    selected = activeSpeedPreset == preset,
                                    onClick = {
                                        onUpdateSettings { it.copy(stepDurationMs = preset.stepDurationMs) }
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = idx,
                                        count = SwipeSpeedPreset.entries.size
                                    ),
                                    icon = {}
                                ) {
                                    Text(
                                        text = preset.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                        Slider(
                            value = settings.stepDurationMs.toFloat().coerceIn(20f, 120f),
                            onValueChange = { v -> onUpdateSettings { it.copy(stepDurationMs = v.roundToLong()) } },
                            valueRange = 20f..120f
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    SliderSettingRow(
                        label = "搜索算力 (Beam Width)",
                        valueText = "${settings.beamWidth}",
                        value = settings.beamWidth.toFloat(),
                        valueRange = 60f..400f,
                        onValueChange = { v -> onUpdateSettings { it.copy(beamWidth = (v / 20f).roundToInt() * 20) } }
                    )
                    SliderSettingRow(
                        label = "按住重算显形延时（问号‧转/风化‧转）",
                        valueText = "${settings.holdReplanDelayMs} ms",
                        value = settings.holdReplanDelayMs.toFloat(),
                        valueRange = 200f..800f,
                        onValueChange = { v -> onUpdateSettings { it.copy(holdReplanDelayMs = (v / 20f).roundToLong() * 20L) } }
                    )
                    SliderSettingRow(
                        label = "执行前倒数",
                        valueText = "${settings.countdownSeconds} 秒",
                        value = settings.countdownSeconds.toFloat(),
                        valueRange = 0f..3f,
                        steps = 2,
                        onValueChange = { v -> onUpdateSettings { it.copy(countdownSeconds = v.roundToInt()) } }
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    SwitchSettingRow(
                        title = "试跑确认模式（推荐）",
                        subtitle = "算路后先画线预览，点「行」再自动划珠",
                        checked = settings.testModeOnlyDraw,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(testModeOnlyDraw = v) } }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SwitchSettingRow(
                        title = "允许斜转（8 方向）",
                        subtitle = "45° 中心对角优化，高 C 与解盾更强",
                        checked = settings.allowDiagonalMove,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(allowDiagonalMove = v) } }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SwitchSettingRow(
                        title = "自动识别 6×8 违建盘面",
                        subtitle = "自动向上探查第 6~8 行扩展盘面",
                        checked = settings.calibration.autoDetectWeijian,
                        onCheckedChange = { v ->
                            onUpdateSettings { it.copy(calibration = it.calibration.copy(autoDetectWeijian = v)) }
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SwitchSettingRow(
                        title = "拟人化微抖动滑动",
                        subtitle = "中段加入细微速度与像素扰动",
                        checked = settings.humanMode,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(humanMode = v) } }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SwitchSettingRow(
                        title = "先露后藏问号珠自动记忆",
                        subtitle = "打问号关时开启，翻问号前自动锁定清晰盘面",
                        checked = settings.flashRevealMemoryEnabled,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(flashRevealMemoryEnabled = v) } }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SwitchSettingRow(
                        title = "按住转珠重算微移触发（问号‧转）",
                        subtitle = "按住微推 1 格触发显形不松手，实时重算续滑",
                        checked = settings.holdReplanNudge,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(holdReplanNudge = v) } }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SwitchSettingRow(
                        title = "主工具栏内嵌急停按钮（停）",
                        subtitle = "算路或划屏时在悬浮主栏一键中止",
                        checked = settings.showEmergencyStop,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(showEmergencyStop = v) } }
                    )
                }
            }

            // 5. On-Device C++20 JNI Benchmark
            SectionHeader("原生引擎实测")
            ElevatedCard(shape = RoundedCornerShape(16.dp)) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "本机 C++20 NDK 算路压测",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "验证原生 .so 求解速度（6×5 & 6×8）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        FilledTonalButton(
                            onClick = onRunNativeBenchmark,
                            enabled = !isBenchmarking
                        ) {
                            Text(if (isBenchmarking) "测试中..." else "一键压测")
                        }
                    }
                    if (benchmarkReport != null) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Text(
                                text = benchmarkReport,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 2.dp)
    )
}

@Composable
private fun PermissionItemRow(
    title: String,
    subtitle: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (granted) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        FilledTonalButton(
            onClick = onAction,
            enabled = !granted || actionLabel == "去开启",
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Text(
                text = if (granted) "已就绪" else actionLabel,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
private fun SwitchSettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun SliderSettingRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(top = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps
        )
    }
}
