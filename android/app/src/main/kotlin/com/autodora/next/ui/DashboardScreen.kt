package com.autodora.next.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatAlignLeft
import androidx.compose.material.icons.automirrored.outlined.FormatAlignRight
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.autodora.next.BuildConfig
import com.autodora.next.R
import com.autodora.next.capture.CaptureRouter
import com.autodora.next.data.*
import com.autodora.next.model.*
import com.autodora.next.touch.SwipeSpeedPreset
import com.autodora.next.vision.EnemySkillParseResult
import com.autodora.next.vision.EnemySkillParseStatus
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class PermissionHealthState(
    val overlayGranted: Boolean,
    val accessibilityConnected: Boolean,
    val mediaProjectionReady: Boolean,
    val notificationsGranted: Boolean,
    val overlayRunning: Boolean
)

data class AiModelsStatus(
    val boardEngineLabel: String = "",
    val hasBoardMasterOnnx: Boolean = false,
    val hasEnemyIconOnnx: Boolean = false,
    val hasOcrOnnx: Boolean = false
)

private enum class HomeTab(val labelRes: Int) {
    OVERVIEW(R.string.tab_overview),
    STRATEGIES(R.string.tab_strategies),
    ENEMY_HUB(R.string.tab_enemy_hub),
    SETTINGS(R.string.tab_settings)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(
    health: PermissionHealthState,
    settings: AppSettings,
    presets: List<TeamPresetRecord>,
    activeWaveChips: List<WaveShieldChip>,
    bypassedGreySkills: List<BypassedEnemySkill> = emptyList(),
    activeVisualHints: BoardVisualHints = BoardVisualHints(),
    aiModelsStatus: AiModelsStatus = AiModelsStatus(),
    benchmarkReport: String?,
    isBenchmarking: Boolean,
    trainingStats: TrainingStorageStats = TrainingStorageStats(),
    isExportingTrainingData: Boolean = false,
    onExportTrainingData: () -> Unit = {},
    onClearTrainingData: () -> Unit = {},
    capturedEnemySkills: List<CapturedEnemySkillEntity> = emptyList(),
    savedStageScripts: List<StageWaveScript> = emptyList(),
    activeStageScript: StageWaveScript? = null,
    activeStageWaveIndex: Int = 1,
    rulesVersionLabel: String = "",
    hasCustomRulesOverride: Boolean = false,
    onEvaluateRuleTestText: (String) -> EnemySkillParseResult = { EnemySkillParseResult(emptyList(), emptyList(), emptyList(), BoardVisualHints()) },
    onImportCustomRulesJson: () -> Unit = {},
    onResetCustomRulesJson: () -> Unit = {},
    onImportStageWikiJson: () -> Unit = {},
    onImportBuiltInStageBundle: () -> Unit = {},
    onImportBuiltInAuthoritativeSkills: () -> Unit = {},
    onActivateStageScript: (StageWaveScript) -> Unit = {},
    onDeactivateStageScript: () -> Unit = {},
    onStepStageWave: (Int) -> Unit = {},
    onDeleteStageScript: (String) -> Unit = {},
    onClearAllStageScripts: () -> Unit = {},
    onRestoreBypassedSkill: (BypassedEnemySkill) -> Unit = {},
    onUpsertEnemySkill: (oldHash: String?, title: String, body: String, stageName: String, waveIndex: Int) -> Unit = { _, _, _, _, _ -> },
    onDeleteEnemySkill: (String) -> Unit = {},
    onCleanNoiseEnemySkills: () -> Unit = {},
    onReparseAllEnemySkills: () -> Unit = {},
    onClearAllEnemySkills: () -> Unit = {},
    updateCheckState: UpdateCheckState = UpdateCheckState.Idle,
    onRequestOverlay: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenAppDetails: () -> Unit,
    onRequestMediaProjection: () -> Unit,
    onRequestNotifications: () -> Unit,
    onToggleOverlayService: () -> Unit,
    onRunNativeBenchmark: () -> Unit,
    onOpenGitHub: () -> Unit = {},
    onCheckForUpdates: () -> Unit = {},
    onDownloadUpdate: (UpdateCheckState.UpdateAvailable) -> Unit = {},
    onCancelUpdateDownload: () -> Unit = {},
    onInstallReadyApk: (String) -> Unit = {},
    onOpenUpdateUrl: (String) -> Unit = {},
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onSavePreset: (TeamBasePreset, Boolean) -> Unit,
    onResetStrategy: () -> Unit,
    onToggleWaveChip: (WaveShieldChip) -> Unit,
    onClearWaveChips: () -> Unit
) {
    val ctx = LocalContext.current
    var activeTab by remember { mutableStateOf(HomeTab.OVERVIEW) }
    var helpPopup by remember { mutableStateOf<Pair<String, String>?>(null) }

    val activePresetRecord = presets.firstOrNull { it.preset.id == settings.activePresetId }
        ?: presets.firstOrNull()
    val activeCfg = activePresetRecord?.preset?.baseConfig ?: RuleConfig()
    val activeGoalSummary = LocaleHelper.goalSummary(ctx, activeCfg)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(modifier = Modifier.padding(end = 8.dp)) {
                        Text(
                            text = stringResource(R.string.app_name),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.app_subtitle),
                            fontSize = 10.5.sp,
                            lineHeight = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    Surface(
                        onClick = onToggleOverlayService,
                        shape = RoundedCornerShape(10.dp),
                        color = if (health.overlayRunning) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        modifier = Modifier.padding(end = 10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = if (health.overlayRunning) Icons.Outlined.CheckCircle else Icons.Outlined.PauseCircle,
                                contentDescription = null,
                                tint = if (health.overlayRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = stringResource(if (health.overlayRunning) R.string.status_running else R.string.status_stopped),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (health.overlayRunning) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
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
                    val label = stringResource(tab.labelRes)
                    val icon = when (tab) {
                        HomeTab.OVERVIEW -> Icons.Outlined.Dashboard
                        HomeTab.STRATEGIES -> Icons.Outlined.Tune
                        HomeTab.ENEMY_HUB -> Icons.AutoMirrored.Outlined.MenuBook
                        HomeTab.SETTINGS -> Icons.Outlined.Settings
                    }
                    NavigationBarItem(
                        selected = selected,
                        onClick = { activeTab = tab },
                        icon = {
                            when {
                                tab == HomeTab.STRATEGIES && activeWaveChips.isNotEmpty() -> {
                                    BadgedBox(badge = { Badge { Text("${activeWaveChips.size}") } }) {
                                        Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp))
                                    }
                                }
                                tab == HomeTab.ENEMY_HUB && activeStageScript != null -> {
                                    BadgedBox(badge = { Badge { Text("W$activeStageWaveIndex") } }) {
                                        Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp))
                                    }
                                }
                                else -> Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp))
                            }
                        },
                        label = { Text(label, fontSize = 11.5.sp, textAlign = TextAlign.Center) }
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
                    activeGoalSummary = activeGoalSummary,
                    activeWaveChips = activeWaveChips,
                    activeStageScript = activeStageScript,
                    activeStageWaveIndex = activeStageWaveIndex,
                    aiModelsStatus = aiModelsStatus,
                    onRequestOverlay = onRequestOverlay,
                    onOpenAccessibility = onOpenAccessibility,
                    onOpenAppDetails = onOpenAppDetails,
                    onRequestMediaProjection = onRequestMediaProjection,
                    onRequestNotifications = onRequestNotifications,
                    onToggleOverlayService = onToggleOverlayService,
                    onStepStageWave = onStepStageWave,
                    onDeactivateStageScript = onDeactivateStageScript,
                    onUpdateSettings = onUpdateSettings,
                    onNavigateTab = { activeTab = it },
                    onShowHelp = { title, body -> helpPopup = title to body }
                )

                HomeTab.STRATEGIES -> UnifiedStrategyTab(
                    settings = settings,
                    presets = presets,
                    activeWaveChips = activeWaveChips,
                    bypassedGreySkills = bypassedGreySkills,
                    activeVisualHints = activeVisualHints,
                    onRestoreBypassedSkill = onRestoreBypassedSkill,
                    onUpdateSettings = onUpdateSettings,
                    onSavePreset = onSavePreset,
                    onResetStrategy = onResetStrategy,
                    onToggleWaveChip = onToggleWaveChip,
                    onClearWaveChips = onClearWaveChips,
                    onShowHelp = { title, body -> helpPopup = title to body }
                )

                HomeTab.ENEMY_HUB -> EnemySkillHubTab(
                    capturedEnemySkills = capturedEnemySkills,
                    savedStageScripts = savedStageScripts,
                    activeStageScript = activeStageScript,
                    activeStageWaveIndex = activeStageWaveIndex,
                    rulesVersionLabel = rulesVersionLabel,
                    hasCustomRulesOverride = hasCustomRulesOverride,
                    onEvaluateRuleTestText = onEvaluateRuleTestText,
                    onImportCustomRulesJson = onImportCustomRulesJson,
                    onResetCustomRulesJson = onResetCustomRulesJson,
                    onImportStageWikiJson = onImportStageWikiJson,
                    onImportBuiltInStageBundle = onImportBuiltInStageBundle,
                    onImportBuiltInAuthoritativeSkills = onImportBuiltInAuthoritativeSkills,
                    onActivateStageScript = onActivateStageScript,
                    onDeactivateStageScript = onDeactivateStageScript,
                    onStepStageWave = onStepStageWave,
                    onDeleteStageScript = onDeleteStageScript,
                    onClearAllStageScripts = onClearAllStageScripts,
                    onUpsertEnemySkill = onUpsertEnemySkill,
                    onDeleteEnemySkill = onDeleteEnemySkill,
                    onCleanNoiseEnemySkills = onCleanNoiseEnemySkills,
                    onReparseAllEnemySkills = onReparseAllEnemySkills,
                    onClearAllEnemySkills = onClearAllEnemySkills,
                    onShowHelp = { title, body -> helpPopup = title to body }
                )

                HomeTab.SETTINGS -> SettingsTab(
                    settings = settings,
                    aiModelsStatus = aiModelsStatus,
                    benchmarkReport = benchmarkReport,
                    isBenchmarking = isBenchmarking,
                    trainingStats = trainingStats,
                    isExportingTrainingData = isExportingTrainingData,
                    onExportTrainingData = onExportTrainingData,
                    onClearTrainingData = onClearTrainingData,
                    updateCheckState = updateCheckState,
                    onUpdateSettings = onUpdateSettings,
                    onRunNativeBenchmark = onRunNativeBenchmark,
                    onOpenGitHub = onOpenGitHub,
                    onCheckForUpdates = onCheckForUpdates,
                    onDownloadUpdate = onDownloadUpdate,
                    onCancelUpdateDownload = onCancelUpdateDownload,
                    onInstallReadyApk = onInstallReadyApk,
                    onOpenUpdateUrl = onOpenUpdateUrl,
                    onShowHelp = { title, body -> helpPopup = title to body }
                )
            }
        }
    }

    helpPopup?.let { (hTitle, hBody) ->
        LocalizedHelpDialog(
            title = hTitle,
            body = hBody,
            dismissLabel = ctx.getString(R.string.btn_got_it),
            onDismiss = { helpPopup = null }
        )
    }
}

