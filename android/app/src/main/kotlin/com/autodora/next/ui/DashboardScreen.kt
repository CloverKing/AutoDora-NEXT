package com.autodora.next.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autodora.next.data.AppSettings
import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class PermissionHealthState(
    val overlayGranted: Boolean,
    val accessibilityConnected: Boolean,
    val mediaProjectionReady: Boolean,
    val notificationsGranted: Boolean,
    val overlayRunning: Boolean
)

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

    Scaffold(
        topBar = {
            Surface(tonalElevation = 2.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            "AutoDora-NEXT",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "免 Root 智能转珠 · C++20 · 动态 6×5/6×8",
                            fontSize = 10.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (health.overlayRunning) Color(0x334CAF50) else MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = if (health.overlayRunning) "运行中" else "未启动",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (health.overlayRunning) Color(0xFF81C784) else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        },
        bottomBar = {
            Surface(tonalElevation = 4.dp) {
                Button(
                    onClick = onToggleOverlayService,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .height(42.dp),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (health.overlayRunning) Color(0xFFD32F2F) else Color(0xFF00897B),
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        imageVector = if (health.overlayRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (health.overlayRunning) "停止悬浮窗助手" else "启动悬浮窗助手",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
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
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 1. Compact Permission Health Card (all 3-4 checks inside one dense card)
            SectionLabel("一、核心权限状态")
            ElevatedCard(shape = RoundedCornerShape(10.dp)) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    CompactPermissionRow(
                        title = "1. 悬浮窗权限",
                        subtitle = "透传路径、三态球与急停",
                        granted = health.overlayGranted,
                        actionLabel = "去授权",
                        onAction = onRequestOverlay
                    )
                    HorizontalDivider(thickness = 0.5.dp)
                    CompactPermissionRow(
                        title = "2. 无障碍自动转珠",
                        subtitle = "免 Root 连续滑动与斜转",
                        granted = health.accessibilityConnected,
                        actionLabel = "去开启",
                        onAction = onOpenAccessibility
                    )
                    HorizontalDivider(thickness = 0.5.dp)
                    CompactPermissionRow(
                        title = "3. 屏幕采集 (MediaProjection)",
                        subtitle = "弹窗请保持「整个屏幕」",
                        granted = health.mediaProjectionReady,
                        actionLabel = "授权",
                        onAction = onRequestMediaProjection
                    )
                    if (!health.notificationsGranted) {
                        HorizontalDivider(thickness = 0.5.dp)
                        CompactPermissionRow(
                            title = "4. 前台通知权限",
                            subtitle = "防止系统回收服务",
                            granted = false,
                            actionLabel = "允许",
                            onAction = onRequestNotifications
                        )
                    }
                }
            }

            // 2. Collapsible HyperOS / Android 15 Tip (collapsed by default to keep screen compact)
            ElevatedCard(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "权限受限 / HyperOS 配置指引",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = onOpenAppDetails,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text("应用详情", fontSize = 11.5.sp)
                            }
                            TextButton(
                                onClick = { showHyperOsGuide = !showHyperOsGuide },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text(if (showHyperOsGuide) "收起" else "展开", fontSize = 11.5.sp)
                            }
                        }
                    }
                    AnimatedVisibility(visible = showHyperOsGuide) {
                        Column(
                            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text("• 无障碍灰显受限：点右上「应用详情」→ 允许受限设置后再开无障碍。", fontSize = 11.sp, lineHeight = 15.sp)
                            Text("• 权限管理中开启「显示悬浮窗」与「后台弹出界面」，省电设为「无限制」。", fontSize = 11.sp, lineHeight = 15.sp)
                            Text("• 录屏授权选「整个屏幕」；进神魔战斗后点悬浮条「校准」贴合真实棋盘。", fontSize = 11.sp, lineHeight = 15.sp)
                        }
                    }
                }
            }

            // 3. Compact On-Device C++20 JNI Benchmark
            ElevatedCard(shape = RoundedCornerShape(10.dp)) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("二、本机 C++20 NDK 引擎实测", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                            Text("验证原生 .so 算路速度（6×5 & 6×8）", fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        FilledTonalButton(
                            onClick = onRunNativeBenchmark,
                            enabled = !isBenchmarking,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            modifier = Modifier.height(30.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(if (isBenchmarking) "测试中..." else "一键压测", fontSize = 11.5.sp)
                        }
                    }
                    if (benchmarkReport != null) {
                        Text(
                            text = benchmarkReport,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // 4. Compact Core Solver & Touch Parameters
            SectionLabel("三、算路与触控参数")
            ElevatedCard(shape = RoundedCornerShape(10.dp)) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    CompactSwitchRow(
                        title = "试跑确认模式（推荐）",
                        subtitle = "算路后先画线预览，点「执行」再滑动",
                        checked = settings.testModeOnlyDraw,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(testModeOnlyDraw = v) } }
                    )
                    HorizontalDivider(thickness = 0.5.dp)
                    CompactSwitchRow(
                        title = "允许斜转（8 方向）",
                        subtitle = "45° 中心对角优化，高 C 与解盾更强",
                        checked = settings.allowDiagonalMove,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(allowDiagonalMove = v) } }
                    )
                    HorizontalDivider(thickness = 0.5.dp)
                    CompactSwitchRow(
                        title = "自动识别 6×8 违建盘面",
                        subtitle = "自动向上探查第 6~8 行扩展盘面",
                        checked = settings.calibration.autoDetectWeijian,
                        onCheckedChange = { v ->
                            onUpdateSettings { it.copy(calibration = it.calibration.copy(autoDetectWeijian = v)) }
                        }
                    )
                    HorizontalDivider(thickness = 0.5.dp)
                    CompactSwitchRow(
                        title = "拟人化微抖动滑动",
                        subtitle = "中段加入细微速度与像素扰动",
                        checked = settings.humanMode,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(humanMode = v) } }
                    )
                    HorizontalDivider(thickness = 0.5.dp)
                    CompactSwitchRow(
                        title = "先露后藏问号珠自动记忆",
                        subtitle = "全版翻问号珠前自动锁定清晰盘面并透视还原",
                        checked = settings.flashRevealMemoryEnabled,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(flashRevealMemoryEnabled = v) } }
                    )
                    HorizontalDivider(thickness = 0.5.dp)
                    CompactSwitchRow(
                        title = "按住转珠重算微移触发（问号‧转）",
                        subtitle = "按住微推 1 格触发显形不松手，实时重算续滑",
                        checked = settings.holdReplanNudge,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(holdReplanNudge = v) } }
                    )
                    HorizontalDivider(thickness = 0.5.dp)
                    CompactSwitchRow(
                        title = "独立红色急停按钮",
                        subtitle = "算路或划屏时一键中止",
                        checked = settings.showEmergencyStop,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(showEmergencyStop = v) } }
                    )

                    HorizontalDivider(thickness = 0.5.dp)
                    CompactSliderRow(
                        label = "最大步数上限",
                        valueText = "${settings.maxSteps} 步",
                        value = settings.maxSteps.toFloat(),
                        valueRange = 10f..60f,
                        steps = 24,
                        onValueChange = { v -> onUpdateSettings { it.copy(maxSteps = v.roundToInt()) } }
                    )
                    CompactSliderRow(
                        label = "搜索算力 (Beam)",
                        valueText = "${settings.beamWidth}",
                        value = settings.beamWidth.toFloat(),
                        valueRange = 60f..400f,
                        onValueChange = { v -> onUpdateSettings { it.copy(beamWidth = (v / 20f).roundToInt() * 20) } }
                    )
                    CompactSliderRow(
                        label = "每格滑动耗时（2秒/3秒极速可调至24ms）",
                        valueText = "${settings.stepDurationMs} ms",
                        value = settings.stepDurationMs.toFloat(),
                        valueRange = 20f..120f,
                        onValueChange = { v -> onUpdateSettings { it.copy(stepDurationMs = v.roundToLong()) } }
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val presets = listOf("2秒极速" to 24L, "3秒快转" to 38L, "标准" to 68L, "稳健" to 95L)
                        for ((label, ms) in presets) {
                            val selected = kotlin.math.abs(settings.stepDurationMs - ms) <= 4L
                            FilterChip(
                                selected = selected,
                                onClick = { onUpdateSettings { it.copy(stepDurationMs = ms) } },
                                label = { Text(label, fontSize = 10.5.sp) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(28.dp)
                            )
                        }
                    }
                    CompactSliderRow(
                        label = "按住重算显形延时（问号‧转/风化‧转）",
                        valueText = "${settings.holdReplanDelayMs} ms",
                        value = settings.holdReplanDelayMs.toFloat(),
                        valueRange = 200f..800f,
                        onValueChange = { v -> onUpdateSettings { it.copy(holdReplanDelayMs = (v / 20f).roundToLong() * 20L) } }
                    )
                    CompactSliderRow(
                        label = "执行前倒数",
                        valueText = "${settings.countdownSeconds} 秒",
                        value = settings.countdownSeconds.toFloat(),
                        valueRange = 0f..3f,
                        steps = 2,
                        onValueChange = { v -> onUpdateSettings { it.copy(countdownSeconds = v.roundToInt()) } }
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 2.dp, top = 2.dp)
    )
}

@Composable
private fun CompactPermissionRow(
    title: String,
    subtitle: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (granted) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (granted) Color(0xFF4CAF50) else Color(0xFFFFA000),
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(title, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 16.sp)
                Text(subtitle, fontSize = 10.5.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(6.dp))
        FilledTonalButton(
            onClick = onAction,
            enabled = !granted || actionLabel == "去开启",
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            modifier = Modifier.height(28.dp),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(if (granted) "已就绪" else actionLabel, fontSize = 11.5.sp)
        }
    }
}

@Composable
private fun CompactSwitchRow(
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
            Text(title, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp)
            Text(subtitle, fontSize = 10.5.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(6.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun CompactSliderRow(
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
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(valueText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.height(28.dp)
        )
    }
}
