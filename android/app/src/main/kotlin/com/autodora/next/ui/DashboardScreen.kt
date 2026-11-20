package com.autodora.next.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.autodora.next.R
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

private enum class HomeTab(val labelRes: Int) {
    OVERVIEW(R.string.tab_overview),
    STRATEGIES(R.string.tab_strategies),
    SHIELDS(R.string.tab_shields),
    SETTINGS(R.string.tab_settings)
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
    val ctx = LocalContext.current
    var activeTab by remember { mutableStateOf(HomeTab.OVERVIEW) }
    var editingPreset by remember { mutableStateOf<Pair<TeamBasePreset, Boolean>?>(null) }
    var helpPopup by remember { mutableStateOf<Pair<String, String>?>(null) }

    val activePresetRecord = presets.firstOrNull { it.preset.id == settings.activePresetId }
        ?: presets.firstOrNull()
    val activePresetName = if (activePresetRecord != null) {
        LocaleHelper.displayPresetName(ctx, activePresetRecord.preset, activePresetRecord.isBuiltIn)
    } else {
        stringResource(R.string.preset_general_name)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(modifier = Modifier.padding(end = 8.dp)) {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(R.string.app_subtitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    AssistChip(
                        onClick = onToggleOverlayService,
                        label = {
                            Text(
                                text = stringResource(
                                    if (health.overlayRunning) R.string.status_running else R.string.status_stopped
                                ),
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
                    val label = stringResource(tab.labelRes)
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
                                    Icon(icon, contentDescription = label)
                                }
                            } else {
                                Icon(icon, contentDescription = label)
                            }
                        },
                        label = { Text(label, maxLines = 1) }
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
                    onDelete = onDeletePreset,
                    onShowHelp = { title, body -> helpPopup = title to body }
                )

                HomeTab.SHIELDS -> ShieldsTab(
                    settings = settings,
                    activeWaveChips = activeWaveChips,
                    lastOcrSummary = lastOcrSummary,
                    onUpdateSettings = onUpdateSettings,
                    onToggleWaveChip = onToggleWaveChip,
                    onClearWaveChips = onClearWaveChips,
                    onParseSkillText = onParseSkillText,
                    onShowHelp = { title, body -> helpPopup = title to body }
                )

                HomeTab.SETTINGS -> SettingsTab(
                    settings = settings,
                    benchmarkReport = benchmarkReport,
                    isBenchmarking = isBenchmarking,
                    onUpdateSettings = onUpdateSettings,
                    onRunNativeBenchmark = onRunNativeBenchmark,
                    onShowHelp = { title, body -> helpPopup = title to body }
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

    helpPopup?.let { (hTitle, hBody) ->
        LocalizedHelpDialog(
            title = hTitle,
            body = hBody,
            dismissLabel = ctx.getString(R.string.btn_got_it),
            onDismiss = { helpPopup = null }
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
    val ctx = LocalContext.current
    var showHyperOsGuide by remember { mutableStateOf(false) }
    val speedPreset = SwipeSpeedPreset.fromStepDurationMs(settings.stepDurationMs)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
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
                            text = stringResource(R.string.overview_current_strategy),
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
                        Text(
                            stringResource(R.string.overview_switch_strategy),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                        text = stringResource(
                            if (health.overlayRunning) R.string.btn_stop_overlay else R.string.btn_start_overlay
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        SectionHeader(stringResource(R.string.section_permissions))
        ElevatedCard(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
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
                PermissionItemRow(
                    title = stringResource(R.string.perm_capture_title),
                    subtitle = stringResource(R.string.perm_capture_sub),
                    granted = health.mediaProjectionReady,
                    actionLabel = stringResource(R.string.perm_grant),
                    allowAlwaysClick = false,
                    onAction = onRequestMediaProjection
                )
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
                    text = stringResource(R.string.guide_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    stringResource(R.string.guide_line_1),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.guide_line_2),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.guide_line_3),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

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
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.hyperos_title),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        TextButton(onClick = onOpenAppDetails) {
                            Text(
                                stringResource(R.string.hyperos_app_details),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        TextButton(onClick = { showHyperOsGuide = !showHyperOsGuide }) {
                            Text(
                                stringResource(if (showHyperOsGuide) R.string.action_collapse else R.string.action_expand),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
                AnimatedVisibility(visible = showHyperOsGuide) {
                    Text(
                        text = stringResource(R.string.hyperos_guide_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                    )
                }
            }
        }
    }
}

// ==================== Tab 1: Strategy Profiles ====================
@Composable
private fun StrategiesTab(
    presets: List<TeamPresetRecord>,
    activePresetId: String,
    onSelectPreset: (String) -> Unit,
    onCreateNew: () -> Unit,
    onEdit: (TeamPresetRecord) -> Unit,
    onDuplicate: (TeamBasePreset) -> Unit,
    onDelete: (String) -> Unit,
    onShowHelp: (String, String) -> Unit
) {
    val ctx = LocalContext.current
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
            Column(modifier = Modifier.weight(1f).padding(end = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.strategies_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.width(4.dp))
                    HelpCircleButton(
                        onClick = {
                            onShowHelp(
                                ctx.getString(R.string.help_strategy_library_title),
                                ctx.getString(R.string.help_strategy_library_body)
                            )
                        }
                    )
                }
                Text(
                    text = stringResource(R.string.strategies_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FilledTonalButton(onClick = onCreateNew) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.btn_new_strategy))
            }
        }

        val items = presets.ifEmpty {
            TeamPresetRepository.defaultBuiltInPresets().map { TeamPresetRecord(it, true, 0L) }
        }
        for (rec in items) {
            val p = rec.preset
            val cfg = p.baseConfig
            val isActive = p.id == activePresetId
            val displayName = LocaleHelper.displayPresetName(ctx, p, rec.isBuiltIn)
            val displayDesc = LocaleHelper.displayPresetDesc(ctx, p, rec.isBuiltIn)
            val tags = buildList {
                add(LocaleHelper.goalModeLabel(ctx, cfg.goalMode))
                add(ctx.getString(R.string.tag_steps_fmt, cfg.maxSteps))
                add(ctx.getString(R.string.tag_beam_fmt, cfg.beamWidth))
                if (cfg.earlyStopOnGoal) add(ctx.getString(R.string.tag_first_result))
                if (cfg.forceSplitThree) add(ctx.getString(R.string.tag_split_three))
                if (cfg.firstWaveOnlyScoring) add(ctx.getString(R.string.tag_first_only))
                if (cfg.elementRules.any { it.minMatchCount == 2 }) add(ctx.getString(R.string.tag_two_match))
                if (cfg.elementRules.any { it.targetShape != TargetShape.ANY }) add(ctx.getString(R.string.tag_custom_shape))
                if (cfg.columnRules.any { it.enabled }) add(ctx.getString(R.string.tag_col_amel))
                if (cfg.useCharacterOrb) add(ctx.getString(R.string.tag_char_orb_fmt, cfg.characterOrbCol + 1))
                if (cfg.startPreset == StartPointPreset.BOTTOM_LEFT) add(ctx.getString(R.string.tag_start_bl))
                if (cfg.startPreset == StartPointPreset.BOTTOM_RIGHT) add(ctx.getString(R.string.tag_start_br))
                if (cfg.allowDiagonalMatch) add(ctx.getString(R.string.tag_diag_match))
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
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = displayName,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                                if (isActive) {
                                    Badge(containerColor = MaterialTheme.colorScheme.primary) {
                                        Text(
                                            stringResource(R.string.badge_active),
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                    }
                                }
                            }
                            if (displayDesc.isNotBlank()) {
                                Text(
                                    text = displayDesc,
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
                                Text(stringResource(R.string.btn_enable), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        FilledTonalButton(
                            onClick = { onEdit(rec) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(stringResource(R.string.btn_edit_strategy), style = MaterialTheme.typography.labelMedium)
                        }
                        OutlinedButton(
                            onClick = { onDuplicate(p.copy(name = displayName, description = displayDesc)) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(stringResource(R.string.btn_duplicate), style = MaterialTheme.typography.labelMedium)
                        }
                        if (!rec.isBuiltIn) {
                            Spacer(Modifier.weight(1f))
                            TextButton(
                                onClick = { onDelete(p.id) },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text(stringResource(R.string.btn_delete), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==================== Tab 2: Shields & Auto Enemy Skill Detection ====================
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ShieldsTab(
    settings: AppSettings,
    activeWaveChips: List<WaveShieldChip>,
    lastOcrSummary: String?,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onToggleWaveChip: (WaveShieldChip) -> Unit,
    onClearWaveChips: () -> Unit,
    onParseSkillText: (String) -> List<WaveShieldChip>,
    onShowHelp: (String, String) -> Unit
) {
    val ctx = LocalContext.current
    var testSkillText by remember { mutableStateOf("") }

    val byId = remember(activeWaveChips) { activeWaveChips.associateBy { it.id } }
    val activeIds = remember(activeWaveChips) { activeWaveChips.map { it.id }.toSet() }

    // 1. Independent Per-Type Combo Shield State
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

    LaunchedEffect(byId["min_combo"]?.paramInt) {
        byId["min_combo"]?.paramInt?.let { if (it in 1..15) minTotalComboVal = it }
    }
    LaunchedEffect(byId["min_first_combo"]?.paramInt) {
        byId["min_first_combo"]?.paramInt?.let { if (it in 1..15) minFirstComboVal = it }
    }
    LaunchedEffect(byId["exact_combo"]?.paramInt) {
        byId["exact_combo"]?.paramInt?.let { if (it in 1..15) exactTotalComboVal = it }
    }
    LaunchedEffect(byId["exact_first_combo"]?.paramInt) {
        byId["exact_first_combo"]?.paramInt?.let { if (it in 1..15) exactFirstComboVal = it }
    }

    // 2. Element Restriction sub-tab (0 = Anti-1st, 1 = Forbid All, 2 = Clear All 1st)
    var elemLimitMode by remember { mutableStateOf(0) }
    var digitalOrbs by remember { mutableStateOf(byId["digital_orb_count"]?.paramInt ?: 18) }

    // 3. Path & Step state
    var maxBurnHurts by remember {
        mutableStateOf(((byId["zero_trail_burning"]?.paramInt ?: 0) ushr 8) and 0xFF)
    }
    var burnTrailLen by remember {
        mutableStateOf((byId["zero_trail_burning"]?.paramInt ?: 0) and 0xFF)
    }
    var stepLimitVal by remember { mutableStateOf(byId["step_limit"]?.paramInt ?: 15) }

    // Shared helpers for cross-validated element shield masks
    val legacyReqIds = remember {
        buildSet {
            add("require_elements")
            add("three_elements")
            add("five_elements")
            add("five_elements_enchanted")
            for (e in 0..5) {
                add("require_elem_$e")
                add("require_ench_$e")
            }
        }
    }

    fun readMaskForLimit(baseId: String): Int {
        var m = byId[baseId]?.paramElementMask ?: 0
        for (e in 0..5) {
            if ("${baseId}_$e" in activeIds) m = m or (1 shl e)
        }
        return m
    }

    fun writeMaskForLimit(baseId: String, prefix: String, newMask: Int) {
        val legacySet = buildSet {
            add(baseId)
            for (i in 0..5) add("${baseId}_$i")
        }
        if (newMask == 0) {
            ActiveStrategyStore.replaceGroup(legacySet, null)
        } else {
            val names = (0..5).filter { (newMask and (1 shl it)) != 0 }
                .joinToString("") { LocaleHelper.elementLabel(ctx, it) }
            ActiveStrategyStore.replaceGroup(
                legacySet,
                WaveShieldChip(
                    id = baseId,
                    label = "$prefix:$names",
                    category = ShieldCategory.COUNT_AND_ELEMENT,
                    paramElementMask = newMask
                )
            )
        }
    }

    fun writeRequiredElements(newMask: Int, requireEnch: Boolean) {
        if (newMask == 0) {
            ActiveStrategyStore.replaceGroup(legacyReqIds, null)
            return
        }
        val names = (0..5).filter { (newMask and (1 shl it)) != 0 }
            .joinToString("") { LocaleHelper.elementLabel(ctx, it) }
        val chip = WaveShieldChip(
            id = "require_elements",
            label = if (requireEnch) "$names+" else names,
            category = ShieldCategory.COUNT_AND_ELEMENT,
            paramInt = if (requireEnch) 1 else 0,
            paramElementMask = newMask
        )
        ActiveStrategyStore.replaceGroup(legacyReqIds, chip)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ==================== 0. Auto Enemy Skill Detection ====================
        SectionHeaderWithHelp(
            text = stringResource(R.string.section_auto_skill),
            onHelp = {
                onShowHelp(
                    ctx.getString(R.string.help_auto_skill_title),
                    ctx.getString(R.string.help_auto_skill_body)
                )
            }
        )
        ElevatedCard(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SwitchSettingRow(
                    title = stringResource(R.string.auto_skill_title),
                    subtitle = stringResource(R.string.auto_skill_sub),
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
                            Text(
                                LocaleHelper.scanModeLabel(ctx, mode),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        // ==================== 0b. Active Wave Shields (Standalone Card!) ====================
        SectionHeaderWithHelp(
            text = stringResource(R.string.section_active_shields),
            onHelp = {
                onShowHelp(
                    ctx.getString(R.string.help_active_shields_title),
                    ctx.getString(R.string.help_active_shields_body)
                )
            }
        )
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
                        text = stringResource(R.string.active_shields_fmt, activeWaveChips.size),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (activeWaveChips.isNotEmpty()) {
                        TextButton(
                            onClick = onClearWaveChips,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text(
                                stringResource(R.string.btn_clear),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
                if (activeWaveChips.isEmpty()) {
                    Text(
                        text = stringResource(R.string.active_shields_empty),
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
                if (!lastOcrSummary.isNullOrBlank()) {
                    Text(
                        text = lastOcrSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // ==================== 1. Combo Shield (Game-Accurate Rules & Validation!) ====================
        SectionHeaderWithHelp(
            text = stringResource(R.string.section_combo_shield),
            onHelp = {
                onShowHelp(
                    ctx.getString(R.string.help_combo_shield_title),
                    ctx.getString(R.string.help_combo_shield_body)
                )
            }
        )
        ElevatedCard(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
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
                    // Always remove obsolete redundant "first_wave_only" chip on shield change
                    ActiveStrategyStore.removeChip("first_wave_only")
                    val chip = when (mode) {
                        "min_combo" -> WaveShieldChip(
                            "min_combo",
                            "${ctx.getString(R.string.combo_type_min_total)} ≥$minTotalComboVal",
                            ShieldCategory.COMBO,
                            paramInt = minTotalComboVal
                        )
                        "min_first_combo" -> WaveShieldChip(
                            "min_first_combo",
                            "${ctx.getString(R.string.combo_type_min_first)} ≥$minFirstComboVal",
                            ShieldCategory.COMBO,
                            paramInt = minFirstComboVal
                        )
                        "exact_combo" -> WaveShieldChip(
                            "exact_combo",
                            "${ctx.getString(R.string.combo_type_exact_total)} $exactTotalComboVal",
                            ShieldCategory.COMBO,
                            paramInt = exactTotalComboVal
                        )
                        "exact_first_combo" -> WaveShieldChip(
                            "exact_first_combo",
                            "${ctx.getString(R.string.combo_type_exact_first)} $exactFirstComboVal",
                            ShieldCategory.COMBO,
                            paramInt = exactFirstComboVal
                        )
                        "zero_combo" -> {
                            // Zero Combo cannot coexist with structural combo extras, full clear, digital orb count, or first-wave special orb shields!
                            listOf(
                                "same_combo_elements", "second_wave_combo", "same_elem_combos",
                                "equal_pair_light_dark", "first_wave_full_clear", "digital_orb_count",
                                "any_enchanted_orb_shield", "race_orb_shield", "char_orb_first_wave", "luminous_orb_shield"
                            ).forEach { ActiveStrategyStore.removeChip(it) }
                            WaveShieldChip(
                                "zero_combo",
                                ctx.getString(R.string.combo_type_zero),
                                ShieldCategory.COMBO
                            )
                        }
                        "odd_combo" -> {
                            val scope = ctx.getString(
                                if (parityFirstOnly) R.string.combo_parity_scope_first else R.string.combo_parity_scope_total
                            )
                            WaveShieldChip(
                                "odd_combo",
                                "${ctx.getString(R.string.combo_type_odd)}（$scope）",
                                ShieldCategory.COMBO,
                                paramInt = if (parityFirstOnly) 0 else 1
                            )
                        }
                        "even_combo" -> {
                            val scope = ctx.getString(
                                if (parityFirstOnly) R.string.combo_parity_scope_first else R.string.combo_parity_scope_total
                            )
                            WaveShieldChip(
                                "even_combo",
                                "${ctx.getString(R.string.combo_type_even)}（$scope）",
                                ShieldCategory.COMBO,
                                paramInt = if (parityFirstOnly) 0 else 1
                            )
                        }
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

                // Dedicated Parameter Panel for Selected Combo Type
                if (curComboMode != "none" && curComboMode != "zero_combo") {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            when (curComboMode) {
                                "min_combo" -> NumericStepperRow(
                                    label = stringResource(R.string.combo_param_min_total),
                                    valueText = stringResource(R.string.combo_value_min_fmt, minTotalComboVal),
                                    onMinus = {
                                        minTotalComboVal = (minTotalComboVal - 1).coerceIn(1, 15)
                                        applyComboType("min_combo")
                                    },
                                    onPlus = {
                                        minTotalComboVal = (minTotalComboVal + 1).coerceIn(1, 15)
                                        applyComboType("min_combo")
                                    }
                                )
                                "min_first_combo" -> NumericStepperRow(
                                    label = stringResource(R.string.combo_param_min_first),
                                    valueText = stringResource(R.string.combo_value_min_fmt, minFirstComboVal),
                                    onMinus = {
                                        minFirstComboVal = (minFirstComboVal - 1).coerceIn(1, 12)
                                        applyComboType("min_first_combo")
                                    },
                                    onPlus = {
                                        minFirstComboVal = (minFirstComboVal + 1).coerceIn(1, 12)
                                        applyComboType("min_first_combo")
                                    }
                                )
                                "exact_combo" -> NumericStepperRow(
                                    label = stringResource(R.string.combo_param_exact_total),
                                    valueText = stringResource(R.string.combo_value_exact_fmt, exactTotalComboVal),
                                    onMinus = {
                                        exactTotalComboVal = (exactTotalComboVal - 1).coerceIn(1, 15)
                                        applyComboType("exact_combo")
                                    },
                                    onPlus = {
                                        exactTotalComboVal = (exactTotalComboVal + 1).coerceIn(1, 15)
                                        applyComboType("exact_combo")
                                    }
                                )
                                "exact_first_combo" -> NumericStepperRow(
                                    label = stringResource(R.string.combo_param_exact_first),
                                    valueText = stringResource(R.string.combo_value_exact_fmt, exactFirstComboVal),
                                    onMinus = {
                                        exactFirstComboVal = (exactFirstComboVal - 1).coerceIn(1, 12)
                                        applyComboType("exact_first_combo")
                                    },
                                    onPlus = {
                                        exactFirstComboVal = (exactFirstComboVal + 1).coerceIn(1, 12)
                                        applyComboType("exact_first_combo")
                                    }
                                )
                                "odd_combo", "even_combo" -> Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CompactChoicePill(
                                        label = stringResource(R.string.combo_parity_scope_first),
                                        selected = parityFirstOnly,
                                        onClick = {
                                            parityFirstOnly = true
                                            applyComboType(curComboMode)
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                    CompactChoicePill(
                                        label = stringResource(R.string.combo_parity_scope_total),
                                        selected = !parityFirstOnly,
                                        onClick = {
                                            parityFirstOnly = false
                                            applyComboType(curComboMode)
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }

                // Game-Accurate Structural Combo Modifiers (hidden when Zero Combo is selected!)
                AnimatedVisibility(visible = curComboMode != "zero_combo") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text(
                            text = stringResource(R.string.combo_extra_header),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        fun upsertSecondWave(n: Int) {
                            ActiveStrategyStore.upsertChip(
                                WaveShieldChip(
                                    "second_wave_combo",
                                    ctx.getString(R.string.combo_extra_second_wave, n),
                                    ShieldCategory.COMBO,
                                    paramInt = n
                                )
                            )
                        }

                        fun upsertSameElem(n: Int, targetAttr: Int) {
                            val mask = if (targetAttr in 0..5) (1 shl targetAttr) else 0
                            val attrTag = if (targetAttr in 0..5) LocaleHelper.elementLabel(ctx, targetAttr) else ""
                            ActiveStrategyStore.upsertChip(
                                WaveShieldChip(
                                    "same_elem_combos",
                                    if (attrTag.isNotEmpty()) "$attrTag ${ctx.getString(R.string.combo_extra_same_elem, n)}"
                                    else ctx.getString(R.string.combo_extra_same_elem, n),
                                    ShieldCategory.COMBO,
                                    paramInt = n,
                                    paramElementMask = mask
                                )
                            )
                        }

                        fun upsertEqualPair(a: Int, b: Int) {
                            val packed = (a and 0x0F) or ((b and 0x0F) shl 4)
                            val la = LocaleHelper.elementLabel(ctx, a)
                            val lb = LocaleHelper.elementLabel(ctx, b)
                            ActiveStrategyStore.upsertChip(
                                WaveShieldChip(
                                    "equal_pair_light_dark",
                                    ctx.getString(R.string.combo_extra_equal_pair, la, lb),
                                    ShieldCategory.COMBO,
                                    paramElementMask = packed
                                )
                            )
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val sameCLabel = stringResource(R.string.combo_extra_same_c)
                            CompactChoicePill(
                                label = sameCLabel,
                                selected = "same_combo_elements" in activeIds,
                                onClick = {
                                    onToggleWaveChip(WaveShieldChip("same_combo_elements", sameCLabel, ShieldCategory.COMBO))
                                }
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
                            val pairLabel = stringResource(
                                R.string.combo_extra_equal_pair,
                                LocaleHelper.elementLabel(ctx, pairElemA),
                                LocaleHelper.elementLabel(ctx, pairElemB)
                            )
                            CompactChoicePill(
                                label = pairLabel,
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
                                onMinus = {
                                    secondWaveVal = (secondWaveVal - 1).coerceIn(1, 6)
                                    upsertSecondWave(secondWaveVal)
                                },
                                onPlus = {
                                    secondWaveVal = (secondWaveVal + 1).coerceIn(1, 6)
                                    upsertSecondWave(secondWaveVal)
                                }
                            )
                        }
                        if ("same_elem_combos" in activeIds) {
                            NumericStepperRow(
                                label = stringResource(R.string.combo_same_elem_step),
                                valueText = stringResource(R.string.combo_value_exact_fmt, sameElemGroupsVal),
                                onMinus = {
                                    sameElemGroupsVal = (sameElemGroupsVal - 1).coerceIn(2, 6)
                                    upsertSameElem(sameElemGroupsVal, sameElemTarget)
                                },
                                onPlus = {
                                    sameElemGroupsVal = (sameElemGroupsVal + 1).coerceIn(2, 6)
                                    upsertSameElem(sameElemGroupsVal, sameElemTarget)
                                }
                            )
                            Text(
                                text = stringResource(R.string.combo_same_elem_attr),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                CompactChoicePill(
                                    label = stringResource(R.string.puzzle_elem_any),
                                    selected = sameElemTarget < 0,
                                    onClick = {
                                        sameElemTarget = -1
                                        upsertSameElem(sameElemGroupsVal, -1)
                                    },
                                    modifier = Modifier.weight(1.65f).height(34.dp)
                                )
                                for (e in 0 until 6) {
                                    ElementColorTogglePill(
                                        elemIndex = e,
                                        selected = sameElemTarget == e,
                                        onClick = {
                                            sameElemTarget = e
                                            upsertSameElem(sameElemGroupsVal, e)
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                        if ("equal_pair_light_dark" in activeIds) {
                            Text(
                                text = stringResource(R.string.combo_equal_pair_pick),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                for (e in 0 until 6) {
                                    val selected = e == pairElemA || e == pairElemB
                                    ElementColorTogglePill(
                                        elemIndex = e,
                                        selected = selected,
                                        onClick = {
                                            if (e != pairElemA && e != pairElemB) {
                                                pairElemA = pairElemB
                                                pairElemB = e
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

        // ==================== 2. Element & Count Shield (Full Cross-Validation!) ====================
        SectionHeaderWithHelp(
            text = stringResource(R.string.section_elem_count_shield),
            onHelp = {
                onShowHelp(
                    ctx.getString(R.string.help_elem_count_title),
                    ctx.getString(R.string.help_elem_count_body)
                )
            }
        )
        ElevatedCard(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 2a. Min Distinct Attributes (changing clears Specific Required Attributes!)
                Text(
                    text = stringResource(R.string.elem_min_distinct_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val curMinDistinct = byId["min_distinct_elements"]?.paramInt ?: 0
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (cnt in listOf(0, 2, 3, 4, 5, 6)) {
                        val lbl = if (cnt == 0) {
                            stringResource(R.string.elem_min_distinct_any)
                        } else {
                            stringResource(R.string.elem_min_distinct_fmt, cnt)
                        }
                        CompactChoicePill(
                            label = lbl,
                            selected = curMinDistinct == cnt,
                            onClick = {
                                if (curMinDistinct != cnt) {
                                    // Clear specific required colors whenever attribute count changes!
                                    ActiveStrategyStore.replaceGroup(legacyReqIds, null)
                                }
                                if (cnt == 0) {
                                    ActiveStrategyStore.removeChip("min_distinct_elements")
                                } else {
                                    ActiveStrategyStore.upsertChip(
                                        WaveShieldChip(
                                            id = "min_distinct_elements",
                                            label = ctx.getString(R.string.elem_min_distinct_fmt, cnt),
                                            category = ShieldCategory.COUNT_AND_ELEMENT,
                                            paramInt = cnt
                                        )
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // 2b. Required Attributes + Independent Enchanted Switch (mutually exclusive with Forbid All!)
                Text(
                    text = stringResource(R.string.elem_req_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val reqMaskFromChip = byId["require_elements"]?.paramElementMask ?: 0
                val reqEnchFromChip = (byId["require_elements"]?.paramInt ?: 0) == 1 ||
                    "five_elements_enchanted" in activeIds ||
                    (0..5).any { "require_ench_$it" in activeIds }
                val effectiveReqMask = remember(activeWaveChips) {
                    var m = reqMaskFromChip
                    if ("three_elements" in activeIds) m = m or 0x07
                    if ("five_elements" in activeIds || "five_elements_enchanted" in activeIds) m = m or 0x1F
                    for (e in 0..5) {
                        if ("require_elem_$e" in activeIds || "require_ench_$e" in activeIds) m = m or (1 shl e)
                    }
                    m
                }

                val antiMask = readMaskForLimit("anti_first_wave")
                val forbidMask = readMaskForLimit("forbid_all")
                val clearMask = readMaskForLimit("clear_all_element")

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    for (e in 0 until 6) {
                        val isOn = (effectiveReqMask and (1 shl e)) != 0
                        ElementColorTogglePill(
                            elemIndex = e,
                            selected = isOn,
                            suffix = if (isOn && reqEnchFromChip) "+" else "",
                            onClick = {
                                val nextMask = effectiveReqMask xor (1 shl e)
                                val adding = (nextMask and (1 shl e)) != 0
                                if (adding) {
                                    // Clear Forbid-All on this color to prevent impossible conflict!
                                    val newForbid = forbidMask and (1 shl e).inv()
                                    if (newForbid != forbidMask) {
                                        writeMaskForLimit("forbid_all", ctx.getString(R.string.elem_limit_forbid), newForbid)
                                    }
                                    // Keep Min Distinct consistent if selected colors exceed it!
                                    val bitCnt = Integer.bitCount(nextMask)
                                    if (curMinDistinct in 2..5 && bitCnt > curMinDistinct) {
                                        ActiveStrategyStore.upsertChip(
                                            WaveShieldChip(
                                                id = "min_distinct_elements",
                                                label = ctx.getString(R.string.elem_min_distinct_fmt, bitCnt),
                                                category = ShieldCategory.COUNT_AND_ELEMENT,
                                                paramInt = bitCnt
                                            )
                                        )
                                    }
                                }
                                writeRequiredElements(nextMask, reqEnchFromChip && nextMask != 0)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.elem_req_ench_switch),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (effectiveReqMask != 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )
                    Switch(
                        checked = reqEnchFromChip && effectiveReqMask != 0,
                        enabled = effectiveReqMask != 0,
                        onCheckedChange = { ench -> writeRequiredElements(effectiveReqMask, ench) }
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // 2c. Attribute Restrictions (Strictly Mutually Exclusive Per Color!)
                Text(
                    text = stringResource(R.string.elem_forbid_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val antiCnt = Integer.bitCount(antiMask)
                val forbidCnt = Integer.bitCount(forbidMask)
                val clearCnt = Integer.bitCount(clearMask)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        Triple(0, stringResource(R.string.elem_limit_anti), antiCnt),
                        Triple(1, stringResource(R.string.elem_limit_forbid), forbidCnt),
                        Triple(2, stringResource(R.string.elem_limit_clear_all), clearCnt)
                    ).forEach { (idx, lbl, cnt) ->
                        CompactChoicePill(
                            label = if (cnt > 0) "$lbl ($cnt)" else lbl,
                            selected = elemLimitMode == idx,
                            onClick = { elemLimitMode = idx },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                val curLimitMask = when (elemLimitMode) {
                    0 -> antiMask
                    1 -> forbidMask
                    else -> clearMask
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    for (e in 0 until 6) {
                        val bit = 1 shl e
                        val isOn = (curLimitMask and bit) != 0
                        ElementColorTogglePill(
                            elemIndex = e,
                            selected = isOn,
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
                                            // Forbid-All conflicts with Required Attribute on the same color!
                                            if ((effectiveReqMask and bit) != 0) {
                                                val prunedReq = effectiveReqMask and bit.inv()
                                                writeRequiredElements(prunedReq, reqEnchFromChip && prunedReq != 0)
                                            }
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

                // 2d. Orb Count & Race Shields (Digital Orb Count <-> Full Clear mutually exclusive!)
                Text(
                    text = stringResource(R.string.elem_count_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val digLabel = stringResource(R.string.shield_digital_orbs_fmt, digitalOrbs)
                    CompactChoicePill(
                        label = digLabel,
                        selected = "digital_orb_count" in activeIds,
                        onClick = {
                            if ("digital_orb_count" in activeIds) {
                                ActiveStrategyStore.removeChip("digital_orb_count")
                            } else {
                                ActiveStrategyStore.removeChip("first_wave_full_clear")
                                ActiveStrategyStore.removeChip("zero_combo")
                                ActiveStrategyStore.upsertChip(
                                    WaveShieldChip("digital_orb_count", digLabel, ShieldCategory.COUNT_AND_ELEMENT, paramInt = digitalOrbs)
                                )
                            }
                        }
                    )
                    val fullClearLabel = stringResource(R.string.shield_full_clear)
                    CompactChoicePill(
                        label = fullClearLabel,
                        selected = "first_wave_full_clear" in activeIds,
                        onClick = {
                            if ("first_wave_full_clear" in activeIds) {
                                ActiveStrategyStore.removeChip("first_wave_full_clear")
                            } else {
                                ActiveStrategyStore.removeChip("digital_orb_count")
                                ActiveStrategyStore.removeChip("zero_combo")
                                ActiveStrategyStore.upsertChip(
                                    WaveShieldChip("first_wave_full_clear", fullClearLabel, ShieldCategory.COUNT_AND_ELEMENT)
                                )
                            }
                        }
                    )
                    val enchOrbLabel = stringResource(R.string.shield_any_enchanted_orb)
                    CompactChoicePill(
                        label = enchOrbLabel,
                        selected = "any_enchanted_orb_shield" in activeIds,
                        onClick = {
                            if ("any_enchanted_orb_shield" in activeIds) {
                                ActiveStrategyStore.removeChip("any_enchanted_orb_shield")
                            } else {
                                ActiveStrategyStore.removeChip("zero_combo")
                                ActiveStrategyStore.upsertChip(
                                    WaveShieldChip("any_enchanted_orb_shield", enchOrbLabel, ShieldCategory.COUNT_AND_ELEMENT)
                                )
                            }
                        }
                    )
                    val raceLabel = stringResource(R.string.shield_race_orb)
                    CompactChoicePill(
                        label = raceLabel,
                        selected = "race_orb_shield" in activeIds,
                        onClick = {
                            if ("race_orb_shield" in activeIds) {
                                ActiveStrategyStore.removeChip("race_orb_shield")
                            } else {
                                ActiveStrategyStore.removeChip("zero_combo")
                                ActiveStrategyStore.upsertChip(
                                    WaveShieldChip("race_orb_shield", raceLabel, ShieldCategory.COUNT_AND_ELEMENT)
                                )
                            }
                        }
                    )
                    val charOrbFirstLabel = stringResource(R.string.shield_char_orb_first)
                    CompactChoicePill(
                        label = charOrbFirstLabel,
                        selected = "char_orb_first_wave" in activeIds,
                        onClick = {
                            if ("char_orb_first_wave" in activeIds) {
                                ActiveStrategyStore.removeChip("char_orb_first_wave")
                            } else {
                                ActiveStrategyStore.removeChip("zero_combo")
                                ActiveStrategyStore.upsertChip(
                                    WaveShieldChip("char_orb_first_wave", charOrbFirstLabel, ShieldCategory.COUNT_AND_ELEMENT)
                                )
                            }
                        }
                    )
                    val forbidWeakLabel = stringResource(R.string.shield_forbid_weakened)
                    CompactChoicePill(
                        label = forbidWeakLabel,
                        selected = "forbid_weakened_orb" in activeIds,
                        onClick = {
                            onToggleWaveChip(
                                WaveShieldChip("forbid_weakened_orb", forbidWeakLabel, ShieldCategory.COUNT_AND_ELEMENT)
                            )
                        }
                    )
                    val luminousLabel = stringResource(R.string.shield_luminous_orb)
                    CompactChoicePill(
                        label = luminousLabel,
                        selected = "luminous_orb_shield" in activeIds,
                        onClick = {
                            if ("luminous_orb_shield" in activeIds) {
                                ActiveStrategyStore.removeChip("luminous_orb_shield")
                            } else {
                                ActiveStrategyStore.removeChip("zero_combo")
                                ActiveStrategyStore.upsertChip(
                                    WaveShieldChip("luminous_orb_shield", luminousLabel, ShieldCategory.COUNT_AND_ELEMENT)
                                )
                            }
                        }
                    )
                }
                if ("digital_orb_count" in activeIds) {
                    NumericStepperRow(
                        label = stringResource(R.string.shield_digital_orbs_step),
                        valueText = "$digitalOrbs ${stringResource(R.string.unit_orbs)}",
                        onMinus = {
                            digitalOrbs = (digitalOrbs - 1).coerceIn(6, 30)
                            ActiveStrategyStore.upsertChip(
                                WaveShieldChip(
                                    "digital_orb_count",
                                    ctx.getString(R.string.shield_digital_orbs_fmt, digitalOrbs),
                                    ShieldCategory.COUNT_AND_ELEMENT,
                                    paramInt = digitalOrbs
                                )
                            )
                        },
                        onPlus = {
                            digitalOrbs = (digitalOrbs + 1).coerceIn(6, 30)
                            ActiveStrategyStore.upsertChip(
                                WaveShieldChip(
                                    "digital_orb_count",
                                    ctx.getString(R.string.shield_digital_orbs_fmt, digitalOrbs),
                                    ShieldCategory.COUNT_AND_ELEMENT,
                                    paramInt = digitalOrbs
                                )
                            )
                        }
                    )
                }
            }
        }

        // ==================== 3. Puzzle & Shape Shield (Concise Labels + Multi-Cross Color Picker!) ====================
        SectionHeaderWithHelp(
            text = stringResource(R.string.section_puzzle_shape_shield),
            onHelp = {
                onShowHelp(
                    ctx.getString(R.string.help_puzzle_shape_title),
                    ctx.getString(R.string.help_puzzle_shape_body)
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
                val puzzleEnch = when {
                    pChip != null -> (pChip.paramInt and 2) != 0
                    "enchanted_puzzle" in activeIds -> true
                    else -> false
                }
                val puzzleElemEnc = if (pChip != null) (pChip.paramInt ushr 2) and 0x0F else 0
                val puzzleElem = if (puzzleElemEnc in 1..6) puzzleElemEnc - 1 else -1
                val puzzleShapeId = if (pChip != null) (pChip.paramInt ushr 8) and 0x0F else 0

                fun savePuzzle(mode: Int, ench: Boolean, elem: Int, shapeId: Int) {
                    if (mode == 0) {
                        ActiveStrategyStore.replaceGroup(WaveShieldCatalog.PUZZLE_EXCLUSIVE_IDS, null)
                        return
                    }
                    val strictBit = if (mode == 2) 1 else 0
                    val enchBit = if (ench) 2 else 0
                    val elemBits = if (elem in 0..5) ((elem + 1) shl 2) else 0
                    val shapeBits = (shapeId and 0x0F) shl 8
                    val packed = strictBit or enchBit or elemBits or shapeBits

                    val modeName = ctx.getString(if (mode == 2) R.string.puzzle_mode_strict else R.string.puzzle_mode_normal)
                    val parts = buildList {
                        add(modeName)
                        if (ench) add(ctx.getString(R.string.ov_puzzle_ench))
                        if (elem in 0..5) add(LocaleHelper.elementLabel(ctx, elem))
                        if (shapeId > 0) add(LocaleHelper.puzzleShapeLabel(ctx, shapeId))
                    }
                    ActiveStrategyStore.replaceGroup(
                        WaveShieldCatalog.PUZZLE_EXCLUSIVE_IDS,
                        WaveShieldChip(
                            id = "puzzle_shield",
                            label = parts.joinToString("·"),
                            category = ShieldCategory.SHAPE_AND_PUZZLE,
                            paramInt = packed
                        )
                    )
                }

                // Crisp 1-row Puzzle Mode selector (Zero bloated parenthetical text!)
                Text(
                    text = stringResource(R.string.puzzle_mode_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CompactChoicePill(
                        label = stringResource(R.string.puzzle_mode_off),
                        selected = puzzleMode == 0,
                        onClick = { savePuzzle(0, false, -1, 0) },
                        modifier = Modifier.weight(1f)
                    )
                    CompactChoicePill(
                        label = stringResource(R.string.puzzle_mode_normal),
                        selected = puzzleMode == 1,
                        onClick = { savePuzzle(1, puzzleEnch, puzzleElem, puzzleShapeId) },
                        modifier = Modifier.weight(1f)
                    )
                    CompactChoicePill(
                        label = stringResource(R.string.puzzle_mode_strict),
                        selected = puzzleMode == 2,
                        onClick = { savePuzzle(2, puzzleEnch, puzzleElem, puzzleShapeId) },
                        modifier = Modifier.weight(1f)
                    )
                }

                AnimatedVisibility(visible = puzzleMode > 0) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = stringResource(R.string.puzzle_ench_switch),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                                )
                                Switch(
                                    checked = puzzleEnch,
                                    onCheckedChange = { savePuzzle(puzzleMode, it, puzzleElem, puzzleShapeId) }
                                )
                            }

                            Text(
                                text = stringResource(R.string.puzzle_elem_label),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                CompactChoicePill(
                                    label = stringResource(R.string.puzzle_elem_any),
                                    selected = puzzleElem < 0,
                                    onClick = { savePuzzle(puzzleMode, puzzleEnch, -1, puzzleShapeId) },
                                    modifier = Modifier.weight(1.65f).height(34.dp)
                                )
                                for (e in 0 until 6) {
                                    ElementColorTogglePill(
                                        elemIndex = e,
                                        selected = puzzleElem == e,
                                        onClick = { savePuzzle(puzzleMode, puzzleEnch, e, puzzleShapeId) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

                            Text(
                                text = stringResource(R.string.puzzle_shape_label),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                for (shapeId in 0..6) {
                                    CompactChoicePill(
                                        label = LocaleHelper.puzzleShapeLabel(ctx, shapeId),
                                        selected = puzzleShapeId == shapeId,
                                        onClick = { savePuzzle(puzzleMode, puzzleEnch, puzzleElem, shapeId) }
                                    )
                                }
                            }
                        }
                    }
                }

                // Standalone Shape Shields + Multi-Cross Color Picker!
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = stringResource(R.string.shape_shield_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val crossChip = byId["cross_shield"] ?: byId["cross_shield_2"] ?: byId["cross_shield_3"]
                val crossRaw = crossChip?.paramInt ?: 0
                val curCrossCount = crossRaw and 0x0F
                val curCrossDistinct = (crossRaw and 0x20) != 0
                val curCrossColorsMask = (crossChip?.paramElementMask ?: 0) and 0x3F

                fun saveCross(count: Int, distinct: Boolean, colorsMask: Int) {
                    if (count <= 0) {
                        ActiveStrategyStore.replaceGroup(WaveShieldCatalog.CROSS_EXCLUSIVE_IDS, null)
                        return
                    }
                    val safeDistinct = (distinct && count >= 2) || Integer.bitCount(colorsMask) >= 2
                    val packed = (count and 0x0F) or (if (safeDistinct) 0x20 else 0)
                    val baseLbl = when (count) {
                        1 -> ctx.getString(R.string.cross_1)
                        2 -> ctx.getString(R.string.cross_2)
                        else -> ctx.getString(R.string.cross_3)
                    }
                    val colorNames = (0..5).filter { (colorsMask and (1 shl it)) != 0 }
                        .joinToString("") { LocaleHelper.elementLabel(ctx, it) }
                    val label = when {
                        colorNames.isNotEmpty() -> "$baseLbl·$colorNames"
                        safeDistinct -> "$baseLbl(≠)"
                        else -> baseLbl
                    }
                    ActiveStrategyStore.replaceGroup(
                        WaveShieldCatalog.CROSS_EXCLUSIVE_IDS,
                        WaveShieldChip(
                            id = "cross_shield",
                            label = label,
                            category = ShieldCategory.SHAPE_AND_PUZZLE,
                            paramInt = packed,
                            paramElementMask = colorsMask
                        )
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    listOf(
                        0 to stringResource(R.string.cross_off),
                        1 to stringResource(R.string.cross_1),
                        2 to stringResource(R.string.cross_2),
                        3 to stringResource(R.string.cross_3)
                    ).forEach { (cnt, lbl) ->
                        CompactChoicePill(
                            label = lbl,
                            selected = curCrossCount == cnt,
                            onClick = {
                                // Cross-validation: clear excess cross colors when reducing cross count!
                                val nextColors = if (cnt != curCrossCount && Integer.bitCount(curCrossColorsMask) > cnt) 0 else curCrossColorsMask
                                saveCross(cnt, curCrossDistinct && cnt >= 2, nextColors)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                AnimatedVisibility(visible = curCrossCount > 0) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (curCrossCount >= 2) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = stringResource(R.string.cross_distinct_switch),
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Switch(
                                        checked = curCrossDistinct,
                                        onCheckedChange = {
                                            // If turning OFF distinct colors while >=2 colors selected, clear colors for consistency
                                            val nextColors = if (!it && Integer.bitCount(curCrossColorsMask) >= 2) 0 else curCrossColorsMask
                                            saveCross(curCrossCount, it, nextColors)
                                        }
                                    )
                                }
                            }
                            Text(
                                text = stringResource(R.string.cross_colors_label, curCrossCount),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                for (e in 0 until 6) {
                                    val bit = 1 shl e
                                    val isOn = (curCrossColorsMask and bit) != 0
                                    ElementColorTogglePill(
                                        elemIndex = e,
                                        selected = isOn,
                                        onClick = {
                                            val nextColors = if (isOn) {
                                                curCrossColorsMask and bit.inv()
                                            } else if (Integer.bitCount(curCrossColorsMask) < curCrossCount) {
                                                curCrossColorsMask or bit
                                            } else {
                                                // At max capacity: drop the lowest existing bit so selection never exceeds curCrossCount!
                                                val lowestBit = curCrossColorsMask and -curCrossColorsMask
                                                (curCrossColorsMask and lowestBit.inv()) or bit
                                            }
                                            val nextDistinct = curCrossDistinct || Integer.bitCount(nextColors) >= 2
                                            saveCross(curCrossCount, nextDistinct, nextColors)
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        WaveShieldChip("l_shape", stringResource(R.string.shield_l_shape), ShieldCategory.SHAPE_AND_PUZZLE),
                        WaveShieldChip("t_shape", stringResource(R.string.shield_t_shape), ShieldCategory.SHAPE_AND_PUZZLE),
                        WaveShieldChip("square_2x2", stringResource(R.string.shield_sq_2x2), ShieldCategory.SHAPE_AND_PUZZLE),
                        WaveShieldChip("all_columns_ameliorate", stringResource(R.string.shield_all_cols), ShieldCategory.SHAPE_AND_PUZZLE)
                    ).forEach { chip ->
                        CompactChoicePill(
                            label = chip.label,
                            selected = chip.id in activeIds,
                            onClick = { onToggleWaveChip(chip) }
                        )
                    }
                }
            }
        }

        // ==================== 4. Path, Hazard & Step Shield ====================
        SectionHeaderWithHelp(
            text = stringResource(R.string.section_path_hazard_shield),
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
                    val lenLabel = if (len == 0) ctx.getString(R.string.ov_burn_len_max) else ctx.getString(R.string.ov_burn_len_6)
                    ActiveStrategyStore.upsertChip(
                        WaveShieldChip(
                            id = "zero_trail_burning",
                            label = "${ctx.getString(R.string.ov_burn_path)}($hurts/$lenLabel)",
                            category = ShieldCategory.HAZARD_AND_PATH,
                            paramInt = packed
                        )
                    )
                }
                SwitchSettingRow(
                    title = stringResource(R.string.burn_path_title),
                    subtitle = stringResource(R.string.burn_path_sub),
                    checked = burnOn,
                    onCheckedChange = { enabled ->
                        if (enabled) upsertBurn(maxBurnHurts, burnTrailLen)
                        else ActiveStrategyStore.removeChip("zero_trail_burning")
                    }
                )
                if (burnOn) {
                    NumericStepperRow(
                        label = stringResource(R.string.burn_max_hurts),
                        valueText = "$maxBurnHurts ${stringResource(R.string.unit_times)}",
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
                        CompactChoicePill(
                            label = stringResource(R.string.burn_len_max),
                            selected = burnTrailLen == 0,
                            onClick = {
                                burnTrailLen = 0
                                upsertBurn(maxBurnHurts, 0)
                            },
                            modifier = Modifier.weight(1f)
                        )
                        CompactChoicePill(
                            label = stringResource(R.string.burn_len_6),
                            selected = burnTrailLen == 6,
                            onClick = {
                                burnTrailLen = 6
                                upsertBurn(maxBurnHurts, 6)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val stepLbl = stringResource(R.string.shield_step_limit_fmt, stepLimitVal)
                    CompactChoicePill(
                        label = stepLbl,
                        selected = "step_limit" in activeIds,
                        onClick = {
                            onToggleWaveChip(
                                WaveShieldChip("step_limit", stepLbl, ShieldCategory.HAZARD_AND_PATH, paramInt = stepLimitVal)
                            )
                        }
                    )
                    listOf(
                        WaveShieldChip("trail_corrosion", stringResource(R.string.shield_trail_corrosion), ShieldCategory.HAZARD_AND_PATH, paramInt = 15),
                        WaveShieldChip("step_hurt_minimize", stringResource(R.string.shield_step_hurt), ShieldCategory.HAZARD_AND_PATH),
                        WaveShieldChip("char_orb_terrain", stringResource(R.string.shield_char_terrain), ShieldCategory.HAZARD_AND_PATH),
                        WaveShieldChip("weijian_burst", stringResource(R.string.shield_weijian_burst), ShieldCategory.HAZARD_AND_PATH)
                    ).forEach { chip ->
                        CompactChoicePill(
                            label = chip.label,
                            selected = chip.id in activeIds,
                            onClick = { onToggleWaveChip(chip) }
                        )
                    }
                }
                if ("step_limit" in activeIds) {
                    NumericStepperRow(
                        label = stringResource(R.string.shield_step_limit_step),
                        valueText = "≤ $stepLimitVal ${stringResource(R.string.unit_steps)}",
                        onMinus = {
                            stepLimitVal = (stepLimitVal - 1).coerceIn(2, 60)
                            ActiveStrategyStore.upsertChip(
                                WaveShieldChip(
                                    "step_limit",
                                    ctx.getString(R.string.shield_step_limit_fmt, stepLimitVal),
                                    ShieldCategory.HAZARD_AND_PATH,
                                    paramInt = stepLimitVal
                                )
                            )
                        },
                        onPlus = {
                            stepLimitVal = (stepLimitVal + 1).coerceIn(2, 60)
                            ActiveStrategyStore.upsertChip(
                                WaveShieldChip(
                                    "step_limit",
                                    ctx.getString(R.string.shield_step_limit_fmt, stepLimitVal),
                                    ShieldCategory.HAZARD_AND_PATH,
                                    paramInt = stepLimitVal
                                )
                            )
                        }
                    )
                }
            }
        }

        // ==================== 5. Standalone Skill Text Rule Tester (Placed at VERY BOTTOM!) ====================
        SectionHeaderWithHelp(
            text = stringResource(R.string.section_rule_tester),
            onHelp = {
                onShowHelp(
                    ctx.getString(R.string.help_rule_tester_title),
                    ctx.getString(R.string.help_rule_tester_body)
                )
            }
        )
        ElevatedCard(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = testSkillText,
                    onValueChange = { testSkillText = it },
                    label = { Text(stringResource(R.string.rule_tester_hint)) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        onClick = { testSkillText = ctx.getString(R.string.rule_tester_sample_text) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(stringResource(R.string.btn_sample), style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { if (testSkillText.isNotBlank()) onParseSkillText(testSkillText) },
                        enabled = testSkillText.isNotBlank()
                    ) {
                        Text(stringResource(R.string.btn_parse_import))
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun ElementColorTogglePill(
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
        shape = RoundedCornerShape(10.dp),
        color = if (selected) badgeColor else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) badgeColor else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = modifier.height(34.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 4.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false
            )
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
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(end = 8.dp)
        )
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

// ==================== Tab 3: Settings, Theme, Language, Slim Sliders & Countdown Dropdown ====================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTab(
    settings: AppSettings,
    benchmarkReport: String?,
    isBenchmarking: Boolean,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onRunNativeBenchmark: () -> Unit,
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
    var countdownMenuExpanded by remember { mutableStateOf(false) }

    val msUnit = stringResource(R.string.unit_ms)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Appearance, Theme Mode & Display Language
        SectionHeader(stringResource(R.string.section_appearance))
        ElevatedCard(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.theme_desc),
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
                            Text(
                                LocaleHelper.themeLabel(context, mode),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Text(
                    text = stringResource(R.string.language_title),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    AppLanguage.entries.forEachIndexed { idx, lang ->
                        SegmentedButton(
                            selected = settings.language == lang,
                            onClick = { onUpdateSettings { it.copy(language = lang) } },
                            shape = SegmentedButtonDefaults.itemShape(idx, AppLanguage.entries.size)
                        ) {
                            Text(
                                LocaleHelper.languageLabel(context, lang),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        // 2. Swipe Speed Presets + Slim Native Slider
        SectionHeader(stringResource(R.string.section_speed))
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
                        text = stringResource(R.string.speed_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${LocaleHelper.speedLabel(context, activeSpeed)} · ${settings.stepDurationMs} $msUnit",
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
                                    text = LocaleHelper.speedLabel(context, preset),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selected) Color(style.fg) else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                                Text(
                                    text = "${preset.stepDurationMs} $msUnit",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) Color(style.fg) else MaterialTheme.colorScheme.onSurfaceVariant
                                )
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
        }

        // 3. Solver Budget, Slim Native Sliders & Countdown Dropdown
        SectionHeaderWithHelp(
            text = stringResource(R.string.section_engine_steps),
            onHelp = {
                onShowHelp(
                    context.getString(R.string.help_engine_settings_title),
                    context.getString(R.string.help_engine_settings_body)
                )
            }
        )
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
                    label = { Text(stringResource(R.string.field_max_steps)) },
                    suffix = { Text(stringResource(R.string.unit_steps)) },
                    supportingText = {
                        Text(
                            if (maxStepsError) stringResource(R.string.error_max_steps)
                            else stringResource(R.string.support_max_steps, settings.maxSteps)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
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
                    value = settings.beamWidth.toFloat(),
                    valueRange = 60f..400f,
                    onValueChange = { v -> onUpdateSettings { it.copy(beamWidth = (v / 20f).roundToInt() * 20) } }
                )
                SliderSettingRow(
                    label = stringResource(R.string.slider_hold_delay),
                    valueText = "${settings.holdReplanDelayMs} $msUnit",
                    value = settings.holdReplanDelayMs.toFloat(),
                    valueRange = 200f..800f,
                    onValueChange = { v -> onUpdateSettings { it.copy(holdReplanDelayMs = (v / 20f).roundToLong() * 20L) } }
                )

                // Pre-Swipe Countdown Native Exposed Dropdown (pre-resolved with localized context so Popup never falls back to system locale!)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val countdownItems = remember(settings.language, context) {
                    listOf(0, 1, 2, 3, 5).map { sec ->
                        sec to if (sec == 0) {
                            context.getString(R.string.countdown_none)
                        } else {
                            context.getString(R.string.unit_seconds, sec)
                        }
                    }
                }
                val currentCountdownText = countdownItems.firstOrNull { it.first == settings.countdownSeconds }?.second
                    ?: context.getString(R.string.unit_seconds, settings.countdownSeconds)
                ExposedDropdownMenuBox(
                    expanded = countdownMenuExpanded,
                    onExpandedChange = { countdownMenuExpanded = !countdownMenuExpanded }
                ) {
                    OutlinedTextField(
                        value = currentCountdownText,
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        label = { Text(context.getString(R.string.slider_countdown)) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = countdownMenuExpanded)
                        },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = countdownMenuExpanded,
                        onDismissRequest = { countdownMenuExpanded = false }
                    ) {
                        CompositionLocalProvider(
                            LocalContext provides context,
                            LocalConfiguration provides context.resources.configuration
                        ) {
                            countdownItems.forEach { (sec, itemText) ->
                                DropdownMenuItem(
                                    text = { Text(itemText) },
                                    onClick = {
                                        onUpdateSettings { it.copy(countdownSeconds = sec) }
                                        countdownMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4. Mode & Control Switches
        SectionHeader(stringResource(R.string.section_modes))
        ElevatedCard(shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
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
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SwitchSettingRow(
                    title = stringResource(R.string.mode_nudge_title),
                    subtitle = stringResource(R.string.mode_nudge_sub),
                    checked = settings.holdReplanNudge,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(holdReplanNudge = v) } }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SwitchSettingRow(
                    title = stringResource(R.string.mode_diag_title),
                    subtitle = stringResource(R.string.mode_diag_sub),
                    checked = settings.allowDiagonalMove,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(allowDiagonalMove = v) } }
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
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(
                        text = stringResource(R.string.mode_question_mem_title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.mode_question_mem_sub),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
            }
        }

        // 5. Local Engine Benchmark
        SectionHeader(stringResource(R.string.section_benchmark))
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
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = stringResource(R.string.bench_title),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = stringResource(R.string.bench_sub),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    FilledTonalButton(
                        onClick = onRunNativeBenchmark,
                        enabled = !isBenchmarking
                    ) {
                        Text(
                            stringResource(if (isBenchmarking) R.string.btn_bench_running else R.string.btn_bench_run)
                        )
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
private fun SectionHeaderWithHelp(text: String, onHelp: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        HelpCircleButton(onClick = onHelp)
    }
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
            overflow = TextOverflow.Ellipsis,
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
    allowAlwaysClick: Boolean,
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
            enabled = !granted || allowAlwaysClick,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Text(
                if (granted) stringResource(R.string.perm_ready) else actionLabel,
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
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * Clean native-Android style slim SeekBar (4dp track + 18dp circular thumb) instead of
 * Material 3 1.3's oversized 16dp thick bar and vertical line thumb.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SlimNativeSlider(
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
            .height(28.dp),
        thumb = {
            Box(
                modifier = Modifier
                    .size(18.dp)
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
    Column(
        modifier = Modifier.padding(top = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
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
        SlimNativeSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange
        )
    }
}