// ==================== Tab 0: Overview Hub (Compact Colored Drawers + Full (?) Help) ====================
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OverviewTab(
    health: PermissionHealthState,
    settings: AppSettings,
    activeGoalSummary: String,
    activeWaveChips: List<WaveShieldChip>,
    activeStageScript: StageWaveScript?,
    activeStageWaveIndex: Int,
    aiModelsStatus: AiModelsStatus,
    onRequestOverlay: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenAppDetails: () -> Unit,
    onRequestMediaProjection: () -> Unit,
    onRequestNotifications: () -> Unit,
    onToggleOverlayService: () -> Unit,
    onStepStageWave: (Int) -> Unit,
    onDeactivateStageScript: () -> Unit,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onNavigateTab: (HomeTab) -> Unit,
    onShowHelp: (String, String) -> Unit
) {
    val ctx = LocalContext.current
    val speedPreset = SwipeSpeedPreset.fromStepDurationMs(settings.stepDurationMs)
    val totalPerms = if (health.notificationsGranted) 3 else 4
    val readyPerms = listOf(
        health.overlayGranted,
        health.accessibilityConnected,
        health.mediaProjectionReady
    ).count { it }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 1. Indigo Drawer: Active Strategy & Overlay Control
        ColoredCollapsibleDrawerCard(
            title = stringResource(R.string.overview_current_strategy),
            accentColor = DrawerAccentColors.Indigo,
            summaryText = activeGoalSummary,
            highlighted = health.overlayRunning,
            initialExpanded = true,
            onHelp = {
                onShowHelp(
                    ctx.getString(R.string.help_ov_status_title),
                    ctx.getString(R.string.help_ov_status_body)
                )
            }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = activeGoalSummary,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                )
                CompactActionButton(
                    label = stringResource(R.string.overview_config_strategy),
                    filled = true,
                    onClick = { onNavigateTab(HomeTab.STRATEGIES) }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                InfoPill(
                    label = stringResource(R.string.overview_pill_steps, settings.maxSteps),
                    onClick = { onNavigateTab(HomeTab.SETTINGS) },
                    modifier = Modifier.weight(1f)
                )
                InfoPill(
                    label = stringResource(R.string.overview_pill_speed, LocaleHelper.speedLabel(ctx, speedPreset)),
                    onClick = { onNavigateTab(HomeTab.SETTINGS) },
                    modifier = Modifier.weight(1f)
                )
                InfoPill(
                    label = stringResource(R.string.overview_pill_shields, activeWaveChips.size),
                    onClick = { onNavigateTab(HomeTab.STRATEGIES) },
                    modifier = Modifier.weight(1f)
                )
            }

            Surface(
                onClick = { onNavigateTab(if (activeStageScript != null) HomeTab.ENEMY_HUB else HomeTab.SETTINGS) },
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.overview_ai_status_title),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.ai_engine_active_fmt, aiModelsStatus.boardEngineLabel.ifBlank { "-" }),
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (activeStageScript != null) {
                        val totalW = activeStageScript.waves.lastOrNull()?.waveIndex ?: activeStageScript.waves.size
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.stage_scripts_active_banner_fmt,
                                    activeStageScript.stageName,
                                    activeStageWaveIndex,
                                    totalW
                                ),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f).padding(end = 6.dp)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                CompactActionButton(label = "‹", onClick = { onStepStageWave(-1) })
                                CompactActionButton(label = "›", onClick = { onStepStageWave(+1) })
                                CompactActionButton(
                                    label = stringResource(R.string.knowledge_btn_stop_stage),
                                    destructive = true,
                                    onClick = onDeactivateStageScript
                                )
                            }
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.overview_no_stage_script),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable { onNavigateTab(HomeTab.ENEMY_HUB) }
                        )
                    }
                }
            }

            Button(
                onClick = onToggleOverlayService,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
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
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(if (health.overlayRunning) R.string.btn_stop_overlay else R.string.btn_start_overlay),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // 2. Teal Drawer: Permissions Health
        ColoredCollapsibleDrawerCard(
            title = stringResource(R.string.section_permissions),
            accentColor = DrawerAccentColors.Teal,
            summaryText = "$readyPerms/$totalPerms",
            highlighted = readyPerms == totalPerms,
            initialExpanded = true,
            onHelp = {
                onShowHelp(
                    ctx.getString(R.string.help_ov_perms_title),
                    ctx.getString(R.string.help_ov_perms_body)
                )
            }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PermissionItemRow(
                    title = stringResource(R.string.perm_overlay_title),
                    subtitle = stringResource(R.string.perm_overlay_sub),
                    granted = health.overlayGranted,
                    actionLabel = stringResource(R.string.perm_grant),
                    allowAlwaysClick = false,
                    onAction = onRequestOverlay
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PermissionItemRow(
                    title = stringResource(R.string.perm_a11y_title),
                    subtitle = stringResource(R.string.perm_a11y_sub),
                    granted = health.accessibilityConnected,
                    actionLabel = stringResource(R.string.perm_enable),
                    allowAlwaysClick = true,
                    onAction = onOpenAccessibility
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val useA11yCapture = CaptureRouter.effectiveMode(settings.captureMode) == CaptureMode.ACCESSIBILITY
                PermissionItemRow(
                    title = stringResource(R.string.perm_capture_title),
                    subtitle = stringResource(
                        if (useA11yCapture) R.string.perm_capture_a11y_sub else R.string.perm_capture_media_sub
                    ),
                    granted = health.mediaProjectionReady,
                    actionLabel = stringResource(if (useA11yCapture) R.string.perm_enable else R.string.perm_grant),
                    allowAlwaysClick = true,
                    onAction = onRequestMediaProjection
                )
                if (CaptureRouter.isAccessibilityCaptureSupported()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CompactChoicePill(
                            label = stringResource(R.string.capture_mode_a11y),
                            selected = useA11yCapture,
                            onClick = { onUpdateSettings { it.copy(captureMode = CaptureMode.ACCESSIBILITY) } },
                            modifier = Modifier.weight(1.25f)
                        )
                        CompactChoicePill(
                            label = stringResource(R.string.capture_mode_media),
                            selected = !useA11yCapture,
                            onClick = { onUpdateSettings { it.copy(captureMode = CaptureMode.MEDIA_PROJECTION) } },
                            modifier = Modifier.weight(0.95f)
                        )
                    }
                }
                if (!health.notificationsGranted) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    PermissionItemRow(
                        title = stringResource(R.string.perm_notif_title),
                        subtitle = stringResource(R.string.perm_notif_sub),
                        granted = false,
                        actionLabel = stringResource(R.string.perm_grant),
                        allowAlwaysClick = false,
                        onAction = onRequestNotifications
                    )
                }
            }
        }

        // 3. Amber Drawer: Floating Capsule Usage Guide
        ColoredCollapsibleDrawerCard(
            title = stringResource(R.string.guide_title),
            accentColor = DrawerAccentColors.Amber,
            initialExpanded = true,
            onHelp = {
                onShowHelp(
                    ctx.getString(R.string.help_ov_guide_title),
                    ctx.getString(R.string.help_ov_guide_body)
                )
            }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.guide_line_1), fontSize = 11.5.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.guide_line_2), fontSize = 11.5.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.guide_line_3), fontSize = 11.5.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // 4. Crimson Drawer: HyperOS / Android 13+ Restricted Settings Guide
        ColoredCollapsibleDrawerCard(
            title = stringResource(R.string.hyperos_title),
            accentColor = DrawerAccentColors.Crimson,
            initialExpanded = true,
            onHelp = {
                onShowHelp(
                    ctx.getString(R.string.help_ov_hyperos_title),
                    ctx.getString(R.string.help_ov_hyperos_body)
                )
            }
        ) {
            Text(
                text = stringResource(R.string.hyperos_guide_body),
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                CompactActionButton(
                    label = stringResource(R.string.hyperos_app_details),
                    filled = true,
                    icon = Icons.Outlined.Info,
                    onClick = onOpenAppDetails
                )
            }
        }

        Spacer(Modifier.height(4.dp))
    }
}

