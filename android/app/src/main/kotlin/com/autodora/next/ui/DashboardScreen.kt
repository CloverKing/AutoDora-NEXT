package com.autodora.next.ui

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.autodora.next.data.*
import com.autodora.next.model.*
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

private enum class HomeTab(val label: String) {
    OVERVIEW("概览"),
    TEAMS("队伍"),
    SHIELDS("解盾"),
    SETTINGS("设置")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(
    health: PermissionHealthState,
    settings: AppSettings,
    presets: List<TeamPresetRecord>,
    activeWaveChips: List<WaveShieldChip>,
    lastOcrSummary: String?,
    benchmarkReport: String?,
    isBenchmarking: Boolean,
    onRequestOverlay: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenAppDetails: () -> Unit,
    onRequestMediaProjection: () -> Unit,
    onRequestNotifications: () -> Unit,
    onToggleOverlayService: () -> Unit,
    onRunNativeBenchmark: () -> Unit,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onSelectPreset: (String) -> Unit,
    onSavePreset: (TeamBasePreset, Boolean) -> Unit,
    onDuplicatePreset: (TeamBasePreset) -> Unit,
    onDeletePreset: (String) -> Unit,
    onToggleWaveChip: (WaveShieldChip) -> Unit,
    onClearWaveChips: () -> Unit,
    onParseSkillText: (String) -> List<WaveShieldChip>
) {
    var activeTab by remember { mutableStateOf(HomeTab.OVERVIEW) }
    var editingPreset by remember { mutableStateOf<Pair<TeamBasePreset, Boolean>?>(null) }

    val activePresetRecord = presets.firstOrNull { it.preset.id == settings.activePresetId }
        ?: presets.firstOrNull()
    val activePresetName = activePresetRecord?.preset?.name ?: "通用高连击队"

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
                            text = "免 Root 智能转珠 · C++20 引擎 · 动态盘面",
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
                                text = if (health.overlayRunning) "助手运行中" else "未启动",
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
            NavigationBar {
                HomeTab.entries.forEach { tab ->
                    val selected = activeTab == tab
                    val icon = when (tab) {
                        HomeTab.OVERVIEW -> Icons.Outlined.Dashboard
                        HomeTab.TEAMS -> Icons.Outlined.Groups
                        HomeTab.SHIELDS -> Icons.Outlined.Shield
                        HomeTab.SETTINGS -> Icons.Outlined.Tune
                    }
                    NavigationBarItem(
                        selected = selected,
                        onClick = { activeTab = tab },
                        icon = {
                            if (tab == HomeTab.SHIELDS && activeWaveChips.isNotEmpty()) {
                                BadgedBox(badge = { Badge { Text("${activeWaveChips.size}") } }) {
                                    Icon(icon, contentDescription = tab.label)
                                }
                            } else {
                                Icon(icon, contentDescription = tab.label)
                            }
                        },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (activeTab) {
                HomeTab.OVERVIEW -> OverviewTab(
                    health = health,
                    settings = settings,
                    activePresetName = activePresetName,
                    activeWaveChips = activeWaveChips,
                    onRequestOverlay = onRequestOverlay,
                    onOpenAccessibility = onOpenAccessibility,
                    onOpenAppDetails = onOpenAppDetails,
                    onRequestMediaProjection = onRequestMediaProjection,
                    onRequestNotifications = onRequestNotifications,
                    onToggleOverlayService = onToggleOverlayService,
                    onNavigateTab = { activeTab = it }
                )

                HomeTab.TEAMS -> TeamsTab(
                    presets = presets,
                    activePresetId = settings.activePresetId,
                    onSelectPreset = onSelectPreset,
                    onCreateNew = {
                        editingPreset = TeamBasePreset(id = "", name = "", description = "") to false
                    },
                    onEdit = { rec -> editingPreset = rec.preset to rec.isBuiltIn },
                    onDuplicate = onDuplicatePreset,
                    onDelete = onDeletePreset
                )

                HomeTab.SHIELDS -> ShieldsTab(
                    activeWaveChips = activeWaveChips,
                    lastOcrSummary = lastOcrSummary,
                    onToggleWaveChip = onToggleWaveChip,
                    onClearWaveChips = onClearWaveChips,
                    onParseSkillText = onParseSkillText
                )

                HomeTab.SETTINGS -> SettingsTab(
                    settings = settings,
                    benchmarkReport = benchmarkReport,
                    isBenchmarking = isBenchmarking,
                    onUpdateSettings = onUpdateSettings,
                    onRunNativeBenchmark = onRunNativeBenchmark
                )
            }
        }
    }

    editingPreset?.let { (preset, isBuiltIn) ->
        TeamPresetEditorDialog(
            initial = preset,
            isBuiltIn = isBuiltIn,
            onDismiss = { editingPreset = null },
            onConfirm = { updated ->
                onSavePreset(updated, isBuiltIn)
                editingPreset = null
            }
        )
    }
}

// ==================== Tab 0: Overview Hub ====================
@Composable
private fun OverviewTab(
    health: PermissionHealthState,
    settings: AppSettings,
    activePresetName: String,
    activeWaveChips: List<WaveShieldChip>,
    onRequestOverlay: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenAppDetails: () -> Unit,
    onRequestMediaProjection: () -> Unit,
    onRequestNotifications: () -> Unit,
    onToggleOverlayService: () -> Unit,
    onNavigateTab: (HomeTab) -> Unit
) {
    var showHyperOsGuide by remember { mutableStateOf(false) }
    val speedPreset = SwipeSpeedPreset.fromStepDurationMs(settings.stepDurationMs)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Hero Launch & Live Strategy Card
        ElevatedCard(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "当前作战配置",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = activePresetName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    FilledTonalButton(
                        onClick = { onNavigateTab(HomeTab.TEAMS) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("切换队伍", style = MaterialTheme.typography.labelMedium)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    InfoPill(
                        label = "步数上限 ${settings.maxSteps} 步",
                        onClick = { onNavigateTab(HomeTab.SETTINGS) },
                        modifier = Modifier.weight(1f)
                    )
                    InfoPill(
                        label = "速度 ${speedPreset.label}",
                        onClick = { onNavigateTab(HomeTab.SETTINGS) },
                        modifier = Modifier.weight(1f)
                    )
                    InfoPill(
                        label = if (activeWaveChips.isEmpty()) "波次盾 0" else "波次盾 ${activeWaveChips.size}",
                        onClick = { onNavigateTab(HomeTab.SHIELDS) },
                        modifier = Modifier.weight(1f)
                    )
                }

                Button(
                    onClick = onToggleOverlayService,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
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
                        text = if (health.overlayRunning) "停止悬浮助手" else "启动悬浮助手",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Core Permissions Checklist
        SectionHeader("运行权限检查")
        ElevatedCard(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                PermissionItemRow(
                    title = "1. 悬浮窗权限",
                    subtitle = "显示系统配色悬浮胶囊、路径预览与游戏内调参抽屉",
                    granted = health.overlayGranted,
                    actionLabel = "去授权",
                    onAction = onRequestOverlay
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PermissionItemRow(
                    title = "2. 无障碍自动转珠",
                    subtitle = "免 Root 连续斜转、角色珠下拉与按住重算续滑",
                    granted = health.accessibilityConnected,
                    actionLabel = "去开启",
                    onAction = onOpenAccessibility
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PermissionItemRow(
                    title = "3. 屏幕采集 (MediaProjection)",
                    subtitle = "系统弹窗请保持选择「整个屏幕」",
                    granted = health.mediaProjectionReady,
                    actionLabel = "授权",
                    onAction = onRequestMediaProjection
                )
                if (!health.notificationsGranted) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    PermissionItemRow(
                        title = "4. 前台通知权限",
                        subtitle = "保持悬浮助手在后台稳定运行",
                        granted = false,
                        actionLabel = "允许",
                        onAction = onRequestNotifications
                    )
                }
            }
        }

        // Quick Usage Guide
        OutlinedCard(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.outlinedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "悬浮胶囊操作说明",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text("• 「算」识盘解盾并画线预览；「◀ / ▶」切换候选解；「行」统一执行转珠（开「按住转」时执行按住重算）。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("• 「线」仅控制转珠路径显示；「校」控制盘面校准网格与四角拖拽；「停」随时一键急停。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("• 点「⋯」可展开按住转/记忆/颠倒/速度、4-Tab 解盾调参抽屉、1:1 盘面编绘与离线 OCR 识敌技。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Collapsible HyperOS / Restricted Settings Guide
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
                            text = "HyperOS / 受限设置配置指引",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        TextButton(onClick = onOpenAppDetails) {
                            Text("应用详情", style = MaterialTheme.typography.labelMedium)
                        }
                        TextButton(onClick = { showHyperOsGuide = !showHyperOsGuide }) {
                            Text(if (showHyperOsGuide) "收起" else "展开", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                AnimatedVisibility(visible = showHyperOsGuide) {
                    Column(
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("• 无障碍灰显受限：点右上「应用详情」→ 右上角菜单开启「允许受限设置」后再启用无障碍。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("• 权限管理中允许「显示悬浮窗」与「后台弹出界面」，省电策略建议设为「无限制」。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ==================== Tab 1: Room Team Presets ====================
@Composable
private fun TeamsTab(
    presets: List<TeamPresetRecord>,
    activePresetId: String,
    onSelectPreset: (String) -> Unit,
    onCreateNew: () -> Unit,
    onEdit: (TeamPresetRecord) -> Unit,
    onDuplicate: (TeamBasePreset) -> Unit,
    onDelete: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "队伍基底预设库",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "整场生效的队伍习惯，与游戏内悬浮抽屉实时同步",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FilledTonalButton(onClick = onCreateNew) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("新建队伍")
            }
        }

        val items = presets.ifEmpty {
            TeamPresetRepository.defaultBuiltInPresets().map { TeamPresetRecord(it, true, 0L) }
        }
        for (rec in items) {
            val p = rec.preset
            val isActive = p.id == activePresetId
            val tags = buildList {
                add("上限 ${p.baseConfig.maxSteps} 步")
                if (p.baseConfig.elementRules.any { it.minMatchCount == 2 }) add("含二消")
                if (p.baseConfig.columnRules.any { it.enabled }) add("直排昇华")
                if (p.baseConfig.useCharacterOrb) add("${p.baseConfig.characterOrbCol + 1}号角色珠")
                if (p.baseConfig.allowDiagonalMatch) add("允许斜消")
            }

            ElevatedCard(
                shape = RoundedCornerShape(16.dp),
                colors = if (isActive) {
                    CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                } else {
                    CardDefaults.elevatedCardColors()
                }
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = p.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                                if (isActive) {
                                    Badge(containerColor = MaterialTheme.colorScheme.primary) {
                                        Text("使用中", color = MaterialTheme.colorScheme.onPrimary)
                                    }
                                }
                            }
                            if (p.description.isNotBlank()) {
                                Text(
                                    text = p.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Text(
                        text = tags.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!isActive) {
                            Button(
                                onClick = { onSelectPreset(p.id) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text("启用", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        FilledTonalButton(
                            onClick = { onEdit(rec) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text("编辑", style = MaterialTheme.typography.labelMedium)
                        }
                        OutlinedButton(
                            onClick = { onDuplicate(p) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("复制", style = MaterialTheme.typography.labelMedium)
                        }
                        if (!rec.isBuiltIn) {
                            Spacer(Modifier.weight(1f))
                            TextButton(
                                onClick = { onDelete(p.id) },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("删除", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==================== Tab 2: Wave Shield Strategy & Offline OCR Tester ====================
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShieldsTab(
    activeWaveChips: List<WaveShieldChip>,
    lastOcrSummary: String?,
    onToggleWaveChip: (WaveShieldChip) -> Unit,
    onClearWaveChips: () -> Unit,
    onParseSkillText: (String) -> List<WaveShieldChip>
) {
    var testSkillText by remember { mutableStateOf("") }
    val activeIds = remember(activeWaveChips) { activeWaveChips.map { it.id }.toSet() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Active Wave Shields Summary
        ElevatedCard(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "当前已激活本波敌技盾 (${activeWaveChips.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (activeWaveChips.isNotEmpty()) {
                        TextButton(onClick = onClearWaveChips) {
                            Text("清空全部", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                if (activeWaveChips.isEmpty()) {
                    Text(
                        text = "尚未叠加临时波次盾。可在下方勾选，或在战斗中打开敌技弹窗点悬浮栏「OCR 识敌技」自动填入。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (chip in activeWaveChips) {
                            InputChip(
                                selected = true,
                                onClick = { onToggleWaveChip(chip) },
                                label = { Text(chip.label, style = MaterialTheme.typography.labelMedium) },
                                trailingIcon = {
                                    Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                                }
                            )
                        }
                    }
                }
                if (!lastOcrSummary.isNullOrBlank()) {
                    Text(
                        text = lastOcrSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Offline Enemy Skill Rule Parser Workbench
        SectionHeader("离线敌技词条解析（繁 / 简自动归一）")
        ElevatedCard(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = testSkillText,
                    onValueChange = { testSkillText = it },
                    label = { Text("输入或粘贴敌技描述（如：五屬強化盾、首消 7 Combo、拼圖盾-限）") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        onClick = { testSkillText = "五屬強化盾\n首消 7 Combo 盾\n拼圖盾-限" },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("示例 1", style = MaterialTheme.typography.labelMedium)
                    }
                    OutlinedButton(
                        onClick = { testSkillText = "軌跡燃燒\n雙十字盾\n首消禁心\n數字盾 18 顆符石" },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("示例 2", style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { if (testSkillText.isNotBlank()) onParseSkillText(testSkillText) },
                        enabled = testSkillText.isNotBlank()
                    ) {
                        Text("解析并导入")
                    }
                }
            }
        }

        // Categorized Wave Shield Catalog
        val groups = listOf(
            "连击类敌技盾" to ShieldCategory.COMBO,
            "属性与数量盾" to ShieldCategory.COUNT_AND_ELEMENT,
            "形状与拼图盾" to ShieldCategory.SHAPE_AND_PUZZLE,
            "地形、障碍与路径" to ShieldCategory.HAZARD_AND_PATH
        )
        val allChips = remember { WaveShieldCatalog.allChips() }
        for ((title, category) in groups) {
            SectionHeader(title)
            ElevatedCard(shape = RoundedCornerShape(16.dp)) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (chip in allChips.filter { it.category == category }) {
                        val selected = chip.id in activeIds
                        FilterChip(
                            selected = selected,
                            onClick = { onToggleWaveChip(chip) },
                            label = { Text(chip.label, style = MaterialTheme.typography.labelMedium) }
                        )
                    }
                }
            }
        }
    }
}

// ==================== Tab 3: Settings, Theme, Speed & Benchmark ====================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTab(
    settings: AppSettings,
    benchmarkReport: String?,
    isBenchmarking: Boolean,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onRunNativeBenchmark: () -> Unit
) {
    val context = LocalContext.current
    val overlayPalette = remember(settings.themeMode) { OverlayThemeResolver.resolve(context, settings.themeMode) }

    var maxStepsInput by remember { mutableStateOf(settings.maxSteps.toString()) }
    LaunchedEffect(settings.maxSteps) {
        if (maxStepsInput.toIntOrNull() != settings.maxSteps) {
            maxStepsInput = settings.maxSteps.toString()
        }
    }
    val parsedSteps = maxStepsInput.toIntOrNull()
    val maxStepsError = maxStepsInput.isNotEmpty() && (parsedSteps == null || parsedSteps !in 8..300)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Appearance & Theme Mode
        SectionHeader("外观与主题风格")
        ElevatedCard(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "系统 Material You 配色（启动页与悬浮栏实时同步）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ThemeMode.entries.forEachIndexed { idx, mode ->
                        SegmentedButton(
                            selected = settings.themeMode == mode,
                            onClick = { onUpdateSettings { it.copy(themeMode = mode) } },
                            shape = SegmentedButtonDefaults.itemShape(idx, ThemeMode.entries.size),
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

        // 2. Unified Thermal Speed Presets (1:1 synced with Floating Bar!)
        SectionHeader("划动速度档位（与悬浮栏 1:1 同步）")
        val activeSpeed = SwipeSpeedPreset.fromStepDurationMs(settings.stepDurationMs)
        ElevatedCard(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "快 → 慢四档语义色阶",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${activeSpeed.label} · ${settings.stepDurationMs} ms/格",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (preset in SwipeSpeedPreset.entries) {
                        val selected = activeSpeed == preset
                        val style = overlayPalette.speedStyle(preset)
                        Surface(
                            onClick = { onUpdateSettings { it.copy(stepDurationMs = preset.stepDurationMs) } },
                            shape = RoundedCornerShape(10.dp),
                            color = if (selected) Color(style.bg) else MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(
                                width = if (selected) 1.5.dp else 1.dp,
                                color = if (selected) Color(style.stroke) else MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = preset.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selected) Color(style.fg) else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                                Text(
                                    text = "${preset.stepDurationMs}ms",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) Color(style.fg) else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                Slider(
                    value = settings.stepDurationMs.toFloat().coerceIn(20f, 120f),
                    onValueChange = { v -> onUpdateSettings { it.copy(stepDurationMs = v.roundToLong()) } },
                    valueRange = 20f..120f
                )
            }
        }

        // 3. Solver Budget & Step Input (8..300)
        SectionHeader("解盘引擎与步数设置")
        ElevatedCard(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
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
                    label = { Text("最大步数上限 (8 ~ 300)") },
                    suffix = { Text("步") },
                    supportingText = {
                        Text(
                            if (maxStepsError) "请输入 8 ~ 300 之间的整数"
                            else "当前生效：${settings.maxSteps} 步（> 60 步自动放宽算路预算，随时可点「停」中止）"
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (stepVal in listOf(30, 60, 100, 150, 200)) {
                        FilterChip(
                            selected = settings.maxSteps == stepVal,
                            onClick = {
                                maxStepsInput = stepVal.toString()
                                onUpdateSettings { it.copy(maxSteps = stepVal) }
                            },
                            label = { Text("$stepVal", style = MaterialTheme.typography.labelMedium) },
                            modifier = Modifier.weight(1f)
                        )
                    }
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
                    label = "按住重算显形延时（问号‧转 / 风化‧转）",
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
            }
        }

        // 4. Mode & Control Switches
        SectionHeader("交互与辅助模式")
        ElevatedCard(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                SwitchSettingRow(
                    title = "试跑确认模式（推荐）",
                    subtitle = "点击「算」后先画线预览，确认后再点「行」执行",
                    checked = settings.testModeOnlyDraw,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(testModeOnlyDraw = v) } }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SwitchSettingRow(
                    title = "按住转珠重算模式（按住转）",
                    subtitle = "开启后点「行」将按住起手珠显形，不松手实时重算续滑",
                    checked = settings.holdReplanEnabled,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(holdReplanEnabled = v) } }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SwitchSettingRow(
                    title = "按住重算微移 1 格触发",
                    subtitle = "适配需起手轻移才翻开问号珠的「问号‧转」关卡",
                    checked = settings.holdReplanNudge,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(holdReplanNudge = v) } }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SwitchSettingRow(
                    title = "允许 8 向斜转",
                    subtitle = "启用 45° 对角穿插优化，高连击与解盾更强",
                    checked = settings.allowDiagonalMove,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(allowDiagonalMove = v) } }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SwitchSettingRow(
                    title = "自动识别 6×8 违建扩版",
                    subtitle = "向上探查第 6~8 行并受血条隔离保护防误判",
                    checked = settings.calibration.autoDetectWeijian,
                    onCheckedChange = { v ->
                        onUpdateSettings { it.copy(calibration = it.calibration.copy(autoDetectWeijian = v)) }
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SwitchSettingRow(
                    title = "先露后藏问号珠记忆",
                    subtitle = "打全版问号关时开启，自动锁定翻面前清晰盘面",
                    checked = settings.flashRevealMemoryEnabled,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(flashRevealMemoryEnabled = v) } }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SwitchSettingRow(
                    title = "拟人化微抖动滑动",
                    subtitle = "滑动轨迹中段加入轻微速度与坐标扰动",
                    checked = settings.humanMode,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(humanMode = v) } }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SwitchSettingRow(
                    title = "悬浮主栏内嵌急停按钮（停）",
                    subtitle = "算路或划珠期间可随时一键中止",
                    checked = settings.showEmergencyStop,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(showEmergencyStop = v) } }
                )
            }
        }

        // 5. On-Device C++20 NDK Benchmark
        SectionHeader("本机原生引擎压测")
        ElevatedCard(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "C++20 NDK 求解器性能验证",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "在本机实测 6×5 标准盘与 6×8 违建盘算路耗时",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    FilledTonalButton(
                        onClick = onRunNativeBenchmark,
                        enabled = !isBenchmarking
                    ) {
                        Text(if (isBenchmarking) "测试中..." else "开始压测")
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

// ==================== Shared Material 3 Building Blocks ====================
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
private fun InfoPill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
        )
    }
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
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(8.dp))
        FilledTonalButton(
            onClick = onAction,
            enabled = !granted || actionLabel == "去开启",
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Text(if (granted) "已就绪" else actionLabel, style = MaterialTheme.typography.labelMedium)
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
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
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
