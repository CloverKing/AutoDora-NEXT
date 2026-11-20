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
    STRATEGIES("策略"),
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
                        HomeTab.STRATEGIES -> Icons.Outlined.Tune
                        HomeTab.SHIELDS -> Icons.Outlined.Shield
                        HomeTab.SETTINGS -> Icons.Outlined.Settings
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

                HomeTab.STRATEGIES -> StrategiesTab(
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
                    settings = settings,
                    activeWaveChips = activeWaveChips,
                    lastOcrSummary = lastOcrSummary,
                    onUpdateSettings = onUpdateSettings,
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
                        onClick = { onNavigateTab(HomeTab.STRATEGIES) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("切换策略", style = MaterialTheme.typography.labelMedium)
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
                Text("• 点「⋯」可展开按住转/自动识技/颠倒/速度、4-Tab 解盾调参抽屉、1:1 盘面编绘与一键识敌技（全屏问号珠记忆已常驻开启）。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

// ==================== Tab 1: Room Strategy / Goal Profiles ====================
@Composable
private fun StrategiesTab(
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
                    text = "作战策略与目标库",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "配置整场消珠目标、单属性四元组、直排昇华与队长特性，与悬浮窗实时同步",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FilledTonalButton(onClick = onCreateNew) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("新建策略")
            }
        }

        val items = presets.ifEmpty {
            TeamPresetRepository.defaultBuiltInPresets().map { TeamPresetRecord(it, true, 0L) }
        }
        for (rec in items) {
            val p = rec.preset
            val cfg = p.baseConfig
            val isActive = p.id == activePresetId
            val tags = buildList {
                add(cfg.goalMode.shortLabel)
                add("上限 ${cfg.maxSteps} 步")
                add("算力 ${cfg.beamWidth}")
                if (cfg.earlyStopOnGoal) add("最快解")
                if (cfg.forceSplitThree) add("强制切3")
                if (cfg.firstWaveOnlyScoring) add("只计首批")
                if (cfg.elementRules.any { it.minMatchCount == 2 }) add("含二消")
                if (cfg.elementRules.any { it.targetShape != TargetShape.ANY }) add("指定形态")
                if (cfg.columnRules.any { it.enabled }) add("直排昇华")
                if (cfg.useCharacterOrb) add("${cfg.characterOrbCol + 1}号角色珠")
                if (cfg.startPreset != StartPointPreset.ANY) {
                    add(if (cfg.startPreset == StartPointPreset.BOTTOM_LEFT) "左下起手" else "右下起手")
                }
                if (cfg.allowDiagonalMatch) add("允许斜消")
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
                                        Text("生效中", color = MaterialTheme.colorScheme.onPrimary)
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
                            Text("配置策略", style = MaterialTheme.typography.labelMedium)
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

// ==================== Tab 2: Abstract Parameterized Shield Controls & Auto Enemy Skill Engine ====================
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ShieldsTab(
    settings: AppSettings,
    activeWaveChips: List<WaveShieldChip>,
    lastOcrSummary: String?,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onToggleWaveChip: (WaveShieldChip) -> Unit,
    onClearWaveChips: () -> Unit,
    onParseSkillText: (String) -> List<WaveShieldChip>
) {
    var testSkillText by remember { mutableStateOf("") }
    var showRuleTester by remember { mutableStateOf(false) }

    val byId = remember(activeWaveChips) { activeWaveChips.associateBy { it.id } }
    val activeIds = remember(activeWaveChips) { activeWaveChips.map { it.id }.toSet() }

    // Parameterized local stepper state synced with active chips
    var comboNumber by remember {
        mutableStateOf(
            byId["exact_first_combo"]?.paramInt
                ?: byId["exact_combo"]?.paramInt
                ?: byId["min_combo"]?.paramInt
                ?: 6
        )
    }
    var digitalOrbs by remember { mutableStateOf(byId["digital_orb_count"]?.paramInt ?: 18) }
    var maxBurnHurts by remember {
        mutableStateOf(((byId["zero_trail_burning"]?.paramInt ?: 0) ushr 8) and 0xFF)
    }
    var burnTrailLen by remember {
        mutableStateOf((byId["zero_trail_burning"]?.paramInt ?: 0) and 0xFF)
    }
    var stepLimitVal by remember { mutableStateOf(byId["step_limit"]?.paramInt ?: 15) }

    val elemNames = listOf("水", "火", "木", "光", "暗", "心")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Auto Enemy Skill Intelligence Hub
        SectionHeader("自动敌技识别中枢")
        ElevatedCard(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SwitchSettingRow(
                    title = "自动识别敌技（开启即全自动）",
                    subtitle = "切换新波次时自动触发识别，无需每回合手动点击",
                    checked = settings.autoEnemySkillEnabled,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(autoEnemySkillEnabled = v) } }
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    EnemySkillScanMode.entries.forEachIndexed { idx, mode ->
                        SegmentedButton(
                            selected = settings.enemySkillScanMode == mode,
                            onClick = { onUpdateSettings { it.copy(enemySkillScanMode = mode) } },
                            shape = SegmentedButtonDefaults.itemShape(idx, EnemySkillScanMode.entries.size)
                        ) {
                            Text(mode.shortLabel, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                    }
                }
                Text(
                    text = when (settings.enemySkillScanMode) {
                        EnemySkillScanMode.HYBRID -> "战斗中先零点击快识状态图标与盘面特效；进入新波次后自动点右上角齿轮 → 逐个点开敌技前小三角 ▶ 展开说明 → 超屏自动下划 → 离线 OCR 挂载后自动点取消返回。"
                        EnemySkillScanMode.AUTO_GEAR_OCR -> "每波自动点右上角齿轮进入「關卡資訊」，自动展开全部技能小三角 ▶、多敌切换与下划滚动，100% 精准识别所有敌技并过滤 2026 灰字已无视项。"
                        EnemySkillScanMode.BATTLE_ICONS -> "不点击游戏界面，仅在战斗画面直接匹配敌人状态图标与盘面金框拼图/暴风特效。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Active composed wave shields summary
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "本波已挂载解盾 (${activeWaveChips.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { showRuleTester = !showRuleTester }) {
                            Text(if (showRuleTester) "收起词条测试" else "词条测试", style = MaterialTheme.typography.labelMedium)
                        }
                        if (activeWaveChips.isNotEmpty()) {
                            TextButton(onClick = onClearWaveChips) {
                                Text("清空", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
                if (activeWaveChips.isEmpty()) {
                    Text(
                        text = "当前无临时波次盾。下方参数化模块可自由组合，或开启自动识别后由助手自动填入。",
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

                AnimatedVisibility(visible = showRuleTester) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = testSkillText,
                            onValueChange = { testSkillText = it },
                            label = { Text("离线词条测试（如：首批消除 4 連擊、五屬強化盾、拼圖盾-限）") },
                            minLines = 2,
                            maxLines = 4,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedButton(
                                onClick = { testSkillText = "【固定 4 連擊盾】\n首批消除 4 連擊 (Combo)\n五屬強化盾\n拼圖盾-限" },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("示例", style = MaterialTheme.typography.labelMedium)
                            }
                            Spacer(Modifier.weight(1f))
                            Button(
                                onClick = { if (testSkillText.isNotBlank()) onParseSkillText(testSkillText) },
                                enabled = testSkillText.isNotBlank()
                            ) {
                                Text("解析导入")
                            }
                        }
                    }
                }
            }
        }

        // 2. Abstract Combo Shield Controller (连击盾参数器)
        SectionHeader("连击盾控制器（Combo）")
        ElevatedCard(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val curComboMode = when {
                    "min_combo" in activeIds -> "min_combo"
                    "exact_combo" in activeIds -> "exact_combo"
                    "exact_first_combo" in activeIds -> "exact_first_combo"
                    "zero_combo" in activeIds -> "zero_combo"
                    "odd_combo" in activeIds -> "odd_combo"
                    "even_combo" in activeIds -> "even_combo"
                    else -> "none"
                }

                fun applyComboMode(mode: String, num: Int) {
                    val chip = when (mode) {
                        "min_combo" -> WaveShieldChip("min_combo", "≥ $num Combo", ShieldCategory.COMBO, paramInt = num)
                        "exact_combo" -> WaveShieldChip("exact_combo", "指定 $num Combo", ShieldCategory.COMBO, paramInt = num)
                        "exact_first_combo" -> WaveShieldChip("exact_first_combo", "首消 $num Combo", ShieldCategory.COMBO, paramInt = num)
                        "zero_combo" -> WaveShieldChip("zero_combo", "0 Combo 空转", ShieldCategory.COMBO)
                        "odd_combo" -> WaveShieldChip("odd_combo", "奇数 Combo", ShieldCategory.COMBO)
                        "even_combo" -> WaveShieldChip("even_combo", "偶数 Combo", ShieldCategory.COMBO)
                        else -> null
                    }
                    ActiveStrategyStore.replaceGroup(WaveShieldCatalog.COMBO_EXCLUSIVE_IDS, chip)
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(
                        "none" to "不限制",
                        "min_combo" to "≥ ${comboNumber}C",
                        "exact_combo" to "指定 ${comboNumber}C",
                        "exact_first_combo" to "首消 ${comboNumber}C",
                        "zero_combo" to "0C 空转",
                        "odd_combo" to "奇数 C",
                        "even_combo" to "偶数 C"
                    ).forEach { (id, label) ->
                        FilterChip(
                            selected = curComboMode == id,
                            onClick = { applyComboMode(id, comboNumber) },
                            label = { Text(label, style = MaterialTheme.typography.labelMedium) }
                        )
                    }
                }

                if (curComboMode in setOf("min_combo", "exact_combo", "exact_first_combo")) {
                    NumericStepperRow(
                        label = "目标连击数",
                        valueText = "$comboNumber Combo",
                        onMinus = {
                            comboNumber = (comboNumber - 1).coerceIn(1, 15)
                            applyComboMode(curComboMode, comboNumber)
                        },
                        onPlus = {
                            comboNumber = (comboNumber + 1).coerceIn(1, 15)
                            applyComboMode(curComboMode, comboNumber)
                        }
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(
                        WaveShieldChip("first_wave_only", "只计首批", ShieldCategory.COMBO),
                        WaveShieldChip("same_combo_elements", "同属同 C", ShieldCategory.COMBO),
                        WaveShieldChip("equal_pair_light_dark", "光暗同等 C", ShieldCategory.COMBO),
                        WaveShieldChip("second_wave_combo", "次批叠珠 ≥1C", ShieldCategory.COMBO, paramInt = 1),
                        WaveShieldChip("same_elem_combos", "同属 3 组连击", ShieldCategory.COMBO, paramInt = 3)
                    ).forEach { chip ->
                        FilterChip(
                            selected = chip.id in activeIds,
                            onClick = { onToggleWaveChip(chip) },
                            label = { Text(chip.label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
        }

        // 3. Abstract Element & Count Shield Controller (属性矩阵 + 颗数盾)
        SectionHeader("属性与数量盾控制器")
        ElevatedCard(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "单属性波次约束（点击循环：不限 → 必消 → 强化 → 反首消 → 全禁 → 首消全清）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                fun currentElemMode(e: Int): Int = when {
                    "require_elem_$e" in activeIds -> 1
                    "require_ench_$e" in activeIds -> 2
                    "anti_first_wave_$e" in activeIds -> 3
                    "forbid_all_$e" in activeIds -> 4
                    "clear_all_element_$e" in activeIds -> 5
                    else -> 0
                }

                fun setElemMode(e: Int, mode: Int) {
                    val exclusive = setOf(
                        "require_elem_$e", "require_ench_$e", "anti_first_wave_$e",
                        "forbid_all_$e", "clear_all_element_$e"
                    )
                    val mask = 1 shl e
                    val name = elemNames[e]
                    val chip = when (mode) {
                        1 -> WaveShieldChip("require_elem_$e", "必消$name", ShieldCategory.COUNT_AND_ELEMENT, paramElementMask = mask)
                        2 -> WaveShieldChip("require_ench_$e", "强化$name", ShieldCategory.COUNT_AND_ELEMENT, paramElementMask = mask)
                        3 -> WaveShieldChip("anti_first_wave_$e", "反首消$name", ShieldCategory.COUNT_AND_ELEMENT, paramElementMask = mask)
                        4 -> WaveShieldChip("forbid_all_$e", "禁消$name", ShieldCategory.COUNT_AND_ELEMENT, paramElementMask = mask)
                        5 -> WaveShieldChip("clear_all_element_$e", "全清$name", ShieldCategory.COUNT_AND_ELEMENT, paramElementMask = mask)
                        else -> null
                    }
                    ActiveStrategyStore.replaceGroup(exclusive, chip)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (e in 0 until 6) {
                        val m = currentElemMode(e)
                        val modeShort = when (m) {
                            1 -> "必"
                            2 -> "强"
                            3 -> "反"
                            4 -> "禁"
                            5 -> "全"
                            else -> "·"
                        }
                        FilterChip(
                            selected = m != 0,
                            onClick = { setElemMode(e, (m + 1) % 6) },
                            label = {
                                Text(
                                    "${elemNames[e]}$modeShort",
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1
                                )
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Quick multi-element combo sets
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(
                        WaveShieldChip("three_elements", "水火木三属", ShieldCategory.COUNT_AND_ELEMENT),
                        WaveShieldChip("five_elements", "五属盾", ShieldCategory.COUNT_AND_ELEMENT),
                        WaveShieldChip("five_elements_enchanted", "五属强化盾", ShieldCategory.COUNT_AND_ELEMENT),
                        WaveShieldChip("first_wave_full_clear", "首消全版", ShieldCategory.COUNT_AND_ELEMENT),
                        WaveShieldChip("race_orb_shield", "种族符石首消", ShieldCategory.COUNT_AND_ELEMENT)
                    ).forEach { chip ->
                        FilterChip(
                            selected = chip.id in activeIds,
                            onClick = { onToggleWaveChip(chip) },
                            label = { Text(chip.label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                    val digOn = "digital_orb_count" in activeIds
                    FilterChip(
                        selected = digOn,
                        onClick = {
                            onToggleWaveChip(
                                WaveShieldChip("digital_orb_count", "数字盾 $digitalOrbs 颗", ShieldCategory.COUNT_AND_ELEMENT, paramInt = digitalOrbs)
                            )
                        },
                        label = { Text("数字盾 $digitalOrbs 颗", style = MaterialTheme.typography.labelSmall) }
                    )
                }
                if ("digital_orb_count" in activeIds) {
                    NumericStepperRow(
                        label = "首消精确颗数",
                        valueText = "$digitalOrbs 颗",
                        onMinus = {
                            digitalOrbs = (digitalOrbs - 1).coerceIn(6, 30)
                            ActiveStrategyStore.upsertChip(
                                WaveShieldChip("digital_orb_count", "数字盾 $digitalOrbs 颗", ShieldCategory.COUNT_AND_ELEMENT, paramInt = digitalOrbs)
                            )
                        },
                        onPlus = {
                            digitalOrbs = (digitalOrbs + 1).coerceIn(6, 30)
                            ActiveStrategyStore.upsertChip(
                                WaveShieldChip("digital_orb_count", "数字盾 $digitalOrbs 颗", ShieldCategory.COUNT_AND_ELEMENT, paramInt = digitalOrbs)
                            )
                        }
                    )
                }
            }
        }

        // 4. Abstract Shape & Puzzle Shield Controller (拼图 + 十字 + 几何形状)
        SectionHeader("形状与拼图盾控制器")
        ElevatedCard(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val curPuzzle = when {
                    "strict_puzzle" in activeIds -> "strict_puzzle"
                    "enchanted_puzzle" in activeIds -> "enchanted_puzzle"
                    "loose_puzzle" in activeIds -> "loose_puzzle"
                    else -> "none"
                }
                Text("拼图盾模式", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(
                        "none" to "关",
                        "loose_puzzle" to "普通拼图",
                        "strict_puzzle" to "严格(限)",
                        "enchanted_puzzle" to "强化拼图"
                    ).forEach { (id, lbl) ->
                        FilterChip(
                            selected = curPuzzle == id,
                            onClick = {
                                val chip = when (id) {
                                    "loose_puzzle" -> WaveShieldChip("loose_puzzle", "普通拼图盾", ShieldCategory.SHAPE_AND_PUZZLE)
                                    "strict_puzzle" -> WaveShieldChip("strict_puzzle", "严格拼图盾", ShieldCategory.SHAPE_AND_PUZZLE)
                                    "enchanted_puzzle" -> WaveShieldChip("enchanted_puzzle", "强化拼图盾", ShieldCategory.SHAPE_AND_PUZZLE)
                                    else -> null
                                }
                                ActiveStrategyStore.replaceGroup(WaveShieldCatalog.PUZZLE_EXCLUSIVE_IDS, chip)
                            },
                            label = { Text(lbl, style = MaterialTheme.typography.labelSmall, maxLines = 1) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                val curCross = byId["cross_shield"]?.paramInt
                    ?: byId["cross_shield_2"]?.paramInt
                    ?: byId["cross_shield_3"]?.paramInt
                    ?: 0
                Text("十字盾数量", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf(0 to "关", 1 to "单十字", 2 to "双十字", 3 to "三十字").forEach { (cnt, lbl) ->
                        FilterChip(
                            selected = curCross == cnt,
                            onClick = {
                                val chip = if (cnt == 0) null else WaveShieldChip(
                                    id = "cross_shield",
                                    label = lbl + "盾",
                                    category = ShieldCategory.SHAPE_AND_PUZZLE,
                                    paramInt = cnt
                                )
                                ActiveStrategyStore.replaceGroup(WaveShieldCatalog.CROSS_EXCLUSIVE_IDS, chip)
                            },
                            label = { Text(lbl, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        WaveShieldChip("l_shape", "L 形盾", ShieldCategory.SHAPE_AND_PUZZLE),
                        WaveShieldChip("t_shape", "T 形盾", ShieldCategory.SHAPE_AND_PUZZLE),
                        WaveShieldChip("square_2x2", "田字 2×2 盾", ShieldCategory.SHAPE_AND_PUZZLE),
                        WaveShieldChip("all_columns_ameliorate", "全直排 ≥ 4 消", ShieldCategory.SHAPE_AND_PUZZLE)
                    ).forEach { chip ->
                        FilterChip(
                            selected = chip.id in activeIds,
                            onClick = { onToggleWaveChip(chip) },
                            label = { Text(chip.label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
        }

        // 5. Abstract Path, Hazard & Step Controller (完整继承旧版燃烧路径/腐蚀路径参数!)
        SectionHeader("路径、地形与步数控制器")
        ElevatedCard(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val burnOn = "zero_trail_burning" in activeIds
                fun upsertBurn(hurts: Int, len: Int) {
                    val packed = (hurts shl 8) or (len and 0xFF)
                    val lenLabel = if (len == 0) "全程" else "${len}格"
                    ActiveStrategyStore.upsertChip(
                        WaveShieldChip(
                            id = "zero_trail_burning",
                            label = "轨迹燃烧(${hurts}伤/$lenLabel)",
                            category = ShieldCategory.HAZARD_AND_PATH,
                            paramInt = packed
                        )
                    )
                }
                SwitchSettingRow(
                    title = "轨迹燃烧保护（燃烧路径）",
                    subtitle = "设定轨迹尾迹长度与允许碰撞次数上限（继承旧版配置）",
                    checked = burnOn,
                    onCheckedChange = { enabled ->
                        if (enabled) upsertBurn(maxBurnHurts, burnTrailLen)
                        else ActiveStrategyStore.removeChip("zero_trail_burning")
                    }
                )
                if (burnOn) {
                    NumericStepperRow(
                        label = "最大允许碰撞次数",
                        valueText = "$maxBurnHurts 次",
                        onMinus = {
                            maxBurnHurts = (maxBurnHurts - 1).coerceIn(0, 10)
                            upsertBurn(maxBurnHurts, burnTrailLen)
                        },
                        onPlus = {
                            maxBurnHurts = (maxBurnHurts + 1).coerceIn(0, 10)
                            upsertBurn(maxBurnHurts, burnTrailLen)
                        }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = burnTrailLen == 0,
                            onClick = {
                                burnTrailLen = 0
                                upsertBurn(maxBurnHurts, 0)
                            },
                            label = { Text("轨迹长度：全程 (Max)", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = burnTrailLen == 6,
                            onClick = {
                                burnTrailLen = 6
                                upsertBurn(maxBurnHurts, 6)
                            },
                            label = { Text("轨迹长度：6 格", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val stepLimOn = "step_limit" in activeIds
                    FilterChip(
                        selected = stepLimOn,
                        onClick = {
                            onToggleWaveChip(
                                WaveShieldChip("step_limit", "限 $stepLimitVal 步内", ShieldCategory.HAZARD_AND_PATH, paramInt = stepLimitVal)
                            )
                        },
                        label = { Text("限 $stepLimitVal 步内", style = MaterialTheme.typography.labelSmall) }
                    )
                    listOf(
                        WaveShieldChip("trail_corrosion", "轨迹腐蚀自避", ShieldCategory.HAZARD_AND_PATH, paramInt = 15),
                        WaveShieldChip("step_hurt_minimize", "一步一伤省步", ShieldCategory.HAZARD_AND_PATH),
                        WaveShieldChip("char_orb_terrain", "角色珠通行/停放", ShieldCategory.HAZARD_AND_PATH),
                        WaveShieldChip("weijian_burst", "违建长步爆发", ShieldCategory.HAZARD_AND_PATH)
                    ).forEach { chip ->
                        FilterChip(
                            selected = chip.id in activeIds,
                            onClick = { onToggleWaveChip(chip) },
                            label = { Text(chip.label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                if ("step_limit" in activeIds) {
                    NumericStepperRow(
                        label = "本波步数限制",
                        valueText = "≤ $stepLimitVal 步",
                        onMinus = {
                            stepLimitVal = (stepLimitVal - 1).coerceIn(2, 60)
                            ActiveStrategyStore.upsertChip(
                                WaveShieldChip("step_limit", "限 $stepLimitVal 步内", ShieldCategory.HAZARD_AND_PATH, paramInt = stepLimitVal)
                            )
                        },
                        onPlus = {
                            stepLimitVal = (stepLimitVal + 1).coerceIn(2, 60)
                            ActiveStrategyStore.upsertChip(
                                WaveShieldChip("step_limit", "限 $stepLimitVal 步内", ShieldCategory.HAZARD_AND_PATH, paramInt = stepLimitVal)
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun NumericStepperRow(
    label: String,
    valueText: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                onClick = onMinus,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                modifier = Modifier.height(30.dp)
            ) { Text("−") }
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            FilledTonalButton(
                onClick = onPlus,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                modifier = Modifier.height(30.dp)
            ) { Text("+") }
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
                    title = "路径预览显示识盘珠色角标",
                    subtitle = "画线预览时在每格显示识别出的珠色，方便一眼核对首消 C 数",
                    checked = settings.showOrbBadgesOnPath,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(showOrbBadgesOnPath = v) } }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(
                        text = "全屏问号珠记忆（常驻原生特性 · 自动生效）",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "后台 6Hz 静默记住翻面前清晰盘面；若进场直接全版问号，点「算」将自动按旧版问号‧转逻辑按住微移显形重算。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