// ==================== Tab 2: Enemy Skill & Stage Script Hub (100% Inline 6-Per-Page + 3-Level Filter) ====================
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun EnemySkillHubTab(
    capturedEnemySkills: List<CapturedEnemySkillEntity>,
    savedStageScripts: List<StageWaveScript>,
    activeStageScript: StageWaveScript?,
    activeStageWaveIndex: Int,
    rulesVersionLabel: String,
    hasCustomRulesOverride: Boolean,
    onEvaluateRuleTestText: (String) -> EnemySkillParseResult,
    onImportCustomRulesJson: () -> Unit,
    onResetCustomRulesJson: () -> Unit,
    onImportStageWikiJson: () -> Unit,
    onImportBuiltInStageBundle: () -> Unit,
    onImportBuiltInAuthoritativeSkills: () -> Unit,
    onActivateStageScript: (StageWaveScript) -> Unit,
    onDeactivateStageScript: () -> Unit,
    onStepStageWave: (Int) -> Unit,
    onDeleteStageScript: (String) -> Unit,
    onClearAllStageScripts: () -> Unit,
    onUpsertEnemySkill: (oldHash: String?, title: String, body: String, stageName: String, waveIndex: Int) -> Unit,
    onDeleteEnemySkill: (String) -> Unit,
    onCleanNoiseEnemySkills: () -> Unit,
    onReparseAllEnemySkills: () -> Unit,
    onClearAllEnemySkills: () -> Unit,
    onShowHelp: (String, String) -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f

    // Drawer 1 (Stage Wave Scripts) inline state: 3-level game portal filter + search + 6/page + inline wave expand
    var selectedL1Entry by rememberSaveable { mutableStateOf("ALL") }
    var selectedL2Sub by rememberSaveable { mutableStateOf("ALL") }
    var selectedL3Series by rememberSaveable { mutableStateOf("ALL") }
    var stageSearchQuery by rememberSaveable { mutableStateOf("") }
    var stagePage by rememberSaveable { mutableStateOf(0) }
    var expandedStageKey by rememberSaveable { mutableStateOf<String?>(null) }
    var showClearAllStagesConfirm by remember { mutableStateOf(false) }

    // Drawer 2 (Local Enemy Skill KB) inline state: status + source filter + search + 6/page + inline detail expand
    var skillStatusFilter by rememberSaveable { mutableStateOf("ALL") }
    var skillSourceFilter by rememberSaveable { mutableStateOf("ALL") }
    var skillSearchQuery by rememberSaveable { mutableStateOf("") }
    var skillPage by rememberSaveable { mutableStateOf(0) }
    var expandedSkillHash by rememberSaveable { mutableStateOf<String?>(null) }
    var editingSkillTarget by remember { mutableStateOf<CapturedEnemySkillEntity?>(null) }
    var showEditSkillDialog by remember { mutableStateOf(false) }
    var deleteSkillConfirmTarget by remember { mutableStateOf<CapturedEnemySkillEntity?>(null) }
    var showClearAllSkillsConfirm by remember { mutableStateOf(false) }

    // Drawer 3 (Offline Rule Tester)
    var ruleTestInput by rememberSaveable { mutableStateOf("") }
    var copyFeedbackMsg by remember { mutableStateOf<String?>(null) }

    val parsedSkillCnt = capturedEnemySkills.count { it.parseStatus == EnemySkillParseStatus.PARSED }
    val nonBoardSkillCnt = capturedEnemySkills.count { it.parseStatus == EnemySkillParseStatus.NON_BOARD_KNOWN }
    val unparsedSkillCnt = capturedEnemySkills.count { it.parseStatus == EnemySkillParseStatus.UNPARSED }

    val l1Counts = remember(savedStageScripts) {
        savedStageScripts.groupingBy { it.entryCategory.ifBlank { "大型活動" } }.eachCount()
    }
    val l1Filtered = remember(savedStageScripts, selectedL1Entry) {
        if (selectedL1Entry == "ALL") savedStageScripts
        else savedStageScripts.filter { it.entryCategory.ifBlank { "大型活動" } == selectedL1Entry }
    }
    val availableL2Subs = remember(l1Filtered) {
        l1Filtered.mapNotNull { it.subCategory.takeIf(String::isNotBlank) }.distinct()
    }
    LaunchedEffect(availableL2Subs) {
        if (selectedL2Sub != "ALL" && selectedL2Sub !in availableL2Subs) selectedL2Sub = "ALL"
    }
    val l2Filtered = remember(l1Filtered, selectedL2Sub) {
        if (selectedL2Sub == "ALL") l1Filtered
        else l1Filtered.filter { it.subCategory == selectedL2Sub }
    }
    val availableL3Series = remember(l2Filtered) {
        l2Filtered.mapNotNull { it.dungeonSeries.takeIf(String::isNotBlank) }.distinct()
    }
    LaunchedEffect(availableL3Series) {
        if (selectedL3Series != "ALL" && selectedL3Series !in availableL3Series) selectedL3Series = "ALL"
    }
    val filteredStages = remember(l2Filtered, selectedL3Series, stageSearchQuery) {
        val bySeries = if (selectedL3Series == "ALL") l2Filtered else l2Filtered.filter { it.dungeonSeries == selectedL3Series }
        val q = stageSearchQuery.trim()
        if (q.isEmpty()) bySeries
        else bySeries.filter { sc ->
            sc.stageName.contains(q, ignoreCase = true) ||
                sc.dungeonSeries.contains(q, ignoreCase = true) ||
                sc.subCategory.contains(q, ignoreCase = true) ||
                sc.difficultyTag.contains(q, ignoreCase = true) ||
                sc.waves.any { w ->
                    w.skillTitles.any { t -> t.contains(q, ignoreCase = true) } ||
                        w.chips.any { c -> c.label.contains(q, ignoreCase = true) }
                }
        }
    }

    val filteredSkills = remember(capturedEnemySkills, skillStatusFilter, skillSourceFilter, skillSearchQuery) {
        val byStatus = when (skillStatusFilter) {
            EnemySkillParseStatus.PARSED -> capturedEnemySkills.filter { it.parseStatus == EnemySkillParseStatus.PARSED }
            EnemySkillParseStatus.UNPARSED -> capturedEnemySkills.filter { it.parseStatus == EnemySkillParseStatus.UNPARSED }
            EnemySkillParseStatus.NON_BOARD_KNOWN -> capturedEnemySkills.filter { it.parseStatus == EnemySkillParseStatus.NON_BOARD_KNOWN }
            else -> capturedEnemySkills
        }
        val bySource = when (skillSourceFilter) {
            "LIVE" -> byStatus.filter {
                it.sourceType.contains("POPUP", ignoreCase = true) || it.sourceType.contains("MODAL", ignoreCase = true) || it.sourceType.contains("OCR", ignoreCase = true)
            }
            "WIKI" -> byStatus.filter {
                it.sourceType.contains("AUTHORITATIVE", ignoreCase = true) || it.sourceType.contains("WIKI", ignoreCase = true)
            }
            "MANUAL" -> byStatus.filter { it.sourceType.equals("MANUAL_EDIT", ignoreCase = true) }
            else -> byStatus
        }
        val q = skillSearchQuery.trim()
        if (q.isEmpty()) bySource
        else bySource.filter { sk ->
            sk.titleZhTw.contains(q, ignoreCase = true) ||
                sk.rawBodyZhTw.contains(q, ignoreCase = true) ||
                sk.stageName.contains(q, ignoreCase = true) ||
                sk.mappedChipsJson.contains(q, ignoreCase = true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ==================== Drawer 1 (Indigo): Stage Wave Scripts (Inline 3-Level Filter + 6/Page) ====================
        ColoredCollapsibleDrawerCard(
            title = stringResource(R.string.section_stage_scripts_hub),
            accentColor = DrawerAccentColors.Indigo,
            summaryText = if (activeStageScript != null) {
                "${activeStageScript.stageName} · W$activeStageWaveIndex"
            } else {
                stringResource(R.string.stage_scripts_badge_fmt, savedStageScripts.size)
            },
            highlighted = activeStageScript != null || savedStageScripts.isNotEmpty(),
            initialExpanded = true,
            onHelp = {
                onShowHelp(
                    context.getString(R.string.help_stage_scripts_title),
                    context.getString(R.string.help_stage_scripts_body)
                )
            }
        ) {
            if (activeStageScript != null) {
                val totalW = activeStageScript.waves.lastOrNull()?.waveIndex ?: activeStageScript.waves.size
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(
                                R.string.stage_scripts_active_banner_fmt,
                                activeStageScript.stageName,
                                activeStageWaveIndex,
                                totalW
                            ),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f).padding(end = 6.dp)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            CompactActionButton(label = "‹", onClick = { onStepStageWave(-1) })
                            CompactActionButton(label = "›", onClick = { onStepStageWave(+1) })
                            CompactActionButton(
                                label = stringResource(R.string.knowledge_btn_stop_stage),
                                destructive = true,
                                onClick = onDeactivateStageScript
                            )
                        }
                    }
                }
            }

            // Action Buttons Row (FlowRow — ZERO ellipsis!)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                CompactActionButton(
                    label = stringResource(R.string.btn_import_builtin_140_stages),
                    filled = true,
                    icon = Icons.Outlined.DownloadForOffline,
                    onClick = onImportBuiltInStageBundle
                )
                CompactActionButton(
                    label = stringResource(R.string.btn_import_stage_file),
                    icon = Icons.Outlined.UploadFile,
                    onClick = onImportStageWikiJson
                )
                if (savedStageScripts.isNotEmpty()) {
                    CompactActionButton(
                        label = stringResource(R.string.knowledge_btn_clear_all),
                        destructive = true,
                        onClick = { showClearAllStagesConfirm = true }
                    )
                }
            }

            // Compact Search Field
            CompactInputField(
                value = stageSearchQuery,
                onValueChange = { stageSearchQuery = it; stagePage = 0 },
                placeholder = stringResource(R.string.stage_scripts_search_hint),
                showSearchIcon = true
            )

            // Level 1: Game Portal Entry Pills
            Text(stringResource(R.string.stage_filter_l1_label), fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CompactChoicePill(
                    label = "${stringResource(R.string.stage_filter_all)} ${savedStageScripts.size}",
                    selected = selectedL1Entry == "ALL",
                    onClick = { selectedL1Entry = "ALL"; selectedL2Sub = "ALL"; selectedL3Series = "ALL"; stagePage = 0 }
                )
                for (entry in StageHierarchyHelper.ENTRY_ORDER) {
                    val cnt = l1Counts[entry] ?: 0
                    if (cnt > 0 || selectedL1Entry == entry) {
                        CompactChoicePill(
                            label = "$entry $cnt",
                            selected = selectedL1Entry == entry,
                            onClick = { selectedL1Entry = entry; selectedL2Sub = "ALL"; selectedL3Series = "ALL"; stagePage = 0 }
                        )
                    }
                }
            }

            // Level 2: Dynamic Sub-Category Pills
            if (selectedL1Entry != "ALL" && availableL2Subs.size > 1) {
                Text(stringResource(R.string.stage_filter_l2_label), fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CompactChoicePill(
                        label = stringResource(R.string.stage_filter_all),
                        selected = selectedL2Sub == "ALL",
                        onClick = { selectedL2Sub = "ALL"; selectedL3Series = "ALL"; stagePage = 0 }
                    )
                    for (sub in availableL2Subs) {
                        CompactChoicePill(
                            label = sub,
                            selected = selectedL2Sub == sub,
                            onClick = { selectedL2Sub = sub; selectedL3Series = "ALL"; stagePage = 0 }
                        )
                    }
                }
            }

            // Level 3: Dungeon Series Pills (shown when L2 narrowed or <= 12 series)
            if ((selectedL1Entry != "ALL" || selectedL2Sub != "ALL") && availableL3Series.size in 2..14) {
                Text(stringResource(R.string.stage_filter_l3_label), fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CompactChoicePill(
                        label = stringResource(R.string.stage_filter_all),
                        selected = selectedL3Series == "ALL",
                        onClick = { selectedL3Series = "ALL"; stagePage = 0 }
                    )
                    for (ser in availableL3Series) {
                        CompactChoicePill(
                            label = ser,
                            selected = selectedL3Series == ser,
                            onClick = { selectedL3Series = ser; stagePage = 0 }
                        )
                    }
                }
            }

            // Inline 6-Per-Page Stage Cards
            val stagePageSize = 6
            val totalStagePages = ((filteredStages.size + stagePageSize - 1) / stagePageSize).coerceAtLeast(1)
            val safeStagePage = stagePage.coerceIn(0, totalStagePages - 1)
            val pageStages = filteredStages.drop(safeStagePage * stagePageSize).take(stagePageSize)

            if (pageStages.isEmpty()) {
                Text(
                    text = stringResource(R.string.stage_scripts_empty_list),
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            } else {
                for (script in pageStages) {
                    val isActive = activeStageScript?.stageKey == script.stageKey
                    val isExpanded = expandedStageKey == script.stageKey
                    val shieldTotal = script.waves.sumOf { it.chips.size }
                    val catTag = listOfNotNull(
                        script.entryCategory.takeIf { it.isNotBlank() },
                        script.subCategory.takeIf { it.isNotBlank() },
                        script.difficultyTag.takeIf { it.isNotBlank() }
                    ).joinToString(" · ")

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f) else MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { expandedStageKey = if (isExpanded) null else script.stageKey }
                                .padding(horizontal = 9.dp, vertical = 7.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 6.dp)) {
                                    Text(
                                        text = script.stageName,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "${if (catTag.isNotBlank()) "[$catTag] · " else ""}${script.waves.size} 波 · $shieldTotal 盾",
                                        fontSize = 10.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    CompactActionButton(
                                        label = stringResource(if (isExpanded) R.string.stage_collapse_waves else R.string.stage_expand_waves),
                                        onClick = { expandedStageKey = if (isExpanded) null else script.stageKey }
                                    )
                                    if (isActive) {
                                        CompactActionButton(
                                            label = stringResource(R.string.knowledge_btn_stop_stage),
                                            destructive = true,
                                            onClick = onDeactivateStageScript
                                        )
                                    } else {
                                        CompactActionButton(
                                            label = stringResource(R.string.knowledge_btn_activate_stage),
                                            filled = true,
                                            onClick = { onActivateStageScript(script) }
                                        )
                                    }
                                    IconButton(
                                        onClick = { onDeleteStageScript(script.stageKey) },
                                        modifier = Modifier.size(26.dp)
                                    ) {
                                        Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(15.dp))
                                    }
                                }
                            }

                            AnimatedVisibility(visible = isExpanded) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    for (w in script.waves) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.Top,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Surface(
                                                onClick = {
                                                    ActiveStageScriptStore.activateScript(script, w.waveIndex)
                                                },
                                                shape = RoundedCornerShape(6.dp),
                                                color = if (isActive && activeStageWaveIndex == w.waveIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer
                                            ) {
                                                Text(
                                                    text = if (w.isBoss) "W${w.waveIndex}·BOSS" else "W${w.waveIndex}",
                                                    fontSize = 10.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isActive && activeStageWaveIndex == w.waveIndex) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                                if (w.skillTitles.isNotEmpty()) {
                                                    Text(
                                                        text = w.skillTitles.joinToString(" / "),
                                                        fontSize = 11.sp,
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                }
                                                if (w.chips.isNotEmpty()) {
                                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                                        for (c in w.chips) {
                                                            Surface(
                                                                shape = RoundedCornerShape(6.dp),
                                                                color = DrawerAccentColors.Teal.copy(alpha = if (isDark) 0.24f else 0.12f)
                                                            ) {
                                                                Text(
                                                                    text = LocaleHelper.formatWaveChipLabel(context, c),
                                                                    fontSize = 10.5.sp,
                                                                    fontWeight = FontWeight.SemiBold,
                                                                    color = DrawerAccentColors.foreground(DrawerAccentColors.Teal, isDark),
                                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                                )
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

                // Inline Pagination Bar
                InlinePaginationBar(
                    currentPage = safeStagePage,
                    totalPages = totalStagePages,
                    totalItems = filteredStages.size,
                    onPrev = { stagePage = (safeStagePage - 1).coerceAtLeast(0) },
                    onNext = { stagePage = (safeStagePage + 1).coerceAtMost(totalStagePages - 1) }
                )
            }
        }

        // ==================== Drawer 2 (Purple): Local Enemy Skill KB & Hot-Reload (Inline 6/Page) ====================
        ColoredCollapsibleDrawerCard(
            title = stringResource(R.string.section_enemy_kb_hub),
            accentColor = DrawerAccentColors.Purple,
            summaryText = stringResource(R.string.knowledge_hub_badge_fmt, capturedEnemySkills.size, savedStageScripts.size),
            highlighted = capturedEnemySkills.isNotEmpty() || hasCustomRulesOverride,
            initialExpanded = true,
            onHelp = {
                onShowHelp(
                    context.getString(R.string.help_enemy_kb_title),
                    context.getString(R.string.help_enemy_kb_body)
                )
            }
        ) {
            Surface(
                shape = RoundedCornerShape(9.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Text(
                    text = stringResource(
                        R.string.knowledge_hub_stats_fmt,
                        parsedSkillCnt,
                        nonBoardSkillCnt,
                        unparsedSkillCnt,
                        savedStageScripts.size,
                        rulesVersionLabel.ifBlank { "-" }
                    ),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth().padding(8.dp)
                )
            }
            if (hasCustomRulesOverride) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.knowledge_custom_rules_active),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    CompactActionButton(
                        label = stringResource(R.string.knowledge_btn_reset_rules),
                        onClick = onResetCustomRulesJson
                    )
                }
            }

            // Action Buttons FlowRow (ZERO ellipsis, NO double "+ +" bug!)
            val copyOkFmt = stringResource(R.string.knowledge_copied_unparsed_fmt)
            val noUnparsedMsg = stringResource(R.string.knowledge_no_unparsed)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                CompactActionButton(
                    label = stringResource(R.string.btn_import_builtin_499_skills),
                    filled = true,
                    icon = Icons.Outlined.DownloadForOffline,
                    onClick = onImportBuiltInAuthoritativeSkills
                )
                CompactActionButton(
                    label = stringResource(R.string.knowledge_btn_import_rules),
                    icon = Icons.Outlined.UploadFile,
                    onClick = onImportCustomRulesJson
                )
                CompactActionButton(
                    label = stringResource(R.string.knowledge_btn_add),
                    filled = true,
                    icon = Icons.Outlined.Add,
                    onClick = {
                        editingSkillTarget = null
                        showEditSkillDialog = true
                    }
                )
                CompactActionButton(
                    label = stringResource(R.string.knowledge_btn_copy_unparsed),
                    onClick = {
                        val report = TrainingDataRepository.formatUnparsedSkillsReport(capturedEnemySkills)
                        if (report.isBlank()) {
                            copyFeedbackMsg = noUnparsedMsg
                        } else {
                            clipboardManager.setText(AnnotatedString(report))
                            copyFeedbackMsg = String.format(Locale.getDefault(), copyOkFmt, unparsedSkillCnt)
                        }
                    }
                )
                if (capturedEnemySkills.isNotEmpty()) {
                    CompactActionButton(label = stringResource(R.string.knowledge_btn_clean_noise), onClick = onCleanNoiseEnemySkills)
                    CompactActionButton(label = stringResource(R.string.knowledge_btn_reparse_all), onClick = onReparseAllEnemySkills)
                    CompactActionButton(
                        label = stringResource(R.string.knowledge_btn_clear_all),
                        destructive = true,
                        onClick = { showClearAllSkillsConfirm = true }
                    )
                }
            }
            if (!copyFeedbackMsg.isNullOrBlank()) {
                Text(text = copyFeedbackMsg.orEmpty(), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
            }

            // Inline Search + Status Filter + Source Filter
            CompactInputField(
                value = skillSearchQuery,
                onValueChange = { skillSearchQuery = it; skillPage = 0 },
                placeholder = stringResource(R.string.knowledge_search_hint),
                showSearchIcon = true
            )

            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(
                    Triple("ALL", R.string.knowledge_filter_all, capturedEnemySkills.size),
                    Triple(EnemySkillParseStatus.PARSED, R.string.knowledge_filter_parsed, parsedSkillCnt),
                    Triple(EnemySkillParseStatus.UNPARSED, R.string.knowledge_filter_unparsed, unparsedSkillCnt),
                    Triple(EnemySkillParseStatus.NON_BOARD_KNOWN, R.string.knowledge_filter_nonboard, nonBoardSkillCnt)
                ).forEach { (key, resId, cnt) ->
                    CompactChoicePill(
                        label = "${stringResource(resId)} $cnt",
                        selected = skillStatusFilter == key,
                        onClick = { skillStatusFilter = key; skillPage = 0 }
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(
                    "ALL" to R.string.kb_source_all,
                    "LIVE" to R.string.kb_source_live,
                    "WIKI" to R.string.kb_source_wiki,
                    "MANUAL" to R.string.kb_source_manual
                ).forEach { (key, resId) ->
                    CompactChoicePill(
                        label = stringResource(resId),
                        selected = skillSourceFilter == key,
                        onClick = { skillSourceFilter = key; skillPage = 0 }
                    )
                }
            }

            // Inline 6-Per-Page Skill Records
            val skillPageSize = 6
            val totalSkillPages = ((filteredSkills.size + skillPageSize - 1) / skillPageSize).coerceAtLeast(1)
            val safeSkillPage = skillPage.coerceIn(0, totalSkillPages - 1)
            val pageSkills = filteredSkills.drop(safeSkillPage * skillPageSize).take(skillPageSize)

            if (pageSkills.isEmpty()) {
                Text(
                    text = stringResource(R.string.knowledge_empty_list),
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            } else {
                for (sk in pageSkills) {
                    val chips = remember(sk.mappedChipsJson) {
                        TrainingDataRepository.decodeMappedChips(sk.mappedChipsJson)
                    }
                    val isExpanded = expandedSkillHash == sk.skillHash
                    val statusBadgeBg = when (sk.parseStatus) {
                        EnemySkillParseStatus.PARSED -> MaterialTheme.colorScheme.primaryContainer
                        EnemySkillParseStatus.NON_BOARD_KNOWN -> MaterialTheme.colorScheme.secondaryContainer
                        else -> MaterialTheme.colorScheme.errorContainer
                    }
                    val statusBadgeFg = when (sk.parseStatus) {
                        EnemySkillParseStatus.PARSED -> MaterialTheme.colorScheme.onPrimaryContainer
                        EnemySkillParseStatus.NON_BOARD_KNOWN -> MaterialTheme.colorScheme.onSecondaryContainer
                        else -> MaterialTheme.colorScheme.onErrorContainer
                    }

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { expandedSkillHash = if (isExpanded) null else sk.skillHash }
                                .padding(horizontal = 9.dp, vertical = 7.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = sk.titleZhTw.ifBlank { sk.skillHash },
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f).padding(end = 6.dp)
                                )
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Surface(shape = RoundedCornerShape(5.dp), color = statusBadgeBg) {
                                        Text(
                                            text = sk.parseStatus,
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = statusBadgeFg,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            editingSkillTarget = sk
                                            showEditSkillDialog = true
                                        },
                                        modifier = Modifier.size(26.dp)
                                    ) {
                                        Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(15.dp))
                                    }
                                    IconButton(
                                        onClick = { deleteSkillConfirmTarget = sk },
                                        modifier = Modifier.size(26.dp)
                                    ) {
                                        Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(15.dp))
                                    }
                                }
                            }

                            if (!isExpanded) {
                                Text(
                                    text = sk.rawBodyZhTw.replace('\n', ' ').take(90),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Text(
                                    text = sk.rawBodyZhTw,
                                    fontSize = 11.5.sp,
                                    lineHeight = 16.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "${sk.sourceType}${if (sk.stageName.isNotBlank()) " · ${sk.stageName}" else ""} · ${stringResource(R.string.knowledge_hit_fmt, sk.hitCount)}",
                                    fontSize = 10.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (chips.isNotEmpty()) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    for (c in chips) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = DrawerAccentColors.Purple.copy(alpha = if (isDark) 0.24f else 0.12f)
                                        ) {
                                            Text(
                                                text = LocaleHelper.formatWaveChipLabel(context, c),
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = DrawerAccentColors.foreground(DrawerAccentColors.Purple, isDark),
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                InlinePaginationBar(
                    currentPage = safeSkillPage,
                    totalPages = totalSkillPages,
                    totalItems = filteredSkills.size,
                    onPrev = { skillPage = (safeSkillPage - 1).coerceAtLeast(0) },
                    onNext = { skillPage = (safeSkillPage + 1).coerceAtMost(totalSkillPages - 1) }
                )
            }
        }

        // ==================== Drawer 3 (Teal): Offline Enemy Skill Rule + 499 KB Tester ====================
        ColoredCollapsibleDrawerCard(
            title = stringResource(R.string.section_enemy_tester_hub),
            accentColor = DrawerAccentColors.Teal,
            highlighted = ruleTestInput.isNotBlank(),
            initialExpanded = true,
            onHelp = {
                onShowHelp(
                    context.getString(R.string.help_enemy_tester_title),
                    context.getString(R.string.help_enemy_tester_body)
                )
            }
        ) {
            CompactMultilineField(
                value = ruleTestInput,
                onValueChange = { ruleTestInput = it },
                placeholder = stringResource(R.string.knowledge_tester_hint)
            )
            if (ruleTestInput.isNotBlank()) {
                val testRes = remember(ruleTestInput, rulesVersionLabel, capturedEnemySkills.size) {
                    onEvaluateRuleTestText(ruleTestInput)
                }
                Text(
                    text = stringResource(R.string.knowledge_tester_result_fmt, testRes.activeChips.size),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (testRes.activeChips.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        for (chip in testRes.activeChips) {
                            val pfx = if (chip.fromKnowledgeFallback) stringResource(R.string.ov_kb_chip_prefix) else ""
                            CompactChoicePill(
                                label = "$pfx${LocaleHelper.formatWaveChipLabel(context, chip)}",
                                selected = true,
                                aiHighlighted = chip.fromKnowledgeFallback,
                                onClick = { ActiveStrategyStore.upsertChip(chip.copy(autoDetected = false)) }
                            )
                        }
                    }
                }
                val h = testRes.combinedHints
                if (h.hasMonochrome || h.hasDisguise || h.hasElectrified || h.hasFrozenOrPetrified) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (h.hasMonochrome) CompactChoicePill(stringResource(R.string.ov_hint_monochrome_pill), true, {})
                        if (h.hasDisguise) CompactChoicePill(stringResource(R.string.ov_hint_disguise_edit_pill), true, {})
                        if (h.hasElectrified) CompactChoicePill(stringResource(R.string.ov_hint_elec_pill), true, {})
                        if (h.hasFrozenOrPetrified) CompactChoicePill(stringResource(R.string.ov_hint_frozen_pill), true, {})
                    }
                }
            }
        }
    }

    // ==================== Localized Confirmation & Edit Dialogs ====================
    if (showClearAllStagesConfirm) {
        LocalizedConfirmDialog(
            title = context.getString(R.string.stage_scripts_clear_confirm_title),
            body = context.getString(R.string.stage_scripts_clear_confirm_body),
            confirmText = context.getString(R.string.knowledge_btn_clear_all),
            cancelText = context.getString(R.string.about_update_cancel_btn),
            destructive = true,
            onConfirm = {
                showClearAllStagesConfirm = false
                onClearAllStageScripts()
            },
            onDismiss = { showClearAllStagesConfirm = false }
        )
    }

    if (showEditSkillDialog) {
        val editTarget = editingSkillTarget
        var formTitle by remember(editTarget) { mutableStateOf(editTarget?.titleZhTw.orEmpty()) }
        var formBody by remember(editTarget) { mutableStateOf(editTarget?.rawBodyZhTw.orEmpty()) }
        var formStage by remember(editTarget) { mutableStateOf(editTarget?.stageName.orEmpty()) }
        var formWave by remember(editTarget) {
            mutableStateOf(if ((editTarget?.waveIndex ?: 0) > 0) editTarget!!.waveIndex.toString() else "")
        }
        val livePreview = remember(formBody, rulesVersionLabel) {
            if (formBody.isBlank()) null else onEvaluateRuleTestText(formBody)
        }
        LocalizedDialogSurface(onDismiss = { showEditSkillDialog = false }) {
            Text(
                text = context.getString(
                    if (editTarget == null) R.string.knowledge_add_dialog_title
                    else R.string.knowledge_edit_dialog_title
                ),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Text(context.getString(R.string.knowledge_field_title), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                CompactInputField(
                    value = formTitle,
                    onValueChange = { formTitle = it },
                    placeholder = context.getString(R.string.knowledge_field_title_hint)
                )
                Text(context.getString(R.string.knowledge_field_body), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                CompactMultilineField(
                    value = formBody,
                    onValueChange = { formBody = it },
                    placeholder = context.getString(R.string.knowledge_field_body_hint)
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CompactInputField(
                        value = formStage,
                        onValueChange = { formStage = it },
                        placeholder = context.getString(R.string.knowledge_field_stage),
                        modifier = Modifier.weight(1.4f)
                    )
                    CompactInputField(
                        value = formWave,
                        onValueChange = { raw -> formWave = raw.filter { it.isDigit() }.take(2) },
                        placeholder = context.getString(R.string.knowledge_field_wave),
                        numeric = true,
                        modifier = Modifier.weight(0.8f)
                    )
                }
                if (livePreview != null) {
                    val pStatus = livePreview.allBlocks.firstOrNull()?.parseStatus ?: EnemySkillParseStatus.UNPARSED
                    Text(
                        text = context.getString(R.string.knowledge_live_preview_fmt, pStatus, livePreview.activeChips.size),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (livePreview.activeChips.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (c in livePreview.activeChips) {
                                CompactChoicePill(LocaleHelper.formatWaveChipLabel(context, c), true, {})
                            }
                        }
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                CompactActionButton(
                    label = context.getString(R.string.about_update_cancel_btn),
                    onClick = { showEditSkillDialog = false }
                )
                Spacer(Modifier.width(8.dp))
                CompactActionButton(
                    label = context.getString(R.string.knowledge_btn_save),
                    filled = true,
                    enabled = formBody.isNotBlank(),
                    onClick = {
                        onUpsertEnemySkill(
                            editTarget?.skillHash,
                            formTitle.trim(),
                            formBody.trim(),
                            formStage.trim(),
                            formWave.toIntOrNull() ?: 0
                        )
                        showEditSkillDialog = false
                    }
                )
            }
        }
    }

    deleteSkillConfirmTarget?.let { target ->
        LocalizedConfirmDialog(
            title = context.getString(R.string.knowledge_delete_confirm_title),
            body = context.getString(R.string.knowledge_delete_confirm_body, target.titleZhTw.ifBlank { target.skillHash }),
            confirmText = context.getString(R.string.knowledge_btn_delete),
            cancelText = context.getString(R.string.about_update_cancel_btn),
            destructive = true,
            onConfirm = {
                onDeleteEnemySkill(target.skillHash)
                deleteSkillConfirmTarget = null
            },
            onDismiss = { deleteSkillConfirmTarget = null }
        )
    }

    if (showClearAllSkillsConfirm) {
        LocalizedConfirmDialog(
            title = context.getString(R.string.knowledge_clear_confirm_title),
            body = context.getString(R.string.knowledge_clear_confirm_body),
            confirmText = context.getString(R.string.knowledge_btn_clear_all),
            cancelText = context.getString(R.string.about_update_cancel_btn),
            destructive = true,
            onConfirm = {
                showClearAllSkillsConfirm = false
                onClearAllEnemySkills()
            },
            onDismiss = { showClearAllSkillsConfirm = false }
        )
    }
}

// ==================== Tab 3: Settings, Split Capture Switches & Full (?) Help ====================
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SettingsTab(
    settings: AppSettings,
    aiModelsStatus: AiModelsStatus,
    benchmarkReport: String?,
    isBenchmarking: Boolean,
    trainingStats: TrainingStorageStats,
    isExportingTrainingData: Boolean,
    onExportTrainingData: () -> Unit,
    onClearTrainingData: () -> Unit,
    updateCheckState: UpdateCheckState,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onRunNativeBenchmark: () -> Unit,
    onOpenGitHub: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onDownloadUpdate: (UpdateCheckState.UpdateAvailable) -> Unit,
    onCancelUpdateDownload: () -> Unit,
    onInstallReadyApk: (String) -> Unit,
    onOpenUpdateUrl: (String) -> Unit,
    onShowHelp: (String, String) -> Unit
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
    var showClearTrainingConfirm by remember { mutableStateOf(false) }

    val msUnit = stringResource(R.string.unit_ms)
    val activeSpeed = SwipeSpeedPreset.fromStepDurationMs(settings.stepDurationMs)
    val invertActive = settings.invertDragX || settings.invertDragY
    val enabledModesCount = listOf(
        settings.testModeOnlyDraw,
        settings.holdReplanEnabled,
        invertActive,
        settings.allowDiagonalMove,
        settings.humanMode,
        settings.showEmergencyStop,
        settings.showFloatingRecordButton
    ).count { it }
    val aiVisionEnabledCount = listOf(
        settings.useAiRecognizer,
        settings.autoCapturePreBattleStageInfo,
        settings.autoCaptureInBattleEnemyInfo,
        settings.autoEnemyIconDetectEnabled,
        settings.calibration.autoDetectWeijian,
        settings.showOrbBadgesOnPath
    ).count { it }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 0. Indigo Drawer: AI Vision & Split Pre-Battle / In-Battle Capture Switches (1:1 synced with Floating Settings Indigo!)
        ColoredCollapsibleDrawerCard(
            title = stringResource(R.string.section_ai_vision),
            accentColor = DrawerAccentColors.Indigo,
            summaryText = "$aiVisionEnabledCount/6",
            highlighted = aiVisionEnabledCount > 0,
            initialExpanded = true,
            onHelp = {
                onShowHelp(
                    context.getString(R.string.help_ai_vision_title),
                    context.getString(R.string.help_ai_vision_body)
                )
            }
        ) {
            val readyLbl = stringResource(R.string.ai_model_ready)
            val missLbl = stringResource(R.string.ai_model_missing)
            Surface(
                shape = RoundedCornerShape(9.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = stringResource(R.string.ai_engine_active_fmt, aiModelsStatus.boardEngineLabel.ifBlank { "-" }),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(
                            R.string.ai_models_ready_fmt,
                            if (aiModelsStatus.hasBoardMasterOnnx) readyLbl else missLbl,
                            if (aiModelsStatus.hasEnemyIconOnnx) readyLbl else missLbl,
                            if (aiModelsStatus.hasOcrOnnx) readyLbl else missLbl
                        ),
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SwitchSettingRow(
                    title = stringResource(R.string.mode_ai_vision_title),
                    subtitle = stringResource(R.string.mode_ai_vision_sub),
                    checked = settings.useAiRecognizer,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(useAiRecognizer = v) } }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SwitchSettingRow(
                    title = stringResource(R.string.ai_capture_pre_battle_title),
                    subtitle = stringResource(R.string.ai_capture_pre_battle_sub),
                    checked = settings.autoCapturePreBattleStageInfo,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(autoCapturePreBattleStageInfo = v) } }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SwitchSettingRow(
                    title = stringResource(R.string.ai_capture_in_battle_title),
                    subtitle = stringResource(R.string.ai_capture_in_battle_sub),
                    checked = settings.autoCaptureInBattleEnemyInfo,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(autoCaptureInBattleEnemyInfo = v) } }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SwitchSettingRow(
                    title = stringResource(R.string.mode_enemy_icon_title),
                    subtitle = stringResource(R.string.mode_enemy_icon_sub),
                    checked = settings.autoEnemyIconDetectEnabled,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(autoEnemyIconDetectEnabled = v) } }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SwitchSettingRow(
                    title = stringResource(R.string.mode_weijian_title),
                    subtitle = stringResource(R.string.mode_weijian_sub),
                    checked = settings.calibration.autoDetectWeijian,
                    onCheckedChange = { v ->
                        onUpdateSettings { it.copy(calibration = it.calibration.copy(autoDetectWeijian = v)) }
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SwitchSettingRow(
                    title = stringResource(R.string.mode_badges_title),
                    subtitle = stringResource(R.string.mode_badges_sub),
                    checked = settings.showOrbBadgesOnPath,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(showOrbBadgesOnPath = v) } }
                )
            }
        }

        // 1. Purple Drawer: Appearance, Theme Mode & Display Language
        ColoredCollapsibleDrawerCard(
            title = stringResource(R.string.section_appearance),
            accentColor = DrawerAccentColors.Purple,
            summaryText = "${LocaleHelper.themeLabel(context, settings.themeMode)} · ${LocaleHelper.languageLabel(context, settings.language)} · ${stringResource(if (settings.floatingBarVertical) R.string.floating_bar_vertical else R.string.floating_bar_horizontal)}${stringResource(if (settings.floatingBarDockRight) R.string.floating_bar_dock_right else R.string.floating_bar_dock_left)}",
            highlighted = true,
            initialExpanded = true,
            onHelp = {
                onShowHelp(
                    context.getString(R.string.help_appearance_title),
                    context.getString(R.string.help_appearance_body)
                )
            }
        ) {
            Text(stringResource(R.string.theme_desc), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                ThemeMode.entries.forEach { mode ->
                    CompactChoicePill(
                        label = LocaleHelper.themeLabel(context, mode),
                        selected = settings.themeMode == mode,
                        onClick = { onUpdateSettings { it.copy(themeMode = mode) } },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Text(stringResource(R.string.language_title), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                AppLanguage.entries.forEach { lang ->
                    CompactChoicePill(
                        label = LocaleHelper.languageLabel(context, lang),
                        selected = settings.language == lang,
                        onClick = { onUpdateSettings { it.copy(language = lang) } }
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Text(stringResource(R.string.floating_bar_layout_title), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                CompactChoicePill(
                    label = stringResource(R.string.floating_bar_horizontal),
                    selected = !settings.floatingBarVertical,
                    onClick = { onUpdateSettings { it.copy(floatingBarVertical = false) } },
                    modifier = Modifier.weight(1f)
                )
                CompactChoicePill(
                    label = stringResource(R.string.floating_bar_vertical),
                    selected = settings.floatingBarVertical,
                    onClick = { onUpdateSettings { it.copy(floatingBarVertical = true) } },
                    modifier = Modifier.weight(1f)
                )
            }

            Text(stringResource(R.string.floating_bar_dock_title), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                CompactChoicePill(
                    label = stringResource(R.string.floating_bar_dock_left),
                    selected = !settings.floatingBarDockRight,
                    onClick = { onUpdateSettings { it.copy(floatingBarDockRight = false, floatingBarPosXRatio = -1f) } },
                    modifier = Modifier.weight(1f)
                )
                CompactChoicePill(
                    label = stringResource(R.string.floating_bar_dock_right),
                    selected = settings.floatingBarDockRight,
                    onClick = { onUpdateSettings { it.copy(floatingBarDockRight = true, floatingBarPosXRatio = -1f) } },
                    modifier = Modifier.weight(1f)
                )
            }
            Text(stringResource(R.string.floating_bar_memory_hint), fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // 2. Amber Drawer: Swipe Speed Presets + Slim Native Slider
        ColoredCollapsibleDrawerCard(
            title = stringResource(R.string.section_speed),
            accentColor = DrawerAccentColors.Amber,
            summaryText = "${LocaleHelper.speedLabel(context, activeSpeed)} · ${settings.stepDurationMs} $msUnit",
            highlighted = true,
            initialExpanded = true,
            onHelp = {
                onShowHelp(
                    context.getString(R.string.help_speed_title),
                    context.getString(R.string.help_speed_body)
                )
            }
        ) {
            Text(stringResource(R.string.speed_desc), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                for (rowPresets in SwipeSpeedPreset.entries.chunked(3)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        for (preset in rowPresets) {
                            val selected = activeSpeed == preset
                            val style = overlayPalette.speedStyle(preset)
                            Surface(
                                onClick = { onUpdateSettings { it.copy(stepDurationMs = preset.stepDurationMs) } },
                                shape = RoundedCornerShape(9.dp),
                                color = if (selected) Color(style.bg) else MaterialTheme.colorScheme.surfaceContainerLow,
                                border = BorderStroke(
                                    width = if (selected) 1.4.dp else 1.dp,
                                    color = if (selected) Color(style.stroke) else MaterialTheme.colorScheme.outlineVariant
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(
                                    modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = LocaleHelper.speedLabel(context, preset),
                                        fontSize = 11.5.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (selected) Color(style.fg) else MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                    Text(
                                        text = "${preset.stepDurationMs} $msUnit",
                                        fontSize = 10.sp,
                                        color = if (selected) Color(style.fg) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
            SlimNativeSlider(
                value = settings.stepDurationMs.toFloat().coerceIn(20f, 120f),
                onValueChange = { v -> onUpdateSettings { it.copy(stepDurationMs = v.roundToLong()) } },
                valueRange = 20f..120f
            )
        }

        // 3. Blue Drawer: Solver Budget, Hold-Replan Budgets & Pre-Swipe Countdown Pills
        ColoredCollapsibleDrawerCard(
            title = stringResource(R.string.section_engine_steps),
            accentColor = DrawerAccentColors.Blue,
            summaryText = "${settings.maxSteps}${stringResource(R.string.unit_steps)} · ${settings.beamWidth}",
            highlighted = true,
            initialExpanded = true,
            onHelp = {
                onShowHelp(
                    context.getString(R.string.help_engine_settings_title),
                    context.getString(R.string.help_engine_settings_body)
                )
            }
        ) {
            Text(stringResource(R.string.priority_tier_hint_settings), fontSize = 10.5.sp, color = MaterialTheme.colorScheme.primary)
            CompactInputField(
                value = maxStepsInput,
                onValueChange = { raw ->
                    val digits = raw.filter { it.isDigit() }.take(3)
                    maxStepsInput = digits
                    val v = digits.toIntOrNull()
                    if (v != null && v in 8..300) {
                        onUpdateSettings { it.copy(maxSteps = v) }
                    }
                },
                placeholder = stringResource(R.string.field_max_steps),
                numeric = true,
                suffixText = stringResource(R.string.unit_steps),
                isError = maxStepsError
            )
            Text(
                text = if (maxStepsError) stringResource(R.string.error_max_steps) else stringResource(R.string.support_max_steps, settings.maxSteps),
                fontSize = 10.5.sp,
                color = if (maxStepsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                for (stepVal in listOf(30, 60, 100, 150, 200)) {
                    CompactChoicePill(
                        label = "$stepVal",
                        selected = settings.maxSteps == stepVal,
                        onClick = {
                            maxStepsInput = stepVal.toString()
                            onUpdateSettings { it.copy(maxSteps = stepVal) }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SliderSettingRow(
                label = stringResource(R.string.slider_beam),
                valueText = "${settings.beamWidth}",
                value = settings.beamWidth.toFloat().coerceIn(40f, 600f),
                valueRange = 40f..600f,
                onValueChange = { v -> onUpdateSettings { it.copy(beamWidth = ((v / 20f).roundToInt() * 20).coerceIn(40, 600)) } }
            )
            SliderSettingRow(
                label = stringResource(R.string.slider_hold_delay),
                valueText = "${settings.holdReplanDelayMs} $msUnit",
                value = settings.holdReplanDelayMs.toFloat().coerceIn(240f, 900f),
                valueRange = 240f..900f,
                onValueChange = { v -> onUpdateSettings { it.copy(holdReplanDelayMs = ((v / 20f).roundToLong() * 20L).coerceIn(240L, 900L)) } }
            )
            Text(stringResource(R.string.slider_hold_delay_sub), fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val solveSecFormatted = String.format(Locale.US, "%.2fs", settings.holdReplanSolveTimeMs / 1000.0)
            SliderSettingRow(
                label = stringResource(R.string.slider_hold_solve_time),
                valueText = "$solveSecFormatted (${settings.holdReplanSolveTimeMs} $msUnit)",
                value = settings.holdReplanSolveTimeMs.toFloat().coerceIn(150f, 3500f),
                valueRange = 150f..3500f,
                onValueChange = { v ->
                    onUpdateSettings {
                        it.copy(holdReplanSolveTimeMs = ((v / 50f).roundToLong() * 50L).coerceIn(150L, 3500L))
                    }
                }
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                for ((presetLabel, presetMs) in listOf("0.3s" to 300L, "0.6s" to 600L, "1.2s" to 1200L, "2.0s" to 2000L, "3.5s" to 3500L)) {
                    CompactChoicePill(
                        label = presetLabel,
                        selected = settings.holdReplanSolveTimeMs == presetMs,
                        onClick = { onUpdateSettings { it.copy(holdReplanSolveTimeMs = presetMs) } },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Text(stringResource(R.string.slider_hold_solve_time_sub), fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            // Compact Pre-Swipe Countdown Selector Pills (replaces oversized 56dp dropdown!)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(stringResource(R.string.slider_countdown), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                for (sec in listOf(0, 1, 2, 3)) {
                    val txt = if (sec == 0) stringResource(R.string.countdown_none) else stringResource(R.string.unit_seconds, sec)
                    CompactChoicePill(
                        label = txt,
                        selected = settings.countdownSeconds == sec,
                        onClick = { onUpdateSettings { it.copy(countdownSeconds = sec) } },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 4. Teal Drawer: Mode & Control Switches
        ColoredCollapsibleDrawerCard(
            title = stringResource(R.string.section_modes),
            accentColor = DrawerAccentColors.Teal,
            summaryText = "$enabledModesCount/7",
            highlighted = enabledModesCount > 0,
            initialExpanded = true,
            onHelp = {
                onShowHelp(
                    context.getString(R.string.help_modes_title),
                    context.getString(R.string.help_modes_body)
                )
            }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                SwitchSettingRow(
                    title = stringResource(R.string.mode_test_title),
                    subtitle = stringResource(R.string.mode_test_sub),
                    checked = settings.testModeOnlyDraw,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(testModeOnlyDraw = v) } }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SwitchSettingRow(
                    title = stringResource(R.string.mode_hold_title),
                    subtitle = stringResource(R.string.mode_hold_sub),
                    checked = settings.holdReplanEnabled,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(holdReplanEnabled = v) } }
                )
                Surface(
                    shape = RoundedCornerShape(9.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (settings.holdReplanEnabled) DrawerAccentColors.Teal.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant
                    ),
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.mode_hold_trigger_label),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (settings.holdReplanEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            CompactChoicePill(
                                label = stringResource(R.string.mode_hold_trigger_inplace),
                                selected = !settings.holdReplanNudge,
                                onClick = { onUpdateSettings { it.copy(holdReplanNudge = false) } },
                                modifier = Modifier.weight(1f)
                            )
                            CompactChoicePill(
                                label = stringResource(R.string.mode_hold_trigger_nudge),
                                selected = settings.holdReplanNudge,
                                onClick = { onUpdateSettings { it.copy(holdReplanNudge = true) } },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Text(stringResource(R.string.mode_hold_trigger_sub), fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(stringResource(R.string.mode_invert_title), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text(stringResource(R.string.mode_invert_sub), fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        listOf(
                            Triple(R.string.invert_off, false, false),
                            Triple(R.string.invert_x, true, false),
                            Triple(R.string.invert_y, false, true),
                            Triple(R.string.invert_xy, true, true)
                        ).forEach { (lblRes, ix, iy) ->
                            CompactChoicePill(
                                label = stringResource(lblRes),
                                selected = settings.invertDragX == ix && settings.invertDragY == iy,
                                onClick = { onUpdateSettings { it.copy(invertDragX = ix, invertDragY = iy) } }
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SwitchSettingRow(
                    title = stringResource(R.string.mode_diag_title),
                    subtitle = stringResource(R.string.mode_diag_sub),
                    checked = settings.allowDiagonalMove,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(allowDiagonalMove = v) } }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(modifier = Modifier.padding(vertical = 3.dp)) {
                    Text(stringResource(R.string.mode_question_mem_title), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.mode_question_mem_sub), fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SwitchSettingRow(
                    title = stringResource(R.string.mode_human_title),
                    subtitle = stringResource(R.string.mode_human_sub),
                    checked = settings.humanMode,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(humanMode = v) } }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SwitchSettingRow(
                    title = stringResource(R.string.mode_stop_title),
                    subtitle = stringResource(R.string.mode_stop_sub),
                    checked = settings.showEmergencyStop,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(showEmergencyStop = v) } }
                )
                if (CaptureRouter.isAccessibilityCaptureSupported()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    val useA11yCap = CaptureRouter.effectiveMode(settings.captureMode) == CaptureMode.ACCESSIBILITY
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(stringResource(R.string.capture_mode_title), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            CompactChoicePill(
                                label = stringResource(R.string.capture_mode_a11y),
                                selected = useA11yCap,
                                onClick = { onUpdateSettings { it.copy(captureMode = CaptureMode.ACCESSIBILITY) } },
                                modifier = Modifier.weight(1.25f)
                            )
                            CompactChoicePill(
                                label = stringResource(R.string.capture_mode_media),
                                selected = !useA11yCap,
                                onClick = { onUpdateSettings { it.copy(captureMode = CaptureMode.MEDIA_PROJECTION) } },
                                modifier = Modifier.weight(0.95f)
                            )
                        }
                        Text(stringResource(R.string.capture_mode_sub), fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SwitchSettingRow(
                    title = stringResource(R.string.mode_floating_record_title),
                    subtitle = stringResource(R.string.mode_floating_record_sub),
                    checked = settings.showFloatingRecordButton,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(showFloatingRecordButton = v) } }
                )
            }
        }

        // 4.5. Rose Drawer: On-Device Training Collector
        val mbUsedText = String.format(Locale.US, "%.2f", trainingStats.totalBytes / (1024.0 * 1024.0))
        ColoredCollapsibleDrawerCard(
            title = stringResource(R.string.section_training_collector),
            accentColor = DrawerAccentColors.Rose,
            summaryText = if (settings.trainingDataCollectionEnabled) {
                stringResource(R.string.training_collect_on_badge, trainingStats.totalSamples)
            } else {
                stringResource(R.string.training_collect_off_badge)
            },
            highlighted = settings.trainingDataCollectionEnabled && trainingStats.totalSamples > 0,
            initialExpanded = true,
            onHelp = {
                onShowHelp(
                    context.getString(R.string.help_training_collector_title),
                    context.getString(R.string.help_training_collector_body)
                )
            }
        ) {
            SwitchSettingRow(
                title = stringResource(R.string.training_collect_switch_title),
                subtitle = stringResource(R.string.training_collect_switch_sub),
                checked = settings.trainingDataCollectionEnabled,
                onCheckedChange = { v -> onUpdateSettings { it.copy(trainingDataCollectionEnabled = v) } }
            )
            Surface(shape = RoundedCornerShape(9.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text(
                    text = stringResource(
                        R.string.training_collect_stats_v2_fmt,
                        trainingStats.totalSamples,
                        trainingStats.boardSamples,
                        trainingStats.editorCorrectionSamples,
                        trainingStats.enemyModalSamples,
                        trainingStats.capturedEnemySkillsCount,
                        mbUsedText
                    ),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth().padding(8.dp)
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                CompactActionButton(
                    label = stringResource(
                        if (isExportingTrainingData) R.string.training_collect_btn_exporting
                        else R.string.training_collect_btn_export
                    ),
                    filled = true,
                    enabled = !isExportingTrainingData && (trainingStats.totalSamples > 0 || trainingStats.capturedEnemySkillsCount > 0),
                    onClick = onExportTrainingData
                )
                CompactActionButton(
                    label = stringResource(R.string.training_collect_btn_clear),
                    destructive = true,
                    enabled = !isExportingTrainingData && trainingStats.totalSamples > 0,
                    onClick = { showClearTrainingConfirm = true }
                )
            }
        }

        if (showClearTrainingConfirm) {
            LocalizedConfirmDialog(
                title = context.getString(R.string.training_collect_clear_confirm_title),
                body = context.getString(R.string.training_collect_clear_confirm_body),
                confirmText = context.getString(R.string.training_collect_btn_clear),
                cancelText = context.getString(R.string.about_update_cancel_btn),
                destructive = true,
                onConfirm = {
                    showClearTrainingConfirm = false
                    onClearTrainingData()
                },
                onDismiss = { showClearTrainingConfirm = false }
            )
        }

        // 5. Blue Drawer: Local Engine Benchmark
        ColoredCollapsibleDrawerCard(
            title = stringResource(R.string.section_benchmark),
            accentColor = DrawerAccentColors.Blue,
            initialExpanded = true,
            onHelp = {
                onShowHelp(
                    context.getString(R.string.help_benchmark_title),
                    context.getString(R.string.help_benchmark_body)
                )
            }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(stringResource(R.string.bench_title), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text(stringResource(R.string.bench_sub), fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                CompactActionButton(
                    label = stringResource(if (isBenchmarking) R.string.btn_bench_running else R.string.btn_bench_run),
                    filled = true,
                    enabled = !isBenchmarking,
                    onClick = onRunNativeBenchmark
                )
            }
            if (benchmarkReport != null) {
                Surface(shape = RoundedCornerShape(9.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Text(
                        text = benchmarkReport,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth().padding(8.dp)
                    )
                }
            }
        }

        // 6. Crimson Drawer: About & Version
        ColoredCollapsibleDrawerCard(
            title = stringResource(R.string.section_about),
            accentColor = DrawerAccentColors.Crimson,
            summaryText = "v${BuildConfig.VERSION_NAME}",
            highlighted = true,
            initialExpanded = true,
            onHelp = {
                onShowHelp(
                    context.getString(R.string.help_about_title),
                    context.getString(R.string.help_about_body)
                )
            }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("AutoDora-NEXT", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = stringResource(R.string.about_version_fmt, BuildConfig.VERSION_NAME),
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Text(
                        text = "v${BuildConfig.VERSION_NAME}",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            val updateBusy = updateCheckState is UpdateCheckState.Checking || updateCheckState is UpdateCheckState.Downloading
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                CompactActionButton(
                    label = stringResource(R.string.about_github_btn),
                    icon = Icons.AutoMirrored.Outlined.OpenInNew,
                    onClick = onOpenGitHub
                )
                CompactActionButton(
                    label = stringResource(
                        if (updateCheckState is UpdateCheckState.Checking) R.string.about_checking_update
                        else R.string.about_check_update_btn
                    ),
                    filled = true,
                    icon = Icons.Outlined.SystemUpdate,
                    enabled = !updateBusy,
                    onClick = onCheckForUpdates
                )
            }

            when (val st = updateCheckState) {
                UpdateCheckState.Idle -> Unit
                UpdateCheckState.Checking -> {
                    Surface(shape = RoundedCornerShape(9.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.about_checking_update), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                is UpdateCheckState.UpToDate -> {
                    Surface(shape = RoundedCornerShape(9.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp))
                            Text(stringResource(R.string.about_up_to_date_fmt, st.currentVersion), fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                is UpdateCheckState.UpdateAvailable -> {
                    Surface(
                        shape = RoundedCornerShape(9.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(9.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(
                                text = stringResource(R.string.about_update_avail_fmt, st.latestVersion),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            if (st.releaseNotes.isNotBlank()) {
                                Text(
                                    text = st.releaseNotes.lines().filter { it.isNotBlank() }.take(3).joinToString("\n"),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.88f)
                                )
                            }
                            CompactActionButton(
                                label = stringResource(if (!st.apkDownloadUrl.isNullOrBlank()) R.string.about_update_download_btn else R.string.about_update_browser_btn),
                                filled = true,
                                onClick = {
                                    if (!st.apkDownloadUrl.isNullOrBlank()) onDownloadUpdate(st) else onOpenUpdateUrl(st.releaseUrl)
                                }
                            )
                        }
                    }
                }
                is UpdateCheckState.Downloading -> {
                    val hasTotal = st.totalBytes > 0L
                    val progress = if (hasTotal) (st.downloadedBytes.toFloat() / st.totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
                    val pct = (progress * 100f).roundToInt().coerceIn(0, 100)
                    Surface(shape = RoundedCornerShape(9.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)) {
                        Column(modifier = Modifier.fillMaxWidth().padding(9.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.about_update_downloading_fmt, st.latestVersion), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        text = if (hasTotal) stringResource(R.string.about_update_progress_fmt, UpdateChecker.formatBytesMb(st.downloadedBytes), UpdateChecker.formatBytesMb(st.totalBytes), pct)
                                        else UpdateChecker.formatBytesMb(st.downloadedBytes),
                                        fontSize = 10.5.sp
                                    )
                                }
                                CompactActionButton(label = stringResource(R.string.about_update_cancel_btn), onClick = onCancelUpdateDownload)
                            }
                            if (hasTotal) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                is UpdateCheckState.ReadyToInstall -> {
                    Surface(shape = RoundedCornerShape(9.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.about_update_ready_fmt, st.latestVersion),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f).padding(end = 6.dp)
                            )
                            CompactActionButton(
                                label = stringResource(R.string.about_update_install_now_btn),
                                filled = true,
                                onClick = { onInstallReadyApk(st.apkPath) }
                            )
                        }
                    }
                }
                is UpdateCheckState.Error -> {
                    Surface(shape = RoundedCornerShape(9.dp), color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.75f)) {
                        Text(
                            text = stringResource(R.string.about_update_error_fmt, st.detail),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.fillMaxWidth().padding(8.dp)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))
    }
}

// ==================== Shared Compact Material 3 Building Blocks (v0.9.0 Standard) ====================
internal object DrawerAccentColors {
    val Indigo = Color(0xFF3F51B5)
    val Teal = Color(0xFF00897B)
    val Amber = Color(0xFFE67600)
    val Crimson = Color(0xFFD32F2F)
    val Blue = Color(0xFF1976D2)
    val Purple = Color(0xFF7B1FA2)
    val Rose = Color(0xFFC2185B)

    fun foreground(accent: Color, isDark: Boolean): Color {
        if (!isDark) return accent
        return when (accent) {
            Indigo -> Color(0xFFAEB8FF)
            Teal -> Color(0xFF80CBC4)
            Amber -> Color(0xFFFFCC80)
            Crimson -> Color(0xFFFFAB91)
            Blue -> Color(0xFF90CAF9)
            Purple -> Color(0xFFDFB0FF)
            Rose -> Color(0xFFF48FB1)
            else -> Color(0xFFE0E6FF)
        }
    }
}

@Composable
internal fun ColoredCollapsibleDrawerCard(
    title: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
    summaryText: String? = null,
    highlighted: Boolean = false,
    initialExpanded: Boolean = true,
    onHelp: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(10.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initialExpanded) }
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val accentFg = DrawerAccentColors.foreground(accentColor, isDark)
    val borderColor = if (expanded || highlighted) {
        accentColor.copy(alpha = if (isDark) 0.65f else 0.52f)
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    val headerTint = accentColor.copy(alpha = if (isDark) 0.20f else 0.10f)
    val dividerColor = accentColor.copy(alpha = if (isDark) 0.28f else 0.16f)
    val summaryBg = accentColor.copy(alpha = if (isDark) 0.28f else 0.12f)
    val cardBg = if (isDark) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = cardBg,
        border = BorderStroke(if (expanded || highlighted) 1.25.dp else 1.dp, borderColor),
        tonalElevation = if (isDark) 1.dp else 0.dp,
        shadowElevation = if (isDark) 0.dp else 1.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 38.dp)
                    .background(headerTint)
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(3.5.dp)
                        .height(15.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(accentFg)
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (!summaryText.isNullOrBlank()) {
                    Spacer(Modifier.width(5.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (highlighted) summaryBg else MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Text(
                            text = summaryText,
                            fontSize = 10.5.sp,
                            fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (highlighted) accentFg else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .widthIn(max = 155.dp)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                if (onHelp != null) {
                    Spacer(Modifier.width(4.dp))
                    HelpCircleButton(onClick = onHelp)
                }
                Spacer(Modifier.width(5.dp))
                Text(
                    text = if (expanded) "▾" else "▸",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = accentFg
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(thickness = 1.dp, color = dividerColor)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(contentPadding),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                        content = content
                    )
                }
            }
        }
    }
}

@Composable
internal fun ElementColorTogglePill(
    elemIndex: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    suffix: String = ""
) {
    val ctx = LocalContext.current
    val badgeColor = Color(LocaleHelper.elementBadgeArgb(elemIndex))
    val label = LocaleHelper.elementLabel(ctx, elemIndex) + suffix
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) badgeColor else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) badgeColor else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = modifier.heightIn(min = 30.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
            Text(
                text = label,
                fontSize = 11.5.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
internal fun NumericStepperRow(
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
        Text(
            label,
            fontSize = 11.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(end = 8.dp)
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CompactActionButton(label = "−", onClick = onMinus)
            Text(
                text = valueText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            CompactActionButton(label = "+", onClick = onPlus)
        }
    }
}

@Composable
private fun InlinePaginationBar(
    currentPage: Int,
    totalPages: Int,
    totalItems: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    if (totalPages <= 1) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        CompactActionButton(label = "◀", enabled = currentPage > 0, onClick = onPrev)
        Text(
            text = "${stringResource(R.string.ov_stage_page_fmt, currentPage + 1, totalPages)} ($totalItems)",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        CompactActionButton(label = "▶", enabled = currentPage < totalPages - 1, onClick = onNext)
    }
}

@Composable
private fun InfoPill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp)) {
            Text(
                text = label,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun PermissionItemRow(
    title: String,
    subtitle: String,
    granted: Boolean,
    actionLabel: String,
    allowAlwaysClick: Boolean,
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
                imageVector = if (granted) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(title, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(subtitle, fontSize = 10.5.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(6.dp))
        CompactActionButton(
            label = if (granted) stringResource(R.string.perm_ready) else actionLabel,
            filled = !granted,
            enabled = !granted || allowAlwaysClick,
            onClick = onAction
        )
    }
}

@Composable
internal fun SwitchSettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 34.dp)
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 6.dp)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 10.5.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.scale(0.78f)
        )
    }
}

@Composable
internal fun CompactSwitchLine(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f).padding(end = 6.dp)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.scale(0.78f)
        )
    }
}

@Composable
internal fun CompactInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    numeric: Boolean = false,
    showSearchIcon: Boolean = false,
    suffixText: String? = null,
    isError: Boolean = false
) {
    val borderCol = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, borderCol),
        modifier = modifier.fillMaxWidth().height(35.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showSearchIcon) {
                Icon(
                    Icons.Outlined.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(6.dp))
            }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (!suffixText.isNullOrBlank()) {
                Spacer(Modifier.width(4.dp))
                Text(text = suffixText, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (value.isNotEmpty() && showSearchIcon) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { onValueChange("") }
                )
            }
        }
    }
}

@Composable
internal fun CompactMultilineField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth().heightIn(min = 66.dp, max = 140.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(9.dp)) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = TextStyle(
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
internal fun CompactActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    val bg = when {
        !enabled -> MaterialTheme.colorScheme.surfaceContainerLow
        destructive -> MaterialTheme.colorScheme.errorContainer
        filled -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    val fg = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        destructive -> MaterialTheme.colorScheme.onErrorContainer
        filled -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val border = when {
        !enabled -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        destructive -> MaterialTheme.colorScheme.error.copy(alpha = 0.45f)
        filled -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        color = bg,
        border = BorderStroke(1.dp, border),
        modifier = modifier.heightIn(min = 29.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = label,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = fg,
                textAlign = TextAlign.Center
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SlimNativeSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val thumbBorder = MaterialTheme.colorScheme.surface

    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        interactionSource = interactionSource,
        modifier = modifier
            .fillMaxWidth()
            .height(26.dp),
        thumb = {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .shadow(2.dp, CircleShape)
                    .clip(CircleShape)
                    .background(activeColor)
                    .border(2.dp, thumbBorder, CircleShape)
            )
        },
        track = { sliderState ->
            val fraction = ((sliderState.value - sliderState.valueRange.start) /
                (sliderState.valueRange.endInclusive - sliderState.valueRange.start).coerceAtLeast(1e-4f))
                .coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(inactiveColor)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(activeColor)
                )
            }
        }
    )
}

@Composable
private fun SliderSettingRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(
                text = valueText,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        SlimNativeSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange
        )
    }
}

/**
 * Explicit locale-preserving Dialog wrapper so Compose's separate `DialogWindow` NEVER falls back
 * to the Android OS system language when the user chose 简体中文 / 繁體中文 / English in-app!
 */
@Composable
internal fun LocalizedDialogSurface(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val localizedCtx = LocalContext.current
    val localizedCfg = LocalConfiguration.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        CompositionLocalProvider(
            LocalContext provides localizedCtx,
            LocalConfiguration provides localizedCfg
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
                modifier = Modifier.fillMaxWidth(0.92f)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = content
                )
            }
        }
    }
}

@Composable
internal fun LocalizedConfirmDialog(
    title: String,
    body: String,
    confirmText: String,
    cancelText: String,
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    LocalizedDialogSurface(onDismiss = onDismiss) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Text(body, fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            CompactActionButton(label = cancelText, onClick = onDismiss)
            Spacer(Modifier.width(8.dp))
            CompactActionButton(
                label = confirmText,
                filled = !destructive,
                destructive = destructive,
                onClick = onConfirm
            )
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
    LocalizedDialogSurface(onDismiss = onDismiss) {
        Text(
            text = title,
            fontSize = 14.5.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Column(
            modifier = Modifier
                .heightIn(max = 390.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = body,
                fontSize = 11.5.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            CompactActionButton(label = dismissLabel, filled = true, onClick = onDismiss)
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
        modifier = modifier.size(24.dp)
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
internal fun CompactChoicePill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    aiHighlighted: Boolean = false,
    activeContainer: Color = MaterialTheme.colorScheme.primaryContainer,
    activeContent: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    activeBorder: Color? = MaterialTheme.colorScheme.primary
) {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val aiContainer = if (isDark) Color(0xFF173A44) else Color(0xFFE0F7FA)
    val aiContent = if (isDark) Color(0xFF9CEFF8) else Color(0xFF005662)
    val aiStroke = Color(0xFF00ACC1)

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = when {
            selected && aiHighlighted -> aiContainer
            selected -> activeContainer
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        },
        border = BorderStroke(
            width = if (selected) 1.25.dp else 1.dp,
            color = when {
                selected && aiHighlighted -> aiStroke
                selected -> activeBorder ?: activeContainer
                else -> MaterialTheme.colorScheme.outlineVariant
            }
        ),
        modifier = modifier.heightIn(min = 30.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
        ) {
            Text(
                text = label,
                fontSize = 11.5.sp,
                lineHeight = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    selected && aiHighlighted -> aiContent
                    selected -> activeContent
                    else -> MaterialTheme.colorScheme.onSurface
                },
                textAlign = TextAlign.Center
            )
        }
    }
}
