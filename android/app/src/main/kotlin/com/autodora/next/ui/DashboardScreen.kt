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
    var showHyperOsGuide by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AutoDora-NEXT", fontWeight = FontWeight.Bold)
                        Text(
                            "免 Root 智能转珠 · C++20 引擎 · 动态 6×5/6×8 违建",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 6.dp) {
                Button(
                    onClick = onToggleOverlayService,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .height(54.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (health.overlayRunning) Color(0xFFD32F2F) else Color(0xFF00897B)
                    )
                ) {
                    Icon(
                        imageVector = if (health.overlayRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (health.overlayRunning) "停止悬浮窗助手" else "启动悬浮窗助手",
                        style = MaterialTheme.typography.titleMedium,
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Permission & Service Health Cards
            Text("一、免 Root 核心权限状态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            PermissionStatusRow(
                title = "1. 悬浮窗权限 (SYSTEM_ALERT_WINDOW)",
                subtitle = "用于绘制透传路径轨迹、三态球与急停按钮",
                granted = health.overlayGranted,
                actionLabel = "去授权",
                onAction = onRequestOverlay
            )

            PermissionStatusRow(
                title = "2. 免 Root 无障碍自动转珠",
                subtitle = "通过 AccessibilityService 执行平滑滑动与斜转",
                granted = health.accessibilityConnected,
                actionLabel = "去开启",
                onAction = onOpenAccessibility
            )

            PermissionStatusRow(
                title = "3. 免 Root 屏幕采集 (MediaProjection)",
                subtitle = "Android 15 弹窗请务必选择「整个屏幕」",
                granted = health.mediaProjectionReady,
                actionLabel = if (health.mediaProjectionReady) "已就绪" else "立即授权",
                onAction = onRequestMediaProjection
            )

            if (!health.notificationsGranted) {
                PermissionStatusRow(
                    title = "4. 前台服务通知权限 (Android 13+)",
                    subtitle = "防止系统后台回收录屏与悬浮窗服务",
                    granted = false,
                    actionLabel = "允许",
                    onAction = onRequestNotifications
                )
            }

            // 2. Xiaomi 15 / HyperOS dedicated setup tip card
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "小米 15 / HyperOS 专属设置指引",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(onClick = { showHyperOsGuide = !showHyperOsGuide }) {
                            Text(if (showHyperOsGuide) "收起" else "展开")
                        }
                    }
                    AnimatedVisibility(visible = showHyperOsGuide) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("• 若无障碍开关显示灰色受限：点下方「打开应用详情」→ 底部或右上角「允许受限设置」后再开启无障碍。", style = MaterialTheme.typography.bodySmall)
                            Text("• 权限管理中同步开启「显示悬浮窗」与「后台弹出界面」。", style = MaterialTheme.typography.bodySmall)
                            Text("• 省电策略设为「无限制」，最近任务卡片下拉加锁防 HyperOS 杀后台。", style = MaterialTheme.typography.bodySmall)
                            Text("• 录屏授权弹窗选择「整个屏幕」，首次进战斗可点悬浮窗「校准」对齐 6×5 盘面。", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(4.dp))
                            OutlinedButton(onClick = onOpenAppDetails) {
                                Text("打开 AutoDora-NEXT 应用详情页")
                            }
                        }
                    }
                }
            }

            // 3. On-Device C++20 JNI Benchmark Self-Test
            ElevatedCard {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("二、本机 C++20 NDK 引擎实测", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text("直接验证本机原生 .so 算路速度（6×5 & 6×8 违建）", style = MaterialTheme.typography.bodySmall)
                        }
                        FilledTonalButton(
                            onClick = onRunNativeBenchmark,
                            enabled = !isBenchmarking
                        ) {
                            Text(if (isBenchmarking) "测试中..." else "一键压测")
                        }
                    }
                    if (benchmarkReport != null) {
                        Text(
                            text = benchmarkReport,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // 4. Core Solver & Touch Parameters
            Text("三、算路与免 Root 触控参数", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            ElevatedCard {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SwitchRow(
                        title = "试跑确认模式（推荐）",
                        subtitle = "识图算路后先画线预览，点击悬浮窗「执行」再自动滑动",
                        checked = settings.testModeOnlyDraw,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(testModeOnlyDraw = v) } }
                    )
                    HorizontalDivider()
                    SwitchRow(
                        title = "允许斜转（8 方向移动）",
                        subtitle = "45° 中心对角优化，高 C 与解盾更强",
                        checked = settings.allowDiagonalMove,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(allowDiagonalMove = v) } }
                    )
                    HorizontalDivider()
                    SwitchRow(
                        title = "自动识别 6×8 违建盘面",
                        subtitle = "每回合自动向上探查第 6~8 行，违建回合秒切 48 格",
                        checked = settings.calibration.autoDetectWeijian,
                        onCheckedChange = { v ->
                            onUpdateSettings { it.copy(calibration = it.calibration.copy(autoDetectWeijian = v)) }
                        }
                    )
                    HorizontalDivider()
                    SwitchRow(
                        title = "拟人化微抖动滑动",
                        subtitle = "在路径中段加入细微速度与像素扰动",
                        checked = settings.humanMode,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(humanMode = v) } }
                    )
                    HorizontalDivider()
                    SwitchRow(
                        title = "显示独立红色急停按钮",
                        subtitle = "转珠或算路过程中随时一键中止",
                        checked = settings.showEmergencyStop,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(showEmergencyStop = v) } }
                    )

                    HorizontalDivider()
                    Text("最大步数上限：${settings.maxSteps} 步", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Slider(
                        value = settings.maxSteps.toFloat(),
                        onValueChange = { v -> onUpdateSettings { it.copy(maxSteps = v.roundToInt()) } },
                        valueRange = 10f..60f,
                        steps = 24
                    )

                    Text("搜索算力档位（Beam Width）：${settings.beamWidth}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Slider(
                        value = settings.beamWidth.toFloat(),
                        onValueChange = { v -> onUpdateSettings { it.copy(beamWidth = (v / 20f).roundToInt() * 20) } },
                        valueRange = 60f..400f
                    )

                    Text("每格滑动耗时：${settings.stepDurationMs} ms（越小越快）", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Slider(
                        value = settings.stepDurationMs.toFloat(),
                        onValueChange = { v -> onUpdateSettings { it.copy(stepDurationMs = v.roundToLong()) } },
                        valueRange = 40f..120f
                    )

                    Text("执行前倒数：${settings.countdownSeconds} 秒", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Slider(
                        value = settings.countdownSeconds.toFloat(),
                        onValueChange = { v -> onUpdateSettings { it.copy(countdownSeconds = v.roundToInt()) } },
                        valueRange = 0f..3f,
                        steps = 2
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PermissionStatusRow(
    title: String,
    subtitle: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    ElevatedCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (granted) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (granted) Color(0xFF4CAF50) else Color(0xFFFFA000)
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = onAction, enabled = !granted || actionLabel == "去开启") {
                Text(if (granted) "已就绪" else actionLabel)
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
